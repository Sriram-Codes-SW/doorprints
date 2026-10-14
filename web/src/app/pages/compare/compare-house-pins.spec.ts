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

import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { afterEach, describe, expect, it } from 'vitest';
import { LocalDataService } from '../../core/local-data.service';
import { newHouse } from '../../core/models';
import type { HouseDto } from '../../core/models';
import { LocalStore } from '../../data/local-store.service';
import type { Lang } from '../../i18n/languages';
import { TranslationService } from '../../i18n/translation.service';
import { DEFAULT_SCORING } from '../../shared/scoring';
import { ComparePage } from './compare-page';

/**
 * The house's Compare page, pinned whole before kinds PR B (S4b-BL-279; docs/03 §18.12): with only houses the page is
 * byte for byte what it was, whatever the page learns about other kinds. The text of the picker, the table and the
 * footnote, in English and in Tamil (docs/06 TC-U-216). Written first, green on `main`, kept green by the change.
 */
const RENT: HouseDto = {
  ...newHouse(13, 80),
  id: 'a',
  label: 'Green View',
  status: 'SHORTLISTED',
  price: 32000,
  priceType: 'RENT',
  bedrooms: 2,
  areaSqft: 1150,
  rating: 4,
  street: 'MG Road',
  locality: 'Indiranagar',
  contactName: 'Ravi',
  contactPhone: '+91 98450 12345',
  checklist: { water: 4, security: 3 },
  cost: { deposit: 64000, maintenance: 2500, maintenanceIncluded: false, brokerageMonths: 1, availableFrom: '2026-10-15', agreedPrice: 31000 },
  rooms: [{ id: 'r1', type: 'HALL', lengthCm: 396, widthCm: 366, sort: 0 }],
};
const SALE: HouseDto = { ...newHouse(13.1, 80.1), id: 'b', label: 'Beach Road', price: 1250000, priceType: 'SALE', areaSqft: 1450, rating: 2, checklist: { water: 2 } };
const PLAIN: HouseDto = { ...newHouse(13.2, 80.2), id: 'c', label: 'Third' };

async function dump(lang: Lang): Promise<string[]> {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    imports: [ComparePage],
    providers: [
      provideRouter([]),
      {
        provide: LocalDataService,
        useValue: {
          settled: signal(0),
          scoring: () => of(DEFAULT_SCORING),
          houses: () => of([RENT, SALE, PLAIN]),
          visitCounts: () => of(new Map([['a', 2]])),
          places: () => of([{ id: 'p1', name: 'Office', lat: 13.0827, lon: 80.2707 }]),
          brokers: () => of([]),
        },
      },
      { provide: LocalStore, useValue: { lengthUnit: () => Promise.resolve('FT') } },
      { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({ ids: 'a,b,c' }) } } },
    ],
  });
  await TestBed.inject(TranslationService).setLang(lang);
  const fixture = TestBed.createComponent(ComparePage);
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const t = (el: Element | null | undefined) => el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
  return [
    't:' + t(host.querySelector('h1')),
    'hint:' + t(host.querySelector('#picker-hint')),
    'chips:' + [...host.querySelectorAll('.chip')].map(t).join(' | '),
    'head:' + [...host.querySelectorAll('thead th')].map(t).join(' | ') + ' @ ' + [...host.querySelectorAll('thead a')].map((a) => a.getAttribute('href')).join(','),
    ...[...host.querySelectorAll('tbody tr')].map((tr) => [...tr.children].map(t).join(' | ')),
    'foot:' + t(host.querySelector('.footnote')),
  ];
}

describe('the house’s Compare page, pinned (S4b-BL-279)', () => {
  afterEach(() => {
    localStorage.clear();
    TestBed.resetTestingModule();
  });

  it('English', async () => {
    expect(await dump('en')).toEqual([
  't:Compare houses',
  'hint:Choose 2 to 4 houses. Selected: 3.',
  'chips:★Shortlisted: Green View Score 3.8 | Beach Road Score 2.0 | Third Score –',
  'head:Criterion | Green View →★ Shortlisted | Beach Road →● New | Third →● New @ /houses/a,/houses/b,/houses/c',
  'Overall score | 3.8 ✓(best) | 2.0 | –No value',
  'Price | ₹32,000/month | ₹12,50,000 | –No value',
  'BHK | 2 | –No value | –No value',
  'Carpet area | 1,150 sq ft | 1,450 sq ft | –No value',
  'Monthly cost | ₹34,500 | –No value | –No value',
  'Money to move in | ₹1,28,000 | –No value | –No value',
  'Cost per sq ft | ₹27 | ₹862 | –No value',
  'Available from | 2026-10-15 | –No value | –No value',
  'Agreed price | ₹31,000 | –No value | –No value',
  'Rooms | 1 · 156 sq ft | –No value | –No value',
  'Your rating | ★★★★☆4 out of 5 stars | ★★☆☆☆2 out of 5 stars | –No value',
  'Visits | 2 | 0 | 0',
  'Street | MG Road, Indiranagar | –No value | –No value',
  'Distance to Office | 30.7 km | 18.6 km | 15.1 km',
  'Water supply | 4 ✓(best) | 2 | –No value',
  'Power backup | –No value | –No value | –No value',
  'Parking | –No value | –No value | –No value',
  'Sunlight | –No value | –No value | –No value',
  'Ventilation | –No value | –No value | –No value',
  'Quiet (low noise) | –No value | –No value | –No value',
  'Safety and security | 3 ✓(best) | –No value | –No value',
  'Building condition | –No value | –No value | –No value',
  'Neighbourhood | –No value | –No value | –No value',
  'Commute | –No value | –No value | –No value',
  'Contact | Ravi+91 98450 12345 | –No value | –No value',
  'foot:Checklist items are scored 0 to 5. The best value in each row is highlighted and marked ✓.',
]);
  });
  it('Tamil', async () => {
    expect(await dump('ta')).toEqual([
  't:வீடுகளை ஒப்பிடு',
  'hint:2 முதல் 4 வீடுகளைத் தேர்ந்தெடுக்கவும். தேர்ந்தெடுத்தவை: 3.',
  'chips:★தேர்வானது: Green View மதிப்பெண் 3.8 | Beach Road மதிப்பெண் 2.0 | Third மதிப்பெண் –',
  'head:அளவுகோல் | Green View →★ தேர்வானது | Beach Road →● புதியது | Third →● புதியது @ /houses/a,/houses/b,/houses/c',
  'மொத்த மதிப்பெண் | 3.8 ✓(சிறந்தது) | 2.0 | –மதிப்பு இல்லை',
  'விலை | ₹32,000/மாதம் | ₹12,50,000 | –மதிப்பு இல்லை',
  'BHK | 2 | –மதிப்பு இல்லை | –மதிப்பு இல்லை',
  'கார்பெட் பரப்பு | 1,150 சதுர அடி | 1,450 சதுர அடி | –மதிப்பு இல்லை',
  'மாதச் செலவு | ₹34,500 | –மதிப்பு இல்லை | –மதிப்பு இல்லை',
  'குடியேற வேண்டிய தொகை | ₹1,28,000 | –மதிப்பு இல்லை | –மதிப்பு இல்லை',
  'சதுர அடிக்கு செலவு | ₹27 | ₹862 | –மதிப்பு இல்லை',
  'எப்போதிலிருந்து கிடைக்கும் | 2026-10-15 | –மதிப்பு இல்லை | –மதிப்பு இல்லை',
  'ஒப்புக்கொண்ட விலை | ₹31,000 | –மதிப்பு இல்லை | –மதிப்பு இல்லை',
  'அறைகள் | 1 · 156 sq ft | –மதிப்பு இல்லை | –மதிப்பு இல்லை',
  'உங்கள் மதிப்பீடு | ★★★★☆5-க்கு 4 நட்சத்திரங்கள் | ★★☆☆☆5-க்கு 2 நட்சத்திரங்கள் | –மதிப்பு இல்லை',
  'வருகைகள் | 2 | 0 | 0',
  'தெரு | MG Road, Indiranagar | –மதிப்பு இல்லை | –மதிப்பு இல்லை',
  'Office வரை தூரம் | 30.7 கி.மீ. | 18.6 கி.மீ. | 15.1 கி.மீ.',
  'தண்ணீர் வசதி | 4 ✓(சிறந்தது) | 2 | –மதிப்பு இல்லை',
  'மின் காப்பு (பவர் பேக்கப்) | –மதிப்பு இல்லை | –மதிப்பு இல்லை | –மதிப்பு இல்லை',
  'வாகன நிறுத்தம் | –மதிப்பு இல்லை | –மதிப்பு இல்லை | –மதிப்பு இல்லை',
  'சூரிய ஒளி | –மதிப்பு இல்லை | –மதிப்பு இல்லை | –மதிப்பு இல்லை',
  'காற்றோட்டம் | –மதிப்பு இல்லை | –மதிப்பு இல்லை | –மதிப்பு இல்லை',
  'அமைதி (குறைந்த இரைச்சல்) | –மதிப்பு இல்லை | –மதிப்பு இல்லை | –மதிப்பு இல்லை',
  'பாதுகாப்பு | 3 ✓(சிறந்தது) | –மதிப்பு இல்லை | –மதிப்பு இல்லை',
  'கட்டிடத்தின் நிலை | –மதிப்பு இல்லை | –மதிப்பு இல்லை | –மதிப்பு இல்லை',
  'சுற்றுப்புறம் | –மதிப்பு இல்லை | –மதிப்பு இல்லை | –மதிப்பு இல்லை',
  'போக்குவரத்து வசதி | –மதிப்பு இல்லை | –மதிப்பு இல்லை | –மதிப்பு இல்லை',
  'தொடர்பு | Ravi+91 98450 12345 | –மதிப்பு இல்லை | –மதிப்பு இல்லை',
  'foot:சரிபார்ப்புப் பட்டியலின் ஒவ்வொன்றுக்கும் 0 முதல் 5 வரை மதிப்பெண். ஒவ்வொரு வரிசையிலும் சிறந்த மதிப்பு முன்னிலைப்படுத்தப்பட்டு ✓ குறியிடப்பட்டுள்ளது.',
]);
  });
});
