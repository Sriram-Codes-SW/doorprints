import { Injectable, signal } from '@angular/core';
import type { Msg } from '../i18n/translation.service';

/**
 * Feeds the app-wide polite live region (rendered once in the App shell) so screen readers hear
 * results like "Saved" or "Photo uploaded" without moving focus. Errors use role="alert" in place.
 */
@Injectable({ providedIn: 'root' })
export class Announcer {
  private readonly current = signal<Msg | null>(null);
  readonly message = this.current.asReadonly();
  /** The message waiting for its 100 ms, so {@link cancel} can tell whose it is. */
  private pending: Msg | null = null;
  private timer: ReturnType<typeof setTimeout> | undefined;

  announce(message: Msg): void {
    // Clear first so repeating the same message is announced again.
    this.current.set(null);
    clearTimeout(this.timer);
    this.pending = message;
    this.timer = setTimeout(() => {
      this.pending = null;
      this.timer = undefined;
      this.current.set(message);
    }, 100);
  }

  /**
   * Withdraws a message that says something is still running ("Saving…", "Preparing photos 3 of 10…") when that
   * work ends without a result of its own to announce: a failure (whose role="alert" must be the last thing heard,
   * not followed by a late "Saving…"), a cancel, or the page going away. Pass the message you queued: only a
   * pending or shown message with that same key is dropped, so a caller never withdraws another feature's
   * announcement (the region is app-wide; a "Photo uploaded" queued after a "Saving…" survives the save's failure).
   * Emptying the region is not itself announced.
   */
  cancel(message: Msg): void {
    if (this.pending !== null && this.pending.key === message.key) {
      clearTimeout(this.timer);
      this.timer = undefined;
      this.pending = null;
    }
    if (this.current()?.key === message.key) this.current.set(null);
  }
}
