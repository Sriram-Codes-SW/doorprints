import type { Dict, TKey } from '../i18n/en';
import {
  escapeMarkdown,
  formatCoord,
  formatDate,
  formatDateTime,
  formatDecimal,
  formatPrice,
  markdownCell,
  tr,
} from './deterministic';
import type { ExportBundle, ExportHouse } from './export-model';
import { checklistEntries, checklistLabel, labelOf, statusText } from './html-export';
import { optionSummaryKeys } from './option-summary';
import { photoFileName } from './photo-names';

/**
 * The Markdown copy (docs/11 §5.2): headings per house, tables for scores and visits, photos listed by file name
 * (never embedded — that is what the HTML copy is for). Lines end with `\n`, the file ends with one newline, and
 * every piece of user text is escaped, so a house called `**deal**` reads as `**deal**`.
 */
export function buildMarkdown(bundle: ExportBundle, dict: Dict): string {
  const out: string[] = [];
  const { counts, options } = bundle;

  out.push(`# ${escapeMarkdown(tr(dict, 'exp.title'))}`, '');
  out.push(escapeMarkdown(tr(dict, 'exp.subtitle')), '');
  out.push(escapeMarkdown(tr(dict, 'exp.exportedAt', { date: formatDateTime(bundle.exportedAt) })), '');
  out.push(
    escapeMarkdown(tr(dict, 'exp.counts', { houses: counts.houses, visits: counts.visits, photos: counts.photos })),
    '',
  );
  out.push(`## ${escapeMarkdown(tr(dict, 'exp.optionsHeading'))}`, '');
  for (const key of optionSummaryKeys(options)) out.push(`- ${escapeMarkdown(tr(dict, key))}`);
  out.push('');
  out.push(`> ${escapeMarkdown(tr(dict, options.includeContacts ? 'exp.privacyContacts' : 'exp.privacyNoContacts'))}`, '');

  if (bundle.houses.length === 0) {
    out.push(escapeMarkdown(tr(dict, 'exp.noHouses')), '');
    out.push('---', '', escapeMarkdown(tr(dict, 'exp.footer')), '');
    return out.join('\n');
  }

  out.push(`## ${escapeMarkdown(tr(dict, 'exp.ranking'))}`, '');
  out.push(
    row([
      tr(dict, 'exp.colRank'),
      tr(dict, 'exp.colHouse'),
      tr(dict, 'common.score'),
      tr(dict, 'compare.price'),
      tr(dict, 'house.status'),
    ]),
  );
  out.push(separator(5));
  bundle.ranking.forEach((entry, index) => {
    out.push(
      row([
        String(index + 1),
        labelOf(entry.house.label, dict),
        entry.score === null ? tr(dict, 'house.notScored') : formatDecimal(entry.score, 1),
        formatPrice(dict, entry.house.price, entry.house.priceType),
        statusText(entry.house.status, dict),
      ]),
    );
  });
  out.push('');

  bundle.houses.forEach((entry, index) => out.push(...houseSection(entry, index + 1, bundle, dict)));

  out.push('---', '', escapeMarkdown(tr(dict, 'exp.footer')), '');
  return out.join('\n');
}

function houseSection(entry: ExportHouse, position: number, bundle: ExportBundle, dict: Dict): string[] {
  const { house, score, visits, photos } = entry;
  const label = labelOf(house.label, dict);
  const out: string[] = [];
  out.push(`## ${position}. ${escapeMarkdown(label)}`, '');

  const facts: [string, string][] = [];
  const push = (key: TKey, value: string) => {
    if (value && value !== '–') facts.push([tr(dict, key), value]);
  };
  const bedrooms = house.bedrooms ?? null;
  const rating = house.rating ?? null;
  push('house.status', statusText(house.status, dict));
  push('compare.overall', score === null ? tr(dict, 'house.notScored') : formatDecimal(score, 1));
  push('compare.price', formatPrice(dict, house.price, house.priceType));
  if (bedrooms !== null) push('compare.bhk', tr(dict, 'common.bhk', { n: bedrooms }));
  if (rating !== null) push('compare.rating', tr(dict, 'common.stars', { n: rating }));
  push('house.address', house.address ?? '');
  push('house.street', house.street ?? '');
  push('house.locality', house.locality ?? '');
  push('house.location', `${formatCoord(house.lat)}, ${formatCoord(house.lon)}`);
  push('house.listingUrl', house.listingUrl ?? '');
  if (bundle.options.includeContacts) {
    push('house.contactName', house.contactName ?? '');
    push('house.contactPhone', house.contactPhone ?? '');
  }
  push('exp.fieldSaved', formatDate(house.createdAt));

  if (facts.length) {
    out.push(row([tr(dict, 'exp.colField'), tr(dict, 'exp.colValue')]));
    out.push(separator(2));
    for (const [name, value] of facts) out.push(row([name, value]));
    out.push('');
  }

  const checklist = checklistEntries(house.checklist);
  if (checklist.length) {
    out.push(`### ${escapeMarkdown(tr(dict, 'house.checklist'))}`, '');
    out.push(row([tr(dict, 'compare.criterion'), tr(dict, 'common.score')]));
    out.push(separator(2));
    for (const [key, value] of checklist) out.push(row([checklistLabel(key, dict), tr(dict, 'exp.scoreOf5', { n: value })]));
    out.push('');
  }

  if (visits.length) {
    out.push(`### ${escapeMarkdown(tr(dict, 'house.visits'))}`, '');
    out.push(row([tr(dict, 'exp.colArrived'), tr(dict, 'exp.colLeft'), tr(dict, 'exp.colStreet')]));
    out.push(separator(3));
    for (const visit of visits) {
      out.push(
        row([formatDateTime(visit.arrivedAt), visit.leftAt ? formatDateTime(visit.leftAt) : '–', visit.street ?? '']),
      );
    }
    out.push('');
  }

  if (house.notes) {
    out.push(`### ${escapeMarkdown(tr(dict, 'house.notes'))}`, '');
    for (const line of house.notes.split(/\r?\n/)) out.push(escapeMarkdown(line));
    out.push('');
  }

  if (photos.length) {
    out.push(`### ${escapeMarkdown(tr(dict, 'house.photos'))}`, '');
    for (const photo of photos) out.push(`- \`${photoFileName(photo.id)}\``);
    out.push('');
  }

  return out;
}

function row(cells: readonly string[]): string {
  return `| ${cells.map(markdownCell).join(' | ')} |`;
}

function separator(columns: number): string {
  return `|${' --- |'.repeat(columns)}`;
}
