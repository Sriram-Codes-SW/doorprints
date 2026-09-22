import { HttpEvent, HttpHandlerFn, HttpRequest, HttpResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, of } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';
import { apiInterceptor } from './api.interceptor';
import { ApiConfig, ConfigService } from './config.service';

describe('apiInterceptor', () => {
  const config = signal<ApiConfig | null>(null);
  /** Requests the interceptor handed on to the next handler. */
  const forwarded: HttpRequest<unknown>[] = [];

  const next: HttpHandlerFn = (req: HttpRequest<unknown>): Observable<HttpEvent<unknown>> => {
    forwarded.push(req);
    return of(new HttpResponse<unknown>({ status: 200 }));
  };

  function run(req: HttpRequest<unknown>): HttpRequest<unknown> {
    forwarded.length = 0;
    TestBed.runInInjectionContext(() => apiInterceptor(req, next)).subscribe();
    expect(forwarded.length).toBe(1);
    return forwarded[0];
  }

  function intercept(url: string): HttpRequest<unknown> {
    return run(new HttpRequest<unknown>('GET', url));
  }

  beforeEach(() => {
    config.set({ baseUrl: 'https://api.example.org', apiKey: 'secret-key' });
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [{ provide: ConfigService, useValue: { config: config.asReadonly() } }],
    });
  });

  it.each(['/api/houses', '/api/houses/1/photos?x=1', '/api'])('prefixes the base URL and adds the key for %s', (url) => {
    const req = intercept(url);
    expect(req.url).toBe('https://api.example.org' + url);
    expect(req.headers.get('X-API-Key')).toBe('secret-key');
  });

  it.each([
    'https://nominatim.openstreetmap.org/reverse?lat=1&lon=2',
    'https://tiles.openfreemap.org/styles/liberty',
    'https://evil.example/api/houses',
    '/apix/houses',
    '/apiary',
    'api/houses',
    '/assets/api/x.json',
  ])('leaves %s untouched (no key leak)', (url) => {
    const req = intercept(url);
    expect(req.url).toBe(url);
    expect(req.headers.has('X-API-Key')).toBe(false);
  });

  it('passes /api requests through unchanged when not configured', () => {
    config.set(null);
    const req = intercept('/api/houses');
    expect(req.url).toBe('/api/houses');
    expect(req.headers.has('X-API-Key')).toBe(false);
  });

  it('keeps method, body and existing headers', () => {
    const original = new HttpRequest<unknown>('POST', '/api/houses', { a: 1 }).clone({ setHeaders: { 'Content-Type': 'application/json' } });
    const req = run(original);
    expect(req.method).toBe('POST');
    expect(req.body).toEqual({ a: 1 });
    expect(req.headers.get('Content-Type')).toBe('application/json');
    expect(req.headers.get('X-API-Key')).toBe('secret-key');
    expect(req.url).toBe('https://api.example.org/api/houses');
  });
});
