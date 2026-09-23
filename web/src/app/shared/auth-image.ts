import { Component, OnDestroy, OnInit, inject, input, output, signal } from '@angular/core';
import { Subscription } from 'rxjs';
import { LocalDataService } from '../core/local-data.service';
import { TPipe } from '../i18n/t.pipe';

/**
 * Shows a photo from this browser's local store (IndexedDB). It is read as a Blob and turned into an object URL,
 * so nothing is fetched over the network and the photo shows offline.
 * The image sits inside a real button so the larger view can be opened with a keyboard.
 */
@Component({
  selector: 'app-auth-image',
  imports: [TPipe],
  template: `
    @if (src(); as s) {
      <button type="button" class="open" [attr.aria-label]="'photo.open' | t: { alt: alt() }" (click)="opened.emit(s)">
        <img [src]="s" [alt]="alt()" />
      </button>
    } @else if (failed()) {
      <div class="placeholder" role="img" [attr.aria-label]="'photo.failed' | t">
        <span aria-hidden="true">⚠</span>
      </div>
    } @else {
      <div class="placeholder loading" role="img" [attr.aria-label]="'photo.loading' | t"></div>
    }
  `,
  styles: `
    :host {
      display: block;
      width: 100%;
      height: 100%;
    }
    .open {
      display: block;
      width: 100%;
      height: 100%;
      padding: 0;
      border: 0;
      background: none;
      cursor: zoom-in;
    }
    .open:focus-visible {
      outline-offset: -3px;
    }
    img {
      display: block;
      width: 100%;
      height: 100%;
      object-fit: cover;
    }
    .placeholder {
      width: 100%;
      height: 100%;
      display: grid;
      place-items: center;
      color: var(--muted);
      background: var(--surface-2);
      font-size: 1.5rem;
    }
    .loading {
      background: linear-gradient(90deg, var(--surface-2) 25%, var(--border) 50%, var(--surface-2) 75%);
      background-size: 200% 100%;
      animation: pulse 1.4s ease-in-out infinite;
    }
    @keyframes pulse {
      from {
        background-position: 200% 0;
      }
      to {
        background-position: -200% 0;
      }
    }
  `,
})
export class AuthImage implements OnInit, OnDestroy {
  readonly photoId = input.required<string>();
  /** Required, meaningful alternative text (e.g. "Photo 2 of 2BHK near the park"). */
  readonly alt = input.required<string>();
  readonly opened = output<string>();

  private readonly api = inject(LocalDataService);
  protected readonly src = signal<string | null>(null);
  protected readonly failed = signal(false);
  private sub?: Subscription;
  private objectUrl: string | null = null;

  ngOnInit(): void {
    this.sub = this.api.photo(this.photoId()).subscribe({
      next: (blob) => {
        this.objectUrl = URL.createObjectURL(blob);
        this.src.set(this.objectUrl);
      },
      error: () => this.failed.set(true),
    });
  }

  ngOnDestroy(): void {
    this.sub?.unsubscribe();
    if (this.objectUrl) URL.revokeObjectURL(this.objectUrl);
  }
}
