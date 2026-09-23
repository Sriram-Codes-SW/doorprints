import { describe, expect, it } from 'vitest';
import { crc32, utf8, zip } from './zip';
import { sha256Hex } from './sha256';
import { columnName, excelSerial, sheetName } from './xlsx-export';

const WHEN = new Date('2026-09-22T10:15:30.000Z');

/** Reads the little-endian 32-bit value at `at`. */
function u32(bytes: Uint8Array, at: number): number {
  return new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength).getUint32(at, true);
}

function u16(bytes: Uint8Array, at: number): number {
  return new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength).getUint16(at, true);
}

describe('crc32', () => {
  it('matches the published check values', () => {
    expect(crc32(utf8(''))).toBe(0);
    // The standard CRC-32 check value for "123456789".
    expect(crc32(utf8('123456789'))).toBe(0xcbf43926);
    expect(crc32(utf8('a'))).toBe(0xe8b7be43);
  });
});

describe('sha256Hex', () => {
  it('matches the FIPS 180-4 test vectors', () => {
    expect(sha256Hex(utf8(''))).toBe('e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855');
    expect(sha256Hex(utf8('abc'))).toBe('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
    expect(sha256Hex(utf8('abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq'))).toBe(
      '248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1',
    );
  });

  it('handles a message that spans several blocks', () => {
    expect(sha256Hex(utf8('a'.repeat(1000)))).toBe(
      '41edece42d63e8d9bf515a9ba6932e1c20cbc9f5a5d134645adb5db1b9737ea3',
    );
  });
});

describe('zip', () => {
  const entries = [
    { path: 'a.txt', data: utf8('hello') },
    { path: 'dir/b.txt', data: utf8('सर्दी') },
  ];
  const bytes = zip(entries, WHEN);

  it('writes a local header, a central directory and an end record', () => {
    expect(u32(bytes, 0)).toBe(0x04034b50);
    expect(u32(bytes, bytes.length - 22)).toBe(0x06054b50);
    expect(u16(bytes, bytes.length - 22 + 8)).toBe(2); // entries on this disk
    expect(u16(bytes, bytes.length - 22 + 10)).toBe(2); // entries in total
  });

  it('stores entries uncompressed, with the UTF-8 name flag', () => {
    expect(u16(bytes, 8)).toBe(0); // compression method 0 = stored
    expect(u16(bytes, 6)).toBe(0x0800); // bit 11: UTF-8 file name
    expect(u32(bytes, 18)).toBe(5); // compressed size of "hello"
    expect(u32(bytes, 22)).toBe(5); // uncompressed size
    expect(u32(bytes, 14)).toBe(crc32(utf8('hello')));
  });

  it('is byte-for-byte reproducible for the same entries and time', () => {
    expect(Array.from(zip(entries, WHEN))).toEqual(Array.from(bytes));
  });

  it('writes an empty archive as just the end record', () => {
    expect(zip([], WHEN).length).toBe(22);
  });
});

describe('xlsx helpers', () => {
  it('names columns like a spreadsheet', () => {
    expect(columnName(1)).toBe('A');
    expect(columnName(26)).toBe('Z');
    expect(columnName(27)).toBe('AA');
    expect(columnName(52)).toBe('AZ');
    expect(columnName(53)).toBe('BA');
  });

  it('converts dates to the serial numbers Excel uses', () => {
    expect(excelSerial(new Date('1970-01-01T00:00:00.000Z'))).toBe('25569');
    expect(excelSerial(new Date('2026-09-22T00:00:00.000Z'))).toBe('46287');
  });

  it('removes characters Excel rejects in a sheet name and truncates at 31', () => {
    expect(sheetName('a[b]c:d*e?f/g\\h')).toBe('a_b_c_d_e_f_g_h');
    expect(sheetName('x'.repeat(40))).toHaveLength(31);
  });
});
