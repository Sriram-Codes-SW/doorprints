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
