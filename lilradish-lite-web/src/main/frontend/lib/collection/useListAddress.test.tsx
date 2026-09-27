import { act, render } from "@testing-library/react";
import { RouterProvider, createMemoryRouter } from "react-router";
import { describe, expect, it } from "vitest";

import type { Ordering } from "./ordering";
import { useListAddress } from "./useListAddress";

type Column = "key" | "name";

const SORTABLE = { key: true, name: true } satisfies Record<Column, true>;

const OPENING: Ordering<Column> = { column: "name", descending: false };

/** The hook on a list's own route, a second entry before it, and what it last answered. */
function onAddress(address: string) {
  const heard: { current: ReturnType<typeof useListAddress<Column>> | null } = {
    current: null,
  };
  function List() {
    heard.current = useListAddress(SORTABLE, OPENING);
    return null;
  }
  const router = createMemoryRouter(
    [
      { path: "/list", element: <List /> },
      { path: "/before", element: null },
    ],
    { initialEntries: ["/before", address], initialIndex: 1 },
  );
  render(<RouterProvider router={router} />);
  return {
    heard: () => heard.current!,
    at: () =>
      `${router.state.location.pathname}${router.state.location.search}`,
    reached: () => router.state.historyAction,
  };
}

describe("useListAddress", () => {
  it("reads the filter and the order the address holds, spelt as the server takes the order", () => {
    const { heard } = onAddress("/list?filter=pa&sort=-key");

    expect(heard().filter).toBe("pa");
    expect(heard().order).toEqual({ column: "key", descending: true });
    expect(heard().sort).toBe("-key");
  });

  it("reads an address holding neither as nothing typed and the order the list opens on", () => {
    const { heard } = onAddress("/list");

    expect(heard().filter).toBe("");
    expect(heard().order).toBe(OPENING);
  });

  it("writes what is typed into the address in place of the entry it was on, keeping the order", async () => {
    const { heard, at, reached } = onAddress("/list?sort=key");

    await act(async () => heard().onFilter("pa"));

    expect(at()).toBe("/list?sort=key&filter=pa");
    expect(reached()).toBe("REPLACE");
  });

  it("takes the filter out of the address once nothing is typed", async () => {
    const { heard, at } = onAddress("/list?filter=pa&sort=key");

    await act(async () => heard().onFilter(""));

    expect(at()).toBe("/list?sort=key");
  });

  it("writes the order pressed into the address in place of the entry it was on, keeping the filter", async () => {
    const { heard, at, reached } = onAddress("/list?filter=pa");

    await act(async () => heard().onOrder({ column: "key", descending: true }));

    expect(at()).toBe("/list?filter=pa&sort=-key");
    expect(reached()).toBe("REPLACE");
  });
});
