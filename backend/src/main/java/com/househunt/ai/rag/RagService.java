package com.househunt.ai.rag;

import com.househunt.ai.PromptSafety;
import com.househunt.ai.config.AiProperties;
import com.househunt.ai.rag.AskModels.AskFilters;
import com.househunt.ai.rag.AskModels.AskResponse;
import com.househunt.ai.rag.AskModels.Citation;
import com.househunt.ai.rag.AskModels.ModelAnswer;
import com.househunt.ai.web.AiUnavailableException;
import com.househunt.ai.web.AiUsageLogger;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/**
 * Feature 2: "Ask my house hunt". Retrieve (metadata pre-filter + cosine similarity in pgvector) -> answer only from
 * the retrieved houses -> keep only citations that point at retrieved houses.
 */
@Service
@ConditionalOnBooleanProperty("app.ai.enabled")
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final ChatClient chat;
    private final VectorStore vectorStore;
    private final AiProperties props;

    public RagService(ChatClient chat, VectorStore vectorStore, AiProperties props) {
        this.chat = chat;
        this.vectorStore = vectorStore;
        this.props = props;
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

    /** Keeps only ids that were actually retrieved (drops hallucinated ones), in the model's order, de-duplicated. */
    static List<Citation> citations(ModelAnswer answer, List<Document> docs, String question) {
        var byId = new LinkedHashMap<String, Document>();
        docs.forEach(d -> byId.put(d.getId(), d));
        var ids = new ArrayList<String>();
        if (answer.citedHouseIds() != null) answer.citedHouseIds().forEach(id -> ids.add(normalizeId(id)));
        // Also accept ids that only appear inline as [house:<id>].
        var m = java.util.regex.Pattern.compile("\\[house:([0-9a-fA-F-]{36})]").matcher(answer.answer());
        while (m.find()) ids.add(m.group(1).toLowerCase());

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
        var s = id.strip().toLowerCase();
        if (s.startsWith("[house:")) s = s.substring(7);
        if (s.startsWith("house:")) s = s.substring(6);
        if (s.endsWith("]")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
