import { ThemeProvider } from "@mui/material/styles";
import { fireEvent, render, screen } from "@testing-library/react";
import { createIntl } from "react-intl";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { ReadField } from "../../../api/filling";
import type { ShownOrKept } from "../../../api/groups/{groupId}/runs/{runId}/steps";
import { theme } from "../../../lib/theme/theme";
import { CurrentFileRunContext } from "./CurrentFileRun";
import {
  FilledFields,
  FilledValue,
  inOneLine,
  labelledInLine,
} from "./FilledValue";

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
  "Kept as it was given; it no longer matches what is declared now.";

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

const RIGHT_TO_LEFT_OVERRIDE = String.fromCodePoint(0x202e);

const ZERO_WIDTH_SPACE = String.fromCodePoint(0x200b);

/** Each a marker, so that many of them in a row need as many markers. */
const markedApart = (markers: number) => `a${ZERO_WIDTH_SPACE}`.repeat(markers);

const setApart = (words: string) =>
  `${String.fromCodePoint(0x2068)}${words}${String.fromCodePoint(0x2069)}`;

const FILE_FIELD = field("file", {
  types: ["application/pdf"],
  maximumBytes: 12,
});

const FILE_VALUE = {
  id: "0000000f-0000-4000-8000-000000000001",
  name: "Demo Sample.pdf",
  type: "application/pdf",
  size: 5,
};

function drawingFile(shown: ShownOrKept, of: ReadField = FILE_FIELD) {
  return render(
    <ThemeProvider theme={theme}>
      <CurrentFileRunContext.Provider
        value={{ groupId: "group-now", runId: "run-now" }}
      >
        <FilledValue field={of} label="Attachment" shown={shown} shut={false} />
      </CurrentFileRunContext.Provider>
    </ThemeProvider>,
  ).container;
}

describe("FilledValue", () => {
  it("draws a declared file's name, bare type and size with a current-run attachment link", () => {
    const drawn = drawingFile({ value: FILE_VALUE });
    const link = screen.getByRole("link", {
      name: /Download.*Demo Sample.pdf/,
    });

    expect(link).toHaveAttribute(
      "href",
      "/api/groups/group-now/runs/run-now/files/0000000f-0000-4000-8000-000000000001",
    );
    expect(link).toHaveAttribute("download");
    expect(drawn).toHaveTextContent(/Type:.*application\/pdf.*\./);
    expect(drawn).toHaveTextContent("Size: 5 bytes.");
    expect(drawn).not.toHaveTextContent(FILE_VALUE.id);
    expect(drawn.querySelector("iframe, embed, object")).toBeNull();
  });

  it("keeps declared files in many and nested fields in order, each with its own link", () => {
    const another = {
      ...FILE_VALUE,
      id: "0000000f-0000-4000-8000-000000000002",
      name: "Demo Sample two.pdf",
    };
    const nested = field("fields", {
      fields: [
        { ...FILE_FIELD, name: "first", most: 2 },
        { ...FILE_FIELD, name: "second" },
      ],
    });

    drawingFile(
      { value: { first: [FILE_VALUE, another], second: FILE_VALUE } },
      nested,
    );

    expect(
      screen.getAllByRole("link").map((link) => link.getAttribute("href")),
    ).toEqual([
      "/api/groups/group-now/runs/run-now/files/0000000f-0000-4000-8000-000000000001",
      "/api/groups/group-now/runs/run-now/files/0000000f-0000-4000-8000-000000000002",
      "/api/groups/group-now/runs/run-now/files/0000000f-0000-4000-8000-000000000001",
    ]);
    expect(screen.getAllByRole("link").map((link) => link.textContent)).toEqual(
      [
        expect.stringContaining("Demo Sample.pdf"),
        expect.stringContaining("Demo Sample two.pdf"),
        expect.stringContaining("Demo Sample.pdf"),
      ],
    );
  });

  it("draws hidden text beside a downloadable file and a real field named withheld", () => {
    const privateText = "Demo Sample private note";
    const declared = field("fields", {
      fields: [
        field("text", { name: "privateNote", longest: 100 }),
        { ...FILE_FIELD, name: "attachment" },
        field("text", { name: "withheld", longest: 100 }),
      ],
    });
    const drawn = drawingFile(
      {
        value: {
          privateNote: { withheld: true },
          attachment: FILE_VALUE,
          withheld: "Demo Sample",
        },
      },
      declared,
    );

    expect(drawn).toHaveTextContent("Withheld from you.");
    expect(drawn).toHaveTextContent("Demo Sample");
    expect(
      screen.getByRole("link", { name: /Download.*Demo Sample.pdf/ }),
    ).toHaveAttribute(
      "href",
      "/api/groups/group-now/runs/run-now/files/0000000f-0000-4000-8000-000000000001",
    );
    expect(drawn.innerHTML).not.toContain(privateText);
    expect(screen.queryByRole("link", { name: privateText })).toBeNull();
  });

  it("says a whole fields value is withheld rather than showing its children as none", () => {
    const declared = field("fields", {
      fields: [field("text", { name: "note", longest: 40 })],
    });
    const drawn = drawing({ value: { withheld: true } }, declared);

    expect(drawn).toHaveTextContent("Withheld from you.");
    expect(drawn).not.toHaveTextContent("None");
    expect(drawn).not.toHaveTextContent("note");
    expect(screen.queryByRole("link")).toBeNull();
  });

  it("still reads a real child named withheld in a declared fields value", () => {
    const declared = field("fields", {
      fields: [field("text", { name: "withheld", longest: 40 })],
    });
    const drawn = drawing({ value: { withheld: "Demo Sample" } }, declared);

    expect(drawn).toHaveTextContent("Demo Sample");
    expect(drawn).not.toHaveTextContent("Withheld from you.");
  });

  it("says unknown for a declared file with invalid size and offers no download", () => {
    const bad = { ...FILE_VALUE, size: "5" };
    const declared = drawingFile({ value: bad as never });

    expect(declared).toHaveTextContent(UNKNOWN);
    expect(screen.queryByRole("link")).toBeNull();
    expect(declared).not.toHaveTextContent("Demo Sample.pdf");
  });

  it("shows unknown when a file field holds the wrong one-or-many shape", () => {
    const single = drawingFile({ value: [FILE_VALUE] });
    expect(single).toHaveTextContent(UNKNOWN);
    expect(screen.queryByRole("link")).toBeNull();

    const many = drawingFile({ value: FILE_VALUE }, { ...FILE_FIELD, most: 2 });
    expect(many).toHaveTextContent(UNKNOWN);
    expect(screen.queryByRole("link")).toBeNull();
  });

  it("refuses a malformed file identifier without rendering its hidden characters", () => {
    const hidden = String.fromCodePoint(0x200b).repeat(1_001);
    const drawn = drawingFile({ value: { ...FILE_VALUE, id: hidden } });

    expect(drawn).toHaveTextContent(UNKNOWN);
    expect(drawn).not.toHaveTextContent("left out");
    expect(drawn.innerHTML).not.toContain(hidden);
    expect(screen.queryByRole("link")).toBeNull();
  });

  it.each([
    ["an unusable name", { name: "Demo\nSample.pdf" }],
    ["an unusable type", { type: "application//pdf" }],
  ])("does not link a file with %s", (_case, changed) => {
    const drawn = drawingFile({ value: { ...FILE_VALUE, ...changed } });

    expect(drawn).toHaveTextContent(UNKNOWN);
    expect(screen.queryByRole("link")).toBeNull();
  });

  it("does not turn a withheld or earlier-shape value into file metadata or a link", () => {
    const withheld = drawingFile({ withheld: true });

    expect(withheld).toHaveTextContent("Withheld from you.");
    expect(screen.queryByRole("link")).toBeNull();

    const earlier = drawingFile({ asKept: JSON.stringify(FILE_VALUE) });
    expect(earlier).toHaveTextContent(EARLIER_SHAPE);
    expect(screen.queryByRole("link")).toBeNull();
  });

  it("shows readable metadata without a download link outside a readable run page", () => {
    const drawn = drawing({ value: FILE_VALUE }, FILE_FIELD);

    expect(drawn).toHaveTextContent("Demo Sample.pdf");
    expect(drawn).toHaveTextContent(/Type:.*application\/pdf.*\./);
    expect(screen.queryByRole("link")).toBeNull();
  });
  it("draws a short text of one line set apart in an element of its own, open, with nothing to open it by", () => {
    const drawn = drawing({ value: "A kettle leaks." }, field("text"));

    expect(drawn.querySelector("bdi")?.textContent).toBe("A kettle leaks.");
    expect(screen.queryByRole("button")).toBeNull();
  });

  it.each([
    ["a text", { value: `Pay ${RIGHT_TO_LEFT_OVERRIDE}001$` }, "text"],
    [
      "one kept in an earlier shape",
      { asKept: `Pay ${RIGHT_TO_LEFT_OVERRIDE}001$` },
      "text",
    ],
    [
      "a number written in a way it cannot be read",
      { value: `1${RIGHT_TO_LEFT_OVERRIDE}2` },
      "number",
    ],
  ] as const)(
    "draws an override in %s where it stands as a marker named for it, never the override itself",
    (_case, shown, kind) => {
      const drawn = drawing(shown, field(kind), false);

      expect(
        drawn.querySelector("bdi [role='img']")?.getAttribute("title"),
      ).toBe(
        "A character that would turn the order text is shown in is here, drawn instead of obeyed: U+202E.",
      );
      expect(drawn.querySelector("bdi")?.textContent).toContain("U+202E");
      expect(drawn.innerHTML).not.toContain(RIGHT_TO_LEFT_OVERRIDE);
    },
  );

  const SPREAD = [
    ["one text", field("text"), (markers: number) => markedApart(markers)],
    [
      "many items, several alike",
      field("text", { most: 20 }),
      (markers: number) => [
        ...Array.from({ length: 9 }, () => markedApart(100)),
        markedApart(markers - 900),
      ],
    ],
    [
      "fields within fields, nothing declaring them",
      undefined,
      (markers: number) => ({
        notes: Array.from({ length: 5 }, () => markedApart(100)),
        about: { title: markedApart(markers - 500) },
      }),
    ],
  ] as const;

  it.each(SPREAD)(
    "draws every marker of a value of %s needing as many across it as are drawn, saying nothing left out",
    (_shape, of, spread) => {
      const drawn = drawing({ value: spread(1_000) }, of, false);

      expect(screen.getAllByRole("img")).toHaveLength(1_000);
      expect(drawn.textContent).not.toContain("left out");
    },
  );

  it.each(SPREAD)(
    "draws no marker anywhere in a value of %s needing one more across it than are drawn, saying once above it how many characters it left out",
    (_shape, of, spread) => {
      const drawn = drawing({ value: spread(1_001) }, of, false);

      expect(screen.queryByRole("img")).toBeNull();
      expect(drawn.firstElementChild).toHaveTextContent(
        "1,001 characters that show nothing or would turn the order text is shown in are left out of what is drawn here: too many to mark where each stands.",
      );
      expect(screen.getAllByText(/left out/)).toHaveLength(1);
      expect(
        screen
          .getAllByText(/^a+$/)
          .reduce((kept, each) => kept + each.textContent.length, 0),
      ).toBe(1_001);
      expect(drawn.textContent).not.toContain("U+200B");
      expect(drawn.innerHTML).not.toContain(ZERO_WIDTH_SPACE);
    },
  );

  it("draws a control in a text as a marker said to show nothing, a line ended with a carriage return and a line feed as it is, and neither control on the page", () => {
    const drawn = drawing(
      { value: `Leak${String.fromCodePoint(0x1)}\r\nTwice.` },
      field("text"),
      false,
    );

    expect(
      screen.getAllByRole("img").map((each) => each.getAttribute("title")),
    ).toEqual(["A character that shows nothing is here: U+0001."]);
    expect(drawn.querySelector("bdi")?.textContent).toBe(
      "LeakU+0001\r\nTwice.",
    );
    expect(drawn.innerHTML).not.toContain(String.fromCodePoint(0x1));
  });

  it("draws no marker anywhere in a value of many items whose controls and lone carriage returns need one more than are drawn, saying once how many it left out", () => {
    const control = String.fromCodePoint(0x1);
    const drawn = drawing(
      {
        value: [
          `a${control}`.repeat(500),
          "b\r".repeat(500),
          `c${String.fromCodePoint(0x2028)}`,
          "d\r\n",
        ],
      },
      field("text", { most: 20 }),
      false,
    );

    expect(screen.queryByRole("img")).toBeNull();
    expect(drawn.firstElementChild).toHaveTextContent(
      "1,001 characters that show nothing or would turn the order text is shown in are left out of what is drawn here: too many to mark where each stands.",
    );
    expect(
      [...drawn.querySelectorAll("bdi")].map((each) => each.textContent),
    ).toEqual(["a".repeat(500), "b".repeat(500), "c", "d\r\n"]);
    expect(drawn.innerHTML).not.toContain(control);
    expect(drawn.innerHTML).not.toContain(String.fromCodePoint(0x2028));
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

  it("runs many on in one line where it stands within one, each drawn as its field's kind, joined as the reader's language joins a list", () => {
    reader.language = "de";

    const drawn = render(
      <FilledValue
        field={field("yes_no", { most: 3 })}
        label="Notes"
        shown={{ value: ["true", "false", "maybe"] }}
        shut={false}
        inLine
      />,
    ).container;

    expect(drawn.textContent).toBe(
      "Yes, No und maybeWritten in a way this page cannot read.",
    );
    expect(drawn.querySelector("ul, li, p, div")).toBeNull();
  });

  it("runs fields on in one line where they stand within one, each after its label, a field given none saying so", () => {
    const of = field("fields", {
      fields: [
        field("text", { name: "name", label: "Name", longest: 20 }),
        field("number", { name: "count", label: "Count" }),
      ],
    });

    const drawn = render(
      <FilledValue
        field={of}
        label="Notes"
        shown={{ value: { count: null, name: "Kettle" } }}
        shut={false}
        inLine
      />,
    ).container;

    expect(drawn.textContent).toBe("Name: Kettle and Count: None");
    expect(drawn.querySelector("dl, dt, dd, div")).toBeNull();
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
    ["withheld", { withheld: true } as const, "text", "Withheld from you."],
    ["unreadable", { value: "P9" }, "yes_no", UNREADABLE],
    ["kept in an earlier shape", { asKept: "x" }, "text", EARLIER_SHAPE],
    [
      "a moment on the reader's clock",
      { value: "2026-09-18T15:00:07+05:30" },
      "moment",
      /on your own clock$/,
    ],
  ])(
    "says a value is %s on a line of its own in an element a heading or a link may hold",
    (_case, shown, kind, said) => {
      const drawn = render(
        <ThemeProvider theme={theme}>
          <FilledValue
            field={field(kind)}
            label="Notes"
            shown={shown}
            shut={false}
          />
        </ThemeProvider>,
      ).container;

      const sentence = drawn.querySelector(".MuiTypography-body2")!;
      expect(sentence.tagName).toBe("SPAN");
      expect(sentence).toHaveTextContent(said);
      expect(sentence).toHaveStyle({ display: "block" });
      expect(drawn.querySelector("p, div")).toBeNull();
    },
  );

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

  it("runs fields nothing declares on in one line under their names, each after it", () => {
    const drawn = render(
      <FilledFields
        fields={undefined}
        values={{ ticket_notes: "Fire.", count: "2" }}
        shut={false}
        inLine
      />,
    ).container;

    expect(drawn.textContent).toBe("ticket notes: Fire. and count: 2");
    expect(drawn.querySelector("dl")).toBeNull();
  });
});

describe("inOneLine", () => {
  it.each([
    ["one", ["a"], "a"],
    ["two", ["a", "b"], "a and b"],
    ["three", ["a", "b", "c"], "a, b, and c"],
  ])(
    "joins %s as the reader's language joins things that all hold, each kept as it is drawn",
    (_case, items, said) => {
      const drawn = render(
        <p>{inOneLine(items.map((each) => <bdi>{each}</bdi>))}</p>,
      ).container;

      expect(drawn.textContent).toBe(said);
      expect(
        [...drawn.querySelectorAll("bdi")].map((each) => each.textContent),
      ).toEqual(items);
    },
  );

  it("joins in the reader's own language, not the page's", () => {
    reader.language = "de";

    const drawn = render(<p>{inOneLine(["a", "b"])}</p>).container;

    expect(drawn.textContent).toBe("a und b");
    expect(drawn.textContent).not.toContain("and");
  });
});

describe("labelledInLine", () => {
  it("draws a value after what it is called, the value kept as it is drawn", () => {
    const drawn = render(
      <p>{labelledInLine(<bdi>Name</bdi>, <bdi>Kettle</bdi>)}</p>,
    ).container;

    expect(drawn.textContent).toBe("Name: Kettle");
    expect(
      [...drawn.querySelectorAll("bdi")].map((each) => each.textContent),
    ).toEqual(["Name", "Kettle"]);
  });
});
