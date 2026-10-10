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

package app.doorprints.shared.ai

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Contact removal on the device, beside the shared vectors (`ParityVectorsTest`): email addresses go whole before the
 * name parts (S4b-BL-179), and a name part that is an ordinary word is replaced on purpose (S4b-BL-180).
 */
class ContactRedactorTest {
    @Test
    fun emailAddressesAreRemovedWholeBeforeTheNameParts() {
        val suresh = ContactRedactor.forContact("Suresh Rao", null)
        assertEquals(
            "Mail [email] or [email], insta @[contact], https://wa.me/[phone]",
            suresh.freeText("Mail suresh.rao@gmail.com or sureshrao1983@yahoo.co.in, insta @suresh_rao, https://wa.me/919886055555"),
        )
        val anil = ContactRedactor.forContact("Anil Verma", null)
        assertEquals(
            "Portal https://portal.example/contact?email=[email]&ref=1",
            anil.freeText("Portal https://portal.example/contact?email=suresh.rao@gmail.com&ref=1"),
        )
        assertEquals("Call [phone] or mail [email]", anil.freeText("Call 98450 12345 or mail anil@example.com"))
        assertEquals("[phone],[email]", anil.freeText("98450 12345,anil@example.com"))
        assertEquals("Mail me at [email].", anil.freeText("Mail me at sam@example.com."))
        assertEquals("Shop 4, mail [email]", anil.place("Shop 4, mail owner@example.org"))
        assertEquals("mail [email] ok", ContactRedactor.scrubStoredText("Contact: Suresh Rao\nmail suresh@gmail.com ok", "Suresh Rao", null))
        assertEquals(
            "Write to [email] or [phone], see https://example.com/l/123",
            ContactRedactor.redactGeneric("Write to a.b@c.in or 98450 12345, see https://example.com/l/123"),
        )
        assertEquals("Mail [email] now", ContactRedactor.redactGeneric("Mail ravi+flat3@example.co.in now"))
        assertEquals("Mail [email] now", ContactRedactor.redactGeneric("Mail " + "a".repeat(70) + "@example.com now"))
    }

    @Test
    fun aSavedLandlineWrittenWithoutItsStdCodeIsRemoved() {
        val r = ContactRedactor.forContact(null, "080 2345 6789")
        assertEquals("reach the office on [phone]", r.freeText("reach the office on 2345 6789"))
        assertEquals("call [phone] or [phone]", r.freeText("call 23456789 or 2345.6789"))
        assertEquals("plot 12345678, deposit 2345 6788", r.freeText("plot 12345678, deposit 2345 6788"))
        // A 10-digit saved number and a landline saved without the 0 have no such variant.
        assertEquals("ext 450 12345", ContactRedactor.forContact(null, "98450 12345").freeText("ext 450 12345"))
        assertEquals("office 2345 6789", ContactRedactor.forContact(null, "8023456789").freeText("office 2345 6789"))
    }

    @Test
    fun anAtSignThatIsNotAnEmailAddressIsKept() {
        val text = "Rent 28k @ month, ask x@y or a@b."
        assertEquals(text, ContactRedactor.forContact(null, null).freeText(text))
        assertEquals(text, ContactRedactor.redactGeneric(text))
    }

    @Test
    fun aNamePartThatIsAnOrdinaryWordIsReplacedInFreeTextOnPurpose() {
        assertEquals(
            "[contact] garden at the back, a [contact] hedge, [contact] said keys with Rosemary",
            ContactRedactor.forContact("Rose Bush", null).freeText("Rose garden at the back, a bush hedge, Rose said keys with Rosemary"),
        )
        assertEquals(
            "Owner [contact] the parking spot; [contact] called",
            ContactRedactor.forContact("Will Mark", null).freeText("Owner will mark the parking spot; Will Mark called"),
        )
        assertEquals("[contact] coloured gate", ContactRedactor.forContact("Gold", null).freeText("Gold coloured gate"))
        assertEquals(
            "[contact] Nagar, Sri [contact] Temple road, ramp access",
            ContactRedactor.forContact("Ram", null).freeText("Ram Nagar, Sri Ram Temple road, ramp access"),
        )
        assertEquals("[contact] Lane, Rosewood Park", ContactRedactor.forContact("Rose Bush", null).place("Rose Bush Lane, Rosewood Park"))
        assertEquals("K block near K R Puram", ContactRedactor.forContact("K. Ramesh", null).freeText("K block near K R Puram"))
    }
}
