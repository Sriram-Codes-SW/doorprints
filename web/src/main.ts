import { bootstrapApplication } from '@angular/platform-browser';
import { App } from './app/app';
import { appConfig } from './app/app.config';
import { startUnlessFramed } from './app/core/frame-guard';
import { migrateLegacyStorage } from './app/core/storage-keys';
import { initialLang } from './app/i18n/translation.service';

// Before anything reads storage: move keys saved under the pre-rename names (house-hunt.*, hh.*) to doorprints.*.
migrateLegacyStorage();

// Inside another site's frame the app does not start; it shows a translated "open in its own tab" message instead
// (defence in depth behind web/firebase.json's frame-ancestors / X-Frame-Options, see core/frame-guard.ts).
startUnlessFramed({ win: window, doc: document, href: location.href, lang: initialLang }, () => {
  bootstrapApplication(App, appConfig).catch((err: unknown) => console.error(err));
});
