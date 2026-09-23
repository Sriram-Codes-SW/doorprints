import { Injectable, signal } from '@angular/core';

/**
 * Whether the screen on show holds edits that are not saved yet, for the code that is **not** that screen.
 *
 * The router's `canDeactivate` guard already protects an in-app navigation away from a half-typed house. A reload
 * does not go through the router, so the "new version, Reload" banner (PwaService.applyUpdate) asks here first —
 * docs/05 §14.4 names "a reload in the middle of typing a note loses the note" as the reason updates need consent.
 *
 * `HouseDetailPage` is the only writer: it mirrors its own `dirty()` into {@link dirty} and registers its save
 * action so the update prompt can offer "Save first". It releases both when it is destroyed.
 */
@Injectable({ providedIn: 'root' })
export class UnsavedChanges {
  /** True while the current screen has edits that a reload would throw away. */
  readonly dirty = signal(false);

  /**
   * Set just before a reload the user has already agreed to, so the page's own `beforeunload` warning does not
   * ask the same question a second time in the browser's words.
   */
  leaving = false;

  private saver: (() => Promise<boolean>) | null = null;
  private owner: object | null = null;

  /** The screen now on show registers its save action (resolves true once saved). */
  register(owner: object, saver: () => Promise<boolean>): void {
    this.owner = owner;
    this.saver = saver;
  }

  /**
   * The screen is going away. Only its own registration is removed: when one house page replaces another, the
   * new one may already have registered, and its state must survive the old one's cleanup.
   */
  release(owner: object): void {
    if (this.owner !== owner) return;
    this.owner = null;
    this.saver = null;
    this.dirty.set(false);
  }

  /** True when "Save first" can be offered. */
  get canSave(): boolean {
    return this.saver !== null;
  }

  /** Runs the registered save; false when there is none or it did not succeed. */
  async save(): Promise<boolean> {
    const saver = this.saver;
    if (!saver) return false;
    try {
      return await saver();
    } catch {
      return false;
    }
  }
}
