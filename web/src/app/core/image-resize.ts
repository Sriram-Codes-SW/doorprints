/** Thrown by resizeImage; the reason maps to a translated message in errorMsg(). */
export class ImageResizeError extends Error {
  constructor(readonly reason: 'canvas' | 'encode' | 'read') {
    super(`Image resize failed: ${reason}`);
    this.name = 'ImageResizeError';
  }
}

/** Downscales an image so its longest side is at most `maxSize` px and re-encodes it as JPEG. */
export async function resizeImage(file: Blob, maxSize = 1600, quality = 0.8): Promise<Blob> {
  const bitmap = await loadImage(file);
  try {
    const scale = Math.min(1, maxSize / Math.max(bitmap.width, bitmap.height));
    const width = Math.max(1, Math.round(bitmap.width * scale));
    const height = Math.max(1, Math.round(bitmap.height * scale));
    const canvas = document.createElement('canvas');
    canvas.width = width;
    canvas.height = height;
    const ctx = canvas.getContext('2d');
    if (!ctx) throw new ImageResizeError('canvas');
    ctx.fillStyle = '#fff';
    ctx.fillRect(0, 0, width, height);
    ctx.drawImage(bitmap.source, 0, 0, width, height);
    return await new Promise<Blob>((resolve, reject) =>
      canvas.toBlob((b) => (b ? resolve(b) : reject(new ImageResizeError('encode'))), 'image/jpeg', quality),
    );
  } finally {
    bitmap.dispose();
  }
}

interface LoadedImage {
  source: CanvasImageSource;
  width: number;
  height: number;
  dispose: () => void;
}

async function loadImage(file: Blob): Promise<LoadedImage> {
  if (typeof createImageBitmap === 'function') {
    try {
      // Respects EXIF orientation in modern browsers.
      const bmp = await createImageBitmap(file, { imageOrientation: 'from-image' });
      return { source: bmp, width: bmp.width, height: bmp.height, dispose: () => bmp.close() };
    } catch {
      // fall back to <img>
    }
  }
  const url = URL.createObjectURL(file);
  try {
    const img = new Image();
    img.decoding = 'async';
    img.src = url;
    await img.decode();
    return { source: img, width: img.naturalWidth, height: img.naturalHeight, dispose: () => URL.revokeObjectURL(url) };
  } catch {
    URL.revokeObjectURL(url);
    throw new ImageResizeError('read');
  }
}
