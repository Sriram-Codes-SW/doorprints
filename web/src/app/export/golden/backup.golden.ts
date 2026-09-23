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
 * owned by the Backend and Docs teams. The string below is byte-identical to its contents — **2 144 bytes**, the
 * file adding only a trailing newline — and must stay so, because it is what proves a backup written in a browser
 * imports on a phone and the other way round. Verified by regenerating this string from `fixture.ts` through the
 * real `collect` + `buildBackupData` + `backupJson` and diffing it against the canonical file: identical, 2 144
 * bytes each. `exporters.spec.ts` asserts that byte count as well as the string, so a re-generation cannot
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

export const GOLDEN_BACKUP_DATA_JSON = `{"format":"doorprints-backup/1","exportedAt":1790072130000,"houses":[{"id":"11111111-1111-4111-8111-111111111111","label":"Green View 2BHK","address":"12, MG Road","street":"MG Road","locality":"Adyar","lat":13.006,"lon":80.2574,"status":"SHORTLISTED","price":32000,"priceType":"RENT","bedrooms":2,"rating":4,"contactName":"Ravi Kumar","contactPhone":"+91 98400 11111","listingUrl":"https://example.com/listing/1","notes":"Owner said \\"no pets\\" & <no smoking>.\\nAsk about water in summer.","checklist":{"newItemFromNewerApp":2,"parking":4,"power":3,"water":5},"createdAt":1788242400000,"updatedAt":1789029000000},{"id":"22222222-2222-4222-8222-222222222222","label":"=SUM(A1:A9) சென்னை flat","lat":0,"lon":0,"status":"NEW","checklist":{},"createdAt":1788328800000,"updatedAt":1788328800000},{"id":"33333333-3333-4333-8333-333333333333","label":"","street":"Beach Road","lat":13.05,"lon":80.28,"status":"REJECTED","price":1250000,"priceType":"SALE","bedrooms":3,"rating":1,"notes":"Too noisy | too dark","checklist":{"noise":0},"createdAt":1788415200000,"updatedAt":1788415200000}],"visits":[{"id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1","houseId":"11111111-1111-4111-8111-111111111111","lat":13.006,"lon":80.2574,"street":"MG Road","arrivedAt":1788606000000,"leftAt":1788607500000,"source":"MANUAL","updatedAt":1788607500000},{"id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2","houseId":"11111111-1111-4111-8111-111111111111","lat":13.0061,"lon":80.2575,"street":"MG Road","arrivedAt":1788926400000,"source":"AUTO","updatedAt":1788926400000},{"id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa3","houseId":"33333333-3333-4333-8333-333333333333","lat":13.05,"lon":80.28,"street":"Beach Road","arrivedAt":1788700000000,"leftAt":1788701800000,"source":"MANUAL","updatedAt":1788701800000}],"photos":[{"id":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1","houseId":"11111111-1111-4111-8111-111111111111","fileName":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1.jpg","createdAt":1788606300000},{"id":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2","houseId":"33333333-3333-4333-8333-333333333333","fileName":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2.jpg","createdAt":1788415800000}]}`;
