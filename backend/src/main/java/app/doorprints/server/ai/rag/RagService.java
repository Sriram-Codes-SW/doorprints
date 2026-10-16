/*
 * Copyright 2026 Sriram (Sriram-Codes-SW)
 *
 * This file is part of Doorprints.
 *
 * Doorprints is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, version 3 of the License.
 *
 * Doorprints is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with Doorprints (the file LICENSE;
 * the file NOTICE has additional permissions under section 7). If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package app.doorprints.server.ai.rag;

import app.doorprints.server.ai.kind.AiKinds;
import app.doorprints.server.ai.AnswerText;
import app.doorprints.server.ai.ContactRedactor;
import app.doorprints.server.ai.PromptSafety;
import app.doorprints.server.ai.config.AiProperties;
import app.doorprints.server.ai.rag.AskModels.AskFilters;
import app.doorprints.server.ai.rag.AskModels.AskResponse;
import app.doorprints.server.ai.rag.AskModels.Citation;
import app.doorprints.server.ai.rag.AskModels.ModelAnswer;
import app.doorprints.server.ai.web.AiUnavailableException;
import app.doorprints.server.ai.web.AiUsageLogger;
import app.doorprints.server.common.BadRequestException;
import app.doorprints.server.house.House;
import app.doorprints.server.house.HouseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.beans.factory.annotation.Autowired;
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

    /**
     * Eval-only seams for the canary suite (S4b-BL-237, docs/ai/ai-design.md 8.6), package-private and null or false in
     * production: nothing reads them from configuration, a property or the environment. Only the test class
     * {@code AskCanarySeams} (same package, test sources) sets them, for one canary run. {@code canarySystemText}
     * rewrites the built system text before it is sent; {@code canaryListedIdsCount} lets the model's {@code citedHouseIds}
     * count as citations even when the answer states facts inline about other houses (the inline-marker rule bypassed).
     */
    static volatile java.util.function.UnaryOperator<String> canarySystemText;
    static volatile boolean canaryListedIdsCount;
    /**
     * Builds the whole prompt from the raw question and the retrieved records instead of {@link AskPrompts#build}
     * (canary {@code no-wrapping}: a fixed tag, no neutralisation, the "Records exist only between the tags" rule left out).
     * Null in production.
     */
    static volatile java.util.function.BiFunction<String, List<Document>, AskPrompts.Built> canaryPrompt;

    private final ChatClient chat;
    private final VectorStore vectorStore;
    private final AiProperties props;
    private final HouseRepository houses;
    /** The kinds this server has available now: a document written under another list is not read (S4b-BL-308). */
    private final AiKinds kinds;

    /** With the house alone available, as a test that names no list. */
    public RagService(ChatClient chat, VectorStore vectorStore, AiProperties props, HouseRepository houses) {
        this(chat, vectorStore, props, houses, AiKinds.OFF);
    }

    @Autowired
    public RagService(ChatClient chat, VectorStore vectorStore, AiProperties props, HouseRepository houses,
                      AiKinds kinds) {
        this.chat = chat;
        this.vectorStore = vectorStore;
        this.props = props;
        this.houses = houses;
        this.kinds = kinds;
    }

    /**
     * The documents that may still be read: one written under a kind that has left {@code app.kinds.available}
     * (its metadata {@code kind}, stamped by {@link KindDocuments#metadata}) is dropped, so a changed list never
     * serves a school's fees or grades until {@code POST /api/ai/reindex} has rewritten it (S4b-BL-308, T-I49). A house
     * document, or one stored before the stamp, has no kind and stays. Pure.
     */
    static List<Document> availableOnly(List<Document> docs, AiKinds kinds) {
        if (docs == null) return null;
        return docs.stream().filter(d -> !(d.getMetadata().get("kind") instanceof String k) || kinds.isAvailable(k)).toList();
    }

    /**
     * Answers a question about the user's own houses from retrieved records only (retrieve, then generate).
     * The question is embedded and the closest house documents are fetched, optionally narrowed by structured
     * filters; contact names and numbers are scrubbed from them ({@link #redacted}) before the chat model sees them.
     * With nothing retrieved the fixed "I don't know" answer is returned without calling the model. Citations are
     * validated by {@link #citations}.
     * @throws IllegalArgumentException if the question is blank or longer than the configured limit
     * @throws AiUnavailableException if retrieval or the model call fails
     */
    public AskResponse ask(String question, AskFilters filters) {
        return ask(question, filters, null);
    }

    /**
     * As {@link #ask(String, AskFilters)}, for one kind's candidates (S4b-BL-290, the AI scoping rule of docs/03 §12 and
     * §18.12). {@code kind} null is the call above, byte for byte (every document the store holds and the server's kinds
     * allow). A kind must be one of {@code app.kinds.available} (else a 400, as Extract answers) and then only that kind's
     * candidates are read: a built-in kind other than the house is also searched by the metadata {@code kind} its documents
     * carry, and every kind (the house and a kind with no stamp included) is checked against the stored row's own kind, so
     * the answer, its citations and the prompt hold that kind's records and no other. The refusal for nothing found is the
     * kind's own sentence.
     * @throws BadRequestException if the question is blank or too long, or the kind is not available here
     */
    public AskResponse ask(String question, AskFilters filters, String kind) {
        if (question == null || question.isBlank()) throw new BadRequestException("question must not be blank");
        if (question.length() > props.maxQuestionChars()) {
            throw new BadRequestException("question is longer than " + props.maxQuestionChars() + " characters");
        }
        if (kind != null) kinds.require(kind);
        // Stamped documents (a built-in kind other than the house) are narrowed in the store; the rest in memory.
        String stamp = kind == null || kinds.of(kind) == null ? null : kind;
        int topK = props.rag().topK();
        // topK is at most 20 (AiProperties.Rag), so the fetch is at most 80.
        int fetch = kind == null || stamp != null ? topK : topK * SCOPE_OVERFETCH;
        List<Document> docs;
        long started = System.nanoTime();
        try {
            var search = SearchRequest.builder()
                    .query(question)
                    .topK(fetch)
                    .similarityThreshold(props.rag().similarityThreshold())
                    .filterExpression(AskPrompts.filter(filters, null, stamp))
                    .build();
            docs = vectorStore.similaritySearch(search);
            List<Document> visited = VisitQuestions.isAbout(question) ? visitedDocs(question, filters, stamp) : null;
            Map<String, House> rows = null;
            if (kind != null) {
                rows = rowsOf(docs, visited);
                docs = inKind(docs, kind, rows);
                if (docs != null && docs.size() > topK) docs = docs.subList(0, topK);
                visited = inKind(visited, kind, rows);
            }
            if (visited != null) docs = withVisited(docs, visited);
            docs = availableOnly(docs, kinds);
            if (docs != null && !docs.isEmpty()) docs = redacted(docs, rows != null ? rows : contactsOf(docs));
        } catch (RuntimeException e) {
            throw new AiUnavailableException("Search over your houses failed", e);
        }
        if (docs == null || docs.isEmpty()) {
            // Nothing relevant: answer without spending an LLM call.
            return new AskResponse(refusalFor(kind), List.of(), false, 0);
        }

        // Ids only (never the question, the text, notes, names or phones): which houses reach the model, in order.
        if (log.isDebugEnabled()) {
            log.debug("ask retrieved {} houses: {}", docs.size(), docs.stream().map(Document::getId).toList());
        }
        var prompt = builtPrompt(question, docs, PromptSafety.nonce(), kinds);
        ModelAnswer answer;
        try {
            var result = chat.prompt()
                    .system(systemText(prompt.system()))
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
            return new AskResponse(refusalFor(kind), List.of(), false, docs.size());
        }
        return answered(answer, docs, question);
    }

    /** How many documents the visited search asks for: all of them in any realistic hunt, so recency can decide. */
    static final int VISITED_FETCH_MAX = 200;

    /**
     * The documents of the houses for a question about visits (S4b-BL-194 item 2: vector similarity alone returned 20
     * houses without one of the two visited, because most documents say "not visited yet"): ONE search with the same
     * question and filters plus the document metadata {@code visited == true} (or {@code false} for a negated
     * question), no similarity threshold, up to {@value #VISITED_FETCH_MAX} documents. The visited ones are sorted by
     * {@code lastVisit}, newest first; the unvisited ones stay in the store's order (most similar first). A store
     * indexed before this metadata existed has no such documents, so the ordinary result stands (re-index once).
     */
    private List<Document> visitedDocs(String question, AskFilters filters, String stamp) {
        boolean negated = VisitQuestions.isNegated(question);
        var found = vectorStore.similaritySearch(SearchRequest.builder()
                .query(question)
                .topK(VISITED_FETCH_MAX)
                .similarityThreshold(0.0)
                .filterExpression(AskPrompts.filter(filters, !negated, stamp))
                .build());
        if (found == null) return List.of();
        return negated ? found : byLastVisit(found);
    }

    private List<Document> withVisited(List<Document> similar, List<Document> visited) {
        return withVisited(similar == null ? List.of() : similar, visited, props.rag().topK());
    }

    /** The documents by metadata {@code lastVisit}, newest first; equal ones by id, a missing or odd value last. Pure. */
    static List<Document> byLastVisit(List<Document> docs) {
        return docs.stream().sorted(java.util.Comparator
                .comparingLong((Document d) -> -lastVisit(d)).thenComparing(Document::getId)).toList();
    }

    private static long lastVisit(Document d) {
        return d.getMetadata().get("lastVisit") instanceof Number n ? n.longValue() : Long.MIN_VALUE + 1;
    }

    /** The visited documents first, then the similar ones not among them, at most {@code cap}. Pure. */
    static List<Document> withVisited(List<Document> similar, List<Document> visited, int cap) {
        var out = new LinkedHashMap<String, Document>();
        for (var d : visited) out.putIfAbsent(d.getId(), d);
        for (var d : similar) out.putIfAbsent(d.getId(), d);
        return out.values().stream().limit(cap).toList();
    }

    /**
     * The response for an answer the model returned: the text cleaned by {@link AnswerText#clean} against the retrieved
     * (already redacted) records, then the citations of that text. Pure, unit-tested.
     */
    static AskResponse answered(ModelAnswer answer, List<Document> docs, String question) {
        var records = new StringBuilder();
        for (var d : docs) records.append(d.getText()).append('\n');
        var text = AnswerText.clean(answer.answer().strip(), records.toString());
        var citations = citations(new ModelAnswer(text, answer.citedHouseIds()), docs, question);
        return new AskResponse(text, citations, !citations.isEmpty(), docs.size());
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

    /** How many times the top-K a search fetches when the kind cannot be narrowed in the store (the house, a kind with no stamp). */
    static final int SCOPE_OVERFETCH = 4;

    /** The stored rows of the documents of both lists (either may be null), by id: the authority on which kind a document is of. */
    private Map<String, House> rowsOf(List<Document> docs, List<Document> visited) {
        var all = new ArrayList<Document>();
        if (docs != null) all.addAll(docs);
        if (visited != null) all.addAll(visited);
        return contactsOf(all);
    }

    /**
     * The documents whose stored row is of {@code kind}, in order. A document with no row (its id is not a candidate's, or the
     * row is gone) cannot be shown to be of the kind and is dropped. Null stays null. Pure given the rows.
     */
    static List<Document> inKind(List<Document> docs, String kind, Map<String, House> rows) {
        if (docs == null) return null;
        return docs.stream().filter(d -> {
            var row = rows.get(d.getId());
            return row != null && kind.equals(row.getKind());
        }).toList();
    }

    /** The sentence for an answer with nothing to go on: the kind file's own ({@link AiKinds#readAs}: the house's for no kind). */
    private String refusalFor(String kind) {
        return kinds.readAs(kind).refusal();
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
        if (ids.isEmpty() || canaryListedIdsCount) {
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

    /** The prompt as sent: {@link AskPrompts#build} with the call's nonce, unless the no-wrapping canary builds it. */
    static AskPrompts.Built builtPrompt(String question, List<Document> docs, String nonce) {
        var canary = canaryPrompt;
        return canary == null ? AskPrompts.build(question, docs, nonce) : canary.apply(question, docs);
    }

    /** As above, in the words of the docs' kind when they all have one ({@link AskPrompts#build(String, List, String, AiKinds)}). */
    static AskPrompts.Built builtPrompt(String question, List<Document> docs, String nonce, AiKinds kinds) {
        var canary = canaryPrompt;
        return canary == null ? AskPrompts.build(question, docs, nonce, kinds) : canary.apply(question, docs);
    }

    /** The system text as sent: the built one, unless a canary rewrites it. */
    static String systemText(String built) {
        var canary = canarySystemText;
        return canary == null ? built : canary.apply(built);
    }

    /**
     * Whether the answer is the exact refusal sentence (the house's or a kind's, S4b-BL-269), after trimming and folding curly single quotes (U+2018, U+2019)
     * to {@code '} so "I don\u2019t know ..." is recognised the same way the eval scorer recognises it.
     */
    static boolean isRefusal(String answer) {
        if (answer == null) return false;
        var s = foldApostrophes(answer.strip());
        return AskPrompts.I_DONT_KNOW.equals(s) || AiKinds.isKindRefusal(s);
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

    /**
     * Lower-cases an id the model listed and strips a leading {@code [house:} or {@code house:} and a trailing
     * bracket, so a loosely formatted id still matches.
     */
    private static String normalizeId(String id) {
        if (id == null) return "";
        var s = id.strip().toLowerCase(Locale.ROOT);
        if (s.startsWith("[house:")) s = s.substring(7);
        if (s.startsWith("house:")) s = s.substring(6);
        if (s.endsWith("]")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
