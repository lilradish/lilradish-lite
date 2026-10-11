import { useCallback, useEffect, useRef, useState } from "react";

import { readRun, type Run } from "../../../api/groups/{groupId}/runs/{runId}";
import {
  readSteps,
  type RunSteps,
} from "../../../api/groups/{groupId}/runs/{runId}/steps";
import {
  asProblem,
  NOT_REACHED,
  RequestFailed,
  type Problem,
} from "../../../api/problem";
import { useResource, type Resource } from "../../../lib/request/useResource";
import { reportCaught } from "../../../lib/report/reportCaught";
import { useRunReadAccess } from "../RunReadAccess";

const PAIR_ATTEMPTS = 3;
const PAIR_WAIT_MS = 100;

export interface RunReading {
  readonly run: Run;
  readonly steps: RunSteps;
}

/** One read's answer, and the read it was taken over: a read asked again since lets it go. */
interface Kept<T> {
  readonly load: (signal: AbortSignal) => Promise<T | null>;
  readonly over: T | null;
  readonly value: T;
}

type KeptUp<T> = Resource<T | null> & { readonly quietly: () => void };

/** A run and its steps, read together and kept up while the steps answer says how often. */
export function useRunRead(groupId: string, runId: string): KeptUp<RunReading> {
  const access = useRunReadAccess();
  const guardRead = access.read;
  // After a step act the run is read again and after a run act the steps are: here the two are one read.
  const load = useCallback(
    async (signal: AbortSignal): Promise<RunReading | null> => {
      for (;;) {
        for (let attempt = 0; attempt < PAIR_ATTEMPTS; attempt += 1) {
          if (signal.aborted) {
            throw signal.reason;
          }
          const [run, steps] = await Promise.all([
            guardRead(signal, () => readRun(groupId, runId, signal)),
            guardRead(signal, () => readSteps(groupId, runId, signal)),
          ]);
          if (signal.aborted) {
            throw signal.reason;
          }
          if (sameReachedSteps(run, steps)) {
            return { run, steps };
          }
          await waitForPair(signal);
        }
      }
    },
    [groupId, runId, guardRead],
  );
  return useKeptUp(load, waitOfReading);
}

function sameReachedSteps(run: Run, steps: RunSteps): boolean {
  if (run.state !== steps.run.state) {
    return false;
  }
  const events = new Set(
    run.conversationEvents.flatMap((event) =>
      event.kind === "step_reached" && event.run.runId === run.runId
        ? [event.stepId]
        : [],
    ),
  );
  const rows = new Set(
    steps.steps
      .filter((step) => step.state !== "not_started")
      .map((step) => step.stepId),
  );
  return (
    events.size === rows.size && [...events].every((stepId) => rows.has(stepId))
  );
}

function waitForPair(signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal.aborted) {
      reject(signal.reason);
      return;
    }
    const cancel = () => {
      clearTimeout(timer);
      reject(signal.reason);
    };
    const timer = setTimeout(() => {
      signal.removeEventListener("abort", cancel);
      resolve();
    }, PAIR_WAIT_MS);
    signal.addEventListener("abort", cancel, { once: true });
  });
}

function waitOfReading(reading: RunReading): number | undefined {
  return reading.steps.rereadAfterSeconds;
}

/**
 * `load` reads again quietly every while `waitOf` says, and by `quietly`; a served refusal stops that read,
 * while one that reached no server is tried again at the next wait. Both must be stable, as `useResource` asks.
 */
export function useKeptUp<T>(
  load: (signal: AbortSignal) => Promise<T | null>,
  waitOf: (value: T) => number | undefined,
): KeptUp<T> {
  const access = useRunReadAccess();
  const isClosed = access.closed;
  const closedProblem = access.problem;
  const read = useResource(load, null);
  const over = read.value;
  const [kept, setKept] = useState<Kept<T> | null>(null);
  const [quietFailure, setQuietFailure] = useState<{
    readonly load: typeof load;
    readonly over: T | null;
    readonly problem: Problem;
    readonly recovering: boolean;
  } | null>(null);
  const [unreached, setUnreached] = useState(0);
  const failedQuiet =
    quietFailure?.load === load && quietFailure.over === over
      ? quietFailure
      : null;
  const shown =
    failedQuiet !== null && (!failedQuiet.recovering || read.loading)
      ? null
      : kept !== null && kept.load === load && kept.over === over
        ? kept.value
        : over;
  const problem =
    read.problem ??
    (read.loading || failedQuiet?.recovering
      ? null
      : (failedQuiet?.problem ?? null));
  const asking = useRef<AbortController | null>(null);
  const reloadRead = read.reload;
  const reload = useCallback(() => {
    asking.current?.abort();
    setQuietFailure((failed) =>
      failed === null ? null : { ...failed, recovering: true },
    );
    reloadRead();
  }, [reloadRead]);

  const quietly = useCallback(() => {
    if (isClosed()) {
      return;
    }
    asking.current?.abort();
    const controller = new AbortController();
    asking.current = controller;
    load(controller.signal).then(
      (value) => {
        if (!controller.signal.aborted && !isClosed() && value !== null) {
          setQuietFailure(null);
          setKept({ load, over, value });
        }
      },
      (failure: unknown) => {
        if (controller.signal.aborted || isClosed()) {
          return;
        }
        if (
          failure instanceof RequestFailed &&
          failure.problem.status === undefined &&
          failure.problem.code === NOT_REACHED
        ) {
          setUnreached((count) => count + 1);
        } else {
          if (!(failure instanceof RequestFailed)) {
            reportCaught(failure);
          }
          setKept(null);
          setQuietFailure({
            load,
            over,
            problem: asProblem(failure, controller.signal),
            recovering: false,
          });
        }
      },
    );
  }, [load, over, isClosed]);

  // `load` is read by nothing here: a quiet read of one `load` is let go once another replaces it.
  useEffect(() => {
    const held = asking;
    return () => held.current?.abort();
  }, [load]);

  const wait = shown === null ? undefined : waitOf(shown);
  const settled = !read.loading && problem === null;
  // `shown` and `unreached` are read by nothing here: each new read, or each that reached nothing, sets the next
  // wait going. `exhaustive-deps` is blind to their removal; the tests are not.
  useEffect(() => {
    if (closedProblem !== null || isClosed()) {
      asking.current?.abort();
      return;
    }
    if (wait === undefined || !settled) {
      return;
    }
    const timer = setTimeout(quietly, wait * 1000);
    return () => clearTimeout(timer);
  }, [closedProblem, isClosed, wait, settled, shown, unreached, quietly]);

  return { ...read, value: shown, problem, reload, quietly };
}
