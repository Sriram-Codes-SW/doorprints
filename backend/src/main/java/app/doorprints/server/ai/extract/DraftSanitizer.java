package app.doorprints.server.ai.extract;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Server-side validation of model output. The model is treated as an untrusted parser: every field is trimmed,
 * stripped of control characters, clamped to the column sizes of {@code house}, and checked against the source
 * text where that is cheap (phone numbers and URLs must literally appear in the listing, which kills the most
 * common hallucinations). Pure Java, no Spring, fully unit-tested.
 */
public final class DraftSanitizer {

    static final int LABEL_MAX = 200, ADDRESS_MAX = 500, STREET_MAX = 200, LOCALITY_MAX = 200,
            CONTACT_NAME_MAX = 200, PHONE_MAX = 50, URL_MAX = 1000, NOTES_MAX = 2000,
            AMENITIES_MAX = 20, AMENITY_MAX = 50, BEDROOMS_MAX = 20;
    static final long PRICE_MAX = 1_000_000_000_000L;

    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t]]");
    private static final Pattern WS = Pattern.compile("\\s+");
    private static final Pattern PRICE = Pattern.compile(
            "(?i)(\\d+(?:\\.\\d+)?)\\s*(k|thousand|l|lac|lakh|lakhs|lacs|cr|crore|crores|m|mn|million)?\\b");
    private static final Pattern FIRST_INT = Pattern.compile("\\d+");

    private DraftSanitizer() {
    }

    public static HouseDraft sanitize(RawListing raw, String sourceText) {
        var warnings = new ArrayList<String>();
        if (raw == null) {
            warnings.add("Model returned nothing usable");
            return new HouseDraft("Untitled listing", null, null, null, null, null, null, null, null, null, null,
                    List.of(), warnings);
        }
        var source = sourceText == null ? "" : sourceText;

        var address = clean(raw.address(), ADDRESS_MAX, "address", warnings);
        var street = clean(raw.street(), STREET_MAX, "street", warnings);
        var locality = clean(raw.locality(), LOCALITY_MAX, "locality", warnings);
        var priceType = priceType(raw.priceType(), warnings);
        var price = price(raw.price(), warnings);
        var bedrooms = bedrooms(raw.bedrooms(), warnings);
        var contactName = clean(raw.contactName(), CONTACT_NAME_MAX, "contactName", warnings);
        var phone = phone(raw.contactPhone(), source, warnings);
        var url = url(raw.listingUrl(), source, warnings);
        var notes = clean(raw.notes(), NOTES_MAX, "notes", warnings);
        var amenities = amenities(raw.amenities(), warnings);

        var label = clean(raw.label(), LABEL_MAX, "label", warnings);
        if (label == null) {
            label = defaultLabel(bedrooms, locality, street);
            warnings.add("label: generated because the model returned none");
        }
        return new HouseDraft(label, address, street, locality, price, priceType, bedrooms, contactName, phone, url,
                notes, amenities, List.copyOf(warnings));
    }

    /** Trim, drop control chars, collapse whitespace (except in notes' newlines), clamp length; blank -> null. */
    static String clean(String value, int max, String field, List<String> warnings) {
        if (value == null) return null;
        var s = CONTROL.matcher(value).replaceAll("");
        s = field.equals("notes") ? s.strip() : WS.matcher(s).replaceAll(" ").strip();
        if (s.isEmpty() || s.equalsIgnoreCase("null") || s.equalsIgnoreCase("n/a") || s.equalsIgnoreCase("unknown")) {
            return null;
        }
        if (s.length() > max) {
            warnings.add(field + ": truncated to " + max + " characters");
            s = s.substring(0, max);
        }
        return s;
    }

    static String priceType(String value, List<String> warnings) {
        if (value == null || value.isBlank()) return null;
        var v = value.strip().toUpperCase(Locale.ROOT);
        if (v.equals("RENT") || v.equals("RENTAL") || v.equals("LEASE") || v.equals("MONTHLY")) return "RENT";
        if (v.equals("SALE") || v.equals("SELL") || v.equals("BUY") || v.equals("RESALE")) return "SALE";
        warnings.add("priceType: '" + abbreviate(value) + "' is not RENT or SALE, dropped");
        return null;
    }

    /** Parses "25,000", "₹ 25000/month", "25k", "1.2 Cr", "85 lakh" into whole rupees. */
    static Long price(String value, List<String> warnings) {
        if (value == null || value.isBlank()) return null;
        var m = PRICE.matcher(value.replace(",", "").replace("_", ""));
        if (!m.find()) {
            warnings.add("price: could not read '" + abbreviate(value) + "', dropped");
            return null;
        }
        try {
            var number = new BigDecimal(m.group(1));
            var unit = m.group(2) == null ? "" : m.group(2).toLowerCase(Locale.ROOT);
            var multiplier = switch (unit) {
                case "k", "thousand" -> 1_000L;
                case "l", "lac", "lakh", "lakhs", "lacs" -> 100_000L;
                case "cr", "crore", "crores" -> 10_000_000L;
                case "m", "mn", "million" -> 1_000_000L;
                default -> 1L;
            };
            var rupees = number.multiply(BigDecimal.valueOf(multiplier)).longValue();
            if (rupees < 0 || rupees > PRICE_MAX) {
                warnings.add("price: " + rupees + " is out of range, dropped");
                return null;
            }
            return rupees;
        } catch (NumberFormatException | ArithmeticException e) {
            warnings.add("price: could not read '" + abbreviate(value) + "', dropped");
            return null;
        }
    }

    static Integer bedrooms(String value, List<String> warnings) {
        if (value == null || value.isBlank()) return null;
        var lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("studio") || lower.contains("1rk")) return 0;
        var m = FIRST_INT.matcher(value);
        if (!m.find()) {
            warnings.add("bedrooms: could not read '" + abbreviate(value) + "', dropped");
            return null;
        }
        try {
            int n = Integer.parseInt(m.group());
            if (n > BEDROOMS_MAX) {
                warnings.add("bedrooms: " + n + " is out of range, dropped");
                return null;
            }
            return n;
        } catch (NumberFormatException e) {
            warnings.add("bedrooms: could not read '" + abbreviate(value) + "', dropped");
            return null;
        }
    }

    /** Keeps only phone characters, requires 7-15 digits, and requires the digits to appear in the source text. */
    static String phone(String value, String source, List<String> warnings) {
        if (value == null || value.isBlank()) return null;
        var kept = value.replaceAll("[^0-9+()\\- ]", "").strip();
        var digits = kept.replaceAll("\\D", "");
        if (digits.length() < 7 || digits.length() > 15) {
            warnings.add("contactPhone: not a valid phone number, dropped");
            return null;
        }
        var sourceDigits = source.replaceAll("\\D", "");
        // Compare the last 10 digits so "+91 98450 12345" matches "9845012345" in the text.
        var tail = digits.length() > 10 ? digits.substring(digits.length() - 10) : digits;
        if (!sourceDigits.contains(tail)) {
            warnings.add("contactPhone: not found in the listing text, dropped");
            return null;
        }
        return kept.length() > PHONE_MAX ? kept.substring(0, PHONE_MAX) : kept;
    }

    /** Only absolute http(s) URLs that literally appear in the source text. */
    static String url(String value, String source, List<String> warnings) {
        if (value == null || value.isBlank()) return null;
        var v = value.strip();
        try {
            var uri = URI.create(v);
            var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null) {
                warnings.add("listingUrl: only http(s) links are allowed, dropped");
                return null;
            }
        } catch (IllegalArgumentException e) {
            warnings.add("listingUrl: not a valid URL, dropped");
            return null;
        }
        if (!source.contains(v)) {
            warnings.add("listingUrl: not found in the listing text, dropped");
            return null;
        }
        if (v.length() > URL_MAX) {
            warnings.add("listingUrl: too long, dropped");
            return null;
        }
        return v;
    }

    static List<String> amenities(List<String> values, List<String> warnings) {
        if (values == null) return List.of();
        var out = new LinkedHashSet<String>();
        for (var v : values) {
            var c = clean(v, AMENITY_MAX, "amenities", warnings);
            if (c != null) out.add(c.toLowerCase(Locale.ROOT));
            if (out.size() == AMENITIES_MAX) {
                if (values.size() > AMENITIES_MAX) warnings.add("amenities: kept the first " + AMENITIES_MAX);
                break;
            }
        }
        return List.copyOf(out);
    }

    static String defaultLabel(Integer bedrooms, String locality, String street) {
        var where = locality != null ? locality : street;
        var what = bedrooms == null ? "House" : bedrooms == 0 ? "Studio" : bedrooms + "BHK";
        var label = where == null ? what : what + " in " + where;
        return label.length() > LABEL_MAX ? label.substring(0, LABEL_MAX) : label;
    }

    private static String abbreviate(String s) {
        var t = WS.matcher(s).replaceAll(" ").strip();
        return t.length() <= 40 ? t : t.substring(0, 40) + "…";
    }
}
