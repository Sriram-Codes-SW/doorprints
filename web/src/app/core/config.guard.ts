import { CanActivateFn, Router } from '@angular/router';
import { inject } from '@angular/core';
import { ConfigService } from './config.service';

export const configGuard: CanActivateFn = () => {
  const config = inject(ConfigService);
  return config.configured() ? true : inject(Router).createUrlTree(['/connect']);
};
