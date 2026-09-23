import { DICTIONARIES, Lang } from '../i18n/languages';

/**
 * Doorprints refuses to run inside a frame (clickjacking defence, threat model F-10 / SEC-012, docs/02 RR-11).
 *
 * The real protection is the host's response headers: `web/firebase.json` sends `X-Frame-Options: DENY` and
 * `Content-Security-Policy: frame-ancestors 'none'`, and Firebase Hosting (the live host) applies them, so a
 * browser never even loads the app into another site's frame. This is **defence in depth** for a host that
 * ignores `firebase.json` (a copy of the build on GitHub Pages or any plain static server): a page cannot set
 * `frame-ancestors` for itself — a `<meta>` policy may not carry it — but it can decline to start. So when the
 * page finds itself framed, `main.ts` shows a short translated message with a link that opens the app in its own
 * tab, and **never bootstraps Angular**: no house, no photo, no server key and no button is ever drawn inside
 * someone else's page, which is what a clickjacking overlay needs.
 *
 * Plain DOM on purpose. Nothing here may depend on the app starting, and every text goes in via `textContent`,
 * never as HTML.
 */

/** The two members {@link isFramed} reads; `window` fits, and a test can pass a plain object. */
export interface FrameCheckWindow {
  readonly top: unknown;
  readonly self: unknown;
}

/**
 * True when this document is not the top-level page. Comparing `top` with `self` is allowed across origins (both
 * are WindowProxy references, nothing is read from the other page). If a browser ever throws while doing so,
 * treat it as framed: refusing wrongly costs one click on "Open in a new tab", starting wrongly costs the defence.
 */
export function isFramed(win: FrameCheckWindow): boolean {
  try {
    return win.top !== win.self;
  } catch {
    return true;
  }
}

/**
 * Replaces `<app-root>` (or, failing that, fills `<body>`) with the refusal message in `lang`:
 * heading, one sentence of why, a link opening `href` in a new tab, and `href` itself as copyable text for the
 * case where the framing page's `sandbox` blocks new tabs and the link does nothing. Returns the new element.
 */
export function renderFrameRefusal(doc: Document, lang: Lang, href: string): HTMLElement {
  const dict = DICTIONARIES[lang];
  doc.documentElement.lang = lang;
  doc.title = dict['app.name'];

  const main = doc.createElement('main');
  main.className = 'frame-refusal';

  const heading = doc.createElement('h1');
  heading.textContent = dict['frame.title'];

  const body = doc.createElement('p');
  body.textContent = dict['frame.body'];

  // `_blank`, not `_top`: navigating the framing page away is that page's decision, a new tab is ours.
  // `noopener` so the new tab has no handle on this frame (and so no handle on the page around it).
  const open = doc.createElement('a');
  open.className = 'btn btn-primary';
  open.href = href;
  open.target = '_blank';
  open.rel = 'noopener';
  open.textContent = dict['frame.open'];

  const hint = doc.createElement('p');
  hint.className = 'frame-refusal-hint muted small';
  hint.textContent = dict['frame.copyHint'];

  const address = doc.createElement('p');
  address.className = 'frame-refusal-address small';
  address.setAttribute('translate', 'no');
  address.textContent = href;

  main.append(heading, body, open, hint, address);

  const root = doc.querySelector('app-root');
  if (root) root.replaceWith(main);
  else doc.body.prepend(main);
  return main;
}

/** What {@link startUnlessFramed} needs from the page, passed in so the decision can be unit tested. */
export interface StartEnvironment {
  readonly win: FrameCheckWindow;
  readonly doc: Document;
  /** The address to offer in the new tab: this page's own URL (`location.href`). */
  readonly href: string;
  /** The language for the message; only asked for when the page is framed. */
  readonly lang: () => Lang;
}

/**
 * `main.ts`'s entry point: calls `boot` (which bootstraps Angular) when the page is the top-level document, and
 * otherwise renders the refusal and does **not** call it.
 */
export function startUnlessFramed(env: StartEnvironment, boot: () => void): 'booted' | 'refused' {
  if (isFramed(env.win)) {
    renderFrameRefusal(env.doc, env.lang(), env.href);
    return 'refused';
  }
  boot();
  return 'booted';
}
