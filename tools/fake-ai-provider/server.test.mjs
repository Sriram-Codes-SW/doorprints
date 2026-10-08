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

// The fake provider server's own tests (node --test tools/fake-ai-provider). Expected shapes are the vendors' documented
// ones, cited in server.mjs; nothing is recomputed from the server's code.
import { after, before, beforeEach, describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { start } from './server.mjs';

const golden = JSON.parse(fs.readFileSync(new URL('../../docs/ai/evals/golden-set.json', import.meta.url), 'utf8'));
let srv;
let base;
before(async () => { srv = await start({ golden }); base = `http://127.0.0.1:${srv.port}`; });
after(() => srv.close());
beforeEach(() => { Object.assign(srv.state, { mode: 'ok', cors: 'permissive', key: null, requests: [], redirected: 0 }); });

const SCHEMA = { type: 'object', properties: { answer: { type: 'string' }, citedHouseIds: { type: 'array', items: { type: 'string' } } }, required: ['answer', 'citedHouseIds'], additionalProperties: false };
const post = (p, headers, body, extra = {}) =>
  fetch(base + p, { method: 'POST', redirect: 'manual', headers: { 'content-type': 'application/json', ...headers }, body: typeof body === 'string' ? body : JSON.stringify(body), ...extra });
const oaBody = (rf, extra = {}) => ({ model: 'm', messages: [{ role: 'system', content: 'Reply as JSON.' }, { role: 'user', content: 'hi' }], ...(rf ? { response_format: rf } : {}), ...extra });
const strictRf = { type: 'json_schema', json_schema: { name: 'answer', strict: true, schema: SCHEMA } };
const oa = (body, mode, headers = { authorization: 'Bearer k' }) => post('/v1/chat/completions', { ...(mode ? { 'x-fake-mode': mode } : {}), ...headers }, body);
const anBody = (extra = {}) => ({ model: 'm', max_tokens: 20, messages: [{ role: 'user', content: 'hi' }], tools: [{ name: 'answer', description: 'd', input_schema: SCHEMA }], tool_choice: { type: 'tool', name: 'answer' }, ...extra });
const anHeaders = { 'x-api-key': 'k', 'anthropic-version': '2023-06-01' };
const an = (body, mode, headers = anHeaders) => post('/v1/messages', { ...(mode ? { 'x-fake-mode': mode } : {}), ...headers }, body);
const gmBody = { contents: [{ role: 'user', parts: [{ text: 'hi' }] }], generationConfig: { responseMimeType: 'application/json', responseSchema: { type: 'object', properties: { answer: { type: 'string' } } } } };
const gm = (body, mode, headers = { 'x-goog-api-key': 'k' }) => post('/v1beta/models/g:generateContent', { ...(mode ? { 'x-fake-mode': mode } : {}), ...headers }, body);

describe('OpenAI-style /chat/completions', () => {
  it('answers a strict json_schema request with choices[0].message.content holding the schema shape', async () => {
    const r = await oa(oaBody(strictRf));
    assert.equal(r.status, 200);
    const j = await r.json();
    assert.equal(j.choices[0].finish_reason, 'stop');
    assert.deepEqual(Object.keys(JSON.parse(j.choices[0].message.content)), ['answer', 'citedHouseIds']);
  });
  it('serves the route under any base path', async () => {
    for (const p of ['/chat/completions', '/v1/chat/completions', '/openai/v1/chat/completions', '/api/v1/chat/completions']) {
      assert.equal((await post(p, { authorization: 'Bearer k' }, oaBody(null))).status, 200, p);
    }
  });
  it('refuses a missing bearer key with 401 and the documented error object', async () => {
    const r = await oa(oaBody(null), undefined, {});
    assert.equal(r.status, 401);
    const e = (await r.json()).error;
    assert.equal(e.code, 'invalid_api_key');
    assert.equal(typeof e.message, 'string');
  });
  it('takes no key in ollama mode, which ignores it', async () => {
    const r = await oa(oaBody(null), 'ollama', {});
    assert.equal(r.status, 200);
    assert.equal((await r.json()).system_fingerprint, 'fp_ollama');
  });
  it('refuses a key other than the configured one', async () => {
    srv.state.key = 'right';
    assert.equal((await oa(oaBody(null), undefined, { authorization: 'Bearer wrong' })).status, 401);
    assert.equal((await oa(oaBody(null), undefined, { authorization: 'Bearer right' })).status, 200);
  });
  it('answers 400 for malformed requests instead of passing them', async () => {
    assert.equal((await oa({ messages: oaBody(null).messages })).status, 400, 'no model');
    assert.equal((await oa({ model: 'm', messages: [] })).status, 400, 'no messages');
    assert.equal((await oa('not json')).status, 400, 'not json');
    assert.equal((await post('/v1/chat/completions', { authorization: 'Bearer k', 'content-type': 'text/plain' }, oaBody(null))).status, 400, 'content type');
    assert.equal((await oa(oaBody({ type: 'xml' }))).status, 400, 'response_format type');
    const loose = { type: 'json_schema', json_schema: { name: 'a', strict: true, schema: { type: 'object', properties: { a: { type: 'string' } } } } };
    const r = await oa(oaBody(loose));
    assert.equal(r.status, 400, 'strict schema without additionalProperties false');
    assert.match((await r.json()).error.message, /additionalProperties/);
    assert.equal((await oa(oaBody({ type: 'json_object' }, { messages: [{ role: 'user', content: 'hello' }] }))).status, 400, 'json_object without the word json');
  });
  it('refuses a key in the URL', async () => {
    assert.equal((await post('/v1/chat/completions?api_key=k', { authorization: 'Bearer k' }, oaBody(null))).status, 400);
  });
  it('moves the ladder: json-object-only refuses json_schema naming response_format but takes json_object', async () => {
    const r = await oa(oaBody(strictRf), 'json-object-only');
    assert.equal(r.status, 400);
    assert.match((await r.json()).error.message.toLowerCase(), /response_format/);
    assert.equal((await oa(oaBody({ type: 'json_object' }), 'json-object-only')).status, 200);
  });
  it('no-structured refuses both formats and takes a plain request', async () => {
    assert.equal((await oa(oaBody(strictRf), 'no-structured')).status, 400);
    const r = await oa(oaBody({ type: 'json_object' }), 'no-structured');
    assert.equal(r.status, 400);
    assert.match((await r.json()).error.message, /response_format/);
    assert.equal((await oa(oaBody(null), 'no-structured')).status, 200);
  });
  it('reads the shape from the system trailer when no response_format is sent', async () => {
    const system = `Reply as JSON.\n\nReply with only a JSON object of this shape: ${JSON.stringify(SCHEMA)}`;
    const r = await oa({ model: 'm', messages: [{ role: 'system', content: system }, { role: 'user', content: 'hi' }] });
    assert.deepEqual(Object.keys(JSON.parse((await r.json()).choices[0].message.content)), ['answer', 'citedHouseIds']);
  });
  it('wraps JSON in a fence, returns no choices, or cuts the JSON with finish_reason length', async () => {
    assert.match((await (await oa(oaBody(strictRf), 'fenced')).json()).choices[0].message.content, /^```json\n\{.*\}\n```$/s);
    assert.deepEqual((await (await oa(oaBody(strictRf), 'empty-choices')).json()).choices, []);
    const t = (await (await oa(oaBody(strictRf), 'truncated')).json()).choices[0];
    assert.equal(t.finish_reason, 'length');
    assert.throws(() => JSON.parse(t.message.content));
  });
  it('maps each error mode to its status, and 429 carries Retry-After', async () => {
    for (const [mode, status] of [['unauthorized', 401], ['forbidden', 403], ['not-found', 404], ['rate-limit', 429], ['server-error', 500], ['overloaded', 529], ['ollama-not-found', 404]]) {
      assert.equal((await oa(oaBody(null), mode)).status, status, mode);
    }
    assert.equal((await oa(oaBody(null), 'rate-limit')).headers.get('retry-after'), '7');
  });
  it('words errors as each vendor documents: OpenAI code, Groq message and type only, OpenRouter 200 with only error', async () => {
    assert.equal((await (await oa(oaBody(null), 'not-found')).json()).error.code, 'model_not_found');
    const groq = (await (await oa(oaBody(null), 'groq-rate-limit')).json()).error;
    assert.deepEqual(Object.keys(groq).sort(), ['message', 'type']);
    const r = await oa(oaBody(null), 'openrouter-200-error');
    assert.equal(r.status, 200);
    const j = await r.json();
    assert.equal(j.error.code, 502);
    assert.equal(j.choices, undefined);
  });
  it('answers a redirect with 302 and counts nobody following it', async () => {
    const r = await oa(oaBody(null), 'redirect');
    assert.equal(r.status, 302);
    assert.equal(r.headers.get('location'), '/__redirected');
    assert.equal((await (await fetch(`${base}/__requests`)).json()).redirected, 0);
  });
  it('sends headers and then stalls the body', async () => {
    const r = await oa(oaBody(null), 'stall');
    assert.equal(r.status, 200);
    const reader = r.body.getReader();
    const first = await reader.read();
    assert.ok(!first.done && first.value.length > 0, 'a first chunk arrives');
    const next = await Promise.race([reader.read(), new Promise((ok) => setTimeout(() => ok('stalled'), 300))]);
    assert.equal(next, 'stalled');
    await reader.cancel();
  });
});

describe('Anthropic /v1/messages', () => {
  it('answers a forced tool with a tool_use block whose input fits the schema', async () => {
    const j = await (await an(anBody())).json();
    assert.equal(j.stop_reason, 'tool_use');
    const call = j.content.find((b) => b.type === 'tool_use');
    assert.equal(call.name, 'answer');
    assert.deepEqual(Object.keys(call.input), ['answer', 'citedHouseIds']);
  });
  it('requires x-api-key (401 authentication_error) and anthropic-version (400)', async () => {
    const r = await an(anBody(), undefined, { 'anthropic-version': '2023-06-01' });
    assert.equal(r.status, 401);
    assert.deepEqual(await r.json(), { type: 'error', error: { type: 'authentication_error', message: 'x-api-key header is required' }, request_id: 'req_fake' });
    assert.equal((await an(anBody(), undefined, { 'x-api-key': 'k' })).status, 400);
  });
  it('refuses tools without tool_choice, or a tool_choice naming no tool, or no max_tokens', async () => {
    assert.equal((await an(anBody({ tool_choice: undefined }))).status, 400);
    assert.equal((await an(anBody({ tool_choice: { type: 'auto' } }))).status, 400);
    assert.equal((await an(anBody({ tool_choice: { type: 'tool', name: 'other' } }))).status, 400);
    assert.equal((await an(anBody({ max_tokens: undefined }))).status, 400);
  });
  it('takes a tool-less ping and answers text that stopped at max_tokens', async () => {
    const j = await (await an(anBody({ tools: undefined, tool_choice: undefined, max_tokens: 5 }))).json();
    assert.ok(Array.isArray(j.content));
    assert.equal(j.stop_reason, 'max_tokens');
  });
  it('answers max_tokens, a text-only reply and the forced-tool refusal of newer models', async () => {
    assert.equal((await (await an(anBody(), 'max-tokens')).json()).stop_reason, 'max_tokens');
    assert.ok((await (await an(anBody(), 'text-only')).json()).content.every((b) => b.type === 'text'));
    const r = await an(anBody(), 'no-forced-tool');
    assert.equal(r.status, 400);
    assert.match((await r.json()).error.message, /not supported for this model/);
  });
  it('maps statuses with the documented error types and Retry-After on 429', async () => {
    for (const [mode, status, type] of [['unauthorized', 401, 'authentication_error'], ['forbidden', 403, 'permission_error'], ['not-found', 404, 'not_found_error'], ['rate-limit', 429, 'rate_limit_error'], ['server-error', 500, 'api_error'], ['overloaded', 529, 'overloaded_error']]) {
      const r = await an(anBody(), mode);
      assert.equal(r.status, status, mode);
      const j = await r.json();
      assert.equal(j.type, 'error');
      assert.equal(j.error.type, type);
    }
    assert.equal((await an(anBody(), 'rate-limit')).headers.get('retry-after'), '7');
  });
  it('refuses a browser (an Origin) that lacks anthropic-dangerous-direct-browser-access', async () => {
    const r = await an(anBody(), undefined, { ...anHeaders, origin: 'http://page.test' });
    assert.equal(r.status, 400);
    assert.match((await r.json()).error.message, /anthropic-dangerous-direct-browser-access/);
    assert.equal((await an(anBody(), undefined, { ...anHeaders, origin: 'http://page.test', 'anthropic-dangerous-direct-browser-access': 'true' })).status, 200);
  });
});

describe('Gemini generateContent', () => {
  it('answers candidates[0].content.parts[0].text with the JSON', async () => {
    const j = await (await gm(gmBody)).json();
    assert.deepEqual(Object.keys(JSON.parse(j.candidates[0].content.parts[0].text)), ['answer']);
  });
  it('requires x-goog-api-key and a JSON-mode payload', async () => {
    assert.equal((await gm(gmBody, undefined, {})).status, 403);
    assert.equal((await gm({ contents: gmBody.contents })).status, 400);
  });
  it('words a bad key as Google does (400 with API_KEY_INVALID) and a quota stop as RESOURCE_EXHAUSTED', async () => {
    const bad = await gm(gmBody, 'bad-key');
    assert.equal(bad.status, 400);
    assert.match(JSON.stringify(await bad.json()), /API_KEY_INVALID/);
    const q = await gm(gmBody, 'rate-limit');
    assert.equal(q.status, 429);
    assert.equal((await q.json()).error.status, 'RESOURCE_EXHAUSTED');
  });
});

describe('CORS', () => {
  const pre = (p, asked, origin = 'http://page.test') =>
    fetch(base + p, { method: 'OPTIONS', headers: { origin, 'access-control-request-method': 'POST', 'access-control-request-headers': asked } });
  it('permissive mode allows whatever is asked', async () => {
    const r = await pre('/v1/chat/completions', 'authorization, content-type, x-api-key');
    assert.equal(r.status, 204);
    assert.match(r.headers.get('access-control-allow-headers'), /x-api-key/);
  });
  it('vendor-like mode lists only the family headers, so a stray x-api-key on the OpenAI route is not allowed', async () => {
    srv.state.cors = 'vendor-like';
    const list = (await pre('/v1/chat/completions', 'authorization, content-type, x-api-key')).headers.get('access-control-allow-headers').split(', ');
    assert.ok(list.includes('authorization') && list.includes('content-type'));
    assert.ok(!list.includes('x-api-key'));
    assert.ok((await pre('/v1/messages', 'x-api-key')).headers.get('access-control-allow-headers').includes('anthropic-dangerous-direct-browser-access'));
    assert.ok((await pre('/v1beta/models/g:generateContent', 'x-goog-api-key')).headers.get('access-control-allow-headers').includes('x-goog-api-key'));
  });
  it('exposes Retry-After to a page only in permissive mode', async () => {
    const call = () => oa(oaBody(null), 'rate-limit', { authorization: 'Bearer k', origin: 'http://page.test' });
    assert.equal((await call()).headers.get('access-control-expose-headers'), 'retry-after');
    srv.state.cors = 'vendor-like';
    assert.equal((await call()).headers.get('access-control-expose-headers'), null);
    assert.equal((await call()).headers.get('access-control-allow-origin'), 'http://page.test');
  });
});

describe('control and golden answers', () => {
  it('POST /__mode sets the mode for later requests and /__requests records and clears them', async () => {
    await fetch(`${base}/__mode`, { method: 'POST', body: JSON.stringify({ mode: 'server-error' }) });
    assert.equal((await oa(oaBody(null))).status, 500);
    const log = await (await fetch(`${base}/__requests`)).json();
    assert.equal(log.requests.length, 1);
    assert.equal(log.requests[0].headers.authorization, 'Bearer k');
    assert.equal(log.requests[0].body.model, 'm');
    await fetch(`${base}/__requests`, { method: 'DELETE' });
    assert.equal((await (await fetch(`${base}/__requests`)).json()).requests.length, 0);
  });
  it('answers an extract case of the golden set with its expected values', async () => {
    const c = golden.cases.find((g) => g.id === 'extract-01-whatsapp-rent');
    const listing = { type: 'object', properties: { price: { type: ['string', 'null'] }, priceType: { type: ['string', 'null'] }, bedrooms: { type: ['string', 'null'] }, amenities: { type: 'array', items: { type: 'string' } } } };
    const r = await oa({ model: 'm', messages: [{ role: 'system', content: 'JSON please' }, { role: 'user', content: c.input.text }], response_format: { type: 'json_schema', json_schema: { name: 'listing', strict: false, schema: listing } } });
    const got = JSON.parse((await r.json()).choices[0].message.content);
    assert.equal(got.price, String(c.expected.price));
    assert.equal(got.bedrooms, String(c.expected.bedrooms));
    assert.deepEqual(got.amenities, c.expected.amenitiesInclude);
  });
});
