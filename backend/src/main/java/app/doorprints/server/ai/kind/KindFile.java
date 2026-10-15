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

package app.doorprints.server.ai.kind;

import java.util.List;
import java.util.Map;

/**
 * A kind file as the server reads it (kinds PR 28, docs/03 ADR-36 §18.22): the parts of {@code docs/schemas/kinds/<id>.json}
 * that the server acts on, validated by {@link KindFiles}. The rest of the file (labels, sections, statuses, the checklist,
 * the questions) is the apps' and is only checked for shape. Immutable.
 *
 * @param id           the kind's id
 * @param version      the file's version
 * @param core         the core field ids the kind shows, in the file's order
 * @param moneyCadences the cadences the kind's own money (core {@code money}) is paid in, in the file's order; empty when the
 *                      kind shows no money
 * @param extras       the kind's typed extras, in the file's order
 * @param search       what the list search reads
 * @param ai           the AI slots and words
 * @param capabilities what the kind can do beyond its fields ({@link Capabilities#DEFAULTS} when the file has none)
 */
public record KindFile(String id, int version, List<String> core, List<String> moneyCadences, List<Extra> extras, List<String> search, Ai ai,
                       Capabilities capabilities) {

    /**
     * One typed extra of the kind file (door 5).
     *
     * @param integer        number only
     * @param min            number and money: the lowest value, or null
     * @param max            number and money: the highest value, or null
     * @param maxLength      text only
     * @param unit           number only
     * @param cadences       money only: the cadences the amount may be paid in; empty with {@code cadenceFromMoney}
     * @param cadenceFromMoney money only: paid as often as the candidate's own money
     * @param options        enum only: the option ids in the file's order
     */
    public record Extra(String id, String type, Boolean integer, Long min, Long max, Integer maxLength, String unit,
                        List<String> cadences, boolean cadenceFromMoney, List<String> options) {
    }

    /**
     * The AI contract of the kind file.
     *
     * @param words the English words of the AI text, or null when the file has none (the kind is then not readable by
     *              the AI: it is read as a house)
     */
    public record Ai(List<String> extract, List<String> ask, List<String> plan, List<String> never, Words words) {
    }

    /**
     * The English words a kind's prompts and documents are written in.
     *
     * @param noun    the first line's key of an Ask document, and the word of a generated label
     * @param records the plural: the Ask records' block name and the word in the Ask refusal
     * @param extras  the words of each typed extra that an AI slot names (and is not known to the server by id)
     */
    public record Words(String noun, String records, Map<String, ExtraWords> extras) {
        /** The Ask refusal sentence of this kind. */
        public String refusal() {
            return "I don't know based on the " + records + " you have saved.";
        }
    }

    /** The words of one extra: the key of its document line and, for an enum, the words of each option id. */
    public record ExtraWords(String words, Map<String, String> options) {
    }

    /** The kind's capabilities (docs/03 §18.19); a feature the file leaves out is null or false as {@link #DEFAULTS}. */
    public record Capabilities(boolean map, Hunt hunt, boolean rooms, boolean moveIn, Contacts contacts,
                               Viewings viewings, Compare compare, boolean paths, Identity identity, boolean baseline,
                               List<String> filters) {
        /** What a kind with no {@code capabilities} has: the map and the paths on, everything else off. */
        public static final Capabilities DEFAULTS = new Capabilities(true, null, false, false, null, null, null, true,
                null, false, List.of());
    }

    /**
     * Hunt mode (docs/03 section 18.20): how near an alert is, in metres (30 to 300), whether a stay at an unsaved place offers to
     * save it, whether the walk tells the person about streets it has saved places on, and whether an area wake-up may start a walk.
     */
    public record Hunt(int alertRadiusM, boolean stayCreates, boolean streetMemory, boolean areas) {
    }

    /** The candidate's contact: whose it is and whether a saved phone makes or links the address-book entry. */
    public record Contacts(String role, boolean autoCreateFromPhone) {
    }

    /** Viewings, with the question bank that seeds them. */
    public record Viewings(String questionsSeed) {
    }

    /** The criteria Compare scores, in order. */
    public record Compare(List<String> criteria) {
    }

    /** The kind's icon and colour in the chooser. */
    public record Identity(String icon, String colour) {
    }
}
