import { describe, expect, it } from 'vitest';
import { DICTIONARIES } from '../i18n/languages';
import {
  csvCell,
  escapeHtml,
  escapeLeadingMarker,
  escapeMarkdown,
  escapeXml,
  fixed,
  fileStamp,
  formatCoord,
  formatDate,
  formatDateTime,
  formatDecimal,
  formatInt,
  formatPrice,
  indianGroup,
  isoUtc,
  rupees,
  markdownCell,
  tr,
} from './deterministic';

const en = DICTIONARIES.en;

describe('indianGroup', () => {
  it('groups the last three digits, then pairs', () => {
    expect(indianGroup(0)).toBe('0');
    expect(indianGroup(999)).toBe('999');
    expect(indianGroup(1000)).toBe('1,000');
    expect(indianGroup(32000)).toBe('32,000');
    expect(indianGroup(125000)).toBe('1,25,000');
    expect(indianGroup(1250000)).toBe('12,50,000');
    expect(indianGroup(123456789)).toBe('12,34,56,789');
  });

  it('keeps the sign and rounds to whole rupees', () => {
    expect(indianGroup(-125000)).toBe('-1,25,000');
    expect(indianGroup(1500.6)).toBe('1,501');
  });
});

describe('formatPrice', () => {
  it('adds ₹ and, for rent, the translated per-month suffix', () => {
    expect(formatPrice(en, 32000, 'RENT')).toBe('₹32,000/month');
    expect(formatPrice(en, 1250000, 'SALE')).toBe('₹12,50,000');
    expect(formatPrice(en, null, 'RENT')).toBe('–');
    expect(formatPrice(en, undefined, null)).toBe('–');
  });

  it('uses the chosen language, not the app language', () => {
    expect(formatPrice(DICTIONARIES.ta, 32000, 'RENT')).toContain('₹32,000');
  });
});

describe('rupees', () => {
  it('matches ExportRows.rupees: the minus goes before the sign', () => {
    expect(rupees(1250000)).toBe('₹12,50,000');
    expect(rupees(-125000)).toBe('-₹1,25,000');
    expect(rupees(0)).toBe('₹0');
  });
});

describe('fixed', () => {
  it('rounds ties away from zero', () => {
    expect(fixed(2.5, 0)).toBe('3');
    expect(fixed(-2.5, 0)).toBe('-3');
    expect(fixed(3.85, 1)).toBe('3.9');
    expect(fixed(-3.85, 1)).toBe('-3.9');
  });

  /**
   * `toFixed` rounds on the exact decimal value of the double, while the Kotlin exporter uses
   * `floor(x * 10^n + 0.5)` on the double itself. For values like 0.015 the two disagree, and a copy of the same
   * house made on a phone and in a browser would then show different scores — so the web app mirrors the Kotlin
   * arithmetic rather than reaching for `toFixed`.
   */
  it('follows the Kotlin arithmetic where toFixed would disagree', () => {
    expect(fixed(0.015, 2)).toBe('0.02');
    expect((0.015).toFixed(2)).toBe('0.01');
    expect(fixed(0.045, 2)).toBe('0.05');
    expect((0.045).toFixed(2)).toBe('0.04');
  });

  it('always writes the requested number of decimals', () => {
    expect(fixed(0, 2)).toBe('0.00');
    expect(fixed(1234.5678, 2)).toBe('1234.57');
    expect(fixed(13.006, 6)).toBe('13.006000');
  });

  it('never writes a negative zero', () => {
    expect(fixed(-0.001, 1)).toBe('0.0');
  });

  it('writes nothing for a non-finite value', () => {
    expect(fixed(Number.NaN, 1)).toBe('');
    expect(fixed(Number.POSITIVE_INFINITY, 1)).toBe('');
  });
});

describe('numbers and dates', () => {
  it('formats with a fixed number of decimals and ASCII digits', () => {
    expect(formatDecimal(3.75, 1)).toBe('3.8');
    expect(formatDecimal(0, 1)).toBe('0.0');
    expect(formatDecimal(null)).toBe('–');
    expect(formatInt(2)).toBe('2');
    expect(formatInt(Number.NaN)).toBe('–');
    expect(formatCoord(13.006)).toBe('13.006000');
  });

  it('writes dates in UTC so the file does not depend on the reader’s time zone', () => {
    expect(formatDate('2026-09-22T23:45:00.000Z')).toBe('2026-09-22');
    expect(formatDateTime('2026-09-22T10:15:30.000Z')).toBe('2026-09-22 10:15 UTC');
    expect(formatDate('not a date')).toBe('–');
    expect(formatDateTime(null)).toBe('–');
  });

  it('normalises timestamps for data files', () => {
    expect(isoUtc('2026-09-22T10:15:30Z')).toBe('2026-09-22T10:15:30.000Z');
    expect(isoUtc(null)).toBe('');
  });

  it('names files by the UTC export date', () => {
    expect(fileStamp('2026-09-22T10:15:30.000Z')).toBe('2026-09-22');
    expect(fileStamp('')).toBe('0000-00-00');
  });
});

describe('tr', () => {
  it('fills placeholders and leaves unknown ones alone', () => {
    expect(tr(en, 'exp.photoAlt', { n: 1, house: 'A' })).toBe('Photo 1 of A');
    expect(tr(en, 'exp.photoAlt', { n: 1 })).toBe('Photo 1 of {house}');
    expect(tr(en, 'exp.ranking')).toBe('Ranking');
  });
});

describe('escaping', () => {
  it('escapes HTML', () => {
    expect(escapeHtml('<b>"x" & \'y\'</b>')).toBe('&lt;b&gt;&quot;x&quot; &amp; &#39;y&#39;&lt;/b&gt;');
    expect(escapeHtml(null)).toBe('');
  });

  it('escapes XML and drops characters XML 1.0 cannot carry', () => {
    expect(escapeXml('a\u0000b<c')).toBe('ab&lt;c');
  });

  it('escapes only the Markdown characters that matter', () => {
    expect(escapeMarkdown('**bold** | pipe')).toBe('\\*\\*bold\\*\\* \\| pipe');
    // Dates stay readable: hyphens and full stops are not escaped.
    expect(escapeMarkdown('2026-09-22.')).toBe('2026-09-22.');
    expect(markdownCell('two\nlines')).toBe('two lines');
  });

  /**
   * Inline escaping is not enough: Markdown decides headings, bullets and ordered lists from the first non-space
   * character of a **line**, so a note that began "# " used to be exported as a heading and one that began "- "
   * as a list item. The `#`, `+` and `-` cases mirror `MarkdownWriter.text` in android/shared.
   */
  it('escapes a leading heading, bullet or ordered-list marker so a note is not reformatted', () => {
    expect(escapeMarkdown('# Not a heading')).toBe('\\# Not a heading');
    expect(escapeMarkdown('#tag')).toBe('\\#tag');
    expect(escapeMarkdown('- not a bullet')).toBe('\\- not a bullet');
    expect(escapeMarkdown('+ not a bullet')).toBe('\\+ not a bullet');
    expect(escapeMarkdown('1. not a list')).toBe('1\\. not a list');
    expect(escapeMarkdown('1) not a list')).toBe('1\\) not a list');
    expect(escapeMarkdown('  # indented still counts')).toBe('  \\# indented still counts');
    // Every line of a multi-line note, not just the first.
    expect(escapeMarkdown('fine\n# heading')).toBe('fine\n\\# heading');
  });

  it('leaves numbers, dates and negative coordinates readable', () => {
    // A "-" only starts a list when it stands alone or is followed by a space.
    expect(escapeLeadingMarker('-12.978321')).toBe('-12.978321');
    expect(escapeLeadingMarker('2026-09-22')).toBe('2026-09-22');
    expect(escapeLeadingMarker('3.8')).toBe('3.8');
    expect(escapeLeadingMarker('10.5 km')).toBe('10.5 km');
    expect(escapeLeadingMarker('')).toBe('');
  });
});

describe('csvCell', () => {
  it('quotes fields that contain a comma, a quote or a newline', () => {
    expect(csvCell('12, MG Road')).toBe('"12, MG Road"');
    expect(csvCell('say "hi"')).toBe('"say ""hi"""');
    expect(csvCell('a\nb')).toBe('"a\nb"');
    expect(csvCell('plain')).toBe('plain');
  });

  it('defuses spreadsheet formulas', () => {
    expect(csvCell('=SUM(A1)')).toBe("'=SUM(A1)");
    expect(csvCell('+91 12345')).toBe("'+91 12345");
    expect(csvCell('-5')).toBe("'-5");
    expect(csvCell('@here')).toBe("'@here");
  });

  it('writes nothing for null, undefined and non-finite numbers', () => {
    expect(csvCell(null)).toBe('');
    expect(csvCell(undefined)).toBe('');
    expect(csvCell(Number.NaN)).toBe('');
    expect(csvCell(0)).toBe('0');
  });

  /**
   * The guard is for text only. `csv-export.ts` passes false for a number cell: a leading minus is part of the
   * number, and an apostrophe in front of it would stop a spreadsheet reading -12.978321 as a coordinate.
   */
  it('leaves a number alone when the caller says it is not text', () => {
    expect(csvCell('-12.978321', false)).toBe('-12.978321');
    expect(csvCell('-12.978321')).toBe("'-12.978321");
  });

  it('quotes a field with a leading or trailing space, like the Kotlin writer', () => {
    expect(csvCell(' padded')).toBe('" padded"');
    expect(csvCell('padded ')).toBe('"padded "');
  });
});
