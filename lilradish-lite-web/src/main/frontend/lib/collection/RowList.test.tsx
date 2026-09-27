import { ThemeProvider } from "@mui/material/styles";
import { render, screen } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import type { ReactNode } from "react";
import { MemoryRouter } from "react-router";
import { describe, expect, it, vi } from "vitest";

import { theme } from "../theme/theme";
import { NavigableList, SelectableList } from "./RowList";

/** `theme.palette.primary.main`, as a resolved colour is reported back. */
const CHOSEN_COLOUR = "rgb(11, 87, 208)";

/** `theme.palette.divider`, which `theme.ts` leaves at Material's own value. */
const RESTING_COLOUR = "rgba(0, 0, 0, 0.12)";

interface Row {
  readonly id: string;
  readonly name: string;
}

const ROWS: readonly Row[] = [
  { id: "a3f2", name: "Billing" },
  { id: "b7c1", name: "Payroll" },
];

/**
 * Under the real theme, because `sx` is not type-checked against the property
 * it is written on: an unresolved palette path is emitted verbatim as invalid
 * CSS and dropped, and the row still renders and still reads correctly.
 */
function themed({ children }: { children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

function routed({ children }: { children: ReactNode }) {
  return (
    <MemoryRouter initialEntries={["/system/people"]}>
      <ThemeProvider theme={theme}>{children}</ThemeProvider>
    </MemoryRouter>
  );
}

function navigable(state?: { readonly from: string }) {
  return render(
    <NavigableList
      items={ROWS}
      keyOf={(row) => row.id}
      pathOf={(row) => `/system/people/${row.id}`}
      state={state}
      title={(row) => row.name}
      detail={(row) => `run ${row.id}`}
    />,
    { wrapper: routed },
  );
}

function selectable(selectedId: string | null, onSelect = vi.fn()) {
  return {
    onSelect,
    ...render(
      <SelectableList
        items={ROWS}
        keyOf={(row) => row.id}
        isSelected={(row) => row.id === selectedId}
        onSelect={onSelect}
        title={(row) => row.name}
        detail={(row) => `run ${row.id}`}
      />,
      { wrapper: themed },
    ),
  };
}

describe("NavigableList", () => {
  it("makes every row an anchor, which is what a new tab or a copied address needs", () => {
    navigable();

    const rows = screen.getAllByRole("link");

    expect(rows.map((row) => row.tagName)).toEqual(["A", "A"]);
    expect(rows.map((row) => row.getAttribute("href"))).toEqual([
      "/system/people/a3f2",
      "/system/people/b7c1",
    ]);
    expect(screen.queryAllByRole("button")).toEqual([]);
  });

  it("shows what a row says and what it says about itself", () => {
    navigable();

    expect(screen.getByText("Billing")).toBeDefined();
    expect(screen.getByText("run a3f2")).toBeDefined();
  });

  it("marks none current where nothing says which is, and names no list", () => {
    navigable();

    expect(
      screen
        .getAllByRole("link")
        .map((row) => row.getAttribute("aria-current")),
    ).toEqual([null, null]);
    expect(screen.getByRole("list")).not.toHaveAttribute("aria-labelledby");
  });

  it("marks the row the address names current, and the list by what names it", () => {
    render(
      <>
        <h2 id="named">Groups</h2>
        <NavigableList
          items={ROWS}
          keyOf={(row) => row.id}
          pathOf={(row) => `/system/people/${row.id}`}
          isCurrent={(row) => row.id === "b7c1"}
          labelledBy="named"
          title={(row) => row.name}
          detail={(row) => `run ${row.id}`}
        />
      </>,
      { wrapper: routed },
    );

    expect(
      screen
        .getAllByRole("link")
        .map((row) => row.getAttribute("aria-current")),
    ).toEqual([null, "true"]);
    expect(screen.getByRole("list", { name: "Groups" })).toBeInTheDocument();
  });
});

describe("SelectableList", () => {
  it("makes every row a real button rather than a div wearing the role", () => {
    selectable(null);

    const rows = screen.getAllByRole("button");

    expect(rows.map((row) => row.tagName)).toEqual(["BUTTON", "BUTTON"]);
    expect(screen.queryAllByRole("link")).toEqual([]);
  });

  it("marks the chosen row current and leaves the attribute off every other", () => {
    selectable("a3f2");

    const [chosen, other] = screen.getAllByRole("button");

    expect(chosen.getAttribute("aria-current")).toBe("true");
    expect(other.hasAttribute("aria-current")).toBe(false);
  });

  it("draws the chosen row's edge in the primary colour and every other in the divider", () => {
    selectable("a3f2");

    const [chosen, other] = screen.getAllByRole("button");

    expect(getComputedStyle(chosen).borderColor).toBe(CHOSEN_COLOUR);
    expect(getComputedStyle(other).borderColor).toBe(RESTING_COLOUR);
  });

  /**
   * Every part is a span, and a span flows inline: it is the text root's own
   * styles that put the detail on a line of its own, so the root must be
   * restyled into a span rather than replaced by one — and a block, or the
   * margins it spaces the row with are dropped.
   */
  it("sets a row's detail on its own line under the title, spaced as a row of two lines", () => {
    selectable(null);

    const title = screen.getByText("Billing");
    const detail = screen.getByText("run a3f2");
    const root = title.parentElement!;

    expect(getComputedStyle(title).display).toBe("block");
    expect(getComputedStyle(detail).display).toBe("block");
    expect(root.tagName).toBe("SPAN");
    expect(getComputedStyle(root).display).toBe("block");
    expect(getComputedStyle(root).marginTop).toBe("6px");
  });

  it("hands the whole item back when a row is chosen", async () => {
    const { onSelect } = selectable(null);

    await userEvent.click(screen.getByRole("button", { name: /Payroll/ }));

    expect(onSelect).toHaveBeenCalledWith(ROWS[1]);
    expect(onSelect).toHaveBeenCalledTimes(1);
  });

  /**
   * A button may hold phrasing content only. React builds the tree through DOM
   * APIs, so a flow-content descendant renders and tests happily in the
   * browser; it is the HTML parser, on a server-rendered page, that closes the
   * button early and takes the row's structure with it.
   */
  it("keeps flow content out of the button, which nothing but this would catch", () => {
    selectable("a3f2");

    for (const row of screen.getAllByRole("button")) {
      expect(row.querySelector("div, p")).toBeNull();
    }
  });
});
