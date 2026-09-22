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
  private timer: ReturnType<typeof setTimeout> | undefined;

  announce(message: Msg): void {
    // Clear first so repeating the same message is announced again.
    this.current.set(null);
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.current.set(message), 100);
  }
}
