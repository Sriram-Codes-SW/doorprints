import { describe, expect, it, vi } from 'vitest';
import { DICTIONARIES, LANGUAGES, Lang } from '../i18n/languages';
import { FrameCheckWindow, isFramed, renderFrameRefusal, startUnlessFramed } from './frame-guard';

/** A page like the built index.html: an `<app-root>` and the `<noscript>` after it. */
function page(): Document {
  const doc = document.implementation.createHTMLDocument('Doorprints');
  doc.body.innerHTML = '<app-root></app-root><noscript>Doorprints needs JavaScript.</noscript>';
  return doc;
}

const HREF = 'https://doorprints.web.app/houses/42?tab=visits';

describe('isFramed', () => {
  it('is false for the top-level page', () => {
    const win = {} as { top: unknown; self: unknown };
    win.top = win;
    win.self = win;
    expect(isFramed(win)).toBe(false);
  });

  it('is true when top is another window', () => {
    const self = {};
    expect(isFramed({ top: {}, self })).toBe(true);
  });

  it('is true when reading top throws (fails closed)', () => {
    const win: FrameCheckWindow = {
      get top(): unknown {
        throw new Error('SecurityError');
      },
      self: {},
    };
    expect(isFramed(win)).toBe(true);
  });
});

describe('renderFrameRefusal', () => {
  it.each(LANGUAGES.map((l) => l.code))('shows the message in %s instead of the app', (lang: Lang) => {
    const doc = page();
    const dict = DICTIONARIES[lang];

    const main = renderFrameRefusal(doc, lang, HREF);

    // The app root is gone, so nothing could ever render into it.
    expect(doc.querySelector('app-root')).toBeNull();
    expect(doc.body.firstElementChild).toBe(main);
    expect(doc.documentElement.lang).toBe(lang);
    expect(doc.title).toBe('Doorprints');

    expect(main.querySelector('h1')?.textContent).toBe(dict['frame.title']);
    expect(main.textContent).toContain(dict['frame.body']);
    expect(main.textContent).toContain(dict['frame.copyHint']);

    const link = main.querySelector('a');
    expect(link?.textContent).toBe(dict['frame.open']);
    expect(link?.getAttribute('href')).toBe(HREF);
    expect(link?.getAttribute('target')).toBe('_blank');
    expect(link?.getAttribute('rel')).toBe('noopener');

    const address = main.querySelector('.frame-refusal-address');
    expect(address?.textContent).toBe(HREF);
    expect(address?.getAttribute('translate')).toBe('no');
  });

  it('never parses the address as HTML', () => {
    const doc = page();
    const hostile = 'https://doorprints.web.app/?q=<img src=x onerror=alert(1)>';
    const main = renderFrameRefusal(doc, 'en', hostile);
    expect(main.querySelector('img')).toBeNull();
    expect(main.querySelector('.frame-refusal-address')?.textContent).toBe(hostile);
  });

  it('still shows the message when the page has no <app-root>', () => {
    const doc = document.implementation.createHTMLDocument('Doorprints');
    const main = renderFrameRefusal(doc, 'hi', HREF);
    expect(doc.body.contains(main)).toBe(true);
    expect(main.querySelector('h1')?.textContent).toBe(DICTIONARIES.hi['frame.title']);
  });
});

describe('startUnlessFramed', () => {
  it('boots the app, and leaves the page alone, when it is the top-level page', () => {
    const doc = page();
    const win = {} as { top: unknown; self: unknown };
    win.top = win;
    win.self = win;
    const boot = vi.fn();
    const lang = vi.fn((): Lang => 'ta');

    expect(startUnlessFramed({ win, doc, href: HREF, lang }, boot)).toBe('booted');
    expect(boot).toHaveBeenCalledTimes(1);
    expect(lang).not.toHaveBeenCalled();
    expect(doc.querySelector('app-root')).not.toBeNull();
    expect(doc.querySelector('.frame-refusal')).toBeNull();
  });

  it('does not boot the app inside a frame, and shows the refusal in the chosen language', () => {
    const doc = page();
    const boot = vi.fn();

    expect(startUnlessFramed({ win: { top: {}, self: {} }, doc, href: HREF, lang: () => 'te' }, boot)).toBe('refused');
    expect(boot).not.toHaveBeenCalled();
    expect(doc.querySelector('app-root')).toBeNull();
    expect(doc.querySelector('.frame-refusal h1')?.textContent).toBe(DICTIONARIES.te['frame.title']);
    expect(doc.documentElement.lang).toBe('te');
  });
});
