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

import app.doorprints.server.common.BadRequestException;
import app.doorprints.server.house.House;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The kinds this server has available and which kind the AI reads a candidate as (kinds PR 7, S4b-BL-211; docs/03
 * ADR-36 §18.11; renamed in S4b-BL-280; driven by the kind files in kinds PR 28, §18.22), the server's twin of the apps'
 * {@code AVAILABLE_KINDS}: {@code app.kinds.available} ({@code KINDS_AVAILABLE}, a comma-separated list, default
 * {@code house}; the house is always in it). With the house alone every candidate is read as a house is today, its extras
 * unread; with a kind other than the house in the list, a candidate of that kind is read through its kind's {@code ai}
 * slots ({@link AiKind}). An unknown kind is always read as a house; a request that names a kind outside the list is a
 * 400 ({@link #require}).
 *
 * <p>Every kind is read from its kind file ({@link KindFiles#builtIn}: {@code kinds/<id>.json} on the class path, a copy
 * of {@code docs/schemas/kinds}) and its prompt texts ({@link KindPrompts}); a new kind needs its file and nothing in Java.
 * {@link #HOUSE} and {@link #SCHOOL} are two of them, kept as names for the code and the tests that speak of those two.
 */
@Component
public class AiKinds {

    /** Every kind file on the class path that tells the AI its words, by id: the house, the school and any other file. */
    private static final Map<String, AiKind> LOADED = loadAll();

    /** The house, read from {@code house.json}: what a candidate of no kind, an unavailable kind or an unknown kind is read as. */
    public static final AiKind HOUSE = required(House.HOUSE_KIND);

    /** The schools kind, read from {@code school.json}. */
    public static final AiKind SCHOOL = required("school");

    /** The house alone: what every caller that names no list gets. */
    public static final AiKinds OFF = new AiKinds(List.of());

    private final List<String> available;

    public AiKinds(@Value("${app.kinds.available:house}") List<String> available) {
        var list = new ArrayList<String>();
        list.add(House.HOUSE_KIND);
        if (available != null) {
            for (var k : available) {
                var id = k == null ? "" : k.trim();
                if (id.matches(House.KIND_PATTERN) && !list.contains(id)) list.add(id);
            }
        }
        this.available = List.copyOf(list);
    }

    private static Map<String, AiKind> loadAll() {
        var out = new LinkedHashMap<String, AiKind>();
        KindFiles.builtIn().forEach((id, file) -> {
            var kind = AiKind.from(file, KindPrompts.builtIn(id));
            if (kind != null) out.put(id, kind);
        });
        return Collections.unmodifiableMap(out);
    }

    private static AiKind required(String id) {
        var k = LOADED.get(id);
        if (k == null) throw new IllegalStateException("the server has no kind file kinds/" + id + ".json with ai.words");
        return k;
    }

    /** A list of the house and, when {@code schools}, the schools kind (tests and callers that name a flag). */
    public static AiKinds schools(boolean schools) {
        return new AiKinds(schools ? List.of(House.HOUSE_KIND, SCHOOL.id()) : List.of());
    }

    /** The kinds available here, the house first. */
    public List<String> available() {
        return available;
    }

    /** Whether a request may name {@code kind}: none, or one in the list. */
    public boolean isAvailable(String kind) {
        return kind == null || available.contains(kind);
    }

    /** A 400 for a request that names a kind outside {@code app.kinds.available}. */
    public void require(String kind) {
        if (!isAvailable(kind)) throw new BadRequestException("kind " + kind + " is not available");
    }

    /** Whether {@code sentence} is the Ask refusal of a kind other than the house (S4b-BL-269); the house's is {@code AskPrompts.I_DONT_KNOW}. */
    public static boolean isKindRefusal(String sentence) {
        return LOADED.values().stream().anyMatch(k -> !k.id().equals(House.HOUSE_KIND) && k.refusal().equals(sentence));
    }

    /** The AI kind of a candidate of {@code kind}, or null: read as a house (the house, an unavailable or unknown kind). */
    public AiKind of(String kind) {
        return kind != null && !House.HOUSE_KIND.equals(kind) && available.contains(kind) ? LOADED.get(kind) : null;
    }

    /**
     * The kind a candidate of {@code kind} is read as for a document, a prompt or a draft: {@link #of}, else the house
     * ({@link #HOUSE}), which the same code reads through its file like any other.
     */
    public AiKind readAs(String kind) {
        var k = of(kind);
        return k != null ? k : HOUSE;
    }

    /** Whether {@code kind} is in every {@code app.kinds.available}: the baseline kind, the house (its documents carry no kind stamp, S4b-BL-308). */
    public static boolean alwaysAvailable(String kind) {
        return House.HOUSE_KIND.equals(kind);
    }

    /**
     * Whether {@code kind} is the baseline kind (its file's {@code capabilities.baseline}): the one whose AI calls name no kind and whose
     * candidates carry no kind stamp. A kind this server has no file for is not (kinds PR 32).
     */
    public static boolean isBaseline(String kind) {
        var file = kind == null ? null : KindFiles.builtIn().get(kind);
        return file != null && file.capabilities().baseline();
    }

    /** The kind a candidate is of: its own, else the baseline kind's. */
    public static String kindOf(String kind) {
        return kind == null ? House.HOUSE_KIND : kind;
    }

    /** The kind file the server read for {@code kind} (its capabilities among the rest), or null when it has none. */
    public static KindFile file(String kind) {
        return kind == null ? null : KindFiles.builtIn().get(kind);
    }
}
