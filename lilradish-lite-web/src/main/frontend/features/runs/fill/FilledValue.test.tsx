import { ThemeProvider } from "@mui/material/styles";
import { fireEvent, render, screen } from "@testing-library/react";
import { createIntl } from "react-intl";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { ReadField } from "../../../api/filling";
import type { ShownOrKept } from "../../../api/groups/{groupId}/runs/{runId}/steps";
import { theme } from "../../../lib/theme/theme";
import { FilledFields, FilledValue } from "./FilledValue";

/** The reader's language where a spec names one; the browser's otherwise. */
const reader = vi.hoisted(() => ({
  language: undefined as string | undefined,
}));

vi.mock(import("../../../i18n/intl"), async (importOriginal) => {
  const original = await importOriginal();
  return {
    ...original,
    get readersIntl() {
      return reader.language === undefined
        ? original.readersIntl
        : createIntl({ locale: reader.language, defaultLocale: "en" });
    },
  };
});

afterEach(() => {
  reader.language = undefined;
});

const UNKNOWN = "Not something this page can say yet.";

const UNREADABLE = "Written in a way this page cannot read.";

const EARLIER_SHAPE =
  "Given in the shape this step had before; shown as it was kept.";

function field(kind: string, more: Partial<ReadField> = {}): ReadField {
  return { name: kind, kind, mustBeGiven: false, ...more };
}

function drawing(shown: ShownOrKept, of?: ReadField, shut = true) {
  return render(
    <ThemeProvider theme={theme}>
      <FilledValue field={of} label="Notes" shown={shown} shut={shut} />
    </ThemeProvider>,
  ).container;
}

const setApart = (words: string) =>
  `${String.fromCodePoint(0x2068)}${words}${String.fromCodePoint(0x2069)}`;

describe("FilledValue", () => {
  it("draws a short text of one line set apart in an element of its own, open, with nothing to open it by", () => {
    const drawn = drawing({ value: "A kettle leaks." }, field("text"));

    expect(drawn.querySelector("bdi")?.textContent).toBe("A kettle leaks.");
    expect(screen.queryByRole("button")).toBeNull();
  });

  it("shuts a text of more than one line until it is opened, saying whether it is open, every line kept once it is", () => {
    const drawn = drawing({ value: "Line one\nLine two" }, field("text"));
    const control = screen.getByRole("button", { name: "Notes, in full" });
    expect(control).toHaveAttribute("aria-expanded", "false");
    expect(drawn.querySelector("bdi")).toBeNull();

    fireEvent.click(control);

    expect(control).toHaveAttribute("aria-expanded", "true");
    expect(drawn.querySelector("bdi")?.textContent).toBe("Line one\nLine two");
    expect(
      document.getElementById(control.getAttribute("aria-controls")!),
    ).not.toHaveAttribute("hidden");
  });

  it.each([
    ["200 is open", 200, false],
    ["201 is shut", 201, true],
  ])(
    "counts a text's length in code points as the server does, so one of emoji %s",
    (_case, count, shut) => {
      const drawn = drawing({ value: "😀".repeat(count) }, field("text"));

      expect(screen.queryByRole("button") !== null).toBe(shut);
      expect(drawn.querySelector("bdi") === null).toBe(shut);
    },
  );

  it("draws a long text in full, every line kept, with nothing to open it by, where long texts are not shut", () => {
    const drawn = drawing(
      { value: "Line one\nLine two" },
      field("text"),
      false,
    );

    expect(drawn.querySelector("bdi")?.textContent).toBe("Line one\nLine two");
    expect(screen.queryByRole("button")).toBeNull();
  });

  it("shuts a long text wherever it is, within fields and among many, named by the field it is within and its place among them, leaving a short one beside it open", () => {
    const notes = field("text", { name: "notes", label: "Notes", most: 3 });
    const title = field("text", { name: "title", label: "Title" });
    drawing(
      { value: { notes: ["Seen once.", "Seen\nagain."], title: "Leak" } },
      field("fields", { fields: [notes, title] }),
    );

    expect(
      screen.getAllByRole("button").map((each) => each.textContent),
    ).toEqual([`Notes › ${setApart("Notes")}, item 2, in full`]);
    expect(screen.getByText("Seen once.")).toBeInTheDocument();
    expect(screen.getByText("Leak")).toBeInTheDocument();
    expect(screen.queryByText(/again/)).toBeNull();
  });

  it("names the controls of two long texts in sibling fields apart, each by the field it is within", () => {
    const notes = field("text", { name: "notes", label: "Notes" });
    const group = (name: string, label: string) =>
      field("fields", { name, label, fields: [notes] });
    drawing(
      {
        value: {
          before: { notes: "Seen\nonce." },
          after: { notes: "Seen\nagain." },
        },
      },
      field("fields", {
        fields: [group("before", "Before"), group("after", "After")],
      }),
    );

    expect(
      screen.getAllByRole("button").map((each) => each.textContent),
    ).toEqual([
      `Notes › ${setApart("Before")} › ${setApart("Notes")}, in full`,
      `Notes › ${setApart("After")} › ${setApart("Notes")}, in full`,
    ]);
  });

  it("groups a number as the reader does and rounds none of its digits", () => {
    const drawn = drawing(
      { value: "-12345678901234567890.123456789" },
      field("number"),
    );

    expect(drawn.textContent).toBe("-12,345,678,901,234,567,890.123456789");
  });

  it("keeps every digit written after the point, a nought at the end included", () => {
    expect(drawing({ value: "1.50" }, field("number")).textContent).toBe(
      "1.50",
    );
    expect(drawing({ value: "15" }, field("number")).textContent).toBe("15");
  });

  it("dates a day of the first century in that century, not the one Date.UTC reads it in", () => {
    const drawn = drawing({ value: "0005-03-01" }, field("date"));

    const day = drawn.querySelector("time")!;
    expect(day.getAttribute("datetime")).toBe("0005-03-01");
    expect(day.textContent).toMatch(/^Mar 1, 5$/);
    expect(day.textContent).not.toContain("1905");
  });

  it("says a moment in the offset it was given in, to the second, and beneath it on the reader's own clock", () => {
    const drawn = drawing(
      { value: "2026-09-18T15:00:07+05:30" },
      field("moment"),
    );

    const moment = drawn.querySelector("time")!;
    expect(moment.textContent).toMatch(
      /^Sep 18, 2026, 3:00:07\sPM, UTC\+05:30$/,
    );
    expect(moment.getAttribute("datetime")).toBe("2026-09-18T15:00:07+05:30");
    expect(moment.hasAttribute("title")).toBe(false);
    expect(moment.nextElementSibling?.textContent).toMatch(
      /^Sep 18, 2026, 11:30:07\sAM on your own clock$/,
    );
  });

  it("says every digit of a fraction of a second written, on both clocks, and the first three cut, never rounded, in the element's own time", () => {
    const drawn = drawing(
      { value: "2026-09-18T15:00:59.999900+05:30" },
      field("moment"),
    );

    const moment = drawn.querySelector("time")!;
    expect(moment.textContent).toMatch(
      /^Sep 18, 2026, 3:00:59\.999900\sPM, UTC\+05:30$/,
    );
    expect(moment.nextElementSibling?.textContent).toMatch(
      /^Sep 18, 2026, 11:30:59\.999900\sAM on your own clock$/,
    );
    expect(moment.getAttribute("datetime")).toBe(
      "2026-09-18T15:00:59.999+05:30",
    );
  });

  it("writes a moment in the reader's own digits and words, a fraction of a second too, drawn as one without a fraction is", () => {
    reader.language = "ar-EG";
    const withFraction = drawing(
      { value: "2026-09-18T15:00:07.123456+05:30" },
      field("moment"),
    ).querySelector("time")!.nextElementSibling!.textContent!;
    const without = drawing(
      { value: "2026-09-18T15:00:07+05:30" },
      field("moment"),
    ).querySelector("time")!.nextElementSibling!.textContent!;

    expect(withFraction).toContain("٠٧٫١٢٣٤٥٦");
    expect(withFraction).not.toMatch(/[0-9]/);
    expect(withFraction.replace("٫١٢٣٤٥٦", "")).toBe(without);
  });

  it.each([
    ["a day past the end of its month", "2026-02-30", field("date")],
    ["a day in no year a date may be", "0000-01-01", field("date")],
    [
      "a moment past six digits of a second",
      "2026-09-18T15:00:00.1234567+05:30",
      field("moment"),
    ],
    [
      "a moment on a day past the end of its month",
      "2026-02-30T15:00:00Z",
      field("moment"),
    ],
    ["a number written with an exponent", "1e5", field("number")],
    ["yes or no written as a word", "yes", field("yes_no")],
  ])(
    "draws %s as it came, said to be unreadable, and never as the value it would roll over to",
    (_case, value, of) => {
      const drawn = drawing({ value }, of);

      expect(drawn.querySelector("bdi")?.textContent).toBe(value);
      expect(drawn).toHaveTextContent(UNREADABLE);
      expect(drawn.querySelector("time")).toBeNull();
    },
  );

  it.each([
    ["true", "Yes"],
    ["false", "No"],
  ])(
    "says yes or no for %s, never the word it was written with",
    (value, said) => {
      const drawn = drawing({ value }, field("yes_no"));

      expect(drawn.textContent).toBe(said);
    },
  );

  it("says a term with what it means, and a term none of its field's as it came, said to be unreadable", () => {
    const of = field("term", {
      terms: { terms: [{ term: "P1", meaning: "Drop everything" }] },
    });

    const offered = drawing({ value: "P1" }, of).textContent;
    const other = drawing({ value: "P9" }, of);

    expect(offered).toBe(
      `${String.fromCodePoint(0x2068)}P1${String.fromCodePoint(0x2069)}: ${String.fromCodePoint(0x2068)}Drop everything${String.fromCodePoint(0x2069)}`,
    );
    expect(offered).not.toContain(UNREADABLE);
    expect(other.querySelector("bdi")?.textContent).toBe("P9");
    expect(other).toHaveTextContent(UNREADABLE);
  });

  it("lists many as items of a list, each drawn as its field's kind", () => {
    drawing({ value: ["true", "false"] }, field("yes_no", { most: 3 }));

    expect(
      screen.getAllByRole("listitem").map((item) => item.textContent),
    ).toEqual(["Yes", "No"]);
  });

  it("says many given none as none, and draws no list for it", () => {
    const drawn = drawing({ value: [] }, field("text", { most: 3 }));

    expect(drawn.textContent).toBe("None");
    expect(drawn.querySelector("ul")).toBeNull();
  });

  it("draws fields under their labels in declared order, a field given none saying so", () => {
    const of = field("fields", {
      fields: [
        field("text", { name: "name", label: "Name", longest: 20 }),
        field("number", { name: "count", label: "Count" }),
      ],
    });

    drawing({ value: { count: null, name: "Kettle" } }, of);

    expect(screen.getAllByRole("term").map((term) => term.textContent)).toEqual(
      ["Name", "Count"],
    );
    expect(
      screen.getAllByRole("definition").map((each) => each.textContent),
    ).toEqual(["Kettle", "None"]);
  });

  it("says a value withheld from the reader is withheld, and draws nothing of it as none", () => {
    const drawn = drawing({ withheld: true }, field("text", { longest: 9 }));

    expect(drawn.textContent).toBe("Withheld from you.");
    expect(drawn.textContent).not.toContain("None");
  });

  it.each([
    ["fields", JSON.stringify({ ticket_notes: "Fire." })],
    ["many", JSON.stringify(["Fire.", "Smoke."])],
    ["text", JSON.stringify("R-7")],
  ])(
    "draws %s kept in a shape the step had before as the text it was kept as, set apart and read as nothing else, saying so beside it",
    (_shape, kept) => {
      const drawn = drawing({ asKept: kept });

      const text = drawn.querySelector("bdi")!;
      expect(text.textContent).toBe(kept);
      expect(getComputedStyle(text).whiteSpace).toBe("pre-wrap");
      expect(drawn).toHaveTextContent(EARLIER_SHAPE);
      expect(screen.queryAllByRole("term")).toEqual([]);
      expect(screen.queryAllByRole("listitem")).toEqual([]);
      expect(screen.queryByRole("button")).toBeNull();
    },
  );

  it.each([
    ["shut until opened where long texts are shut", true],
    ["in full, with nothing to open it by, where they are not", false],
  ])(
    "draws a long text kept in a shape the step had before %s, saying so beside it either way",
    (_case, shut) => {
      const kept = JSON.stringify("😀".repeat(200));

      const drawn = drawing({ asKept: kept }, undefined, shut);

      expect(
        screen.queryByRole("button", { name: "Notes, in full" }) !== null,
      ).toBe(shut);
      expect(drawn.querySelector("bdi")?.textContent).toBe(
        shut ? undefined : kept,
      );
      expect(drawn).toHaveTextContent(EARLIER_SHAPE);
    },
  );

  it("says none kept in a shape the step had before as none, and that it was kept so", () => {
    const drawn = drawing({ asKept: null });

    expect(drawn.textContent).toBe(`None${EARLIER_SHAPE}`);
    expect(drawn.querySelector("bdi")).toBeNull();
  });

  it("says nothing of a shape the step had before beside a value given in its own", () => {
    const drawn = drawing({ value: { ticket_notes: "Fire." } });

    expect(screen.getByRole("definition")).toHaveTextContent("Fire.");
    expect(drawn).not.toHaveTextContent(EARLIER_SHAPE);
  });

  it("says none as none", () => {
    expect(drawing({ value: null }, field("date")).textContent).toBe("None");
  });

  it.each([
    ["many, where its field holds one", ["a", "b"], field("text")],
    ["fields, where its field holds text", { a: "b" }, field("text")],
    ["one of many that is itself many", [["a"]], field("text", { most: 2 })],
    [
      "one of a field of a kind this build does not know",
      "red",
      field("colour"),
    ],
  ])(
    "says a value shaped as %s is something it cannot say yet",
    (_case, value, of) => {
      const drawn = drawing({ value }, of);

      expect(drawn.textContent).toBe(UNKNOWN);
      expect(drawn.querySelector("bdi")).toBeNull();
    },
  );

  it("draws a value nothing declares by its shape alone, fields under their names", () => {
    drawing({ value: { ticket: "Fire" } });

    expect(screen.getByRole("term")).toHaveTextContent("ticket");
    expect(screen.getByRole("definition")).toHaveTextContent("Fire");
  });

  it("names a field nothing declares as a person reads its name, on its label and on its control alike", () => {
    drawing({ value: { ticket_notes: "Fire.\nAgain." } });

    expect(screen.getByRole("term").textContent).toBe("ticket notes");
    expect(screen.getByRole("button").textContent).toBe(
      `Notes › ${setApart("ticket notes")}, in full`,
    );
    expect(document.body.textContent).not.toContain("ticket_notes");
  });
});

describe("FilledFields", () => {
  it("draws only the fields declared, in declared order, leaving out a member nothing declares", () => {
    render(
      <FilledFields
        fields={[field("text", { name: "b", label: "Bee", longest: 5 })]}
        values={{ a: "left out", b: "kept" }}
        shut={true}
      />,
    );

    expect(screen.getAllByRole("term").map((term) => term.textContent)).toEqual(
      ["Bee"],
    );
    expect(screen.queryByText("left out")).toBeNull();
  });

  it("says a field declared but given nothing as none, even where its name is one every object inherits", () => {
    render(
      <FilledFields
        fields={[field("text", { name: "constructor", longest: 5 })]}
        values={{}}
        shut={true}
      />,
    );

    expect(screen.getByRole("definition")).toHaveTextContent(/^None$/);
  });
});
