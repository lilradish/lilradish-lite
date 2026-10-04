import { act, renderHook, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";

import { RequestFailed, type Problem } from "../api/problem";
import { useResource, type Resource } from "../lib/request/useResource";
import { deferred } from "../testutil/deferred";
import { answered } from "../testutil/standingRead";
import { StandingProvider } from "./standing/StandingContext";
import { useActedOn } from "./useActedOn";

interface Thing {
  readonly name: string;
}

const READ: Thing = { name: "as read" };

const ANSWERED: Thing = { name: "as the act answered" };

const EMPTY: Thing = { name: "nothing held" };

function settled(value: Thing, problem: Problem | null = null) {
  return { value, problem, loading: false, reload: vi.fn() };
}

/** Only a refusal naming this code says the thing moved. */
function movedWhere(problem: Problem): boolean {
  return problem.code === "THING_MOVED";
}

/** The hook over a read the spec hands in, and whose reading again it and the standing's it counts. */
function actingOn(first: Resource<Thing>) {
  const readStandingAgain = vi.fn();
  const answeredWith = vi.fn();
  const readAgain = vi.fn();
  const standing = { ...answered([]), reload: readStandingAgain };
  const hooked = renderHook(
    (read: Resource<Thing>) =>
      useActedOn(read, movedWhere, { answered: answeredWith, readAgain }),
    {
      initialProps: first,
      wrapper: ({ children }: { readonly children: ReactNode }) => (
        <StandingProvider read={standing}>{children}</StandingProvider>
      ),
    },
  );
  return { ...hooked, readStandingAgain, answeredWith, readAgain };
}

const REFUSALS = [
  ["the act itself", { status: 403, code: "ACT_NOT_PERMITTED" }, 1, 1],
  ["the group", { status: 404, code: "GROUP_NOT_IN_VIEW" }, 1, 1],
  ["the thing having moved", { status: 409, code: "THING_MOVED" }, 0, 1],
  ["what was sent", { status: 400, code: "BODY_UNUSABLE" }, 0, 0],
] as const;

function refusing(problem: Problem): () => Promise<Thing> {
  return () => Promise.reject(new RequestFailed(problem));
}

describe("useActedOn", () => {
  it("shows what was read, then what an act answered, then what a read after it brought", async () => {
    const first = settled(READ);
    const { result, rerender } = actingOn(first);
    expect(result.current.shown).toBe(READ);

    await act(async () =>
      result.current.changing.run(() => Promise.resolve(ANSWERED)),
    );
    expect(result.current.shown).toBe(ANSWERED);

    const again = { name: "read again" };
    rerender(settled(again));
    expect(result.current.shown).toBe(again);
  });

  it("guards an action answer from an older read in flight, then takes the fresh answer", async () => {
    const read = { ...settled(READ), loading: true };
    const { result, rerender } = actingOn(read);

    await act(async () =>
      result.current.changing.run(() => Promise.resolve(ANSWERED)),
    );

    expect(read.reload).toHaveBeenCalledOnce();
    expect(result.current.shown).toBe(ANSWERED);

    const fresh = { name: "as freshly read" };
    rerender({ ...read, value: fresh, loading: false });
    expect(result.current.shown).toBe(fresh);
    expect(result.current.changing.problem).toBeNull();
  });

  it("clears an action answer if its guarded fresh read is refused", async () => {
    const read = { ...settled(READ), loading: true };
    const { result, rerender } = actingOn(read);
    await act(async () =>
      result.current.changing.run(() => Promise.resolve(ANSWERED)),
    );

    const refusal = { status: 403, code: "THING_NOT_IN_VIEW" };
    rerender({ ...read, value: EMPTY, problem: refusal, loading: false });

    expect(result.current.shown).toBe(EMPTY);
    expect(result.current.changing.problem).toBeNull();
    expect(read.reload).toHaveBeenCalledOnce();
  });

  it.each(["answer", "refusal"])(
    "keeps an action answer when an older %s has queued a render in the same turn",
    async (ending) => {
      const earlier = deferred<Thing>();
      const fresh = deferred<Thing>();
      const load = vi
        .fn<(signal: AbortSignal) => Promise<Thing>>()
        .mockResolvedValueOnce(READ)
        .mockImplementationOnce(() => earlier.promise)
        .mockImplementationOnce(() => fresh.promise);
      const standing = answered([]);
      const { result } = renderHook(
        () => {
          const read = useResource(load, EMPTY);
          return { read, acted: useActedOn(read, movedWhere) };
        },
        {
          wrapper: ({ children }: { readonly children: ReactNode }) => (
            <StandingProvider read={standing}>{children}</StandingProvider>
          ),
        },
      );
      await waitFor(() => expect(result.current.read.loading).toBe(false));
      act(() => result.current.read.reload());

      await act(async () => {
        if (ending === "answer") {
          earlier.settle({ name: "obsolete read" });
        } else {
          earlier.refuse(
            new RequestFailed({ status: 403, code: "OBSOLETE_REFUSAL" }),
          );
        }
        await Promise.resolve();
        result.current.acted.changing.run(() => Promise.resolve(ANSWERED));
        await Promise.resolve();
      });

      expect(result.current.read.loading).toBe(true);
      expect(result.current.read.problem).toBeNull();
      expect(result.current.acted.shown).toBe(ANSWERED);
      expect(load).toHaveBeenCalledTimes(3);

      await act(async () => fresh.settle({ name: "fresh read" }));
      expect(result.current.acted.shown).toEqual({ name: "fresh read" });
    },
  );

  it("hands the page each answer shown, and never a refusal", async () => {
    const { result, answeredWith } = actingOn(settled(READ));

    await act(async () =>
      result.current.changing.run(() => Promise.resolve(ANSWERED)),
    );
    await act(async () =>
      result.current.changing.run(
        refusing({ status: 409, code: "THING_MOVED" }),
      ),
    );

    expect(answeredWith.mock.calls).toEqual([[ANSWERED]]);
  });

  it.each(REFUSALS)(
    "reads again after an act refused over %s whatever that refusal says moved, and nothing else",
    async (_case, problem, standingReads, thingReads) => {
      const first = settled(READ);
      const { result, readStandingAgain, answeredWith, readAgain } =
        actingOn(first);

      await act(async () => result.current.changing.run(refusing(problem)));

      expect(result.current.changing.problem).toEqual(problem);
      expect(readStandingAgain).toHaveBeenCalledTimes(standingReads);
      expect(first.reload).toHaveBeenCalledTimes(thingReads);
      expect(readAgain).toHaveBeenCalledTimes(thingReads);
      expect(answeredWith).not.toHaveBeenCalled();
      expect(result.current.shown).toBe(READ);
    },
  );

  it.each(REFUSALS)(
    "reads again after a refusal over %s met elsewhere on the page as after the act's own, saying none as the act's",
    (_case, problem, standingReads, thingReads) => {
      const first = settled(READ);
      const { result, readStandingAgain, readAgain } = actingOn(first);

      act(() => result.current.refused(problem));

      expect(readStandingAgain).toHaveBeenCalledTimes(standingReads);
      expect(first.reload).toHaveBeenCalledTimes(thingReads);
      expect(readAgain).toHaveBeenCalledTimes(thingReads);
      expect(result.current.changing.problem).toBeNull();
    },
  );

  it.each([
    [
      "the reader's standing moved",
      { status: 404, code: "GROUP_NOT_IN_VIEW" },
      1,
    ],
    [
      "only the thing is not there",
      { status: 404, code: "RUN_NOT_IN_VIEW" },
      0,
    ],
  ])(
    "reads the standing again where the read itself was refused as %s",
    (_case, problem, standingReads) => {
      const { readStandingAgain } = actingOn(settled(READ, problem));

      expect(readStandingAgain).toHaveBeenCalledTimes(standingReads);
    },
  );
});
