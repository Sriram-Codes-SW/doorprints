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
package app.doorprints.server.ai.kind;

import app.doorprints.server.house.CandidateExtras;
import app.doorprints.server.house.HouseDto;

import java.util.List;
import java.util.Map;

/**
 * A kind as the server's AI reads it (kinds PR 7, S4b-BL-211; docs/03 ADR-36 §18.11; kinds PR 28, §18.22): a copy of its kind
 * file's {@code ai} slots (§18.4 door 10), of the typed extras those slots name, and of the English words of the file's
 * {@code ai.words}, with the texts of its prompts ({@link KindPrompts}). Every kind is one of these, the house too: built by
 * {@link #from} from {@link KindFile} and its prompt texts, never typed in code. {@code AiKindFileTest} and
 * {@code KindFileResourcesTest} hold the copy to the files in {@code docs/schemas/kinds}.
 *
 * @param noun          the first line's key of a document ({@code School: <name>})
 * @param extract       what the listing extract fills (core field ids and extra ids)
 * @param ask           what an Ask document line carries, in line order: core field ids, extra ids, and the sections
 *                      {@code areaNotes}, {@code distances} and {@code visits}, and the house's own lines
 * @param plan          what the Plan tools show and filter on
 * @param never         what a saved candidate never sends to a provider, whatever the other slots say
 * @param extras        the kind's typed extras that a slot names, in the kind file's order
 * @param extractSystem the Extract system text, {@code %1$s} the listing's tag
 * @param records       the name of the Ask records' block ({@code schools}: the tag is {@code schools-<nonce>}), the word
 *                      {@code PromptSafety.neutralize} removes from a value (S4b-BL-269)
 * @param refusal       the Ask refusal sentence of this kind (S4b-BL-269), exact: the answer that cites nothing
 * @param askSystem     the Ask system text, {@code %1$s} the block's tag; it holds {@link #refusal} word for word
 * @param planSystem    the server planner's system text (S4b-BL-290), a format with the start latitude {@code %1$.6f}, the
 *                      start longitude {@code %2$.6f}, the stop cap {@code %3$d} and the neutral rule {@code %4$s}; the
 *                      planner uses tools, so it is not the devices' text (whose candidates are in the prompt)
 * @param extractFields the fields of the Extract answer, in the answer's order
 * @param capabilities  what the kind can do beyond its fields (§18.19)
 */
public record AiKind(String id, String noun, List<String> extract, List<String> ask, List<String> plan,
                     List<String> never, List<Extra> extras, String extractSystem, String records, String refusal,
                     String askSystem, String planSystem, List<KindPrompts.ExtractField> extractFields,
                     KindFile.Capabilities capabilities) {

    private static final long BOUND = 1_000_000_000_000_000L;

    /** A kind built by hand, with the planner text and no Extract fields (a test of a prompt or a document). */
    public AiKind(String id, String noun, List<String> extract, List<String> ask, List<String> plan,
                  List<String> never, List<Extra> extras, String extractSystem, String records, String refusal,
                  String askSystem, String planSystem) {
        this(id, noun, extract, ask, plan, never, extras, extractSystem, records, refusal, askSystem, planSystem,
                List.of(), KindFile.Capabilities.DEFAULTS);
    }

    /** As above, with no planner text of its own. */
    public AiKind(String id, String noun, List<String> extract, List<String> ask, List<String> plan,
                  List<String> never, List<Extra> extras, String extractSystem, String records, String refusal,
                  String askSystem) {
        this(id, noun, extract, ask, plan, never, extras, extractSystem, records, refusal, askSystem, null);
    }

    /**
     * The AI kind of a kind file, or null when the file has no {@code ai.words} (a kind the AI is not told the words of is
     * read as a house). {@code prompts} null: the kind has no pack of its own and its texts are written from its words.
     */
    public static AiKind from(KindFile file, KindPrompts prompts) {
        var words = file.ai().words();
        if (words == null) return null;
        var named = new java.util.LinkedHashSet<String>();
        named.addAll(file.ai().extract());
        named.addAll(file.ai().ask());
        named.addAll(file.ai().plan());
        var extras = new java.util.ArrayList<Extra>();
        for (var e : file.extras()) {
            if (!named.contains(e.id())) continue;
            var w = words.extras().get(e.id());
            var options = w == null ? Map.<String, String>of() : w.options();
            extras.add(new Extra(e.id(), e.type(), w == null ? null : w.words(), options,
                    e.min() != null ? e.min() : e.type().equals("money") ? Long.valueOf(0) : Long.valueOf(-BOUND),
                    e.max() != null ? e.max() : Long.valueOf(BOUND), e.maxLength(),
                    e.cadences().size() == 1 ? e.cadences().get(0) : null));
        }
        var p = prompts == null ? KindPrompts.generic(file) : prompts;
        return new AiKind(file.id(), words.noun(), file.ai().extract(), file.ai().ask(), file.ai().plan(),
                file.ai().never(), List.copyOf(extras), p.extractSystem(), words.records(), words.refusal(),
                p.askSystem(), p.planSystem(), p.extractFields(), file.capabilities());
    }

    /**
     * One typed extra (door 5): its type, the range a value must keep to be read at all, and its English words.
     *
     * @param words   the key of its document line; null for an extra the server's AI knows by id (the house's)
     * @param options the option ids and their words, in the kind file's order (an {@code enum})
     * @param cadence the one cadence a {@code money} extra has, or null
     */
    public record Extra(String id, String type, String words, Map<String, String> options, Long min, Long max,
                        Integer maxLength, String cadence) {
    }

    /** Whether a provider may see {@code id} for {@code slot}: the slot names it and {@code never} does not. */
    public boolean allows(List<String> slot, String id) {
        return slot.contains(id) && !never.contains(id);
    }

    /** The extra of that id, or null. */
    public Extra extra(String id) {
        return extras.stream().filter(e -> e.id().equals(id)).findFirst().orElse(null);
    }

    /**
     * The candidate as {@code slot} lets a provider see it: the core fields the slot does not name are left out, and
     * of its extras only those the slot names (S4b-BL-270: the Plan tools read them, as {@code KindDocuments.extras};
     * an Ask document reads them through {@code KindDocuments.text}); an extra the kind does not know is never kept.
     * The contact name and phone stay, only so that the contact redactor can take them out of the other fields: no view
     * writes them. Never used for a house.
     */
    public HouseDto view(HouseDto h, List<String> slot) {
        return new HouseDto(h.id(), h.kind(), allows(slot, "label") ? h.label() : "",
                allows(slot, "address") ? h.address() : null, allows(slot, "street") ? h.street() : null,
                allows(slot, "locality") ? h.locality() : null, h.lat(), h.lon(),
                allows(slot, "status") ? h.status() : null, allows(slot, "money") ? h.money() : null,
                allows(slot, "rating") ? h.rating() : null, h.contactName(), h.contactPhone(),
                allows(slot, "listingUrl") ? h.listingUrl() : null, allows(slot, "notes") ? h.notes() : null,
                h.locationSource(), onlyNamed(h.extras(), slot), allows(slot, "rooms") ? h.rooms() : null,
                allows(slot, "questions") ? h.answers() : null, allows(slot, "moveIn") ? h.moveIn() : null, null,
                allows(slot, "checklist") ? h.checklist() : Map.of(), h.createdAt(), h.updatedAt(), h.deleted(),
                h.syncVersion(), h.distanceMeters());
    }

    /** A copy of the extras holding only the kind's own extras that {@code slot} names (and {@code never} does not), or null. */
    private CandidateExtras onlyNamed(CandidateExtras extras, List<String> slot) {
        if (extras == null) return null;
        var kept = new CandidateExtras();
        for (var e : this.extras) {
            var v = extras.other().get(e.id());
            if (v != null && allows(slot, e.id())) kept.put(e.id(), v);
        }
        return CandidateExtras.orNull(kept);
    }
}
