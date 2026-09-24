/**
 * A `.geojson` file imported with `with { loader: 'text' }` (Angular's esbuild text loader): its content as a string.
 * Used for the held areas' polygon (src/app/shared/india-boundaries.ts, S4b-BL-12).
 */
declare module '*.geojson' {
  const text: string;
  export default text;
}
