import { describe, expect, it, vi } from "vitest";

import type { ModelMeasured, SystemMeasured } from "../../api/measurements";
import type * as Catalogues from "../../i18n/en";
import {
  BY_MODEL,
  inOrder,
  modelAndMode,
  modelColumns,
  systemColumns,
} from "./measurementColumns";

// The catalogue as it is, and a word for one mode a deployment names, which the catalogue has none for yet.
vi.mock("../../i18n/en", async (importOriginal) => {
  const catalogues = await importOriginal<typeof Catalogues>();
  return {
    ...catalogues,
    APP_EN: { ...catalogues.APP_EN, "mode.research": "Researching" },
  };
});

function measured(
  model: string,
  mode: string | null,
  productions: number,
): ModelMeasured {
  return {
    model,
    mode,
    productions,
    refusedOnReview: 1234,
    reviews: 12345,
    refusing: 0,
    didNotFit: 1234567,
  };
}

const SORTER = measured("sorter", null, 5);
const SORTER_CAREFUL = measured("sorter", "careful", 5);
const SORTER_BRIEF = measured("sorter", "brief", 9);
const ANSWERER = measured("answerer", null, 2);
const ZEBRA = measured("zebra_2", "careful", 7);

const CALLS: SystemMeasured = {
  model: "sorter",
  wentWrong: 1000,
  neverCameBack: 0,
  turnedAway: 9007199254740991,
};

function headsOf(columns: readonly { label: string; sortKey?: string }[]) {
  return columns.map((column) => [column.label, column.sortKey]);
}

describe("modelColumns", () => {
  it("heads the models with the model, its mode and each of its figures, every one sorting by what it shows", () => {
    expect(headsOf(modelColumns())).toEqual([
      ["Model", "model"],
      ["Mode", "mode"],
      ["Productions", "productions"],
      ["Refused on review", "refusedOnReview"],
      ["Reviews", "reviews"],
      ["Refusing", "refusing"],
      ["Did not fit", "didNotFit"],
    ]);
  });

  it.each([
    [
      "a mode the catalogue has no word for, named as the deployment names it",
      SORTER_CAREFUL,
      "careful",
    ],
    [
      "a mode the catalogue has a word for, in that word",
      measured("sorter", "research", 5),
      "Researching",
    ],
    ["nothing where it ran as it is", SORTER, ""],
  ])(
    "shows %s, and each count grouped in the reader's way",
    (_case, row, mode) => {
      expect(modelColumns().map((column) => column.cell(row))).toEqual([
        "sorter",
        mode,
        "5",
        "1,234",
        "12,345",
        "0",
        "1,234,567",
      ]);
    },
  );

  it("reports no missing word for a mode the catalogue has none for", () => {
    const complaints = vi.spyOn(console, "error");

    const mode = modelColumns()[1].cell(SORTER_CAREFUL);

    expect(mode).toBe("careful");
    expect(complaints).not.toHaveBeenCalled();
  });
});

describe("systemColumns", () => {
  it("heads this system's figures with the model and each way a call to it failed, every one sorting", () => {
    expect(headsOf(systemColumns())).toEqual([
      ["Model", "model"],
      ["Went wrong", "wentWrong"],
      ["Never came back", "neverCameBack"],
      ["Turned away", "turnedAway"],
    ]);
  });

  it("shows the model and every count grouped in the reader's way, none rounded", () => {
    expect(systemColumns().map((column) => column.cell(CALLS))).toEqual([
      "sorter",
      "1,000",
      "0",
      "9,007,199,254,740,991",
    ]);
  });
});

describe("modelAndMode", () => {
  it("keys one model's rows apart by mode, and apart from it run as it is", () => {
    const keys = [SORTER, SORTER_CAREFUL, SORTER_BRIEF].map(modelAndMode);

    expect(new Set(keys).size).toBe(3);
  });

  /** Joined with a space, both would be "sort claims careful". */
  it("keys apart two rows whose model and mode read the same once run together, and alike the same model and mode", () => {
    const spacedModel = measured("sort claims", "careful", 1);
    const spacedMode = measured("sort", "claims careful", 1);

    expect(modelAndMode(spacedModel)).not.toBe(modelAndMode(spacedMode));
    expect(modelAndMode(spacedModel)).toBe(
      modelAndMode(measured("sort claims", "careful", 2)),
    );
  });
});

describe("inOrder", () => {
  /** In the order the server sends them, which no order asked here matches. */
  const ARRIVED = [SORTER, SORTER_CAREFUL, SORTER_BRIEF, ZEBRA, ANSWERER];

  it.each([
    [BY_MODEL, [ANSWERER, SORTER, SORTER_CAREFUL, SORTER_BRIEF, ZEBRA]],
    [
      { column: "model", descending: true },
      [ZEBRA, SORTER, SORTER_CAREFUL, SORTER_BRIEF, ANSWERER],
    ],
    [
      { column: "productions", descending: false },
      [ANSWERER, SORTER, SORTER_CAREFUL, ZEBRA, SORTER_BRIEF],
    ],
    [
      { column: "productions", descending: true },
      [SORTER_BRIEF, ZEBRA, SORTER, SORTER_CAREFUL, ANSWERER],
    ],
    [
      { column: "mode", descending: false },
      [SORTER, ANSWERER, SORTER_BRIEF, SORTER_CAREFUL, ZEBRA],
    ],
    [
      { column: "mode", descending: true },
      [SORTER_CAREFUL, ZEBRA, SORTER_BRIEF, SORTER, ANSWERER],
    ],
  ] as const)(
    "puts the rows in the order %o, rows alike in it kept as they arrived",
    (order, expected) => {
      expect(inOrder(ARRIVED, order)).toEqual(expected);
    },
  );

  it("orders counts by their value, not by the digits they are written in", () => {
    const rows = [measured("a", null, 10), measured("b", null, 9)];

    expect(
      inOrder(rows, { column: "productions", descending: false }).map(
        (row) => row.productions,
      ),
    ).toEqual([9, 10]);
  });

  it("leaves the rows it was handed as they were", () => {
    const rows = [...ARRIVED];

    inOrder(rows, BY_MODEL);

    expect(rows).toEqual(ARRIVED);
  });
});
