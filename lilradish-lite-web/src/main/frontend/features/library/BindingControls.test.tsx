import { ThemeProvider } from "@mui/material/styles";
import {
  createEvent,
  fireEvent,
  render,
  screen,
  within,
} from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { useState, type ReactNode } from "react";
import { describe, expect, it } from "vitest";

import type { DeclaredField } from "../../api/declaration";
import type { ContentProblem } from "../../api/groups/{groupId}/{kind}/{entryId}/versions";
import type { WorkflowVersion } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}";
import type { MessageId } from "../../i18n/app";
import { theme } from "../../lib/theme/theme";
import { noticesIn } from "../../testutil/notices";
import { BindingControls, BoundRows } from "./BindingControls";
import type {
  BindingDraft,
  Shape,
  SourceDraft,
  SourceOffered,
} from "./workflowDrafts";
import { draftOf } from "./declarationDrafts";
import {
  flagsOf,
  freshStep,
  sourcesFor,
  targetRows,
  type StepDraft,
} from "./workflowDrafts";

const FILE: DeclaredField = {
  fieldId: "file",
  name: "note",
  kind: "file",
  types: ["application/pdf"],
  maximumBytes: 1024,
  many: true,
  most: 2,
  mustBeGiven: true,
};

const TEXT: Shape = {
  name: "note",
  kind: "text",
  many: false,
  mustBeGiven: true,
  longest: 100,
};

const SOURCES: readonly SourceOffered[] = [
  {
    value: "input",
    words: "What the workflow takes",
    fields: [
      { ...TEXT, name: "short", longest: 50 },
      { ...TEXT, name: "long", longest: 500 },
      { ...TEXT, name: "count", kind: "number" },
    ],
  },
];

function themed({ children }: { readonly children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

/** The controls over a host holding the binding, whose last source the test reads. */
function binding(
  target: Shape,
  source: SourceDraft,
  editable = true,
  flags: readonly MessageId[] = [],
  sources: readonly SourceOffered[] = SOURCES,
) {
  const held: { source: SourceDraft } = { source };
  function Host() {
    const [bound, setBound] = useState<BindingDraft>({
      target: "note",
      source,
    });
    return (
      <BindingControls
        label="note"
        target={target}
        binding={bound}
        sources={sources}
        constant
        editable={editable}
        flags={flags}
        onChange={(next) => {
          held.source = next;
          setBound({ target: "note", source: next });
        }}
      />
    );
  }
  render(<Host />, { wrapper: themed });
  return held;
}

function group(): HTMLElement {
  return screen.getByRole("group", { name: "note" });
}

function fileBinding(
  fields: readonly DeclaredField[],
  target: DeclaredField,
  from: "input" | "step",
  path = "",
  editable = true,
  problems: readonly ContentProblem[] = [],
) {
  const held: { bindings: readonly BindingDraft[] } = { bindings: [] };
  const route: StepDraft = {
    ...freshStep("files"),
    name: "Demo Sample",
    runs: {
      kind: "route",
      discriminator: { from: "none" },
      gives: fields.map(draftOf),
      cases: [],
    },
  };
  const workflow: WorkflowVersion = {
    revision: 1,
    takes: fields,
    gives: [],
    steps: [],
    outputs: [],
    keepsOwnCeiling: false,
    raiseNeedsApproval: false,
    mayBeHelped: false,
    problems,
    sendsPast: [],
    offered: {
      lists: [],
      questions: [],
      workflows: [],
      codeSteps: [],
      searchWays: [],
      models: [],
    },
    terms: new Map(),
  };
  function Host() {
    const [bindings, setBindings] = useState<BindingDraft[]>([
      {
        bindingId: "file-binding",
        target: target.name,
        source:
          from === "input"
            ? { from, path }
            : { from, stepKey: route.key, path },
      },
    ]);
    const reader = { ...freshStep("reader"), bindings };
    const flow = { steps: [route, reader], outputs: [] };
    const sources = sourcesFor(flow, 1, workflow, new Map(), reader);
    const rows = targetRows(
      [target],
      bindings,
      problems,
      (bound, shape) => flagsOf(bound, shape, flow, 1, problems, sources),
      "contentProblem.input_unbound",
    );
    return (
      <BoundRows
        rows={rows}
        sources={sources}
        constant={false}
        editable={editable}
        change={(name, source) => {
          const next = [{ bindingId: "file-binding", target: name, source }];
          held.bindings = next;
          setBindings(next);
        }}
      />
    );
  }
  const drawn = render(<Host />, { wrapper: themed });
  return { held, ...drawn };
}

describe("BindingControls", () => {
  it("shows a real unnamed source in its closed selector and menu, keeping keyboard selection and names literal", async () => {
    const flow = { steps: [freshStep("s1")], outputs: [] };
    const workflow: WorkflowVersion = {
      revision: 1,
      takes: [],
      gives: [],
      steps: [],
      outputs: [],
      keepsOwnCeiling: false,
      raiseNeedsApproval: false,
      mayBeHelped: false,
      problems: [],
      sendsPast: [],
      terms: new Map(),
      offered: {
        lists: [],
        questions: [],
        workflows: [],
        codeSteps: [],
        searchWays: [],
        models: [],
      },
    };
    const offered = sourcesFor(flow, 1, workflow, new Map(), undefined);
    binding(TEXT, { from: "step", stepKey: "s1", path: "" }, true, [], offered);
    const control = screen.getByRole("combobox", { name: "From" });
    expect(control.textContent).toBe("Step 1: Step 1, not named yet");
    const hint = within(control).getByText("not named yet");
    expect(getComputedStyle(hint).fontStyle).toBe("italic");
    expect(within(control).getByText("Step 1").tagName).toBe("BDI");
    expect(
      getComputedStyle(within(control).getByText("Step 1")).fontStyle,
    ).not.toBe("italic");
    await userEvent.click(control);
    const option = screen.getByRole("option", {
      name: "Step 1: Step 1, not named yet",
    });
    expect(
      getComputedStyle(within(option).getByText("not named yet")).fontStyle,
    ).toBe("italic");
    await userEvent.keyboard("{Enter}");
    expect(screen.queryByRole("listbox")).toBeNull();
    expect(control).toHaveFocus();
  });

  it.each(["", "Demo Sample <img src=x>"])(
    "keeps a read-only step source and path complete for %s",
    (name) => {
      const words =
        name === "" ? "Step 1: Step 1, not named yet" : `Step 1: ${name}`;
      const offered: SourceOffered[] = [
        { value: "s1", words, step: { position: 1, name }, fields: [TEXT] },
      ];
      binding(
        TEXT,
        { from: "step", stepKey: "s1", path: "note" },
        false,
        [],
        offered,
      );
      const source = screen.getByLabelText(`${words}, note`);
      expect(source.textContent).toBe(`${words}, note`);
      expect(within(source).getByText("note").tagName).toBe("BDI");
      if (name === "") {
        expect(
          getComputedStyle(within(source).getByText("not named yet")).fontStyle,
        ).toBe("italic");
      } else {
        expect(within(source).getByText(name).tagName).toBe("BDI");
        expect(
          getComputedStyle(within(source).getByText(name)).fontStyle,
        ).not.toBe("italic");
        expect(within(source).queryByText("not named yet")).toBeNull();
      }
      expect(source.querySelector("img")).toBeNull();
      expect(screen.queryByRole("combobox")).toBeNull();
    },
  );
  it.each(
    (
      [
        [
          "target accepts another type",
          FILE,
          { ...FILE, types: ["application/pdf", "image/png"] },
          true,
        ],
        [
          "source may give another type",
          { ...FILE, types: ["application/pdf", "image/png"] },
          FILE,
          false,
        ],
        [
          "source may give a larger file",
          { ...FILE, maximumBytes: 1025 },
          FILE,
          false,
        ],
        ["source may give more files", { ...FILE, most: 3 }, FILE, false],
        ["equal limits", FILE, FILE, true],
        [
          "one does not become many",
          { ...FILE, many: false, most: undefined },
          FILE,
          false,
        ],
        [
          "many does not become one",
          FILE,
          { ...FILE, many: false, most: undefined },
          false,
        ],
      ] satisfies readonly (readonly [
        string,
        DeclaredField,
        DeclaredField,
        boolean,
      ])[]
    ).flatMap(([name, source, target, accepted]) =>
      (["input", "step"] as const).map(
        (from) =>
          [`${name} (${from})`, source, target, accepted, from] as const,
      ),
    ),
  )(
    "offers only a fitting real file pointer when %s",
    async (_case, source, target, accepted, from) => {
      const { held } = fileBinding(
        [{ ...source, name: "attachment" }],
        target,
        from,
      );

      await userEvent.click(
        within(group()).getByRole("combobox", { name: "Which" }),
      );

      expect(
        screen.getAllByRole("option").map((option) => option.textContent),
      ).toEqual(
        accepted ? ["None chosen yet", "attachment"] : ["None chosen yet"],
      );
      expect(screen.queryByRole("option", { name: "A constant" })).toBeNull();
      if (accepted) {
        await userEvent.click(
          screen.getByRole("option", { name: "attachment" }),
        );
        expect(held.bindings).toEqual([
          {
            bindingId: "file-binding",
            target: "note",
            source:
              from === "input"
                ? { from, path: "attachment" }
                : { from, stepKey: "files", path: "attachment" },
          },
        ]);
        expect(
          within(group()).getByRole("combobox", { name: "Which" }),
        ).not.toHaveAttribute("aria-invalid", "true");
        expect(group()).not.toHaveTextContent("Nothing fills it yet.");
      } else {
        await userEvent.keyboard("{Escape}");
        expect(held.bindings).toEqual([]);
        expect(
          within(group()).getByRole("combobox", { name: "Which" }),
        ).toHaveAccessibleDescription(
          "Nothing fills it yet. Nothing there fits this.",
        );
      }
    },
  );

  it.each(["input", "step"] as const)(
    "offers nested file pointers from %s without keeping one past its limit",
    async (from) => {
      const { held } = fileBinding(
        [
          {
            fieldId: "details",
            name: "details",
            kind: "fields",
            many: false,
            mustBeGiven: true,
            fields: [
              { ...FILE, name: "fits" },
              { ...FILE, name: "large", maximumBytes: 1025 },
            ],
          },
        ],
        FILE,
        from,
      );

      await userEvent.click(
        within(group()).getByRole("combobox", { name: "Which" }),
      );
      expect(
        screen.getAllByRole("option").map((option) => option.textContent),
      ).toEqual(["None chosen yet", "details.fits"]);
      await userEvent.click(
        screen.getByRole("option", { name: "details.fits" }),
      );
      expect(held.bindings[0]!.source).toEqual(
        from === "input"
          ? { from, path: "details.fits" }
          : { from, stepKey: "files", path: "details.fits" },
      );
      expect(
        within(group()).getByRole("combobox", { name: "Which" }),
      ).not.toHaveAttribute("aria-describedby");
    },
  );

  it.each([true, false])(
    "keeps a saved file pointer that no longer fits visible once, editable %s",
    async (editable) => {
      const { held } = fileBinding(
        [{ ...FILE, name: "large", maximumBytes: 1025 }],
        FILE,
        "step",
        "large",
        editable,
        [
          {
            code: "source_does_not_fit",
            part: "steps",
            bindingId: "file-binding",
          },
        ],
      );

      if (editable) {
        const which = within(group()).getByRole("combobox", { name: "Which" });
        expect(which).toHaveTextContent("large");
        expect(which).toHaveAccessibleDescription(
          "What it reads does not fit what it fills.",
        );
        expect(noticesIn(group())).toEqual([]);
        await userEvent.click(which);
        expect(
          screen.getAllByRole("option").map((option) => option.textContent),
        ).toEqual(["None chosen yet", "large"]);
      } else {
        expect(screen.queryByRole("combobox")).toBeNull();
        const source = screen.getByLabelText("Step 1: Demo Sample, large");
        expect(source).toBeInTheDocument();
        expect(source.textContent).toBe("Step 1: Demo Sample, large");
        expect(noticesIn(document.body)).toEqual([
          {
            severity: "warning",
            words: "What it reads does not fit what it fills.",
          },
        ]);
      }
      expect(held.bindings).toEqual([]);
    },
  );

  it("offers an optional file pointer and names its independent required-value problem after selection", async () => {
    fileBinding(
      [{ ...FILE, name: "optional", mustBeGiven: false }],
      FILE,
      "input",
    );
    await userEvent.click(
      within(group()).getByRole("combobox", { name: "Which" }),
    );
    expect(
      screen.getByRole("option", { name: "optional" }),
    ).toBeInTheDocument();
    await userEvent.click(screen.getByRole("option", { name: "optional" }));

    expect(
      within(group()).getByRole("combobox", { name: "Which" }),
    ).toHaveAccessibleDescription(
      "What it fills must be given, and what it reads need not be, so that may be empty.",
    );
    expect(group()).not.toHaveTextContent(
      "What it reads does not fit what it fills.",
    );
  });

  it("keeps an unfinished source selected until its pointer is chosen", async () => {
    function Host() {
      const [bindings, setBindings] = useState<BindingDraft[]>([]);
      const rows = targetRows(
        [
          {
            fieldId: "f1",
            name: "note",
            kind: "text",
            many: false,
            mustBeGiven: false,
          },
        ],
        bindings,
        [],
        () => [],
        "contentProblem.input_unbound",
      );
      return (
        <BoundRows
          rows={rows}
          sources={SOURCES}
          constant
          editable
          change={(target, source) => setBindings([{ target, source }])}
        />
      );
    }
    render(<Host />, { wrapper: themed });
    expect(
      within(group()).getByText("Nothing fills it yet."),
    ).toBeInTheDocument();

    await userEvent.click(
      within(group()).getByRole("combobox", { name: "From" }),
    );
    await userEvent.click(
      screen.getByRole("option", { name: "What the workflow takes" }),
    );
    expect(
      within(group()).getByRole("combobox", { name: "From" }),
    ).toHaveTextContent("What the workflow takes");
    expect(
      within(group()).getByRole("combobox", { name: "Which" }),
    ).toBeInTheDocument();
    expect(
      within(group()).getByText("Nothing fills it yet."),
    ).toBeInTheDocument();

    await userEvent.click(
      within(group()).getByRole("combobox", { name: "Which" }),
    );
    await userEvent.click(screen.getByRole("option", { name: "short" }));
    expect(within(group()).queryByText("Nothing fills it yet.")).toBeNull();

    await userEvent.click(
      within(group()).getByRole("combobox", { name: "From" }),
    );
    await userEvent.click(screen.getByRole("option", { name: "Nothing yet" }));
    expect(
      within(group()).getByText("Nothing fills it yet."),
    ).toBeInTheDocument();
  });

  it("uses a single-line constant control and refuses a pasted break without changing it", () => {
    const held = binding(
      { ...TEXT, lines: "one" },
      { from: "constant", typed: "Demo Sample" },
    );
    const input = within(group()).getByRole("textbox", {
      name: "The constant",
    });
    const paste = createEvent.paste(input, {
      clipboardData: { getData: () => `a${String.fromCodePoint(0x0a)}b` },
    });

    expect(input.tagName).toBe("INPUT");
    fireEvent(input, paste);

    expect(paste.defaultPrevented).toBe(true);
    expect(input).toHaveValue("Demo Sample");
    expect(held.source).toEqual({ from: "constant", typed: "Demo Sample" });
    expect(input).toHaveAccessibleDescription(
      /This field takes one line, and this holds a line break\./,
    );

    const drop = createEvent.drop(input, {
      dataTransfer: { getData: () => `c${String.fromCodePoint(0x0a)}d` },
    });
    fireEvent(input, drop);
    expect(drop.defaultPrevented).toBe(true);
    expect(held.source).toEqual({ from: "constant", typed: "Demo Sample" });

    const keypress = createEvent.keyPress(input, {
      key: "Enter",
      which: 10,
      charCode: 10,
    });
    fireEvent(input, keypress);
    expect(keypress.defaultPrevented).toBe(true);
    expect(input).toHaveValue("Demo Sample");
  });

  it("uses a textarea for several or older text and reports a saved constant that no longer fits one line", () => {
    binding({ ...TEXT, lines: "several" }, { from: "constant", typed: "a" });
    binding(TEXT, { from: "constant", typed: "b" });
    expect(
      screen
        .getAllByRole("textbox", { name: "The constant" })
        .map((box) => box.tagName),
    ).toEqual(["TEXTAREA", "TEXTAREA"]);

    const read = `a${String.fromCodePoint(0x0a)}b`;
    binding(
      { ...TEXT, lines: "one" },
      { from: "constant", typed: read, read: JSON.stringify(read) },
    );
    expect(
      within(screen.getAllByRole("group", { name: "note" })[2]!).getByRole(
        "textbox",
        { name: "The constant" },
      ),
    ).toHaveAccessibleDescription(
      /This field takes one line, and this holds a line break\./,
    );
  });

  it("accepts JSON formatting breaks for many one-line texts and checks each actual value", () => {
    const held = binding(
      { ...TEXT, many: true, lines: "one" },
      { from: "constant", typed: '["Demo Sample"]' },
    );
    const input = within(group()).getByRole("textbox", {
      name: /The constant, written out:/,
    });
    const lineFeed = String.fromCodePoint(0x0a);
    const formatted = `[${lineFeed}"Demo Sample"${lineFeed}]`;
    const paste = createEvent.paste(input, {
      clipboardData: { getData: () => formatted },
    });

    expect(input.tagName).toBe("TEXTAREA");
    fireEvent(input, paste);
    expect(paste.defaultPrevented).toBe(false);
    fireEvent.change(input, { target: { value: formatted } });
    expect(held.source).toEqual({ from: "constant", typed: formatted });
    expect(input).not.toHaveAccessibleDescription(/holds a line break/);

    const dropped = `[${lineFeed}"Demo Sample"]`;
    const drop = createEvent.drop(input, {
      dataTransfer: { getData: () => dropped },
    });
    fireEvent(input, drop);
    expect(drop.defaultPrevented).toBe(false);
    fireEvent.change(input, { target: { value: dropped } });
    expect(held.source).toEqual({ from: "constant", typed: dropped });

    const brokenValue = JSON.stringify([`Demo${lineFeed}Sample`]);
    fireEvent.change(input, { target: { value: brokenValue } });
    expect(held.source).toEqual({ from: "constant", typed: brokenValue });
    expect(input).toHaveAccessibleDescription(/holds a line break/);
  });

  it("offers only the fields that fit what is filled, keeping one chosen already in sight", async () => {
    binding(TEXT, { from: "input", path: "long" });

    await userEvent.click(
      within(group()).getByRole("combobox", { name: "Which" }),
    );

    expect(
      screen.getAllByRole("option").map((option) => option.textContent),
    ).toEqual(["None chosen yet", "long", "short"]);
  });

  it("offers matching fields even where a source may be empty, so the binding can explain that choice", async () => {
    const required = { ...TEXT, name: "email", mustBeGiven: true };
    const target: Shape = { ...TEXT, kind: "fields", fields: [required] };
    binding(
      target,
      { from: "input", path: "optional_child" },
      true,
      [],
      [
        {
          value: "input",
          words: "What the workflow takes",
          fields: [
            { ...target, name: "optional_parent", mustBeGiven: false },
            {
              ...target,
              name: "optional_child",
              fields: [{ ...required, mustBeGiven: false }],
            },
            { ...target, name: "certain" },
          ],
        },
      ],
    );
    await userEvent.click(
      within(group()).getByRole("combobox", { name: "Which" }),
    );
    expect(
      screen.getAllByRole("option").map((option) => option.textContent),
    ).toEqual([
      "None chosen yet",
      "optional_parent",
      "optional_child",
      "certain",
    ]);
  });

  it("switches a constant filling a yes or no, starting at no", async () => {
    const held = binding({ ...TEXT, kind: "yes_no" }, { from: "none" });

    await userEvent.click(
      within(group()).getByRole("combobox", { name: "From" }),
    );
    await userEvent.click(screen.getByRole("option", { name: "A constant" }));
    expect(held.source).toEqual({ from: "constant", typed: "false" });
    expect(within(group()).queryByRole("textbox")).toBeNull();

    await userEvent.click(within(group()).getByRole("switch", { name: "Yes" }));

    expect(held.source).toEqual({ from: "constant", typed: "true" });
  });

  it("keeps Nothing, Yes, and No separate for an optional yes or no constant", async () => {
    const held = binding(
      { ...TEXT, kind: "yes_no", mustBeGiven: false },
      { from: "none" },
    );
    await userEvent.click(
      within(group()).getByRole("combobox", { name: "From" }),
    );
    await userEvent.click(screen.getByRole("option", { name: "A constant" }));
    expect(held.source).toEqual({ from: "constant", typed: "null" });
    const choice = within(group()).getByRole("combobox", {
      name: "The constant",
    });
    for (const [words, typed] of [
      ["Yes", "true"],
      ["No", "false"],
      ["Nothing", "null"],
    ] as const) {
      await userEvent.click(choice);
      await userEvent.click(screen.getByRole("option", { name: words }));
      expect(held.source).toEqual({ from: "constant", typed });
    }
    expect(within(group()).queryByRole("switch")).toBeNull();
  });

  it("holds a constant filling a number as typed in its box, every digit of it", async () => {
    const held = binding(
      { ...TEXT, kind: "number" },
      { from: "constant", typed: "" },
    );

    await userEvent.type(
      within(group()).getByRole("textbox", { name: "The number" }),
      "12345678901234567890123456789012345678",
    );

    expect(held.source).toEqual({
      from: "constant",
      typed: "12345678901234567890123456789012345678",
    });
  });

  it("says against a number typed that the server would refuse", async () => {
    binding({ ...TEXT, kind: "number" }, { from: "constant", typed: "" });
    const box = within(group()).getByRole("textbox", { name: "The number" });

    await userEvent.type(box, "1e5");

    expect(box).toHaveAttribute("aria-invalid", "true");
    expect(box).toHaveAccessibleDescription(
      "A number is written in digits, with a point before any fraction, and nothing else; a zero is never written with a minus sign.",
    );
    expect(group()).not.toHaveAttribute("aria-describedby");
  });

  it("says nothing against a number typed that the server takes", async () => {
    binding({ ...TEXT, kind: "number" }, { from: "constant", typed: "" });
    const box = within(group()).getByRole("textbox", { name: "The number" });

    await userEvent.type(box, "12.5");

    expect(box).toHaveAttribute("aria-invalid", "false");
    expect(box).not.toHaveAttribute("aria-describedby");
  });

  it("says against a constant of text one holding what a model may not be sent", () => {
    binding(TEXT, {
      from: "constant",
      typed: "a" + String.fromCodePoint(0x202e) + "b",
    });

    expect(
      within(group()).getByRole("textbox", { name: "The constant" }),
    ).toHaveAccessibleDescription(
      "What was written holds a direction control, which text sent to a model may not hold.",
    );
  });

  it("says a constant as it was read nothing against, however it is typed here", () => {
    binding(
      { ...TEXT, kind: "number" },
      { from: "constant", typed: "1e21", read: "1e21" },
    );

    expect(
      within(group()).getByRole("textbox", { name: "The number" }),
    ).not.toHaveAttribute("aria-describedby");
  });

  /** Said once, as the error of one box, so a reader reaching the input hears it there and nowhere else. */
  it.each([
    ["nothing yet", TEXT, { from: "none" }, "combobox", "From"],
    ["somewhere", TEXT, { from: "input", path: "long" }, "combobox", "Which"],
    [
      "a constant of yes or no",
      { ...TEXT, kind: "yes_no" },
      { from: "constant", typed: "true" },
      "combobox",
      "From",
    ],
    [
      "a constant typed",
      TEXT,
      { from: "constant", typed: "x" },
      "textbox",
      "The constant",
    ],
  ] as const)(
    "says what does not hold of an input filled from %s as the error of the last box drawn, and in no notice",
    (_case, target, source, role, name) => {
      binding(target, source, true, ["contentProblem.input_unbound"]);

      const boxes = [
        ...within(group()).queryAllByRole("combobox"),
        ...within(group()).queryAllByRole("textbox"),
      ].sort((one, other) =>
        one.compareDocumentPosition(other) & Node.DOCUMENT_POSITION_FOLLOWING
          ? -1
          : 1,
      );
      const noted = within(group()).getByRole(role, { name });

      expect(boxes.at(-1)).toBe(noted);
      expect(noted).toHaveAttribute("aria-invalid", "true");
      expect(noted).toHaveAccessibleDescription("Nothing fills it yet.");
      expect(
        boxes.filter(
          (box) => box !== noted && box.getAttribute("aria-invalid") === "true",
        ),
      ).toEqual([]);
      expect(noticesIn(group())).toEqual([]);
      expect(group()).not.toHaveAttribute("aria-describedby");
    },
  );

  it("says each thing that does not hold against it once", () => {
    binding(TEXT, { from: "none" }, true, [
      "contentProblem.input_unbound",
      "contentProblem.input_unbound",
    ]);

    expect(within(group()).getAllByText("Nothing fills it yet.")).toHaveLength(
      1,
    );
  });

  it("reads a constant filling a yes or no in words where it may not be written, and offers no control", () => {
    binding(
      { ...TEXT, kind: "yes_no" },
      { from: "constant", typed: "true" },
      false,
    );

    expect(screen.getByText("note")).toBeInTheDocument();
    expect(screen.getByText(/A constant: Yes/)).toBeInTheDocument();
    expect(screen.queryByRole("switch")).toBeNull();
    expect(screen.queryByRole("combobox")).toBeNull();
  });
});
