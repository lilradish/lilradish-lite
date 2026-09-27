import { act, renderHook, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { BROKEN_HERE, NOT_REACHED, RequestFailed } from "../../api/problem";
import { serving } from "../../testutil/answering";
import { deferred } from "../../testutil/deferred";
import { reportCaught } from "../report/reportCaught";
import { post } from "./http";
import { useAction } from "./useAction";

vi.mock("../report/reportCaught", () => ({ reportCaught: vi.fn() }));

describe("useAction", () => {
  it("says it is running from the moment the reader asks", () => {
    const settled = vi.fn();
    const answer = deferred<unknown>();
    const { result } = renderHook(() => useAction(settled));

    act(() => {
      result.current.run(() => answer.promise);
    });

    expect(result.current.running).toBe(true);
    expect(result.current.problem).toBeNull();
    expect(settled).not.toHaveBeenCalled();
  });

  it("hands the screen what the act answered and stops running", async () => {
    const settled = vi.fn();
    const { result } = renderHook(() => useAction(settled));

    await act(async () => {
      result.current.run(() => Promise.resolve({ runId: "9f1c" }));
    });

    expect(settled).toHaveBeenCalledExactlyOnceWith({ runId: "9f1c" });
    expect(result.current.running).toBe(false);
    expect(result.current.problem).toBeNull();
  });

  /**
   * The screen moves on while an act is out — a filter typed, another row
   * picked — and an answer taken into the screen as it was would undo that.
   */
  it("hands the answer to the screen as it stands when the answer lands, not as it stood when asked", async () => {
    const settledThen = vi.fn();
    const settledNow = vi.fn();
    const answer = deferred<unknown>();
    const { result, rerender } = renderHook(
      ({ settled }) => useAction(settled),
      { initialProps: { settled: settledThen } },
    );
    act(() => {
      result.current.run(() => answer.promise);
    });

    rerender({ settled: settledNow });
    await act(async () => {
      answer.settle({ runId: "9f1c" });
    });

    expect(settledNow).toHaveBeenCalledExactlyOnceWith({ runId: "9f1c" });
    expect(settledThen).not.toHaveBeenCalled();
  });

  it("hands a refusal to the screen as it stands when the refusal lands, and reports nothing as done", async () => {
    const settled = vi.fn();
    const refusedThen = vi.fn();
    const refusedNow = vi.fn();
    const answer = deferred<unknown>();
    const { result, rerender } = renderHook(
      ({ refused }) => useAction(settled, refused),
      { initialProps: { refused: refusedThen } },
    );
    act(() => {
      result.current.run(() => answer.promise);
    });

    rerender({ refused: refusedNow });
    await act(async () => {
      answer.refuse(new RequestFailed({ status: 409, code: "OTHER_REFUSAL" }));
    });

    expect(refusedNow).toHaveBeenCalledExactlyOnceWith({
      status: 409,
      code: "OTHER_REFUSAL",
    });
    expect(result.current.problem).toEqual({
      status: 409,
      code: "OTHER_REFUSAL",
    });
    expect(refusedThen).not.toHaveBeenCalled();
    expect(settled).not.toHaveBeenCalled();
  });

  it("hands on no refusal for an act it abandoned", async () => {
    const refused = vi.fn();
    const first = deferred<unknown>();
    const { result } = renderHook(() => useAction(vi.fn(), refused));
    act(() => {
      result.current.run(() => first.promise);
    });
    act(() => {
      result.current.run(() => new Promise(() => {}));
    });

    await act(async () => {
      first.refuse(
        new DOMException("The operation was aborted.", "AbortError"),
      );
    });

    expect(refused).not.toHaveBeenCalled();
  });

  it("keeps one and the same run however often the screen hands it a new callback", () => {
    const { result, rerender } = renderHook(
      ({ settled }) => useAction(settled),
      { initialProps: { settled: vi.fn() } },
    );
    const first = result.current.run;

    rerender({ settled: vi.fn() });

    expect(result.current.run).toBe(first);
  });

  it("shows the server's own reason for a refusal and does not report the act as done", async () => {
    const settled = vi.fn();
    const { result } = renderHook(() => useAction(settled));

    await act(async () => {
      result.current.run(() =>
        Promise.reject(
          new RequestFailed({
            status: 409,
            code: "SOME_REFUSAL",
            detail: "Someone changed this first.",
          }),
        ),
      );
    });

    expect(result.current.problem).toEqual({
      status: 409,
      code: "SOME_REFUSAL",
      detail: "Someone changed this first.",
    });
    expect(result.current.running).toBe(false);
    expect(settled).not.toHaveBeenCalled();
  });

  it("routes an act that throws where one that rejects goes, reported as the fault it is", async () => {
    const settled = vi.fn();
    const fault = new Error("built the request wrong");
    const { result } = renderHook(() => useAction(settled));

    act(() => {
      result.current.run(() => {
        throw fault;
      });
    });

    await waitFor(() => expect(result.current.running).toBe(false));

    expect(result.current.problem).toEqual({ code: BROKEN_HERE });
    expect(settled).not.toHaveBeenCalled();
    expect(vi.mocked(reportCaught).mock.calls).toEqual([[fault]]);
  });

  it("reports a fault in work chained onto the act exactly once, and shows the reader none of what it says", async () => {
    serving({ "POST /api/groups": [["{}", 201]] });
    const settled = vi.fn();
    const refused = vi.fn();
    const { result } = renderHook(() => useAction<string>(settled, refused));

    await act(async () => {
      result.current.run((signal) =>
        post("/api/groups", {}, signal, (body) => body as { id: string }).then(
          (answer) => answer.id.trim(),
        ),
      );
    });

    expect(result.current.problem).toEqual({ code: BROKEN_HERE });
    expect(refused).toHaveBeenCalledWith({ code: BROKEN_HERE });
    expect(settled).not.toHaveBeenCalled();
    expect(vi.mocked(reportCaught)).toHaveBeenCalledOnce();
    expect(vi.mocked(reportCaught).mock.calls[0]?.[0]).toBeInstanceOf(
      TypeError,
    );
  });

  it("reports nothing of an act no response came back to, and says it never arrived", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>().mockRejectedValue(new TypeError("Failed to fetch")),
    );
    const settled = vi.fn();
    const { result } = renderHook(() => useAction(settled));

    await act(async () => {
      result.current.run((signal) =>
        post("/api/groups", {}, signal, (body) => body),
      );
    });

    expect(result.current.problem).toEqual({ code: NOT_REACHED });
    expect(vi.mocked(reportCaught)).not.toHaveBeenCalled();
  });

  it("reports nothing of an act abandoned by asking again", async () => {
    const settled = vi.fn();
    const abandoned = vi.fn();
    const { result } = renderHook(() => useAction(settled));
    act(() => {
      result.current.run(
        (signal) =>
          new Promise((_settle, refuse) => {
            signal.addEventListener(
              "abort",
              () => {
                abandoned();
                refuse(signal.reason);
              },
              { once: true },
            );
          }),
      );
    });

    await act(async () => {
      result.current.run(() => deferred<unknown>().promise);
    });

    expect(abandoned).toHaveBeenCalledOnce();
    expect(vi.mocked(reportCaught)).not.toHaveBeenCalled();
  });

  it("puts the previous refusal away the moment the act is asked for again", async () => {
    const settled = vi.fn();
    const retry = deferred<unknown>();
    const { result } = renderHook(() => useAction(settled));
    await act(async () => {
      result.current.run(() => Promise.reject(new Error("first try")));
    });
    expect(result.current.problem).not.toBeNull();

    act(() => {
      result.current.run(() => retry.promise);
    });

    expect(result.current.problem).toBeNull();
    expect(result.current.running).toBe(true);
  });

  /**
   * The two answers are settled in a fixed order rather than left to arrive in
   * one: two decisions about one attempt resolved by whichever response lands
   * last is exactly the outcome this rules out, so the test may not depend on
   * which lands last either.
   */
  it("abandons the first act rather than letting the second race it", async () => {
    const settled = vi.fn();
    const first = deferred<unknown>();
    const second = deferred<unknown>();
    const signals: AbortSignal[] = [];
    const { result } = renderHook(() => useAction(settled));

    act(() => {
      result.current.run((signal) => {
        signals.push(signal);
        return first.promise;
      });
    });
    act(() => {
      result.current.run((signal) => {
        signals.push(signal);
        return second.promise;
      });
    });

    expect(signals[0]?.aborted).toBe(true);
    expect(signals[1]?.aborted).toBe(false);

    await act(async () => {
      first.settle({ from: "the abandoned act" });
    });

    expect(settled).not.toHaveBeenCalled();
    expect(result.current.running).toBe(true);

    await act(async () => {
      second.settle({ from: "the live act" });
    });

    expect(settled).toHaveBeenCalledExactlyOnceWith({ from: "the live act" });
    expect(result.current.running).toBe(false);
  });

  it("reports an abandoned act as neither done nor refused", async () => {
    const settled = vi.fn();
    const first = deferred<unknown>();
    const second = deferred<unknown>();
    const { result } = renderHook(() => useAction(settled));

    act(() => {
      result.current.run(() => first.promise);
    });
    act(() => {
      result.current.run(() => second.promise);
    });
    await act(async () => {
      first.refuse(
        new DOMException("The operation was aborted.", "AbortError"),
      );
    });

    expect(result.current.problem).toBeNull();
    expect(result.current.running).toBe(true);
    expect(settled).not.toHaveBeenCalled();
  });

  /**
   * `onSettled` navigates on some screens, so firing it into a tree that is
   * gone is a navigation nobody asked for.
   */
  it("settles nothing into a screen the reader has left", async () => {
    const settled = vi.fn();
    const answer = deferred<unknown>();
    const signals: AbortSignal[] = [];
    const { result, unmount } = renderHook(() => useAction(settled));

    act(() => {
      result.current.run((signal) => {
        signals.push(signal);
        return answer.promise;
      });
    });
    unmount();
    await act(async () => {
      answer.settle({ committed: true });
    });

    expect(signals[0]?.aborted).toBe(true);
    expect(settled).not.toHaveBeenCalled();
  });
});
