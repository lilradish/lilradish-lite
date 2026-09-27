import type { PoolPersonPanel } from "../../api/pool/people";
import { readPerson } from "../../api/pool/people/{subjectId}";
import { usePickedRow } from "../../lib/collection/usePickedRow";

/** The person in the pool the address names, as `usePickedRow` holds a picked row. */
export function usePickedPerson(subjectId: string | undefined) {
  return usePickedRow(subjectId, readPerson, subjectIdOf);
}

function subjectIdOf(person: PoolPersonPanel): string {
  return person.subjectId;
}
