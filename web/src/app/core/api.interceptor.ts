import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { ConfigService } from './config.service';

/**
 * Only touches requests for our own API (relative URLs starting with /api): prefixes the configured
 * base URL and adds the X-API-Key header. Third-party requests (e.g. Nominatim) pass through untouched.
 */
export const apiInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith('/api/') && req.url !== '/api') {
    return next(req);
  }
  const config = inject(ConfigService).config();
  if (!config) {
    return next(req);
  }
  return next(
    req.clone({
      url: config.baseUrl + req.url,
      setHeaders: { 'X-API-Key': config.apiKey },
    }),
  );
};
