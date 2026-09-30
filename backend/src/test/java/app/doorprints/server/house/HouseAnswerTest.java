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

package app.doorprints.server.house;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Slice 3a: a house's viewing answers, their limits, their text form for the entity and the tombstone. No database. */
class HouseAnswerTest {

    private static HouseAnswer answer(String id) {
        return new HouseAnswer(id, "qd_water", "How is the water supply?", "Borewell and corporation", "ANSWERED", 0);
    }

    private static HouseDto house(List<HouseAnswer> answers) {
        return new HouseDto(UUID.randomUUID(), "Green View", null, null, null, 12.9, 77.6, HouseStatus.NEW, null, null,
                null, null, null, null, null, null, null, null, null, null, answers, null, null, Map.of(), null, null, false,
                0, null);
    }

    private static List<String> violations(Validator validator, List<HouseAnswer> answers) {
        return validator.validate(house(answers)).stream().map(v -> v.getPropertyPath().toString()).sorted().toList();
    }

    @Test
    void aGoodListOrNoListPassesAndTheLimitsAreInclusive() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(violations(validator, null)).isEmpty();
            assertThat(violations(validator, List.of())).isEmpty();
            assertThat(violations(validator, List.of(answer("a1111111-1111-4111-8111-111111111111")))).isEmpty();
            var edge = new HouseAnswer("a.b_c-9".repeat(9) + "x", "q.b_c-9".repeat(9) + "x", "t".repeat(300),
                    "a".repeat(2000), "SKIPPED", 0);
            assertThat(edge.id()).hasSize(64);
            assertThat(violations(validator, List.of(edge))).isEmpty();
            // Only the id and the text are required: an open question has no answer, no status and no bank link.
            assertThat(violations(validator, List.of(new HouseAnswer("a", null, "Is it open?", null, null, null))))
                    .isEmpty();
            var sixty = new ArrayList<HouseAnswer>();
            for (int i = 0; i < HouseAnswer.MAX; i++) sixty.add(answer("a" + i));
            assertThat(violations(validator, sixty)).isEmpty();
        }
    }

    @Test
    void eachFieldOutOfRangeIsNamed() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var bad = List.of(
                    new HouseAnswer("a", "has space", "Q", null, null, null),
                    new HouseAnswer("a", "..", "Q", null, null, null),
                    new HouseAnswer("a", null, null, null, null, null),
                    new HouseAnswer("a", null, "  ", null, null, null),
                    new HouseAnswer("a", null, "t".repeat(301), null, null, null),
                    new HouseAnswer("a", null, "Q", "a".repeat(2001), null, null),
                    new HouseAnswer("a", null, "Q", null, "DONE", null),
                    new HouseAnswer("a", null, "Q", null, null, -1));
            var paths = List.of("answers[0].questionId", "answers[0].questionId", "answers[0].text",
                    "answers[0].text", "answers[0].text", "answers[0].answer", "answers[0].status",
                    "answers[0].sort");
            for (int i = 0; i < bad.size(); i++) {
                assertThat(violations(validator, List.of(bad.get(i)))).as("case %d", i).containsExactly(paths.get(i));
            }
        }
    }

    @Test
    void aBadIdIsRefused() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (var id : new String[]{null, "", "x".repeat(65), "has space", "a/b", "..", "."}) {
                assertThat(violations(validator, List.of(answer(id)))).as("id '%s'", id)
                        .containsExactly("answers[0].id");
            }
        }
    }

    @Test
    void aSixtyFirstAnswerAndADuplicateIdAreRefused() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var many = new ArrayList<HouseAnswer>();
            for (int i = 0; i <= HouseAnswer.MAX; i++) many.add(answer("a" + i));
            assertThat(violations(validator, many)).containsExactly("answers");
            assertThat(violations(validator, List.of(answer("same"), answer("same"))))
                    .containsExactly("answerIdsUnique");
            assertThat(validator.validate(house(List.of(answer("same"), answer("same")))))
                    .extracting(v -> v.getMessage()).containsExactly("answers must not repeat an id");
            // Ids are compared exactly: case matters, like a record id.
            assertThat(violations(validator, List.of(answer("a"), answer("A")))).isEmpty();
        }
    }

    @Test
    void anEmptyListIsNoAnswersAndTheTextFormIsCompactInPropertyOrder() {
        assertThat(HouseAnswer.write(List.of())).isNull();
        assertThat(HouseAnswer.write(null)).isNull();
        assertThat(HouseAnswer.parse("[]")).isNull();
        assertThat(HouseAnswer.parse(null)).isNull();
        assertThat(HouseAnswer.parse(" ")).isNull();
        var answers = List.of(answer("a"), new HouseAnswer("b", null, "Open one?", null, "OPEN", 1));
        var json = HouseAnswer.write(answers);
        assertThat(json).isEqualTo("[{\"id\":\"a\",\"questionId\":\"qd_water\",\"text\":\"How is the water supply?\","
                + "\"answer\":\"Borewell and corporation\",\"status\":\"ANSWERED\",\"sort\":0},"
                + "{\"id\":\"b\",\"text\":\"Open one?\",\"status\":\"OPEN\",\"sort\":1}]");
        assertThat(HouseAnswer.parse(json)).isEqualTo(answers);
    }

    @Test
    void problemsNameEveryFieldOutOfRangeByIndexAndNeverEchoUserText() {
        assertThat(HouseAnswer.problems(null)).isEmpty();
        assertThat(HouseAnswer.problems(List.of(answer("a"), answer("b")))).isEmpty();
        var many = new ArrayList<HouseAnswer>();
        for (int i = 0; i <= HouseAnswer.MAX; i++) many.add(answer("a" + i));
        assertThat(HouseAnswer.problems(many)).containsExactly("answers has more than 60 answers");
        assertThat(HouseAnswer.problems(List.of(answer("a"), answer("a"))))
                .containsExactly("answers[1].id is repeated");
        assertThat(HouseAnswer.problems(List.of(new HouseAnswer("a b", "x y", "t".repeat(301), "a".repeat(2001),
                "DONE", -1))))
                .containsExactly("answers[0].id is out of range", "answers[0].questionId is out of range",
                        "answers[0].text is out of range", "answers[0].answer is out of range",
                        "answers[0].status is out of range", "answers[0].sort is out of range");
        assertThat(HouseAnswer.problems(List.of(new HouseAnswer("a", null, " ", null, null, null))))
                .containsExactly("answers[0].text is out of range");
    }

    @Test
    void orderedPutsOpenFirstThenSortThenIdAndReadsCoercesTheStatus() {
        var answered = new HouseAnswer("b", null, "Q1", "yes", "ANSWERED", 0);
        var typedWhileOpen = new HouseAnswer("a", null, "Q2", "yes", "OPEN", 1);
        var noAnswer = new HouseAnswer("c", null, "Q3", null, "ANSWERED", 2);
        var open2 = new HouseAnswer("e", null, "Q4", null, "OPEN", 2);
        var skipped = new HouseAnswer("d", null, "Q5", null, "SKIPPED", 0);
        assertThat(typedWhileOpen.reads()).isEqualTo("ANSWERED");
        assertThat(noAnswer.reads()).isEqualTo("OPEN");
        assertThat(skipped.reads()).isEqualTo("SKIPPED");
        assertThat(HouseAnswer.ordered(List.of(answered, typedWhileOpen, noAnswer, open2, skipped)))
                .extracting(HouseAnswer::id).containsExactly("c", "e", "b", "d", "a");
    }

    @Test
    void theAnswersGoToTheEntityAndBackAndAPutWithoutThemClearsThem() {
        var entity = new House(UUID.randomUUID());
        var answers = List.of(answer("a"), answer("b"));
        house(answers).applyTo(entity);
        assertThat(entity.getAnswers()).startsWith("[{\"id\":\"a\"");
        assertThat(HouseDto.from(entity).answers()).isEqualTo(answers);
        house(List.of()).applyTo(entity);
        assertThat(entity.getAnswers()).isNull();
        assertThat(HouseDto.from(entity).answers()).isNull();
    }

    /** F-16 and PRV-005: a tombstone keeps no answer, which may name people or hold a phone number. */
    @Test
    void aTombstoneBlanksTheAnswers() {
        var entity = new House(UUID.randomUUID());
        house(List.of(answer("a"))).applyTo(entity);
        entity.purgeContent();
        assertThat(entity.getAnswers()).isNull();
        assertThat(HouseDto.from(entity).answers()).isNull();
    }
}
