package com.househunt.ai;

import com.househunt.house.HouseDto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The ONE place that keeps a house's contact person out of text bound for an AI provider (threat model F-30,
 * requirement AI-010). Every provider-bound path goes through it:
 * <ul>
 *   <li>embedding text and Ask context: {@code HouseDocuments.text()} builds from {@link #forHouse(HouseDto)} and never
 *       emits the contact fields; {@code RagService} passes retrieved (possibly pre-fix) chunk text through
 *       {@link #scrubStoredText(String, String, String)} before it goes into a prompt or a citation;</li>
 *   <li>agent and MCP tool results: {@code HouseSearchService.HouseSummary} and {@code HouseQueries.HouseDetails} are
 *       built from {@link #forHouse(HouseDto)} and have no contact fields.</li>
 * </ul>
 * The contact name and phone stay in the database and in the normal house API, so the user still sees them in the
 * apps; they are only withheld from the model.
 *
 * <p>Two kinds of field, two methods on {@link Redactor}:
 * <ul>
 *   <li>{@link Redactor#freeText(String)} for everything the user types freely: label, checklist keys, listing URL and
 *       notes. It removes the saved contact name, whole, AND each name part of 3+ letters except honorifics, so a
 *       house labelled "Ramesh's 2BHK" for contact "Ramesh Kumar" becomes "[contact]'s 2BHK";</li>
 *   <li>{@link Redactor#place(String)} for place fields only: address, street and locality. It removes the whole
 *       name (as saved, or its significant parts in order or reversed with any separator, honorifics ignored, so
 *       "C/o Ramesh Kumar" goes for "Mr. Ramesh Kumar"; for an initials-style name the initials plus the name, before
 *       or after it, with or without dots, so "C/o K Ramesh" goes for "K. Ramesh" and "C/o AK Sharma" for
 *       "A. K. Sharma") but keeps single name parts, so a place such as "Kumar Park" or "Lakshmi Nagar" survives.</li>
 * </ul>
 * Both methods also remove:
 * <ul>
 *   <li>the saved contact phone, whatever the separators ({@code 98450-12345} for {@code +91 98450 12345}), when it
 *       has 8 or more digits;</li>
 *   <li>anything else that looks like a phone number: {@code +<country code>...}, Indian mobiles
 *       ({@code [6-9]} + 9 digits, optional {@code +91}/{@code 0} prefix and separators), STD-code landlines
 *       ({@code 080-2345 6789}) and any run of 10-15 digits. Dates ({@code 2026-09-22}) and prices
 *       ({@code 2500000}, {@code 1,20,00,000}) are left alone.</li>
 * </ul>
 * Name matching is case-insensitive and on word boundaries (Indic scripts included), so "Rameshwaram" is kept.
 * Placeholders are {@value #CONTACT} and {@value #PHONE}. This is best effort for free text (a name spelled
 * differently in a note is not caught); the structured contact fields are never sent at all.
 */
public final class ContactRedactor {

    public static final String CONTACT = "[contact]";
    public static final String PHONE = "[phone]";

    /** Line prefix under which documents indexed before the fix stored the contact name. */
    private static final Pattern STORED_CONTACT_LINE = Pattern.compile("(?im)^[ \\t]*Contact[ \\t]*:.*(?:\\R|$)");

    private static final String WORD = "[\\p{L}\\p{M}\\p{N}]";
    private static final Pattern PHONE_LIKE = Pattern.compile(
            "(?<![\\p{L}\\p{M}\\p{N}+])(?:"
                    + "\\+\\d(?:[ .()\\-]{0,2}\\d){6,14}"                  // +<cc> ... (7-15 digits)
                    + "|(?:(?:\\+?91|0)[ \\-]?)?[6-9](?:[ .\\-]?\\d){9}"  // Indian mobile
                    + "|0\\d{2,4}[ \\-]?\\d{3,4}[ \\-]?\\d{3,4}"          // STD code + landline
                    + "|\\d{10,15}"                                     // long digit run
                    + ")(?!" + WORD + ")");
    private static final Set<String> HONORIFICS = Set.of("mr", "mrs", "ms", "miss", "dr", "sri", "shri", "smt",
            "kumari", "sir", "madam", "uncle", "aunty", "auntie", "anna", "akka", "ji", "garu", "owner", "broker",
            "agent", "landlord", "the", "and");

    private ContactRedactor() {
    }

    /** A redactor for one house's free text, knowing that house's contact name and phone. */
    public static Redactor forHouse(HouseDto house) {
        return house == null ? new Redactor(null, null) : new Redactor(house.contactName(), house.contactPhone());
    }

    /** As {@link #forHouse(HouseDto)} for callers that only have the two values (e.g. an entity). */
    public static Redactor forContact(String contactName, String contactPhone) {
        return new Redactor(contactName, contactPhone);
    }

    /**
     * For chunk text read back from the vector store: drops any {@code Contact:} line (documents indexed before the
     * fix had one) and then redacts like {@link Redactor#freeText(String)} (whole chunk, so place lines lose name
     * parts too). The name and phone may be {@code null}
     * (house deleted meanwhile): the {@code Contact:} line and phone-like numbers are still removed.
     */
    public static String scrubStoredText(String text, String contactName, String contactPhone) {
        if (text == null || text.isEmpty()) return text;
        var withoutLine = STORED_CONTACT_LINE.matcher(text).replaceAll("");
        return forContact(contactName, contactPhone).freeText(withoutLine);
    }

    /** Only the generic phone-number rule, for text with no known contact. */
    public static String redactPhones(String text) {
        if (text == null || text.isEmpty()) return text;
        return PHONE_LIKE.matcher(text).replaceAll(PHONE);
    }

    public static final class Redactor {
        /** A saved phone with fewer digits is not matched in text (8 = a landline without its STD code). */
        static final int MIN_SAVED_PHONE_DIGITS = 8;

        private static final String SEP = "[^\\p{L}\\p{M}\\p{N}]+";
        private static final String SEP_OPTIONAL = "[^\\p{L}\\p{M}\\p{N}]*";

        private final List<Pattern> fullName;
        private final List<Pattern> nameParts;
        private final Pattern savedPhone;

        private Redactor(String contactName, String contactPhone) {
            var name = contactName == null ? "" : contactName.strip().replaceAll("\\s+", " ");
            var significant = new ArrayList<String>();
            var tokens = new ArrayList<String>();   // non-honorific letter tokens in saved order
            var initials = new ArrayList<String>(); // tokens of 1-2 letters: "K" in "K. Ramesh", "AK" in "AK Sharma"
            for (var p : name.split("[^\\p{L}\\p{M}]+")) {
                if (p.isEmpty() || HONORIFICS.contains(p.toLowerCase(Locale.ROOT))) continue;
                tokens.add(p);
                if (p.codePointCount(0, p.length()) >= 3) significant.add(p);
                else initials.add(p);
            }
            var full = new ArrayList<Pattern>();
            if (name.codePointCount(0, name.length()) >= 2) full.add(word(Pattern.quote(name)));
            // Initials-style names (common in ta/te): "K. Ramesh" -> "C/o K Ramesh", "Ramesh K", "K.Ramesh";
            // "A. K. Sharma" -> "A K Sharma", "AK Sharma", "Sharma A.K.". The initials are required here, so a lone
            // "Ramesh" in a place field is still kept. Longest forms first, before the initials-free forms below.
            if (!initials.isEmpty() && !significant.isEmpty()) {
                var orders = new LinkedHashSet<List<String>>();
                orders.add(List.copyOf(tokens));
                var initialsFirst = new ArrayList<>(initials);
                initialsFirst.addAll(significant);
                orders.add(List.copyOf(initialsFirst));
                var initialsLast = new ArrayList<>(significant);
                initialsLast.addAll(initials);
                orders.add(List.copyOf(initialsLast));
                for (var order : orders) full.add(word(joinWithInitials(order)));
            }
            if (significant.size() >= 2) {
                full.add(word(joinParts(significant)));
                var reversed = new ArrayList<>(significant);
                Collections.reverse(reversed);
                if (!reversed.equals(significant)) full.add(word(joinParts(reversed)));
            }
            this.fullName = List.copyOf(full);
            var parts = new LinkedHashSet<>(significant);
            var sorted = new ArrayList<>(parts);
            sorted.sort(Comparator.comparingInt(String::length).reversed());
            this.nameParts = sorted.stream().map(Pattern::quote).map(Redactor::word).toList();
            this.savedPhone = phonePattern(contactPhone);
        }

        /**
         * For place fields only (address, street, locality): the whole saved name, the saved phone and phone-like
         * numbers. "Whole name" means the saved string as typed, and also its significant parts (3+ letters, not an
         * honorific) in order or in reverse order with any separator between them, so for "Mr. Ramesh Kumar" the
         * text "C/o Ramesh  Kumar", "RAMESH KUMAR" and "Kumar, Ramesh" all lose the name. Single name parts are kept so
         * place names such as "Kumar Park" survive.
         */
        public String place(String s) {
            if (s == null || s.isEmpty()) return s;
            var out = phones(s);
            for (var p : fullName) out = p.matcher(out).replaceAll(CONTACT);
            return out;
        }

        /**
         * For every field the user types freely (label, checklist keys, listing URL, notes): as
         * {@link #place(String)}, plus every name part of 3+ letters that is not an honorific.
         */
        public String freeText(String s) {
            if (s == null || s.isEmpty()) return s;
            var out = place(s);
            for (var p : nameParts) out = p.matcher(out).replaceAll(CONTACT);
            return out;
        }

        private String phones(String s) {
            var out = savedPhone == null ? s : savedPhone.matcher(s).replaceAll(PHONE);
            return PHONE_LIKE.matcher(out).replaceAll(PHONE);
        }

        /** {@code regex} on word boundaries (Indic scripts included), case-insensitive. */
        private static Pattern word(String regex) {
            return Pattern.compile("(?<!" + WORD + ")(?:" + regex + ")(?!" + WORD + ")",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        }

        /** The name parts in this order, with any run of non-word characters (spaces, dots, commas) between them. */
        private static String joinParts(List<String> parts) {
            var sb = new StringBuilder();
            for (var p : parts) {
                if (!sb.isEmpty()) sb.append(SEP);
                sb.append(Pattern.quote(p));
            }
            return sb.toString();
        }

        /**
         * Tokens in this order; a token of 3+ letters must match whole, a shorter one is an initial: each of its letters
         * may be followed by a dot, and consecutive initial letters may be written together ("AK", "A.K.") or apart
         * ("A K", "A. K."). Word tokens are separated from their neighbours by at least one non-word character.
         */
        private static String joinWithInitials(List<String> tokens) {
            var sb = new StringBuilder();
            boolean previousWasInitial = false;
            for (var t : tokens) {
                boolean initial = t.codePointCount(0, t.length()) < 3;
                if (!sb.isEmpty()) sb.append(previousWasInitial && initial ? SEP_OPTIONAL : SEP);
                if (initial) {
                    var letters = t.codePoints().toArray();
                    for (int i = 0; i < letters.length; i++) {
                        if (i > 0) sb.append(SEP_OPTIONAL);
                        sb.append(Pattern.quote(new String(letters, i, 1)));
                    }
                } else {
                    sb.append(Pattern.quote(t));
                }
                previousWasInitial = initial;
            }
            return sb.toString();
        }

        /**
         * The saved phone's digits with any separators between them; with a country code also its last 10 digits
         * alone (people write the same number with and without +91). {@code null} when it has fewer than
         * 8 digits ({@code MIN_SAVED_PHONE_DIGITS}): a shorter saved number would also match prices and deposits
         * ("Price 123456") wherever they appear. Every variant keeps at least that many digits.
         */
        private static Pattern phonePattern(String phone) {
            if (phone == null) return null;
            var digits = phone.replaceAll("\\D", "");
            if (digits.length() < MIN_SAVED_PHONE_DIGITS) return null;
            var variants = new LinkedHashSet<String>();
            variants.add(digits);
            if (digits.length() > 10) variants.add(digits.substring(digits.length() - 10));
            if (digits.startsWith("0") && digits.length() > MIN_SAVED_PHONE_DIGITS) variants.add(digits.substring(1));
            var alternatives = new ArrayList<String>();
            for (var v : variants) {
                var sb = new StringBuilder();
                for (int i = 0; i < v.length(); i++) {
                    if (i > 0) sb.append("[ .()\\-]{0,2}");
                    sb.append(v.charAt(i));
                }
                alternatives.add(sb.toString());
            }
            return Pattern.compile("(?<!\\d)\\+?(?:" + String.join("|", alternatives) + ")(?!\\d)");
        }
    }
}
