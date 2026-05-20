import { inflateRawSync } from "node:zlib";
import type { ParsedTable } from "./types";

interface ZipEntry {
  name: string;
  compression: number;
  compressedSize: number;
  uncompressedSize: number;
  localHeaderOffset: number;
}

function readUInt16(buffer: Buffer, offset: number) {
  return buffer.readUInt16LE(offset);
}

function readUInt32(buffer: Buffer, offset: number) {
  return buffer.readUInt32LE(offset);
}

function findEndOfCentralDirectory(buffer: Buffer) {
  for (let i = buffer.length - 22; i >= Math.max(0, buffer.length - 65558); i--) {
    if (readUInt32(buffer, i) === 0x06054b50) return i;
  }
  throw new Error("Invalid XLSX file: ZIP directory not found");
}

function listZipEntries(buffer: Buffer): ZipEntry[] {
  const eocd = findEndOfCentralDirectory(buffer);
  const entryCount = readUInt16(buffer, eocd + 10);
  const centralDirectoryOffset = readUInt32(buffer, eocd + 16);
  const entries: ZipEntry[] = [];
  let offset = centralDirectoryOffset;

  for (let i = 0; i < entryCount; i++) {
    if (readUInt32(buffer, offset) !== 0x02014b50) {
      throw new Error("Invalid XLSX file: corrupt ZIP directory");
    }

    const compression = readUInt16(buffer, offset + 10);
    const compressedSize = readUInt32(buffer, offset + 20);
    const uncompressedSize = readUInt32(buffer, offset + 24);
    const nameLength = readUInt16(buffer, offset + 28);
    const extraLength = readUInt16(buffer, offset + 30);
    const commentLength = readUInt16(buffer, offset + 32);
    const localHeaderOffset = readUInt32(buffer, offset + 42);
    const name = buffer
      .subarray(offset + 46, offset + 46 + nameLength)
      .toString("utf8");

    entries.push({
      name,
      compression,
      compressedSize,
      uncompressedSize,
      localHeaderOffset,
    });

    offset += 46 + nameLength + extraLength + commentLength;
  }

  return entries;
}

function readZipEntry(buffer: Buffer, entry: ZipEntry): Buffer {
  const offset = entry.localHeaderOffset;
  if (readUInt32(buffer, offset) !== 0x04034b50) {
    throw new Error(`Invalid XLSX file: corrupt ZIP entry ${entry.name}`);
  }

  const nameLength = readUInt16(buffer, offset + 26);
  const extraLength = readUInt16(buffer, offset + 28);
  const dataStart = offset + 30 + nameLength + extraLength;
  const compressed = buffer.subarray(dataStart, dataStart + entry.compressedSize);

  if (entry.compression === 0) return compressed;
  if (entry.compression === 8) return inflateRawSync(compressed);

  throw new Error(`Unsupported XLSX compression method ${entry.compression}`);
}

function unzip(buffer: Buffer): Map<string, string> {
  const files = new Map<string, string>();
  for (const entry of listZipEntries(buffer)) {
    if (entry.uncompressedSize > 20 * 1024 * 1024) {
      throw new Error(`XLSX entry is too large: ${entry.name}`);
    }
    files.set(entry.name, readZipEntry(buffer, entry).toString("utf8"));
  }
  return files;
}

function decodeXml(value: string) {
  return value
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&amp;/g, "&")
    .replace(/&quot;/g, "\"")
    .replace(/&apos;/g, "'");
}

function getAttr(xml: string, attr: string) {
  const match = new RegExp(`\\b${attr}="([^"]*)"`, "i").exec(xml);
  return match ? decodeXml(match[1]) : null;
}

function stripTags(xml: string) {
  return decodeXml(xml.replace(/<[^>]*>/g, ""));
}

function parseSharedStrings(xml: string | undefined) {
  if (!xml) return [];
  const values: string[] = [];
  for (const match of xml.matchAll(/<si\b[^>]*>([\s\S]*?)<\/si>/g)) {
    const textParts = [...match[1].matchAll(/<t\b[^>]*>([\s\S]*?)<\/t>/g)].map(
      (part) => decodeXml(part[1])
    );
    values.push(textParts.length > 0 ? textParts.join("") : stripTags(match[1]));
  }
  return values;
}

function columnIndexFromCellRef(ref: string) {
  const letters = /^[A-Z]+/i.exec(ref)?.[0] ?? "A";
  let index = 0;
  for (const letter of letters.toUpperCase()) {
    index = index * 26 + letter.charCodeAt(0) - 64;
  }
  return index - 1;
}

function normalizeTarget(target: string) {
  if (target.startsWith("/")) return target.slice(1);
  if (target.startsWith("xl/")) return target;
  return `xl/${target}`;
}

function parseWorkbook(files: Map<string, string>) {
  const workbook = files.get("xl/workbook.xml");
  const rels = files.get("xl/_rels/workbook.xml.rels");
  if (!workbook || !rels) throw new Error("Invalid XLSX file: workbook missing");

  const relMap = new Map<string, string>();
  for (const match of rels.matchAll(/<Relationship\b([^>]*)\/?>/g)) {
    const attrs = match[1];
    const id = getAttr(attrs, "Id");
    const target = getAttr(attrs, "Target");
    if (id && target) relMap.set(id, normalizeTarget(target));
  }

  const sheets: { name: string; path: string }[] = [];
  for (const match of workbook.matchAll(/<sheet\b([^>]*)\/?>/g)) {
    const attrs = match[1];
    const name = getAttr(attrs, "name") ?? "Sheet";
    const relationshipId = getAttr(attrs, "r:id");
    const path = relationshipId ? relMap.get(relationshipId) : null;
    if (path) sheets.push({ name, path });
  }

  return sheets;
}

function parseSheet(xml: string, sharedStrings: string[]) {
  const rows: string[][] = [];

  for (const rowMatch of xml.matchAll(/<row\b([^>]*)>([\s\S]*?)<\/row>/g)) {
    const rowAttrs = rowMatch[1];
    const rowXml = rowMatch[2];
    const rowNumber = Number(getAttr(rowAttrs, "r") ?? rows.length + 1);
    const row: string[] = rows[rowNumber - 1] ?? [];

    for (const cellMatch of rowXml.matchAll(/<c\b([^>]*)>([\s\S]*?)<\/c>/g)) {
      const attrs = cellMatch[1];
      const cellXml = cellMatch[2];
      const ref = getAttr(attrs, "r") ?? "A1";
      const type = getAttr(attrs, "t");
      const valueMatch = /<v\b[^>]*>([\s\S]*?)<\/v>/.exec(cellXml);
      const inlineMatch = /<is\b[^>]*>([\s\S]*?)<\/is>/.exec(cellXml);
      let value = valueMatch ? decodeXml(valueMatch[1]) : "";

      if (type === "s") {
        value = sharedStrings[Number(value)] ?? value;
      } else if (type === "inlineStr" && inlineMatch) {
        value = stripTags(inlineMatch[1]);
      }

      row[columnIndexFromCellRef(ref)] = value.trim();
    }

    rows[rowNumber - 1] = row;
  }

  return rows
    .map((row) => {
      const lastValueIndex = row.reduce(
        (last, value, index) => (value ? index : last),
        -1
      );
      return lastValueIndex >= 0 ? row.slice(0, lastValueIndex + 1) : [];
    })
    .filter((row) => row.some((value) => value.trim()));
}

export function parseXlsx(buffer: Buffer): ParsedTable[] {
  const files = unzip(buffer);
  const sharedStrings = parseSharedStrings(files.get("xl/sharedStrings.xml"));
  const sheets = parseWorkbook(files);

  return sheets
    .map((sheet) => ({
      kind: "xlsx" as const,
      sheetName: sheet.name,
      rows: parseSheet(files.get(sheet.path) ?? "", sharedStrings),
    }))
    .filter((sheet) => sheet.rows.length > 0);
}
