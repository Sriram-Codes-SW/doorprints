/**
 * 24px Material Icons shapes (Apache-2.0) for empty states, drawn with `currentColor` inside `.empty-glyph` (a soft
 * circle, styles.css). The same iconography as the navigation bar, so an empty screen never falls back to a platform
 * emoji that looks different on every OS and clashes with the dark theme.
 */
export const GLYPHS = {
  /** Material "home": no house yet, or a house that is not in this browser. */
  home: 'M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z',
  /** Material "search": nothing matches the search or filter. */
  search:
    'M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z',
  /** Material "compare_arrows", as in the navigation bar: fewer than two houses to compare. */
  compare: 'M9.01 14H2v2h7.01v3L13 15l-3.99-4v3zm5.98-1v-3H22V8h-7.01V5L11 9l3.99 4z',
  /** Material "cloud_download": the houses are on the server, not in this browser yet. */
  cloudDownload:
    'M19.35 10.04C18.67 6.59 15.64 4 12 4 9.11 4 6.6 5.64 5.35 8.04 2.34 8.36 0 10.91 0 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96zM17 13l-5 5-5-5h3V9h4v4h3z',
} as const;
