package app.doorprints.server.ai.extract;

import app.doorprints.server.ai.kind.AiKinds;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The oracle records of the days before the server read the kind files ({@link RawListing}, {@link RawSchoolListing}) as the
 * fields and amenities the one sanitizer reads (kinds PR 28): a test that has a record from the golden or the parity vectors
 * hands it on through here, so the house's old cases run through the code that now serves every kind.
 */
public final class HouseRaw {

    private HouseRaw() {
    }

    /** The house's draft of a raw listing: {@link KindDraftSanitizer} with the house kind file. */
    public static HouseDraft sanitize(RawListing raw, String source) {
        if (raw == null) return KindDraftSanitizer.sanitize(AiKinds.HOUSE, null, source);
        var m = new LinkedHashMap<String, String>();
        m.put("label", raw.label());
        m.put("address", raw.address());
        m.put("street", raw.street());
        m.put("locality", raw.locality());
        m.put("price", raw.price());
        m.put("priceType", raw.priceType());
        m.put("bedrooms", raw.bedrooms());
        m.put("contactName", raw.contactName());
        m.put("contactPhone", raw.contactPhone());
        m.put("listingUrl", raw.listingUrl());
        m.put("notes", raw.notes());
        return KindDraftSanitizer.sanitize(AiKinds.HOUSE, m, raw.amenities(), source);
    }

    /** A raw listing as the converter hands it to the service. */
    public static KindOutputConverter.Raw raw(RawListing raw) {
        if (raw == null) return null;
        var m = new LinkedHashMap<String, String>();
        m.put("label", raw.label());
        m.put("address", raw.address());
        m.put("street", raw.street());
        m.put("locality", raw.locality());
        m.put("price", raw.price());
        m.put("priceType", raw.priceType());
        m.put("bedrooms", raw.bedrooms());
        m.put("contactName", raw.contactName());
        m.put("contactPhone", raw.contactPhone());
        m.put("listingUrl", raw.listingUrl());
        m.put("notes", raw.notes());
        m.values().removeIf(java.util.Objects::isNull);
        return new KindOutputConverter.Raw(m, raw.amenities());
    }

    /** The school listing's fields by id. */
    public static Map<String, String> fields(RawSchoolListing raw) {
        if (raw == null) return null;
        var m = new LinkedHashMap<String, String>();
        m.put("label", raw.label());
        m.put("address", raw.address());
        m.put("street", raw.street());
        m.put("locality", raw.locality());
        m.put("contactName", raw.contactName());
        m.put("contactPhone", raw.contactPhone());
        m.put("listingUrl", raw.listingUrl());
        m.put("notes", raw.notes());
        m.put("board", raw.board());
        m.put("medium", raw.medium());
        m.put("grades", raw.grades());
        m.put("busService", raw.busService());
        m.put("fees", raw.fees());
        m.put("admissionFee", raw.admissionFee());
        return m;
    }
}
