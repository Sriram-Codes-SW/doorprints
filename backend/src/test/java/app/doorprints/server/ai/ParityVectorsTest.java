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

import app.doorprints.server.ai.agent.RouteOptimizer;
import app.doorprints.server.ai.extract.DraftSanitizer;
import app.doorprints.server.ai.extract.RawListing;
import app.doorprints.server.ai.rag.AskPrompts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The server's AI safety code against the shared test vectors (docs/ai/evals/parity-vectors.json; docs/03 §13.1,
 * ADR-26): contact removal, the listing checks, the Ask snippet and citation markers, the walking route, and the statuses in the running. The same
 * file is checked by the phones' Kotlin (`ParityVectorsTest` in `:shared`) and the website's TypeScript, so on-device
 * AI treats text exactly as the server does. The expected values are the server's own answers: run this test with
 * {@code -Dparity.write=true} to fill them in after changing the inputs, and review the diff. The regional vectors
 * (S4b-BL-174: Indian phone formats, price styles, routes across India, rupee grouping) were written by hand or with an
 * independent Python haversine, never filled in this way; do not run the write mode over them without reading the diff.
 */
class ParityVectorsTest {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final Path FILE = Path.of("..", "docs", "ai", "evals", "parity-vectors.json");

    @Test
    void theServerGivesTheRecordedAnswers() throws Exception {
        var root = (ObjectNode) JSON.readTree(FILE.toFile());
        var computed = compute(root);
        if (Boolean.getBoolean("parity.write")) {
            Files.writeString(FILE, JSON.writeValueAsString(computed) + "\n");
            return;
        }
        // Through text, so a long written as 529 compares equal to the int 529 read back.
        assertThat(JSON.readTree(JSON.writeValueAsString(computed))).isEqualTo(root);
    }

    private static ObjectNode compute(ObjectNode in) throws Exception {
        var out = in.deepCopy();
        for (var c : (ArrayNode) out.get("redact")) {
            var o = (ObjectNode) c;
            var name = text(o.get("name"));
            var phone = text(o.get("phone"));
            var input = o.get("input").asText();
            var r = ContactRedactor.forContact(name, phone);
            var result = switch (o.get("method").asText()) {
                case "place" -> r.place(input);
                case "freeText" -> r.freeText(input);
                case "scrub" -> ContactRedactor.scrubStoredText(input, name, phone);
                case "phones" -> ContactRedactor.redactPhones(input);
                default -> throw new IllegalArgumentException(o.get("method").asText());
            };
            o.put("expected", result);
        }
        for (var c : (ArrayNode) out.get("sanitize")) {
            var o = (ObjectNode) c;
            var raw = o.get("raw").isNull() ? null : JSON.treeToValue(o.get("raw"), RawListing.class);
            o.set("expected", JSON.valueToTree(DraftSanitizer.sanitize(raw, o.get("source").asText())));
        }
        for (var c : (ArrayNode) out.get("snippet")) {
            var o = (ObjectNode) c;
            o.put("expected", AskPrompts.snippet(o.get("doc").asText(), o.get("question").asText(), 240));
        }
        var inline = app.doorprints.server.ai.rag.RagService.class.getDeclaredMethod("inlineIds", String.class);
        inline.setAccessible(true);
        var ids = JSON.createArrayNode();
        for (var c : (ArrayNode) in.get("inlineIds")) {
            var o = JSON.createObjectNode();
            o.put("input", c.isObject() ? c.get("input").asText() : c.asText());
            o.set("expected", JSON.valueToTree(inline.invoke(null, o.get("input").asText())));
            ids.add(o);
        }
        out.set("inlineIds", ids);
        for (var c : (ArrayNode) out.get("inTheRunning")) {
            var o = (ObjectNode) c;
            o.put("expected", app.doorprints.server.house.HouseStatus.valueOf(o.get("status").asText()).inTheRunning());
        }
        routeLegs((ObjectNode) out.get("route"));
        // S4b-BL-174: routes across India (Mumbai, Chennai to Guwahati, the extremes from Kanyakumari, the hills).
        for (var r : (ArrayNode) out.get("routes")) routeLegs((ObjectNode) r);
        return out;
    }

    /** Fills {@code nearestNeighbour} and {@code inOrder} of one route entry ({@code start}, {@code points}). */
    private static void routeLegs(ObjectNode route) {
        var start = route.get("start");
        var points = new ArrayList<RouteOptimizer.Point>();
        for (var p : route.get("points")) points.add(new RouteOptimizer.Point(p.get(0).asText(), p.get(1).asDouble(), p.get(2).asDouble()));
        route.set("nearestNeighbour", legs(RouteOptimizer.nearestNeighbour(start.get(0).asDouble(), start.get(1).asDouble(), points)));
        route.set("inOrder", legs(RouteOptimizer.legsInOrder(start.get(0).asDouble(), start.get(1).asDouble(), points)));
    }

    private static ArrayNode legs(List<RouteOptimizer.Leg> legs) {
        var a = JSON.createArrayNode();
        for (var l : legs) {
            var o = JSON.createObjectNode();
            o.put("id", l.to().id());
            o.put("meters", Math.round(l.meters()));
            o.put("walkMinutes", l.walkMinutes());
            a.add(o);
        }
        return a;
    }

    private static String text(JsonNode n) {
        return n == null || n.isNull() ? null : n.asText();
    }
}
