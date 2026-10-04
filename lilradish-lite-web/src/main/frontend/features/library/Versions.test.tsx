import { ThemeProvider } from "@mui/material/styles";
import { render, screen, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import type { ReactNode } from "react";
import { MemoryRouter } from "react-router";
import { describe, expect, it } from "vitest";

import type { Version } from "../../api/groups/{groupId}/{kind}";
import { theme } from "../../lib/theme/theme";
import { drawnAtDepth, drawnOf } from "../../testutil/depths";
import { Versions } from "./Versions";

const AVO = { userId: "000d51", displayName: "Avo Lumerace" };

function version(
  number: number,
  standing: string,
  over: Partial<Version> = {},
): Version {
  return {
    versionId: `00000007-0000-4000-8000-00000000d${String(number).padStart(3, "0")}`,
    number,
    standing,
    writers: [AVO],
    writtenByMigration: false,
    acts: new Set(),
    pinnedBy: [],
    ...over,
  };
}

const DRAFT = version(4, "draft");

const RETIRED_SINCE = version(3, "retired", { approval: { approver: AVO } });

const IN_SERVICE = version(2, "in_service", { approval: { approver: AVO } });

const RETIRED = version(1, "retired", { approval: { approver: AVO } });

/** Newest first, as the server sends them: a newer version retired while an older one still serves. */
const SENT = [DRAFT, RETIRED_SINCE, IN_SERVICE, RETIRED];

const NEWEST_FIRST = ["Version 4", "Version 3", "Version 2", "Version 1"];

/** The open version and its standing, the standing set apart from the words around it. */
const SHUT_LINE = `Version 2 · ${String.fromCodePoint(0x2068)}In service${String.fromCodePoint(0x2069)}`;

function themed({ children }: { readonly children: ReactNode }) {
  return (
    <MemoryRouter>
      <ThemeProvider theme={theme}>{children}</ThemeProvider>
    </MemoryRouter>
  );
}

/** A fresh element on every call: a rerender handed the same element back draws nothing again. */
function versionsOf(
  versions: readonly Version[] = SENT,
  open: Version | null = IN_SERVICE,
) {
  return (
    <Versions
      versions={versions}
      open={open ?? undefined}
      hrefOf={(each) => `/entry?version=${each.versionId}`}
      headingRef={() => {}}
      actions={<button type="button">New draft</button>}
    >
      <p>Said under the heading</p>
    </Versions>
  );
}

function part(): HTMLElement {
  return screen.getByRole("region", { name: "Versions" });
}

function control(): HTMLElement {
  return screen.getByRole("button", { name: "Versions" });
}

/** Which of the two marks the control shows, opening or shutting. */
function iconOf(button: HTMLElement): string | null {
  return button.querySelector("svg")?.getAttribute("data-testid") ?? null;
}

/** The first cell of every row, in the order drawn. */
function numbersDrawn(): string[] {
  const [, ...rows] = within(
    screen.getByRole("table", { name: "Versions" }),
  ).getAllByRole<HTMLTableRowElement>("row");
  return rows.map((row) => row.cells[0]!.textContent ?? "");
}

describe("Versions", () => {
  it("starts shut with one version and names the one already open", () => {
    render(versionsOf([IN_SERVICE], IN_SERVICE), { wrapper: themed });

    expect(control()).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("table")).toBeNull();
    const line = within(part()).getByLabelText(SHUT_LINE);
    expect(line).toBeVisible();
    expect(line.textContent).toBe("Version 2 · In service");
    const standing = within(line).getByText("In service");
    expect(standing.tagName).toBe("BDI");
    expect(getComputedStyle(standing.parentElement!).fontStyle).toBe("italic");
    expect(getComputedStyle(within(line).getByText("2")).fontStyle).not.toBe(
      "italic",
    );
  });

  it("keeps the reader's choice when the selected version changes", async () => {
    const { rerender } = render(versionsOf(), { wrapper: themed });
    await userEvent.click(control());

    rerender(versionsOf(SENT, DRAFT));

    expect(control()).toHaveAttribute("aria-expanded", "false");
    expect(
      within(part()).getByLabelText(
        `Version 4 · ${String.fromCodePoint(0x2068)}Draft${String.fromCodePoint(0x2069)}`,
      ),
    ).toBeVisible();
  });

  /** The heading is the control, so it is named as the part is whether it is shut or open. */
  it("heads the part with the control that shuts it, open at first and naming the table it controls", () => {
    render(versionsOf(), { wrapper: themed });

    const heading = screen.getByRole("heading", { level: 2, name: "Versions" });
    const controlled = document.getElementById(
      control().getAttribute("aria-controls")!,
    );

    expect(heading).toContainElement(control());
    expect(control()).toHaveAttribute("aria-expanded", "true");
    expect(controlled).toContainElement(screen.getByRole("table"));
    expect(controlled).toBeVisible();
    expect(iconOf(control())).toBe("ExpandLessIcon");
    expect(screen.queryByText(/^Version \d+ · /)).toBeNull();
  });

  it("frames itself as a part of the page", () => {
    render(versionsOf(), { wrapper: themed });

    expect(drawnOf(part())).toEqual(drawnAtDepth(1));
  });

  it("draws the actions level with the heading, and what it is handed under the heading and above the table", () => {
    render(versionsOf(), { wrapper: themed });

    const heading = screen.getByRole("heading", { name: "Versions" });
    const action = screen.getByRole("button", { name: "New draft" });
    const said = screen.getByText("Said under the heading");

    expect(action.parentElement).toBe(heading.parentElement);
    expect(heading.contains(action)).toBe(false);
    expect(
      heading.parentElement!.compareDocumentPosition(said) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).not.toBe(0);
    expect(
      said.compareDocumentPosition(screen.getByRole("table")) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).not.toBe(0);
  });

  it("marks the open version's row picked and no other, each row a link to the address that opens it", () => {
    render(versionsOf(), { wrapper: themed });

    const links = within(screen.getByRole("table")).getAllByRole("link");

    expect(
      links.map((link) => [
        link.textContent,
        link.getAttribute("href"),
        link.getAttribute("aria-current"),
      ]),
    ).toEqual([
      ["Version 4", `/entry?version=${DRAFT.versionId}`, null],
      ["Version 3", `/entry?version=${RETIRED_SINCE.versionId}`, null],
      ["Version 2", `/entry?version=${IN_SERVICE.versionId}`, "true"],
      ["Version 1", `/entry?version=${RETIRED.versionId}`, null],
    ]);
  });

  /** A version's name is words in the text of the page, never an identifier read character by character. */
  it("sets each version's name in the face of the words around it", () => {
    render(versionsOf(), { wrapper: themed });

    const named = within(screen.getByRole("table")).getAllByRole("link");
    const faces = named.map(
      (link) => getComputedStyle(link.closest("td")!).fontFamily,
    );

    expect(faces).toEqual(named.map(() => theme.typography.fontFamily));
  });

  it("sorts newest first until a heading is pressed, then by that heading", async () => {
    render(versionsOf(), { wrapper: themed });
    const sortedBefore = numbersDrawn();
    const headedBefore = screen
      .getAllByRole("columnheader")
      .map((header) => header.getAttribute("aria-sort"));

    await userEvent.click(screen.getByRole("button", { name: "Standing" }));

    expect(sortedBefore).toEqual(NEWEST_FIRST);
    expect(headedBefore).toEqual(["descending", null, null, null]);
    expect(numbersDrawn()).toEqual([
      "Version 4",
      "Version 2",
      "Version 3",
      "Version 1",
    ]);
    expect(
      screen
        .getAllByRole("columnheader")
        .map((header) => header.getAttribute("aria-sort")),
    ).toEqual([null, "ascending", null, null]);
  });

  it("shuts the table on a press, saying in one line the version open and its standing, the keyboard left on the control", async () => {
    render(versionsOf(), { wrapper: themed });
    const controlled = document.getElementById(
      control().getAttribute("aria-controls")!,
    );

    await userEvent.click(control());

    expect(control()).toHaveAttribute("aria-expanded", "false");
    expect(controlled).toBeEmptyDOMElement();
    expect(iconOf(control())).toBe("ExpandMoreIcon");
    expect(screen.queryByRole("table")).toBeNull();
    expect(screen.queryAllByRole("link")).toEqual([]);
    expect(within(part()).getByLabelText(SHUT_LINE)).toBeVisible();
    expect(document.activeElement).toBe(control());
    expect(screen.getByRole("button", { name: "New draft" })).toBeVisible();
  });

  it("opens the table again on a second press, and stops saying which is open apart from it", async () => {
    render(versionsOf(), { wrapper: themed });

    await userEvent.click(control());
    await userEvent.click(control());

    expect(control()).toHaveAttribute("aria-expanded", "true");
    expect(numbersDrawn()).toEqual(NEWEST_FIRST);
    expect(screen.queryByLabelText(SHUT_LINE)).toBeNull();
    expect(iconOf(control())).toBe("ExpandLessIcon");
  });

  /** Which version to work on is the first thing the page asks, so nothing remembers it shut or sorted. */
  it("draws itself afresh open and newest first, however it was left", async () => {
    const { unmount } = render(versionsOf(), { wrapper: themed });
    await userEvent.click(screen.getByRole("button", { name: "Standing" }));
    await userEvent.click(control());
    unmount();

    render(versionsOf(), { wrapper: themed });

    expect(control()).toHaveAttribute("aria-expanded", "true");
    expect(numbersDrawn()).toEqual(NEWEST_FIRST);
  });

  it("says so in the table where there is no version, and nothing in its place shut where none is open", async () => {
    render(versionsOf([], null), { wrapper: themed });
    const empty = screen.getByRole("table").textContent;

    expect(control()).toHaveAttribute("aria-expanded", "true");
    await userEvent.click(control());

    expect(empty).toContain("It has no version yet.");
    expect(within(part()).queryByText(/^Version \d/)).toBeNull();
    expect(part().textContent).toBe("VersionsNew draftSaid under the heading");
  });
});
