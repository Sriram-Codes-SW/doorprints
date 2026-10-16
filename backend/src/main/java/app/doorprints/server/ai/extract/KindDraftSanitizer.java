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
package app.doorprints.server.ai.extract;

import app.doorprints.server.ai.kind.AiKind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The safety check between the model and the form for a listing of any kind (kinds PR 7, S4b-BL-211; docs/03 ADR-36
 * §18.11; the house's too since kinds PR 28, §18.22), with the rules of the house's check before it: each text cleaned and
 * clamped with a warning, the phone and the link kept only when the listing holds them, and what the kind's
 * {@code ai.extract} slot names read by its id or its type: the price and its type ({@code money}), the BHK
 * ({@code bedrooms}) and the amenities, as the house's always were, and the typed extras of the file: an option by its id or
 * its English words, a yes or no, a text clamped to its {@code maxLength}, money read as the house's price is
 * ("96,000", "1.2 lakh") with the extra's one cadence. A value that cannot be read is dropped with a warning. The draft has
 * only what the slot names, the extras in the kind file's order; nothing else the model returns is read. The twin of the
 * website's {@code sanitizeKindDraft} and the phone's {@code KindDraftSanitizer}.
 */
public final class KindDraftSanitizer {

    private KindDraftSanitizer() {
    }

    /**
     * The draft of a listing of kind {@code k} from the model's raw fields ({@code null}: nothing usable), a kind with no
     * amenities in its answer.
     *
     * @param sourceText the listing the model read, to confirm the phone and the link
     */
    public static HouseDraft sanitize(AiKind k, Map<String, String> raw, String sourceText) {
        return sanitize(k, raw, null, sourceText);
    }

    /**
     * As above, with the amenities the model listed ({@code null}: none). The draft is read in one fixed order, which is the
     * order of its warnings: the address, street and locality, the price type and price, the BHK, the contact, the phone,
     * the link, the notes, the amenities, the kind's extras, the label.
     */
    public static HouseDraft sanitize(AiKind k, Map<String, String> raw, List<String> rawAmenities, String sourceText) {
        var warnings = new ArrayList<String>();
        if (raw == null) {
            warnings.add("Model returned nothing usable");
            return new HouseDraft("Untitled listing", null, null, null, null, null, null, null, null, null, null,
                    List.of(), warnings, null);
        }
        var source = sourceText == null ? "" : sourceText;
        var slot = k.extract();
        var address = slot.contains("address") ? DraftSanitizer.clean(raw.get("address"), DraftSanitizer.ADDRESS_MAX, "address", warnings) : null;
        var street = slot.contains("street") ? DraftSanitizer.clean(raw.get("street"), DraftSanitizer.STREET_MAX, "street", warnings) : null;
        var locality = slot.contains("locality") ? DraftSanitizer.clean(raw.get("locality"), DraftSanitizer.LOCALITY_MAX, "locality", warnings) : null;
        var priceType = slot.contains("money") ? DraftSanitizer.priceType(raw.get("priceType"), warnings) : null;
        var price = slot.contains("money") ? DraftSanitizer.price(raw.get("price"), warnings) : null;
        var bedrooms = slot.contains("bedrooms") ? DraftSanitizer.bedrooms(raw.get("bedrooms"), warnings) : null;
        var contactName = slot.contains("contactName") ? DraftSanitizer.clean(raw.get("contactName"), DraftSanitizer.CONTACT_NAME_MAX, "contactName", warnings) : null;
        var phone = slot.contains("contactPhone") ? DraftSanitizer.phone(raw.get("contactPhone"), source, warnings) : null;
        var url = slot.contains("listingUrl") ? DraftSanitizer.url(raw.get("listingUrl"), source, warnings) : null;
        if (url != null) {
            var links = DraftSanitizer.linkCount(source);
            if (links >= 2) warnings.add("listingUrl: the text has " + links + " links, check this is the right one");
        }
        var notes = slot.contains("notes") ? DraftSanitizer.clean(raw.get("notes"), DraftSanitizer.NOTES_MAX, "notes", warnings) : null;
        var amenities = slot.contains("amenities") ? DraftSanitizer.amenities(rawAmenities, warnings) : List.<String>of();
        var extras = new LinkedHashMap<String, Object>();
        for (var e : k.extras()) {
            if (!slot.contains(e.id()) || e.words() == null) continue;
            var value = extra(e, raw.get(e.id()), warnings);
            if (value != null) extras.put(e.id(), value);
        }
        var label = slot.contains("label") ? DraftSanitizer.clean(raw.get("label"), DraftSanitizer.LABEL_MAX, "label", warnings) : null;
        if (label == null) {
            var where = locality != null ? locality : street;
            if (slot.contains("bedrooms")) {
                // A kind that names its BHK is named by it ("2BHK in Indiranagar", "Studio"): the house's label, cut without a word.
                label = DraftSanitizer.defaultLabel(k.noun(), bedrooms, locality, street);
                warnings.add("label: generated because the model returned none");
            } else {
                label = where == null ? k.noun() : k.noun() + " in " + where;
                warnings.add("label: generated because the model returned none");
                // A long place makes a long name: cut with the warning every other field gives (review of #313).
                if (label.length() > DraftSanitizer.LABEL_MAX) {
                    label = label.substring(0, DraftSanitizer.LABEL_MAX);
                    warnings.add("label: truncated to " + DraftSanitizer.LABEL_MAX + " characters");
                }
            }
        }
        return new HouseDraft(label, address, street, locality, price, priceType, bedrooms, contactName, phone, url,
                notes, amenities, List.copyOf(warnings), extras.isEmpty() ? null : extras);
    }

    /** One extra read by its type, or null (a warning says why when there was a value). */
    static Object extra(AiKind.Extra e, String value, List<String> warnings) {
        if (value == null || value.isBlank()) return null;
        return switch (e.type()) {
            case "enum" -> {
                var v = value.strip();
                for (var o : e.options().entrySet()) {
                    if (o.getKey().equalsIgnoreCase(v) || o.getValue().equalsIgnoreCase(v)) yield o.getKey();
                }
                warnings.add(e.id() + ": '" + DraftSanitizer.abbreviate(value) + "' is not one of "
                        + String.join(", ", e.options().keySet()) + ", dropped");
                yield null;
            }
            case "bool" -> {
                var v = value.strip().toLowerCase(Locale.ROOT);
                if (v.equals("yes") || v.equals("y") || v.equals("true")) yield Boolean.TRUE;
                if (v.equals("no") || v.equals("n") || v.equals("false")) yield Boolean.FALSE;
                warnings.add(e.id() + ": '" + DraftSanitizer.abbreviate(value) + "' is not yes or no, dropped");
                yield null;
            }
            case "text" -> DraftSanitizer.clean(value, e.maxLength(), e.id(), warnings);
            case "money" -> {
                var amount = DraftSanitizer.price(value, warnings, e.id());
                if (amount == null) yield null;
                var money = new LinkedHashMap<String, Object>();
                money.put("amount", amount);
                money.put("cadence", e.cadence());
                yield money;
            }
            default -> throw new IllegalStateException("the listing extract does not fill a " + e.type() + " extra");
        };
    }
}
