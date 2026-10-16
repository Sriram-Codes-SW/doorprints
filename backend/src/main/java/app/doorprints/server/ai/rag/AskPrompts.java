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

import app.doorprints.server.ai.PromptSafety;
import app.doorprints.server.ai.kind.AiKind;
import app.doorprints.server.ai.kind.AiKinds;
import app.doorprints.server.ai.rag.AskModels.AskFilters;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Pure helpers for the RAG prompt, the metadata filter and citation snippets (unit-tested). */
public final class AskPrompts {

    public static final String I_DONT_KNOW = "I don't know based on the houses you have saved.";

    private AskPrompts() {
    }

    /**
     * The system and user messages for one question.
     */
    public record Built(String system, String user) {
    }

    /**
     * Builds the question-answering prompt for a house: {@link #build(String, List, String, AiKind)} with the house kind
     * file's texts ({@link AiKinds#HOUSE}). The retrieved records go between tags carrying the per-call nonce, each headed by its
     * id. The system text limits the model to those records, fixes the refusal sentence, and asks for inline
     * {@code [house:<id>]} citations. Record text and question are neutralised so they cannot close the tags
     * ({@link PromptSafety}).
     */
    public static Built build(String question, List<Document> docs, String nonce) {
        return build(question, docs, nonce, AiKinds.HOUSE);
    }

    /**
     * As {@link #build(String, List, String)}, in the words of the kind every document belongs to (S4b-BL-269): when each
     * document was written under the same kind other than the house (its metadata {@code kind}, stamped by
     * {@code KindDocuments.metadata}) that {@code kinds} has available, that kind's system text, block name and refusal; for a
     * house, for documents of more than one kind and for none, the house kind file's, byte for byte what the house's prompt
     * was (the house pins). The records' markers stay {@code [house:<id>]} for every kind, since the answer's citations are
     * read by them.
     */
    public static Built build(String question, List<Document> docs, String nonce, AiKinds kinds) {
        var k = kindOf(docs, kinds);
        return build(question, docs, nonce, k == null ? AiKinds.HOUSE : k);
    }

    /** The prompt in the words of {@code k}: its Ask system text and the records in its block. */
    public static Built build(String question, List<Document> docs, String nonce, AiKind k) {
        var tag = k.records() + "-" + nonce;
        var context = new StringBuilder();
        for (var d : docs) {
            context.append("[house:").append(d.getId()).append("]\n")
                    .append(PromptSafety.neutralize(d.getText(), k.records())).append("\n\n");
        }
        var user = "<" + tag + ">\n" + context.toString().strip() + "\n</" + tag + ">\n\nQuestion: "
                + PromptSafety.neutralize(question, k.records());
        return new Built(k.askSystem().formatted(tag, PromptSafety.NEUTRAL_RULE), user);
    }

    /** The one kind other than the house that every document was written under, else null (read as the house). */
    static AiKind kindOf(List<Document> docs, AiKinds kinds) {
        if (docs == null || docs.isEmpty() || kinds == null) return null;
        AiKind found = null;
        for (var d : docs) {
            var id = d.getMetadata().get("kind");
            var k = id instanceof String s ? kinds.of(s) : null;
            if (k == null || (found != null && found != k)) return null;
            found = k;
        }
        return found;
    }

    /** Builds the pgvector metadata filter, or null when no filter was given. */
    public static Filter.Expression filter(AskFilters f) {
        return filter(f, null);
    }

    /**
     * As {@link #filter(AskFilters)}, and when {@code visited} is given also only the houses whose document metadata
     * {@code visited} equals it (a question about visits).
     */
    public static Filter.Expression filter(AskFilters f, Boolean visited) {
        return filter(f, visited, null);
    }

    /**
     * As {@link #filter(AskFilters, Boolean)}, and when {@code kind} is given also only the documents stamped with that
     * kind (S4b-BL-290; a document of a built-in kind other than the house carries it, {@code KindDocuments.metadata}). The
     * value is bound as a typed filter value, never part of a query text, and the caller has already checked it against
     * {@code app.kinds.available}.
     */
    public static Filter.Expression filter(AskFilters f, Boolean visited, String kind) {
        if (f == null && visited == null && kind == null) return null;
        var b = new FilterExpressionBuilder();
        FilterExpressionBuilder.Op op = kind == null ? null : b.eq("kind", kind);
        if (visited != null) op = and(b, op, b.eq("visited", visited));
        if (f == null) return op.build();
        if (f.status() != null) op = and(b, op, b.eq("status", f.status().name()));
        if (f.priceType() != null) op = and(b, op, b.eq("priceType", f.priceType()));
        if (f.maxPrice() != null) op = and(b, op, b.lte("price", f.maxPrice()));
        if (f.minBedrooms() != null) op = and(b, op, b.gte("bedrooms", f.minBedrooms()));
        if (f.minRating() != null) op = and(b, op, b.gte("rating", f.minRating()));
        return op == null ? null : op.build();
    }

    private static FilterExpressionBuilder.Op and(FilterExpressionBuilder b, FilterExpressionBuilder.Op left,
                                                  FilterExpressionBuilder.Op right) {
        return left == null ? right : b.and(left, right);
    }

    /** The line of the document sharing most words with the question (falls back to the first line). */
    public static String snippet(String docText, String question, int max) {
        if (docText == null || docText.isBlank()) return "";
        var qWords = words(question);
        String best = null;
        int bestScore = -1;
        for (var line : docText.split("\n")) {
            if (line.isBlank()) continue;
            int score = 0;
            for (var w : words(line)) if (qWords.contains(w)) score++;
            if (score > bestScore) {
                best = line;
                bestScore = score;
            }
        }
        var s = best == null ? "" : best.strip();
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /**
     * Lower-case words longer than two characters, for the snippet overlap score.
     */
    private static Set<String> words(String s) {
        var out = new HashSet<String>();
        if (s == null) return out;
        for (var w : s.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) if (w.length() > 2) out.add(w);
        return out;
    }
}
