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

import app.doorprints.server.ai.agent.HouseSearchService;
import app.doorprints.server.ai.config.AiProperties;
import app.doorprints.server.ai.extract.ListingExtractionService;
import app.doorprints.server.ai.rag.RagService;
import app.doorprints.server.common.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The AI endpoints refuse a bad input with a message the caller can read (S4b-BL-159). They answer through
 * {@code ApiExceptionHandler}, which shows only a {@link BadRequestException}'s text and replaces any other
 * {@code IllegalArgumentException}'s with "Malformed request". The checks run before the services touch the model,
 * the vector store or the database, so they are exercised here with none of them (the seam is the public method).
 */
class ClientInputErrorsTest {

    private final AiProperties props = AiProperties.defaults();

    @Test
    void aBlankOrTooLongQuestionIsARefusalWithItsReason() {
        var rag = new RagService(null, null, props, null);
        assertThatThrownBy(() -> rag.ask("  ", null)).isInstanceOf(BadRequestException.class)
                .hasMessage("question must not be blank");
        assertThatThrownBy(() -> rag.ask("q".repeat(props.maxQuestionChars() + 1), null))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("question is longer than");
    }

    @Test
    void aBlankOrTooLongListingIsARefusalWithItsReason() {
        var extraction = new ListingExtractionService(null, props);
        assertThatThrownBy(() -> extraction.extract("")).isInstanceOf(BadRequestException.class)
                .hasMessage("text must not be blank");
        assertThatThrownBy(() -> extraction.extract("t".repeat(props.maxInputChars() + 1)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("text is longer than");
    }

    @Test
    void coordinatesOutsideTheEarthAreARefusalWithItsReason() {
        var search = new HouseSearchService(null);
        assertThatThrownBy(() -> search.nearby(91, 0, 100)).isInstanceOf(BadRequestException.class)
                .hasMessage("invalid coordinates");
        assertThatThrownBy(() -> search.nearby(0, -181, 100)).isInstanceOf(BadRequestException.class);
    }
}
