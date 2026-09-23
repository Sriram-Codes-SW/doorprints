/**
 * Where a photo lives inside an export.
 *
 * Shared with the Android exporter (`ExportPhoto.fileName` and `BackupFormat.photoEntry` in
 * `android/shared/.../export/`): the tables show the bare **file name**, and the backup ZIP stores the bytes under
 * `photos/<file name>`. Photos are always re-encoded as JPEG before they are stored, so the extension is fixed.
 */
export const PHOTO_DIR = 'photos/';

/** `<id>.jpg` — what the CSV, XLSX and Markdown copies print, and what a backup row carries. */
export function photoFileName(photoId: string): string {
  return `${photoId}.jpg`;
}

/** `photos/<id>.jpg` — the entry name inside a backup ZIP. */
export function photoEntry(fileName: string): string {
  return PHOTO_DIR + fileName;
}
