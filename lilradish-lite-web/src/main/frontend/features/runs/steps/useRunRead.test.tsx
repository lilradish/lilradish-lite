import { act, renderHook } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import {
  NOT_AN_ADDRESS,
  NOT_REACHED,
  RequestFailed,
} from "../../../api/problem";
import { useKeptUp } from "./useRunRead";

/** One read in flight, which the spec settles or refuses when it chooses, and the signal it was handed. */
interface Asked {
  readonly signal: AbortSignal;
  readonly settle: (value: string) => void;
  readonly refuse: (failure: unknown) => void;
}

/** The read being kept up, every call to it held in the order made. */
function reads() {
  const asked: Asked[] = [];
  const load = vi.fn(
    (signal: AbortSignal) =>
      new Promise<string | null>((settle, refuse) => {
        asked.push({ signal, settle, refuse });
      }),
  );
  return { asked, load };
}

const NEVER_AGAIN = (): number | undefined => undefined;

const EVERY_SECOND = (): number | undefined => 1;

const REFUSED = new RequestFailed({ status: 409, code: "RUN_STOPPED" });

async function settling(asked: Asked | undefined, value: string) {
  await act(async () => asked!.settle(value));
}

async function refusing(asked: Asked | undefined, failure: unknown) {
  await act(async () => asked!.refuse(failure));
}

afterEach(() => {
  vi.useRealTimers();
});

describe("useKeptUp", () => {
  it("replaces what is drawn with a read asked for quietly, never saying it is reading", async () => {
    const { asked, load } = reads();
    const { result } = renderHook(() => useKeptUp(load, NEVER_AGAIN));
    await settling(asked[0], "first");

    act(() => result.current.quietly());

    expect(result.current.loading).toBe(false);
    expect(result.current.value).toBe("first");
    await settling(asked[1], "second");
    expect(result.current.value).toBe("second");
    expect(result.current.problem).toBeNull();
  });

  it("lets a quiet read go once another is asked, drawing only the later, and saying nothing of the one let go", async () => {
    const { asked, load } = reads();
    const { result } = renderHook(() => useKeptUp(load, NEVER_AGAIN));
    await settling(asked[0], "first");

    act(() => result.current.quietly());
    act(() => result.current.quietly());
    act(() => result.current.quietly());
    await settling(asked[3], "latest");
    await settling(asked[1], "answered after");
    await refusing(asked[2], new DOMException("Let go.", "AbortError"));

    expect(asked[1]!.signal.aborted).toBe(true);
    expect(asked[2]!.signal.aborted).toBe(true);
    expect(asked[3]!.signal.aborted).toBe(false);
    expect(result.current.value).toBe("latest");
    expect(result.current.problem).toBeNull();
    expect(load).toHaveBeenCalledTimes(4);
  });

  it("says a refusal a quiet read met as a read asked again says it, and draws nothing read before it", async () => {
    const { asked, load } = reads();
    const { result } = renderHook(() => useKeptUp(load, NEVER_AGAIN));
    await settling(asked[0], "first");
    act(() => result.current.quietly());

    await refusing(asked[1], REFUSED);

    expect(load).toHaveBeenCalledTimes(3);
    expect(result.current.loading).toBe(true);
    await refusing(asked[2], REFUSED);
    expect(result.current.problem?.code).toBe("RUN_STOPPED");
    expect(result.current.value).toBeNull();
  });

  /** Timeouts are faked; nothing here waits the way Testing Library does. */
  it("keeps what is drawn where a quiet read reached no server, saying nothing of it, and tries again at the next wait", async () => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    const { asked, load } = reads();
    const { result } = renderHook(() => useKeptUp(load, EVERY_SECOND));
    await settling(asked[0], "first");
    await act(() => vi.advanceTimersByTimeAsync(1000));
    expect(load).toHaveBeenCalledTimes(2);

    await refusing(asked[1], new RequestFailed({ code: NOT_REACHED }));

    expect(result.current.value).toBe("first");
    expect(result.current.problem).toBeNull();
    expect(result.current.loading).toBe(false);
    expect(load).toHaveBeenCalledTimes(2);
    await act(() => vi.advanceTimersByTimeAsync(1000));
    expect(load).toHaveBeenCalledTimes(3);
    expect(asked[2]!.signal.aborted).toBe(false);
  });

  it("says a quiet read refused here without reaching the server as a read asked again says it, not as one that reached nothing", async () => {
    const { asked, load } = reads();
    const { result } = renderHook(() => useKeptUp(load, NEVER_AGAIN));
    await settling(asked[0], "first");
    act(() => result.current.quietly());

    await refusing(asked[1], new RequestFailed({ code: NOT_AN_ADDRESS }));

    expect(load).toHaveBeenCalledTimes(3);
    expect(result.current.loading).toBe(true);
    await refusing(asked[2], new RequestFailed({ code: NOT_AN_ADDRESS }));
    expect(result.current.problem).toEqual({ code: NOT_AN_ADDRESS });
    expect(result.current.value).toBeNull();
  });

  it("abandons a quiet read still in flight when it goes, drawing nothing it answers", async () => {
    const { asked, load } = reads();
    const { result, unmount } = renderHook(() => useKeptUp(load, NEVER_AGAIN));
    await settling(asked[0], "first");
    act(() => result.current.quietly());

    unmount();
    await settling(asked[1], "too late");

    expect(asked[1]!.signal.aborted).toBe(true);
    expect(result.current.value).toBe("first");
  });

  it("abandons a quiet read still in flight once another read is kept up in its place, drawing nothing it answers", async () => {
    const before = reads();
    const after = reads();
    const { result, rerender } = renderHook(
      ({ load }) => useKeptUp(load, NEVER_AGAIN),
      { initialProps: { load: before.load } },
    );
    await settling(before.asked[0], "first");
    act(() => result.current.quietly());

    rerender({ load: after.load });
    await settling(before.asked[1], "too late");

    expect(before.asked[1]!.signal.aborted).toBe(true);
    expect(result.current.value).not.toBe("too late");
    await settling(after.asked[0], "second");
    expect(after.asked[0]!.signal.aborted).toBe(false);
    expect(result.current.value).toBe("second");
  });
});
