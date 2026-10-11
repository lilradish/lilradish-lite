import { ThemeProvider } from "@mui/material/styles";
import {
  act,
  createEvent,
  fireEvent,
  isInaccessible,
  render,
  screen,
  within,
} from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { useState, type ReactNode } from "react";
import { describe, expect, it, onTestFinished, vi } from "vitest";

import type { DeclaredField } from "../../api/declaration";
import type { ContentProblem } from "../../api/groups/{groupId}/{kind}/{entryId}/versions";
import type { WorkflowVersion } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}";
import type { MessageId } from "../../i18n/app";
import { theme } from "../../lib/theme/theme";
import { noticesIn } from "../../testutil/notices";
import { laidOutAt } from "../../testutil/layout";
import { BindingControls, BoundRows } from "./BindingControls";
import type {
  BindingDraft,
  Shape,
  SourceDraft,
  SourceOffered,
} from "./workflowDrafts";
import { draftOf } from "./declarationDrafts";
import * as workflowDrafts from "./workflowDrafts";
import {
  bindingFlagsFor,
  flowOf,
  freshStep,
  namedVersions,
  sourcesFor,
  targetRows,
  type StepDraft,
} from "./workflowDrafts";

const FILE: DeclaredField = {
  fieldId: "file",
  name: "note",
  kind: "file",
  types: [".pdf"],
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

const DECLARED_TEXT: DeclaredField = {
  fieldId: "note",
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
  return screen.getByRole("rowgroup", { name: "note" });
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
    const flags = bindingFlagsFor(flow, 1, sources);
    const rows = targetRows(
      [target],
      bindings,
      problems,
      (bound, shape) => flags(bound, shape, problems),
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
  it.each([true, false])(
    "associates each source property with its own headed cell, editable %s",
    (editable) => {
      laidOutAt(800, 1000);
      const rows = targetRows(
        [DECLARED_TEXT],
        [{ target: "note", source: { from: "input", path: "short" } }],
        [],
        () => [],
        "workflow.outputUnbound",
      );
      render(
        <BoundRows
          rows={rows}
          sources={SOURCES}
          constant
          editable={editable}
          change={() => undefined}
        />,
        { wrapper: themed },
      );
      const table = screen.getByRole("table");
      const heads = within(table).getAllByRole("columnheader");
      expect(
        heads.map((head) => [head.textContent, head.getAttribute("scope")]),
      ).toEqual([
        ["From", "col"],
        ["Which", "col"],
      ]);
      const body = screen.getByRole("rowgroup", { name: "note" });
      const [title, values] = within(body).getAllByRole("row");
      expect(within(title!).getAllByRole("cell")).toHaveLength(1);
      expect(within(title!).getByRole("cell")).toHaveAttribute("colspan", "2");
      const cells = within(values!).getAllByRole("cell");
      expect(cells).toHaveLength(2);
      expect(cells.every((cell) => !cell.hasAttribute("colspan"))).toBe(true);
      expect(cells[0]).toHaveTextContent("What the workflow takes");
      expect(cells[1]).toHaveTextContent("short");
      if (editable) {
        expect(
          within(cells[0]!).getByRole("combobox", { name: "From, note" }),
        ).toBeInTheDocument();
        expect(
          within(cells[1]!).getByRole("combobox", { name: "Which, note" }),
        ).toBeInTheDocument();
      } else expect(within(body).queryByRole("combobox")).toBeNull();
    },
  );

  function editingManyBindings(count: number) {
    laidOutAt(800, 1000);
    const fields = Array.from({ length: count }, (_, index) => ({
      ...DECLARED_TEXT,
      fieldId: `field-${index}`,
      name: `field_${index}`,
    }));
    const fitted = vi.spyOn(workflowDrafts, "pointerFits");
    const expanded = vi.spyOn(workflowDrafts, "pointers");
    onTestFinished(() => {
      fitted.mockRestore();
      expanded.mockRestore();
    });
    const received = vi.fn();
    function Owner() {
      const [bindings, bind] = useState<BindingDraft[]>(
        fields.map((field) => ({
          target: field.name,
          source: { from: "input", path: "field_0" },
        })),
      );
      const [revision, revise] = useState(0);
      const [receiver, replaceReceiver] = useState(0);
      const sources: SourceOffered[] = [
        {
          value: "input",
          words: "Run inputs",
          fields: fields.map((field) => ({
            ...TEXT,
            name: field.name,
            label: revision === 0 ? undefined : `Revised ${field.name}`,
          })),
        },
      ];
      return (
        <>
          <button onClick={() => revise((before) => before + 1)}>
            Revise sources
          </button>
          <button onClick={() => replaceReceiver((before) => before + 1)}>
            Change receiver
          </button>
          <BoundRows
            rows={targetRows(
              fields,
              bindings,
              [],
              () => [],
              "workflow.outputUnbound",
            )}
            sources={sources}
            constant
            editable
            change={(target, source) => {
              received(revision, receiver, target, source);
              bind((before) =>
                before.map((binding) =>
                  binding.target === target ? { ...binding, source } : binding,
                ),
              );
            }}
          />
        </>
      );
    }
    render(<Owner />, { wrapper: themed });
    const first = within(screen.getByRole("table"))
      .getAllByRole("rowgroup", { hidden: true })
      .find((body) => body.getAttribute("aria-label") === "field_0")!;
    expect(first).toHaveAccessibleName("field_0");
    return { fields, fitted, expanded, received, first };
  }

  it("reuses unchanged rows and shared source expansion across a 100-by-100 binding edit", async () => {
    const { fields, fitted, expanded, received, first } =
      editingManyBindings(100);
    expect(fitted).toHaveBeenCalledTimes(10000);
    expect(expanded).toHaveBeenCalledTimes(1);
    fitted.mockClear();
    expanded.mockClear();
    await userEvent.click(
      within(first).getByRole("combobox", { name: "Which, field_0" }),
    );
    const options = within(screen.getByRole("listbox")).getAllByRole("option", {
      hidden: true,
    });
    expect(options.map((option) => option.textContent)).toEqual([
      "None chosen yet",
      ...fields.map((field) => field.name),
    ]);
    await userEvent.click(options[2]!);
    expect(received).toHaveBeenLastCalledWith(0, 0, "field_0", {
      from: "input",
      path: "field_1",
    });
    expect(fitted).toHaveBeenCalledTimes(100);
    expect(expanded).not.toHaveBeenCalled();
  });

  it("observes source changes and the current receiver while retaining unchanged binding rows", async () => {
    const { fitted, expanded, received, first } = editingManyBindings(2);
    await userEvent.click(
      within(first).getByRole("combobox", { name: "Which, field_0" }),
    );
    await userEvent.click(screen.getByRole("option", { name: "field_1" }));
    fitted.mockClear();
    expanded.mockClear();
    await userEvent.click(
      screen.getByText("Revise sources", { selector: "button" }),
    );
    expect(expanded).toHaveBeenCalledTimes(1);
    expect(
      within(first).getByRole("combobox", { name: "Which, field_0" }),
    ).toHaveTextContent("Revised field_1 field_1");
    fitted.mockClear();
    expanded.mockClear();
    await userEvent.click(
      screen.getByText("Change receiver", { selector: "button" }),
    );
    expect(fitted).not.toHaveBeenCalled();
    expect(expanded).not.toHaveBeenCalled();
    await userEvent.click(
      within(first).getByRole("combobox", { name: "Which, field_0" }),
    );
    await userEvent.click(
      screen.getByRole("option", { name: "Revised field_0 field_0" }),
    );
    expect(received).toHaveBeenLastCalledWith(1, 1, "field_0", {
      from: "input",
      path: "field_0",
    });
  });

  it.each([true, false])(
    "uses one headed binding table with labels and nested author names, editable %s",
    (editable) => {
      const fields: DeclaredField[] = [
        {
          ...DECLARED_TEXT,
          fieldId: "first",
          name: "first_name",
          label: "Given name",
        },
        {
          ...DECLARED_TEXT,
          fieldId: "address",
          name: "address",
          kind: "fields",
          fields: [
            { ...DECLARED_TEXT, fieldId: "street", name: "street_name" },
          ],
        },
      ];
      const rows = targetRows(
        fields,
        [],
        [],
        () => [],
        "workflow.outputUnbound",
      );
      render(
        <BoundRows
          rows={rows}
          sources={SOURCES}
          constant
          editable={editable}
          change={() => undefined}
        />,
        { wrapper: themed },
      );

      const table = screen.getByRole("table");
      expect(
        within(table)
          .getAllByRole("columnheader")
          .map((head) => head.textContent),
      ).toEqual(["From", "Which"]);
      const names = [
        ...table.querySelectorAll("tbody > tr:first-child td > div"),
      ];
      expect(names.map((name) => name.textContent)).toEqual([
        "Given name · first_name",
        "address",
        "street_name",
      ]);
      expect(names.map((name) => getComputedStyle(name).paddingLeft)).toEqual([
        "0px",
        "0px",
        "24px",
      ]);
      expect(within(table).queryByText("address.street_name")).toBeNull();
      if (editable) {
        expect(
          within(table).getByRole("combobox", { name: "From, Given name" }),
        ).toBeInTheDocument();
        expect(
          within(table).getByRole("combobox", { name: "From, street_name" }),
        ).toBeInTheDocument();
        expect(
          within(table).queryByRole("combobox", { name: /^Which,/ }),
        ).toBeNull();
      } else {
        expect(within(table).queryByRole("combobox")).toBeNull();
        expect(within(table).getAllByText("Nothing yet")).toHaveLength(3);
      }
    },
  );

  it("marks each bound row with the whole path of the field it fills, so a change can be found by it, and the heading row with none", () => {
    const rows = targetRows(
      [
        { ...DECLARED_TEXT, fieldId: "first", name: "first_name" },
        {
          ...DECLARED_TEXT,
          fieldId: "address",
          name: "address",
          kind: "fields",
          fields: [
            { ...DECLARED_TEXT, fieldId: "street", name: "street_name" },
          ],
        },
      ],
      [],
      [],
      () => [],
      "workflow.outputUnbound",
    );
    render(
      <BoundRows
        rows={rows}
        sources={SOURCES}
        constant
        editable
        change={() => undefined}
      />,
      { wrapper: themed },
    );

    const [heading, ...allRows] = screen.getAllByRole("row");
    const bound = allRows.filter((row) => row.hasAttribute("data-path"));
    expect(allRows).toHaveLength(8);
    expect(
      allRows
        .filter((row) => !row.hasAttribute("data-path"))
        .every((row) => row.querySelector("td")?.colSpan === 2),
    ).toBe(true);
    expect(bound.map((row) => row.getAttribute("data-path"))).toEqual([
      "first_name",
      "address",
      "address.street_name",
    ]);
    expect(heading).not.toHaveAttribute("data-path");
  });

  it("switches one binding table to narrow rows with two static control labels and no duplicate inputs", async () => {
    const resize = laidOutAt(800, 1000);
    const rows = targetRows(
      [
        { ...DECLARED_TEXT, fieldId: "first", name: "first_name" },
        { ...DECLARED_TEXT, fieldId: "last", name: "last_name" },
      ],
      [{ target: "first_name", source: { from: "input", path: "short" } }],
      [],
      () => [],
      "workflow.outputUnbound",
    );
    render(
      <BoundRows
        rows={rows}
        sources={SOURCES}
        constant
        editable
        change={() => undefined}
      />,
      { wrapper: themed },
    );
    expect(screen.getAllByRole("columnheader")).toHaveLength(2);
    await resize(399);

    expect(screen.queryByRole("columnheader")).toBeNull();
    expect(screen.getAllByRole("table")).toHaveLength(1);
    expect(screen.getAllByRole("combobox")).toHaveLength(3);
    const first = screen.getByRole("rowgroup", { name: "first_name" });
    expect(within(first).getByText("From")).toBeVisible();
    expect(within(first).getByText("Which")).toBeVisible();
    expect(
      within(first).getByRole("combobox", { name: "Which, first_name" }),
    ).toHaveTextContent("short");
    const empty = screen.getByRole("rowgroup", { name: "last_name" });
    expect(within(empty).getByText("From")).toBeVisible();
    expect(within(empty).queryByText("Which")).toBeNull();
    const controlRow = within(first).getByText("From").closest("tr")!;
    expect(controlRow).toHaveStyle({
      gridTemplateColumns: "repeat(2,minmax(0,1fr))",
    });
    expect(
      [...controlRow.querySelectorAll("td")].map((cell) => [
        getComputedStyle(cell).paddingLeft,
        getComputedStyle(cell).paddingRight,
      ]),
    ).toEqual([
      ["12px", "4px"],
      ["4px", "12px"],
    ]);
    await resize(800);
    expect(screen.getAllByRole("columnheader")).toHaveLength(2);
    expect(screen.getAllByRole("combobox")).toHaveLength(3);
  });

  it("draws a wide binding table in every table's geometry: headings 36 high over a 2-pixel rule, rows 44 over a 1-pixel one, inset 12", () => {
    laidOutAt(800, 1000);
    const rows = targetRows(
      [{ ...DECLARED_TEXT, fieldId: "first", name: "first_name" }],
      [{ target: "first_name", source: { from: "input", path: "short" } }],
      [],
      () => [],
      "workflow.outputUnbound",
    );
    render(
      <BoundRows
        rows={rows}
        sources={SOURCES}
        constant
        editable
        change={() => undefined}
      />,
      { wrapper: themed },
    );
    const drawn = (cell: Element) => {
      const style = getComputedStyle(cell);
      return [
        style.height,
        style.padding,
        style.fontSize,
        style.lineHeight,
        style.borderBottomWidth,
        style.borderBottomColor,
      ];
    };
    const headings = screen.getAllByRole("columnheader");
    const cells = [...screen.getByRole("table").querySelectorAll("tbody td")];
    expect(headings).toHaveLength(2);
    expect(cells.length).toBeGreaterThan(0);

    for (const heading of headings) {
      expect(drawn(heading)).toEqual([
        "36px",
        "7px 12px",
        "12px",
        "22px",
        "2px",
        "rgb(214, 224, 228)",
      ]);
    }
    for (const cell of cells) {
      expect(drawn(cell)).toEqual([
        "44px",
        cell.hasAttribute("colspan")
          ? "11px 12px"
          : cell === cell.parentElement!.firstElementChild
            ? "11px 8px 11px 12px"
            : "11px 12px 11px 8px",
        "14px",
        "22px",
        "1px",
        "rgb(228, 235, 238)",
      ]);
    }
  });

  it.each(["table", "single row"])(
    "retains the focused constant, selection, typed draft and refused paste across both widths in a %s",
    async (drawing) => {
      const resize = laidOutAt(800, 1000);
      let latest: SourceDraft = { from: "constant", typed: "author text" };
      let changes = 0;
      function Host() {
        const [source, setSource] = useState(latest);
        const change = (next: SourceDraft) => {
          changes += 1;
          latest = next;
          setSource(next);
        };
        const target = { ...TEXT, lines: "one" as const };
        const bound = { target: "note", source };
        return drawing === "table" ? (
          <BoundRows
            rows={[
              { path: "note", depth: 0, target, binding: bound, flags: [] },
            ]}
            sources={SOURCES}
            constant
            editable
            change={(_name, next) => change(next)}
          />
        ) : (
          <BindingControls
            label="note"
            target={target}
            binding={bound}
            sources={SOURCES}
            constant
            editable
            flags={[]}
            onChange={change}
          />
        );
      }
      render(<Host />, { wrapper: themed });
      const input = screen.getByRole<HTMLInputElement>("textbox", {
        name: "Value, note",
      });
      const from = screen.getByRole("combobox", { name: "From, note" });
      await userEvent.clear(input);
      await userEvent.type(input, "author draft");
      input.setSelectionRange(2, 5);
      const changesBeforePaste = changes;
      const paste = createEvent.paste(input, {
        clipboardData: { getData: () => "first\nsecond" },
      });
      fireEvent(input, paste);
      expect(paste.defaultPrevented).toBe(true);
      expect(changes).toBe(changesBeforePaste);
      expect(input).toHaveAccessibleDescription(
        /This field takes a single line; remove the line break\./,
      );
      const refusedDescription = input.getAttribute("aria-describedby");

      for (const width of [404, 800]) {
        await resize(width);
        expect(screen.getByRole("textbox", { name: "Value, note" })).toBe(
          input,
        );
        expect(input).toHaveFocus();
        expect([input.selectionStart, input.selectionEnd]).toEqual([2, 5]);
        expect(input).toHaveValue("author draft");
        expect(latest).toEqual({ from: "constant", typed: "author draft" });
        expect(changes).toBe(changesBeforePaste);
        expect(input).toHaveAttribute("aria-describedby", refusedDescription);
        expect(input).toHaveAccessibleDescription(
          /This field takes a single line; remove the line break\./,
        );
        expect(screen.getByRole("combobox", { name: "From, note" })).toBe(from);
        expect(screen.getAllByRole("textbox")).toHaveLength(1);
      }
    },
  );

  it.each(["route", "question", "workflow", "code_step"] as const)(
    "draws real %s declaration labels and changes only the complete source path",
    async (kind) => {
      const fields: DeclaredField[] = [
        {
          ...DECLARED_TEXT,
          fieldId: "package",
          name: "package",
          label: "Returned details",
          kind: "fields",
          fields: [
            { ...DECLARED_TEXT, label: "Returned note" },
            {
              ...DECLARED_TEXT,
              fieldId: "empty",
              name: "empty_name",
              label: "",
            },
          ],
        },
        {
          ...DECLARED_TEXT,
          fieldId: "same",
          name: "same_name",
          label: "same_name",
        },
      ];
      const workflow: WorkflowVersion = {
        revision: 1,
        takes: [],
        gives: [],
        steps: [
          {
            stepId: "writer",
            name: "writer",
            runs:
              kind === "route"
                ? {
                    kind,
                    discriminator: {
                      bindingId: "discriminator",
                      source: { constant: '"urgent"' },
                    },
                    gives: fields,
                    cases: [],
                  }
                : kind === "code_step"
                  ? { kind, codeStep: "write_note" }
                  : {
                      kind,
                      version: {
                        versionName: null,
                        name: "Prepare note",
                        versionId: "note-version",
                        number: 1,
                        standing: "in_service",
                      },
                      takes: [],
                      gives: fields,
                    },
            bindings: [],
          },
          { stepId: "reader", name: "reader", bindings: [] },
        ],
        outputs: [],
        keepsOwnCeiling: false,
        raiseNeedsApproval: false,
        mayBeHelped: false,
        problems: [],
        sendsPast: [],
        offered: {
          codeSteps: [{ name: "write_note", takes: [], gives: fields }],
          searchWays: [],
          models: [],
        },
        terms: new Map(),
      };
      const flow = flowOf(workflow);
      const writer = flow.steps[0]!;
      const sources = sourcesFor(
        flow,
        1,
        workflow,
        namedVersions(workflow),
        flow.steps[1],
      );
      const held = binding(
        TEXT,
        { from: "step", stepKey: writer.key, path: "package.note" },
        true,
        [],
        sources,
      );
      const which = screen.getByRole("combobox", { name: "Which, note" });
      expect(which).toHaveTextContent("Returned note package.note");
      await userEvent.click(which);
      const options = screen.getAllByRole("option");
      expect(options).toHaveLength(4);
      expect(options.map((option) => option.textContent)).toEqual([
        "None chosen yet",
        "Returned note package.note",
        "package.empty_name",
        "same_name",
      ]);
      const labelled = screen.getByRole("option", {
        name: "Returned note package.note",
      });
      expect(labelled.querySelector("bdi")).toHaveTextContent("Returned note");
      expect(labelled.querySelector("code")).toHaveTextContent("package.note");
      for (const path of ["package.empty_name", "same_name"]) {
        expect(
          screen.getByRole("option", { name: path }).querySelector("code"),
        ).toBeNull();
      }
      await userEvent.click(
        screen.getByRole("option", { name: "package.empty_name" }),
      );
      expect(held.source).toEqual({
        from: "step",
        stepKey: writer.key,
        path: "package.empty_name",
      });
      expect(which).toHaveTextContent("package.empty_name");
      await userEvent.click(which);
      await userEvent.click(
        screen.getByRole("option", { name: "Returned note package.note" }),
      );
      expect(held.source).toEqual({
        from: "step",
        stepKey: writer.key,
        path: "package.note",
      });
      expect(which).toHaveTextContent("Returned note package.note");
    },
  );

  it.each(["From", "Which"])(
    "retains the same focused %s selector and error relationships across both widths",
    async (words) => {
      const resize = laidOutAt(800, 1000);
      const rows = targetRows(
        [DECLARED_TEXT],
        [{ target: "note", source: { from: "input", path: "short" } }],
        [],
        () => ["contentProblem.input_unbound"],
        "workflow.outputUnbound",
      );
      render(
        <BoundRows
          rows={rows}
          sources={SOURCES}
          constant
          editable
          change={() => undefined}
        />,
        { wrapper: themed },
      );
      const controls = ["From", "Which"].map((label) =>
        screen.getByRole("combobox", { name: `${label}, note` }),
      );
      const control = screen.getByRole("combobox", { name: `${words}, note` });
      const description = control.getAttribute("aria-describedby");
      act(() => control.focus());

      for (const width of [404, 800]) {
        await resize(width);
        expect(screen.getByRole("combobox", { name: `${words}, note` })).toBe(
          control,
        );
        expect(control).toHaveFocus();
        expect(screen.getAllByRole("combobox")).toHaveLength(2);
        expect(screen.getAllByRole("combobox")).toEqual(controls);
        for (const each of controls) {
          expect(each).toHaveAccessibleDescription("Nothing fills it yet.");
          expect(each).toHaveAttribute("aria-describedby", description);
          expect(each).toHaveAttribute("aria-invalid", "true");
        }
      }
      await userEvent.keyboard("{ArrowDown}");
      expect(screen.getByRole("listbox")).toBeInTheDocument();
      await userEvent.keyboard("{Escape}");
      expect(control).toHaveFocus();
      expect(screen.queryByRole("listbox")).toBeNull();
      expect(controls[1]).toHaveTextContent("short");
    },
  );

  it.each(["field_255", "missing_field"])(
    "offers all 256 fitting labelled fields while retaining selected raw path %s",
    async (path) => {
      const fields: Shape[] = Array.from({ length: 256 }, (_, index) => ({
        ...TEXT,
        name: `field_${index}`,
        label: `Field ${index}`,
      }));
      const held = binding(
        TEXT,
        { from: "input", path },
        true,
        [],
        [{ value: "input", words: "Run inputs", fields }],
      );
      const which = screen.getByRole("combobox", { name: "Which, note" });
      await userEvent.click(which);
      const expected = [
        "None chosen yet",
        ...(path === "missing_field" ? [path] : []),
        ...fields.map((field) => `${field.label} ${field.name}`),
      ];
      // jsdom recomputes styles for each accessible name; comparing the whole list without naming every option keeps this usable under load.
      const options = within(screen.getByRole("listbox")).getAllByRole(
        "option",
        { hidden: true },
      );
      expect(isInaccessible(options[0]!)).toBe(false);
      expect(isInaccessible(options.at(-1)!)).toBe(false);
      expect(options).toHaveLength(expected.length);
      expect(options.map((option) => option.textContent)).toEqual(expected);
      const last = options[expected.indexOf("Field 255 field_255")]!;
      expect(last).toHaveAccessibleName("Field 255 field_255");
      expect(last).toHaveAttribute(
        "aria-selected",
        path === "field_255" ? "true" : "false",
      );
      const chosen = options[expected.indexOf("Field 128 field_128")]!;
      expect(chosen).toHaveAccessibleName("Field 128 field_128");
      await userEvent.click(chosen);
      expect(held.source).toEqual({ from: "input", path: "field_128" });
      expect(which).toHaveTextContent("Field 128 field_128");
    },
  );

  it("retains the human label and complete path of a selected field that no longer fits", async () => {
    const held = binding(
      TEXT,
      { from: "input", path: "long" },
      true,
      [],
      [
        {
          value: "input",
          words: "Run inputs",
          fields: [
            { ...TEXT, name: "short", label: "Short note", longest: 50 },
            { ...TEXT, name: "long", label: "Long note", longest: 500 },
          ],
        },
      ],
    );
    await userEvent.click(
      screen.getByRole("combobox", { name: "Which, note" }),
    );
    expect(
      screen.getAllByRole("option").map((option) => option.textContent),
    ).toEqual(["None chosen yet", "Long note long", "Short note short"]);
    expect(
      screen.getByRole("option", { name: "Long note long" }),
    ).toHaveAttribute("aria-selected", "true");
    await userEvent.keyboard("{Escape}");
    expect(held.source).toEqual({ from: "input", path: "long" });
  });

  it("offers labelled fields with a key chip while retaining the complete pointer and literal unlabelled key", async () => {
    const held = binding(
      TEXT,
      { from: "input", path: "short" },
      true,
      [],
      [
        {
          value: "input",
          words: "What the workflow takes",
          fields: [
            { ...TEXT, name: "short", label: "Given name" },
            { ...TEXT, name: "first_name" },
          ],
        },
      ],
    );
    const which = screen.getByRole("combobox", { name: "Which, note" });
    expect(within(which).getByText("Given name")).toBeInTheDocument();
    expect(within(which).getByText("short").closest("code")).not.toBeNull();
    await userEvent.click(which);
    const labelled = screen.getByRole("option", { name: "Given name short" });
    expect(labelled.textContent).toBe("Given name short");
    const unlabelled = screen.getByRole("option", { name: "first_name" });
    expect(unlabelled.querySelector("code")).toBeNull();
    expect(screen.queryByRole("option", { name: "first name" })).toBeNull();
    await userEvent.click(unlabelled);
    expect(held.source).toEqual({ from: "input", path: "first_name" });
    expect(which).toHaveTextContent("first_name");
    expect(screen.queryByRole("listbox")).toBeNull();
  });

  it("formats a stored number's exact typed digits even when its parsed source read is already present", () => {
    const source: SourceDraft = {
      from: "constant",
      typed: "1234567.8900",
      read: "1234567.8900",
    };
    const held = binding({ ...TEXT, kind: "number" }, source, false);
    expect(screen.getByText("1,234,567.8900")).toBeInTheDocument();
    expect(held.source).toEqual(source);
  });
  it("reads a selected step by its label while retaining its machine binding key and path", () => {
    const sources: SourceOffered[] = [
      {
        value: "s1",
        words: "Step 1: 確認する (file_claim)",
        step: { position: 1, name: "file_claim", label: "確認する" },
        fields: [TEXT],
      },
    ];
    const held = binding(
      TEXT,
      { from: "step", stepKey: "s1", path: "note" },
      false,
      [],
      sources,
    );

    const source = within(group()).getAllByRole("row")[1]!;
    expect(source.textContent).toBe(
      "FromStep 1: \u78ba\u8a8d\u3059\u308bWhichnote",
    );
    expect(within(source).getByText("確認する").tagName).toBe("BDI");
    expect(within(source).queryByText("file_claim")).toBeNull();
    expect(within(source).getByText("note").tagName).toBe("BDI");
    expect(held.source).toEqual({ from: "step", stepKey: "s1", path: "note" });
  });
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
        codeSteps: [],
        searchWays: [],
        models: [],
      },
    };
    const offered = sourcesFor(flow, 1, workflow, new Map(), undefined);
    binding(TEXT, { from: "step", stepKey: "s1", path: "" }, true, [], offered);
    const control = screen.getByRole("combobox", { name: /^From, / });
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
        {
          value: "s1",
          words,
          step: { position: 1, name, label: "" },
          fields: [TEXT],
        },
      ];
      binding(
        TEXT,
        { from: "step", stepKey: "s1", path: "note" },
        false,
        [],
        offered,
      );
      const source = within(group()).getAllByRole("row")[1]!;
      expect(source.textContent).toBe(`From${words}Whichnote`);
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
          { ...FILE, types: [".pdf", ".png"] },
          true,
        ],
        [
          "source may give another type",
          { ...FILE, types: [".pdf", ".png"] },
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
        within(group()).getByRole("combobox", { name: /^Which, / }),
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
          within(group()).getByRole("combobox", { name: /^Which, / }),
        ).not.toHaveAttribute("aria-invalid", "true");
        expect(group()).not.toHaveTextContent("Nothing fills it yet.");
      } else {
        await userEvent.keyboard("{Escape}");
        expect(held.bindings).toEqual([]);
        expect(
          within(group()).getByRole("combobox", { name: /^Which, / }),
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
        within(group()).getByRole("combobox", { name: /^Which, / }),
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
        within(group()).getByRole("combobox", { name: /^Which, / }),
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
        const which = within(group()).getByRole("combobox", {
          name: /^Which, /,
        });
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
        const [title, source] = screen.getAllByRole("row").slice(1);
        expect(title).toHaveTextContent("note");
        expect(source).toBeInTheDocument();
        expect(
          [...source!.querySelectorAll("td")].map((cell) => cell.textContent),
        ).toEqual(["Step 1: Demo Sample", "large"]);
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
      within(group()).getByRole("combobox", { name: /^Which, / }),
    );
    expect(
      screen.getByRole("option", { name: "optional" }),
    ).toBeInTheDocument();
    await userEvent.click(screen.getByRole("option", { name: "optional" }));

    expect(
      within(group()).getByRole("combobox", { name: /^Which, / }),
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
      within(group()).getByRole("combobox", { name: /^From, / }),
    );
    await userEvent.click(
      screen.getByRole("option", { name: "What the workflow takes" }),
    );
    expect(
      within(group()).getByRole("combobox", { name: /^From, / }),
    ).toHaveTextContent("What the workflow takes");
    expect(
      within(group()).getByRole("combobox", { name: /^Which, / }),
    ).toBeInTheDocument();
    expect(
      within(group()).getByText("Nothing fills it yet."),
    ).toBeInTheDocument();

    await userEvent.click(
      within(group()).getByRole("combobox", { name: /^Which, / }),
    );
    await userEvent.click(screen.getByRole("option", { name: "short" }));
    expect(within(group()).queryByText("Nothing fills it yet.")).toBeNull();

    await userEvent.click(
      within(group()).getByRole("combobox", { name: /^From, / }),
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
      name: /^Value, /,
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
      /This field takes a single line; remove the line break\./,
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

  it("uses a textarea for text with several lines or no line setting and reports a saved constant that does not fit one line", () => {
    binding({ ...TEXT, lines: "several" }, { from: "constant", typed: "a" });
    binding(TEXT, { from: "constant", typed: "b" });
    expect(
      screen
        .getAllByRole("textbox", { name: /^Value, / })
        .map((box) => box.tagName),
    ).toEqual(["TEXTAREA", "TEXTAREA"]);

    const read = `a${String.fromCodePoint(0x0a)}b`;
    binding(
      { ...TEXT, lines: "one" },
      { from: "constant", typed: read, read: JSON.stringify(read) },
    );
    expect(
      within(screen.getAllByRole("rowgroup", { name: "note" })[2]!).getByRole(
        "textbox",
        { name: /^Value, / },
      ),
    ).toHaveAccessibleDescription(
      /This field takes a single line; remove the line break\./,
    );
  });

  it("accepts JSON formatting breaks for many one-line texts and checks each actual value", () => {
    const held = binding(
      { ...TEXT, many: true, lines: "one" },
      { from: "constant", typed: '["Demo Sample"]' },
    );
    const input = within(group()).getByRole("textbox", {
      name: /^Value, /,
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
    expect(input).not.toHaveAccessibleDescription(/remove the line break/);

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
    expect(input).toHaveAccessibleDescription(/remove the line break/);
  });

  it("offers only the fields that fit what is filled, keeping one chosen already in sight", async () => {
    binding(TEXT, { from: "input", path: "long" });

    await userEvent.click(
      within(group()).getByRole("combobox", { name: /^Which, / }),
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
      within(group()).getByRole("combobox", { name: /^Which, / }),
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
      within(group()).getByRole("combobox", { name: /^From, / }),
    );
    await userEvent.click(screen.getByRole("option", { name: "A constant" }));
    expect(held.source).toEqual({ from: "constant", typed: "false" });
    expect(within(group()).queryByRole("textbox")).toBeNull();

    await userEvent.click(
      within(group()).getByRole("switch", { name: /^Value, / }),
    );

    expect(held.source).toEqual({ from: "constant", typed: "true" });
  });

  it("keeps Nothing, Yes, and No separate for an optional yes or no constant", async () => {
    const held = binding(
      { ...TEXT, kind: "yes_no", mustBeGiven: false },
      { from: "none" },
    );
    await userEvent.click(
      within(group()).getByRole("combobox", { name: /^From, / }),
    );
    await userEvent.click(screen.getByRole("option", { name: "A constant" }));
    expect(held.source).toEqual({ from: "constant", typed: "null" });
    const choice = within(group()).getByRole("combobox", {
      name: /^Value, /,
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
      within(group()).getByRole("textbox", { name: /^Value, / }),
      "12345678901234567890123456789012345678",
    );

    expect(held.source).toEqual({
      from: "constant",
      typed: "12345678901234567890123456789012345678",
    });
  });

  it("says against a number typed that the server would refuse", async () => {
    binding({ ...TEXT, kind: "number" }, { from: "constant", typed: "" });
    const box = within(group()).getByRole("textbox", { name: /^Value, / });

    await userEvent.type(box, "1e5");

    expect(box).toHaveAttribute("aria-invalid", "true");
    expect(box).toHaveAccessibleDescription(
      "A number is written in digits, with a point before any fraction, and nothing else; a zero is never written with a minus sign.",
    );
    expect(group()).not.toHaveAttribute("aria-describedby");
  });

  it("says nothing against a number typed that the server takes", async () => {
    binding({ ...TEXT, kind: "number" }, { from: "constant", typed: "" });
    const box = within(group()).getByRole("textbox", { name: /^Value, / });

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
      within(group()).getByRole("textbox", { name: /^Value, / }),
    ).toHaveAccessibleDescription(
      "What you wrote contains a text-direction character, which can't be sent to a model.",
    );
  });

  it("says a constant as it was read nothing against, however it is typed here", () => {
    binding(
      { ...TEXT, kind: "number" },
      { from: "constant", typed: "1e21", read: "1e21" },
    );

    expect(
      within(group()).getByRole("textbox", { name: /^Value, / }),
    ).not.toHaveAttribute("aria-describedby");
  });

  /** One message describes every control participating in the same binding. */
  it.each([
    ["nothing yet", TEXT, { from: "none" }],
    ["somewhere", TEXT, { from: "input", path: "long" }],
    [
      "a constant of yes or no",
      { ...TEXT, kind: "yes_no" },
      { from: "constant", typed: "true" },
    ],
    ["a constant typed", TEXT, { from: "constant", typed: "x" }],
  ] as const)(
    "describes both binding controls with the same problem once when filled from %s",
    (_case, target, source) => {
      binding(target, source, true, ["contentProblem.input_unbound"]);

      const boxes = [
        ...within(group()).queryAllByRole("combobox"),
        ...within(group()).queryAllByRole("textbox"),
        ...within(group()).queryAllByRole("switch"),
      ].sort((one, other) =>
        one.compareDocumentPosition(other) & Node.DOCUMENT_POSITION_FOLLOWING
          ? -1
          : 1,
      );
      expect(boxes.length).toBeGreaterThan(0);
      for (const box of boxes) {
        expect(box).toHaveAccessibleDescription("Nothing fills it yet.");
        if (box.getAttribute("role") !== "switch") {
          expect(box).toHaveAttribute("aria-invalid", "true");
        }
      }
      expect(
        new Set(boxes.map((box) => box.getAttribute("aria-describedby"))).size,
      ).toBe(1);
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
    expect(within(group()).getByText("A constant")).toBeInTheDocument();
    expect(within(group()).getByText("Yes")).toBeInTheDocument();
    expect(screen.queryByRole("switch")).toBeNull();
    expect(screen.queryByRole("combobox")).toBeNull();
  });
});
