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
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ConfirmService } from '../../core/confirm.service';
import { LocalDataService } from '../../core/local-data.service';
import { newHouse } from '../../core/models';
import type { HouseDto } from '../../core/models';
import { TranslationService } from '../../i18n/translation.service';
import type { Msg } from '../../i18n/translation.service';
import type { BrokerRow } from '../../shared/broker';
import { BrokerPage } from './broker-page';
import { BrokersPage } from './brokers-page';

const RAVI: BrokerRow = {
  id: 'b-1',
  updatedAt: '2026-09-10T08:30:00.000Z',
  broker: { name: 'Ravi Kumar', phone: '+91 98400 11111', agency: 'Adyar Homes', feeTerms: "15 days' rent, once", rating: 4 },
};
const MEENA: BrokerRow = { id: 'b-2', updatedAt: null, broker: { name: 'Meena Iyer' } };
const HOUSE: HouseDto = { ...newHouse(13, 80), id: 'h-1', label: 'Green View', brokerId: 'b-1' };

function api(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    settled: signal(0),
    brokers: () => of([RAVI, MEENA]),
    houses: () => of([HOUSE, { ...HOUSE, id: 'h-2' }, { ...HOUSE, id: 'h-3', brokerId: 'b-2' }]),
    brokerHouses: () => of([HOUSE]),
    saveBroker: vi.fn(() => of(RAVI)),
    deleteBroker: vi.fn(() => of(undefined)),
    ...overrides,
  };
}

function text(msg: Msg): string {
  return TestBed.inject(TranslationService).t(msg.key, msg.params);
}

afterEach(() => TestBed.resetTestingModule());

describe('BrokersPage', () => {
  async function render(data = api()): Promise<HTMLElement> {
    TestBed.configureTestingModule({ imports: [BrokersPage], providers: [provideRouter([]), { provide: LocalDataService, useValue: data }] });
    TestBed.inject(TranslationService).setLang('en');
    const fixture = TestBed.createComponent(BrokersPage);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('lists the brokers by name with agency, phone, stars and the number of houses', async () => {
    const host = await render();
    const items = [...host.querySelectorAll('li')].map((li) => li.textContent?.replace(/\s+/g, ' ').trim());
    expect(items).toHaveLength(2);
    expect(items[0]).toContain('Meena Iyer');
    expect(items[0]).toContain(text({ key: 'brokers.houses', params: { n: 1 } }));
    expect(items[1]).toContain('Ravi Kumar');
    expect(items[1]).toContain('Adyar Homes');
    expect(items[1]).toContain('+91 98400 11111');
    expect(items[1]).toContain('★★★★☆');
    expect(items[1]).toContain(text({ key: 'brokers.houses', params: { n: 2 } }));
    expect(host.querySelector<HTMLAnchorElement>('li a')!.getAttribute('href')).toBe('/brokers/b-2');
  });

  it('says how brokers appear when there are none, and still offers Add broker', async () => {
    const host = await render(api({ brokers: () => of([]) }));
    expect(host.textContent).toContain(text({ key: 'brokers.empty' }));
    expect(host.querySelector('li')).toBeNull();
    expect(host.querySelector<HTMLAnchorElement>('a.btn-primary')!.getAttribute('href')).toBe('/brokers/new');
  });
});

describe('BrokerPage', () => {
  async function render(id: string, data = api()): Promise<{ host: HTMLElement; detect: () => void; navigate: ReturnType<typeof vi.spyOn>; data: Record<string, ReturnType<typeof vi.fn>> }> {
    TestBed.configureTestingModule({
      imports: [BrokerPage],
      providers: [
        provideRouter([]),
        { provide: LocalDataService, useValue: data },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id }) } } },
      ],
    });
    TestBed.inject(TranslationService).setLang('en');
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const fixture = TestBed.createComponent(BrokerPage);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return { host: fixture.nativeElement as HTMLElement, detect: () => fixture.detectChanges(), navigate, data: data as never };
  }

  it('shows the fields, a Call link, the houses from this broker and Delete broker', async () => {
    const { host } = await render('b-1');
    expect(host.querySelector<HTMLInputElement>('#broker-name')!.value).toBe('Ravi Kumar');
    expect(host.querySelector<HTMLInputElement>('#broker-phone')!.value).toBe('+91 98400 11111');
    expect(host.querySelector<HTMLInputElement>('#broker-agency')!.value).toBe('Adyar Homes');
    expect(host.querySelector<HTMLInputElement>('#broker-fee')!.value).toBe("15 days' rent, once");
    expect(host.querySelector<HTMLAnchorElement>('a[href^="tel:"]')).not.toBeNull();
    expect(host.querySelector<HTMLAnchorElement>('a[href="/houses/h-1"]')?.textContent).toContain('Green View');
    expect(host.textContent).toContain(text({ key: 'brokers.deleteHint' }));
    expect(host.querySelector('.btn-danger')?.textContent).toContain(text({ key: 'brokers.delete' }));
  });

  it('warns under a house of this broker that another broker shows the same flat (S4b-BL-85), and only then', async () => {
    const flat = { ...HOUSE, bedrooms: 2, floor: 3, locationSource: 'GPS' as const };
    const other: HouseDto = { ...flat, id: 'h-9', label: 'Meena shows it too', brokerId: 'b-2', lat: 13.00009 };
    const { host } = await render('b-1', api({ brokerHouses: () => of([flat]), houses: () => of([flat, other]) }));
    expect(host.querySelector('.warn-box')?.textContent).toContain('Maybe the same flat as Meena shows it too');
    TestBed.resetTestingModule();
    const { host: plain } = await render('b-1', api({ brokerHouses: () => of([flat]), houses: () => of([flat, { ...other, floor: 4 }]) }));
    expect(plain.querySelector('.warn-box')).toBeNull();
  });

  it('says so for a broker that is not in this browser', async () => {
    const { host } = await render('nope');
    expect(host.textContent).toContain(text({ key: 'brokers.notFound' }));
    expect(host.querySelector('form')).toBeNull();
  });

  it('refuses a blank name and saves a valid broker under a new id', async () => {
    const { host, detect, navigate, data } = await render('new');
    const form = host.querySelector('form')!;
    form.dispatchEvent(new Event('submit'));
    detect();
    expect(data['saveBroker']).not.toHaveBeenCalled();
    expect(host.querySelector('#broker-name-error')).not.toBeNull();

    const name = host.querySelector<HTMLInputElement>('#broker-name')!;
    name.value = 'New One';
    name.dispatchEvent(new Event('input'));
    const phone = host.querySelector<HTMLInputElement>('#broker-phone')!;
    phone.value = '9000000001';
    phone.dispatchEvent(new Event('input'));
    form.dispatchEvent(new Event('submit'));
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(data['saveBroker']).toHaveBeenCalledTimes(1);
    const [id, broker] = data['saveBroker'].mock.calls[0] as unknown as [string, unknown];
    expect(id).toMatch(/^[0-9a-f-]{36}$/);
    expect(broker).toEqual({ name: 'New One', phone: '9000000001' });
    expect(navigate).toHaveBeenCalledWith(['/brokers']);
  });

  it('asks before deleting, and says the houses keep their contact details', async () => {
    const { host, navigate, data } = await render('b-1');
    const confirm = TestBed.inject(ConfirmService);
    const ask = vi.spyOn(confirm, 'ask').mockResolvedValueOnce(false).mockResolvedValueOnce(true);
    const del = host.querySelector<HTMLButtonElement>('.btn-danger')!;
    del.click();
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(data['deleteBroker']).not.toHaveBeenCalled();
    del.click();
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(data['deleteBroker']).toHaveBeenCalledWith('b-1');
    expect(navigate).toHaveBeenCalledWith(['/brokers']);
    const message = ask.mock.calls[0][0];
    expect(text(message)).toContain('Ravi Kumar');
    expect(text(message)).toContain('keep their contact details');
  });
});
