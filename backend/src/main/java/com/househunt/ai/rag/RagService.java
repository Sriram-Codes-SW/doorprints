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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Feature 2: "Ask my house hunt". Retrieve (metadata pre-filter + cosine similarity in pgvector) -> answer only from
 * the retrieved houses -> keep only citations that point at retrieved houses.
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

    /** Keeps only ids that were actually retrieved (drops hallucinated ones), in the model's order, de-duplicated. */
    static List<Citation> citations(ModelAnswer answer, List<Document> docs, String question) {
        var byId = new LinkedHashMap<String, Document>();
        docs.forEach(d -> byId.put(d.getId(), d));
        var ids = new ArrayList<String>();
        if (answer.citedHouseIds() != null) answer.citedHouseIds().forEach(id -> ids.add(normalizeId(id)));
        // Also accept ids that only appear inline as [house:<id>].
        var m = java.util.regex.Pattern.compile("\\[house:([0-9a-fA-F-]{36})]").matcher(answer.answer());
        while (m.find()) ids.add(m.group(1).toLowerCase(Locale.ROOT));

        var out = new ArrayList<Citation>();
        int dropped = 0;
        for (var id : ids.stream().distinct().toList()) {
            var doc = byId.get(id);
            if (doc == null) {
                dropped++;
                continue;
            }
            var label = String.valueOf(doc.getMetadata().getOrDefault("label", ""));
            out.add(new Citation(UUID.fromString(id), label, AskPrompts.snippet(doc.getText(), question, 240)));
        }
        if (dropped > 0) log.info("ask: dropped {} citation(s) that were not in the retrieved context", dropped);
        return out;
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
