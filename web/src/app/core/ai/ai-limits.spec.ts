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

import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { LocalStore } from '../../data/local-store.service';
import { AiHouse, FALLBACK_REASON, MAX_HOUSES, NOTES_MAX, assemblePlan, houseText, selectForAsk, selectForPlan, type PlanCandidate } from './ai-core';
import { GEMINI_URL, MAX_INPUT_CHARS, OnDeviceAiService } from './on-device-ai.service';

/**
 * The AI limits (S4b-BL-181, docs/ai/ai-design.md 7 and 9): how many houses the model may see, how many stops a plan may
 * have, how long notes and a pasted listing may be. The expected numbers are written from the rules (40 houses, 8 stops,
 * 3,000 characters of notes and then " …", 8,000 characters of listing), not read from the constants, so a changed
 * constant fails here. tools/mutations/ai-limits-web.json changes each limit by one and names the test that must fail.
 */

const START = { lat: 12.9716, lon: 77.5946 };

/** A house `i` thousandths of a degree north of the start point: every one is a different, increasing distance away. */
const north = (i: number, over: Partial<AiHouse> = {}): AiHouse => ({
  id: `h${i}`, label: `House ${i}`, locality: 'Indiranagar', lat: START.lat + i * 0.001, lon: START.lon, status: 'SHORTLISTED', ...over,
});

const ids = (xs: { id: string }[]) => xs.map((x) => x.id);

/** A lone half of a surrogate pair: a broken character. */
const BROKEN = /[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/;

const notesLine = (notes: string) => houseText({ id: 'n', label: 'N', lat: 1, lon: 1, notes }).split('\n').find((l) => l.startsWith('Notes: '))!;

describe('houses the model may see (S4b-BL-181)', () => {
  it('keeps the forty nearest of forty-five saved houses as plan candidates, nearest first', () => {
    // Listed farthest first, so the nearest forty are the last forty listed.
    const listed = Array.from({ length: 45 }, (_, i) => north(44 - i));
    expect(MAX_HOUSES).toBe(40);
    const chosen = selectForPlan(listed, START.lat, START.lon);
    expect(chosen).toHaveLength(40);
    expect(ids(chosen)).toEqual(Array.from({ length: 40 }, (_, i) => `h${i}`));
  });

  it('sends all of forty or fewer houses and exactly forty of forty-one, and breaks a tie by the order saved', () => {
    expect(selectForPlan(Array.from({ length: 40 }, (_, i) => north(i)), START.lat, START.lon)).toHaveLength(40);
    expect(selectForPlan(Array.from({ length: 41 }, (_, i) => north(i)), START.lat, START.lon)).toHaveLength(40);
    const twins = Array.from({ length: 45 }, (_, i) => north(0, { id: `t${i}` }));
    expect(ids(selectForPlan(twins, START.lat, START.lon))).toEqual(Array.from({ length: 40 }, (_, i) => `t${i}`));
  });

  it('keeps the forty houses sharing most words with the question out of forty-five, in the order saved when they tie', () => {
    const many = Array.from({ length: 45 }, (_, i) => north(i, { label: i === 43 ? 'Lake view villa' : i === 44 ? 'Lake house' : `House ${i}` }));
    const chosen = selectForAsk(many, 'lake view');
    expect(chosen).toHaveLength(40);
    // h43 shares two words (lake, view), h44 one (lake); the other thirty-eight have none and keep their order: h0..h37.
    expect(ids(chosen)).toEqual(['h43', 'h44', ...Array.from({ length: 38 }, (_, i) => `h${i}`)]);
  });

  it('does not rank or cut exactly forty houses for Ask, and cuts forty-one', () => {
    const forty = Array.from({ length: 40 }, (_, i) => north(i, { label: i === 39 ? 'Lake view villa' : `House ${i}` }));
    expect(ids(selectForAsk(forty, 'lake view'))).toEqual(forty.map((h) => h.id));
    expect(selectForAsk([...forty, north(40)], 'lake view')).toHaveLength(40);
  });
});

describe('a plan has at most eight stops (S4b-BL-181)', () => {
  const cand = (i: number): PlanCandidate => ({
    id: `00000000-0000-4000-8000-${String(i).padStart(12, '0')}`, label: `H${i}`, locality: 'L', street: null, status: 'SHORTLISTED', price: null,
    priceType: null, bedrooms: null, rating: null, lat: START.lat + i * 0.001, lon: START.lon, distanceMeters: i * 111,
  });
  const seen = new Map(Array.from({ length: 12 }, (_, i) => cand(i)).map((c) => [c.id, c]));
  const allIds = [...seen.keys()];

  it('stops at the cap with the first houses the model named, in its order', () => {
    const stops = allIds.map((houseId) => ({ houseId, reason: 'near' }));
    expect(assemblePlan({ stops }, seen, START.lat, START.lon, 8).stops.map((s) => s.houseId)).toEqual(allIds.slice(0, 8));
    expect(assemblePlan({ stops }, seen, START.lat, START.lon, 3).stops.map((s) => s.houseId)).toEqual(allIds.slice(0, 3));
    // Exactly at the cap nothing is dropped.
    expect(assemblePlan({ stops: stops.slice(0, 8) }, seen, START.lat, START.lon, 8).stops).toHaveLength(8);
  });

  it('keeps the cap on the fallback route: the eight nearest houses in the running, by walking order', () => {
    const fallback = assemblePlan({ stops: [{ houseId: 'made-up', reason: 'x' }] }, seen, START.lat, START.lon, 8);
    expect(fallback.fallback).toBe(true);
    expect(fallback.stops.map((s) => s.houseId)).toEqual(allIds.slice(0, 8));
    expect(fallback.stops.every((s) => s.reason === FALLBACK_REASON)).toBe(true);
    expect(assemblePlan(null, seen, START.lat, START.lon, 2).stops.map((s) => s.houseId)).toEqual(allIds.slice(0, 2));
  });
});

describe('notes are cut at 3,000 characters with " …" (S4b-BL-181)', () => {
  it('cuts 3,001 characters to 3,000 and the marker, and leaves 3,000 whole', () => {
    expect(NOTES_MAX).toBe(3000);
    expect(notesLine('x'.repeat(3001))).toBe(`Notes: ${'x'.repeat(3000)} …`);
    expect(notesLine('x'.repeat(3000))).toBe(`Notes: ${'x'.repeat(3000)}`);
    expect(notesLine('x'.repeat(2999))).toBe(`Notes: ${'x'.repeat(2999)}`);
  });

  it('measures the notes after trimming them', () => {
    expect(notesLine(`  ${'x'.repeat(3000)}  \n`)).toBe(`Notes: ${'x'.repeat(3000)}`);
  });

  it('never leaves half of an emoji at the cut', () => {
    // The emoji is two UTF-16 units at 2,999 and 3,000: the cut falls between them, so the whole emoji goes.
    expect(notesLine(`${'a'.repeat(2999)}😀tail`)).toBe(`Notes: ${'a'.repeat(2999)} …`);
    // One unit earlier it fits whole.
    expect(notesLine(`${'a'.repeat(2998)}😀tail`)).toBe(`Notes: ${'a'.repeat(2998)}😀 …`);
    expect(notesLine(`${'a'.repeat(2999)}😀tail`)).not.toMatch(BROKEN);
  });

  it('cuts Hindi, Tamil and Telugu notes at 3,000 characters without breaking one', () => {
    for (const unit of ['घर बहुत अच्छा है। ', 'வீடு மிகவும் நல்லது. ', 'ఇల్లు చాలా బాగుంది. ']) {
      const notes = unit.repeat(Math.ceil(3100 / unit.length));
      const line = notesLine(notes);
      expect(line.endsWith(' …')).toBe(true);
      expect(line.slice('Notes: '.length, -2)).toBe(notes.slice(0, 3000));
      expect(line).not.toMatch(BROKEN);
    }
  });
});

describe('the on-device assistant keeps its limits (S4b-BL-181)', () => {
  let http: HttpTestingController;
  let ai: OnDeviceAiService;
  let houses: unknown[];
  const KEY = 'test-key-one'; // a made-up value, never a credential
  const row = (i: number) => ({
    id: `00000000-0000-4000-8000-${String(i).padStart(12, '0')}`, label: `House ${i}`, address: null, street: null, locality: 'Indiranagar',
    lat: START.lat + i * 0.001, lon: START.lon, status: 'SHORTLISTED', price: 25000, priceType: 'RENT', bedrooms: 2, rating: 4,
    contactName: null, contactPhone: null, listingUrl: null, notes: null, checklist: null, updatedAt: '2026-09-20T10:00:00Z', deleted: false,
  });
  const reply = (json: unknown) => ({ candidates: [{ content: { parts: [{ text: JSON.stringify(json) }] } }] });

  beforeEach(() => {
    houses = Array.from({ length: 45 }, (_, i) => row(44 - i));
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(), provideHttpClientTesting(),
        { provide: LocalStore, useValue: { allHouses: async () => houses, allVisits: async () => [], viewings: { all: async () => [] }, areas: { all: async () => [] }, places: { all: async () => [] }, areaNotes: { rows: async () => [] } } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    ai = TestBed.inject(OnDeviceAiService);
  });

  afterEach(() => http.verify());

  async function request() {
    await new Promise((r) => setTimeout(r));
    return http.expectOne(GEMINI_URL);
  }

  it('offers a plan only the forty nearest of forty-five houses and says at most eight stops', async () => {
    const plan = ai.planVisits(KEY, { question: 'A walk', startLat: START.lat, startLon: START.lon, maxStops: 25 });
    const req = await request();
    const sent = JSON.stringify(req.request.body);
    const offered = [...sent.matchAll(/label: (House \d+) \|/g)].map((m) => m[1]);
    expect(offered).toEqual(Array.from({ length: 40 }, (_, i) => `House ${i}`));
    expect(sent).toContain('Plan at most 8 stops.');
    req.flush(reply({ summary: 's', stops: Array.from({ length: 12 }, (_, i) => ({ houseId: row(i).id, reason: 'near' })) }));
    const res = await plan;
    expect(res.stops).toHaveLength(8);
    expect(res.stops.map((s) => s.label)).toEqual(Array.from({ length: 8 }, (_, i) => `House ${i}`));
  });

  it('lets a request ask for fewer stops, never for none, never for more than eight', async () => {
    for (const [asked, said] of [[3, 3], [0, 1], [-4, 1], [9, 8]] as const) {
      const plan = ai.planVisits(KEY, { question: 'A walk', startLat: START.lat, startLon: START.lon, maxStops: asked });
      const req = await request();
      expect(JSON.stringify(req.request.body), `asked for ${asked}`).toContain(`Plan at most ${said} stops.`);
      req.flush(reply({ summary: 's', stops: [] }));
      await plan;
    }
  });

  it('accepts a pasted listing of 8,000 characters and refuses 8,001 before any request', async () => {
    expect(MAX_INPUT_CHARS).toBe(8000);
    await expect(ai.extractListing(KEY, 'a'.repeat(8001))).rejects.toThrow(/too long/);
    http.expectNone(GEMINI_URL);
    const draft = ai.extractListing(KEY, 'a'.repeat(8000));
    const req = await request();
    expect(JSON.stringify(req.request.body)).toContain('a'.repeat(8000));
    req.flush(reply({ label: 'x' }));
    await draft;
  });

  it('refuses a listing of spaces only', async () => {
    await expect(ai.extractListing(KEY, ' '.repeat(10))).rejects.toThrow(/empty/);
    http.expectNone(GEMINI_URL);
  });

  it('refuses a start point that is not on Earth before any request', async () => {
    const bad: [number, number][] = [[90.0001, 77], [-90.0001, 77], [12, 180.0001], [12, -180.0001], [NaN, 77], [12, NaN], [Infinity, 77], [12, -Infinity]];
    for (const [startLat, startLon] of bad) {
      await expect(ai.planVisits(KEY, { question: 'A walk', startLat, startLon }), `${startLat}, ${startLon}`).rejects.toThrow(/start point/);
    }
    http.expectNone(GEMINI_URL);
  });

  it('accepts the poles and the date line', async () => {
    for (const [startLat, startLon] of [[90, 180], [-90, -180]]) {
      const plan = ai.planVisits(KEY, { question: 'A walk', startLat, startLon });
      const req = await request();
      req.flush(reply({ summary: 's', stops: [] }));
      await plan;
    }
  });
});
