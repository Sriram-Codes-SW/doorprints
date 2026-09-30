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

package app.doorprints.server.photo;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Slice 5: the rules for what a person says about a photo (M6 on the server), without a database. */
class PhotoMetaTest {

    private static List<String> violations(PhotoMeta meta) {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(meta).stream().map(v -> v.getPropertyPath().toString()).sorted().toList();
        }
    }

    @Test
    void goodMetaPassesAndTheLimitsAreInclusive() {
        assertThat(violations(new PhotoMeta(null, null, null, 0))).isEmpty();
        assertThat(violations(new PhotoMeta("c1111111-1111-4111-8111-111111111111",
                List.of("KITCHEN_FITTINGS", "MOVE_IN", "damp corner"), "Kitchen at move-in", 1790813400000L))).isEmpty();
        var ten = new ArrayList<String>(List.of("EXTERIOR", "ENTRANCE", "KITCHEN_FITTINGS", "BATHROOM_FITTINGS", "DAMP",
                "CRACK", "LEAK", "VIEW", "WATER_TANK", "METER"));
        assertThat(violations(new PhotoMeta("r".repeat(64), ten, "c".repeat(200), 1))).isEmpty();
        assertThat(violations(new PhotoMeta(null, List.of("t".repeat(30)), null, 1))).isEmpty();
    }

    @Test
    void m6_aCustomTagEqualToAFixedKeyInAnyCaseIsRefusedButTheFixedKeyIsFine() {
        assertThat(violations(new PhotoMeta(null, List.of("DAMP"), null, 1))).isEmpty();
        for (var bad : List.of("damp", "Damp", "kitchen_fittings", "Move_In")) {
            assertThat(violations(new PhotoMeta(null, List.of(bad), null, 1))).as(bad).containsExactly("tagsClean");
        }
        assertThat(violations(new PhotoMeta(null, List.of("damp corner"), null, 1))).isEmpty();
    }

    @Test
    void m6_aRepeatedTagIgnoringCaseIsRefused() {
        assertThat(violations(new PhotoMeta(null, List.of("old paint", "old paint "), null, 1))).isEmpty();
        assertThat(violations(new PhotoMeta(null, List.of("old paint", "Old Paint"), null, 1)))
                .containsExactly("tagsClean");
        assertThat(violations(new PhotoMeta(null, List.of("LEAK", "LEAK"), null, 1))).containsExactly("tagsClean");
    }

    @Test
    void m6_moreThanTenTagsAnOverLongOrEmptyTagAreRefused() {
        var eleven = new ArrayList<String>();
        for (int i = 0; i < 11; i++) eleven.add("tag " + i);
        assertThat(violations(new PhotoMeta(null, eleven, null, 1))).containsExactly("tags");
        assertThat(violations(new PhotoMeta(null, List.of("t".repeat(31)), null, 1))).containsExactly("tags[0].<list element>");
        assertThat(violations(new PhotoMeta(null, List.of(""), null, 1))).containsExactly("tags[0].<list element>");
        var withNull = new ArrayList<String>();
        withNull.add(null);
        assertThat(violations(new PhotoMeta(null, withNull, null, 1))).containsExactly("tags[0].<list element>");
    }

    @Test
    void aLongCaptionALongRoomIdAndANegativeStampAreRefused() {
        assertThat(violations(new PhotoMeta(null, null, "c".repeat(201), 1))).containsExactly("caption");
        assertThat(violations(new PhotoMeta("r".repeat(65), null, null, 1))).containsExactly("roomId");
        assertThat(violations(new PhotoMeta(null, null, null, -1))).containsExactly("metaUpdatedAt");
    }

    @Test
    void importProblemsNameTheRowAndFieldAndNeverEchoTheText() {
        assertThat(PhotoMeta.problems("photos[0]", null, null, null, null)).isEmpty();
        assertThat(PhotoMeta.problems("photos[0]", "room", List.of("KITCHEN_FITTINGS", "custom"), "caption", 5L)).isEmpty();
        var secret = "secret ".repeat(40);
        var problems = PhotoMeta.problems("photos[2]", "r".repeat(65), List.of("old paint", "Old Paint", "kitchen_fittings", secret),
                "c".repeat(201), -1L);
        assertThat(problems).contains("photos[2].roomId is out of range", "photos[2].caption is out of range",
                "photos[2].metaUpdatedAt must not be negative", "photos[2].tags[3] is out of range",
                "photos[2].tags repeats a tag");
        assertThat(String.join(" ", problems)).doesNotContain("secret").doesNotContain("old paint").doesNotContain("Old Paint");
        assertThat(PhotoMeta.problems("photos[0]", null, List.of("kitchen_fittings"), null, null))
                .containsExactly("photos[0].tags spells a fixed key in another case");
        var eleven = new ArrayList<String>();
        for (int i = 0; i < 11; i++) eleven.add("tag " + i);
        assertThat(PhotoMeta.problems("photos[0]", null, eleven, null, null))
                .containsExactly("photos[0].tags has more than 10 tags");
    }

    @Test
    void tagsGoToTheColumnAsCompactJsonAndBack() {
        assertThat(PhotoMeta.writeTags(null)).isNull();
        assertThat(PhotoMeta.writeTags(List.of())).isNull();
        assertThat(PhotoMeta.parseTags("[]")).isNull();
        assertThat(PhotoMeta.parseTags(null)).isNull();
        var json = PhotoMeta.writeTags(List.of("MOVE_IN", "damp corner"));
        assertThat(json).isEqualTo("[\"MOVE_IN\",\"damp corner\"]");
        assertThat(PhotoMeta.parseTags(json)).containsExactly("MOVE_IN", "damp corner");
    }
}
