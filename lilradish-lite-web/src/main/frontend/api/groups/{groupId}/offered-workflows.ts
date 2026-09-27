import { atSegment } from "../../../lib/request/address";
import {
  countFrom,
  isUnchecked,
  listOf,
  optional,
  textFrom,
} from "../../../lib/request/document";
import { get } from "../../../lib/request/http";
import { fillFieldsFrom, type FillField } from "../../filling";
import { GROUPS } from "../../groups";

/** A workflow a run may be started of. `entryId` addresses it and is never shown. */
export interface OfferedWorkflow {
  readonly entryId: string;
  /** Shown isolated wherever it is shown: a name may carry a directional control. */
  readonly name: string;
  /** Absent where it says nothing of what it is for. */
  readonly purpose?: string;
  /** Every version of it in service, newest first, and never none: a workflow is offered for having one. */
  readonly versions: readonly OfferedVersion[];
}

/** One version in service, with what a run of it takes. `versionId` is what a run is started of. */
export interface OfferedVersion {
  readonly versionId: string;
  readonly number: number;
  /** In declared order. */
  readonly takes: readonly FillField[];
}

/** By name, as the server orders them. */
export function readOffered(
  groupId: string,
  signal: AbortSignal,
): Promise<readonly OfferedWorkflow[]> {
  return atSegment(GROUPS, groupId, (group) =>
    get(`${group}/offered-workflows`, signal, offeredFrom),
  );
}

function offeredFrom(body: unknown): readonly OfferedWorkflow[] | null {
  return isUnchecked(body) ? listOf(body.workflows, workflowFrom) : null;
}

/** Built member by member; a workflow missing what it is drawn from is not one this side can show. */
function workflowFrom(body: unknown): OfferedWorkflow | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const { entryId, name } = body;
  const purpose = optional(body, "purpose", textFrom);
  const versions = listOf(body.versions, versionFrom);
  if (
    typeof entryId !== "string" ||
    typeof name !== "string" ||
    purpose === null ||
    versions === null ||
    versions.length === 0
  ) {
    return null;
  }
  const offered = { entryId, name, versions };
  return purpose === undefined ? offered : { ...offered, purpose };
}

function versionFrom(body: unknown): OfferedVersion | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const { versionId } = body;
  const number = countFrom(body.number);
  const takes = fillFieldsFrom(body.takes);
  return typeof versionId === "string" && number !== null && takes !== null
    ? { versionId, number, takes }
    : null;
}
