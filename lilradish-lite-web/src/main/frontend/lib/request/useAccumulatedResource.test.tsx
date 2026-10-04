import { StrictMode, type ReactNode } from "react";
import { act, renderHook, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { servedAmong } from "../../api/problem";
import { requestsTo, serving } from "../../testutil/answering";
import { cursorLoader } from "./cursorLoader";
import { useAccumulatedResource } from "./useAccumulatedResource";

const NO_VIEW = new Set(["NO_VIEW"]);

function closes(problem: Parameters<typeof servedAmong>[0]): boolean {
  return servedAmong(problem, NO_VIEW);
}

function loadFor(filter: string) {
  return cursorLoader(
    "/api/offered",
    (row): string | null => (typeof row === "string" ? row : null),
    new URLSearchParams({ filter }),
  );
}

function pendingReply() {
  let answer!: (reply: readonly [string, number]) => void;
  const reply = new Promise<readonly [string, number]>((resolve) => {
    answer = resolve;
  });
  return { reply, answer };
}

describe("useAccumulatedResource", () => {
  it("starts with one read and adds one ordered page per press, with nothing beyond the end", async () => {
    const sent = serving({
      "GET /api/offered": [
        ['{"items":["first"],"nextCursor":"after-first"}', 200],
        ['{"items":["second"]}', 200],
      ],
    });
    const load = loadFor("Demo Sample");
    const { result } = renderHook(() => useAccumulatedResource(load, closes));

    await waitFor(() => expect(result.current.items).toEqual(["first"]));
    expect(requestsTo(sent)).toEqual(["GET /api/offered?filter=Demo+Sample"]);
    expect(result.current.hasNext).toBe(true);

    act(() => {
      result.current.next();
      result.current.next();
    });
    await waitFor(() =>
      expect(result.current.items).toEqual(["first", "second"]),
    );
    expect(requestsTo(sent)).toEqual([
      "GET /api/offered?filter=Demo+Sample",
      "GET /api/offered?filter=Demo+Sample&cursor=after-first",
    ]);
    expect(result.current.hasNext).toBe(false);
    expect(result.current.problem).toBeNull();

    act(() => result.current.next());
    expect(sent.mock.calls).toHaveLength(2);
  });

  it("keeps accepted rows after an ordinary failure and retries only that cursor", async () => {
    const sent = serving({
      "GET /api/offered": [
        ['{"items":["first"],"nextCursor":"after-first"}', 200],
        ['{"code":"SERVER_ERROR"}', 500],
        ['{"items":["second"]}', 200],
      ],
    });
    const load = loadFor("Demo Sample");
    const { result } = renderHook(() => useAccumulatedResource(load, closes));
    await waitFor(() => expect(result.current.items).toEqual(["first"]));

    act(() => result.current.next());
    await waitFor(() =>
      expect(result.current.problem?.code).toBe("SERVER_ERROR"),
    );
    expect(result.current.items).toEqual(["first"]);
    expect(result.current.terminal).toBe(false);
    expect(sent.mock.calls).toHaveLength(2);

    act(() => result.current.retry());
    await waitFor(() =>
      expect(result.current.items).toEqual(["first", "second"]),
    );
    expect(requestsTo(sent)).toEqual([
      "GET /api/offered?filter=Demo+Sample",
      "GET /api/offered?filter=Demo+Sample&cursor=after-first",
      "GET /api/offered?filter=Demo+Sample&cursor=after-first",
    ]);
    expect(result.current.problem).toBeNull();
  });

  it("reloads from the first page instead of retrying a failed later cursor", async () => {
    const sent = serving({
      "GET /api/offered": [
        ['{"items":["first"],"nextCursor":"after-first"}', 200],
        ['{"code":"SERVER_ERROR"}', 500],
        ['{"items":["new first"]}', 200],
      ],
    });
    const load = loadFor("Demo Sample");
    const { result } = renderHook(() => useAccumulatedResource(load, closes));
    await waitFor(() => expect(result.current.items).toEqual(["first"]));
    act(() => result.current.next());
    await waitFor(() =>
      expect(result.current.problem?.code).toBe("SERVER_ERROR"),
    );

    act(() => result.current.reload());
    expect(result.current.items).toEqual([]);
    await waitFor(() => expect(result.current.items).toEqual(["new first"]));
    expect(requestsTo(sent).at(-1)).toBe("GET /api/offered?filter=Demo+Sample");
    expect(result.current.problem).toBeNull();
  });

  it("abandons a pending later page before reloading the first one", async () => {
    const late = pendingReply();
    const sent = serving({
      "GET /api/offered": [
        ['{"items":["first"],"nextCursor":"after-first"}', 200],
        late.reply,
        ['{"items":["new first"]}', 200],
      ],
    });
    const load = loadFor("Demo Sample");
    const { result } = renderHook(() => useAccumulatedResource(load, closes));
    await waitFor(() => expect(result.current.items).toEqual(["first"]));
    act(() => result.current.next());
    await waitFor(() => expect(sent.mock.calls).toHaveLength(2));
    const abandoned = sent.mock.calls[1]?.[1]?.signal;

    act(() => result.current.reload());
    expect(abandoned?.aborted).toBe(true);
    expect(result.current.items).toEqual([]);
    await waitFor(() => expect(result.current.items).toEqual(["new first"]));
    await act(async () => late.answer(['{"items":["old later"]}', 200]));
    expect(result.current.items).toEqual(["new first"]);
    expect(requestsTo(sent).at(-1)).toBe("GET /api/offered?filter=Demo+Sample");
  });

  it("takes all prior rows away on a served loss of authority and shuts the way on", async () => {
    const sent = serving({
      "GET /api/offered": [
        ['{"items":["first"],"nextCursor":"after-first"}', 200],
        ['{"code":"NO_VIEW"}', 403],
      ],
    });
    const load = loadFor("Demo Sample");
    const { result } = renderHook(() => useAccumulatedResource(load, closes));
    await waitFor(() => expect(result.current.items).toEqual(["first"]));

    act(() => result.current.next());
    await waitFor(() => expect(result.current.terminal).toBe(true));
    expect(result.current.items).toEqual([]);
    expect(result.current.hasNext).toBe(false);
    expect(result.current.problem?.code).toBe("NO_VIEW");
    act(() => {
      result.current.retry();
      result.current.reload();
      result.current.next();
    });
    expect(sent.mock.calls).toHaveLength(2);
  });

  it("clears rows in the render of a changed source and ignores its late answer", async () => {
    const late = pendingReply();
    const sent = vi.fn<typeof fetch>().mockImplementation((input) => {
      const address = new URL(String(input), "http://reader.test");
      if (address.searchParams.get("filter") === "old") {
        if (address.searchParams.has("cursor")) {
          return late.reply.then(
            ([body, status]) => new Response(body, { status }),
          );
        }
        return Promise.resolve(
          new Response('{"items":["old"],"nextCursor":"after-old"}'),
        );
      }
      return Promise.resolve(new Response('{"items":["new"]}'));
    });
    vi.stubGlobal("fetch", sent);
    const old = loadFor("old");
    const newer = loadFor("new");
    const view = renderHook(
      ({ source }: { source: typeof old }) =>
        useAccumulatedResource(source, closes),
      { initialProps: { source: old } },
    );
    await waitFor(() => expect(view.result.current.items).toEqual(["old"]));
    act(() => view.result.current.next());
    await waitFor(() => expect(sent.mock.calls).toHaveLength(2));
    const abandoned = sent.mock.calls[1]?.[1]?.signal;

    view.rerender({ source: newer });
    expect(view.result.current.items).toEqual([]);
    await waitFor(() => expect(view.result.current.items).toEqual(["new"]));
    expect(abandoned?.aborted).toBe(true);
    await act(async () => late.answer(['{"items":["late old"]}', 200]));
    expect(view.result.current.items).toEqual(["new"]);
    expect(sent.mock.calls).toHaveLength(3);
  });

  it("does not append an accepted page twice when effects are replayed", async () => {
    const sent = serving({
      "GET /api/offered": [['{"items":["once"]}', 200]],
    });
    const load = loadFor("Demo Sample");
    const wrapper = ({ children }: { readonly children: ReactNode }) => (
      <StrictMode>{children}</StrictMode>
    );
    const { result } = renderHook(() => useAccumulatedResource(load, closes), {
      wrapper,
    });

    await waitFor(() => expect(result.current.items).toEqual(["once"]));
    expect(result.current.items).not.toEqual(["once", "once"]);
    expect(result.current.hasNext).toBe(false);
    expect(sent.mock.calls.length).toBeGreaterThanOrEqual(1);
  });
});
