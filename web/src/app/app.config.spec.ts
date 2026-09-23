import { TestBed } from '@angular/core/testing';
import { ROUTER_CONFIGURATION } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { appConfig } from './app.config';

describe('appConfig', () => {
  /*
   * TC-NAV (UX lead review 2026-09-22): edit a house, press its Back, choose "Keep editing", Save, press Back again —
   * this must land on the list. With the router's default ('replace') the cancelled Back wrote the house URL over the
   * list's history entry, and the second Back left the app (or did nothing in an installed app).
   */
  it('restores history with historyGo() when a guard cancels a Back, instead of overwriting the previous entry', () => {
    TestBed.configureTestingModule({ providers: appConfig.providers });
    expect(TestBed.inject(ROUTER_CONFIGURATION).canceledNavigationResolution).toBe('computed');
  });
});
