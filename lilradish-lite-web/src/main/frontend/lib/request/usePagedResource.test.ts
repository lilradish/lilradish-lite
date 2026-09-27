import { act, renderHook, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { RequestFailed } from "../../api/problem";
import type { Page } from "./page";
import { usePagedResource } from "./usePagedResource";

type Load = (
  cursor: string | null,
  signal: AbortSignal,
) => Promise<Page<string>>;

/**
 * A collection of two pages under one filter, recording the cursor it was asked
 * for so a test can state what reached the server rather than what the hook
 * kept to itself.
 */
function collection(filter: string, asked: [string, string | null][]): Load {
  return (cursor) => {
    asked.push([filter, cursor]);
    return Promise.resolve(
      cursor === null
        ? { items: [`${filter} row one`], nextCursor: `${filter}-after-one` }
        : { items: [`${filter} row two`] },
    );
  };
}

function reading(load: Load) {
  return renderHook(
    ({ source }: { source: Load }) => usePagedResource(source),
    { initialProps: { source: load } },
  );
}

async function advancedPastTheFirstPage(load: Load) {
  const view = reading(load);
  await waitFor(() => expect(view.result.current.hasNext).toBe(true));
  act(() => {
    view.result.current.next();
  });
  await waitFor(() => expect(view.result.current.onFirstPage).toBe(false));
  return view;
}

describe("usePagedResource", () => {
  it("shows the page a collection opens on, and says there is more", async () => {
    const { result } = reading(collection("prompt", []));

    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(result.current.items).toEqual(["prompt row one"]);
    expect(result.current.onFirstPage).toBe(true);
    expect(result.current.hasNext).toBe(true);
    expect(result.current.problem).toBeNull();
  });

  it("asks for what follows with the cursor the page in hand carried", async () => {
    const asked: [string, string | null][] = [];
    const { result } = await advancedPastTheFirstPage(
      collection("prompt", asked),
    );

    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(asked).toEqual([
      ["prompt", null],
      ["prompt", "prompt-after-one"],
    ]);
    expect(result.current.items).toEqual(["prompt row two"]);
    expect(result.current.hasNext).toBe(false);
  });

  /** A cursor is the only thing that says there is more, and the last page has none. */
  it("asks for nothing beyond the last page", async () => {
    const asked: [string, string | null][] = [];
    const { result } = await advancedPastTheFirstPage(
      collection("prompt", asked),
    );
    await waitFor(() => expect(result.current.hasNext).toBe(false));

    act(() => {
      result.current.next();
    });

    expect(asked).toHaveLength(2);
    expect(result.current.items).toEqual(["prompt row two"]);
  });

  /**
   * The type says `string | undefined`; a document that wrote `null` says
   * otherwise, and only the runtime is in a position to notice. Read as a
   * further page it would be a way on that is offered at the end of every
   * collection and that quietly returns to the start when taken.
   */
  it("reads a cursor written as null as the end of the collection", async () => {
    // The cast is the subject, not a shortcut around one: it builds the answer
    // whose type says `string | undefined` and whose body says `null`, which is
    // the only way to state a response the type system will not admit exists.
    const wroteNull = () =>
      Promise.resolve({
        items: ["the last row"],
        nextCursor: null,
      } as unknown as Page<string>);
    const { result } = reading(wroteNull);

    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(result.current.hasNext).toBe(false);
    expect(result.current.items).toEqual(["the last row"]);
  });

  it("returns to the first page when asked, without waiting for the filter to change", async () => {
    const asked: [string, string | null][] = [];
    const { result } = await advancedPastTheFirstPage(
      collection("prompt", asked),
    );

    act(() => {
      result.current.first();
    });
    await waitFor(() => expect(result.current.onFirstPage).toBe(true));

    expect(asked).toEqual([
      ["prompt", null],
      ["prompt", "prompt-after-one"],
      ["prompt", null],
    ]);
  });

  /**
   * A cursor belongs to the query that minted it; carried to another, it
   * names a position that query never had.
   */
  it("asks a changed filter for its first page rather than the previous filter's cursor", async () => {
    const asked: [string, string | null][] = [];
    const view = await advancedPastTheFirstPage(collection("prompt", asked));

    view.rerender({ source: collection("tool", asked) });
    await waitFor(() =>
      expect(asked.some(([filter]) => filter === "tool")).toBe(true),
    );

    expect(asked.filter(([filter]) => filter === "tool")).toEqual([
      ["tool", null],
    ]);
    expect(view.result.current.onFirstPage).toBe(true);
  });

  /**
   * The reset must not be so eager that an unrelated re-render undoes paging
   * forward: the page in hand is the page that was asked for.
   */
  it("keeps the cursor while the filter is the one it was issued under", async () => {
    const asked: [string, string | null][] = [];
    const load = collection("prompt", asked);
    const view = await advancedPastTheFirstPage(load);

    view.rerender({ source: load });

    expect(asked).toEqual([
      ["prompt", null],
      ["prompt", "prompt-after-one"],
    ]);
    expect(view.result.current.onFirstPage).toBe(false);
  });

  /**
   * A refused page leaves the reader somewhere with nothing to show and no way
   * on, and there is exactly one way out of that: a cursor reaches forward or it
   * reaches the start, and the start is the only one always available.
   */
  it("leaves the way back to the start open when the next page is refused", async () => {
    const load = vi
      .fn<Load>()
      .mockResolvedValueOnce({
        items: ["a row someone is reading"],
        nextCursor: "after-one",
      })
      .mockRejectedValueOnce(
        new RequestFailed({ status: 403, code: "SOME_REFUSAL" }),
      )
      .mockResolvedValueOnce({ items: ["the first page, read again"] });
    const { result } = await advancedPastTheFirstPage(load);
    await waitFor(() => expect(result.current.problem).not.toBeNull());
    expect(result.current.onFirstPage).toBe(false);

    act(() => {
      result.current.first();
    });
    await waitFor(() =>
      expect(result.current.items).toEqual(["the first page, read again"]),
    );

    expect(result.current.onFirstPage).toBe(true);
    expect(result.current.problem).toBeNull();
  });

  it("takes the page in hand down when a re-read of that same page is refused", async () => {
    const load = vi
      .fn<Load>()
      .mockResolvedValueOnce({ items: ["a row someone is reading"] })
      .mockRejectedValueOnce(
        new RequestFailed({ status: 403, code: "OTHER_REFUSAL" }),
      );
    const { result } = reading(load);
    await waitFor(() => expect(result.current.loading).toBe(false));

    act(() => {
      result.current.reload();
    });
    await waitFor(() => expect(result.current.problem).not.toBeNull());

    expect(result.current.items).toEqual([]);
    expect(result.current.problem?.code).toBe("OTHER_REFUSAL");
    expect(result.current.loading).toBe(false);
  });

  it("re-reads the page in hand rather than the first one", async () => {
    const asked: [string, string | null][] = [];
    const { result } = await advancedPastTheFirstPage(
      collection("prompt", asked),
    );

    act(() => {
      result.current.reload();
    });
    await waitFor(() => expect(asked).toHaveLength(3));

    expect(asked[2]).toEqual(["prompt", "prompt-after-one"]);
    expect(result.current.onFirstPage).toBe(false);
  });

  /** What was on it has gone since, and the rest of the collection is still there. */
  it("goes back to the first page when the page in hand is read again and comes back empty", async () => {
    const asked: (string | null)[] = [];
    let emptied = false;
    const load: Load = (cursor) => {
      asked.push(cursor);
      return Promise.resolve(
        cursor === null
          ? { items: ["row one"], nextCursor: "after-one" }
          : { items: emptied ? [] : ["row two"] },
      );
    };
    const { result } = await advancedPastTheFirstPage(load);
    await waitFor(() => expect(result.current.items).toEqual(["row two"]));
    emptied = true;

    act(() => {
      result.current.reload();
    });

    await waitFor(() => expect(result.current.items).toEqual(["row one"]));
    expect(asked).toEqual([null, "after-one", "after-one", null]);
    expect(result.current.onFirstPage).toBe(true);
  });

  it("stays on the first page when it comes back empty, the collection being empty", async () => {
    const asked: (string | null)[] = [];
    const { result } = reading((cursor) => {
      asked.push(cursor);
      return Promise.resolve({ items: [] });
    });

    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(result.current.items).toEqual([]);
    expect(result.current.onFirstPage).toBe(true);
    expect(asked).toEqual([null]);
  });

  it("hands over the page last answered whole, with what it says beside its rows, and none before one has", async () => {
    const load = () => Promise.resolve({ items: ["a row"], tone: "calm" });
    const { result } = renderHook(() => usePagedResource(load));
    const before = result.current.answered;

    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(before).toBeNull();
    expect(result.current.answered).toEqual({ items: ["a row"], tone: "calm" });
  });

  it("hands over no page once the page in hand is refused, as it holds no rows", async () => {
    const load = vi
      .fn<(cursor: string | null) => Promise<Page<string> & { tone: string }>>()
      .mockResolvedValueOnce({ items: ["a row"], tone: "calm" })
      .mockRejectedValueOnce(
        new RequestFailed({ status: 403, code: "SOME_REFUSAL" }),
      );
    const { result } = renderHook(() => usePagedResource(load));
    await waitFor(() => expect(result.current.answered).not.toBeNull());

    act(() => {
      result.current.reload();
    });
    await waitFor(() => expect(result.current.problem).not.toBeNull());

    expect(result.current.answered).toBeNull();
    expect(result.current.items).toEqual([]);
  });
});
