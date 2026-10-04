import type { FieldKind, FieldStanding } from "../../api/declaration";
import { say, type MessageId } from "../../i18n/app";
import { fileSizeOf, type FileSizeUnit } from "./declarationDrafts";
import { intl } from "../../i18n/intl";

// In the catalogue's language, not the reader's, for the reason `joinedInCell` gives.
const PARTS = new Intl.ListFormat(intl.defaultLocale, {
  type: "unit",
  style: "short",
});

/** What a field is and asks, each part already said, run together as one line is scanned. */
export function partsSaid(parts: readonly string[]): string {
  return PARTS.format(parts);
}

/** The words each kind of field is said in, closed over the kinds in both directions, in the order they are offered. */
export const FIELD_KIND_WORDS = {
  text: "fieldKind.text",
  number: "fieldKind.number",
  date: "fieldKind.date",
  moment: "fieldKind.moment",
  yes_no: "fieldKind.yes_no",
  term: "fieldKind.term",
  fields: "fieldKind.fields",
  file: "fieldKind.file",
} as const satisfies Record<FieldKind, MessageId>;

/** The words each standing is said in, closed over the standings in both directions, in the order they are offered. */
export const FIELD_STANDING_WORDS = {
  always: "fieldStanding.always",
  never: "fieldStanding.never",
  above_confidence: "fieldStanding.above_confidence",
} as const satisfies Record<FieldStanding, MessageId>;

export const FILE_SIZE_UNIT_WORDS = {
  bytes: "declaration.sizeUnit.bytes",
  KB: "declaration.sizeUnit.KB",
  MB: "declaration.sizeUnit.MB",
  GB: "declaration.sizeUnit.GB",
} as const satisfies Record<FileSizeUnit, MessageId>;

export function fileSizeSaid(bytes: number): string {
  const { fileSize, fileSizeUnit } = fileSizeOf(bytes);
  return `${fileSize} ${say(FILE_SIZE_UNIT_WORDS[fileSizeUnit])}`;
}
