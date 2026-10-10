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

package app.doorprints.server.ai.eval;

import app.doorprints.server.ai.eval.EvalScorer.CaseResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Agreement across the trials of a case (S4b-BL-227), informational and never gated. Every scored trial after the first
 * is compared with trial 1 of the same case; a trial that failed at the provider is left out. The key of a trial is built
 * by the scorer: extract = the whole draft after the scorer's normalisation; ask = the set of cited houses (plus the
 * pass or fail, added in {@link #key}); plan = the set of stops and the fallback flag (the stop order is kept apart).
 */
final class Agreement {

    private Agreement() {
    }

    static final List<String> TYPES = List.of(EvalScorer.EXTRACT, EvalScorer.ASK, EvalScorer.PLAN);

    /** One type: cases run at least twice, those whose every trial agrees, and the trial pairs (each trial against trial 1). */
    record Row(String type, int cases, int agreeing, int pairs, int differing, int mostTrials) {
        Double value() {
            return cases == 0 ? null : (double) agreeing / cases;
        }
    }

    record Order(int cases, int agreeing) {
    }

    /** The draft after the scorer's normalisation, as canonical JSON: blank and missing fields are the same. */
    static String extractKey(Map<String, Object> draft) {
        var out = new TreeMap<String, Object>();
        draft.forEach((k, v) -> {
            var n = normalise(k, v);
            if (n != null) out.put(k, n);
        });
        return AddressVariants.canonicalJson(out);
    }

    private static Object normalise(String key, Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue() == Math.rint(n.doubleValue()) ? (Object) n.longValue() : n.doubleValue();
        if (v instanceof Boolean) return v;
        if (v instanceof Collection<?> list) {
            var items = new ArrayList<Object>();
            for (var item : list) {
                var n = normalise(key, item);
                if (n != null) items.add(n);
            }
            return items.isEmpty() ? null : items;
        }
        var s = String.valueOf(v);
        if (s.isBlank()) return null;
        return switch (key) {
            case "contactPhone" -> {
                var digits = s.replaceAll("\\D", "");
                yield digits.isEmpty() ? null : digits.length() > 10 ? digits.substring(digits.length() - 10) : digits;
            }
            case "listingUrl" -> {
                var url = s.strip().toLowerCase(Locale.ROOT);
                yield url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
            }
            default -> {
                var n = EvalScorer.normalize(s);
                yield n.isEmpty() ? null : n;
            }
        };
    }

    /** The structured fields of a draft, compared one by one across trials (S4b-BL-236); the order of the agreement table. */
    static final List<String> STRUCTURED_FIELDS = List.of("price", "priceType", "bedrooms", "areaSqft", "locality", "street",
            "contactPhone", "listingUrl");
    /** Compared as a set, apart from the structured tuple. */
    static final String AMENITIES = "amenities";
    /** Free text: reported apart and informational, because wording varies even when the facts agree. */
    static final List<String> FREE_TEXT_FIELDS = List.of("label", "notes", "address", "contactName");
    /** The row that says whether every structured field agreed at once. */
    static final String STRUCTURED_TUPLE = "all structured fields";

    /**
     * The draft's fields after the scorer's normalisation ({@link #normalise}: 28,000 and 28000 are one value, case,
     * punctuation and spacing folded, the phone's last ten digits, the link without its trailing slash), the amenities as
     * a sorted set. A blank or missing field is null. Only the fields named above are kept.
     */
    static Map<String, Object> extractFields(Map<String, Object> draft) {
        var out = new TreeMap<String, Object>();
        for (var f : STRUCTURED_FIELDS) out.put(f, normalise(f, draft.get(f)));
        for (var f : FREE_TEXT_FIELDS) out.put(f, normalise(f, draft.get(f)));
        var amenities = normalise(AMENITIES, draft.get(AMENITIES));
        out.put(AMENITIES, amenities instanceof Collection<?> c ? String.join(",", new TreeSet<>(c.stream().map(String::valueOf).toList())) : null);
        return out;
    }

    /** One field across the cases run at least twice: how many cases agreed on it in every scored trial. */
    record FieldRow(String field, int cases, int agreeing, boolean freeText) {
        int differing() {
            return cases - agreeing;
        }
    }

    /**
     * The field-level agreement of the extract cases (S4b-BL-236): per structured field, the amenities set and the whole
     * structured tuple, then the free-text fields apart. A case agrees on a field when every scored trial has the same
     * normalised value as trial 1 (both null counts as the same). Empty when no extract case ran twice.
     */
    static List<FieldRow> fieldRows(List<List<CaseResult>> perCase) {
        var compared = new ArrayList<List<CaseResult>>();
        for (var trials : perCase) {
            var list = scored(trials);
            if (list.size() >= 2 && EvalScorer.EXTRACT.equals(list.get(0).type) && list.stream().allMatch(r -> r.agreeFields != null)) {
                compared.add(list);
            }
        }
        if (compared.isEmpty()) return List.of();
        var out = new ArrayList<FieldRow>();
        for (var f : STRUCTURED_FIELDS) out.add(row(f, compared, List.of(f), false));
        out.add(row(AMENITIES, compared, List.of(AMENITIES), false));
        out.add(row(STRUCTURED_TUPLE, compared, STRUCTURED_FIELDS, false));
        for (var f : FREE_TEXT_FIELDS) out.add(row(f, compared, List.of(f), true));
        return out;
    }

    private static FieldRow row(String name, List<List<CaseResult>> compared, List<String> fields, boolean freeText) {
        int agreeing = 0;
        for (var list : compared) {
            var first = list.get(0).agreeFields;
            boolean same = list.stream().skip(1).allMatch(r -> fields.stream()
                    .allMatch(f -> java.util.Objects.equals(first.get(f), r.agreeFields.get(f))));
            if (same) agreeing++;
        }
        return new FieldRow(name, compared.size(), agreeing, freeText);
    }

    static String citationKey(Collection<String> cited) {
        return "cited=" + String.join(",", new TreeSet<>(cited));
    }

    static String stopSetKey(Collection<String> stops, boolean fallback) {
        return "stops=" + String.join(",", new TreeSet<>(stops)) + "|fallback=" + fallback;
    }

    /** The key two trials are compared by. A call without an answer agrees with nothing but itself. */
    static String key(CaseResult r) {
        if (r.agreeKey == null) return "no answer";
        return EvalScorer.ASK.equals(r.type) ? r.agreeKey + "|passed=" + r.passed() : r.agreeKey;
    }

    private static List<CaseResult> scored(List<CaseResult> trials) {
        return trials.stream().filter(r -> !r.infra).toList();
    }

    static List<Row> rows(List<List<CaseResult>> perCase) {
        var out = new ArrayList<Row>();
        for (var type : TYPES) {
            int cases = 0, agreeing = 0, pairs = 0, differing = 0, most = 0;
            for (var trials : perCase) {
                var list = scored(trials);
                if (list.size() < 2 || !type.equals(list.get(0).type)) continue;
                cases++;
                pairs += list.size() - 1;
                most = Math.max(most, list.size());
                var first = key(list.get(0));
                int off = (int) list.stream().skip(1).filter(r -> !key(r).equals(first)).count();
                differing += off;
                if (off == 0) agreeing++;
            }
            if (cases > 0) out.add(new Row(type, cases, agreeing, pairs, differing, most));
        }
        return out;
    }

    /** Plan cases that kept the same stop order in every scored trial. */
    static Order order(List<List<CaseResult>> perCase) {
        int cases = 0, agreeing = 0;
        for (var trials : perCase) {
            var list = scored(trials);
            if (list.size() < 2 || !EvalScorer.PLAN.equals(list.get(0).type)) continue;
            cases++;
            var first = String.valueOf(list.get(0).orderKey);
            if (list.stream().skip(1).allMatch(r -> String.valueOf(r.orderKey).equals(first))) agreeing++;
        }
        return new Order(cases, agreeing);
    }

    static String ruleOfThree(Row row) {
        if (row.differing() == 0) {
            return row.type() + ": " + row.pairs() + " trial pairs, none differ, so the per-trial disagreement rate is at most 3/"
                    + row.pairs() + " = " + EvalScorer.fmt(3.0 / row.pairs()) + " at 95% confidence (rule of three).";
        }
        return row.type() + ": " + row.differing() + " of " + row.pairs() + " trial pairs differ, so the rule of three does not "
                + "apply (it needs none); the observed rate is " + EvalScorer.fmt((double) row.differing() / row.pairs()) + ".";
    }

    /** The scorecard section; nothing when no case was run twice. */
    static void append(StringBuilder sb, List<List<CaseResult>> perCase) {
        var rows = rows(perCase);
        if (rows.isEmpty()) return;
        sb.append("\n## Agreement across trials (informational, not gated)\n\n"
                + "Every scored trial after the first is compared with trial 1 of the same case; a trial that failed at the "
                + "provider is left out. A case agrees when all its trials do: extract = the whole draft after normalisation "
                + "(case, punctuation and spacing folded, phone digits, link without a trailing slash); ask = the same cited "
                + "houses and the same pass or fail; plan = the same set of stops and the same fallback flag.\n\n"
                + "| Type | Most trials | Cases | All trials agree | Agreement | Trial pairs | Pairs that differ |\n"
                + "|---|---:|---:|---:|---:|---:|---:|\n");
        for (var r : rows) {
            sb.append("| ").append(r.type()).append(" | ").append(r.mostTrials()).append(" | ").append(r.cases()).append(" | ")
                    .append(r.agreeing()).append(" | ").append(EvalScorer.fmt(r.value())).append(" | ").append(r.pairs())
                    .append(" | ").append(r.differing()).append(" |\n");
        }
        var order = order(perCase);
        if (order.cases() > 0) {
            sb.append("\nPlan stop order: ").append(order.agreeing()).append(" of ").append(order.cases())
                    .append(" cases kept the same order in every trial (")
                    .append(EvalScorer.fmt((double) order.agreeing() / order.cases())).append(").\n");
        }
        var fields = fieldRows(perCase);
        if (!fields.isEmpty()) {
            sb.append("\n### Field agreement of the extract cases (informational, not gated)\n\n"
                    + "Per field, the cases whose every scored trial gave the same normalised value as trial 1 (28,000 and 28000 are "
                    + "one value; case, punctuation and spacing folded; the phone's last ten digits; amenities as a set). The free-text "
                    + "fields are reported apart: wording varies even when the facts agree, so they do not count against the "
                    + "structured tuple.\n\n| Field | Cases | Agree | Agreement (95% CI) | Differ |\n|---|---:|---:|---:|---:|\n");
            for (var f : fields) {
                sb.append("| ").append(f.field()).append(f.freeText() ? " (free text)" : "").append(" | ").append(f.cases())
                        .append(" | ").append(f.agreeing()).append(" | ").append(Interval.label(f.agreeing(), f.cases()))
                        .append(" | ").append(f.differing()).append(" |\n");
            }
        }
        sb.append("\n");
        rows.forEach(r -> sb.append(ruleOfThree(r)).append('\n'));
        sb.append("\nThis bounds how often a repeat differs from trial 1 on these cases; it does not show that the model "
                + "answers the same way every time.\n");
    }
}
