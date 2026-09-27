import { useCallback, useEffect, useRef, useState } from "react";

import { readRun, type Run } from "../../../api/groups/{groupId}/runs/{runId}";
import {
  readSteps,
  type RunSteps,
} from "../../../api/groups/{groupId}/runs/{runId}/steps";
import { NOT_REACHED, RequestFailed } from "../../../api/problem";
import { useResource, type Resource } from "../../../lib/request/useResource";

export interface RunReading {
  readonly run: Run;
  readonly steps: RunSteps;
}

/** One read's answer, and the read it was taken over: a read asked again since lets it go. */
interface Kept<T> {
  readonly over: T | null;
  readonly value: T;
}

/** A read kept up, and a way to read it again at once, as it keeps up. */
type KeptUp<T> = Resource<T | null> & { readonly quietly: () => void };

/** A run and its steps, read together and kept up while the steps answer says how often. */
export function useRunRead(groupId: string, runId: string): KeptUp<RunReading> {
  // After a step act the run is read again and after a run act the steps are: here the two are one read.
  const load = useCallback(
    async (signal: AbortSignal): Promise<RunReading | null> => {
      const [run, steps] = await Promise.all([
        readRun(groupId, runId, signal),
        readSteps(groupId, runId, signal),
      ]);
      return { run, steps };
    },
    [groupId, runId],
  );
  return useKeptUp(load, waitOfReading);
}

function waitOfReading(reading: RunReading): number | undefined {
  return reading.steps.rereadAfterSeconds;
}

/**
 * `load` read again quietly every while `waitOf` says, and by `quietly`; a refusal is said as `reload` says one,
 * and a read that reached no server is tried again at the next wait. Both must be stable, as `useResource` asks.
 */
export function useKeptUp<T>(
  load: (signal: AbortSignal) => Promise<T | null>,
  waitOf: (value: T) => number | undefined,
): KeptUp<T> {
  const read = useResource(load, null);
  const reload = read.reload;
  const over = read.value;
  const [kept, setKept] = useState<Kept<T> | null>(null);
  const [unreached, setUnreached] = useState(0);
  const shown = kept !== null && kept.over === over ? kept.value : over;
  const asking = useRef<AbortController | null>(null);

  const quietly = useCallback(() => {
    asking.current?.abort();
    const controller = new AbortController();
    asking.current = controller;
    load(controller.signal).then(
      (value) => {
        if (!controller.signal.aborted && value !== null) {
          setKept({ over, value });
        }
      },
      (failure: unknown) => {
        if (controller.signal.aborted) {
          return;
        }
        if (
          failure instanceof RequestFailed &&
          failure.problem.status === undefined &&
          failure.problem.code === NOT_REACHED
        ) {
          setUnreached((count) => count + 1);
        } else {
          reload();
        }
      },
    );
  }, [load, over, reload]);

  // `load` is read by nothing here: a quiet read of one `load` is let go once another replaces it.
  useEffect(() => {
    const held = asking;
    return () => held.current?.abort();
  }, [load]);

  const wait = shown === null ? undefined : waitOf(shown);
  const settled = !read.loading && read.problem === null;
  // `shown` and `unreached` are read by nothing here: each new read, or each that reached nothing, sets the next
  // wait going. `exhaustive-deps` is blind to their removal; the tests are not.
  useEffect(() => {
    if (wait === undefined || !settled) {
      return;
    }
    const timer = setTimeout(quietly, wait * 1000);
    return () => clearTimeout(timer);
  }, [wait, settled, shown, unreached, quietly]);

  return { ...read, value: shown, quietly };
}
