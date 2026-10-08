#!/usr/bin/env node
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

// A fake AI provider server (S4b-BL-175, docs/03 section 13.2, docs/06 TC-AI-24): speaks the public wire formats of the
// providers the app supports so the real HTTP clients (OkHttp on Android, fetch in a browser) run against a real socket.
// It checks each request like a vendor would, so a wrong request fails a test instead of passing silently.
//   node tools/fake-ai-provider/server.mjs [--port N] [--golden docs/ai/evals/golden-set.json] [--static DIR]
// Port: --port, else FAKE_AI_PORT, else 0 (any free port); prints {"listening":PORT}. Loopback only. Node built-ins only.
// Control: POST /__mode {mode, cors, key} (GET shows it); a request header `x-fake-mode` wins for one request;
// GET /__requests lists what arrived (DELETE clears); GET /__redirected counts redirect followers; GET /__static/F serves DIR/F.
// Sources (fetched 2026-10-08) are cited at each shape; behaviour no source states is marked "assumed".
//   OpenAI errors  https://developers.openai.com/api/docs/guides/error-codes  (error.message/type/code/param)
//   Groq errors    https://console.groq.com/docs/errors  ({error:{message,type}})
//   OpenRouter     https://openrouter.ai/docs/api-reference/errors  ({error:{code,message}}; HTTP 200 with only `error`; Retry-After on 429)
//   Anthropic      https://platform.claude.com/docs/en/api/errors and /api/overview (headers x-api-key, anthropic-version,
//                  content-type required; {type:'error', error:{type,message}, request_id}; 529 overloaded_error)
//   Ollama         https://docs.ollama.com/api/openai-compatibility (key ignored locally; tool_choice unsupported) and
//                  server/routes.go on main (CORS AllowHeaders: Authorization, Content-Type, User-Agent, Accept,
//                  X-Requested-With, OpenAI-Beta, x-stainless-*)
//   Gemini         https://ai.google.dev/gemini-api/docs/troubleshooting (429 RESOURCE_EXHAUSTED, 400/403 key errors)
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';

const FAMILY = (p) => (/\/chat\/completions$/.test(p) ? 'openai' : p === '/v1/messages' ? 'anthropic' : /^\/v1beta\/models\/[^/]+:generateContent$/.test(p) ? 'gemini' : null);
const TYPES = { 400: 'invalid_request_error', 401: 'authentication_error', 403: 'permission_error', 404: 'not_found_error', 429: 'rate_limit_error', 500: 'api_error', 529: 'overloaded_error' };
const GOOGLE = { 400: 'INVALID_ARGUMENT', 403: 'PERMISSION_DENIED', 404: 'NOT_FOUND', 429: 'RESOURCE_EXHAUSTED', 500: 'INTERNAL', 503: 'UNAVAILABLE' };
// Modes that answer with an error whatever the (valid) request says: [status, extra headers, message].
const ERRORS = {
  unauthorized: [401, {}, 'Incorrect API key provided.'], forbidden: [403, {}, 'Forbidden.'],
  'not-found': [404, {}, 'The model does not exist or you do not have access to it.'],
  'ollama-not-found': [404, {}, 'model "x" not found, try pulling it first'], // wording assumed (not on the fetched pages)
  'server-error': [500, {}, 'The server had an error.'], overloaded: [529, {}, 'Overloaded'],
  'rate-limit': [429, { 'retry-after': '7' }, 'Rate limit reached.'], 'groq-rate-limit': [429, { 'retry-after': '7' }, 'Rate limit reached for model.'], // Retry-After on Groq assumed
  'bad-key': [400, {}, 'API key not valid. Please pass a valid API key.'],
};
// CORS allow-lists for cors 'vendor-like'. OpenAI-style: Ollama's list is sourced (routes.go); the other vendors' lists
// are assumed (no source states them): a browser call needs only the headers the adapter documents.
const ALLOW = {
  openai: ['authorization', 'content-type', 'user-agent', 'accept', 'x-requested-with', 'openai-beta'],
  anthropic: ['x-api-key', 'anthropic-version', 'content-type', 'anthropic-dangerous-direct-browser-access'],
  gemini: ['x-goog-api-key', 'content-type'],
};
const CANNED = {
  label: '2BHK near Indiranagar', price: '25,000', priceType: 'RENT', bedrooms: '2', locality: 'Indiranagar', contactName: 'Ramesh',
  contactPhone: '98450 12345', amenities: ['parking', 'lift'], answer: 'Canned answer.', summary: 'Canned plan.', ok: true,
};

const isObj = (v) => typeof v === 'object' && v !== null && !Array.isArray(v);
const isText = (v) => typeof v === 'string' && v.trim() !== '';

/** A value that fits `schema` (JSON Schema or Gemini dialect): `hints` by property name, else CANNED, else by type. */
function canned(schema, hints, key) {
  if (key !== undefined && hints[key] !== undefined) return hints[key];
  if (key !== undefined && CANNED[key] !== undefined) return CANNED[key];
  const type = [].concat(schema?.type ?? 'string').find((t) => t !== 'null');
  if (type === 'object') return Object.fromEntries(Object.entries(schema.properties ?? {}).map(([k, s]) => [k, canned(s, hints, k)]));
  if (type === 'array') return [];
  return type === 'boolean' ? true : type === 'number' || type === 'integer' ? 1 : 'sample';
}

/** Hints from the golden-set case whose input text appears in the prompt (extract, ask, plan), else {}. */
function hintsFor(golden, prompt) {
  const c = golden?.cases.find((g) => (g.input.text ?? g.input.question) && prompt.includes(g.input.text ?? g.input.question));
  const e = c?.expected;
  const str = (v) => (v == null ? null : String(v));
  if (!e) return {};
  if (c.type === 'extract') return { price: str(e.price), priceType: e.priceType, bedrooms: str(e.bedrooms), locality: e.locality, contactName: e.contactName, contactPhone: e.contactPhone, listingUrl: e.listingUrl, amenities: e.amenitiesInclude ?? [] };
  if (c.type === 'ask') return { answer: `${(e.mustContain ?? ['Canned']).join(', ')}.`, citedHouseIds: e.expectedHouseIds ?? [] };
  return { stops: (e.stopsSubsetOf ?? []).slice(0, c.input.maxStops ?? 8).map((houseId) => ({ houseId, reason: 'canned' })) };
}

/** Strict mode: every object lists all its properties in `required` and has additionalProperties false (OpenAI Structured Outputs guide). */
function strictProblem(s, at = 'schema') {
  if (!isObj(s)) return null;
  if ([].concat(s.type ?? []).includes('object')) {
    const names = Object.keys(s.properties ?? {});
    if (s.additionalProperties !== false) return `${at}: additionalProperties must be false in strict mode`;
    if (names.some((n) => !(s.required ?? []).includes(n))) return `${at}: every property must be in required in strict mode`;
    for (const n of names) { const p = strictProblem(s.properties[n], `${at}.${n}`); if (p) return p; }
  }
  return s.items ? strictProblem(s.items, `${at}[]`) : null;
}

/** The problem with a request, as [status, message], or null when a vendor would take it. Auth first, then the body. */
function problem(fam, mode, req, b, state) {
  const h = req.headers;
  if (fam === 'openai') {
    const bearer = /^Bearer (\S+)$/.exec(h.authorization ?? '')?.[1];
    if (mode !== 'ollama' && !bearer) return [401, "You didn't provide an API key. Send it in the Authorization header as Bearer <key>."]; // Ollama ignores the key (docs.ollama.com)
    if (mode !== 'ollama' && state.key && bearer !== state.key) return [401, 'Incorrect API key provided.'];
  } else if (fam === 'anthropic') {
    if (!h['x-api-key']) return [401, 'x-api-key header is required'];
    if (state.key && h['x-api-key'] !== state.key) return [401, 'invalid x-api-key'];
    if (!h['anthropic-version']) return [400, 'anthropic-version: header is required']; // required: overview page; wording assumed
    // A browser (it sends Origin) must say it knows the risk. Simon Willison, 2024-08-23, describes the header; status and wording are assumed.
    if (h.origin && h['anthropic-dangerous-direct-browser-access'] !== 'true') return [400, "CORS requests must set 'anthropic-dangerous-direct-browser-access' header"];
  } else {
    if (!h['x-goog-api-key']) return [403, 'Method doesn\'t allow unregistered callers. Please use API Key.']; // assumed wording
    if (state.key && h['x-goog-api-key'] !== state.key) return [400, 'API key not valid. Please pass a valid API key.'];
  }
  if (req.url.includes('?')) return [400, 'Keys and options go in headers and the body, never in the URL.']; // assumed: a vendor would not read them there
  if ((h['content-type'] ?? '').split(';')[0].trim() !== 'application/json') return [400, 'content-type must be application/json'];
  if (!isObj(b)) return [400, 'The body must be a JSON object'];
  if (fam === 'openai') {
    if (!isText(b.model)) return [400, "'model' is required"];
    if (!Array.isArray(b.messages) || !b.messages.length || !b.messages.every((m) => ['system', 'user', 'assistant'].includes(m?.role) && typeof m.content === 'string')) return [400, "'messages' must be a non-empty array of {role, content}"];
    const rf = b.response_format;
    if (rf !== undefined && !['json_schema', 'json_object'].includes(rf?.type)) return [400, "Invalid response_format: 'type' must be json_schema or json_object"];
    if (rf?.type === 'json_schema') {
      const js = rf.json_schema;
      if (!isText(js?.name) || !isObj(js?.schema)) return [400, "Missing 'response_format.json_schema.name' or '.schema'"];
      const bad = js.strict === true ? strictProblem(js.schema) : null;
      if (bad) return [400, `Invalid schema for response_format '${js.name}': ${bad}`];
    }
    if (rf?.type === 'json_object' && !b.messages.some((m) => /json/i.test(m.content))) return [400, "'messages' must contain the word 'json' in some form, to use 'response_format' of type 'json_object'."]; // assumed (OpenAI behaviour, not on the fetched pages)
    if (b.max_tokens !== undefined && !Number.isInteger(b.max_tokens)) return [400, "'max_tokens' must be an integer"];
  } else if (fam === 'anthropic') {
    if (!isText(b.model)) return [400, 'model: Field required'];
    if (!Number.isInteger(b.max_tokens) || b.max_tokens < 1) return [400, 'max_tokens: Field required'];
    if (!Array.isArray(b.messages) || !b.messages.length || !b.messages.every((m) => ['user', 'assistant'].includes(m?.role) && m.content)) return [400, 'messages: Field required'];
    if (b.tools !== undefined) {
      if (!Array.isArray(b.tools) || !b.tools.every((t) => isText(t?.name) && t.input_schema?.type === 'object')) return [400, 'tools: each tool needs a name and an input_schema of type object'];
      // The fake's own contract (stricter than the vendor, which defaults to auto): the app says what it wants, either
      // {type: "tool", name} naming one of the tools or {type: "auto"}; `any` and a missing tool_choice are refused here.
      const tc = b.tool_choice;
      if (tc?.type !== 'auto' && (tc?.type !== 'tool' || !b.tools.some((t) => t.name === tc.name))) return [400, 'tool_choice: must be {type: "tool", name} naming one of the tools, or {type: "auto"}'];
      // errors page, "Forced tool use not supported": `tool` and `any` are a 400 on newer models, `auto` and `none` are accepted.
      if (mode === 'no-forced-tool' && tc.type === 'tool') return [400, 'tool_choice: type "tool" and "any" are not supported for this model.'];
    }
  } else if (!Array.isArray(b.contents) || !b.contents.length || b.generationConfig?.responseMimeType !== 'application/json' || !isObj(b.generationConfig?.responseSchema)) {
    return [400, 'Invalid JSON payload: contents and generationConfig.responseMimeType/responseSchema are required'];
  }
  return null;
}

function errorBody(fam, mode, status, message) {
  if (fam === 'anthropic') return { type: 'error', error: { type: TYPES[status] ?? 'api_error', message }, request_id: 'req_fake' };
  if (fam === 'gemini') { // assumed: Google's standard error model, with the key-invalid reason the app looks for
    const keyBad = /API key not valid/.test(message);
    return { error: { code: status, message, status: GOOGLE[status] ?? 'UNKNOWN', ...(keyBad ? { details: [{ reason: 'API_KEY_INVALID' }] } : {}) } };
  }
  if (mode.startsWith('groq')) return { error: { message, type: status === 429 ? 'tokens' : 'invalid_request_error' } };
  return { error: { message, type: status === 429 ? 'rate_limit_error' : status >= 500 ? 'server_error' : 'invalid_request_error', param: null, code: status === 401 ? 'invalid_api_key' : status === 404 ? 'model_not_found' : null } };
}

/** The success body for `answer` (JSON text) in mode `mode`. */
function successBody(fam, mode, body, answer) {
  const text = mode === 'fenced' ? `\`\`\`json\n${answer}\n\`\`\`` : mode === 'truncated' ? answer.slice(0, Math.ceil(answer.length / 2)) : answer;
  if (fam === 'openai') {
    return { id: 'chatcmpl-fake', object: 'chat.completion', created: 0, model: body.model, ...(mode === 'ollama' ? { system_fingerprint: 'fp_ollama' } : {}), // Ollama's extra field: assumed
      choices: mode === 'empty-choices' ? [] : [{ index: 0, message: { role: 'assistant', content: text }, finish_reason: mode === 'truncated' ? 'length' : 'stop' }],
      usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 } };
  }
  if (fam === 'gemini') return { candidates: [{ content: { role: 'model', parts: [{ text }] }, finishReason: mode === 'truncated' ? 'MAX_TOKENS' : 'STOP' }] };
  const base = { id: 'msg_fake', type: 'message', role: 'assistant', model: body.model };
  if (!body.tools) return { ...base, stop_reason: 'max_tokens', content: [{ type: 'text', text: '{"ok' }] }; // a 5-token ping stops at the limit
  if (mode === 'text-only') return { ...base, stop_reason: 'end_turn', content: [{ type: 'text', text: 'I cannot do that.' }] };
  return { ...base, stop_reason: mode === 'max-tokens' ? 'max_tokens' : 'tool_use', content: [{ type: 'tool_use', id: 'toolu_fake', name: body.tools[0].name, input: JSON.parse(answer) }] };
}

/** Starts the server on 127.0.0.1; resolves with {port, state, close()}. */
export function start({ port = 0, golden = null, staticDir = null } = {}) {
  const state = { mode: 'ok', cors: 'permissive', key: null, requests: [], redirected: 0 };
  const open = new Set();
  const handle = async (req, res) => {
    const url = new URL(req.url, 'http://fake');
    const json = (status, body, headers = {}) => { res.writeHead(status, { 'content-type': 'application/json', ...headers }); res.end(JSON.stringify(body)); };
    const raw = await new Promise((ok) => { const c = []; req.on('data', (d) => c.push(d)); req.on('end', () => ok(Buffer.concat(c).toString())); });
    if (url.pathname === '/__mode') { if (req.method === 'POST') Object.assign(state, JSON.parse(raw)); return json(200, { mode: state.mode, cors: state.cors, key: state.key }); }
    if (url.pathname === '/__requests') { if (req.method === 'DELETE') { state.requests = []; state.redirected = 0; } return json(200, { requests: state.requests, redirected: state.redirected }); }
    if (url.pathname === '/__redirected') { state.redirected++; return json(200, { followed: true }); }
    if (url.pathname.startsWith('/__static/') && staticDir) {
      const file = path.join(staticDir, path.basename(url.pathname));
      if (!fs.existsSync(file)) return json(404, { error: 'no such file' });
      res.writeHead(200, { 'content-type': { '.html': 'text/html', '.js': 'text/javascript' }[path.extname(file)] ?? 'application/octet-stream' });
      return res.end(fs.readFileSync(file));
    }
    const fam = FAMILY(url.pathname);
    if (!fam) return json(404, { error: { message: `no route ${req.method} ${url.pathname}` } });
    const mode = req.headers['x-fake-mode'] ?? state.mode;
    // CORS: 'permissive' echoes what is asked and exposes Retry-After; 'vendor-like' allows only the family's list and
    // exposes nothing (whether vendors expose Retry-After is unsourced: assumed not).
    const origin = req.headers.origin;
    const cors = origin ? { 'access-control-allow-origin': origin, ...(state.cors === 'permissive' ? { 'access-control-expose-headers': 'retry-after' } : {}) } : {};
    if (req.method === 'OPTIONS') {
      state.requests.push({ method: 'OPTIONS', url: req.url, headers: req.headers });
      const allowed = state.cors === 'permissive' ? req.headers['access-control-request-headers'] ?? '' : ALLOW[fam].join(', ');
      res.writeHead(204, { ...cors, 'access-control-allow-methods': 'POST, OPTIONS', 'access-control-allow-headers': allowed, 'access-control-max-age': '0' });
      return res.end();
    }
    let body; try { body = JSON.parse(raw); } catch { body = undefined; }
    state.requests.push({ method: req.method, url: req.url, headers: req.headers, body });
    const fail = (status, message, extra = {}) => json(status, errorBody(fam, mode, status, message), { ...cors, ...extra });
    if (req.method !== 'POST') return fail(405, 'POST only');
    const wrong = problem(fam, mode, req, body, state);
    if (wrong) return fail(...wrong);
    if (ERRORS[mode]) return fail(ERRORS[mode][0], ERRORS[mode][2], ERRORS[mode][1]);
    if (mode === 'redirect') { res.writeHead(302, { ...cors, location: '/__redirected' }); return res.end(); }
    if (mode === 'openrouter-200-error') return json(200, { error: { code: 502, message: 'Provider returned error' }, id: 'gen-fake' }, cors);
    const rf = body.response_format;
    if (fam === 'openai' && rf?.type === 'json_schema' && ['json-object-only', 'no-structured'].includes(mode)) return fail(400, "Invalid parameter: 'response_format' of type 'json_schema' is not supported with this model."); // wording assumed
    if (fam === 'openai' && rf?.type === 'json_object' && mode === 'no-structured') return fail(400, "This model does not support 'response_format'.");
    // The schema asked for: OpenAI tier 1 json_schema; tiers 2 and 3 in the system trailer ("... of this shape: <schema>");
    // the forced tool; Gemini's responseSchema. None (the ping) gets {"ok": true}.
    let schema = rf?.json_schema?.schema ?? body.tools?.[0]?.input_schema ?? body.generationConfig?.responseSchema;
    if (!schema && fam === 'openai') { try { schema = JSON.parse(/of this shape: (.*)$/s.exec(body.messages[0].content)?.[1]); } catch { /* no shape asked */ } }
    const prompt = JSON.stringify(body).replace(/\\n/g, ' ').replace(/\\"/g, '"');
    const answer = schema ? JSON.stringify(canned(schema, hintsFor(golden, prompt), undefined)) : '{"ok": true}';
    const out = successBody(fam, mode, body, answer);
    if (mode === 'stall') { // headers and a first chunk, then nothing: the client's own timeout must end the call
      res.writeHead(200, { 'content-type': 'application/json', 'content-length': '100000', ...cors });
      return void res.write(JSON.stringify(out).slice(0, 20));
    }
    return json(200, out, cors);
  };
  // A bug in this file must be loud (a 500 naming it), not a request that never answers.
  const server = http.createServer((req, res) => handle(req, res).catch((e) => {
    if (res.headersSent) return res.destroy();
    res.writeHead(500, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ error: { message: `fake server bug: ${e.message}` } }));
  }));
  server.on('connection', (s) => { open.add(s); s.on('close', () => open.delete(s)); });
  return new Promise((ok) => server.listen(port, '127.0.0.1', () => ok({
    port: server.address().port, state,
    close: () => new Promise((done) => { for (const s of open) s.destroy(); server.close(done); }),
  })));
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const arg = (n) => process.argv[process.argv.indexOf(n) + 1];
  const has = (n) => process.argv.includes(n);
  const s = await start({
    port: Number(has('--port') ? arg('--port') : process.env.FAKE_AI_PORT ?? 0),
    golden: has('--golden') ? JSON.parse(fs.readFileSync(arg('--golden'), 'utf8')) : null,
    staticDir: has('--static') ? arg('--static') : null,
  });
  console.log(JSON.stringify({ listening: s.port }));
}
