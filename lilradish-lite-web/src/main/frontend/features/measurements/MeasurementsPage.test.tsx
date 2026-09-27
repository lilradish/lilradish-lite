import { ThemeProvider } from "@mui/material/styles";
import { render, screen, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import { MEASUREMENTS_DESTINATION } from "../../app/destinations";
import { theme } from "../../lib/theme/theme";
import { requestsTo, serving, type Reply } from "../../testutil/answering";
import { deferred } from "../../testutil/deferred";
import { MeasurementsPage } from "./MeasurementsPage";

const READ = "GET /api/measurements";

const NOTHING_CALLED = "No model has been called yet.";

function model(
  name: string,
  mode: string | null,
  productions: number,
  didNotFit = 0,
) {
  return {
    model: name,
    mode,
    productions,
    refusedOnReview: 0,
    reviews: 1500,
    refusing: 0,
    didNotFit,
  };
}

function calls(name: string, wentWrong: number, turnedAway: number) {
  return { model: name, wentWrong, neverCameBack: 0, turnedAway };
}

/** In the order the server sends them, which is not the order either table opens in. */
const MEASURED: readonly [string, number] = [
  JSON.stringify({
    models: [
      model("sorter", null, 3),
      model("sorter", "careful", 1200, 4),
      model("answerer", null, 7),
    ],
    system: [calls("sorter", 2, 0), calls("answerer", 0, 1234)],
  }),
  200,
];

function opening(replies: readonly Reply[] = [MEASURED]) {
  const sent = serving({ [READ]: replies });
  render(
    <ThemeProvider theme={theme}>
      <MeasurementsPage
        title={MEASUREMENTS_DESTINATION.label}
        act={MEASUREMENTS_DESTINATION.act}
      />
    </ThemeProvider>,
  );
  return { sent };
}

function tableNamed(name: string): HTMLElement {
  return screen.getByRole("table", { name });
}

/** Every body row of a table, as the words of each cell. */
function rowsOf(table: HTMLElement): string[][] {
  const [, ...rows] = within(table).getAllByRole<HTMLTableRowElement>("row");
  return rows.map((row) =>
    [...row.cells].map((cell) => cell.textContent ?? ""),
  );
}

function headsOf(table: HTMLElement): string[] {
  return within(table)
    .getAllByRole("columnheader")
    .map((head) => head.textContent ?? "");
}

describe("MeasurementsPage", () => {
  it("draws both tables by model ascending, each figure grouped in the reader's way and no mode where one ran as it is", async () => {
    const { sent } = opening();

    const models = await screen.findByRole("table", { name: "The models" });
    const system = tableNamed("This system");

    expect(rowsOf(models)).toEqual([
      ["answerer", "", "7", "0", "1,500", "0", "0"],
      ["sorter", "", "3", "0", "1,500", "0", "0"],
      ["sorter", "careful", "1,200", "0", "1,500", "0", "4"],
    ]);
    expect(rowsOf(system)).toEqual([
      ["answerer", "0", "0", "1,234"],
      ["sorter", "2", "0", "0"],
    ]);
    for (const table of [models, system]) {
      expect(
        within(table).getByRole("columnheader", { name: "Model" }),
      ).toHaveAttribute("aria-sort", "ascending");
    }
    expect(requestsTo(sent)).toEqual(["GET /api/measurements"]);
  });

  it("heads the tables with every figure, and titles the page and each table", async () => {
    opening();

    const models = await screen.findByRole("table", { name: "The models" });

    expect(headsOf(models)).toEqual([
      "Model",
      "Mode",
      "Productions",
      "Refused on review",
      "Reviews",
      "Refusing",
      "Did not fit",
    ]);
    expect(headsOf(tableNamed("This system"))).toEqual([
      "Model",
      "Went wrong",
      "Never came back",
      "Turned away",
    ]);
    expect(
      screen.getAllByRole("heading").map((heading) => heading.textContent),
    ).toEqual(["Measurements", "The models", "This system"]);
  });

  it("sorts one table by the heading pressed, by value and then the other way, leaving the other table as it was and reading nothing again", async () => {
    const { sent } = opening();
    const models = await screen.findByRole("table", { name: "The models" });
    const system = tableNamed("This system");
    const productions = within(models).getByRole("button", {
      name: "Productions",
    });

    await userEvent.click(productions);
    const ascending = rowsOf(models).map((row) => row[2]);
    await userEvent.click(productions);

    expect(ascending).toEqual(["3", "7", "1,200"]);
    expect(rowsOf(models).map((row) => row[2])).toEqual(["1,200", "7", "3"]);
    expect(
      within(models).getByRole("columnheader", { name: "Productions" }),
    ).toHaveAttribute("aria-sort", "descending");
    expect(
      within(models).getByRole("columnheader", { name: "Model" }),
    ).not.toHaveAttribute("aria-sort");
    expect(rowsOf(system).map((row) => row[0])).toEqual(["answerer", "sorter"]);
    expect(
      within(system).getByRole("columnheader", { name: "Model" }),
    ).toHaveAttribute("aria-sort", "ascending");
    expect(requestsTo(sent)).toEqual(["GET /api/measurements"]);
  });

  it("keeps both tables and their heads where nothing has ever been called, each saying so in the one row it holds", async () => {
    opening([[JSON.stringify({ models: [], system: [] }), 200]]);

    const models = await screen.findByRole("table", { name: "The models" });
    const system = tableNamed("This system");

    expect(rowsOf(models)).toEqual([[NOTHING_CALLED]]);
    expect(rowsOf(system)).toEqual([[NOTHING_CALLED]]);
    expect(headsOf(models)).toHaveLength(7);
    expect(headsOf(system)).toHaveLength(4);
    for (const [table, width] of [
      [models, "7"],
      [system, "4"],
    ] as const) {
      expect(within(table).getByRole("cell")).toHaveAttribute("colspan", width);
    }
  });

  it("says it is still reading, and draws no table, until the measurements arrive", async () => {
    const answer = deferred<readonly [string, number]>();
    opening([answer.promise]);

    expect(await screen.findByText("Still reading…")).toBeInTheDocument();
    expect(screen.queryByRole("table")).toBeNull();

    answer.settle(MEASURED);
    expect(
      await screen.findByRole("table", { name: "The models" }),
    ).toBeInTheDocument();
  });

  it("says the rule a refused read met, in place of either table", async () => {
    opening([[JSON.stringify({ code: "ACT_NOT_PERMITTED" }), 403]]);

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Measurements is read only by a role that may read what the estate measures.",
    );
    expect(screen.queryByRole("table")).toBeNull();
    expect(screen.queryByText(NOTHING_CALLED)).toBeNull();
  });

  it("offers nothing to do but sort: no row opens anything, and no control stands outside the heads", async () => {
    opening();

    await screen.findByRole("table", { name: "The models" });

    expect(screen.queryAllByRole("link")).toEqual([]);
    expect(
      screen
        .getAllByRole("button")
        .every((button) => button.closest("th") !== null),
    ).toBe(true);
  });
});
