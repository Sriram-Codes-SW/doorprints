/*
 * Golden file: the self-contained HTML copy.
 *
 * Generated from golden/fixture.ts and checked in. The exporter tests compare their output with these
 * strings byte for byte, so any change to a format shows up as a diff here and has to be deliberate
 * (docs/11 section 5.2: "the same output for the same data and options").
 *
 * Do not edit by hand to make a test pass: change the exporter, then update this file on purpose.
 */

export const GOLDEN_HTML = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; style-src 'unsafe-inline'">
<title>Doorprints — your houses</title>
<style>:root { color-scheme: light; }
* { box-sizing: border-box; }
body { margin: 0 auto; padding: 24px 16px 48px; max-width: 46rem; background: #ffffff; color: #1c2421;
  font-family: system-ui, -apple-system, 'Segoe UI', Roboto, 'Noto Sans', 'Noto Sans Devanagari',
  'Noto Sans Tamil', 'Noto Sans Telugu', sans-serif; line-height: 1.6; }
h1 { font-size: 1.75rem; margin: 0 0 8px; }
h2 { font-size: 1.375rem; margin: 32px 0 8px; }
h3 { font-size: 1rem; margin: 20px 0 6px; }
p { margin: 0 0 12px; }
.cover { border-bottom: 3px solid #1f6f5c; padding-bottom: 16px; }
.lead { font-size: 1.125rem; }
.meta, .options { color: #4a5551; }
.options { margin: 0 0 12px; padding-left: 1.25rem; }
.privacy { background: #e3f0ec; border-left: 4px solid #1f6f5c; padding: 8px 12px; }
table { border-collapse: collapse; width: 100%; margin: 0 0 16px; }
th, td { border: 1px solid #d9e0dd; padding: 6px 10px; text-align: start; vertical-align: top; }
thead th { background: #eef2f0; }
.fields th { width: 38%; background: #f7faf9; font-weight: 600; }
.notes { white-space: normal; }
.photos { display: flex; flex-wrap: wrap; gap: 8px; }
.photos figure { margin: 0; width: calc(50% - 4px); }
.photos img { width: 100%; height: auto; border: 1px solid #d9e0dd; border-radius: 6px; }
.photo-files { margin: 0 0 8px; padding-left: 1.25rem; }
.house { border-top: 1px solid #d9e0dd; padding-top: 8px; }
.empty { font-style: italic; }
footer { margin-top: 32px; border-top: 1px solid #d9e0dd; padding-top: 12px; color: #4a5551; font-size: 0.875rem; }
@page { size: A4; margin: 14mm; }
@media print {
  body { max-width: none; padding: 0; }
  .house { break-before: page; page-break-before: always; border-top: 0; }
  .photos figure { width: calc(33% - 6px); }
  a { text-decoration: none; color: inherit; }
}</style>
</head>
<body>
<header class="cover">
<h1>Doorprints — your houses</h1>
<p class="lead">Your own copy. It opens without the app and without the internet.</p>
<p class="meta">Made on 2026-09-22 10:15 UTC</p>
<p class="meta">Houses: 3 · Visits: 3 · Photos: 2</p>
<h2>What is in this file</h2>
<ul class="options"><li>All houses</li><li>Rejected houses included</li><li>Photos of every house</li><li>Contact details included</li></ul>
<p class="privacy">This copy contains phone numbers of owners and brokers. Share it carefully.</p>
</header>
<section class="ranking">
<h2>Ranking</h2>
<table>
<thead><tr><th scope="col">No.</th><th scope="col">House</th><th scope="col">Score</th><th scope="col">Price</th><th scope="col">Status</th></tr></thead>
<tbody><tr><td>1</td><th scope="row">Green View 2BHK</th><td>3.8</td><td>₹32,000/month</td><td>★ Shortlisted</td></tr><tr><td>2</td><th scope="row">Untitled</th><td>0.5</td><td>₹12,50,000</td><td>✕ Rejected</td></tr><tr><td>3</td><th scope="row">=SUM(A1:A9) சென்னை flat</th><td>Not scored</td><td>–</td><td>● New</td></tr></tbody>
</table>
</section>
<section class="house">
<h2>1. Green View 2BHK</h2>
<table class="fields"><tr><th scope="row">Status</th><td>★ Shortlisted</td></tr><tr><th scope="row">Overall score</th><td>3.8</td></tr><tr><th scope="row">Price</th><td>₹32,000/month</td></tr><tr><th scope="row">BHK</th><td>2 BHK</td></tr><tr><th scope="row">Your rating</th><td>4 out of 5 stars</td></tr><tr><th scope="row">Address</th><td>12, MG Road</td></tr><tr><th scope="row">Street</th><td>MG Road</td></tr><tr><th scope="row">Locality</th><td>Adyar</td></tr><tr><th scope="row">Location</th><td>13.006000, 80.257400</td></tr><tr><th scope="row">Listing link</th><td>https://example.com/listing/1</td></tr><tr><th scope="row">Contact name</th><td>Ravi Kumar</td></tr><tr><th scope="row">Contact phone</th><td>+91 98400 11111</td></tr><tr><th scope="row">Saved on</th><td>2026-09-01</td></tr></table>
<h3>Checklist</h3><table class="fields"><tr><th scope="row">Water supply</th><td>5 out of 5</td></tr><tr><th scope="row">Power backup</th><td>3 out of 5</td></tr><tr><th scope="row">Parking</th><td>4 out of 5</td></tr><tr><th scope="row">newItemFromNewerApp</th><td>2 out of 5</td></tr></table>
<h3>Visits</h3><table><thead><tr><th scope="col">Arrived</th><th scope="col">Left</th><th scope="col">Street</th></tr></thead><tbody><tr><td>2026-09-05 11:00 UTC</td><td>2026-09-05 11:25 UTC</td><td>MG Road</td></tr><tr><td>2026-09-09 04:00 UTC</td><td>–</td><td>MG Road</td></tr></tbody></table>
<h3>Notes</h3><p class="notes">Owner said &quot;no pets&quot; &amp; &lt;no smoking&gt;.<br>Ask about water in summer.</p>
<h3>Photos</h3><div class="photos"><figure><img src="data:image/jpeg;base64,/9j/" alt="Photo 1 of Green View 2BHK"></figure></div>
</section>
<section class="house">
<h2>2. =SUM(A1:A9) சென்னை flat</h2>
<table class="fields"><tr><th scope="row">Status</th><td>● New</td></tr><tr><th scope="row">Overall score</th><td>Not scored</td></tr><tr><th scope="row">Location</th><td>0.000000, 0.000000</td></tr><tr><th scope="row">Saved on</th><td>2026-09-02</td></tr></table>
</section>
<section class="house">
<h2>3. Untitled</h2>
<table class="fields"><tr><th scope="row">Status</th><td>✕ Rejected</td></tr><tr><th scope="row">Overall score</th><td>0.5</td></tr><tr><th scope="row">Price</th><td>₹12,50,000</td></tr><tr><th scope="row">BHK</th><td>3 BHK</td></tr><tr><th scope="row">Your rating</th><td>1 out of 5 stars</td></tr><tr><th scope="row">Street</th><td>Beach Road</td></tr><tr><th scope="row">Location</th><td>13.050000, 80.280000</td></tr><tr><th scope="row">Saved on</th><td>2026-09-03</td></tr></table>
<h3>Checklist</h3><table class="fields"><tr><th scope="row">Quiet (low noise)</th><td>0 out of 5</td></tr></table>
<h3>Visits</h3><table><thead><tr><th scope="col">Arrived</th><th scope="col">Left</th><th scope="col">Street</th></tr></thead><tbody><tr><td>2026-09-06 13:06 UTC</td><td>2026-09-06 13:36 UTC</td><td>Beach Road</td></tr></tbody></table>
<h3>Notes</h3><p class="notes">Too noisy | too dark</p>
<h3>Photos</h3><div class="photos"><figure><img src="data:image/jpeg;base64,/9j/" alt="Photo 1 of Untitled"></figure></div>
</section>
<footer><p>Made with Doorprints. This file needs no app and no internet.</p></footer>
</body>
</html>
`;
