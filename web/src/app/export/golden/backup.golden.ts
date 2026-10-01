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

/*
 * Golden file: the backup's data.json.
 *
 * Generated from golden/fixture.ts and checked in. The exporter tests compare their output with this string byte
 * for byte, so any change to the format shows up as a diff here and has to be deliberate
 * (docs/11 section 5.2: "the same output for the same data and options").
 *
 * Do not edit by hand to make a test pass: change the exporter, then update this file on purpose.
 *
 * **Paired file: `docs/schemas/backup-sample.json`.** That file is the pinned contract for the backup format,
 * owned by the Backend and Docs teams. The string below is byte-identical to its contents — **6283 bytes**, the
 * file adding only a trailing newline — and must stay so, because it is what proves a backup written in a browser
 * imports on a phone and the other way round. Verified by regenerating this string from `fixture.ts` through the
 * real `collect` + `buildBackupData` + `backupJson` and diffing it against the canonical file: identical, 6283
 * bytes each (slice 1a, 2026-09-30: house 1 and house 3 carry `areaSqft`, `locationSource` and `cost`; slice 1b, 2026-09-30: the
 * format is `/2`, both houses carry `brokerId` and the `brokers` list closes the file; slice 1c, 2026-09-30: house 1
 * carries two rooms with sizes, condition and notes; slice 2, 2026-09-30: the `criteria` list (an archived built-in, a custom
 * criterion, a High must-have) and the `preferences` list (the rating share) follow `brokers`; slice 3a, 2026-09-30: house 1 carries two `answers` after its `rooms` and the `questions` list (two seeded defaults and an archived custom one) follows; slice 3b-1, 2026-09-30: the `viewings` list (a done first viewing with a visit and a planned second one with a hunt reminder and notes) follows `questions`; slice 4a, 2026-09-30: the `areas` (two, the second switched off), `places` (two) and `areaNotes` (an area note and a street note) lists follow `viewings` and close the file; slice 5, 2026-09-30: house 1 is TAKEN and carries `moveIn` after its `answers`, and photo 1 carries `roomId`, `tags`, `caption` and `metaUpdatedAt`). `exporters.spec.ts` asserts that byte count as well as the string, so a re-generation cannot
 * quietly shrink the contract.
 *
 * **What the two extra rows are for.** Visit `…aaa3` belongs to house 3 and arrives *between* house 1's two
 * visits; photo `…bbb2` belongs to house 3 and was created *before* house 1's. `docs/schemas/README.md` v1.1
 * section 5 (handover S4-00/b) added them on purpose: grouped by house — the rule ADR-20 and NFR-023 promise —
 * both of house 3's rows still come last, while a global sort by `arrivedAt` / `createdAt` would move them up. The
 * earlier fixture had everything on house 1, agreed with both rules by accident and pinned neither, so a
 * regression to a global sort would have stayed green. `exporters.spec.ts` now asserts the grouped order and that
 * the two rules really disagree on this fixture, which is what makes the golden mean something.
 *
 * Note that the CSV and XLSX **tables** are deliberately the other way round — globally sorted, because Android's
 * `ExportBundle` keeps one flat list and `ExportRows` reads it straight (see `flatVisits` in export-rows.ts). Both
 * orders are pinned by tests; neither is a bug to be "fixed" into the other.
 *
 * Nothing compares this string with the canonical file automatically yet: this suite pins the copy, so a change
 * made in `docs/schemas/` alone would leave the web build green and surface only when a real import failed.
 * A generated copy plus an equality check has been raised with the Docs and DevOps teams as an S4-06 item;
 * until it exists, anyone touching either file must diff it against the other.
 */

export const GOLDEN_BACKUP_DATA_JSON = `{"format":"doorprints-backup/2","exportedAt":1790072130000,"houses":[{"id":"11111111-1111-4111-8111-111111111111","label":"Green View 2BHK","address":"12, MG Road","street":"MG Road","locality":"Adyar","lat":13.006,"lon":80.2574,"status":"TAKEN","price":32000,"priceType":"RENT","bedrooms":2,"rating":4,"contactName":"Ravi Kumar","contactPhone":"+91 98400 11111","listingUrl":"https://example.com/listing/1","notes":"Owner said \\"no pets\\" & <no smoking>.\\nAsk about water in summer.","areaSqft":1150,"locationSource":"GPS","cost":{"deposit":64000,"maintenance":2500,"maintenanceIncluded":false,"brokerageMonths":1,"lockInMonths":11,"noticeMonths":2,"availableFrom":"2026-10-15","myOffer":30000,"agreedPrice":31000},"rooms":[{"id":"c1111111-1111-4111-8111-111111111111","type":"BEDROOM","name":"Master bedroom","lengthCm":396,"widthCm":366,"condition":4,"notes":"Damp patch near the window","sort":0},{"id":"c2222222-2222-4222-8222-222222222222","type":"KITCHEN","name":"Kitchen","lengthCm":300,"widthCm":244,"sort":1}],"answers":[{"id":"a1111111-1111-4111-8111-111111111111","questionId":"qd_maintenance","text":"How much is the maintenance per month, and what does it cover?","answer":"₹2,500 a month; it covers the lift, water and security","status":"ANSWERED","sort":0},{"id":"a2222222-2222-4222-8222-222222222222","text":"Is the terrace open to tenants?","status":"OPEN","sort":1}],"moveIn":{"date":1790812800000,"notes":"Keys handed over by Ravi. Electricity meter reads 4521.","items":[{"id":"mi_agreement","text":"Rental agreement signed and registered","done":true,"sort":0},{"id":"mi_police","text":"Police verification done","sort":1}]},"brokerId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","checklist":{"newItemFromNewerApp":2,"parking":4,"power":3,"water":5},"createdAt":1788242400000,"updatedAt":1789029000000},{"id":"22222222-2222-4222-8222-222222222222","label":"=SUM(A1:A9) சென்னை flat","lat":0,"lon":0,"status":"NEW","checklist":{},"createdAt":1788328800000,"updatedAt":1788328800000},{"id":"33333333-3333-4333-8333-333333333333","label":"","street":"Beach Road","lat":13.05,"lon":80.28,"status":"REJECTED","price":1250000,"priceType":"SALE","bedrooms":3,"rating":1,"notes":"Too noisy | too dark","areaSqft":1450,"locationSource":"MAP","cost":{"brokerage":25000,"agreedPrice":1200000},"brokerId":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb","checklist":{"noise":0},"createdAt":1788415200000,"updatedAt":1788415200000}],"visits":[{"id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1","houseId":"11111111-1111-4111-8111-111111111111","lat":13.006,"lon":80.2574,"street":"MG Road","arrivedAt":1788606000000,"leftAt":1788607500000,"source":"MANUAL","updatedAt":1788607500000},{"id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2","houseId":"11111111-1111-4111-8111-111111111111","lat":13.0061,"lon":80.2575,"street":"MG Road","arrivedAt":1788926400000,"source":"AUTO","updatedAt":1788926400000},{"id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa3","houseId":"33333333-3333-4333-8333-333333333333","lat":13.05,"lon":80.28,"street":"Beach Road","arrivedAt":1788700000000,"leftAt":1788701800000,"source":"MANUAL","updatedAt":1788701800000}],"photos":[{"id":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1","houseId":"11111111-1111-4111-8111-111111111111","fileName":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1.jpg","createdAt":1788606300000,"roomId":"c1111111-1111-4111-8111-111111111111","tags":["KITCHEN_FITTINGS","MOVE_IN","damp corner"],"caption":"Kitchen at move-in: tap drips slightly.","metaUpdatedAt":1790000000000},{"id":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2","houseId":"33333333-3333-4333-8333-333333333333","fileName":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2.jpg","createdAt":1788415800000}],"brokers":[{"id":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb","name":"Meena Iyer","agency":"Beach Road Realty","updatedAt":1788415200000},{"id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","name":"Ravi Kumar","phone":"+91 98400 11111","agency":"Adyar Homes","feeTerms":"15 days' rent, once","notes":"Replies fast; shows keys on weekends","rating":4,"updatedAt":1789029000000}],"criteria":[{"key":"noise","weight":0,"mustHave":false,"minScore":3,"sort":5,"archived":true,"updatedAt":1788328800000},{"key":"c_1a2b3c4d","label":"Pets allowed","weight":2,"mustHave":false,"minScore":3,"sort":10,"updatedAt":1788415200000},{"key":"water","weight":3,"mustHave":true,"minScore":4,"sort":0,"updatedAt":1789029000000}],"preferences":[{"key":"score.ratingShare","value":"0.4","updatedAt":1789029000000}],"questions":[{"id":"qd_deposit","text":"How many months is the deposit, and when and how is it refunded?","category":"MONEY","appliesTo":"RENT","defaultOn":true,"sort":1,"updatedAt":1788242400000},{"id":"qd_maintenance","text":"How much is the maintenance per month, and what does it cover?","category":"MONEY","appliesTo":"BOTH","defaultOn":true,"sort":0,"updatedAt":1788242400000},{"id":"q_9f8e7d6c","text":"Is there a water meter?","category":"WATER_POWER","appliesTo":"RENT","defaultOn":false,"sort":20,"archived":true,"updatedAt":1788328800000}],"viewings":[{"id":"v_3c4d5e6f","houseId":"11111111-1111-4111-8111-111111111111","startsAt":1788604800000,"durationMin":30,"kind":"FIRST","status":"DONE","remindMin":60,"withWhom":"Ravi Kumar","visitId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1","updatedAt":1788606600000},{"id":"v_a1b2c3d4","houseId":"11111111-1111-4111-8111-111111111111","startsAt":1790501400000,"durationMin":45,"kind":"SECOND","status":"PLANNED","remindMin":30,"huntReminder":true,"notes":"Ask for the water bill. Bring a tape; check the terrace, please.","updatedAt":1790072130000}],"areas":[{"id":"a_1f2e3d4c","name":"Adyar","lat":13.0067,"lon":80.2574,"radiusM":500,"updatedAt":1788328800000},{"id":"a_5b6c7d8e","name":"Indiranagar 2nd stage","lat":12.9784,"lon":77.6408,"radiusM":1200,"enabled":false,"updatedAt":1788415200000}],"places":[{"id":"p_0a1b2c3d","name":"Office","lat":13.0827,"lon":80.2707,"updatedAt":1788242400000},{"id":"p_4e5f6a7b","name":"Amma's home","lat":12.9716,"lon":77.5946,"updatedAt":1788328800000}],"areaNotes":[{"id":"n_11223344","areaId":"a_1f2e3d4c","text":"Water tanker every morning; the low streets flood in the monsoon.","updatedAt":1788501600000},{"id":"n_55667788","street":"MG Road","text":"Noisy after 9 pm: the bus depot is on the corner.","updatedAt":1788588000000}]}`;
