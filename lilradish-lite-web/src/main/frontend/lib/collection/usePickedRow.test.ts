import { act, renderHook, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { usePickedRow } from "./usePickedRow";

interface Row {
  readonly key: string;
  readonly from: string;
}

function keyOf(row: Row): string {
  return row.key;
}

/** A read of a row, from a source named so a reading can say which it came from. */
function readFrom(source: string) {
  return vi.fn((key: string) => Promise.resolve<Row>({ key, from: source }));
}

function picking(
  first: string | undefined,
  read: (key: string, signal: AbortSignal) => Promise<Row>,
) {
  return renderHook(
    ({ picked, reading }) => usePickedRow(picked, reading, keyOf),
    { initialProps: { picked: first, reading: read } },
  );
}

describe("usePickedRow", () => {
  it("reads the row picked by its key, and reads nothing where nothing is picked", async () => {
    const read = readFrom("one");
    const hooked = picking("k1", read);

    await waitFor(() =>
      expect(hooked.result.current.read.value).toEqual({
        key: "k1",
        from: "one",
      }),
    );
    hooked.rerender({ picked: undefined, reading: read });

    expect(read.mock.calls.map(([key]) => key)).toEqual(["k1"]);
    expect(hooked.result.current.read.loading).toBe(false);
  });

  /** Read from somewhere else, the same key is another row: a row of one group is not a row of the next. */
  it("reads the row again where it is read from somewhere else, the pick unchanged", async () => {
    const first = readFrom("one");
    const second = readFrom("two");
    const hooked = picking("k1", first);
    await waitFor(() =>
      expect(hooked.result.current.read.value?.from).toBe("one"),
    );

    hooked.rerender({ picked: "k1", reading: second });

    await waitFor(() =>
      expect(hooked.result.current.read.value).toEqual({
        key: "k1",
        from: "two",
      }),
    );
    expect(first).toHaveBeenCalledTimes(1);
    expect(second).toHaveBeenCalledTimes(1);
  });

  /** What a change answered with stands in for reading the row it names, once, by that row's own key. */
  it("takes a seed for the row picked in place of reading it", async () => {
    const read = readFrom("store");
    const hooked = picking(undefined, read);

    act(() => hooked.result.current.seed({ key: "k2", from: "change" }));
    hooked.rerender({ picked: "k2", reading: read });

    await waitFor(() =>
      expect(hooked.result.current.read.value).toEqual({
        key: "k2",
        from: "change",
      }),
    );
    expect(read).not.toHaveBeenCalled();
  });
});
