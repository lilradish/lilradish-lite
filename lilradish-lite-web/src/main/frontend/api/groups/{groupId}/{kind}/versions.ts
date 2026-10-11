import { get } from "../../../../lib/request/http";
import {
  isUnchecked,
  listOf,
  optional,
  textFrom,
} from "../../../../lib/request/document";
import { listVersionFrom, type ListVersion } from "../../../declaration";
import { SEGMENTS, type EntryKind } from "../{kind}";

export interface VersionCandidate extends ListVersion {
  readonly searchWay?: string;
  readonly searchMatches?: boolean;
}

export interface VersionCandidates {
  readonly items: readonly VersionCandidate[];
  readonly more: boolean;
}

/** The server reads no collection for an empty search. */
export function findVersions(
  groupId: string,
  kind: EntryKind,
  text: string,
  searchWay: string | undefined,
  signal: AbortSignal,
): Promise<VersionCandidates> {
  const query = new URLSearchParams({ filter: text });
  if (searchWay !== undefined) query.set("searchWay", searchWay);
  return get(
    `/api/groups/${encodeURIComponent(groupId)}/${SEGMENTS[kind]}/versions?${query}`,
    signal,
    (body) => candidatesFrom(body, kind),
  );
}

export function candidateFrom(
  body: unknown,
  kind: EntryKind,
): VersionCandidate | null {
  const version = listVersionFrom(body);
  if (version === null || !isUnchecked(body)) return null;
  if (kind !== "question") {
    return "searchWay" in body || "searchMatches" in body ? null : version;
  }
  const searchWay = optional(body, "searchWay", textFrom);
  const searchMatches = body.searchMatches;
  return searchWay === null ||
    typeof searchMatches !== "boolean" ||
    (searchMatches && searchWay === undefined)
    ? null
    : {
        ...version,
        searchMatches,
        ...(searchWay === undefined ? {} : { searchWay }),
      };
}

export function candidatesFrom(
  body: unknown,
  kind: EntryKind,
): VersionCandidates | null {
  if (!isUnchecked(body) || typeof body.more !== "boolean") return null;
  const items = listOf(body.items, (item) => candidateFrom(item, kind));
  return items === null ||
    items.length > 20 ||
    new Set(items.map((item) => item.versionId)).size !== items.length
    ? null
    : { items, more: body.more };
}
