package com.househunt.ai.rag;

import com.househunt.ai.ContactRedactor;
import com.househunt.ai.PromptSafety;
import com.househunt.ai.config.AiProperties;
import com.househunt.ai.rag.AskModels.AskFilters;
import com.househunt.ai.rag.AskModels.AskResponse;
import com.househunt.ai.rag.AskModels.Citation;
import com.househunt.ai.rag.AskModels.ModelAnswer;
import com.househunt.ai.web.AiUnavailableException;
import com.househunt.ai.web.AiUsageLogger;
import com.househunt.house.House;
import com.househunt.house.HouseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Feature 2: "Ask my house hunt". Retrieve (metadata pre-filter + cosine similarity in pgvector) -> answer only from
 * the retrieved houses -> keep only citations that are referenced inline in the answer and point at retrieved houses.
 *
 * <p>Retrieved chunk text is passed through {@link ContactRedactor} with each house's current contact name and phone
 * before it is put into the prompt or a citation, so documents indexed before the F-30 fix (which had a
 * {@code Contact:} line) and names typed into notes never reach the provider or an MCP client.
 */
@Service
@ConditionalOnBooleanProperty("app.ai.enabled")
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final ChatClient chat;
    private final VectorStore vectorStore;
    private final AiProperties props;
    private final HouseRepository houses;

    public RagService(ChatClient chat, VectorStore vectorStore, AiProperties props, HouseRepository houses) {
        this.chat = chat;
        this.vectorStore = vectorStore;
        this.props = props;
        this.houses = houses;
    }

    public AskResponse ask(String question, AskFilters filters) {
        if (question == null || question.isBlank()) throw new IllegalArgumentException("question must not be blank");
        if (question.length() > props.maxQuestionChars()) {
            throw new IllegalArgumentException("question is longer than " + props.maxQuestionChars() + " characters");
        }
        List<Document> docs;
        long started = System.nanoTime();
        try {
            var search = SearchRequest.builder()
                    .query(question)
                    .topK(props.rag().topK())
                    .similarityThreshold(props.rag().similarityThreshold())
                    .filterExpression(AskPrompts.filter(filters))
                    .build();
            docs = vectorStore.similaritySearch(search);
            if (docs != null && !docs.isEmpty()) docs = redacted(docs, contactsOf(docs));
        } catch (RuntimeException e) {
            throw new AiUnavailableException("Search over your houses failed", e);
        }
        if (docs == null || docs.isEmpty()) {
            // Nothing relevant: answer without spending an LLM call.
            return new AskResponse(AskPrompts.I_DONT_KNOW, List.of(), false, 0);
        }

        var prompt = AskPrompts.build(question, docs, PromptSafety.nonce());
        ModelAnswer answer;
        try {
            var result = chat.prompt()
                    .system(prompt.system())
                    .user(prompt.user())
                    .options(ChatOptions.builder().temperature(0.1).maxTokens(props.maxOutputTokens()))
                    .call()
                    .responseEntity(ModelAnswer.class);
            AiUsageLogger.log("ask", result.response(), started);
            answer = result.entity();
        } catch (RuntimeException e) {
            throw new AiUnavailableException("Answering failed", e);
        }
        if (answer == null || answer.answer() == null || answer.answer().isBlank()) {
            return new AskResponse(AskPrompts.I_DONT_KNOW, List.of(), false, docs.size());
        }
        var citations = citations(answer, docs, question);
        return new AskResponse(answer.answer().strip(), citations, !citations.isEmpty(), docs.size());
    }

    /** Current contact name/phone per retrieved house id (houses deleted meanwhile are simply absent). */
    private Map<String, House> contactsOf(List<Document> docs) {
        var ids = new ArrayList<UUID>();
        for (var d : docs) {
            try {
                ids.add(UUID.fromString(d.getId()));
            } catch (IllegalArgumentException ignored) {
                // not a house document; scrubbed with the generic rules only
            }
        }
        var out = new HashMap<String, House>();
        if (!ids.isEmpty()) houses.findAllById(ids).forEach(h -> out.put(h.getId().toString(), h));
        return out;
    }

    /**
     * The retrieved documents as they may be shown to the provider (and, as citations, to MCP clients): chunk text
     * and label scrubbed by {@link ContactRedactor#scrubStoredText}, id, score and other metadata unchanged.
     */
    static List<Document> redacted(List<Document> docs, Map<String, House> contacts) {
        var out = new ArrayList<Document>(docs.size());
        for (var d : docs) {
            var h = contacts.get(d.getId());
            var name = h == null ? null : h.getContactName();
            var phone = h == null ? null : h.getContactPhone();
            var text = ContactRedactor.scrubStoredText(d.getText() == null ? "" : d.getText(), name, phone);
            var metadata = new HashMap<String, Object>(d.getMetadata());
            var label = metadata.get("label");
            if (label instanceof String s) metadata.put("label", ContactRedactor.forContact(name, phone).freeText(s));
            out.add(d.mutate().text(text).metadata(metadata).build());
        }
        return out;
    }

    /** An inline citation marker: {@code [house:<id>]}, also {@code [house: <id>]} and {@code [house:<a>, house:<b>]}. */
    private static final Pattern INLINE_MARKER = Pattern.compile("\\[house:([^\\[\\]\\n]{1,400})]", Pattern.CASE_INSENSITIVE);
    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /**
     * The citations of an answer, enforcing the product rule "a house is cited only where the answer states a fact
     * about it": the cited houses are the ids referenced inline as {@code [house:<id>]} in the answer text, in order of
     * first appearance, de-duplicated, and only those that were actually retrieved (hallucinated ids are dropped).
     *
     * <p>Ids the model lists in {@code citedHouseIds} without referencing them inline are dropped: the list is only a
     * fallback for an answer that carries no inline marker at all (some models fill the list but forget the markers;
     * without it such a grounded answer would lose every citation). The refusal sentence (curly apostrophes folded,
     * see {@link #isRefusal}) never has citations.
     */
    static List<Citation> citations(ModelAnswer answer, List<Document> docs, String question) {
        var text = answer.answer() == null ? "" : answer.answer();
        if (text.isBlank() || isRefusal(text)) return List.of();

        var byId = new LinkedHashMap<String, Document>();
        docs.forEach(d -> byId.put(d.getId().toLowerCase(Locale.ROOT), d));

        var ids = new LinkedHashSet<String>(inlineIds(text));
        int listedNotInline = 0;
        if (ids.isEmpty()) {
            if (answer.citedHouseIds() != null) {
                for (var id : answer.citedHouseIds()) {
                    var n = normalizeId(id);
                    if (!n.isEmpty()) ids.add(n);
                }
            }
        } else if (answer.citedHouseIds() != null) {
            for (var id : answer.citedHouseIds()) {
                var n = normalizeId(id);
                if (!n.isEmpty() && !ids.contains(n)) listedNotInline++;
            }
        }

        var out = new ArrayList<Citation>();
        int dropped = 0;
        for (var id : ids) {
            var doc = byId.get(id);
            if (doc == null) {
                dropped++;
                continue;
            }
            var label = String.valueOf(doc.getMetadata().getOrDefault("label", ""));
            out.add(new Citation(UUID.fromString(id), label, AskPrompts.snippet(doc.getText(), question, 240)));
        }
        if (dropped > 0) log.info("ask: dropped {} citation(s) that were not in the retrieved context", dropped);
        if (listedNotInline > 0) {
            log.info("ask: dropped {} citedHouseIds not referenced inline", listedNotInline);
        }
        return out;
    }

    /**
     * Whether the answer is the exact refusal sentence, after trimming and folding curly single quotes (U+2018, U+2019)
     * to {@code '} so "I don\u2019t know ..." is recognised the same way the eval scorer recognises it.
     */
    static boolean isRefusal(String answer) {
        if (answer == null) return false;
        return AskPrompts.I_DONT_KNOW.equals(foldApostrophes(answer.strip()));
    }

    private static String foldApostrophes(String s) {
        return s.replace('\u2019', '\'').replace('\u2018', '\'');
    }

    /** Ids referenced inline as {@code [house:<id>]}, lower case, in order of first appearance. */
    static List<String> inlineIds(String answer) {
        var out = new LinkedHashSet<String>();
        if (answer == null) return List.of();
        var marker = INLINE_MARKER.matcher(answer);
        while (marker.find()) {
            var uuid = UUID_TEXT.matcher(marker.group(1));
            while (uuid.find()) out.add(uuid.group().toLowerCase(Locale.ROOT));
        }
        return List.copyOf(out);
    }

    private static String normalizeId(String id) {
        if (id == null) return "";
        var s = id.strip().toLowerCase(Locale.ROOT);
        if (s.startsWith("[house:")) s = s.substring(7);
        if (s.startsWith("house:")) s = s.substring(6);
        if (s.endsWith("]")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
