import { put, remove } from "../../../../../lib/request/http";
import {
  panelFrom,
  type EstateRole,
  type PoolPersonPanel,
} from "../../../people";
import { atPerson } from "../../{subjectId}";

/**
 * The role goes into the address unescaped, which holds only while it is typed
 * as one of the published spellings and never as any word that arrived.
 */
export function grant(
  subjectId: string,
  role: EstateRole,
  signal: AbortSignal,
): Promise<PoolPersonPanel> {
  return atPerson(subjectId, (person) =>
    put(`${person}/estate-roles/${role}`, signal, panelFrom),
  );
}

export function withdraw(
  subjectId: string,
  role: EstateRole,
  signal: AbortSignal,
): Promise<PoolPersonPanel> {
  return atPerson(subjectId, (person) =>
    remove(`${person}/estate-roles/${role}`, signal, panelFrom),
  );
}
