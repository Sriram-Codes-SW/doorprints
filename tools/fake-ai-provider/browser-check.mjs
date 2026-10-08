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

// The website's own AI adapters in a real browser (Chromium via Playwright) against the fake provider server, so the CORS
// preflight is real (S4b-BL-175, docs/06 TC-AI-24). Two servers on two ports are two origins: the page comes from one, the
// "provider" is the other. With cors 'vendor-like' the provider allows only the headers its family lists (see server.mjs for
// the sources), so a header the adapters add that a vendor would not allow fails here, which no in-memory mock can show.
//   cd web && npm ci            (the adapters are bundled with web/node_modules/esbuild)
//   cd tools/live-ui && npm ci  (Playwright; the browser: npx playwright install chromium, or CHROMIUM=<path>)
//   node tools/fake-ai-provider/browser-check.mjs
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { start } from './server.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '../..');
const { build } = createRequire(path.join(root, 'web/package.json'))('esbuild');
const { chromium } = createRequire(path.join(root, 'tools/live-ui/package.json'))('playwright');

// The page: the real adapters (OpenAI-compatible, Anthropic, Gemini through Angular's HttpClient on fetch), bundled for a
// browser. Gemini's address is a constant (Google's), so the page's fetch sends it to the fake origin and keeps the rest.
const entry = `
import '@angular/compiler';
import { createApplication } from '@angular/platform-browser';
import { HttpClient, provideHttpClient, withFetch } from '@angular/common/http';
import { OpenAiCompatibleChatModel, resetTierCache } from './openai-compat';
import { AnthropicChatModel } from './anthropic';
import { ANSWER_SCHEMA, GeminiChatModel } from './on-device-ai.service';
const w = window as any;
const GOOGLE = 'https://generativelanguage.googleapis.com';
const realFetch = window.fetch.bind(window);
window.fetch = (input: any, init?: any) => {
  const url = typeof input === 'string' ? input : input.url;
  return realFetch(url.startsWith(GOOGLE) ? w.API + url.slice(GOOGLE.length) : input, init);
};
w.call = async (kind: string, api: string, key: string) => {
  try {
    resetTierCache();
    w.API = api;
    const model =
      kind === 'openai' ? new OpenAiCompatibleChatModel({ baseUrl: api + '/v1', model: 'm' }, key)
      : kind === 'anthropic' ? new AnthropicChatModel({ baseUrl: api, model: 'm' }, key)
      : new GeminiChatModel((await createApplication({ providers: [provideHttpClient(withFetch())] })).injector.get(HttpClient), key);
    return { ok: true, text: await model.generateJson('system', 'user', ANSWER_SCHEMA, 0) };
  } catch (e: any) {
    return { ok: false, kind: e?.kind ?? String(e), retryAfter: e?.retryAfter };
  }
};
w.raw = async (url: string, init: any) => {
  try { const r = await fetch(url, init); return { status: r.status, text: await r.text() }; } catch (e) { return { error: String(e) }; }
};
`;
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'fake-ai-page-'));
await build({
  stdin: { contents: entry, resolveDir: path.join(root, 'web/src/app/core/ai'), loader: 'ts', sourcefile: 'entry.ts' },
  bundle: true, format: 'iife', outfile: path.join(dir, 'page.js'), logLevel: 'error', platform: 'browser',
  tsconfigRaw: { compilerOptions: { experimentalDecorators: true, useDefineForClassFields: false } },
});
fs.writeFileSync(path.join(dir, 'index.html'), '<!doctype html><meta charset="utf-8"><title>fake provider page</title><script src="/__static/page.js"></script>');

const pageServer = await start({ staticDir: dir });
const api = await start();
const apiUrl = `http://127.0.0.1:${api.port}`;
const pageUrl = `http://127.0.0.1:${pageServer.port}/__static/index.html`;
assert.notEqual(api.port, pageServer.port, 'two origins');

const browser = await chromium.launch({ executablePath: process.env.CHROMIUM || undefined });
let failed = 0;
try {
  const page = await browser.newPage();
  await page.goto(pageUrl);
  const call = (kind, key = 'k-test') => page.evaluate(([a, b, c]) => window.call(a, b, c), [kind, apiUrl, key]);
  const raw = (url, init) => page.evaluate(([a, b]) => window.raw(a, b), [url, init]);
  const set = (mode, cors) => { Object.assign(api.state, { mode, cors, requests: [], key: null }); };
  const preflights = () => api.state.requests.filter((r) => r.method === 'OPTIONS');
  const headersAsked = (r) => r.headers['access-control-request-headers'].split(',').sort().join(',');
  const check = async (name, fn) => {
    try { await fn(); console.log(`ok   ${name}`); } catch (e) { failed++; console.log(`FAIL ${name}\n     ${String(e.message).split('\n')[0]}`); }
  };

  await check('OpenAI-style call passes a vendor-like policy; the preflight asks for authorization and content-type only', async () => {
    set('ok', 'vendor-like');
    const r = await call('openai');
    assert.equal(r.ok, true, JSON.stringify(r));
    assert.equal(headersAsked(preflights()[0]), 'authorization,content-type');
  });
  await check('Anthropic call passes a vendor-like policy with the dangerous-direct-browser-access header', async () => {
    set('ok', 'vendor-like');
    const r = await call('anthropic');
    assert.equal(r.ok, true, JSON.stringify(r));
    assert.equal(headersAsked(preflights()[0]), 'anthropic-dangerous-direct-browser-access,anthropic-version,content-type,x-api-key');
    const post = api.state.requests.find((q) => q.method === 'POST');
    assert.equal(post.headers['anthropic-dangerous-direct-browser-access'], 'true');
  });
  await check('Gemini call (Angular HttpClient on fetch) passes a vendor-like policy with x-goog-api-key only', async () => {
    set('ok', 'vendor-like');
    const r = await call('gemini');
    assert.equal(r.ok, true, JSON.stringify(r));
    assert.equal(headersAsked(preflights()[0]), 'content-type,x-goog-api-key');
  });
  await check('control: a stray x-api-key on the OpenAI route is blocked by the same policy', async () => {
    set('ok', 'vendor-like');
    const r = await raw(`${apiUrl}/v1/chat/completions`, { method: 'POST', headers: { authorization: 'Bearer k', 'content-type': 'application/json', 'x-api-key': 'k' }, body: '{}' });
    assert.match(r.error ?? '', /Failed to fetch|TypeError/);
  });
  await check('control: the server refuses a browser call to the Anthropic route that lacks the browser-access header', async () => {
    set('ok', 'vendor-like');
    const r = await raw(`${apiUrl}/v1/messages`, { method: 'POST', headers: { 'x-api-key': 'k', 'anthropic-version': '2023-06-01', 'content-type': 'application/json' }, body: JSON.stringify({ model: 'm', max_tokens: 5, messages: [{ role: 'user', content: 'x' }] }) });
    assert.equal(r.status, 400);
    assert.match(r.text, /anthropic-dangerous-direct-browser-access/);
  });
  await check('a keyless OpenAI-style call (Ollama) sends no Authorization and so no authorization in the preflight', async () => {
    set('ollama', 'vendor-like');
    const r = await call('openai', '');
    assert.equal(r.ok, true, JSON.stringify(r));
    assert.equal(headersAsked(preflights()[0]), 'content-type');
  });
  await check('statuses reach the page as the same error kinds (401 keyRejected, 404 modelNotFound, 500 unavailable)', async () => {
    for (const [mode, kind] of [['unauthorized', 'keyRejected'], ['not-found', 'modelNotFound'], ['server-error', 'unavailable']]) {
      set(mode, 'vendor-like');
      assert.equal((await call('openai')).kind, kind, `openai ${mode}`);
      assert.equal((await call('anthropic')).kind, kind, `anthropic ${mode}`);
    }
  });
  // DOCUMENTED GAP F2 (docs/10 S4b-BL-175): a redirect is "unavailable (302)" on the phones; fetch with redirect 'error' rejects, so
  // the website says "unreachable". The key is not sent on, which is the point; only the words differ.
  await check('DOCUMENTED GAP: a redirect is not followed, and reads as unreachable (phones: unavailable with 302)', async () => {
    set('redirect', 'permissive');
    const r = await call('openai');
    assert.equal(r.kind, 'unreachable');
    assert.equal(api.state.redirected, 0, 'nobody followed it');
  });
  // DOCUMENTED GAP F3: a page can read Retry-After across origins only when the provider exposes it (Access-Control-Expose-Headers).
  // Whether OpenAI, Anthropic, Groq or OpenRouter do is unsourced (server.mjs says "assumed"); if not, a 429 reads as 60 s, the default.
  await check('DOCUMENTED GAP: Retry-After is read only when the provider exposes it (7 s with, the 60 s default without)', async () => {
    set('rate-limit', 'permissive');
    assert.equal((await call('openai')).retryAfter, 7);
    set('rate-limit', 'vendor-like');
    assert.equal((await call('openai')).retryAfter, 60);
  });
} finally {
  await browser.close();
  await Promise.all([api.close(), pageServer.close()]);
  fs.rmSync(dir, { recursive: true, force: true });
}
console.log(failed ? `${failed} check(s) failed` : 'all checks passed');
process.exit(failed ? 1 : 0);
