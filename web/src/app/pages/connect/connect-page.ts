import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { ConfigService, DEFAULT_BASE_URL, normalizeBaseUrl } from '../../core/config.service';
import { HouseApiService } from '../../core/house-api.service';
import { StatsDto } from '../../core/models';
import { errorMsg } from '../../core/format';
import { Announcer } from '../../core/announcer.service';
import { Msg } from '../../i18n/translation.service';
import { ConfirmService } from '../../core/confirm.service';
import { AiService } from '../../core/ai.service';
import { TPipe } from '../../i18n/t.pipe';

@Component({
  selector: 'app-connect-page',
  imports: [FormsModule, TPipe],
  templateUrl: './connect-page.html',
  styleUrl: './connect-page.css',
})
export class ConnectPage {
  private readonly config = inject(ConfigService);
  private readonly api = inject(HouseApiService);
  private readonly router = inject(Router);
  private readonly confirm = inject(ConfirmService);
  private readonly announcer = inject(Announcer);
  private readonly ai = inject(AiService);

  protected baseUrl = this.config.config()?.baseUrl ?? DEFAULT_BASE_URL;
  protected apiKey = this.config.config()?.apiKey ?? '';
  /** Keep the key after the browser closes. Off by default for a new connection (safer on shared computers). */
  protected remember = this.config.configured() ? this.config.remembered() : false;
  protected readonly showKey = signal(false);
  protected readonly testing = signal(false);
  protected readonly testResult = signal<StatsDto | null>(null);
  protected readonly error = signal<Msg | null>(null);
  protected readonly configured = this.config.configured;
  protected readonly isHttpsPage = typeof location !== 'undefined' && location.protocol === 'https:';

  protected get canSubmit(): boolean {
    return !!this.baseUrl.trim() && !!this.apiKey.trim();
  }

  protected get mixedContent(): boolean {
    return this.isHttpsPage && this.baseUrl.trim().toLowerCase().startsWith('http:');
  }

  protected test(): void {
    if (!this.canSubmit || this.testing()) return;
    this.testing.set(true);
    this.testResult.set(null);
    this.error.set(null);
    this.api.testConnection(normalizeBaseUrl(this.baseUrl), this.apiKey.trim()).subscribe({
      next: (stats) => {
        this.testResult.set(stats);
        this.testing.set(false);
      },
      error: (err: unknown) => {
        this.error.set(errorMsg(err));
        this.testing.set(false);
      },
    });
  }

  protected save(): void {
    if (!this.canSubmit) return;
    this.config.save({ baseUrl: this.baseUrl, apiKey: this.apiKey }, this.remember);
    this.ai.refresh();
    void this.router.navigate(['/']);
  }

  protected async disconnect(): Promise<void> {
    const ok = await this.confirm.ask({ key: 'confirm.disconnect' }, { confirmKey: 'connect.disconnect', danger: true });
    if (!ok) return;
    this.config.clear();
    this.ai.refresh();
    this.baseUrl = DEFAULT_BASE_URL;
    this.apiKey = '';
    this.testResult.set(null);
    this.error.set(null);
    this.announcer.announce({ key: 'connect.disconnected' });
  }
}
