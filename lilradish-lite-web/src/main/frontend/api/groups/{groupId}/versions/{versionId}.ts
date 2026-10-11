import { get } from "../../../../lib/request/http";
import {
  isUnchecked,
  listOf,
  optional,
  textFrom,
} from "../../../../lib/request/document";
import {
  declaredFrom,
  pinnedFrom,
  termsFrom,
  type DeclaredField,
  type PinnedList,
} from "../../../declaration";

interface Detail {
  readonly version: PinnedList;
  readonly terms: ReadonlyMap<string, readonly string[]>;
}
export type VersionDetail =
  | (Detail & { readonly kind: "reference_list" })
  | (Detail & {
      readonly kind: "workflow";
      readonly takes: readonly DeclaredField[];
      readonly gives: readonly DeclaredField[];
    })
  | (Detail & {
      readonly kind: "question";
      readonly takes: readonly DeclaredField[];
      readonly gives: readonly DeclaredField[];
      readonly searchMatches: boolean;
      readonly searchWay?: string;
    });

/** A version need not be in any candidate page, or in service, to have its existing pin read. */
export function readVersionDetail(
  groupId: string,
  versionId: string,
  signal: AbortSignal,
): Promise<VersionDetail> {
  return get(
    `/api/groups/${encodeURIComponent(groupId)}/versions/${encodeURIComponent(versionId)}`,
    signal,
    detailFrom,
  );
}

export function detailFrom(body: unknown): VersionDetail | null {
  if (!isUnchecked(body)) return null;
  const version = pinnedFrom(body.version);
  const terms = termsFrom(body.terms);
  if (version === null || terms === null) return null;
  if (body.kind === "reference_list") {
    return "takes" in body ||
      "gives" in body ||
      "searchWay" in body ||
      "searchMatches" in body ||
      !terms.has(version.versionId)
      ? null
      : { kind: "reference_list", version, terms };
  }
  const takes = listOf(body.takes, declaredFrom);
  const gives = listOf(body.gives, declaredFrom);
  if (
    takes === null ||
    gives === null ||
    !allTermsHeld([...takes, ...gives], terms)
  )
    return null;
  if (body.kind === "workflow") {
    return "searchWay" in body || "searchMatches" in body
      ? null
      : { kind: "workflow", version, terms, takes, gives };
  }
  if (body.kind !== "question") return null;
  const searchWay = optional(body, "searchWay", textFrom);
  const searchMatches = body.searchMatches;
  return searchWay === null ||
    typeof searchMatches !== "boolean" ||
    (searchMatches && searchWay === undefined)
    ? null
    : {
        kind: "question",
        version,
        terms,
        takes,
        gives,
        searchMatches,
        ...(searchWay === undefined ? {} : { searchWay }),
      };
}

function allTermsHeld(
  fields: readonly DeclaredField[],
  terms: ReadonlyMap<string, readonly string[]>,
): boolean {
  return fields.every(
    (field) =>
      (field.list === undefined || terms.has(field.list.versionId)) &&
      (field.fields === undefined || allTermsHeld(field.fields, terms)),
  );
}
