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

package app.doorprints.server.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** The central redaction rules behind every provider-bound path (F-30). */
class ContactRedactorTest {

    private final ContactRedactor.Redactor ramesh = ContactRedactor.forContact("Mr. Ramesh Kumar", "+91 98450 12345");

    @Test
    void fullNameAndSavedPhoneAreRedactedInAnyField() {
        assertThat(ramesh.place("Mr. Ramesh Kumar's flat, call 98450-12345"))
                .isEqualTo("[contact]'s flat, call [phone]");
        assertThat(ramesh.place("MR. RAMESH KUMAR")).isEqualTo("[contact]");
        assertThat(ramesh.place("+91-98450-12345")).isEqualTo("[phone]");
        assertThat(ramesh.place("9845012345")).isEqualTo("[phone]");
    }

    @Test
    void freeTextAlsoLosesSingleNamePartsButNotHonorificsOrOtherWords() {
        assertThat(ramesh.freeText("Ramesh said the owner lives upstairs; Kumar uncle is the broker. Rameshwaram trip"))
                .isEqualTo("[contact] said the owner lives upstairs; [contact] uncle is the broker. Rameshwaram trip");
        // Place fields (address, street, locality) keep single name parts: "Kumar Park" is a place.
        assertThat(ramesh.place("Kumar Park")).isEqualTo("Kumar Park");
        assertThat(ramesh.place("Mr. Ramesh Kumar's gate, Kumar Park")).isEqualTo("[contact]'s gate, Kumar Park");
    }

    @Test
    void placeRemovesTheFullNameWhateverTheHonorificSpacingCaseOrOrder() {
        // "C/o <owner>" is a common way to write an Indian address; the saved name has an honorific.
        assertThat(ramesh.place("C/o Ramesh Kumar, 12 MG Road")).isEqualTo("C/o [contact], 12 MG Road");
        assertThat(ramesh.place("C/o Ramesh  Kumar, 12 MG Road")).isEqualTo("C/o [contact], 12 MG Road");
        assertThat(ramesh.place("C/O RAMESH KUMAR")).isEqualTo("C/O [contact]");
        assertThat(ramesh.place("C/o Kumar Ramesh, 12 MG Road")).isEqualTo("C/o [contact], 12 MG Road");
        assertThat(ramesh.place("C/o Ramesh.Kumar")).isEqualTo("C/o [contact]");
        // Single parts and other words are still kept in place fields.
        assertThat(ramesh.place("Kumar Park")).isEqualTo("Kumar Park");
        assertThat(ramesh.place("Ramesh Kumaran Road")).isEqualTo("Ramesh Kumaran Road");
        var plain = ContactRedactor.forContact("Ramesh Kumar", null);
        assertThat(plain.place("C/o Ramesh  Kumar")).isEqualTo("C/o [contact]");
        assertThat(plain.place("C/o Kumar Ramesh")).isEqualTo("C/o [contact]");
        assertThat(plain.place("Kumar Park")).isEqualTo("Kumar Park");
    }

    @Test
    void placeRemovesInitialsStyleNamesWrittenWithOrWithoutDotsAndInEitherPosition() {
        // Initials before or after the given name are common in the ta/te locales (v0.10, 9.1).
        var k = ContactRedactor.forContact("K. Ramesh", null);
        assertThat(k.place("C/o K Ramesh, 3rd Cross")).isEqualTo("C/o [contact], 3rd Cross");
        assertThat(k.place("C/o K. Ramesh")).isEqualTo("C/o [contact]");
        assertThat(k.place("C/o K.Ramesh")).isEqualTo("C/o [contact]");
        assertThat(k.place("c/o k ramesh")).isEqualTo("c/o [contact]");
        assertThat(k.place("Ramesh K, Anna Nagar")).isEqualTo("[contact], Anna Nagar");
        // The initial is required in place fields, so a place named after the given name alone survives.
        assertThat(k.place("Ramesh Layout")).isEqualTo("Ramesh Layout");
        assertThat(k.place("KR Puram")).isEqualTo("KR Puram");

        var ak = ContactRedactor.forContact("A. K. Sharma", null);
        assertThat(ak.place("C/o A K Sharma")).isEqualTo("C/o [contact]");
        assertThat(ak.place("C/o A. K. Sharma")).isEqualTo("C/o [contact]");
        assertThat(ak.place("C/o AK Sharma")).isEqualTo("C/o [contact]");
        assertThat(ak.place("C/o A.K.Sharma")).isEqualTo("C/o [contact]");
        assertThat(ak.place("Sharma A K, 12 MG Road")).isEqualTo("[contact], 12 MG Road");
        assertThat(ak.place("Sharma Nagar")).isEqualTo("Sharma Nagar");

        var after = ContactRedactor.forContact("Ramesh K", null);
        assertThat(after.place("C/o K Ramesh")).isEqualTo("C/o [contact]");
        assertThat(after.place("Ramesh Nagar")).isEqualTo("Ramesh Nagar");

        var both = ContactRedactor.forContact("K. Ramesh Kumar", null);
        assertThat(both.place("C/o K Ramesh Kumar")).isEqualTo("C/o [contact]");
        assertThat(both.place("C/o Ramesh Kumar")).isEqualTo("C/o [contact]");

        // Free text still loses the given name on its own.
        assertThat(k.freeText("Ramesh Layout, C/o K Ramesh")).isEqualTo("[contact] Layout, C/o [contact]");
    }

    @Test
    void labelsChecklistKeysAndUrlsNamedAfterTheOwnersFirstNameLoseIt() {
        var r = ContactRedactor.forContact("Ramesh Kumar", "+91 98450 12345");
        assertThat(r.freeText("Ramesh's 2BHK, Indiranagar")).isEqualTo("[contact]'s 2BHK, Indiranagar");
        assertThat(r.freeText("Ramesh flat")).isEqualTo("[contact] flat");
        assertThat(r.freeText("RAMESH ok with pets")).isEqualTo("[contact] ok with pets");
        assertThat(r.freeText("https://example.com/rent/ramesh-2bhk")).isEqualTo("https://example.com/rent/[contact]-2bhk");
        assertThat(r.freeText("Rameshwaram view")).isEqualTo("Rameshwaram view");
    }

    @Test
    void worksForIndicScriptNames() {
        var r = ContactRedactor.forContact("रमेश", null);
        assertThat(r.freeText("रमेश जी से बात हुई")).isEqualTo("[contact] जी से बात हुई");
        var t = ContactRedactor.forContact("ரமேஷ்", null);
        assertThat(t.freeText("ரமேஷ் அழைத்தார்")).isEqualTo("[contact] அழைத்தார்");
    }

    @ParameterizedTest
    @ValueSource(strings = {"+91 98450 12345", "+919845012345", "98450 12345", "098450-12345", "91-98450-12345",
            "080-2345 6789", "080 23456789", "+44 20 7946 0958", "00919845012345", "7760012345"})
    void phoneLikeNumbersAreRedactedEvenWhenNotTheSavedOne(String phone) {
        var none = ContactRedactor.forContact(null, null);
        assertThat(none.freeText("call " + phone + " after 6")).isEqualTo("call [phone] after 6");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Visited on 2026-09-22 10:30", "Price 2500000 negotiable", "Rs 1,20,00,000",
            "Deposit 75000, maintenance 3500", "Plot 12/4, 3rd cross, 560038", "1650 sqft, 3 BHK, 2 cars",
            "22-09-2026 at 5 pm", "Flat 1203, tower 9"})
    void datesPricesAndAddressesAreLeftAlone(String text) {
        assertThat(ContactRedactor.forContact(null, null).freeText(text)).isEqualTo(text);
        assertThat(ContactRedactor.redactGeneric(text)).isEqualTo(text);
    }

    @Test
    void shortSavedPhoneIsStillRedactedWhereverItAppears() {
        var r = ContactRedactor.forContact(null, "2345 6789");
        assertThat(r.freeText("landline 2345-6789 only")).isEqualTo("landline [phone] only");
    }

    @Test
    void savedLandlineWrittenWithoutItsStdCodeIsRedacted() {
        var r = ContactRedactor.forContact(null, "080 2345 6789");
        assertThat(r.freeText("reach the office on 2345 6789")).isEqualTo("reach the office on [phone]");
        assertThat(r.freeText("call 23456789 or 2345.6789")).isEqualTo("call [phone] or [phone]");
        assertThat(r.freeText("plot 12345678, deposit 2345 6788")).isEqualTo("plot 12345678, deposit 2345 6788");
        // A 10-digit saved number and a landline saved without the 0 have no such variant.
        assertThat(ContactRedactor.forContact(null, "98450 12345").freeText("ext 450 12345")).isEqualTo("ext 450 12345");
        assertThat(ContactRedactor.forContact(null, "8023456789").freeText("office 2345 6789")).isEqualTo("office 2345 6789");
    }

    @Test
    void savedPhoneShorterThanEightDigitsIsNotMatchedInText() {
        // A 6-digit saved "phone" would otherwise turn prices and deposits into [phone] (documented in ai-design 9.1).
        var r = ContactRedactor.forContact(null, "123456");
        assertThat(r.freeText("Price 123456 Rs")).isEqualTo("Price 123456 Rs");
        assertThat(ContactRedactor.forContact(null, "234 5678").freeText("Deposit 2345678")).isEqualTo("Deposit 2345678");
    }

    @Test
    void storedChunksLoseTheirContactLine() {
        var stored = "House: Blue gate\nLocality: Indiranagar\nContact: Ramesh Kumar\nNotes: Ramesh is helpful, +91 98450 12345";
        assertThat(ContactRedactor.scrubStoredText(stored, "Ramesh Kumar", "+91 98450 12345"))
                .isEqualTo("House: Blue gate\nLocality: Indiranagar\nNotes: [contact] is helpful, [phone]");
        // House gone from the database: the contact line and phone numbers still go.
        assertThat(ContactRedactor.scrubStoredText(stored, null, null))
                .doesNotContain("Contact:").doesNotContain("98450").contains("Notes: Ramesh is helpful, [phone]");
        assertThat(ContactRedactor.scrubStoredText("Contact: X", null, null)).isEmpty();
    }

    @Test
    void nullsAndBlanksAreSafe() {
        var none = ContactRedactor.forContact(" ", "12");
        assertThat(none.place(null)).isNull();
        assertThat(none.freeText("")).isEmpty();
        assertThat(none.freeText("nothing to hide")).isEqualTo("nothing to hide");
        assertThat(ContactRedactor.forHouse(null).place("x")).isEqualTo("x");
        assertThat(ContactRedactor.scrubStoredText(null, null, null)).isNull();
    }

    // S4b-BL-179: email addresses go whole, before the name rules; the shared vectors pin the same cases.
    @Test
    void emailAddressesAreRemovedWholeInEveryFieldAndBeforeTheNameParts() {
        var suresh = ContactRedactor.forContact("Suresh Rao", null);
        assertThat(suresh.freeText("Mail suresh.rao@gmail.com or sureshrao1983@yahoo.co.in, insta @suresh_rao, https://wa.me/919886055555"))
                .isEqualTo("Mail [email] or [email], insta @[contact], https://wa.me/[phone]");
        var anil = ContactRedactor.forContact("Anil Verma", null);
        assertThat(anil.freeText("Portal https://portal.example/contact?email=suresh.rao@gmail.com&ref=1"))
                .isEqualTo("Portal https://portal.example/contact?email=[email]&ref=1");
        assertThat(anil.freeText("Call 98450 12345 or mail anil@example.com")).isEqualTo("Call [phone] or mail [email]");
        assertThat(anil.freeText("98450 12345,anil@example.com")).isEqualTo("[phone],[email]");
        assertThat(anil.freeText("Mail me at sam@example.com.")).isEqualTo("Mail me at [email].");
        assertThat(anil.place("Shop 4, mail owner@example.org")).isEqualTo("Shop 4, mail [email]");
        assertThat(ContactRedactor.scrubStoredText("Contact: Suresh Rao\nmail suresh@gmail.com ok", "Suresh Rao", null))
                .isEqualTo("mail [email] ok");
        assertThat(ContactRedactor.redactGeneric("Write to a.b@c.in or 98450 12345, see https://example.com/l/123"))
                .isEqualTo("Write to [email] or [phone], see https://example.com/l/123");
        assertThat(ContactRedactor.redactGeneric("Mail ravi+flat3@example.co.in now")).isEqualTo("Mail [email] now");
        assertThat(ContactRedactor.redactGeneric("Mail " + "a".repeat(70) + "@example.com now")).isEqualTo("Mail [email] now");
    }

    @Test
    void anAtSignThatIsNotAnEmailAddressIsKept() {
        var text = "Rent 28k @ month, ask x@y or a@b.";
        assertThat(ContactRedactor.forContact(null, null).freeText(text)).isEqualTo(text);
        assertThat(ContactRedactor.redactGeneric(text)).isEqualTo(text);
    }

    // S4b-BL-180: a name part that is also an ordinary word is replaced wherever it appears (owner decision: a leaked
    // name cannot be recalled, a damaged description can be rephrased). Place fields keep single parts.
    @Test
    void aNamePartThatIsAnOrdinaryWordIsReplacedInFreeTextOnPurpose() {
        assertThat(ContactRedactor.forContact("Rose Bush", null)
                .freeText("Rose garden at the back, a bush hedge, Rose said keys with Rosemary"))
                .isEqualTo("[contact] garden at the back, a [contact] hedge, [contact] said keys with Rosemary");
        assertThat(ContactRedactor.forContact("Will Mark", null)
                .freeText("Owner will mark the parking spot; Will Mark called"))
                .isEqualTo("Owner [contact] the parking spot; [contact] called");
        assertThat(ContactRedactor.forContact("Gold", null).freeText("Gold coloured gate")).isEqualTo("[contact] coloured gate");
        assertThat(ContactRedactor.forContact("Ram", null).freeText("Ram Nagar, Sri Ram Temple road, ramp access"))
                .isEqualTo("[contact] Nagar, Sri [contact] Temple road, ramp access");
        assertThat(ContactRedactor.forContact("Rose Bush", null).place("Rose Bush Lane, Rosewood Park"))
                .isEqualTo("[contact] Lane, Rosewood Park");
        // An initial is not a name part: "K block" and "K R Puram" survive for saved "K. Ramesh".
        assertThat(ContactRedactor.forContact("K. Ramesh", null).freeText("K block near K R Puram"))
                .isEqualTo("K block near K R Puram");
    }
}
