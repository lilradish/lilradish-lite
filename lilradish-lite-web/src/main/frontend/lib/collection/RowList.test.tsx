import { ThemeProvider } from "@mui/material/styles";
import { render, screen } from "@testing-library/react";
import type { ReactNode } from "react";
import { MemoryRouter } from "react-router";
import { describe, expect, it } from "vitest";

import { theme } from "../theme/theme";
import { NavigableList } from "./RowList";

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
function routed({ children }: { children: ReactNode }) {
  return (
    <MemoryRouter initialEntries={["/system/people"]}>
      <ThemeProvider theme={theme}>{children}</ThemeProvider>
    </MemoryRouter>
  );
}

function navigable() {
  return render(
    <NavigableList
      items={ROWS}
      keyOf={(row) => row.id}
      pathOf={(row) => `/system/people/${row.id}`}
      title={(row) => row.name}
      detail={(row) => `run ${row.id}`}
    />,
    { wrapper: routed },
  );
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

  /** A title is a name, read as words, so never in the face an identifier is read character by character in. */
  it("sets a row's title and its detail in the face of the words around them", () => {
    navigable();

    const faces = [
      screen.getByText("Billing"),
      screen.getByText("run a3f2"),
    ].map((part) => getComputedStyle(part).fontFamily);

    expect(faces).toEqual([
      theme.typography.fontFamily,
      theme.typography.fontFamily,
    ]);
  });
});
