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

/**
 * The three response schemas are written in the Gemini dialect (`nullable: true`, optional properties). An
 * OpenAI-compatible provider wants strict JSON Schema (docs/03 §13.2, section `schemaDialect` of the parity vectors).
 */

type Node = Record<string, unknown>;

const isNode = (v: unknown): v is Node => typeof v === 'object' && v !== null && !Array.isArray(v);

/**
 * Gemini dialect to strict JSON Schema, for every node: `nullable` is removed and, where it was true, `type` becomes
 * `[T, "null"]`; `description` and `items` are kept (items converted in turn); every `object` gets
 * `additionalProperties: false` and `required` set to all its property names. Key order is the input's, with `required`
 * then `additionalProperties` last.
 */
export function toStrictSchema(schema: object): object {
  return convert(schema as Node);
}

function convert(node: Node): Node {
  const nullable = node['nullable'] === true;
  const out: Node = {};
  for (const [key, value] of Object.entries(node)) {
    if (key === 'nullable' || key === 'required') continue;
    if (key === 'type' && nullable && typeof value === 'string') out[key] = [value, 'null'];
    else if (key === 'properties' && isNode(value)) out[key] = Object.fromEntries(Object.entries(value).map(([k, v]) => [k, isNode(v) ? convert(v) : v]));
    else if (key === 'items' && isNode(value)) out[key] = convert(value);
    else out[key] = value;
  }
  if (node['type'] === 'object') {
    out['required'] = Object.keys(isNode(node['properties']) ? node['properties'] : {});
    out['additionalProperties'] = false;
  }
  return out;
}

/** What tiers 2 and 3 add to the system prompt: a blank line, then the shape the answer must have. */
export function schemaTrailer(strict: object): string {
  return `Reply with only a JSON object of this shape: ${JSON.stringify(strict)}`;
}
