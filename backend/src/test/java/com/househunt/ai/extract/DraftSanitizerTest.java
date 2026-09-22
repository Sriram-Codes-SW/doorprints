package com.househunt.ai.extract;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DraftSanitizerTest {

    private static final String SOURCE = """
            2BHK for rent in Indiranagar, 12th Main. Rent 28,000/month, deposit 1.5 lakh.
            Call Ramesh +91 98450 12345. Details: https://example.com/listing/42
            """;

    private static RawListing raw(String label, String price, String priceType, String bedrooms, String phone,
                                  String url, List<String> amenities) {
        return new RawListing(label, "12th Main, Indiranagar, Bengaluru", "12th Main", "Indiranagar", price, priceType,
                bedrooms, "Ramesh", phone, url, "Deposit 1.5 lakh.", amenities);
    }

    @Test
    void acceptsAGoodExtraction() {
        var d = DraftSanitizer.sanitize(raw("2BHK in Indiranagar", "28,000", "rent", "2",
                "+91 98450 12345", "https://example.com/listing/42", List.of("Parking", "parking", " Lift ")), SOURCE);
        assertThat(d.label()).isEqualTo("2BHK in Indiranagar");
        assertThat(d.price()).isEqualTo(28000L);
        assertThat(d.priceType()).isEqualTo("RENT");
        assertThat(d.bedrooms()).isEqualTo(2);
        assertThat(d.contactPhone()).isEqualTo("+91 98450 12345");
        assertThat(d.listingUrl()).isEqualTo("https://example.com/listing/42");
        assertThat(d.amenities()).containsExactly("parking", "lift");
        assertThat(d.warnings()).isEmpty();
    }

    @Test
    void parsesIndianPriceUnits() {
        var w = new ArrayList<String>();
        assertThat(DraftSanitizer.price("₹ 25k", w)).isEqualTo(25_000L);
        assertThat(DraftSanitizer.price("1.2 Cr", w)).isEqualTo(12_000_000L);
        assertThat(DraftSanitizer.price("85 lakh", w)).isEqualTo(8_500_000L);
        assertThat(DraftSanitizer.price("Rs. 32,500 per month", w)).isEqualTo(32_500L);
        assertThat(w).isEmpty();
        assertThat(DraftSanitizer.price("call for price", w)).isNull();
        assertThat(DraftSanitizer.price("99999999999999", w)).isNull();
        assertThat(w).hasSize(2);
    }

    @Test
    void dropsHallucinatedPhoneAndUrl() {
        var d = DraftSanitizer.sanitize(raw("x", "28000", "RENT", "2", "+91 99999 00000",
                "https://evil.example/phish", List.of()), SOURCE);
        assertThat(d.contactPhone()).isNull();
        assertThat(d.listingUrl()).isNull();
        assertThat(d.warnings()).anyMatch(s -> s.startsWith("contactPhone"))
                .anyMatch(s -> s.startsWith("listingUrl"));
    }

    @Test
    void rejectsNonHttpUrlsEvenIfPresent() {
        var src = "see javascript:alert(1)";
        var w = new ArrayList<String>();
        assertThat(DraftSanitizer.url("javascript:alert(1)", src, w)).isNull();
        assertThat(w).isNotEmpty();
    }

    @Test
    void clampsLengthsAndStripsControlCharacters() {
        var longLabel = "L".repeat(500);
        var d = DraftSanitizer.sanitize(new RawListing(longLabel, "a\u0000b  c", null, null, null, "LEASE", "2 BHK",
                null, null, null, "n".repeat(5000), null), SOURCE);
        assertThat(d.label()).hasSize(DraftSanitizer.LABEL_MAX);
        assertThat(d.address()).isEqualTo("ab c");
        assertThat(d.priceType()).isEqualTo("RENT");
        assertThat(d.bedrooms()).isEqualTo(2);
        assertThat(d.notes()).hasSize(DraftSanitizer.NOTES_MAX);
        assertThat(d.warnings()).anyMatch(s -> s.startsWith("label: truncated")).anyMatch(s -> s.startsWith("notes: truncated"));
    }

    @Test
    void invalidEnumsAndRangesAreDropped() {
        var w = new ArrayList<String>();
        assertThat(DraftSanitizer.priceType("PG", w)).isNull();
        assertThat(DraftSanitizer.bedrooms("45", w)).isNull();
        assertThat(DraftSanitizer.bedrooms("1RK", w)).isZero();
        assertThat(w).hasSize(2);
    }

    @Test
    void generatesLabelAndHandlesNullOutput() {
        var d = DraftSanitizer.sanitize(new RawListing(null, null, null, "HSR Layout", null, null, "3", null, null,
                null, "null", null), SOURCE);
        assertThat(d.label()).isEqualTo("3BHK in HSR Layout");
        assertThat(d.notes()).isNull();
        assertThat(d.amenities()).isEmpty();
        assertThat(DraftSanitizer.sanitize(null, SOURCE).label()).isEqualTo("Untitled listing");
    }

    @Test
    void capsAmenities() {
        var many = new ArrayList<String>();
        for (int i = 0; i < 40; i++) many.add("amenity " + i);
        var w = new ArrayList<String>();
        assertThat(DraftSanitizer.amenities(many, w)).hasSize(DraftSanitizer.AMENITIES_MAX);
        assertThat(w).isNotEmpty();
    }
}
