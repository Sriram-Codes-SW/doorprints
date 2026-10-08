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
import { provideHttpClient, withFetch } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { LocalStore } from '../../data/local-store.service';
import golden from '../../../../../docs/ai/evals/golden-set.json';
import { type CaseResult, type GoldenCase, type Outcome, evalSetup, formatSummary, scoreCase } from './ai-eval';
import { aiHostOf } from './ai-provider-config';
import { AnthropicChatModel } from './anthropic';
import { type AskFilterValues } from './ai-core';
import { type ModelRef, OnDeviceAiError, OnDeviceAiService } from './on-device-ai.service';
import { OpenAiCompatibleChatModel } from './openai-compat';

type NodeProcess = {
  env?: Record<string, string | undefined>;
  getBuiltinModule?: (id: string) => { writeFileSync(path: string, data: string): void };
};
const proc = (globalThis as { process?: NodeProcess }).process;
const setup = evalSetup(proc?.env ?? {});

/**
 * The golden set (docs/ai/evals/golden-set.json) through the website's own adapters against the provider the owner chose
 * (S4b-BL-153, docs/06 TC-AI-23): the manual *AI evals* workflow, suite `own-provider`, passes the choice and the
 * `AI_EVAL_API_KEY` secret as environment variables (`ai-eval.ts` reads them). Without a key the workflow says
 * `skipped: no key` and never gets here; this spec skips itself as well, so the normal build never calls a provider.
 * Synthetic data only. Reported, not gating: the test fails only when the setup is wrong or no case got an answer.
 */
describe.skipIf(setup.status === 'skip')('Golden set through the chosen provider (real key)', () => {
  it('runs the cases and writes the summary', { timeout: 3_600_000 }, async () => {
    if (setup.status !== 'run') throw new Error(setup.line);
    const at = (c: unknown) => c as never;
    const now = '2026-09-20T10:00:00Z';
    const houses = golden.fixtureHouses.map(({ city: _city, region: _region, ...h }) => ({ ...h, deleted: false, updatedAt: now })); // the golden set's own tags are not house fields
    const visits = golden.fixtureVisits.map((v) => ({ ...v, deleted: false, updatedAt: now }));
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withFetch()),
        {
          provide: LocalStore,
          useValue: {
            allHouses: async () => houses, allVisits: async () => visits, viewings: async () => [], areas: async () => [],
            places: async () => [], areaNoteRows: async () => [],
          },
        },
      ],
    });
    const ai = TestBed.inject(OnDeviceAiService);
    const config = { kind: setup.kind, baseUrl: setup.baseUrl, model: setup.model };
    const via: ModelRef =
      setup.kind === 'gemini' ? setup.key
      : setup.kind === 'openai-compatible' ? new OpenAiCompatibleChatModel(config, setup.key)
      : new AnthropicChatModel(config, setup.key);

    // The app's own 10-a-minute limit is not what is measured; the provider's limits are, and `delayMs` paces for them.
    let clock = 0;
    ai.now = () => (clock += 60_000);
    const results: CaseResult[] = [];
    let stopped: string | null = null;
    let answered = 0;
    for (const c of (golden.cases as GoldenCase[]).filter((g) => setup.types.includes(g.type))) {
      let outcome: Outcome;
      try {
        if (c.type === 'extract') {
          outcome = { type: 'extract', draft: await ai.extractListing(via, String(c.input['text'])) };
        } else if (c.type === 'ask') {
          outcome = { type: 'ask', response: await ai.ask(via, String(c.input['question']), c.input['filters'] as AskFilterValues | undefined) };
        } else {
          outcome = { type: 'plan', plan: await ai.planVisits(via, at(c.input)) };
        }
        answered++;
      } catch (e) {
        const kind = e instanceof OnDeviceAiError ? e.kind : String((e as Error)?.message ?? e).slice(0, 100);
        outcome = { type: 'error', message: kind };
        // Every further case would fail the same way: stop instead of spending the pauses (and the quota).
        const why: Record<string, string> = { keyRejected: 'key rejected', modelNotFound: 'model not found', unreachable: 'provider unreachable' };
        stopped = why[kind] ?? null;
      }
      results.push(scoreCase(c, outcome));
      if (stopped) break;
      await new Promise((r) => setTimeout(r, setup.delayMs));
    }

    const text = formatSummary({ kind: setup.kind, host: aiHostOf(config), model: setup.model }, results, stopped, setup.key);
    console.log(text);
    const file = proc?.env?.['DOORPRINTS_EVAL_SUMMARY'];
    if (file && proc?.getBuiltinModule) proc.getBuiltinModule('node:fs').writeFileSync(file, text);
    expect(answered, 'no case got an answer from the provider').toBeGreaterThan(0);
  });
});
