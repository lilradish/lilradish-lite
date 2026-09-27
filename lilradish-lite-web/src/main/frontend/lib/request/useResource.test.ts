import { act, renderHook, waitFor } from "@testing-library/react";
import { useEffect } from "react";
import { describe, expect, it, vi } from "vitest";

import { BROKEN_HERE, NOT_REACHED, RequestFailed } from "../../api/problem";
import { serving } from "../../testutil/answering";
import { deferred } from "../../testutil/deferred";
import { reportCaught } from "../report/reportCaught";
import { get } from "./http";
import { useResource, type Resource } from "./useResource";

vi.mock("../report/reportCaught", () => ({ reportCaught: vi.fn() }));

const NONE: readonly string[] = [];

type Load = (signal: AbortSignal) => Promise<readonly string[]>;

/**
 * `commits` holds what each committed render saw, in order. A render React
 * threw away never reaches the effect that records it, so an entry here is a
 * paint the reader would have had — which is the only way to tell "replaced
 * before anyone saw it" from "replaced one frame later".
 */
function readingWith(load: Load) {
  const commits: Resource<readonly string[]>[] = [];
  const view = renderHook(
    ({ source }: { source: Load }) => {
      const resource = useResource(source, NONE);
      useEffect(() => {
        commits.push(resource);
      });
      return resource;
    },
    { initialProps: { source: load } },
  );
  return { ...view, commits };
}

describe("useResource", () => {
  it("says it is reading, and holds nothing out as an answer, until one arrives", () => {
    const answer = deferred<readonly string[]>();

    const { result } = readingWith(() => answer.promise);

    expect(result.current.loading).toBe(true);
    expect(result.current.value).toBe(NONE);
    expect(result.current.problem).toBeNull();
  });

  it("shows what the server answered and stops saying it is reading", async () => {
    const answer = deferred<readonly string[]>();
    const { result } = readingWith(() => answer.promise);

    await act(async () => {
      answer.settle(["one row"]);
    });

    expect(result.current.value).toEqual(["one row"]);
    expect(result.current.loading).toBe(false);
    expect(result.current.problem).toBeNull();
  });

  it("shows the server's own reason for a refusal", async () => {
    const { result } = readingWith(() =>
      Promise.reject(
        new RequestFailed({
          status: 403,
          code: "SOME_REFUSAL",
          detail: "This is not yours to read.",
        }),
      ),
    );

    await waitFor(() => expect(result.current.problem).not.toBeNull());

    expect(result.current.problem).toEqual({
      status: 403,
      code: "SOME_REFUSAL",
      detail: "This is not yours to read.",
    });
    expect(result.current.loading).toBe(false);
  });

  /**
   * What was refused may be the authority itself. Rows fetched under an
   * authority since withdrawn are not stale rows — they are rows the reader is
   * no longer allowed to have, and leaving them up says the opposite.
   */
  it.each(["SOME_REFUSAL", "OTHER_REFUSAL"])(
    "takes the rows down with the authority when a re-read is refused with %s",
    async (code) => {
      const load = vi
        .fn<Load>()
        .mockResolvedValueOnce(["a row someone is reading"])
        .mockRejectedValueOnce(new RequestFailed({ status: 403, code }));
      const { result } = readingWith(load);
      await waitFor(() => expect(result.current.loading).toBe(false));

      act(() => {
        result.current.reload();
      });
      await waitFor(() => expect(result.current.problem).not.toBeNull());

      expect(result.current.value).toBe(NONE);
      expect(result.current.problem?.code).toBe(code);
      expect(result.current.loading).toBe(false);
    },
  );

  it("routes a read that throws where one that rejects goes, reported as the fault it is", async () => {
    const fault = new Error("built the request wrong");
    const { result } = readingWith(() => {
      throw fault;
    });

    await waitFor(() => expect(result.current.problem).not.toBeNull());

    expect(result.current.problem).toEqual({ code: BROKEN_HERE });
    expect(result.current.loading).toBe(false);
    expect(vi.mocked(reportCaught).mock.calls).toEqual([[fault]]);
  });

  it("reports a fault in work chained onto the read exactly once, and shows the reader none of what it says", async () => {
    serving({ "GET /api/rows": [["{}", 200]] });
    const { result } = readingWith((signal) =>
      get("/api/rows", signal, (body) => body as { rows: string[] }).then(
        (answer) => answer.rows.map((row) => row.trim()),
      ),
    );

    await waitFor(() => expect(result.current.problem).not.toBeNull());

    expect(result.current.problem).toEqual({ code: BROKEN_HERE });
    expect(result.current.value).toBe(NONE);
    expect(vi.mocked(reportCaught)).toHaveBeenCalledOnce();
    expect(vi.mocked(reportCaught).mock.calls[0]?.[0]).toBeInstanceOf(
      TypeError,
    );
  });

  it("reports nothing of a request no response came back to, and says it never arrived", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>().mockRejectedValue(new TypeError("Failed to fetch")),
    );
    const { result } = readingWith((signal) =>
      get("/api/rows", signal, (body) => body as readonly string[]),
    );

    await waitFor(() => expect(result.current.problem).not.toBeNull());

    expect(result.current.problem).toEqual({ code: NOT_REACHED });
    expect(vi.mocked(reportCaught)).not.toHaveBeenCalled();
  });

  it("reports nothing of a read abandoned when the screen closes", async () => {
    const { unmount } = readingWith(
      (signal) =>
        new Promise((_settle, refuse) => {
          signal.addEventListener("abort", () => refuse(signal.reason), {
            once: true,
          });
        }),
    );

    unmount();
    await act(async () => {});

    expect(vi.mocked(reportCaught)).not.toHaveBeenCalled();
  });

  /**
   * Aborting the request does not stop work a caller chained onto it, so the
   * abort is one half of abandoning a read and the flag beside it is the other.
   */
  it("abandons the request when the screen closes, though work chained onto it runs on", async () => {
    const answer = deferred<readonly string[]>();
    const seen: AbortSignal[] = [];
    const chained = vi.fn();
    const { unmount } = readingWith((signal) => {
      seen.push(signal);
      return answer.promise.then((rows) => {
        chained();
        return rows;
      });
    });
    expect(seen[0]?.aborted).toBe(false);

    unmount();
    await act(async () => {
      answer.settle(["arrived after the screen closed"]);
    });

    expect(seen[0]?.aborted).toBe(true);
    expect(chained).toHaveBeenCalledOnce();
  });

  /**
   * The answer to a read nobody is waiting for any more is not an answer to
   * the read that replaced it. Taking it would put the previous filter's rows
   * back under the new filter's heading, a whole round trip after the reset
   * took them away.
   */
  it("takes no answer from a read the source has moved on from", async () => {
    const first = deferred<readonly string[]>();
    const second = deferred<readonly string[]>();
    const { result, rerender } = readingWith(() => first.promise);
    rerender({ source: () => second.promise });

    await act(async () => {
      first.settle(["what the read left behind answered"]);
    });

    expect(result.current.value).toBe(NONE);
    expect(result.current.loading).toBe(true);

    await act(async () => {
      second.settle(["what the live read answered"]);
    });

    expect(result.current.value).toEqual(["what the live read answered"]);
  });

  /**
   * Abandoning is this side's own doing, so it is a state the screen passes
   * through rather than something the reader is told the server said.
   */
  it("takes no refusal from a read the source has moved on from either", async () => {
    const first = deferred<readonly string[]>();
    const second = deferred<readonly string[]>();
    const { result, rerender } = readingWith(() => first.promise);
    rerender({ source: () => second.promise });

    await act(async () => {
      first.refuse(
        new DOMException("The operation was aborted.", "AbortError"),
      );
    });

    expect(result.current.problem).toBeNull();
    expect(result.current.loading).toBe(true);

    await act(async () => {
      second.settle(["what the live read answered"]);
    });

    expect(result.current.value).toEqual(["what the live read answered"]);
  });

  it("re-reads when the reader asks again, keeping the rows it has until new ones land", async () => {
    const load = vi.fn(() => Promise.resolve(["a row"]));
    const { result } = readingWith(load);
    await waitFor(() => expect(result.current.loading).toBe(false));

    act(() => {
      result.current.reload();
    });

    expect(result.current.loading).toBe(true);
    expect(result.current.value).toEqual(["a row"]);
    await waitFor(() => expect(load).toHaveBeenCalledTimes(2));
  });

  /**
   * Not "replaced a frame later": a commit carrying the previous filter's rows
   * under the new filter's heading is a screen stating one thing and showing
   * another, and the reader has no way to know which of the two is stale.
   */
  it("shows nothing of the previous resource in any commit once the source changes", async () => {
    const first = deferred<readonly string[]>();
    const second = deferred<readonly string[]>();
    const { result, rerender, commits } = readingWith(() => first.promise);
    await act(async () => {
      first.settle(["what the first filter matched"]);
    });
    expect(result.current.value).toEqual(["what the first filter matched"]);

    commits.length = 0;
    rerender({ source: () => second.promise });

    expect(commits.map((each) => each.value)).toEqual([NONE]);
    expect(result.current.loading).toBe(true);
  });

  it("shows nothing of the previous refusal in any commit once the source changes", async () => {
    const second = deferred<readonly string[]>();
    const { result, rerender, commits } = readingWith(() =>
      Promise.reject(
        new RequestFailed({ status: 403, code: "ACT_NOT_PERMITTED" }),
      ),
    );
    await waitFor(() => expect(result.current.problem).not.toBeNull());

    commits.length = 0;
    rerender({ source: () => second.promise });

    expect(commits.map((each) => each.problem)).toEqual([null]);
    expect(result.current.loading).toBe(true);
  });

  /**
   * The reason belonged to the read being replaced, so it goes with it — and a
   * spinner beside a reason about a read no longer in flight is two answers to
   * one question. The rows went with the refusal, so there are none to keep.
   */
  it("puts the previous refusal away the moment the reader asks again", async () => {
    const load = vi
      .fn<Load>()
      .mockResolvedValueOnce(["a row someone is reading"])
      .mockRejectedValueOnce(
        new RequestFailed({ status: 500, code: "INTERNAL" }),
      )
      .mockResolvedValueOnce(["the row the retry found"]);
    const { result } = readingWith(load);
    await waitFor(() => expect(result.current.loading).toBe(false));
    act(() => {
      result.current.reload();
    });
    await waitFor(() => expect(result.current.problem).not.toBeNull());

    act(() => {
      result.current.reload();
    });

    expect(result.current.problem).toBeNull();
    expect(result.current.loading).toBe(true);
    expect(result.current.value).toBe(NONE);

    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(result.current.value).toEqual(["the row the retry found"]);
  });

  /**
   * A caller writing `useResource(load, [])` builds a new empty array every
   * render. Nothing downstream may treat that as a different resource.
   */
  it("reads once for a caller who builds the empty value inline", async () => {
    const load = vi.fn(() => Promise.resolve(["a row"]));
    const { rerender } = renderHook(() => useResource(load, []));
    await waitFor(() => expect(load).toHaveBeenCalledTimes(1));

    rerender();
    rerender();

    expect(load).toHaveBeenCalledTimes(1);
  });

  /**
   * The same caller, at the other moment the empty value is handed back. A
   * blank that changes identity on every source reset is a new object to every
   * memo downstream, and none of them has been shown anything new.
   */
  it("hands back one and the same empty value however often the source changes", () => {
    const pending: Load = () => new Promise(() => {});
    const { result, rerender } = renderHook(
      ({ source }: { source: Load }) => useResource(source, []),
      { initialProps: { source: pending } },
    );
    const blank = result.current.value;

    rerender({ source: () => new Promise(() => {}) });

    expect(result.current.value).toBe(blank);
  });

  /**
   * Handed down through a context by a frame that re-renders on every move: a
   * new object each time is a re-render of every reader for nothing new.
   */
  it("hands back the same resource across renders that settle nothing, and a new one once something does", async () => {
    const load = vi.fn(() => Promise.resolve(["a row"]));
    const { result, rerender } = renderHook(() => useResource(load, NONE));
    await waitFor(() => expect(result.current.loading).toBe(false));
    const settled = result.current;

    rerender();
    const unchanged = result.current;
    act(() => {
      settled.reload();
    });

    expect(unchanged).toBe(settled);
    expect(result.current).not.toBe(settled);
    expect(result.current.loading).toBe(true);
  });
});
