import type { FieldKind, FieldStanding } from "../../api/declaration";
import type { MessageId } from "../../i18n/app";
import type { FileSizeUnit } from "./declarationDrafts";

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
