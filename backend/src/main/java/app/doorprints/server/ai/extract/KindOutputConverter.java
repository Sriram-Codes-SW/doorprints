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

package app.doorprints.server.ai.extract;

import app.doorprints.server.ai.kind.AiKind;
import app.doorprints.server.ai.kind.KindPrompts;
import org.springframework.ai.converter.CompositeResponseTextCleaner;
import org.springframework.ai.converter.MarkdownCodeBlockCleaner;
import org.springframework.ai.converter.ResponseTextCleaner;
import org.springframework.ai.converter.StructuredOutputConverter;
import org.springframework.ai.converter.ThinkingTagCleaner;
import org.springframework.ai.converter.WhitespaceCleaner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The structured answer of a listing extract for any kind (kinds PR 28, docs/03 ADR-36 §18.22): the JSON schema the model is
 * told, built from the kind\'s {@link AiKind#extractFields} (a property per field, in name order, each with its
 * description, none beyond them), and the reading of the model\'s answer into those fields. It replaces one Java record per
 * kind. For the house\'s fields the schema and the format text are byte for byte what Spring AI\'s
 * {@code BeanOutputConverter} wrote for the record it replaces (the house pins, {@code HouseExtractPinsTest}).
 *
 * <p>Only the kind\'s fields are read from the answer; any other property is ignored. A scalar is read as its text (a number
 * for a price becomes "25000"), an object or an array where text belongs is an error, as it was for the record.
 */
public final class KindOutputConverter implements StructuredOutputConverter<KindOutputConverter.Raw> {

    /**
     * The model\'s answer as the sanitizer reads it.
     *
     * @param fields    the text fields by property name, a missing or null one absent
     * @param amenities the list property of the answer (the house\'s amenities), or null
     */
    public record Raw(Map<String, String> fields, List<String> amenities) {
    }

    private static final String FORMAT = "Your response should be in JSON format.\n"
            + "Do not include any explanations, only provide a RFC8259 compliant JSON response following this format without deviation.\n"
            + "Do not include markdown code blocks in your response.\n"
            + "Remove the ```json markdown from the output.\n"
            + "Here is the JSON Schema instance your output must adhere to:\n```%s```\n";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ResponseTextCleaner CLEANER = CompositeResponseTextCleaner.builder()
            .addCleaner(new WhitespaceCleaner()).addCleaner(new ThinkingTagCleaner())
            .addCleaner(new MarkdownCodeBlockCleaner()).addCleaner(new WhitespaceCleaner()).build();

    private final List<KindPrompts.ExtractField> fields;
    private final String schema;

    public KindOutputConverter(AiKind kind) {
        this.fields = kind.extractFields();
        this.schema = schema(fields);
    }

    /** The JSON schema of an answer with these fields: properties and {@code required} in name order. */
    static String schema(List<KindPrompts.ExtractField> fields) {
        var sorted = new TreeMap<String, KindPrompts.ExtractField>();
        fields.forEach(f -> sorted.put(f.name(), f));
        var root = JSON.createObjectNode();
        root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        root.put("type", "object");
        var properties = root.putObject("properties");
        sorted.forEach((name, f) -> {
            var p = properties.putObject(name);
            if (f.list()) {
                p.put("description", f.description());
                p.put("type", "array");
                p.putObject("items").put("type", "string");
            } else {
                p.put("type", "string");
                p.put("description", f.description());
            }
        });
        var required = root.putArray("required");
        sorted.keySet().forEach(required::add);
        root.put("additionalProperties", false);
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    @Override
    public String getJsonSchema() {
        return schema;
    }

    @Override
    public String getFormat() {
        return String.format(FORMAT, schema);
    }

    @Override
    public Raw convert(String source) {
        var node = JSON.readTree(CLEANER.clean(source));
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        if (!node.isObject()) throw new IllegalArgumentException("the answer is not a JSON object");
        var out = new LinkedHashMap<String, String>();
        List<String> amenities = null;
        for (var f : fields) {
            var v = node.get(f.name());
            if (v == null || v.isNull()) continue;
            if (f.list()) {
                if (!v.isArray()) throw new IllegalArgumentException(f.name() + " is not a list");
                amenities = new ArrayList<>();
                for (var item : v) amenities.add(item.isNull() ? null : scalar(item, f.name()));
            } else {
                out.put(f.name(), scalar(v, f.name()));
            }
        }
        return new Raw(out, amenities);
    }

    private static String scalar(JsonNode v, String name) {
        if (v.isObject() || v.isArray()) throw new IllegalArgumentException(name + " is not text");
        return v.asString();
    }
}
