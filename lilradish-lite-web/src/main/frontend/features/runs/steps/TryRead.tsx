import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useState,
  type ReactNode,
} from "react";

import { readTry } from "../../../api/groups/{groupId}/runs/{runId}/steps/{stepId}/tries/{number}";
import type { GroupStanding } from "../../../api/standing";
import type { Try, TryDetails } from "../../../api/stepTries";
import { Async } from "../../../app/Async";
import { useStandingRead } from "../../../app/standing/StandingContext";
import { say } from "../../../i18n/app";
import { Press } from "../../../lib/action/Press";
import { useResource, type Resource } from "../../../lib/request/useResource";
import { useRunReadAccess } from "../RunReadAccess";

const Reads = createContext<ReadonlyMap<
  number,
  Resource<TryDetails | null>
> | null>(null);

/** The displayed and waiting attempts have at most two reads; equal numbers share the same one. */
export function TryRead({
  group,
  runId,
  stepId,
  shown,
  waiting,
  children,
}: {
  readonly group: GroupStanding;
  readonly runId: string;
  readonly stepId: string;
  readonly shown: Try | undefined;
  readonly waiting: Try | undefined;
  readonly children: ReactNode;
}) {
  const needed = [shown, waiting].filter(
    (aTry, at, all): aTry is Try =>
      aTry !== undefined &&
      all.findIndex((other) => other?.number === aTry.number) === at,
  );
  const [slots, setSlots] = useState<readonly (number | undefined)[]>([
    needed[0]?.number,
    needed[1]?.number,
  ]);
  let first = needed.find((aTry) => aTry.number === slots[0]);
  let second = needed.find((aTry) => aTry.number === slots[1]);
  for (const aTry of needed) {
    if (aTry.number !== first?.number && aTry.number !== second?.number) {
      if (first === undefined) {
        first = aTry;
      } else {
        second = aTry;
      }
    }
  }
  if (slots[0] !== first?.number || slots[1] !== second?.number) {
    setSlots([first?.number, second?.number]);
  }
  const firstRead = useNumberedRead(group, runId, stepId, first);
  const secondRead = useNumberedRead(group, runId, stepId, second);
  const firstNumber = first?.number;
  const secondNumber = second?.number;
  const reads = useMemo(() => {
    const held = new Map<number, Resource<TryDetails | null>>();
    if (firstNumber !== undefined) {
      held.set(firstNumber, firstRead);
    }
    if (secondNumber !== undefined) {
      held.set(secondNumber, secondRead);
    }
    return held;
  }, [firstNumber, secondNumber, firstRead, secondRead]);
  return <Reads.Provider value={reads}>{children}</Reads.Provider>;
}

function factsOf(aTry: Try | undefined): string {
  return JSON.stringify(
    aTry === undefined
      ? null
      : [
          aTry.ended,
          aTry.wentWrongPresent,
          aTry.returnedPresent,
          aTry.declinedPresent,
          aTry.unreadableResponsePresent,
          aTry.modelReplyPresent,
          aTry.review.asked,
          aTry.review.by?.kind,
          aTry.review.wentWrong,
          aTry.review.lost,
          aTry.review.declinedPresent,
          aTry.review.unreadableResponsePresent,
          aTry.review.modelReplyPresent,
        ],
  );
}

function useNumberedRead(
  group: GroupStanding,
  runId: string,
  stepId: string,
  aTry: Try | undefined,
): Resource<TryDetails | null> {
  const standing = useStandingRead();
  const current = standing.value.groups.find(
    (held) => held.groupId === group.groupId,
  );
  const userId = standing.value.userId;
  const grants = JSON.stringify(
    current === undefined ? null : [...current.permissions].sort(),
  );
  const access = useRunReadAccess();
  const admitted =
    access.problem === null &&
    !access.closed() &&
    standing.problem === null &&
    userId !== undefined &&
    (current?.permissions.has("read_own_runs") === true ||
      current?.permissions.has("read_all_runs") === true);
  const facts = factsOf(aTry);
  const number = aTry?.number;
  const groupId = group.groupId;
  const identity = JSON.stringify([
    groupId,
    runId,
    stepId,
    number,
    userId,
    grants,
    admitted,
    facts,
  ]);
  const guardRead = access.read;
  const load = useCallback(
    (signal: AbortSignal) =>
      !admitted || number === undefined
        ? Promise.resolve(null)
        : guardRead(signal, async () => ({
            identity,
            value: await readTry(groupId, runId, stepId, number, signal),
          })),
    [admitted, number, identity, groupId, runId, stepId, guardRead],
  );
  const read = useResource(load, null);
  return useMemo(
    () => ({
      ...read,
      value: read.value?.identity === identity ? read.value.value : null,
    }),
    [read, identity],
  );
}

/** Material errors stay at this attempt; a refusal of the run itself is handled by the route's read guard. */
export function TryMaterial({
  number,
  children,
}: {
  readonly number: number;
  readonly children: (details: TryDetails | undefined) => ReactNode;
}) {
  const reads = useContext(Reads);
  if (reads === null) {
    throw new Error("Attempt materials have no reading owner");
  }
  const read = reads.get(number);
  if (read === undefined) {
    return null;
  }
  return (
    <>
      {children(read.problem === null ? (read.value ?? undefined) : undefined)}
      <Async read={read} empty={() => null}>
        {() => null}
      </Async>
      {read.problem === null ? null : (
        <Press tier="secondary" onPress={read.reload}>
          {say("read.retry")}
        </Press>
      )}
    </>
  );
}
