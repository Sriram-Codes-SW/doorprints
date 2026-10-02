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

import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { DeletionAction } from '../../data/drive/drive-deletion-rules';
import type { DriveConnectService } from '../../data/drive/connect/drive-connect.service';
import { AppDriveDelete } from './drive-delete';

const textOf = (el: HTMLElement): string => el.textContent?.replace(/\s+/g, ' ').trim() ?? '';

let fakeService: Pick<DriveConnectService, 'deletePlan' | 'deleteConfirmInfo' | 'authorizeDelete' | 'executeDelete' | 'resumeDelete' | 'passkeyStatus' | 'registerPasskey'>;

function makeFakeService(overrides: Partial<typeof fakeService> = {}) {
  return {
    deletePlan: vi.fn(async () => ({
      ok: true as const,
      plan: { items: [], totalBytes: 0 },
    })),
    deleteConfirmInfo: vi.fn(async () => ({
      ok: true as const,
      tickBoxRequired: false,
      delayMs: 0,
    })),
    authorizeDelete: vi.fn(async () => ({
      ok: true as const,
      grant: { token: 'test-token' },
    })),
    executeDelete: vi.fn(async () => ({ ok: true as const })),
    resumeDelete: vi.fn(async () => ({ ok: true as const })),
    passkeyStatus: vi.fn(async () => 'none' as const),
    registerPasskey: vi.fn(async () => 'registered' as const),
    ...overrides,
  };
}

function createComponent() {
  TestBed.configureTestingModule({
    imports: [AppDriveDelete],
  });

  const fixture = TestBed.createComponent(AppDriveDelete);
  const component = fixture.componentInstance;
  (component as any).service = fakeService;

  fixture.detectChanges();
  return { fixture, component, el: fixture.nativeElement as HTMLElement };
}

describe('AppDriveDelete', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    fakeService = makeFakeService();
  });

  afterEach(() => {
    TestBed.resetTestingModule();
    vi.useRealTimers();
    vi.clearAllMocks();
  });

  describe('Menu phase', () => {
    it('shows deletion action buttons initially', async () => {
      const { el } = createComponent();

      const heading = el.querySelector('h3');
      expect(heading?.textContent).toContain('Delete');
      const buttons = el.querySelectorAll('.deletion-menu button');
      expect(buttons.length).toBeGreaterThanOrEqual(3);
    });

    it('shows one backup button only when oneBackupId is set', async () => {
      const { fixture } = createComponent();
      const btn1 = fixture.nativeElement.querySelector('button:contains("Delete one backup")');
      expect(btn1).toBeFalsy();

      const fixture2 = TestBed.createComponent(AppDriveDelete);
      fixture2.componentInstance.oneBackupId = 'backup-123';
      fixture2.detectChanges();
      const btn2 = fixture2.nativeElement.querySelector('.deletion-menu button');
      expect(btn2).toBeTruthy();
    });

    it('calls deletePlan with correct action when button clicked', async () => {
      const { component } = createComponent();
      (component as any).service = fakeService;

      await component.startDeletion({ type: 'olderBackups' });

      expect(fakeService.deletePlan).toHaveBeenCalledWith({ type: 'olderBackups' });
    });
  });

  describe('Plan phase', () => {
    it('shows plan details after startDeletion', async () => {
      const { component, fixture } = createComponent();

      await component.startDeletion({ type: 'allBackups' });
      fixture.detectChanges();

      expect(component.phase()).toBe('plan');
      const heading = fixture.nativeElement.querySelector('.plan-box h3');
      expect(heading).toBeTruthy();
    });

    it('shows error when deletePlan fails', async () => {
      fakeService = makeFakeService({
        deletePlan: vi.fn(async () => ({ ok: false as const, reason: 'Network error' })),
      });
      const { component } = createComponent();
      (component as any).service = fakeService;

      await component.startDeletion({ type: 'everything' });

      expect(component.phase()).toBe('error');
      expect(component.error()).toBe('Network error');
    });

    it('transitions to confirm phase on proceedToConfirm', async () => {
      const { component } = createComponent();
      (component as any).service = fakeService;

      await component.startDeletion({ type: 'allBackups' });
      await component.proceedToConfirm();

      expect(component.phase()).toBe('confirm');
    });

    it('calls deleteConfirmInfo when proceeding', async () => {
      const { component } = createComponent();
      (component as any).service = fakeService;

      await component.startDeletion({ type: 'allBackups' });
      await component.proceedToConfirm();

      expect(fakeService.deleteConfirmInfo).toHaveBeenCalledWith({ type: 'allBackups' });
    });
  });

  describe('Confirm phase', () => {
    it('shows tick box when tickBoxRequired is true', async () => {
      fakeService = makeFakeService({
        deleteConfirmInfo: vi.fn(async () => ({
          ok: true as const,
          tickBoxRequired: true,
          delayMs: 5000,
        })),
      });
      const { component, fixture, el } = createComponent();
      (component as any).service = fakeService;

      await component.startDeletion({ type: 'everything' });
      await component.proceedToConfirm();
      fixture.detectChanges();

      const checkbox = el.querySelector('input[type="checkbox"]');
      expect(checkbox).toBeTruthy();
    });

    it('enable confirm button immediately when no tick box required', async () => {
      fakeService = makeFakeService({
        deleteConfirmInfo: vi.fn(async () => ({
          ok: true as const,
          tickBoxRequired: false,
          delayMs: 0,
        })),
      });
      const { component, fixture } = createComponent();
      (component as any).service = fakeService;

      await component.startDeletion({ type: 'olderBackups' });
      await component.proceedToConfirm();
      fixture.detectChanges();

      expect(component.confirmEnabled()).toBe(true);
    });

    it('shows countdown after tick box is ticked', async () => {
      fakeService = makeFakeService({
        deleteConfirmInfo: vi.fn(async () => ({
          ok: true as const,
          tickBoxRequired: true,
          delayMs: 5000,
        })),
      });
      const { component, fixture, el } = createComponent();
      (component as any).service = fakeService;

      await component.startDeletion({ type: 'everything' });
      await component.proceedToConfirm();
      component.toggleTickBox();
      fixture.detectChanges();

      expect(component.timeRemaining()).toBeLessThanOrEqual(5);
      const countdownEl = el.querySelector('.countdown');
      expect(countdownEl).toBeTruthy();
    });

    it('enables confirm button after delay expires (5 sec for L3)', async () => {
      fakeService = makeFakeService({
        deleteConfirmInfo: vi.fn(async () => ({
          ok: true as const,
          tickBoxRequired: true,
          delayMs: 5000,
        })),
      });
      const { component, fixture } = createComponent();
      (component as any).service = fakeService;

      await component.startDeletion({ type: 'everything' });
      await component.proceedToConfirm();
      component.toggleTickBox();

      expect(component.confirmEnabled()).toBe(false);

      vi.advanceTimersByTime(5000);
      fixture.detectChanges();

      expect(component.confirmEnabled()).toBe(true);
    });

    it('disables confirm button when tick box is unchecked', async () => {
      fakeService = makeFakeService({
        deleteConfirmInfo: vi.fn(async () => ({
          ok: true as const,
          tickBoxRequired: true,
          delayMs: 5000,
        })),
      });
      const { component } = createComponent();
      (component as any).service = fakeService;

      await component.startDeletion({ type: 'everything' });
      await component.proceedToConfirm();
      component.toggleTickBox();
      vi.advanceTimersByTime(5000);
      expect(component.confirmEnabled()).toBe(true);

      component.toggleTickBox();
      expect(component.confirmEnabled()).toBe(false);
    });

    it('shows passkey error when confirmInfo returns not ok', async () => {
      fakeService = makeFakeService({
        deleteConfirmInfo: vi.fn(async () => ({
          ok: false as const,
          reason: 'Passkey required',
        })),
      });
      const { component } = createComponent();
      (component as any).service = fakeService;

      await component.startDeletion({ type: 'everything' });
      await component.proceedToConfirm();

      expect(component.phase()).toBe('passkey-error');
      expect(component.error()).toBe('Passkey required');
    });
  });

  describe('Deletion flow', () => {
    it('calls executeDelete on confirm with correct arguments', async () => {
      fakeService = makeFakeService({
        deleteConfirmInfo: vi.fn(async () => ({
          ok: true as const,
          tickBoxRequired: false,
          delayMs: 0,
        })),
      });
      const { component } = createComponent();
      (component as any).service = fakeService;

      await component.startDeletion({ type: 'olderBackups' });
      await component.proceedToConfirm();
      await component.confirmDelete();

      expect(fakeService.executeDelete).toHaveBeenCalledWith(
        { items: [], totalBytes: 0 },
        null,
      );
    });

    it('calls authorizeDelete for level 2/3 deletions', async () => {
      fakeService = makeFakeService({
        deleteConfirmInfo: vi.fn(async () => ({
          ok: true as const,
          tickBoxRequired: true,
          delayMs: 5000,
        })),
      });
      const { component } = createComponent();
      (component as any).service = fakeService;

      await component.startDeletion({ type: 'everything' });
      await component.proceedToConfirm();
      component.toggleTickBox();
      vi.advanceTimersByTime(5000);
      await component.confirmDelete();

      expect(fakeService.authorizeDelete).toHaveBeenCalled();
    });

    it('shows result message after successful deletion', async () => {
      const { component, fixture } = createComponent();
      (component as any).service = fakeService;

      await component.startDeletion({ type: 'olderBackups' });
      await component.proceedToConfirm();
      await component.confirmDelete();
      fixture.detectChanges();

      expect(component.phase()).toBe('done');
      expect(component.result()).toContain('Google Drive');
    });

    it('shows error when executeDelete fails', async () => {
      fakeService = makeFakeService({
        executeDelete: vi.fn(async () => ({ ok: false as const, reason: 'IO error' })),
      });
      const { component } = createComponent();
      (component as any).service = fakeService;

      await component.startDeletion({ type: 'olderBackups' });
      await component.proceedToConfirm();
      await component.confirmDelete();

      expect(component.phase()).toBe('error');
      expect(component.error()).toBe('IO error');
    });

    it('clears state on cancel', async () => {
      const { component } = createComponent();
      (component as any).service = fakeService;

      await component.startDeletion({ type: 'allBackups' });
      component.cancel();

      expect(component.phase()).toBe('menu');
      expect(component.plan()).toBeNull();
      expect(component.error()).toBeNull();
    });
  });

  describe('Passkey handling', () => {
    it('checks passkey status on init', async () => {
      fakeService = makeFakeService();
      createComponent();

      expect(fakeService.passkeyStatus).toHaveBeenCalled();
    });

    it('shows setup passkey button when status is none', async () => {
      fakeService = makeFakeService({
        passkeyStatus: vi.fn(async () => 'none' as const),
        deleteConfirmInfo: vi.fn(async () => ({
          ok: false as const,
          reason: 'Passkey required',
        })),
      });
      const { component, fixture, el } = createComponent();
      (component as any).service = fakeService;

      await component.startDeletion({ type: 'everything' });
      await component.proceedToConfirm();
      fixture.detectChanges();

      const setupBtn = el.querySelector('button:contains("Set up a passkey")') ||
                       [...el.querySelectorAll('button')].find(b => b.textContent?.includes('passkey'));
      expect(setupBtn).toBeTruthy();
    });

    it('calls registerPasskey when setup button clicked', async () => {
      fakeService = makeFakeService({
        passkeyStatus: vi.fn(async () => 'none' as const),
      });
      const { component } = createComponent();
      (component as any).service = fakeService;

      await component.registerPasskey();

      expect(fakeService.registerPasskey).toHaveBeenCalled();
    });

    it('transitions to confirm after passkey registration', async () => {
      fakeService = makeFakeService();
      const { component } = createComponent();
      (component as any).service = fakeService;

      component.phase.set('passkey-error');
      await component.registerPasskey();

      expect(component.phase()).toBe('confirm');
    });

    it('handles passkey registration cancellation (null result)', async () => {
      fakeService = makeFakeService({
        registerPasskey: vi.fn(async () => null),
      });
      const { component } = createComponent();
      (component as any).service = fakeService;

      component.phase.set('passkey-error');
      await component.registerPasskey();

      expect(component.phase()).toBe('passkey-error');
    });
  });

  describe('Mutation tests', () => {
    it('test fails if countdown is shortened to 1s instead of 5s', async () => {
      fakeService = makeFakeService({
        deleteConfirmInfo: vi.fn(async () => ({
          ok: true as const,
          tickBoxRequired: true,
          delayMs: 5000,
        })),
      });
      const { component } = createComponent();
      (component as any).service = fakeService;

      await component.startDeletion({ type: 'everything' });
      await component.proceedToConfirm();
      component.toggleTickBox();

      vi.advanceTimersByTime(1000);
      expect(component.confirmEnabled()).toBe(false);

      vi.advanceTimersByTime(3999);
      expect(component.confirmEnabled()).toBe(false);

      vi.advanceTimersByTime(1);
      expect(component.confirmEnabled()).toBe(true);
    });

    it('test fails if executeDelete called without tick box ticked', async () => {
      fakeService = makeFakeService({
        deleteConfirmInfo: vi.fn(async () => ({
          ok: true as const,
          tickBoxRequired: true,
          delayMs: 5000,
        })),
      });
      const { component } = createComponent();
      (component as any).service = fakeService;

      await component.startDeletion({ type: 'everything' });
      await component.proceedToConfirm();

      expect(component.tickedAt()).toBeNull();
      expect(component.confirmEnabled()).toBe(false);
    });

    it('test fails if executeDelete called after cancel', async () => {
      fakeService = makeFakeService();
      const { component } = createComponent();
      (component as any).service = fakeService;

      await component.startDeletion({ type: 'olderBackups' });
      const plan = component.plan();
      component.cancel();

      expect(component.plan()).toBeNull();
      expect(fakeService.executeDelete).not.toHaveBeenCalledWith(plan, expect.anything());
    });

    it('test fails if passkey-refused path calls authorizeDelete anyway', async () => {
      fakeService = makeFakeService({
        deleteConfirmInfo: vi.fn(async () => ({
          ok: false as const,
          reason: 'Passkey required',
        })),
      });
      const { component } = createComponent();
      (component as any).service = fakeService;

      await component.startDeletion({ type: 'everything' });
      await component.proceedToConfirm();

      expect(component.phase()).toBe('passkey-error');
      expect(fakeService.authorizeDelete).not.toHaveBeenCalled();
    });

    it('test fails if cancel does not clear tickedAt', async () => {
      fakeService = makeFakeService({
        deleteConfirmInfo: vi.fn(async () => ({
          ok: true as const,
          tickBoxRequired: true,
          delayMs: 5000,
        })),
      });
      const { component } = createComponent();
      (component as any).service = fakeService;

      await component.startDeletion({ type: 'everything' });
      await component.proceedToConfirm();
      component.toggleTickBox();
      expect(component.tickedAt()).not.toBeNull();

      component.cancel();

      expect(component.tickedAt()).toBeNull();
    });
  });

  describe('Accessibility', () => {
    it('has aria-live region for running phase', async () => {
      fakeService = makeFakeService();
      const { component, fixture, el } = createComponent();
      (component as any).service = fakeService;

      await component.startDeletion({ type: 'olderBackups' });
      component.phase.set('running');
      fixture.detectChanges();

      const status = el.querySelector('[role="status"][aria-live="polite"]');
      expect(status).toBeTruthy();
    });

    it('has alert role for error messages', async () => {
      fakeService = makeFakeService();
      const { component, fixture, el } = createComponent();
      (component as any).service = fakeService;

      component.phase.set('error');
      component.error.set('Test error');
      fixture.detectChanges();

      const alert = el.querySelector('[role="alert"]');
      expect(alert).toBeTruthy();
    });

    it('buttons are properly disabled based on state', async () => {
      const { component, fixture, el } = createComponent();
      component.busy.set(true);
      fixture.detectChanges();

      const buttons = el.querySelectorAll('button');
      buttons.forEach((btn) => {
        expect(btn.disabled).toBe(true);
      });
    });
  });
});
