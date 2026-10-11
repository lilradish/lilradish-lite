import { ThemeProvider } from "@mui/material/styles";
import { render, screen, within } from "@testing-library/react";
import { createElement } from "react";
import { describe, expect, it } from "vitest";

import type { EntryRow } from "../../api/groups/{groupId}/{kind}";
import { theme } from "../../lib/theme/theme";
import { BY_NAME, LIBRARY_SORTABLE, libraryColumns } from "./libraryColumns";

const WAITING: EntryRow = {
  versionName: null,
  entryId: "00000006-0000-4000-8000-000000000b01",
  name: "Triage",
  purpose: "Sorts claims.",
  inService: 3,
  submitted: true,
  stopped: false,
};

const STOPPED: EntryRow = {
  versionName: null,
  entryId: "00000006-0000-4000-8000-000000000b02",
  name: "Handle",
  submitted: false,
  stopped: true,
};

function cellsOf(row: EntryRow): HTMLElement[] {
  const { container } = render(
    createElement(
      ThemeProvider,
      { theme },
      createElement(
        "div",
        null,
        ...libraryColumns().map(({ cell }) =>
          createElement("div", null, cell(row)),
        ),
      ),
    ),
  );
  return [...container.firstElementChild!.children] as HTMLElement[];
}

describe("libraryColumns", () => {
  it("heads a column for each thing a row says, each sortable by the column the server sorts it by", () => {
    expect(
      libraryColumns().map(({ label, sortKey }) => [label, sortKey]),
    ).toEqual([
      ["Name", "name"],
      ["In service", "inService"],
      ["Status", "status"],
    ]);
  });

  it.each([
    [
      "one waiting and running",
      WAITING,
      ["TriageSorts claims.", "Version 3", "Waiting for approval"],
    ],
    [
      "one stopped with none in service",
      STOPPED,
      ["HandleIt says nothing of what it is for.", "None", "Stopped"],
    ],
  ])("fills every cell of %s with its facts", (_case, row, cells) => {
    expect(cellsOf(row).map((cell) => cell.textContent)).toEqual(cells);
  });

  it("shows waiting and stopped together as two separate badges", () => {
    const cells = cellsOf({ ...WAITING, stopped: true });
    const badges = within(cells[2]!).getAllByText(
      /Waiting for approval|Stopped/,
    );

    expect(badges.map((badge) => badge.textContent)).toEqual([
      "Waiting for approval",
      "Stopped",
    ]);
    expect(screen.getByText("Sorts claims.")).toHaveStyle({
      color: theme.palette.text.secondary,
    });
    expect(
      [...cells[0]!.querySelectorAll("bdi")].map((each) => each.textContent),
    ).toEqual(["Triage", "Sorts claims."]);
  });

  it("leaves the status cell empty where neither status holds", () => {
    const cells = cellsOf({ ...STOPPED, stopped: false });

    expect(cells[2]).toBeEmptyDOMElement();
    expect(
      within(cells[2]!).queryByText(/Waiting for approval|Stopped/),
    ).toBeNull();
  });

  it("opens on the name, which no entry is without, in the order that comes first", () => {
    expect(BY_NAME).toEqual({ column: "name", descending: false });
    expect(Object.keys(LIBRARY_SORTABLE)).toEqual(
      libraryColumns().map(({ sortKey }) => sortKey),
    );
  });
});
