import { expectConsoleErrors } from "../../testutil/consoleErrors";
import { ThemeProvider } from "@mui/material/styles";
import { tableCellClasses } from "@mui/material/TableCell";
import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import type { ReactNode } from "react";
import { MemoryRouter, RouterProvider, createMemoryRouter } from "react-router";
import { describe, expect, it, vi } from "vitest";

import type { Problem } from "../../api/problem";
import { noticesIn } from "../../testutil/notices";
import type { PagedResource } from "../request/usePagedResource";
import { theme } from "../theme/theme";
import { SortableTable, WholeTable } from "./SortableTable";

/** A 3px bar in act.main, as this environment reports a shadow back: verbatim, the colour not resolved. */
const PICKED_BAR = "inset 3px 0 0 #1c5b81";

interface Item {
  readonly id: string;
  readonly name: string;
  readonly count: number;
}

const AVO: Item = { id: "p1", name: "Avo Lumerace", count: 3 };
const GLINN: Item = { id: "p2", name: "Glinn Havven", count: 1 };
const HAVO: Item = { id: "p3", name: "Havo Lunnet", count: 2 };

type ItemColumn = "name" | "count";

/**
 * Out of order by every column, whichever way: a table that sorted the rows
 * itself, by any column and in either direction, would show it.
 */
const ITEMS: readonly Item[] = [GLINN, AVO, HAVO];

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
    {
      label: "Count",
      cell: (row: Item) => row.count,
      sortKey: "count",
      numeric: true,
    },
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
      order={{ column: "name" as ItemColumn, descending }}
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
    order: { column: "name" as ItemColumn, descending: false },
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

/** Avo's count: a cell of her row that is not its link. */
function offTheLink(): HTMLElement {
  return within(bodyRows()[1]!).getByText("3");
}

describe("SortableTable", () => {
  it("keeps compact linked keys and all headings on one line while sentences take the remaining width", () => {
    render(
      <SortableTable
        label="Invented cases"
        columns={[
          {
            label: "Run",
            cell: (row: Item) => `SUPPORT-${row.count}`,
            compact: true,
          },
          { label: "A sentence", cell: (row: Item) => row.name },
        ]}
        order={{ column: "name", descending: false }}
        onOrder={vi.fn()}
        page={paged({})}
        keyOf={(row) => row.id}
        hrefOf={(row: Item) => `/records/${row.id}`}
        picked={null}
        empty="Nothing here"
      />,
      { wrapper: themed },
    );
    const table = screen.getByRole("table");
    const rows = [...table.querySelectorAll("tr")];
    expect(rows).toHaveLength(4);
    expect(
      [...table.querySelectorAll("thead th")].map(
        (cell) => getComputedStyle(cell).whiteSpace,
      ),
    ).toEqual(["nowrap", "nowrap"]);
    for (const row of rows) {
      expect(row.children[0]).toHaveStyle({
        width: "1%",
        whiteSpace: "nowrap",
        verticalAlign: "top",
      });
      expect(getComputedStyle(row.children[1]!).width).not.toBe("1%");
    }
    expect(screen.getAllByRole("link")).toHaveLength(3);
    expect(
      [...table.querySelectorAll("tbody td:nth-child(2)")].map(
        (cell) => getComputedStyle(cell).whiteSpace,
      ),
    ).toEqual(["", "", ""]);
  });
  it("names the table for what it lists", () => {
    render(tableOf(paged({})), { wrapper: themed });

    expect(screen.getByRole("table")).toHaveAccessibleName("Records");
  });

  /** Figures are compared down a column, which reads only where their places line up. */
  it.each([
    ["rows that open nothing", undefined],
    ["rows that open", null],
  ])(
    "sets a column of figures, its heading with it, at the trailing end in digits of one width, and no other, on %s",
    (_case, picked) => {
      if (picked === undefined) {
        render(tableOf(paged({})), { wrapper: themed });
      } else {
        routed({ picked });
      }

      const columnOf = (at: number) => [
        screen.getAllByRole("columnheader")[at]!,
        ...bodyRows().map((row) => row.cells[at]!),
      ];
      const setAs = (cells: HTMLElement[]) =>
        cells.map((cell) => {
          const drawn = getComputedStyle(cell);
          return `${drawn.textAlign} ${drawn.fontVariantNumeric}`;
        });

      expect(new Set(setAs(columnOf(1)))).toEqual(
        new Set(["right tabular-nums"]),
      );
      expect(
        [0, 2]
          .flatMap(columnOf)
          .map((cell) => getComputedStyle(cell))
          .filter(
            (drawn) =>
              drawn.textAlign === "right" ||
              drawn.fontVariantNumeric === "tabular-nums",
          ),
      ).toEqual([]);
    },
  );

  it("draws every cell, its heading's included, dense", () => {
    render(tableOf(paged({})), { wrapper: themed });

    const cells = [...screen.getByRole("table").querySelectorAll("th, td")];

    expect(cells).toHaveLength(4 * 3);
    for (const cell of cells) {
      const style = getComputedStyle(cell);
      expect({
        height: style.height,
        fontSize: style.fontSize,
        fontWeight: style.fontWeight,
        lineHeight: style.lineHeight,
        verticalAlign: style.verticalAlign,
        borderColor: style.borderBottomColor,
        borderWidth: style.borderBottomWidth,
        padding: style.padding,
      }).toEqual({
        height: cell.tagName === "TH" ? "36px" : "44px",
        fontSize: cell.tagName === "TH" ? "12px" : "14px",
        fontWeight: cell.tagName === "TH" ? "650" : "400",
        lineHeight: "22px",
        verticalAlign: "top",
        borderColor:
          cell.tagName === "TH" ? "rgb(214, 224, 228)" : "rgb(228, 235, 238)",
        borderWidth: cell.tagName === "TH" ? "2px" : "1px",
        padding: cell.tagName === "TH" ? "7px 12px" : "11px 12px",
      });
      expect(getComputedStyle(cell).overflow).not.toBe("hidden");
      expect(getComputedStyle(cell).whiteSpace).toBe(
        cell.tagName === "TH" || (cell as HTMLTableCellElement).cellIndex === 1
          ? "nowrap"
          : "",
      );
    }
    expect(
      cells.filter(
        (cell) => !cell.classList.contains(tableCellClasses.sizeSmall),
      ),
    ).toEqual([]);
    expect(
      cells.filter((cell) =>
        cell.classList.contains(tableCellClasses.sizeMedium),
      ),
    ).toEqual([]);
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
        "Glinn Havven",
        "Avo Lumerace",
        "Havo Lunnet",
      ]);
    },
  );

  /** Columns may be headed alike, and none may take another's place. */
  it.each([false, true])(
    "keeps columns apart that share a heading, across a new page, rows opening %s",
    (opening) => {
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
      const { rerender } = render(alike([AVO]), { wrapper: themed });

      rerender(alike([GLINN]));

      expect([...bodyRows()[0]!.cells].map((cell) => cell.textContent)).toEqual(
        ["Glinn Havven", "p2", "1"],
      );
      expectConsoleErrors();
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
    ["being read", paged({ items: [], loading: true }), "Loading…"],
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

  it("says it reads while a page answered already is read again, the rows in hand still drawn", () => {
    const page = { ...paged({ loading: true }), answered: { items: ITEMS } };

    render(tableOf(page), { wrapper: themed });

    expect(screen.getByRole("status").textContent).toBe("Loading…");
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

    expect(name.querySelector("bdi")?.textContent).toBe(GLINN.name);
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
    expect(links.map((link) => link.getAttribute("aria-label"))).toEqual([
      null,
      null,
      null,
    ]);
    expect(links.map((link) => link.getAttribute("aria-description"))).toEqual([
      null,
      null,
      null,
    ]);
  });

  it("can name a first-cell link separately from its visible detail", () => {
    render(
      <SortableTable
        label="Records"
        columns={[
          {
            label: "Name",
            cell: (row: Item) => (
              <>
                <span>{row.name}</span>
                <small>Count {row.count}</small>
              </>
            ),
          },
        ]}
        order={{ column: "name" as ItemColumn, descending: false }}
        onOrder={vi.fn()}
        page={paged({ items: [AVO] })}
        keyOf={(row) => row.id}
        picked={null}
        hrefOf={(row) => `${HERE}?picked=${row.id}`}
        linkNameOf={(row) => row.name}
        linkDescriptionOf={(row) => `Count ${row.count}`}
        empty={EMPTY}
      />,
      { wrapper: themed },
    );

    const link = screen.getByRole("link", { name: "Avo Lumerace" });
    expect(link).toHaveAccessibleDescription("Count 3");
    expect(link).toHaveTextContent("Avo LumeraceCount 3");
    expect(
      screen.queryByRole("link", { name: "Avo LumeraceCount 3" }),
    ).toBeNull();
  });

  it("keeps the first cell's link to words", () => {
    routed({ picked: null });

    for (const link of screen.getAllByRole("link")) {
      expect(link.querySelector("div, p, a, button")).toBeNull();
    }
  });

  it("keeps another cell's editing separate from the link, without turning editing into navigation", async () => {
    const acted = vi.fn();
    const moves = routed({
      picked: null,
      control: (row) => (
        <>
          <button onClick={() => acted(row.id)}>Edit {row.name}</button>
          <input aria-label={`Name for ${row.name}`} />
        </>
      ),
    });
    const link = screen.getByRole("link", { name: AVO.name });
    const button = screen.getByRole("button", { name: `Edit ${AVO.name}` });
    const input = screen.getByRole("textbox", { name: `Name for ${AVO.name}` });
    expect(link.querySelector("button, input")).toBeNull();
    expect(button.closest("tr")).toBe(link.closest("tr"));
    expect(input.closest("td")).toBe(button.closest("td"));
    expect(button.closest("td")).not.toBe(link.closest("td"));
    await userEvent.click(button);
    await userEvent.click(input);
    await userEvent.type(input, " revised");
    expect(acted).toHaveBeenCalledExactlyOnceWith(AVO.id);
    expect(moves).toEqual([]);
    await act(async () => {
      link.focus();
    });
    await userEvent.keyboard("{Enter}");
    expect(moves).toEqual([`PUSH ${HERE}?picked=p1`]);
    await userEvent.click(offTheLink());
    expect(moves).toEqual([
      `PUSH ${HERE}?picked=p1`,
      `REPLACE ${HERE}?picked=p1`,
    ]);
  });

  it("marks the picked row current on its link and leaves the attribute off every other", () => {
    routed({ picked: "p1" });

    const avo = screen.getByRole("link", { name: "Avo Lumerace" });
    const others = ["Glinn Havven", "Havo Lunnet"].map((name) =>
      screen.getByRole("link", { name }),
    );

    expect(avo.getAttribute("aria-current")).toBe("true");
    expect(others.map((link) => link.hasAttribute("aria-current"))).toEqual([
      false,
      false,
    ]);
  });

  /** A shape as well as a colour: a tint says nothing to a reader who cannot tell it from the rest. */
  it("draws a bar inside the picked row's first cell in the primary colour, on the picked ground, and neither on another row", () => {
    routed({ picked: "p1" });

    const picked = getComputedStyle(firstCellOf("Avo Lumerace")).boxShadow;
    const others = ["Glinn Havven", "Havo Lunnet"].map(
      (name) => getComputedStyle(firstCellOf(name)).boxShadow,
    );
    const groundOf = (name: string) =>
      getComputedStyle(firstCellOf(name).parentElement!).backgroundColor;

    expect(picked).toBe(PICKED_BAR);
    expect(others.filter((shadow) => shadow.includes("inset"))).toEqual([]);
    expect(groundOf("Avo Lumerace")).toBe("rgb(218, 235, 247)");
    expect(["Glinn Havven", "Havo Lunnet"].map(groundOf)).not.toContain(
      "rgb(218, 235, 247)",
    );
    expect(
      bodyRows().filter((row) => row.classList.contains("Mui-selected")),
    ).toEqual([firstCellOf("Avo Lumerace").parentElement]);
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
    ["its link", () => screen.getByRole("link", { name: "Avo Lumerace" })],
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
    ["its link", () => screen.getByRole("link", { name: "Avo Lumerace" })],
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
        order={{ column: "count" as ItemColumn, descending }}
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

  it("draws every cell dense, and a column of figures at the trailing end", () => {
    render(wholeOf(ITEMS), { wrapper: themed });

    const cells = [...screen.getByRole("table").querySelectorAll("th, td")];

    expect(cells).toHaveLength(4 * 3);
    for (const cell of cells) {
      expect(cell).toHaveStyle({
        height: cell.tagName === "TH" ? "36px" : "44px",
        fontSize: cell.tagName === "TH" ? "12px" : "14px",
        lineHeight: "22px",
        verticalAlign: "top",
      });
    }
    expect(
      cells.filter(
        (cell) => !cell.classList.contains(tableCellClasses.sizeSmall),
      ),
    ).toEqual([]);
    expect(
      cells
        .filter((cell) => getComputedStyle(cell).textAlign === "right")
        .map((cell) => (cell as HTMLTableCellElement).cellIndex),
    ).toEqual([1, 1, 1, 1]);
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

  /** Handed addresses, its rows open and the picked one is marked as `SortableTable` marks one. */
  it("opens each row through its first cell's link, marking the picked row current with a bar and no other", () => {
    render(
      <WholeTable
        label="Records"
        columns={columns()}
        order={{ column: "count" as ItemColumn, descending: false }}
        onOrder={vi.fn()}
        rows={ITEMS}
        keyOf={(row) => row.id}
        empty={EMPTY}
        picked="p1"
        hrefOf={(row) => `${HERE}?picked=${row.id}`}
      />,
      { wrapper: themed },
    );

    const links = screen.getAllByRole("link");

    expect(
      links.map((link) => [
        link.getAttribute("href"),
        link.getAttribute("aria-current"),
      ]),
    ).toEqual([
      [`${HERE}?picked=p2`, null],
      [`${HERE}?picked=p1`, "true"],
      [`${HERE}?picked=p3`, null],
    ]);
    expect(links.map((link) => link.closest("td"))).toEqual(
      bodyRows().map((row) => row.cells[0]),
    );
    expect(getComputedStyle(firstCellOf("Avo Lumerace")).boxShadow).toBe(
      PICKED_BAR,
    );
    expect(
      ["Glinn Havven", "Havo Lunnet"].filter((name) =>
        getComputedStyle(firstCellOf(name)).boxShadow.includes("inset"),
      ),
    ).toEqual([]);
    expect(
      bodyRows().map((row) => row.classList.contains("MuiTableRow-hover")),
    ).toEqual([true, true, true]);
  });
});
