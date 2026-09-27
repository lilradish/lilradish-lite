import { ThemeProvider } from "@mui/material/styles";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import type { ReactNode } from "react";
import { MemoryRouter, RouterProvider, createMemoryRouter } from "react-router";
import { describe, expect, it, vi } from "vitest";

import type { Problem } from "../../api/problem";
import { noticesIn } from "../../testutil/notices";
import type { PagedResource } from "../request/usePagedResource";
import { theme } from "../theme/theme";
import { SortableTable, WholeTable } from "./SortableTable";

/**
 * A four-pixel bar in `theme.palette.primary.main`, as this environment
 * reports a shadow back: verbatim, the colour not resolved.
 */
const PICKED_BAR = "inset 4px 0 0 #0b57d0";

interface Item {
  readonly id: string;
  readonly name: string;
  readonly count: number;
}

const ADA: Item = { id: "p1", name: "Ada Lovelace", count: 3 };
const GRACE: Item = { id: "p2", name: "Grace Hopper", count: 1 };
const HEDY: Item = { id: "p3", name: "Hedy Lamarr", count: 2 };

type ItemOrder = "name" | "count";

/**
 * Out of order by every column, whichever way: a table that sorted the rows
 * itself, by any column and in either direction, would show it.
 */
const ITEMS: readonly Item[] = [GRACE, ADA, HEDY];

const EMPTY = "Nothing matches that.";

const HERE = "/records";

/** A control of the caller's, pressed through words inside it where it can hold any. */
function acting(pressed = vi.fn()) {
  return (row: Item) => (
    <button type="button" onClick={() => pressed(row.id)}>
      <span>Act</span>
    </button>
  );
}

/** One column per kind of cell: words, a count, and a control of the caller's. */
function columns(control: (row: Item) => ReactNode = acting()) {
  return [
    { label: "Name", cell: (row: Item) => row.name, sortKey: "name" },
    { label: "Count", cell: (row: Item) => row.count, sortKey: "count" },
    { label: "Actions", cell: control },
  ] as const;
}

function paged(over: Partial<PagedResource<Item>>): PagedResource<Item> {
  return {
    items: ITEMS,
    problem: null,
    loading: false,
    onFirstPage: true,
    hasNext: false,
    next: vi.fn(),
    first: vi.fn(),
    reload: vi.fn(),
    ...over,
  };
}

const REFUSED: Problem = { status: 403, code: "ACT_NOT_PERMITTED" };

function themed({ children }: { readonly children: ReactNode }) {
  return (
    <MemoryRouter>
      <ThemeProvider theme={theme}>{children}</ThemeProvider>
    </MemoryRouter>
  );
}

/**
 * A fresh element on every render: React skips re-rendering a subtree handed
 * the very same element back, and a rerender that does nothing proves nothing.
 */
function tableOf(
  page: PagedResource<Item>,
  onOrder = vi.fn(),
  descending = false,
) {
  return (
    <SortableTable
      label="Records"
      columns={columns()}
      order={{ column: "name" as ItemOrder, descending }}
      onOrder={onOrder}
      page={page}
      keyOf={(row) => row.id}
      empty={EMPTY}
    />
  );
}

/**
 * The table under a router whose history the test reads: whether an address
 * was pushed or replaced, and how many times it moved for one press. Rows open
 * only where `picked` is given, as it is for a caller.
 */
function routed({
  picked,
  control,
}: {
  readonly picked?: string | null;
  readonly control?: (row: Item) => ReactNode;
}) {
  const common = {
    label: "Records",
    columns: columns(control),
    order: { column: "name" as ItemOrder, descending: false },
    onOrder: vi.fn(),
    page: paged({}),
    keyOf: (row: Item) => row.id,
    empty: EMPTY,
  };
  const router = createMemoryRouter(
    [
      {
        path: "*",
        element:
          picked === undefined ? (
            <SortableTable {...common} />
          ) : (
            <SortableTable
              {...common}
              picked={picked}
              hrefOf={(row) => `${HERE}?picked=${row.id}`}
            />
          ),
      },
    ],
    {
      initialEntries: [
        picked === undefined || picked === null
          ? HERE
          : `${HERE}?picked=${picked}`,
      ],
    },
  );
  // Each address moved to and how it was reached, in the order reached. By
  // entry rather than by path: arriving at the same address twice is two.
  const moves: string[] = [];
  let entry = router.state.location.key;
  router.subscribe(({ location, historyAction }) => {
    if (location.key !== entry) {
      entry = location.key;
      moves.push(`${historyAction} ${location.pathname}${location.search}`);
    }
  });
  render(<RouterProvider router={router} />, {
    wrapper: ({ children }) => (
      <ThemeProvider theme={theme}>{children}</ThemeProvider>
    ),
  });
  return moves;
}

function bodyRows(): HTMLTableRowElement[] {
  const [, ...rows] = within(
    screen.getByRole("table"),
  ).getAllByRole<HTMLTableRowElement>("row");
  return rows;
}

function firstCellOf(name: string): HTMLElement {
  const cell = screen.getByText(name).closest("td");
  if (cell === null) {
    throw new Error(`${name} is not in a cell`);
  }
  return cell;
}

/** Ada's count: a cell of her row that is not its link. */
function offTheLink(): HTMLElement {
  return within(bodyRows()[1]!).getByText("3");
}

describe("SortableTable", () => {
  it("names the table for what it lists", () => {
    render(tableOf(paged({})), { wrapper: themed });

    expect(screen.getByRole("table")).toHaveAccessibleName("Records");
  });

  it("makes a sortable header a real button, and leaves one that cannot sort as its words", () => {
    render(tableOf(paged({})), { wrapper: themed });

    const [name, count, actions] = screen.getAllByRole("columnheader");

    for (const header of [name, count]) {
      const control = within(header!).getByRole("button");
      expect(control.tagName).toBe("BUTTON");
      expect(control.getAttribute("type")).toBe("button");
    }
    expect(within(actions!).queryAllByRole("button")).toEqual([]);
    expect(actions!.textContent).toBe("Actions");
  });

  it.each([
    [false, "ascending"],
    [true, "descending"],
  ])(
    "says the order on the sorted header alone, descending %s",
    (descending, said) => {
      render(tableOf(paged({}), vi.fn(), descending), { wrapper: themed });

      const headers = screen.getAllByRole("columnheader");

      expect(headers.map((header) => header.getAttribute("aria-sort"))).toEqual(
        [said, null, null],
      );
    },
  );

  /** Which way it points is the header's to say, and a name that changed with it would be read as another control. */
  it.each([false, true])(
    "names each sort control by its column alone, descending %s",
    (descending) => {
      render(tableOf(paged({}), vi.fn(), descending), { wrapper: themed });

      const [name, count] = screen.getAllByRole("columnheader");

      expect(within(name!).getByRole("button")).toHaveAccessibleName("Name");
      expect(within(count!).getByRole("button")).toHaveAccessibleName("Count");
    },
  );

  it.each([
    [false, "Name", { column: "name", descending: true }],
    [true, "Name", { column: "name", descending: false }],
    [true, "Count", { column: "count", descending: false }],
    [false, "Count", { column: "count", descending: false }],
  ])(
    "asks for the order a press means, sorted by name descending %s and %s pressed, and leaves the rows as they came",
    async (descending, pressed, asked) => {
      const onOrder = vi.fn();
      render(tableOf(paged({}), onOrder, descending), { wrapper: themed });

      await userEvent.click(screen.getByRole("button", { name: pressed }));

      expect(onOrder.mock.calls).toEqual([[asked]]);
      expect(bodyRows().map((row) => row.cells[0]!.textContent)).toEqual([
        "Grace Hopper",
        "Ada Lovelace",
        "Hedy Lamarr",
      ]);
    },
  );

  /** Columns may be headed alike, and none may take another's place. */
  it.each([false, true])(
    "keeps columns apart that share a heading, across a new page, rows opening %s",
    (opening) => {
      const complaints = vi.spyOn(console, "error");
      const common = {
        label: "Records",
        columns: [
          { label: "Name", cell: (row: Item) => row.name },
          { label: "Name", cell: (row: Item) => row.id },
          { label: "Name", cell: (row: Item) => row.count },
        ],
        order: { column: "name", descending: false },
        onOrder: vi.fn(),
        keyOf: (row: Item) => row.id,
        empty: EMPTY,
      };
      const alike = (items: readonly Item[]) =>
        opening ? (
          <SortableTable
            {...common}
            page={paged({ items })}
            picked={null}
            hrefOf={(row) => `${HERE}?picked=${row.id}`}
          />
        ) : (
          <SortableTable {...common} page={paged({ items })} />
        );
      const { rerender } = render(alike([ADA]), { wrapper: themed });

      rerender(alike([GRACE]));

      expect([...bodyRows()[0]!.cells].map((cell) => cell.textContent)).toEqual(
        ["Grace Hopper", "p2", "1"],
      );
      expect(complaints).not.toHaveBeenCalled();
    },
  );

  it.each([
    ["being read", paged({ items: [], loading: true })],
    ["found empty", paged({ items: [] })],
    ["refused", paged({ items: [], problem: REFUSED })],
  ])(
    "keeps every header while the rows are %s, and shows no row",
    (_state, page) => {
      render(tableOf(page), { wrapper: themed });

      expect(
        screen.getAllByRole("columnheader").map((header) => header.textContent),
      ).toEqual(["Name", "Count", "Actions"]);
      expect(bodyRows()).toEqual([]);
    },
  );

  /**
   * A refusal is the caller's to show, and "nothing matches" beside it would be
   * a claim nobody made; nor is a later page coming back empty a claim about
   * the collection.
   */
  it.each([
    ["being read", paged({ items: [], loading: true }), "Still reading…"],
    ["found empty", paged({ items: [] }), EMPTY],
    [
      "found empty past the first page",
      paged({ items: [], onFirstPage: false }),
      "",
    ],
    ["refused", paged({ items: [], problem: REFUSED }), ""],
    ["read", paged({}), ""],
  ])(
    "says what there is to say while the rows are %s",
    (_state, page, said) => {
      render(tableOf(page), { wrapper: themed });

      expect(screen.getByRole("status").textContent).toBe(said);
    },
  );

  /** A page in hand read again, as it is kept up, is news of nothing; a first read is not. */
  it.each([
    ["nothing has been answered yet", null, ITEMS, "Still reading…"],
    ["a page has been answered already", { items: ITEMS }, ITEMS, ""],
    [
      "a page has been answered already, and held nothing",
      { items: [] },
      [],
      EMPTY,
    ],
  ])(
    "says, where asked to keep quiet while a page is in hand, that it reads only while %s",
    (_case, answered, items, said) => {
      const page = { ...paged({ loading: true, items }), answered };

      render(
        <SortableTable
          label="Records"
          columns={columns()}
          order={{ column: "name" as ItemOrder, descending: false }}
          onOrder={vi.fn()}
          page={page}
          keyOf={(row) => row.id}
          empty={EMPTY}
          quietWhileAnswered
        />,
        { wrapper: themed },
      );

      expect(screen.getByRole("status").textContent).toBe(said);
    },
  );

  it("says it reads while a page answered already is read again, where not asked to keep quiet", () => {
    const page = { ...paged({ loading: true }), answered: { items: ITEMS } };

    render(tableOf(page), { wrapper: themed });

    expect(screen.getByRole("status").textContent).toBe("Still reading…");
    expect(bodyRows()).toHaveLength(ITEMS.length);
  });

  /**
   * A live region is heard when what is in it changes, and one put in along
   * with its words has nothing to have changed from. Outside the table,
   * because a status role on a cell would take the cell's role away.
   */
  /** Nothing found is a hint about what to do next; a wait is a state, and no notice. */
  it.each([
    ["found empty", paged({ items: [] }), [{ severity: "info", words: EMPTY }]],
    ["being read", paged({ items: [], loading: true }), []],
  ])(
    "says what it has to say while the rows are %s in the notice that fits",
    (_state, page, notices) => {
      render(tableOf(page), { wrapper: themed });

      expect(noticesIn(screen.getByRole("status"))).toEqual(notices);
      expect(screen.queryByRole("alert")).toBeNull();
    },
  );

  it("says it from one region outside the table, there before any of it", () => {
    const { rerender } = render(tableOf(paged({ items: [], loading: true })), {
      wrapper: themed,
    });
    const region = screen.getByRole("status");

    rerender(tableOf(paged({ items: [] })));
    const emptied = screen.getByRole("status");
    rerender(tableOf(paged({})));

    expect(region.closest("table")).toBeNull();
    expect(emptied).toBe(region);
    expect(screen.getByRole("status")).toBe(region);
  });

  /** What is and is not isolated is `isolated`'s to say; here, only that the cells go through it. */
  it.each([
    ["rows that open nothing", undefined],
    ["rows that open through a link", null],
  ] as const)("isolates the words in a cell, in %s", (_case, picked) => {
    routed({ picked });

    const name = bodyRows()[0]!.cells[0]!;

    expect(name.querySelector("bdi")?.textContent).toBe(GRACE.name);
  });

  it("opens each row through a link to its own address, from its first cell", () => {
    routed({ picked: null });

    const links = screen.getAllByRole("link");

    expect(links.map((link) => link.getAttribute("href"))).toEqual([
      `${HERE}?picked=p2`,
      `${HERE}?picked=p1`,
      `${HERE}?picked=p3`,
    ]);
    expect(links.map((link) => link.closest("td"))).toEqual(
      bodyRows().map((row) => row.cells[0]),
    );
  });

  /**
   * A link may hold phrasing content only and nothing interactive. The DOM
   * takes anything, so only the parser of a served page would ever object.
   */
  it("keeps the first cell's link to words", () => {
    routed({ picked: null });

    for (const link of screen.getAllByRole("link")) {
      expect(link.querySelector("div, p, a, button")).toBeNull();
    }
  });

  it("marks the picked row current on its link and leaves the attribute off every other", () => {
    routed({ picked: "p1" });

    const ada = screen.getByRole("link", { name: "Ada Lovelace" });
    const others = ["Grace Hopper", "Hedy Lamarr"].map((name) =>
      screen.getByRole("link", { name }),
    );

    expect(ada.getAttribute("aria-current")).toBe("true");
    expect(others.map((link) => link.hasAttribute("aria-current"))).toEqual([
      false,
      false,
    ]);
  });

  /** A shape as well as a colour: a tint says nothing to a reader who cannot tell it from the rest. */
  it("draws a bar inside the picked row's first cell in the primary colour and in no other", () => {
    routed({ picked: "p1" });

    const picked = getComputedStyle(firstCellOf("Ada Lovelace")).boxShadow;
    const others = ["Grace Hopper", "Hedy Lamarr"].map(
      (name) => getComputedStyle(firstCellOf(name)).boxShadow,
    );

    expect(picked).toBe(PICKED_BAR);
    expect(others.filter((shadow) => shadow.includes("inset"))).toEqual([]);
  });

  /** The address can name a row on a page other than this one; nothing here goes looking for it. */
  it("marks nothing when the picked row is not among these", () => {
    routed({ picked: "p9" });

    expect(
      screen
        .getAllByRole("link")
        .filter((link) => link.hasAttribute("aria-current")),
    ).toEqual([]);
    expect(
      bodyRows().filter((row) =>
        getComputedStyle(row.cells[0]!).boxShadow.includes("inset"),
      ),
    ).toEqual([]);
    expect(bodyRows()).toHaveLength(3);
  });

  /** A pick is somewhere to come back from, so moving to another row is pushed. */
  it.each([
    ["its link", () => screen.getByRole("link", { name: "Ada Lovelace" })],
    ["a cell that is not its link", offTheLink],
  ])(
    "goes to another row's address once, pushed, when %s is pressed",
    async (_where, target) => {
      const moves = routed({ picked: "p2" });

      await userEvent.click(target());

      expect(moves).toEqual([`PUSH ${HERE}?picked=p1`]);
    },
  );

  /** Arriving again where the reader already is adds no step to go back through. */
  it.each([
    ["its link", () => screen.getByRole("link", { name: "Ada Lovelace" })],
    ["a cell that is not its link", offTheLink],
  ])(
    "takes the picked row's own address in place when %s is pressed",
    async (_where, target) => {
      const moves = routed({ picked: "p1" });

      await userEvent.click(target());

      expect(moves).toEqual([`REPLACE ${HERE}?picked=p1`]);
    },
  );

  /** A modified press asks the browser for somewhere new to open, which only a link can have it do. */
  it.each([
    ["Ctrl", { ctrlKey: true }],
    ["Meta", { metaKey: true }],
    ["Shift", { shiftKey: true }],
    ["Alt", { altKey: true }],
    ["the middle button", { button: 1 }],
  ])(
    "goes nowhere when a cell that is not its link is pressed with %s",
    (_how, press) => {
      const moves = routed({ picked: "p2" });

      fireEvent.click(offTheLink(), press);

      expect(moves).toEqual([]);
    },
  );

  it.each([
    [
      "a link",
      (pressed: () => void) => (
        <a
          href="#elsewhere"
          onClick={(event) => {
            event.preventDefault();
            pressed();
          }}
        >
          <span data-testid="control">Act</span>
        </a>
      ),
    ],
    [
      "a button",
      (pressed: () => void) => (
        <button type="button" onClick={pressed}>
          <span data-testid="control">Act</span>
        </button>
      ),
    ],
    [
      "an input",
      (pressed: () => void) => (
        <input aria-label="Act" data-testid="control" onClick={pressed} />
      ),
    ],
    [
      "a select",
      (pressed: () => void) => (
        <select aria-label="Act" data-testid="control" onClick={pressed} />
      ),
    ],
    [
      "a text area",
      (pressed: () => void) => (
        <textarea aria-label="Act" data-testid="control" onClick={pressed} />
      ),
    ],
    [
      "a label",
      (pressed: () => void) => (
        <label onClick={pressed}>
          <span data-testid="control">Act</span>
        </label>
      ),
    ],
    [
      "a summary",
      (pressed: () => void) => (
        <details>
          <summary onClick={pressed}>
            <span data-testid="control">Act</span>
          </summary>
        </details>
      ),
    ],
    [
      "an element in the role of a button",
      (pressed: () => void) => (
        <span role="button" tabIndex={0} onClick={pressed}>
          <span data-testid="control">Act</span>
        </span>
      ),
    ],
    [
      "an element in the role of a link",
      (pressed: () => void) => (
        <span role="link" tabIndex={0} onClick={pressed}>
          <span data-testid="control">Act</span>
        </span>
      ),
    ],
  ])(
    "leaves a press on %s of the caller's to it alone",
    async (_what, control) => {
      const pressed = vi.fn();
      const moves = routed({ picked: null, control: () => control(pressed) });

      await userEvent.click(within(bodyRows()[1]!).getByTestId("control"));

      expect(pressed).toHaveBeenCalledOnce();
      expect(moves).toEqual([]);
    },
  );

  it("goes nowhere at the end of a drag that selected words in a row", () => {
    const moves = routed({ picked: null });
    const count = offTheLink();
    const words = document.createRange();
    words.selectNodeContents(count);
    document.getSelection()?.removeAllRanges();
    document.getSelection()?.addRange(words);

    fireEvent.click(count);

    expect(moves).toEqual([]);
  });

  it("offers nothing to press on rows that open nothing", async () => {
    const moves = routed({});
    const [first] = bodyRows();

    await userEvent.click(within(first!).getByText("1"));

    expect(moves).toEqual([]);
    expect(screen.queryAllByRole("link")).toEqual([]);
    expect(getComputedStyle(first!).cursor).not.toBe("pointer");
    expect(first!.classList.contains("MuiTableRow-hover")).toBe(false);
  });

  it("offers a pointer and a hover on rows that open", () => {
    routed({ picked: null });

    for (const row of bodyRows()) {
      expect(getComputedStyle(row).cursor).toBe("pointer");
      expect(row.classList.contains("MuiTableRow-hover")).toBe(true);
    }
  });

  it("puts the way through the collection below the table", () => {
    render(tableOf(paged({ hasNext: true })), { wrapper: themed });

    const table = screen.getByRole("table");
    const next = screen.getByRole("button", { name: "Next page" });

    expect(
      table.compareDocumentPosition(next) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).not.toBe(0);
    expect(table.contains(next)).toBe(false);
  });
});

describe("WholeTable", () => {
  /** A fresh element on every render, for the reason `tableOf` gives. */
  function wholeOf(
    rows: readonly Item[],
    onOrder = vi.fn(),
    descending = false,
  ) {
    return (
      <WholeTable
        label="Records"
        columns={columns()}
        order={{ column: "count" as ItemOrder, descending }}
        onOrder={onOrder}
        rows={rows}
        keyOf={(row) => row.id}
        empty={EMPTY}
      />
    );
  }

  it("names the table, and draws the rows in the order handed in, each cell set apart", () => {
    render(wholeOf(ITEMS), { wrapper: themed });

    expect(screen.getByRole("table")).toHaveAccessibleName("Records");
    expect(bodyRows().map((row) => row.cells[0]!.innerHTML)).toEqual(
      ITEMS.map((item) => `<bdi>${item.name}</bdi>`),
    );
    expect(screen.queryByText(EMPTY)).toBeNull();
  });

  it.each([
    [false, "Count", { column: "count", descending: true }],
    [true, "Count", { column: "count", descending: false }],
    [false, "Name", { column: "name", descending: false }],
  ])(
    "heads it as a table sorted by count descending %s is headed, and asks for the order pressing %s means",
    async (descending, pressed, asked) => {
      const onOrder = vi.fn();
      render(wholeOf(ITEMS, onOrder, descending), { wrapper: themed });

      await userEvent.click(screen.getByRole("button", { name: pressed }));

      expect(onOrder.mock.calls).toEqual([[asked]]);
      expect(
        screen
          .getAllByRole("columnheader")
          .map((header) => header.getAttribute("aria-sort")),
      ).toEqual([null, descending ? "descending" : "ascending", null]);
      expect(bodyRows().map((row) => row.cells[0]!.textContent)).toEqual(
        ITEMS.map((item) => item.name),
      );
    },
  );

  it("says there are no rows in the one row of its body, across every column, and keeps its head", () => {
    render(wholeOf([]), { wrapper: themed });

    const [only, ...more] = bodyRows();

    expect(more).toEqual([]);
    expect(only!.cells).toHaveLength(1);
    expect(only!.cells[0]!.textContent).toBe(EMPTY);
    expect(only!.cells[0]!.getAttribute("colspan")).toBe("3");
    expect(screen.getAllByRole("columnheader")).toHaveLength(3);
    expect(screen.queryByRole("status")).toBeNull();
  });

  it("opens nothing from a row, and offers no way through the collection", async () => {
    render(wholeOf(ITEMS), { wrapper: themed });
    const [first] = bodyRows();

    await userEvent.click(within(first!).getByText("1"));

    expect(screen.queryAllByRole("link")).toEqual([]);
    expect(first!.classList.contains("MuiTableRow-hover")).toBe(false);
    expect(screen.queryByRole("button", { name: "Next page" })).toBeNull();
  });
});
