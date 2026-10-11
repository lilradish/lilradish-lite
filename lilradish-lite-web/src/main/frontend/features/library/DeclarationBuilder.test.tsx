import { iconButtonClasses } from "@mui/material/IconButton";
import { ThemeProvider } from "@mui/material/styles";
import {
  act,
  cleanup,
  fireEvent,
  render as renderNow,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { useState, type ContextType, type ReactNode } from "react";
import { beforeEach, describe, expect, it, onTestFinished, vi } from "vitest";

import type {
  DeclaredField,
  ListVersion,
  SentField,
} from "../../api/declaration";
import { RequestFailed } from "../../api/problem";
import { useAction } from "../../lib/request/useAction";
import { theme } from "../../lib/theme/theme";
import { drawnAtDepth, drawnOf } from "../../testutil/depths";
import { serving } from "../../testutil/answering";
import { NO_GROUND } from "../../testutil/grounds";
import { noticesIn } from "../../testutil/notices";
import { deferred } from "../../testutil/deferred";
import { DeclarationBuilder } from "./DeclarationBuilder";
import { DraftStatusContext } from "./useDraftStatus";
vi.mock("../../app/standing/RequireGroup", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../app/standing/RequireGroup")>()),
  useGroup: () => ({ groupId: "00000003-0000-4000-8000-000000000a71" }),
}));
import { draftOf, type Demands, type DraftField } from "./declarationDrafts";
vi.mock("../../app/standing/StandingContext", () => {
  const value = { acts: new Set(), groups: [], userId: "reader" };
  return {
    useStandingRead: () => ({
      value,
      loading: false,
      problem: null,
      reload: vi.fn(),
    }),
  };
});
import QUESTION from "./questionDemands.json";
import WORKFLOW from "./workflowDemands.json";

const TAKES = QUESTION.takes as Demands;

const GIVES_HINT =
  "What a run of this workflow gives back when it's done. It's shown as the run's result, and it's what a step gets when it runs this workflow inside another. Leave it empty for a workflow that only acts.";

const GIVES = QUESTION.gives as Demands;

/** A half whose values stood where they were made, as a workflow's is. */
const WORKFLOW_GIVES = WORKFLOW.gives as Demands;

const LOAD_SAVED = "Load the saved draft";

const WRITTEN_SINCE =
  "Somebody changed this draft since it was read; nothing was saved.";

const OLDER = "00000007-0000-4000-8000-000000000c31";

const NEWER = "00000007-0000-4000-8000-000000000c32";

const CHANNELS = "00000007-0000-4000-8000-000000000c33";

const LISTS: readonly ListVersion[] = [
  { versionName: null, name: "Categories", versionId: NEWER, number: 2 },
  { versionName: null, name: "Channels", versionId: CHANNELS, number: 1 },
];

const COMPLAINT: DeclaredField = {
  fieldId: "0000000b-0000-4000-8000-000000000c31",
  name: "complaint",
  label: "The complaint",
  help: "As written.",
  kind: "text",
  longest: 4000,
  many: false,
  mustBeGiven: true,
};

const ADDRESSES: DeclaredField = {
  fieldId: "0000000b-0000-4000-8000-000000000c33",
  name: "addresses",
  kind: "text",
  many: true,
  most: 3,
  mustBeGiven: true,
};

const SENDER: DeclaredField = {
  fieldId: "0000000b-0000-4000-8000-000000000c32",
  name: "sender",
  kind: "fields",
  many: false,
  mustBeGiven: false,
  fields: [ADDRESSES],
};

const DOCUMENTS: DeclaredField = {
  fieldId: "0000000b-0000-4000-8000-000000000c36",
  name: "documents",
  kind: "file",
  types: [".pdf"],
  maximumBytes: 1048576,
  many: true,
  most: 3,
  mustBeGiven: true,
};

const RECEIVED: DeclaredField = {
  fieldId: "0000000b-0000-4000-8000-000000000c35",
  name: "received",
  kind: "moment",
  many: false,
  mustBeGiven: true,
};

const CATEGORY: DeclaredField = {
  fieldId: "0000000b-0000-4000-8000-000000000c34",
  name: "category",
  kind: "term",
  many: false,
  list: {
    versionName: null,
    name: "Categories",
    versionId: OLDER,
    number: 1,
    standing: "in_service",
    newer: { versionName: null, versionId: NEWER, number: 2 },
  },
  mustBeGiven: true,
  stands: "above_confidence",
  floor: 80,
};

function setApart(words: string): string {
  return `${String.fromCodePoint(0x2068)}${words}${String.fromCodePoint(0x2069)}`;
}

function themed({ children }: { readonly children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

/** A field sent back as the server would read it, every member it was sent, under a key of its own. */
function declaredAs(sent: SentField, index: number): DeclaredField {
  const list = LISTS.find((each) => each.versionId === sent.list);
  return {
    fieldId: sent.fieldId ?? `saved-${index}-${sent.name}`,
    name: sent.name,
    kind: sent.kind,
    many: sent.many,
    mustBeGiven: sent.mustBeGiven,
    ...(sent.label === null ? {} : { label: sent.label }),
    ...(sent.help === null ? {} : { help: sent.help }),
    ...(sent.most === null ? {} : { most: sent.most }),
    ...(sent.longest == null ? {} : { longest: sent.longest }),
    ...(sent.lines === undefined ? {} : { lines: sent.lines }),
    ...(sent.types === undefined ? {} : { types: sent.types }),
    ...(sent.maximumBytes == null ? {} : { maximumBytes: sent.maximumBytes }),
    ...(list === undefined
      ? {}
      : { list: { ...list, standing: "in_service" } }),
    ...(sent.stands == null ? {} : { stands: sent.stands }),
    ...(sent.floor == null ? {} : { floor: sent.floor }),
    ...(sent.fields === undefined
      ? {}
      : { fields: sent.fields.map(declaredAs) }),
  };
}

async function render(...parameters: Parameters<typeof renderNow>) {
  let view!: ReturnType<typeof renderNow>;
  await act(async () => {
    view = renderNow(...parameters);
  });
  return view;
}

/** The builder as a host draws it, over the real action it is handed, drawing what each save answers. */
async function building(
  demands: Demands,
  fields: readonly DeclaredField[],
  editable: boolean,
  answer: (
    fields: readonly SentField[],
  ) => Promise<readonly DeclaredField[]> = (sent) =>
    Promise.resolve(sent.map(declaredAs)),
  closeOnRefusal = false,
  host: {
    readonly waiting?: boolean;
    readonly focusOnArrival?: boolean;
    readonly openStored?: boolean;
    readonly hint?: string;
    readonly lists?: readonly ListVersion[];
    readonly shapeLocked?: boolean;
    readonly acceptedWhen?: boolean;
    readonly refusalOf?: (fields: readonly SentField[]) => string | null;
    readonly report?: ReportStatus;
  } = {},
) {
  const available = host.lists ?? LISTS;
  serving({
    "GET /api/groups/00000003-0000-4000-8000-000000000a71/reference-lists/versions":
      [[JSON.stringify({ items: available, more: false }), 200]],
    ...Object.fromEntries(
      available.map((version) => [
        `GET /api/groups/00000003-0000-4000-8000-000000000a71/versions/${version.versionId}`,
        [
          [
            JSON.stringify({
              kind: "reference_list",
              version: { ...version, standing: "in_service" },
              terms: { [version.versionId]: ["urgent", "later"] },
            }),
            200,
          ],
        ],
      ]),
    ),
  });
  if (typeof ResizeObserver === "undefined") laidOutAt(1400, 1600);
  const saved = vi.fn(answer);
  const readAfresh = vi.fn<() => void>();
  const shown: { fields: readonly DeclaredField[] } = { fields };
  let refresh: (
    change?: (fields: readonly DeclaredField[]) => readonly DeclaredField[],
  ) => void = () => undefined;
  function Host() {
    const [held, setHeld] = useState(fields);
    refresh = (change) =>
      setHeld((now) =>
        change === undefined ? now.map((field) => ({ ...field })) : change(now),
      );
    const [open, setOpen] = useState(editable);
    const saving = useAction<readonly DeclaredField[]>(
      (answered) => {
        shown.fields = answered;
        setHeld(answered);
      },
      () => setOpen(!closeOnRefusal && open),
    );
    return (
      <DraftStatusContext.Provider value={host.report ?? null}>
        <DeclarationBuilder
          title="What it takes"
          hint={host.hint}
          heading="h3"
          depth={2}
          demands={demands}
          acceptedWhen={host.acceptedWhen ?? demands === GIVES}
          shapeLocked={host.shapeLocked}
          fields={held}
          scope="spec:declaration"
          editable={open}
          waiting={host.waiting ?? false}
          saveLabel="Save what it takes"
          underway="Saving."
          rule="author_entry"
          action={saving}
          save={(sent) => saved(sent)}
          refusalOf={host.refusalOf}
          focusOnArrival={host.focusOnArrival ?? false}
          onReadAfresh={readAfresh}
        />
      </DraftStatusContext.Provider>
    );
  }
  await act(async () => {
    await render(<Host />, { wrapper: themed });
  });
  if (!host.focusOnArrival && host.openStored !== false) {
    await act(async () => openStoredRows());
  }
  return { saved, shown, readAfresh, refresh };
}

function laidOutAt(
  partWidth: number,
  viewport: number,
  frameWidth = partWidth,
) {
  if (frameWidth > partWidth)
    throw new Error("A nested drawer frame cannot be wider than its Part");
  let part = partWidth;
  let frame = frameWidth;
  const live = new Set<Measured>();
  const widthOf = (node: Element) =>
    node === document.body || node === document.documentElement
      ? viewport
      : node.firstElementChild?.getAttribute("role") === "group"
        ? frame
        : node.parentElement?.parentElement === document.body
          ? part
          : 0;
  vi.spyOn(Element.prototype, "getBoundingClientRect").mockImplementation(
    function (this: Element) {
      return new DOMRect(0, 0, widthOf(this), 0);
    },
  );
  class Measured implements ResizeObserver {
    private readonly notify: ResizeObserverCallback;
    private readonly reported = new Map<Element, number>();
    constructor(notify: ResizeObserverCallback) {
      this.notify = notify;
    }
    observe(target: Element) {
      this.reported.set(target, 0);
      live.add(this);
      queueMicrotask(() => this.deliver());
    }
    unobserve(target: Element) {
      this.reported.delete(target);
    }
    disconnect() {
      this.reported.clear();
      live.delete(this);
    }
    deliver() {
      const changed = [...this.reported].filter(
        ([target, width]) => width !== widthOf(target),
      );
      if (changed.length === 0) return;
      this.notify(
        changed.map(([target]) => {
          const width = widthOf(target);
          this.reported.set(target, width);
          const size = [{ inlineSize: width, blockSize: 0 }];
          return {
            target,
            contentRect: new DOMRect(0, 0, width, 0),
            borderBoxSize: size,
            contentBoxSize: size,
            devicePixelContentBoxSize: size,
          };
        }),
        this,
      );
    }
  }
  vi.stubGlobal("ResizeObserver", Measured);
  return async (nextPart: number, nextFrame = nextPart) => {
    if (nextFrame > nextPart)
      throw new Error("A nested drawer frame cannot be wider than its Part");
    part = nextPart;
    frame = nextFrame;
    await act(async () => {
      for (const observer of live) observer.deliver();
    });
  };
}

async function openStoredRows(): Promise<void> {
  const closed = document.querySelector<HTMLElement>(
    'tbody:not([hidden]) button[aria-expanded="false"]',
  );
  if (closed !== null) await act(async () => fireEvent.click(closed));
}

function part(): HTMLElement {
  const title = [...document.querySelectorAll("h3,h5")].find(
    (node) => node.textContent === "What it takes",
  );
  return title!.closest("section")!;
}

function heading(): HTMLElement {
  return part().querySelector("h3")!;
}

async function row(name: string): Promise<HTMLElement> {
  const current = [
    ...document.querySelectorAll<HTMLElement>('fieldset[id$="-editor"]'),
  ].find(
    (node) => node.querySelector("legend")?.getAttribute("aria-label") === name,
  );
  if (current !== undefined) return current;
  const group = [...part().querySelectorAll("tbody")].find(
    (node) => node.getAttribute("aria-label") === name,
  );
  const closed = group?.querySelector<HTMLElement>(
    ':scope > tr button[aria-expanded="false"]',
  );
  if (closed !== null && closed !== undefined) {
    await act(async () => {
      closed.focus();
      fireEvent.click(closed);
    });
  }
  const editor = [
    ...document.querySelectorAll<HTMLElement>('fieldset[id$="-editor"]'),
  ].find(
    (node) => node.querySelector("legend")?.getAttribute("aria-label") === name,
  );
  if (editor === undefined)
    throw new Error("No actual field editor for " + name);
  return editor;
}

async function legendOf(name: string): Promise<HTMLElement> {
  return (await row(name)).querySelector("legend")!;
}

async function closeEditor(): Promise<void> {
  const modal = screen.queryByRole("dialog");
  if (modal !== null) {
    await userEvent.click(within(modal).getByRole("button", { name: "Close" }));
  } else {
    await userEvent.click(
      within(part()).getByRole("button", { name: /^Close / }),
    );
  }
  await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
}

function save(): HTMLElement {
  return screen.getByRole("button", { name: "Save" });
}

async function pressIn(name: string, button: string): Promise<void> {
  if (button === "Remove") {
    await row(name);
    return userEvent.click(
      screen.getByRole("button", { name: "Remove this field" }),
    );
  }
  return userEvent.click(
    within(await row(name)).getByRole("button", {
      name: `${button}: ${setApart(name)}`,
    }),
  );
}

function fieldFacts(root: HTMLElement): Record<string, string> {
  const line =
    root.tagName === "TR"
      ? root
      : (root.querySelector("tr") ??
        root.closest("tbody")!.querySelector("tr")!);
  const labels = [...line.closest("table")!.querySelectorAll("thead th")].map(
    (head) => head.textContent!,
  );
  return Object.fromEntries(
    [...line.children]
      .slice(1, labels.includes("Open") ? -2 : undefined)
      .map((cell, index) => [labels[index + 1]!, cell.textContent!]),
  );
}

function readRows(): HTMLElement[] {
  return [
    ...part().querySelectorAll<HTMLTableRowElement>("tbody:not([hidden]) > tr"),
  ].filter(
    (row) => !within(row).queryByRole("button", { name: "Add a field" }),
  );
}

type ReportStatus = NonNullable<ContextType<typeof DraftStatusContext>>;

/** Whether the half last told the page it holds anything unsaved, which is what holds Submit. */
function lastSaidUnsaved(report: ReturnType<typeof vi.fn<ReportStatus>>) {
  return report.mock.calls.filter(([, status]) => status !== null).at(-1)?.[1]
    ?.unsaved;
}

function editors(): HTMLElement[] {
  return [...document.querySelectorAll<HTMLElement>('fieldset[id$="-editor"]')];
}

async function removeAndSave(name: string): Promise<void> {
  await pressIn(name, "Remove");
  expect(part().querySelector(`tbody[aria-label="${name}"]`)).not.toBeNull();
  await userEvent.click(save());
  await waitFor(() =>
    expect(part().querySelector(`tbody[aria-label="${name}"]`)).toBeNull(),
  );
}

function folded(name: string): HTMLElement {
  const group = part().querySelector<HTMLElement>(
    `tbody[aria-label="${name}"]`,
  )!;
  return within(group).getByRole("button", { name: /^Open / });
}

function moving(name: string, way: "up" | "down"): Promise<void> {
  const field = part().querySelector<HTMLElement>(
    `tbody[aria-label="${name}"]`,
  )!;
  return userEvent.click(
    within(field).getByRole("button", {
      name: new RegExp(`^Move .+ ${way}$`),
    }),
  );
}

describe("DeclarationBuilder", () => {
  beforeEach(() => laidOutAt(1400, 1600));

  it.each(
    [399, 400].flatMap((width) =>
      [undefined, "", "root_name", "\u05e9\u05dd"].map(
        (label) => [width, label] as const,
      ),
    ),
  )(
    "names the outermost standing field at width %s with label %s",
    async (width, label) => {
      laidOutAt(width, 1600);
      await building(
        GIVES,
        [
          {
            ...SENDER,
            name: "root_name",
            label,
            stands: "always",
            fields: [
              {
                ...SENDER,
                fieldId: "middle",
                name: "middle",
                label: "Middle label",
                fields: [ADDRESSES],
              },
            ],
          },
        ],
        false,
      );
      const lines = readRows();
      const inherited =
        width < 400 ? lines[2].firstElementChild! : lines[2].lastElementChild!;
      expect(inherited).toHaveTextContent(
        label && label !== "root_name"
          ? `Same as ${label} \u00b7 root_name`
          : "Same as root_name",
      );
      expect(inherited).not.toHaveTextContent("Middle label");
      expect(inherited.querySelectorAll("bdi").length).toBeGreaterThan(0);
      expect(inherited.querySelector("code")?.textContent ?? null).toBe(
        label && label !== "root_name" ? "root_name" : null,
      );
      expect(part().querySelector("input, textarea, button")).toBeNull();
    },
  );

  /** A value given back standing as its root does, two levels down, with the root renamed and labelled unsaved. */
  async function standingUnderRenamedRoot(width: number) {
    laidOutAt(width, 1600);
    const { saved } = await building(
      GIVES,
      [
        {
          ...SENDER,
          stands: "always",
          fields: [
            {
              ...SENDER,
              fieldId: "middle",
              name: "middle",
              fields: [ADDRESSES],
            },
          ],
        },
      ],
      true,
    );
    await act(async () =>
      fireEvent.change(
        within(await row("sender")).getByRole("textbox", { name: "Name" }),
        { target: { value: "root_name" } },
      ),
    );
    await act(async () =>
      fireEvent.change(
        within(await row("root_name")).getByRole("textbox", { name: "Label" }),
        { target: { value: "Root label" } },
      ),
    );
    const leaf = part().querySelector<HTMLElement>(
      'tbody[aria-label="addresses"]',
    )!;
    return { saved, leaf };
  }

  it.each([399, 400])(
    "updates descendant standing names with unsaved root edits at width %s",
    async (width) => {
      const { saved, leaf } = await standingUnderRenamedRoot(width);

      expect(leaf).toHaveTextContent("Same as Root label \u00b7 root_name");
      expect(saved).not.toHaveBeenCalled();
    },
  );

  it.each([399, 400])(
    "asks a field two levels below the root nothing of what it is accepted when, at width %s",
    async (width) => {
      const { saved } = await standingUnderRenamedRoot(width);

      await closeEditor();

      expect(
        within(await row("addresses")).queryByRole("combobox", {
          name: "Accepted when",
        }),
      ).toBeNull();
      expect(saved).not.toHaveBeenCalled();
    },
  );

  it.each([399, 400])(
    "keeps descendant standing on the root's unsaved names while a middle field's label changes, at width %s",
    async (width) => {
      const { saved, leaf } = await standingUnderRenamedRoot(width);
      await closeEditor();

      await act(async () =>
        fireEvent.change(
          within(await row("middle")).getByRole("textbox", { name: "Label" }),
          { target: { value: "Other parent" } },
        ),
      );

      expect(leaf).toHaveTextContent("Same as Root label \u00b7 root_name");
      expect(leaf).not.toHaveTextContent("Same as Other parent");
      expect(saved).not.toHaveBeenCalled();
    },
  );

  it.each([399, 400])(
    "drops the root's label from descendant standing once it is cleared after the root was closed and opened again, at width %s",
    async (width) => {
      const { saved, leaf } = await standingUnderRenamedRoot(width);
      await closeEditor();

      await act(async () =>
        fireEvent.change(
          within(await row("root_name")).getByRole("textbox", {
            name: "Label",
          }),
          { target: { value: "" } },
        ),
      );

      expect(leaf).toHaveTextContent("Same as root_name");
      expect(leaf).not.toHaveTextContent("Root label");
      expect(saved).not.toHaveBeenCalled();
    },
  );

  it.each(["What it is", "Lines", "Accepted when"])(
    "focuses the actual %s select when its static label is clicked",
    async (label) => {
      await building(GIVES, [{ ...COMPLAINT, stands: "always" }], true);
      const field = await row("complaint");
      const control = within(field).getByRole("combobox", { name: label });
      const labelElement = [
        ...field.querySelectorAll<HTMLElement>("[id]"),
      ].find(
        (element) =>
          element.id === control.getAttribute("aria-labelledby")?.split(" ")[0],
      )!;
      expect(labelElement.tagName).toBe("SPAN");
      expect(labelElement).not.toHaveAttribute("for");
      window.getSelection()!.removeAllRanges();
      await userEvent.click(labelElement);
      expect(control).toHaveFocus();
      const name = within(field).getByRole("textbox", { name: "Name" });
      const nameLabel = field.querySelector<HTMLLabelElement>(
        `label[for="${name.id}"]`,
      )!;
      await userEvent.click(nameLabel);
      expect(name).toHaveFocus();
    },
  );

  it.each([
    ["none yet", undefined, "None chosen yet"],
    ["always", "always" as const, "Always"],
  ])(
    "shows in its Accepted when choice, standing %s, the words of what is chosen, never an empty box",
    async (_case, stands, shown) => {
      await building(
        GIVES,
        [stands === undefined ? COMPLAINT : { ...COMPLAINT, stands }],
        true,
      );

      const choice = within(await row("complaint")).getByRole("combobox", {
        name: "Accepted when",
      });

      expect(choice).toHaveTextContent(shown);
      expect(choice.textContent!.replaceAll("\u200b", "").trim()).not.toBe("");
      expect(choice).not.toHaveTextContent(
        stands === undefined ? "Always" : "None chosen yet",
      );
    },
  );

  it.each([
    ["no name", null, ""],
    ["a name", "Autumn list", " \u00b7 Autumn list"],
  ])(
    "says where a term is read from as the list and its version, the version with %s, on a row read where the space is narrow",
    async (_case, versionName, named) => {
      laidOutAt(399, 1600);
      await building(
        GIVES,
        [{ ...CATEGORY, list: { ...CATEGORY.list!, versionName } }],
        false,
      );

      const [read] = readRows();
      expect(read).toHaveTextContent(
        `From Categories \u00b7 Version 1${named}`,
      );
      expect(read).not.toHaveTextContent(/, version 1/);
    },
  );

  it("separates the default field name from its hint in saved folded rows and newly added legends", async () => {
    await building(
      TAKES,
      [{ ...COMPLAINT, name: "", label: undefined }],
      true,
      undefined,
      false,
      { openStored: false },
    );
    const folded = screen.getByRole("button", {
      name: `Open ${setApart("Field 1, not named yet")}`,
    });
    expect(folded).toHaveAttribute("aria-expanded", "false");
    const named = within(folded.closest("tbody")!).getByText("Field 1");
    expect(named.tagName).toBe("BDI");
    expect(getComputedStyle(named).fontStyle).not.toBe("italic");
    expect(
      getComputedStyle(
        within(folded.closest("tbody")!).getByText("not named yet"),
      ).fontStyle,
    ).toBe("italic");
    await act(async () => folded.focus());
    await userEvent.keyboard("{Enter}");
    expect(
      screen.queryByRole("button", {
        name: `Open ${setApart("Field 1, not named yet")}`,
      }),
    ).toBeNull();
    expect(await legendOf("Field 1, not named yet")).toHaveTextContent(
      "Field 1, not named yet",
    );
    await userEvent.click(screen.getByRole("button", { name: "Add a field" }));
    const added = await row("Field 2, not named yet");
    expect(within(added).getByRole("textbox", { name: "Name" })).toHaveFocus();
    const legend = await legendOf("Field 2, not named yet");
    expect(within(legend).getByText("Field 2").tagName).toBe("BDI");
    expect(
      getComputedStyle(within(legend).getByText("not named yet")).fontStyle,
    ).toBe("italic");
  });

  it("keeps a real name matching the default sentence as data", async () => {
    await building(
      TAKES,
      [{ ...COMPLAINT, name: "Field 1, not named yet" }],
      true,
    );
    const legend = await legendOf("Field 1, not named yet");
    const data = within(legend).getByText("Field 1, not named yet");
    expect(data.tagName).toBe("BDI");
    expect(getComputedStyle(data).fontStyle).not.toBe("italic");
    expect(within(legend).queryByText("not named yet")).toBeNull();
  });
  it("reads text without a line setting as several lines, saves one line when chosen, and starts a new text field with one line", async () => {
    const { saved } = await building(TAKES, [COMPLAINT], true);
    const lines = within(await row("complaint")).getByRole("combobox", {
      name: "Lines",
    });
    expect(lines).toHaveTextContent("Several lines");

    await userEvent.click(lines);
    await act(async () =>
      screen.getByRole("option", { name: "One line" }).focus(),
    );
    await userEvent.keyboard("{Enter}");
    await userEvent.click(save());

    expect(saved).toHaveBeenCalledTimes(1);
    expect(saved.mock.calls[0]![0][0]).toMatchObject({
      kind: "text",
      lines: "one",
    });
    await waitFor(async () =>
      expect(
        within(await row("complaint")).getByRole("combobox", { name: "Lines" }),
      ).toHaveTextContent("One line"),
    );

    await userEvent.click(screen.getByRole("button", { name: "Add a field" }));
    expect(
      within(await row("Field 2, not named yet")).getByRole("combobox", {
        name: "Lines",
      }),
    ).toHaveTextContent("One line");
  });

  it("marks the row of the field being edited with the picked ground and a 3px working edge on its first cell, and no other row", async () => {
    await building(TAKES, [COMPLAINT, DOCUMENTS], true, undefined, false, {
      openStored: false,
    });
    const complaint = screen.getByRole("rowgroup", { name: "complaint" });
    const documents = screen.getByRole("rowgroup", { name: "documents" });
    const line = (group: HTMLElement) => group.querySelector("tr")!;
    expect(getComputedStyle(line(complaint).cells[0]!).boxShadow).not.toBe(
      "inset 3px 0 0 #1c5b81",
    );

    await userEvent.click(
      within(complaint).getByRole("button", {
        name: `Open ${setApart("The complaint")}`,
      }),
    );

    expect(getComputedStyle(line(complaint)).backgroundColor).toBe(
      "rgb(218, 235, 247)",
    );
    expect(getComputedStyle(line(complaint).cells[0]!).boxShadow).toBe(
      "inset 3px 0 0 #1c5b81",
    );
    expect(getComputedStyle(line(complaint).cells[1]!).boxShadow).not.toBe(
      "inset 3px 0 0 #1c5b81",
    );
    expect(getComputedStyle(line(documents)).backgroundColor).not.toBe(
      "rgb(218, 235, 247)",
    );
    expect(getComputedStyle(line(documents).cells[0]!).boxShadow).not.toBe(
      "inset 3px 0 0 #1c5b81",
    );
  });

  it("shows saved fields with their complete property rows, opens one with the keyboard, and leaves a new field open", async () => {
    await building(TAKES, [COMPLAINT, DOCUMENTS], true, undefined, false, {
      openStored: false,
    });
    const complaint = screen.getByRole("rowgroup", { name: "complaint" });
    const documents = screen.getByRole("rowgroup", { name: "documents" });
    const summary = within(complaint).getByRole("button", {
      name: `Open ${setApart("The complaint")}`,
    });
    expect(complaint.querySelector("tr > td")).toHaveTextContent(
      "The complaint \u00b7 complaint",
    );
    expect(fieldFacts(complaint)).toEqual({
      "What it is": "Text Several lines At most 4,000 characters",
      "How many": "One",
      Required: "Yes",
    });
    expect(
      within(complaint).queryByRole("textbox", { name: "Name" }),
    ).toBeNull();
    expect(
      within(documents).getByRole("button", {
        name: `Open ${setApart("documents")}`,
      }),
    ).toHaveAttribute("aria-expanded", "false");
    expect(fieldFacts(documents)).toEqual({
      "What it is": `File ${setApart(".pdf")} Up to 1 MB each`,
      "How many": "Many, up to 3",
      Required: "Yes",
    });

    await act(async () => summary.focus());
    await userEvent.keyboard("{Enter}");
    expect(
      within(await row("complaint")).getByRole("textbox", { name: "Name" }),
    ).toHaveFocus();
    expect(
      within(documents).queryByRole("textbox", { name: "Name" }),
    ).toBeNull();

    await userEvent.click(screen.getByRole("button", { name: "Add a field" }));
    expect(
      within(
        screen.getByRole("group", { name: "Field 3, not named yet" }),
      ).getByRole("textbox", { name: "Name" }),
    ).toHaveFocus();
  });

  it("keeps a field open with its edit when the same saved declaration is read as a new object", async () => {
    const { refresh } = await building(
      TAKES,
      [COMPLAINT],
      true,
      undefined,
      false,
      {
        openStored: false,
      },
    );
    const field = screen.getByRole("rowgroup", { name: "complaint" });
    await userEvent.click(
      within(field).getByRole("button", {
        name: `Open ${setApart("The complaint")}`,
      }),
    );
    const editor = await row("complaint");
    const name = within(editor).getByRole("textbox", { name: "Name" });
    await userEvent.type(name, "_local");
    const help = within(editor).getByRole("textbox", { name: "Help" });
    await act(async () => help.focus());
    expect(help).toHaveFocus();

    act(refresh);

    expect(help).toHaveFocus();
    expect(within(editor).getByRole("textbox", { name: "Name" })).toHaveValue(
      "complaint_local",
    );
    expect(within(part()).getByText("Unsaved")).toBeVisible();
  });

  it("reads a saved new parent and child under their server identities without duplicating their candidate tree", async () => {
    const { saved, shown } = await building(TAKES, [], true, undefined, false, {
      openStored: false,
    });
    await userEvent.click(screen.getByRole("button", { name: "Add a field" }));
    let editor = screen.getByRole("group", { name: "Field 1, not named yet" });
    await userEvent.clear(
      within(editor).getByRole("textbox", { name: "Name" }),
    );
    await userEvent.paste("parent");
    await userEvent.click(
      within(await row("parent")).getByRole("combobox", { name: "What it is" }),
    );
    await userEvent.click(screen.getByRole("option", { name: "Fields" }));
    await userEvent.click(
      screen.getByRole("button", {
        name: `Add a field inside: ${setApart("parent")}`,
      }),
    );
    editor = screen.getByRole("group", { name: "Field 1, not named yet" });
    await userEvent.clear(
      within(editor).getByRole("textbox", { name: "Name" }),
    );
    await userEvent.paste("child");
    await userEvent.click(save());
    await waitFor(() => expect(shown.fields).toHaveLength(1));
    expect(saved).toHaveBeenCalledTimes(1);
    expect(saved.mock.calls[0]![0][0]).toMatchObject({
      fieldId: null,
      name: "parent",
      fields: [{ fieldId: null, name: "child" }],
    });
    expect(shown.fields[0]).toMatchObject({
      fieldId: "saved-0-parent",
      name: "parent",
      fields: [{ fieldId: "saved-0-child", name: "child" }],
    });
    expect(part().querySelectorAll('tbody[aria-label="parent"]')).toHaveLength(
      1,
    );
    expect(part().querySelectorAll('tbody[aria-label="child"]')).toHaveLength(
      1,
    );
    expect(within(part()).queryByText("Unsaved")).toBeNull();
    await userEvent.clear(
      within(await row("child")).getByRole("textbox", { name: "Help" }),
    );
    await userEvent.paste("Canonical child help.");
    await userEvent.click(save());
    await waitFor(() => expect(saved).toHaveBeenCalledTimes(2));
    expect(saved.mock.calls[1]![0][0]).toMatchObject({
      fieldId: "saved-0-parent",
      fields: [{ fieldId: "saved-0-child", help: "Canonical child help." }],
    });
  });

  it("heads each property once and limits Accepted when to the declared answer table", async () => {
    await building(GIVES, [{ ...SENDER, stands: "always" }, CATEGORY], false);

    expect(
      within(part())
        .getAllByRole("columnheader")
        .map((cell) => cell.textContent),
    ).toEqual(["Field", "What it is", "How many", "Required", "Accepted when"]);
    expect(
      readRows().map((line) => line.lastElementChild?.textContent),
    ).toEqual(["Always", "Same as sender", "Above 80% sure"]);
    expect(within(part()).getAllByRole("table")).toHaveLength(1);
    expect(part().querySelector("input, textarea, button")).toBeNull();
  });

  it("keeps a declaration without Accepted when at four property columns even when it asks for standing", async () => {
    await building(GIVES, [CATEGORY], false, undefined, false, {
      acceptedWhen: false,
    });

    expect(
      within(part())
        .getAllByRole("columnheader")
        .map((cell) => cell.textContent),
    ).toEqual(["Field", "What it is", "How many", "Required"]);
    expect(part()).not.toHaveTextContent("Above 80% sure");
  });

  it.each([399, 400])(
    "reads every never-standing parent and child fact without controls at width %s",
    async (width) => {
      laidOutAt(width, 1600);
      await building(GIVES, [{ ...SENDER, stands: "never" }], false);
      const [parent, child] = readRows();
      expect(parent).toBeDefined();
      expect(child).toBeDefined();
      if (width === 400) {
        expect(fieldFacts(parent!)).toEqual({
          "What it is": "Fields",
          "How many": "One",
          Required: "No",
          "Accepted when": "After review",
        });
        expect(fieldFacts(child!)).toEqual({
          "What it is": "Text Several lines How long not said yet",
          "How many": "Many, up to 3",
          Required: "Yes",
          "Accepted when": "Same as sender",
        });
        expect(
          [...part().querySelectorAll("thead th")].map(
            (cell) => cell.textContent,
          ),
        ).toEqual([
          "Field",
          "What it is",
          "How many",
          "Required",
          "Accepted when",
        ]);
      } else {
        expect(parent).toHaveTextContent("sender");
        expect(parent).toHaveTextContent("Fields");
        expect(parent).toHaveTextContent("One");
        expect(parent).toHaveTextContent("Optional");
        expect(parent).toHaveTextContent("Accepted when After review");
        expect(child).toHaveTextContent("addresses");
        expect(child).toHaveTextContent(
          "Text, several lines, How long not said yet",
        );
        expect(child).toHaveTextContent("Many, up to 3");
        expect(child).toHaveTextContent("Required");
        expect(child).toHaveTextContent("Accepted when Same as sender");
        expect(part().querySelectorAll("thead th")).toHaveLength(0);
      }
      expect(part().querySelector("input, textarea, button")).toBeNull();
    },
  );

  it.each([399, 400])(
    "reads the missing confidence at container width %s without inventing a number",
    async (width) => {
      laidOutAt(width, 1600);
      await building(
        GIVES,
        [{ ...COMPLAINT, stands: "above_confidence" }],
        false,
      );

      expect(readRows()[0]).toHaveTextContent("Above a confidence not set yet");
      expect(part()).not.toHaveTextContent("NaN");
      expect(part().querySelector("input, textarea, button")).toBeNull();
    },
  );

  it.each(
    [399, 400].flatMap((width) =>
      ["01", "1e4", " 101 ", "\u202ebad"].map(
        (typed) => [width, typed] as const,
      ),
    ),
  )(
    "keeps the invalid confidence %s/%s isolated through closing and reopening, then saves a corrected number",
    async (width, typed) => {
      laidOutAt(width, 1600);
      const { saved } = await building(
        GIVES,
        [{ ...COMPLAINT, stands: "above_confidence", floor: 80 }],
        true,
      );
      await act(async () =>
        fireEvent.change(
          within(await row("complaint")).getByRole("textbox", {
            name: "Above, in percent",
          }),
          { target: { value: typed } },
        ),
      );

      expect(await row("complaint")).toHaveTextContent(
        "A confidence is a whole number from 1 to 100.",
      );
      expect(save()).toHaveAttribute("aria-disabled", "true");
      await closeEditor();
      const closed = screen.getByRole("rowgroup", { name: "complaint" });

      expect(closed).toHaveTextContent("Above 80% sure");
      expect(closed.querySelector("input, textarea")).toBeNull();
      expect(within(closed).getByText("Unsaved")).toBeInTheDocument();
      expect(
        closed.querySelector('[data-testid="ErrorOutlineOutlinedIcon"]'),
      ).not.toBeNull();
      expect(closed).toHaveAccessibleDescription(
        expect.stringContaining("This field keeps the half from being saved"),
      );
      expect(screen.queryByRole("button", { name: "Save" })).toBeNull();
      expect(saved).not.toHaveBeenCalled();

      await userEvent.click(
        within(closed).getByRole("button", {
          name: `Open ${setApart("The complaint")}`,
        }),
      );
      const floor = within(await row("complaint")).getByRole("textbox", {
        name: "Above, in percent",
      });
      expect(floor).toHaveValue(typed);
      await act(async () =>
        fireEvent.change(floor, { target: { value: "81" } }),
      );

      expect(closed).toHaveTextContent("Above 80% sure");
      expect(await row("complaint")).not.toHaveTextContent(
        "A confidence is a whole number from 1 to 100.",
      );
      await userEvent.click(save());
      await waitFor(() => expect(saved).toHaveBeenCalledOnce());
      expect(saved).toHaveBeenCalledWith([
        expect.objectContaining({ stands: "above_confidence", floor: 81 }),
      ]);
    },
  );

  it("uses its container for the narrow summary and keeps one editor and its draft across the threshold", async () => {
    const resizeTo = laidOutAt(399, 1600);
    const { saved } = await building(
      GIVES,
      [{ ...SENDER, stands: "always" }, CATEGORY],
      true,
      undefined,
      false,
      { openStored: false },
    );
    const category = screen.getByRole("rowgroup", { name: "category" });

    expect(within(part()).queryByRole("columnheader")).toBeNull();
    expect(category).toHaveTextContent("Accepted when Above 80% sure");
    expect(
      screen.getByRole("rowgroup", { name: "addresses" }),
    ).toHaveTextContent("Accepted when Same as sender");
    expect(
      screen.getByRole("rowgroup", { name: "addresses" }),
    ).not.toHaveTextContent("Accepted when \u2014");
    expect(
      screen.getByRole("rowgroup", { name: "addresses" }).firstElementChild!
        .firstElementChild,
    ).toHaveStyle({ paddingLeft: "28px" });
    const editor = await row("category");
    const help = within(editor).getByRole("textbox", { name: "Help" });
    await userEvent.type(help, "Local help.");
    await act(async () => help.focus());

    await resizeTo(400);

    expect(part().querySelectorAll("th")).toHaveLength(7);
    expect(part().querySelectorAll("table")).toHaveLength(1);
    expect(
      within(await row("category")).getByRole("textbox", { name: "Help" }),
    ).toBe(help);
    expect(help).toHaveValue("Local help.");
    expect(help).toHaveFocus();
    const ids = [...part().querySelectorAll("[id]")].map(
      (element) => element.id,
    );
    expect(ids.length).toBeGreaterThan(0);
    expect(new Set(ids).size).toBe(ids.length);
    expect(saved).not.toHaveBeenCalled();
  });

  it.each(["Escape", "Close", "backdrop"] as const)(
    "keeps the same selected editor across actual containers and retains raw input after %s, dropping only Cancel",
    async (closing) => {
      const resize = laidOutAt(1400, 1600);
      const { saved } = await building(TAKES, [COMPLAINT], true);
      const editor = await row("complaint");
      const help = within(editor).getByRole("textbox", { name: "Help" });
      await userEvent.type(help, " Raw candidate.");
      await act(async () => help.focus());
      await resize(900);
      const dialog = await screen.findByRole("dialog");
      expect(dialog).toContainElement(editor);
      expect(within(editor).getByRole("textbox", { name: "Help" })).toBe(help);
      expect(help).toHaveValue("As written. Raw candidate.");
      expect(help).toHaveFocus();
      if (closing === "Escape") await userEvent.keyboard("{Escape}");
      else if (closing === "Close")
        await userEvent.click(screen.getByRole("button", { name: "Close" }));
      else
        await userEvent.click(
          document.querySelector<HTMLElement>(".MuiBackdrop-root")!,
        );
      await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
      expect(saved).not.toHaveBeenCalled();
      const reopened = within(await row("complaint")).getByRole("textbox", {
        name: "Help",
      });
      expect(reopened).toHaveValue("As written. Raw candidate.");
      await resize(1400);
      await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
      expect(
        within(await row("complaint")).getByRole("textbox", { name: "Help" }),
      ).toBe(reopened);
      await userEvent.click(screen.getByRole("button", { name: "Cancel" }));
      expect(saved).not.toHaveBeenCalled();
      expect(
        within(await row("complaint")).getByRole("textbox", { name: "Help" }),
      ).toHaveValue("As written.");
      expect(within(part()).queryByText("Unsaved")).toBeNull();
    },
  );

  it.each([399, 1400])(
    "opens an empty drawer from the table's last row at width %s and adds its summary only after a successful whole-half save",
    async (width) => {
      laidOutAt(width, 1600);
      const { saved, shown } = await building(
        TAKES,
        [COMPLAINT],
        true,
        undefined,
        false,
        { openStored: false },
      );
      const table = within(part()).getByRole("table");
      const add = within(table).getByRole("button", { name: "Add a field" });
      const last = table.querySelector("tbody:last-child > tr:last-child");
      expect(last).toContainElement(add);
      const fieldCells = within(table)
        .getByRole("rowgroup", { name: "complaint" })
        .querySelector("tr")!.children;
      expect(add.closest("td")!.colSpan).toBe(fieldCells.length);
      expect(add.closest("td")!.colSpan).toBe(width === 399 ? 3 : 6);
      await userEvent.click(add);
      const editor = await row("Field 2, not named yet");
      const name = within(editor).getByRole("textbox", { name: "Name" });
      expect(name).toHaveValue("");
      expect(name).toHaveFocus();
      expect(within(editor).getByRole("textbox", { name: "Help" })).toHaveValue(
        "",
      );
      if (width === 399)
        expect(screen.getByRole("dialog")).toContainElement(editor);
      else expect(screen.queryByRole("dialog")).toBeNull();
      await userEvent.type(name, "note");
      expect(
        within(table).queryByRole("rowgroup", { name: "note" }),
      ).toBeNull();
      expect(saved).not.toHaveBeenCalled();
      await userEvent.click(screen.getByRole("button", { name: "Save" }));
      await waitFor(() => expect(shown.fields).toHaveLength(2));
      expect(saved).toHaveBeenCalledTimes(1);
      expect(saved.mock.calls[0]![0]).toEqual([
        {
          fieldId: COMPLAINT.fieldId,
          name: "complaint",
          label: "The complaint",
          help: "As written.",
          kind: "text",
          longest: 4000,
          lines: "several",
          many: false,
          most: null,
          mustBeGiven: true,
        },
        {
          fieldId: null,
          name: "note",
          label: null,
          help: null,
          kind: "text",
          longest: null,
          lines: "one",
          many: false,
          most: null,
          mustBeGiven: true,
        },
      ]);
      await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
      expect(
        within(table).getByRole("rowgroup", { name: "note" }),
      ).toBeVisible();
      expect(
        table.querySelector("tbody:last-child > tr:last-child"),
      ).toContainElement(add);
    },
  );

  it("holds unchanged navigation while the host waits and sends nothing", async () => {
    const { saved } = await building(
      TAKES,
      [COMPLAINT, DOCUMENTS],
      true,
      undefined,
      false,
      { waiting: true },
    );
    const next = screen.getByRole("button", { name: "Next field" });
    expect(next).toHaveAttribute("aria-disabled", "true");
    await act(async () => next.focus());
    await userEvent.keyboard("{Enter}");
    expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue(
      "complaint",
    );
    expect(saved).not.toHaveBeenCalled();
  });

  it("holds navigation during its real save and advances only when that response arrives", async () => {
    const answer = deferred<readonly DeclaredField[]>();
    const { saved } = await building(
      TAKES,
      [COMPLAINT, DOCUMENTS],
      true,
      () => answer.promise,
    );
    await userEvent.type(
      screen.getByRole("textbox", { name: "Help" }),
      " Raw.",
    );
    await userEvent.click(screen.getByRole("button", { name: "Next field" }));
    const next = screen.getByRole("button", { name: "Next field" });
    expect(next).toHaveAttribute("aria-disabled", "true");
    await userEvent.click(next);
    expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue(
      "complaint",
    );
    expect(saved).toHaveBeenCalledTimes(1);
    await act(async () =>
      answer.settle([{ ...COMPLAINT, help: "As written. Raw." }, DOCUMENTS]),
    );
    await waitFor(() =>
      expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue(
        "documents",
      ),
    );
    expect(saved).toHaveBeenCalledTimes(1);
  });

  it.each(["Saving", "Controlled"] as const)(
    "navigates unchanged stored fields in the %s owner without saving or resolving",
    async (owner) => {
      const fields = [COMPLAINT, DOCUMENTS, RECEIVED];
      const resolved = vi.fn();
      const changed = vi.fn();
      let saved = vi.fn();
      if (owner === "Saving")
        saved = (await building(TAKES, fields, true)).saved;
      else {
        laidOutAt(1400, 1600);
        await render(
          <DeclarationBuilder
            title="What it takes"
            heading="h3"
            depth={2}
            demands={TAKES}
            fields={fields}
            scope="spec:controlled-navigation"
            editable
            controlled={{
              drafts: fields.map(draftOf),
              details: new Map(),
              unit: "flow",
              resolve: resolved,
              change: changed,
            }}
          />,
          { wrapper: themed },
        );
      }
      await row("documents");
      expect(
        screen.queryByRole("button", {
          name: owner === "Saving" ? "Save" : "Done",
        }),
      ).toBeNull();
      expect(
        screen.getByRole("button", { name: "Remove this field" }),
      ).toBeInTheDocument();
      await userEvent.click(
        screen.getByRole("button", { name: "Previous field" }),
      );
      await waitFor(() =>
        expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue(
          "complaint",
        ),
      );
      expect(screen.getByRole("textbox", { name: "Name" })).toHaveFocus();
      await userEvent.click(screen.getByRole("button", { name: "Next field" }));
      await waitFor(() =>
        expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue(
          "documents",
        ),
      );
      await userEvent.click(screen.getByRole("button", { name: "Next field" }));
      await waitFor(() =>
        expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue(
          "received",
        ),
      );
      expect(screen.queryByRole("button", { name: "Next field" })).toBeNull();
      expect(
        screen.queryByRole("button", {
          name: owner === "Saving" ? "Save" : "Done",
        }),
      ).toBeNull();
      expect(saved).not.toHaveBeenCalled();
      expect(resolved).not.toHaveBeenCalled();
      expect(changed).not.toHaveBeenCalled();
    },
  );

  it.each([900, 1400])(
    "keeps a 409 refusal and Load the saved draft accessible in the active editor at container width %s",
    async (width) => {
      laidOutAt(width, 1600);
      const { readAfresh, saved } = await building(
        TAKES,
        [COMPLAINT],
        true,
        () =>
          Promise.reject(
            new RequestFailed({
              status: 409,
              code: "DRAFT_WRITTEN_SINCE_READ",
            }),
          ),
      );
      const name = within(await row("complaint")).getByRole("textbox", {
        name: "Name",
      });
      await userEvent.type(name, "_raw");
      await userEvent.click(screen.getByRole("button", { name: "Save" }));
      const alert = await screen.findByRole("alert");
      expect(alert).toHaveTextContent(
        "Somebody changed this draft since it was read; nothing was saved.",
      );
      expect(screen.getAllByRole("alert")).toHaveLength(1);
      const reload = screen.getByRole("button", { name: LOAD_SAVED });
      if (width === 900) {
        const dialog = screen.getByRole("dialog");
        expect(dialog).toContainElement(alert);
        expect(dialog).toContainElement(reload);
      } else expect(screen.queryByRole("dialog")).toBeNull();
      expect(name).toHaveValue("complaint_raw");
      expect(saved).toHaveBeenCalledTimes(1);
      expect(readAfresh).not.toHaveBeenCalled();
      await userEvent.click(reload);
      expect(readAfresh).toHaveBeenCalledTimes(1);
      expect(saved).toHaveBeenCalledTimes(1);
    },
  );

  it("puts the keyboard on the field's row once a Save pressed by keyboard is answered and shuts its editor", async () => {
    const { saved } = await building(TAKES, [COMPLAINT], true);
    await userEvent.type(
      within(await row("complaint")).getByRole("textbox", { name: "Help" }),
      " Briefly.",
    );
    await act(async () => save().focus());

    await userEvent.keyboard("{Enter}");

    await waitFor(() => expect(editors()).toHaveLength(0));
    expect(document.activeElement).toBe(folded("complaint"));
    expect(saved).toHaveBeenCalledTimes(1);
  });

  it("moves to the next field only after its candidate saves and retains the current editor when the previous-field save refuses", async () => {
    let attempts = 0;
    const { saved } = await building(
      TAKES,
      [COMPLAINT, DOCUMENTS],
      true,
      (sent) => {
        attempts += 1;
        return attempts === 1
          ? Promise.resolve(sent.map(declaredAs))
          : Promise.reject(
              new RequestFailed({ status: 400, code: "FIELD_NAME_UNUSABLE" }),
            );
      },
    );
    const complaintHelp = within(await row("complaint")).getByRole("textbox", {
      name: "Help",
    });
    const user = userEvent.setup();
    await user.click(complaintHelp);
    await user.paste(" First candidate.");
    await userEvent.click(screen.getByRole("button", { name: "Next field" }));
    await waitFor(() =>
      expect(screen.getByRole("textbox", { name: "Name" })).toHaveFocus(),
    );
    expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue(
      "documents",
    );
    expect(saved).toHaveBeenCalledTimes(1);
    expect(saved.mock.calls[0]![0]).toEqual([
      expect.objectContaining({
        fieldId: COMPLAINT.fieldId,
        help: "As written. First candidate.",
      }),
      expect.objectContaining({ fieldId: DOCUMENTS.fieldId, help: null }),
    ]);
    const documentHelp = within(await row("documents")).getByRole("textbox", {
      name: "Help",
    });
    await user.click(documentHelp);
    await user.paste("Refused raw candidate.");
    await userEvent.click(
      screen.getByRole("button", { name: "Previous field" }),
    );
    await waitFor(() => expect(saved).toHaveBeenCalledTimes(2));
    await screen.findByRole("alert");
    expect(
      within(await row("documents")).getByRole("textbox", { name: "Help" }),
    ).toBe(documentHelp);
    expect(documentHelp).toHaveValue("Refused raw candidate.");
    expect(screen.queryByRole("group", { name: "complaint" })).toBeNull();
  });

  it("closes an invalid unsaved row with its draft facts and warning intact, then reopens that draft", async () => {
    const { saved } = await building(TAKES, [COMPLAINT], true);
    const name = within(await row("complaint")).getByRole("textbox", {
      name: "Name",
    });
    await userEvent.clear(name);
    await userEvent.type(name, "bad name");
    const longest = within(await row("bad name")).getByRole("textbox", {
      name: "How long, in characters",
    });
    await userEvent.clear(longest);
    await userEvent.type(longest, "2019");
    await closeEditor();
    const closed = screen.getByRole("rowgroup", { name: "bad name" });

    expect(closed.querySelector("input, textarea")).toBeNull();
    expect(fieldFacts(closed)["What it is"]).toBe(
      "Text Several lines At most 4,000 characters",
    );
    expect(within(closed).getByText("Unsaved")).toBeInTheDocument();
    expect(
      closed.querySelector('[data-testid="ErrorOutlineOutlinedIcon"]'),
    ).not.toBeNull();
    expect(closed).toHaveAccessibleDescription(
      expect.stringContaining("This field keeps the half from being saved"),
    );
    expect(
      within(closed).getByRole("button", {
        name: `Open ${setApart("The complaint")}`,
      }),
    ).toHaveFocus();
    expect(
      within(await row("bad name")).getByRole("textbox", { name: "Name" }),
    ).toHaveValue("bad name");
    expect(
      within(await row("bad name")).getByRole("textbox", {
        name: "How long, in characters",
      }),
    ).toHaveValue("2019");
    expect(save()).toHaveAttribute("aria-disabled", "true");
    expect(saved).not.toHaveBeenCalled();
  });

  it("uses raw author names once and puts help below them in its supporting type", async () => {
    await building(
      TAKES,
      [{ ...COMPLAINT, name: "first_name", label: undefined }],
      false,
    );
    const cell = readRows()[0]!.firstElementChild!;

    expect(cell).toHaveTextContent(/^first_nameAs written\.$/);
    expect(cell).not.toHaveTextContent("first name");
    expect(
      getComputedStyle(within(cell as HTMLElement).getByText("As written."))
        .fontSize,
    ).toBe("13px");
    expect(
      getComputedStyle(within(cell as HTMLElement).getByText("As written."))
        .color,
    ).toBe("rgb(88, 102, 108)");
  });

  it.each(["01", "1e4", "1.5", "9007199254740993"])(
    "keeps the invalid typed limit %s as written when the row is closed",
    async (typed) => {
      const { saved } = await building(TAKES, [COMPLAINT], true);
      await act(async () =>
        fireEvent.change(
          within(await row("complaint")).getByRole("textbox", {
            name: "How long, in characters",
          }),
          { target: { value: typed } },
        ),
      );
      await closeEditor();

      const closed = screen.getByRole("rowgroup", { name: "complaint" });
      expect(fieldFacts(closed)["What it is"]).toBe(
        "Text Several lines At most 4,000 characters",
      );
      expect(closed.querySelector("input, textarea")).toBeNull();
      expect(screen.queryByRole("button", { name: "Save" })).toBeNull();
      expect(
        within(await row("complaint")).getByRole("textbox", {
          name: "How long, in characters",
        }),
      ).toHaveValue(typed);
      expect(save()).toHaveAttribute("aria-disabled", "true");
      expect(saved).not.toHaveBeenCalled();
    },
  );

  it("reads the same declaration as a narrow summary with the name and help preserved", async () => {
    laidOutAt(399, 1600);
    await building(
      TAKES,
      [{ ...COMPLAINT, name: "first_name", label: undefined }],
      false,
    );

    expect(within(part()).queryByRole("columnheader")).toBeNull();
    expect(readRows()).toHaveLength(1);
    expect(readRows()[0]).toHaveTextContent(
      "first_name \u2014 Text, several lines, at most 4,000 characters \u00b7 One \u00b7 Required",
    );
    expect(readRows()[0]).toHaveTextContent("As written.");
    expect(part().querySelector("input, textarea, button")).toBeNull();
  });

  it("puts each declaration control's fixed words above the control without floating labels", async () => {
    await building(TAKES, [COMPLAINT], true);
    const field = await row("complaint");
    const labels = [
      ...field.querySelectorAll<HTMLElement>('label[for], span[id$="-label"]'),
    ];

    expect(labels.map((label) => label.textContent)).toEqual([
      "Label",
      "Name",
      "What it is",
      "Lines",
      "How long, in characters",
      "Help",
    ]);
    for (const label of labels) {
      const control = label.hasAttribute("for")
        ? document.getElementById(label.getAttribute("for")!)
        : [...field.querySelectorAll('[role="combobox"]')].find((element) =>
            element
              .getAttribute("aria-labelledby")
              ?.split(" ")
              .includes(label.id),
          );
      expect(control).not.toBeNull();
      expect(label.compareDocumentPosition(control!)).toBe(
        Node.DOCUMENT_POSITION_FOLLOWING,
      );
    }
    expect(field.querySelector(".MuiInputLabel-root")).toBeNull();
    expect(getComputedStyle(labels[0]!).fontSize).toBe("12px");
  });

  it("keeps the same shape controls read-only while search allows labels, help and acceptance to change", async () => {
    const { saved } = await building(
      GIVES,
      [CATEGORY],
      true,
      undefined,
      false,
      {
        shapeLocked: true,
      },
    );
    const field = await row("category");
    expect(
      within(field).getByRole("textbox", { name: "Name" }),
    ).toHaveAttribute("readonly");
    expect(
      within(field).getByRole("combobox", { name: "What it is" }),
    ).toHaveAttribute("aria-readonly", "true");
    expect(
      within(field).getByRole("combobox", { name: "From" }),
    ).toHaveAttribute("readonly");
    const required = within(field).getByRole("checkbox", {
      name: "Must be given",
    });
    expect(required).toHaveAttribute("aria-readonly", "true");
    await userEvent.click(required);
    expect(required).toBeChecked();
    expect(
      within(field).getByRole("textbox", { name: "Label" }),
    ).not.toHaveAttribute("readonly");
    expect(
      within(field).getByRole("combobox", { name: "Accepted when" }),
    ).not.toHaveAttribute("aria-readonly");
    const add = within(part()).getByRole("button", { name: "Add a field" });
    expect(add).toHaveAttribute("aria-disabled", "true");
    await act(async () => add.focus());
    await userEvent.keyboard("{Enter}");
    expect(editors()).toEqual([field]);
    expect(part().querySelectorAll("tbody[hidden]")).toHaveLength(0);
    expect(saved).not.toHaveBeenCalled();
    await userEvent.type(
      within(field).getByRole("textbox", { name: "Help" }),
      "Local help.",
    );
    await userEvent.click(save());
    expect(saved).toHaveBeenCalledTimes(1);
    expect(saved.mock.calls[0]![0][0]).toMatchObject({
      name: "category",
      many: false,
      mustBeGiven: true,
      help: "Local help.",
    });
  });

  it("keeps an unsaved field and its open row when only its pin description changes", async () => {
    const { refresh, saved } = await building(TAKES, [CATEGORY], true);
    const field = await row("category");
    await userEvent.type(
      within(field).getByRole("textbox", { name: "Name" }),
      "_local",
    );
    await act(async () =>
      refresh((fields) =>
        fields.map((held) => ({
          ...held,
          list: {
            ...held.list!,
            name: "Categories updated",
            newer: undefined,
          },
        })),
      ),
    );

    expect(within(field).getByRole("textbox", { name: "Name" })).toHaveValue(
      "category_local",
    );
    expect(within(part()).getByText("Unsaved")).toBeVisible();
    expect(saved).not.toHaveBeenCalled();
  });

  it("marks only changed declarations unsaved and leads with Save until the answer is read", async () => {
    const { saved } = await building(
      TAKES,
      [COMPLAINT],
      true,
      undefined,
      false,
      {
        hint: GIVES_HINT,
      },
    );
    const information = within(part()).getByRole("button", {
      name: "About what it gives back",
    });
    expect(within(part()).queryByText("Unsaved")).toBeNull();
    expect(screen.queryByRole("tooltip")).toBeNull();
    expect(screen.queryByRole("button", { name: "Save" })).toBeNull();

    await userEvent.type(
      within(await row("complaint")).getByRole("textbox", { name: "Name" }),
      "_text",
    );
    expect(within(part()).getByText("Unsaved")).toBeInTheDocument();
    expect(information).toBeVisible();
    expect(save()).toHaveClass("MuiButton-contained");
    await userEvent.click(save());
    await waitFor(() =>
      expect(within(part()).queryByText("Unsaved")).toBeNull(),
    );
    expect(screen.queryByRole("button", { name: "Save" })).toBeNull();
    await row("complaint_text");
    expect(screen.queryByRole("button", { name: "Save" })).toBeNull();
    expect(saved).toHaveBeenCalledTimes(1);
    expect(saved.mock.calls[0]![0]).toEqual([
      {
        fieldId: "0000000b-0000-4000-8000-000000000c31",
        name: "complaint_text",
        label: "The complaint",
        help: "As written.",
        kind: "text",
        longest: 4000,
        lines: "several",
        many: false,
        most: null,
        mustBeGiven: true,
      },
    ]);
    expect(information).toBeVisible();
    expect(screen.queryByRole("tooltip")).toBeNull();
  });

  it("keeps drawing the Save of a field changed while its write is out and once the write is refused, as the leading button", async () => {
    const answer = deferred<readonly DeclaredField[]>();
    await building(TAKES, [COMPLAINT], true, () => answer.promise);
    expect(screen.queryByRole("button", { name: "Save" })).toBeNull();
    await userEvent.type(
      within(await row("complaint")).getByRole("textbox", { name: "Help" }),
      " Now.",
    );

    await userEvent.click(save());

    expect(save()).toHaveClass("MuiButton-contained");
    expect(within(part()).getByText("Unsaved")).toBeInTheDocument();
    await act(async () =>
      answer.refuse(
        new RequestFailed({ status: 400, code: "FIELD_NAME_UNUSABLE" }),
      ),
    );
    expect(save()).toHaveClass("MuiButton-contained");
    expect(save()).not.toHaveAttribute("aria-disabled", "true");
    expect(within(part()).getByText("Unsaved")).toBeInTheDocument();
  });

  it("keeps the information beside a read-only declaration without making it editable", async () => {
    const { saved } = await building(
      TAKES,
      [COMPLAINT],
      false,
      undefined,
      false,
      {
        hint: GIVES_HINT,
      },
    );
    const information = within(part()).getByRole("button", {
      name: "About what it gives back",
    });
    expect(information).toHaveAttribute("type", "button");
    expect(part().querySelectorAll("input, textarea")).toHaveLength(0);
    expect(within(part()).getAllByRole("button")).toEqual([information]);
    expect(heading().textContent).toBe("What it takes");
    expect(part()).toHaveAttribute("aria-labelledby", heading().id);

    await userEvent.hover(information);

    const tooltip = await screen.findByRole("tooltip");
    expect(tooltip.textContent).toBe(GIVES_HINT);
    expect(part()).not.toContainElement(tooltip);
    expect(saved).not.toHaveBeenCalled();
    await userEvent.unhover(information);
    await waitFor(() => expect(screen.queryByRole("tooltip")).toBeNull());
  });
  it("reads each field where it may not be written, the fields another holds under it, and offers nothing to change", async () => {
    await building(TAKES, [COMPLAINT, SENDER], false);

    const first = within(part()).getByRole("table", { name: "What it takes" });
    const rows = readRows();
    expect(
      rows.map((each) => each.firstChild?.firstChild?.textContent),
    ).toEqual(["The complaint \u00b7 complaint", "sender", "addresses"]);
    expect(fieldFacts(rows[0]!)).toEqual({
      "What it is": "Text Several lines At most 4,000 characters",
      "How many": "One",
      Required: "Yes",
    });
    expect(rows[0]).toHaveTextContent("As written.");
    expect(fieldFacts(rows[1]!)).toEqual({
      "What it is": "Fields",
      "How many": "One",
      Required: "No",
    });
    expect(first).toContainElement(rows[2]!);
    expect(rows[2]!.firstElementChild).toHaveStyle({ paddingLeft: "36px" });
    expect(rows[1]!.firstElementChild).toHaveStyle({ paddingLeft: "12px" });
    expect(fieldFacts(rows[2]!)).toEqual({
      "What it is": "Text Several lines How long not said yet",
      "How many": "Many, up to 3",
      Required: "Yes",
    });
    expect(part().querySelectorAll("input, textarea, button")).toHaveLength(0);
  });

  it("shows a stored file field without offering controls in read-only mode", async () => {
    await building(TAKES, [DOCUMENTS], false);

    expect(fieldFacts(readRows()[0]!)).toEqual({
      "What it is": `File ${setApart(".pdf")} Up to 1 MB each`,
      "How many": "Many, up to 3",
      Required: "Yes",
    });
    expect(part().querySelectorAll("input, textarea, button")).toHaveLength(0);
  });

  it.each([
    [1025, "1 KB"],
    [11 * 1024, "11 KB"],
    [10 * 1024 * 1024, "10 MB"],
    [2 * 1024 * 1024 * 1024, "2 GB"],
  ])("shows %i bytes as %s in a stored file", async (maximumBytes, size) => {
    await building(TAKES, [{ ...DOCUMENTS, maximumBytes }], false);

    const file = readRows()[0]!;
    expect(fieldFacts(file)["What it is"]).toBe(
      `File ${setApart(".pdf")} Up to ${size} each`,
    );
  });

  it("reads a value given back that must be given by that, and by the confidence it stands above", async () => {
    await building(GIVES, [CATEGORY], false);

    const row = readRows()[0]!;
    expect(fieldFacts(row)).toEqual({
      "What it is": `Term Categories \u00b7 Version 1`,
      "How many": "One",
      Required: "Yes",
      "Accepted when": "Above 80% sure",
    });
    expect(row).not.toHaveTextContent("May be left empty");
  });

  it("reads a value given back that may be left empty as such, saying what is not chosen of it yet", async () => {
    const { longest: _unsaidLongest, ...unsaid } = COMPLAINT;
    await building(GIVES, [{ ...unsaid, mustBeGiven: false }], false);

    const row = readRows()[0]!;
    expect(fieldFacts(row)).toEqual({
      "What it is": "Text Several lines How long not said yet",
      "How many": "One",
      Required: "No",
      "Accepted when": "Not chosen yet",
    });
    expect(row).not.toHaveTextContent("Must be given");
  });

  it.each([
    [false, false, null, "Categories \u00b7 Version 1"],
    [
      false,
      false,
      "Reference label",
      "Categories \u00b7 Version 1 \u00b7 Reference label",
    ],
    [
      false,
      true,
      "قائمة 📦",
      "Categories \u00b7 Version 1 \u00b7 \u0642\u0627\u0626\u0645\u0629 \u{1f4e6}",
    ],
    [true, false, null, "Categories \u00b7 Version 1"],
    [
      true,
      false,
      "Reference label",
      "Categories \u00b7 Version 1 \u00b7 Reference label",
    ],
  ] as const)(
    "reads the current term version label when editable=%s nested=%s name=%s",
    async (editable, nested, versionName, expected) => {
      const term: DeclaredField = {
        ...CATEGORY,
        list: { ...CATEGORY.list!, versionName },
      };
      const { saved } = await building(
        GIVES,
        nested ? [{ ...SENDER, fields: [term] }] : [term],
        editable,
        undefined,
        false,
        { openStored: false },
      );
      const facts = editable
        ? screen.getByRole("rowgroup", { name: "category" })
        : readRows().at(-1)!;
      expect(fieldFacts(facts)["What it is"]).toBe(`Term ${expected}`);
      const name = facts.querySelector("td:nth-child(2) bdi ~ bdi");
      expect(facts.querySelector("td:nth-child(2) bdi")).toHaveTextContent(
        "Categories",
      );
      if (versionName === null) {
        expect(name).toBeNull();
        expect(facts).not.toHaveTextContent("Reference label");
      } else {
        expect(name).toHaveTextContent(versionName);
        expect(name).toHaveAttribute("dir", "auto");
        expect(name).toHaveStyle({ color: "#58666c" });
      }
      expect(facts.querySelector("input, textarea")).toBeNull();
      expect(saved).not.toHaveBeenCalled();
    },
  );

  it("reads a renamed or cleared current term name after the host reads the declaration afresh", async () => {
    const { refresh, saved } = await building(
      GIVES,
      [
        {
          ...CATEGORY,
          list: { ...CATEGORY.list!, versionName: "Earlier label" },
        },
      ],
      false,
    );
    const facts = () => fieldFacts(readRows()[0]!);
    expect(facts()["What it is"]).toBe(
      "Term Categories \u00b7 Version 1 \u00b7 Earlier label",
    );

    await act(async () =>
      refresh(() => [
        {
          ...CATEGORY,
          list: { ...CATEGORY.list!, versionName: "Later label" },
        },
      ]),
    );
    expect(facts()["What it is"]).toBe(
      "Term Categories \u00b7 Version 1 \u00b7 Later label",
    );
    expect(part()).not.toHaveTextContent("Earlier label");

    await act(async () => refresh(() => [CATEGORY]));
    expect(facts()["What it is"]).toBe("Term Categories \u00b7 Version 1");
    expect(part()).not.toHaveTextContent("Later label");
    expect(part().querySelector("td:nth-child(2) bdi ~ bdi")).toBeNull();
    expect(saved).not.toHaveBeenCalled();
  });

  it("says a half declaring nothing declares nothing, and holds a save that would change nothing", async () => {
    await building(TAKES, [], true);

    expect(part()).toHaveTextContent("Nothing is declared.");
    expect(screen.queryByRole("button", { name: "Save" })).toBeNull();
    await userEvent.click(screen.getByRole("button", { name: "Add a field" }));
    expect(save()).toHaveAttribute("aria-disabled", "true");
    expect(await row("Field 1, not named yet")).toHaveTextContent(
      "A field's name is one to 63 lowercase English letters",
    );
  });

  /** Each field a group of its own named by its name, holding the controls its kind and depth take. */
  it("draws a row per field where it may be written, each with the controls its kind and depth take", async () => {
    await building(TAKES, [COMPLAINT, SENDER], true);

    expect(
      within(part()).getByRole("table", { name: "What it takes" }),
    ).not.toContainElement(await row("complaint"));
    expect(
      screen
        .getAllByRole("group")
        .filter((node) => node.tagName === "FIELDSET"),
    ).toHaveLength(1);
    expect(
      within(await row("complaint")).getByRole("textbox", { name: "Name" }),
    ).toHaveValue("complaint");
    expect(
      within(await row("complaint")).getByRole("textbox", {
        name: "How long, in characters",
      }),
    ).toHaveValue("4000");
    expect(
      within(await row("complaint")).getByRole("checkbox", {
        name: "Must be given",
      }),
    ).toBeChecked();
    expect(
      within(await row("complaint")).queryByRole("combobox", {
        name: "Accepted when",
      }),
    ).toBeNull();
    await row("sender");
    expect(
      part().querySelector('tbody[aria-label="addresses"] td'),
    ).toHaveStyle({ paddingLeft: "36px" });
    expect(
      within(await row("addresses")).getByRole("textbox", { name: "At most" }),
    ).toHaveValue("3");
    expect(
      within(await row("sender")).getByRole("button", {
        name: `Add a field inside: ${setApart("sender")}`,
      }),
    ).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Save" })).toBeNull();
  });

  it("draws row moves and adding inside as named icons, keeping removal in the field footer", async () => {
    await building(TAKES, [COMPLAINT, SENDER], true);

    const icons = {
      up: "ArrowUpwardIcon",
      down: "ArrowDownwardIcon",
      inside: "AddIcon",
    } as const;
    for (const name of ["complaint", "sender"]) await row(name);
    const drawn = (
      [
        ["complaint", ["down"]],
        ["sender", ["up", "inside"]],
      ] as const
    ).flatMap(([name, acts]) => {
      const field = setApart(name === "complaint" ? "The complaint" : name);
      const words = {
        up: `Move ${field} up`,
        down: `Move ${field} down`,
        inside: `Add a field inside: ${field}`,
      };
      return acts.map((act) => ({
        act,
        button: screen.getByRole("button", { name: words[act] }),
      }));
    });

    expect(drawn.map(({ button }) => button.textContent)).toEqual(
      drawn.map(() => ""),
    );
    expect(
      drawn.map(({ button }) =>
        button.querySelector("svg")?.getAttribute("data-testid"),
      ),
    ).toEqual(drawn.map(({ act }) => icons[act]));
    expect(
      drawn.map(({ button }) =>
        button.querySelector("svg")?.getAttribute("aria-hidden"),
      ),
    ).toEqual(drawn.map(() => "true"));
    expect(
      drawn
        .filter(({ button }) =>
          button.classList.contains(iconButtonClasses.colorWarning),
        )
        .map(({ act }) => act),
    ).toEqual([]);
    expect(
      screen.queryByRole("button", {
        name: /^Add a field inside: .*complaint/,
      }),
    ).toBeNull();
    expect(
      screen.getByRole("button", { name: "Remove this field" }),
    ).toHaveTextContent("Remove this field");
  });

  /** The icon's name says which field it acts on; the tip beside it needs only say what it does. */
  it.each([
    [`Move ${setApart("sender")} up`, "Move up"],
    [`Move ${setApart("The complaint")} down`, "Move down"],
    [`Move ${setApart("received")} up`, "Move up"],
    [`Add a field inside: ${setApart("sender")}`, "Add a field inside"],
  ])(
    "says what %s does as a tip of its own words once pointed at",
    async (name, tip) => {
      await building(TAKES, [COMPLAINT, SENDER, RECEIVED], true);

      await row(name.includes("sender") ? "sender" : "complaint");
      const button = screen.getByRole("button", { name });
      await userEvent.hover(button);

      expect(button).toHaveAttribute("aria-label", name);
      expect(name).toContain(
        tip === "Add a field inside"
          ? "Add a field inside:"
          : tip === "Move up"
            ? " up"
            : " down",
      );
      expect((await screen.findByRole("tooltip")).textContent).toBe(tip);
    },
  );

  it("says a row's act as a tip once its icon holds the keyboard", async () => {
    await building(TAKES, [COMPLAINT, RECEIVED], true);
    const button = screen.getByRole("button", {
      name: `Move ${setApart("The complaint")} down`,
    });
    const from = within(await row("complaint")).getByRole("textbox", {
      name: "Help",
    });
    // jsdom's focus-visible selector retains the prior button between tests; start from a keyboard input.
    await act(async () => from.focus());
    await act(async () => fireEvent.keyDown(from, { key: "Tab" }));
    await act(async () => button.focus());
    await act(async () => fireEvent.keyUp(button, { key: "Tab" }));

    expect(button).toHaveFocus();
    expect(button.matches(":focus-visible")).toBe(true);
    expect((await screen.findByRole("tooltip")).textContent).toBe("Move down");
  });

  it("offers every declared kind, including file", async () => {
    await building(TAKES, [COMPLAINT], true);

    await userEvent.click(
      within(await row("complaint")).getByRole("combobox", {
        name: "What it is",
      }),
    );

    expect(
      within(screen.getByRole("listbox"))
        .getAllByRole("option")
        .map((option) => option.textContent),
    ).toEqual([
      "Text",
      "Number",
      "Date",
      "Moment",
      "Yes or no",
      "Term",
      "Fields",
      "File",
    ]);
    expect(screen.getByRole("option", { name: "File" })).toBeInTheDocument();
  });

  it("edits a stored file with its extensions and per-file size, then saves byte limits", async () => {
    const { saved, shown } = await building(TAKES, [DOCUMENTS], true);
    const file = await row("documents");
    const types = within(file).getByRole("textbox", {
      name: "File extensions",
    });
    const size = within(file).getByRole("textbox", { name: "Largest file" });

    expect(types).toHaveValue(".pdf");
    expect(size).toHaveValue("1");
    expect(
      within(file).getByRole("combobox", {
        name: "A KB here is 1,024 bytes, and each larger unit 1,024 of the one before.",
      }),
    ).toHaveTextContent("MB");
    expect(within(file).getByRole("textbox", { name: "At most" })).toHaveValue(
      "3",
    );
    expect(screen.queryByRole("button", { name: "Save" })).toBeNull();

    await userEvent.clear(types);
    await userEvent.type(types, ".pdf{enter}.png");
    await userEvent.clear(size);
    await userEvent.type(size, "2");
    await userEvent.click(save());

    expect(saved).toHaveBeenCalledTimes(1);
    expect(saved.mock.calls[0]![0][0]).toMatchObject({
      kind: "file",
      types: [".pdf", ".png"],
      maximumBytes: 2097152,
      most: 3,
    });
    await waitFor(() =>
      expect(shown.fields[0]).toMatchObject({
        types: [".pdf", ".png"],
        maximumBytes: 2097152,
      }),
    );
  });

  it("uses the selected unit when sending a new file field", async () => {
    const { saved } = await building(TAKES, [COMPLAINT], true);
    await userEvent.click(
      within(await row("complaint")).getByRole("combobox", {
        name: "What it is",
      }),
    );
    await userEvent.click(screen.getByRole("option", { name: "File" }));
    const file = await row("complaint");
    await userEvent.type(
      within(file).getByRole("textbox", { name: "File extensions" }),
      ".pdf",
    );
    await userEvent.type(
      within(file).getByRole("textbox", { name: "Largest file" }),
      "3",
    );
    await userEvent.click(
      within(file).getByRole("combobox", {
        name: "A KB here is 1,024 bytes, and each larger unit 1,024 of the one before.",
      }),
    );
    await userEvent.click(screen.getByRole("option", { name: "KB" }));
    await userEvent.click(save());

    expect(saved).toHaveBeenCalledTimes(1);
    expect(saved.mock.calls[0]![0][0]).toMatchObject({
      kind: "file",
      types: [".pdf"],
      maximumBytes: 3072,
    });
    expect(saved.mock.calls[0]![0][0]).not.toHaveProperty("longest");
  });

  it("holds an invalid file in its row before saving", async () => {
    const { saved } = await building(TAKES, [COMPLAINT], true);
    await userEvent.click(
      within(await row("complaint")).getByRole("combobox", {
        name: "What it is",
      }),
    );
    await userEvent.click(screen.getByRole("option", { name: "File" }));

    const file = await row("complaint");
    expect(
      within(file).getByRole("textbox", { name: "File extensions" }),
    ).toHaveValue("");
    expect(
      within(file).getByText(
        "This field keeps the half from being saved: Give 1 to 64 different extensions, one per line. Each is a dot followed by up to 32 letters, digits or single dots, such as .tar.gz.",
      ),
    ).toBeInTheDocument();
    expect(save()).toHaveAttribute("aria-disabled", "true");
    expect(saved).not.toHaveBeenCalled();
  });

  it("describes both invalid file inputs with their hint and error, then retains only the hint once corrected", async () => {
    const { saved } = await building(TAKES, [DOCUMENTS], true);
    const editor = await row("documents");
    const types = within(editor).getByRole("textbox", {
      name: "File extensions",
    });
    const size = within(editor).getByRole("textbox", { name: "Largest file" });
    const typeId = types.id;
    const sizeId = size.id;
    await act(async () =>
      fireEvent.change(types, { target: { value: "pdf" } }),
    );
    await act(async () => fireEvent.change(size, { target: { value: "-1" } }));
    expect(types).toHaveAttribute("aria-invalid", "true");
    expect(types).toHaveAccessibleDescription(
      "This field keeps the half from being saved: Give 1 to 64 different extensions, one per line. Each is a dot followed by up to 32 letters, digits or single dots, such as .tar.gz. One per line, such as .pdf, .msg or .tar.gz.",
    );
    expect(size).toHaveAttribute("aria-invalid", "true");
    expect(size).toHaveAccessibleDescription(
      "A KB here is 1,024 bytes, and each larger unit 1,024 of the one before. A file field's maximum size is a whole number of bytes, at least 1 byte and less than 8,388,608 GB.",
    );
    expect(save()).toHaveAttribute("aria-disabled", "true");
    await act(async () =>
      fireEvent.change(types, { target: { value: ".pdf" } }),
    );
    await act(async () => fireEvent.change(size, { target: { value: "1" } }));
    expect(types.id).toBe(typeId);
    expect(size.id).toBe(sizeId);
    expect(types).not.toHaveAttribute("aria-invalid", "true");
    expect(size).not.toHaveAttribute("aria-invalid", "true");
    expect(types).toHaveAccessibleDescription(
      "One per line, such as .pdf, .msg or .tar.gz.",
    );
    expect(size).toHaveAccessibleDescription(
      "A KB here is 1,024 bytes, and each larger unit 1,024 of the one before.",
    );
    expect(saved).not.toHaveBeenCalled();
  });

  it("stops at a sixty-fifth pasted extension with a long tail, then sends sixty-four", async () => {
    const { saved } = await building(TAKES, [DOCUMENTS], true);
    const types = within(await row("documents")).getByRole("textbox", {
      name: "File extensions",
    });
    const names = Array.from({ length: 65 }, (_, index) => `.x${index}`);
    const tooMany = `${names.join("\n")}\n${".after\n".repeat(1024)}`;
    const split = vi.spyOn(String.prototype, "split");
    try {
      await act(async () =>
        fireEvent.change(types, { target: { value: tooMany } }),
      );

      expect(
        within(await row("documents")).getByText(
          "This field keeps the half from being saved: A file field can accept at most 64 extensions.",
        ),
      ).toBeInTheDocument();
      expect(save()).toHaveAttribute("aria-disabled", "true");
      expect(saved).not.toHaveBeenCalled();
      expect(
        split.mock.calls.some(
          ([separator], index) =>
            String(separator) === "\n" &&
            String(split.mock.contexts[index]) === tooMany,
        ),
      ).toBe(false);
    } finally {
      split.mockRestore();
    }

    await act(async () =>
      fireEvent.change(types, {
        target: { value: names.slice(0, 64).join("\n") },
      }),
    );
    expect(
      within(await row("documents")).queryByText(
        "A file field can accept at most 64 extensions.",
      ),
    ).toBeNull();
    expect(save()).not.toHaveAttribute("aria-disabled", "true");
    await userEvent.click(save());
    await waitFor(() => expect(saved).toHaveBeenCalledTimes(1));
    expect(saved.mock.calls[0]![0][0]).toMatchObject({
      kind: "file",
      types: names.slice(0, 64),
    });
  });

  it("shows the exact byte-bound refusal when the size typed is one past the safe number", async () => {
    const { saved } = await building(
      TAKES,
      [{ ...DOCUMENTS, maximumBytes: Number.MAX_SAFE_INTEGER }],
      true,
    );
    const file = await row("documents");
    const size = within(file).getByRole("textbox", { name: "Largest file" });
    expect(size).toHaveValue(String(Number.MAX_SAFE_INTEGER));

    await act(async () =>
      fireEvent.change(size, {
        target: { value: String(Number.MAX_SAFE_INTEGER + 1) },
      }),
    );

    expect(
      within(file).getByText(
        "This field keeps the half from being saved: A file field's maximum size is a whole number of bytes, at least 1 byte and less than 8,388,608 GB.",
      ),
    ).toBeInTheDocument();
    expect(save()).toHaveAttribute("aria-disabled", "true");
    expect(saved).not.toHaveBeenCalled();
  });

  it("saves a file draft while its byte limit is unchosen and refuses a typed zero", async () => {
    const { saved } = await building(
      TAKES,
      [{ ...DOCUMENTS, maximumBytes: undefined }],
      true,
    );
    const file = await row("documents");
    const size = within(file).getByRole("textbox", { name: "Largest file" });
    expect(size).toHaveValue("");
    expect(size).not.toHaveAttribute("aria-invalid", "true");
    await userEvent.type(size, "0");
    expect(save()).toHaveAttribute("aria-disabled", "true");
    expect(saved).not.toHaveBeenCalled();

    await userEvent.clear(size);
    await userEvent.type(
      within(file).getByRole("textbox", { name: "Name" }),
      "_draft",
    );
    await userEvent.click(save());
    await waitFor(() => expect(saved).toHaveBeenCalledTimes(1));
    expect(saved.mock.calls[0]![0][0]).toMatchObject({
      name: "documents_draft",
      kind: "file",
      types: [".pdf"],
      maximumBytes: null,
    });
  });

  it("asks a value given back what it takes to stand, and a field it holds only whether it must be given", async () => {
    await building(GIVES, [{ ...SENDER, stands: "never" }], true);

    expect(
      within(await row("sender")).getAllByRole("combobox", {
        name: "Accepted when",
      }),
    ).toHaveLength(1);
    expect(
      within(await row("addresses")).queryByRole("combobox", {
        name: "Accepted when",
      }),
    ).toBeNull();
    expect(
      within(await row("addresses")).getByRole("checkbox", {
        name: "Must be given",
      }),
    ).toBeChecked();
  });

  it("asks above which confidence once a value given back stands above a stated one, and asks no field it holds", async () => {
    await building(GIVES, [{ ...SENDER, stands: "never" }], true);

    await userEvent.click(
      within(await row("sender")).getAllByRole("combobox", {
        name: "Accepted when",
      })[0]!,
    );
    await userEvent.click(
      screen.getByRole("option", { name: "Above a stated confidence" }),
    );

    expect(
      within(await row("sender")).getAllByRole("textbox", {
        name: "Above, in percent",
      }),
    ).toHaveLength(1);
    expect(
      within(await row("addresses")).queryByRole("textbox", {
        name: "Above, in percent",
      }),
    ).toBeNull();
  });

  /** What each depth is asked is the host's to say, so a half no value of which stands asks no field that. */
  it("asks at each depth only what the host's demands ask there", async () => {
    await building(WORKFLOW_GIVES, [{ ...SENDER, stands: "never" }], true);

    for (const field of ["sender", "addresses"]) {
      expect(
        within(await row(field)).getByRole("checkbox", {
          name: "Must be given",
        }),
      ).toBeInTheDocument();
      expect(
        within(await row(field)).queryByRole("combobox", {
          name: "Accepted when",
        }),
      ).toBeNull();
    }
    expect(document.querySelectorAll('fieldset[id$="-editor"]')).toHaveLength(
      1,
    );
    expect(
      within(part()).queryByRole("combobox", {
        name: "Accepted when",
      }),
    ).toBeNull();
    expect(
      within(await row("addresses")).getByRole("textbox", { name: "At most" }),
    ).toBeInTheDocument();
  });

  it("sends whether a value given back must be given beside what it takes to stand", async () => {
    const { saved } = await building(GIVES, [CATEGORY], true);

    await userEvent.click(
      within(await row("category")).getByRole("checkbox", {
        name: "Must be given",
      }),
    );
    await userEvent.click(save());

    expect(saved).toHaveBeenCalledTimes(1);
    expect(saved.mock.calls[0]![0][0]).toMatchObject({
      mustBeGiven: false,
      stands: "above_confidence",
      floor: 80,
    });
  });

  it.each([true, false])(
    "keeps a retired pin while searching current metadata, loaded=%s",
    async (loaded) => {
      const { shown } = await building(
        GIVES,
        [
          {
            ...CATEGORY,
            list: {
              ...CATEGORY.list!,
              versionName: "Stored pin",
              standing: "retired",
              newer: undefined,
            },
          },
        ],
        true,
        undefined,
        false,
        {
          lists: loaded
            ? [
                {
                  versionId: OLDER,
                  number: 1,
                  name: "Current list",
                  versionName: "Loaded name",
                },
                ...LISTS,
              ]
            : LISTS,
        },
      );
      const from = within(await row("category")).getByRole("combobox", {
        name: "From",
      });
      await userEvent.click(from);
      expect(screen.queryByRole("option")).toBeNull();
      expect(await row("category")).toHaveTextContent("Stored pin");
      await act(async () =>
        fireEvent.change(from, { target: { value: "Categories" } }),
      );
      await screen.findByRole("option", {
        name: `Categories \u00b7 Version 2`,
      });
      expect(
        screen.getAllByRole("option").map((each) => each.textContent),
      ).toEqual([
        "None chosen yet",
        ...(loaded ? [`Current list \u00b7 Version 1 \u00b7 Loaded name`] : []),
        `Categories \u00b7 Version 2`,
        `Channels \u00b7 Version 1`,
      ]);
      expect(from).toHaveValue("Categories");
      expect(shown.fields[0]!.list?.versionName).toBe("Stored pin");
      expect(await row("category")).toHaveTextContent(
        "The version pinned is retired.",
      );
    },
  );

  it("searches lists independently and reads the chosen complete pin before replacing its newer explanation", async () => {
    await building(GIVES, [CATEGORY], true);
    expect(await row("category")).toHaveTextContent(
      "A newer version of this list is in service: Version 2.",
    );
    expect(await row("category")).not.toHaveTextContent("retired");
    const from = within(await row("category")).getByRole("combobox", {
      name: "From",
    });
    await userEvent.click(from);
    expect(screen.queryByRole("option")).toBeNull();
    await act(async () =>
      fireEvent.change(from, { target: { value: "Categories" } }),
    );
    const newer = await screen.findByRole("option", {
      name: `Categories \u00b7 Version 2`,
    });
    expect(
      screen.getAllByRole("option").map((each) => each.textContent),
    ).toEqual([
      "None chosen yet",
      `Categories \u00b7 Version 2`,
      `Channels \u00b7 Version 1`,
    ]);
    await userEvent.click(newer);
    await waitFor(async () =>
      expect(await row("category")).not.toHaveTextContent(
        "A newer version of this list is in service",
      ),
    );
  });

  it.each([
    [
      "in_service",
      null,
      "A newer version of this list is in service: Version 2.",
    ],
    [
      "in_service",
      "Newest label",
      "A newer version of this list is in service: Version 2 \u00b7 Newest label.",
    ],
    [
      "retired",
      null,
      "The version pinned is retired, and Version 2 is in service. It is kept while this is a draft, and submitting refuses it.",
    ],
    [
      "retired",
      "Newest label",
      "The version pinned is retired, and Version 2 \u00b7 Newest label is in service. It is kept while this is a draft, and submitting refuses it.",
    ],
  ] as const)(
    "names a newer version beside an existing %s pin with name=%s",
    async (standing, versionName, expected) => {
      const { saved } = await building(
        GIVES,
        [
          {
            ...CATEGORY,
            list: {
              ...CATEGORY.list!,
              standing,
              newer: { versionId: NEWER, number: 2, versionName },
            },
          },
        ],
        true,
      );
      const category = await row("category");
      expect(category).toHaveTextContent(expected);
      const name = within(category)
        .queryAllByText("Newest label")
        .find((element) => element.tagName === "BDI");
      if (versionName === null) {
        expect(name).toBeUndefined();
        expect(category).not.toHaveTextContent(/version 2 \u00b7 /i);
      } else {
        expect(name?.tagName).toBe("BDI");
        expect(name).toHaveAttribute("dir", "auto");
        expect(name).toHaveStyle({ color: "#58666c" });
      }
      expect(
        within(category).getByRole("combobox", { name: "From" }),
      ).toHaveValue(`${setApart("Categories")} \u00b7 Version 1`);
      expect(screen.queryByRole("button", { name: "Save" })).toBeNull();
      expect(saved).not.toHaveBeenCalled();
    },
  );

  /** A draft keeps a pin retired since; only submitting refuses it, which is what the row says. */
  it.each([
    [
      { versionName: null, versionId: NEWER, number: 2 },
      "The version pinned is retired, and Version 2 is in service. It is kept while this is a draft, and submitting refuses it.",
    ],
    [
      undefined,
      "The version pinned is retired. It is kept while this is a draft, and submitting refuses it.",
    ],
  ])(
    "says a pin retired since is kept until submitting, and names the newest in service where one is",
    async (newer, said) => {
      const { saved } = await building(
        GIVES,
        [
          {
            ...CATEGORY,
            list: {
              versionName: null,
              name: "Categories",
              versionId: OLDER,
              number: 1,
              standing: "retired",
              ...(newer === undefined ? {} : { newer }),
            },
          },
        ],
        true,
      );

      expect(await row("category")).toHaveTextContent(said);
      expect(await row("category")).not.toHaveTextContent(
        "A newer version of this list is in service",
      );

      await userEvent.type(
        within(await row("category")).getByRole("textbox", { name: "Label" }),
        "Category",
      );
      await userEvent.click(save());

      expect(saved.mock.calls[0]![0][0]!.list).toBe(OLDER);
      expect(saved.mock.calls[0]![0][0]!.fieldId).toBe(CATEGORY.fieldId);
    },
  );

  /** What is sent is exactly the half as written: a field added, named, made a moment and moved up. */
  it("sends the half whole as written, a field added, changed and moved, and draws what the save answered", async () => {
    const { saved, shown } = await building(TAKES, [COMPLAINT], true);

    await userEvent.click(screen.getByRole("button", { name: "Add a field" }));
    await userEvent.type(
      within(await row("Field 2, not named yet")).getByRole("textbox", {
        name: "Name",
      }),
      "received",
    );
    await userEvent.click(
      within(await row("received")).getByRole("combobox", {
        name: "What it is",
      }),
    );
    await userEvent.click(screen.getByRole("option", { name: "Moment" }));
    await userEvent.click(
      within(await row("received")).getByRole("checkbox", {
        name: "Must be given",
      }),
    );
    await userEvent.click(save());
    await waitFor(() => expect(saved).toHaveBeenCalledTimes(1));
    await moving("received", "up");

    expect(saved).toHaveBeenCalledTimes(2);
    expect(saved.mock.calls[1]![0]).toEqual([
      {
        fieldId: "saved-1-received",
        name: "received",
        label: null,
        help: null,
        kind: "moment",
        many: false,
        most: null,
        mustBeGiven: false,
      },
      {
        fieldId: COMPLAINT.fieldId,
        name: "complaint",
        label: "The complaint",
        help: "As written.",
        kind: "text",
        many: false,
        most: null,
        longest: 4000,
        lines: "several",
        mustBeGiven: true,
      },
    ]);
    await waitFor(() =>
      expect(shown.fields.map((field) => field.fieldId)).toEqual([
        "saved-1-received",
        COMPLAINT.fieldId,
      ]),
    );
    expect(
      within(await row("received")).getByRole("combobox", {
        name: "What it is",
      }),
    ).toHaveTextContent("Moment");
    expect(screen.queryByRole("button", { name: "Save" })).toBeNull();
  });

  it("removes a field and the fields it holds, and adds one inside a field of fields", async () => {
    const { saved } = await building(TAKES, [COMPLAINT, SENDER], true);

    await userEvent.click(
      within(await row("sender")).getByRole("button", {
        name: `Add a field inside: ${setApart("sender")}`,
      }),
    );
    expect(document.querySelectorAll('fieldset[id$="-editor"]')).toHaveLength(
      1,
    );
    expect(readRows()).toHaveLength(3);
    expect(
      part().querySelector('tbody[aria-label="addresses"] td'),
    ).toHaveStyle({ paddingLeft: "36px" });
    await userEvent.type(
      within(await row("Field 2, not named yet")).getByRole("textbox", {
        name: "Name",
      }),
      "postcode",
    );
    await userEvent.click(save());

    await waitFor(() => expect(saved).toHaveBeenCalledOnce());
    expect(saved.mock.calls[0]![0].map((field) => field.name)).toEqual([
      "complaint",
      "sender",
    ]);
    expect(
      saved.mock.calls[0]![0][1]!.fields!.map((field) => field.name),
    ).toEqual(["addresses", "postcode"]);
    await removeAndSave("complaint");
    expect(saved).toHaveBeenCalledTimes(2);
    expect(saved.mock.calls[1]![0].map((field) => field.name)).toEqual([
      "sender",
    ]);
    expect(
      saved.mock.calls[1]![0][0]!.fields!.map((field) => field.name),
    ).toEqual(["addresses", "postcode"]);
    expect(screen.queryByRole("group", { name: "complaint" })).toBeNull();
  });

  it("leaves empty move slots at either end and sends nothing for them", async () => {
    const { saved } = await building(TAKES, [COMPLAINT, RECEIVED], true);
    const up = part().querySelector(
      'tbody[aria-label="complaint"] [data-part="up"]',
    )!;
    const down = part().querySelector(
      'tbody[aria-label="received"] [data-part="down"]',
    )!;

    expect(up).toHaveStyle({ width: "32px", height: "32px" });
    expect(down).toHaveStyle({ width: "32px", height: "32px" });
    expect(
      screen.getByRole("button", {
        name: `Move ${setApart("The complaint")} down`,
      }),
    ).not.toHaveAttribute("aria-disabled");

    expect(up.querySelector("button")).toBeNull();
    expect(down.querySelector("button")).toBeNull();

    expect(screen.queryByRole("button", { name: "Save" })).toBeNull();
    expect(saved).not.toHaveBeenCalled();
  });
  it.each([
    [false, 1],
    [false, 2],
    [true, 1],
    [true, 2],
  ] as const)(
    "keeps the confirmed last sibling's down slot empty with an unconfirmed addition nested %s and %s stored siblings",
    async (nested, count) => {
      const confirmed = count === 1 ? [COMPLAINT] : [COMPLAINT, RECEIVED];
      const last = confirmed.at(-1)!;
      const { saved, shown } = await building(
        TAKES,
        nested ? [{ ...SENDER, fields: confirmed }] : confirmed,
        true,
        undefined,
        false,
        { openStored: false },
      );
      const add = async () =>
        nested
          ? within(await row("sender")).getByRole("button", {
              name: `Add a field inside: ${setApart("sender")}`,
            })
          : screen.getByRole("button", { name: "Add a field" });
      await userEvent.click(await add());
      await act(async () =>
        fireEvent.change(screen.getByRole("textbox", { name: "Name" }), {
          target: { value: "not_confirmed" },
        }),
      );
      await act(async () =>
        fireEvent.change(screen.getByRole("textbox", { name: "Help" }), {
          target: { value: "Keep this candidate" },
        }),
      );
      const line = part().querySelector(`tbody[aria-label="${last.name}"]`)!;
      expect(line.querySelector('[data-part="down"] button')).toBeNull();
      expect(line.querySelector('[data-part="down"]')).toBeEmptyDOMElement();
      expect(line.querySelector('[data-part="down"]')).toHaveStyle({
        width: "32px",
        height: "32px",
      });
      expect(
        [...part().querySelectorAll("tbody[aria-label]:not([hidden])")].map(
          (item) => item.getAttribute("aria-label"),
        ),
      ).toEqual([
        ...(nested ? ["sender"] : []),
        ...confirmed.map((field) => field.name),
      ]);
      expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue(
        "not_confirmed",
      );
      expect(screen.getByRole("textbox", { name: "Help" })).toHaveValue(
        "Keep this candidate",
      );
      expect(saved).not.toHaveBeenCalled();
      expect(shown.fields.map((field) => field.name)).toEqual(
        nested ? ["sender"] : confirmed.map((field) => field.name),
      );
    },
  );
  it.each(["waiting", "refused", "invalid stored"] as const)(
    "does not send an ordinary field move while its host is %s, preserving order and candidate text, then moves when released",
    async (held) => {
      const why = "This half cannot replace the saved draft.";
      const firstName = held === "invalid stored" ? "Complaint" : "complaint";
      let refused = held === "refused";
      const host = {
        waiting: held === "waiting",
        refusalOf: () => (refused ? why : null),
      };
      const { saved, shown, refresh } = await building(
        TAKES,
        [{ ...COMPLAINT, name: firstName }, RECEIVED],
        true,
        undefined,
        false,
        host,
      );
      await act(async () =>
        fireEvent.change(
          within(await row(firstName)).getByRole("textbox", { name: "Help" }),
          {
            target: { value: "Do not send this candidate" },
          },
        ),
      );
      await closeEditor();
      const moveName = `Move ${setApart("The complaint")} down`;
      const move = screen.getByRole("button", { name: moveName });
      expect(move).toHaveAttribute("aria-disabled", "true");
      if (held !== "waiting") {
        expect(move).toHaveAccessibleDescription(
          held === "refused"
            ? why
            : "A field's name is one to 63 lowercase English letters, digits and underscores, starting with a letter.",
        );
        const ids = move.getAttribute("aria-describedby")!.split(" ");
        expect(ids).toHaveLength(1);
        expect(document.getElementById(ids[0]!)).toBeVisible();
      }
      await act(async () => fireEvent.click(move));
      await act(async () => move.focus());
      await userEvent.keyboard("{Enter}");
      expect(saved).not.toHaveBeenCalled();
      expect(shown.fields.map((field) => field.name)).toEqual([
        firstName,
        "received",
      ]);
      expect(
        readRows().map((item) =>
          item.closest("tbody")!.getAttribute("aria-label"),
        ),
      ).toEqual([firstName, "received"]);
      expect(
        within(await row(firstName)).getByRole("textbox", { name: "Help" }),
      ).toHaveValue("Do not send this candidate");
      host.waiting = false;
      refused = false;
      await act(async () =>
        refresh((fields) =>
          fields.map((field) => ({
            ...field,
            name:
              field.fieldId === COMPLAINT.fieldId ? "complaint" : field.name,
          })),
        ),
      );
      const released = screen.getByRole("button", { name: moveName });
      expect(released).not.toHaveAttribute("aria-disabled");
      expect(released).not.toHaveAttribute("aria-describedby");
      const editor = await row("complaint");
      await act(async () =>
        fireEvent.change(
          within(editor).getByRole("textbox", { name: "Name" }),
          {
            target: { value: "Complaint" },
          },
        ),
      );
      await act(async () =>
        fireEvent.change(
          within(editor).getByRole("textbox", { name: "Help" }),
          {
            target: { value: "Do not send this candidate" },
          },
        ),
      );
      await closeEditor();
      await userEvent.click(screen.getByRole("button", { name: moveName }));
      await waitFor(() => expect(saved).toHaveBeenCalledTimes(1));
      expect(
        saved.mock.calls[0]![0].map((field) => [field.name, field.help]),
      ).toEqual([
        ["received", null],
        ["complaint", "As written."],
      ]);
      expect(shown.fields.map((field) => field.name)).toEqual([
        "received",
        "complaint",
      ]);
      expect(
        within(await row("Complaint")).getByRole("textbox", { name: "Help" }),
      ).toHaveValue("Do not send this candidate");
    },
  );
  it("keeps both empty move slots for a single stored field", async () => {
    const { saved } = await building(
      TAKES,
      [COMPLAINT],
      true,
      undefined,
      false,
      {
        openStored: false,
      },
    );
    const slots = part().querySelectorAll(
      '[data-part="up"], [data-part="down"]',
    );
    expect(slots).toHaveLength(2);
    expect(
      [...slots].every((slot) => slot.querySelector("button") === null),
    ).toBe(true);
    expect(saved).not.toHaveBeenCalled();
  });
  it("puts narrow field actions on the last line, leaving the name and facts their whole width", async () => {
    laidOutAt(399, 1600);
    await building(TAKES, [COMPLAINT, RECEIVED], true, undefined, false, {
      openStored: false,
    });
    const line = () =>
      part().querySelector('tbody[aria-label="complaint"] tr')!;
    await waitFor(() =>
      expect(line()).toHaveStyle({
        display: "grid",
        gridTemplateColumns: "minmax(0, 1fr) auto",
      }),
    );
    expect(getComputedStyle(line().querySelector("td")!).gridColumn).toBe(
      "1/-1",
    );
    expect(line().lastElementChild).toContainElement(
      screen.getByRole("button", {
        name: `Move ${setApart("The complaint")} down`,
      }),
    );
  });
  it("says a field error visibly once and shares its current description with each held save and the input", async () => {
    const { saved } = await building(TAKES, [COMPLAINT, RECEIVED], true);
    const name = within(await row("complaint")).getByRole("textbox", {
      name: "Name",
    });
    await act(async () =>
      fireEvent.change(name, { target: { value: "Complaint" } }),
    );
    const why =
      "This field keeps the half from being saved: A field's name is one to 63 lowercase English letters, digits and underscores, starting with a letter.";
    expect(screen.getAllByText(why)).toHaveLength(1);
    expect(screen.getByText(why)).toBeVisible();
    const saveButton = save();
    const next = screen.getByRole("button", { name: "Next field" });
    for (const control of [name, saveButton, next]) {
      expect(control).toHaveAccessibleDescription(why);
      expect(
        control
          .getAttribute("aria-describedby")!
          .split(" ")
          .every((id) => document.getElementById(id) !== null),
      ).toBe(true);
    }
    await act(async () => saveButton.focus());
    await userEvent.keyboard("{Enter}");
    expect(saved).not.toHaveBeenCalled();
    await act(async () =>
      fireEvent.change(name, { target: { value: "complaint_renamed" } }),
    );
    expect(screen.queryByText(why)).toBeNull();
    expect(name).not.toHaveAttribute("aria-describedby");
    expect(saveButton).not.toHaveAttribute("aria-describedby");
    expect(
      [...saveButton.parentElement!.parentElement!.querySelectorAll("button")]
        .slice(0, 2)
        .map((button) => button.textContent),
    ).toEqual(["Save", "Cancel"]);
  });
  it.each([false, true])(
    "moves the confirmed half immediately, keeping other candidate text when refused is %s",
    async (refused) => {
      const reply = deferred<readonly DeclaredField[]>();
      const { saved, shown } = await building(
        TAKES,
        [COMPLAINT, RECEIVED],
        true,
        () => reply.promise,
      );
      await act(async () =>
        fireEvent.change(
          within(await row("complaint")).getByRole("textbox", { name: "Help" }),
          { target: { value: "Only typed, not saved" } },
        ),
      );
      await closeEditor();
      await moving("received", "up");
      expect(saved).toHaveBeenCalledTimes(1);
      expect(
        saved.mock.calls[0]![0].map((field) => [field.name, field.help]),
      ).toEqual([
        ["received", null],
        ["complaint", "As written."],
      ]);
      expect(
        readRows().map((line) =>
          line.closest("tbody")!.getAttribute("aria-label"),
        ),
      ).toEqual(["complaint", "received"]);
      if (refused)
        await act(async () =>
          reply.refuse(
            new RequestFailed({
              status: 409,
              code: "DRAFT_WRITTEN_SINCE_READ",
            }),
          ),
        );
      else
        await act(async () =>
          reply.settle(saved.mock.calls[0]![0].map(declaredAs)),
        );
      expect(shown.fields.map((field) => field.name)).toEqual(
        refused ? ["complaint", "received"] : ["received", "complaint"],
      );
      if (refused)
        expect(await screen.findByRole("alert")).toHaveTextContent(
          WRITTEN_SINCE,
        );
      else
        expect(screen.getByRole("status")).toHaveTextContent(
          `${setApart("received")} moved to position 1 of 2.`,
        );
      expect(
        within(await row("complaint")).getByRole("textbox", { name: "Help" }),
      ).toHaveValue("Only typed, not saved");
    },
  );
  it("moves only the confirmed siblings inside their parent and keeps the parent in place", async () => {
    const { saved } = await building(
      TAKES,
      [
        COMPLAINT,
        { ...SENDER, fields: [ADDRESSES, { ...RECEIVED, name: "sent" }] },
      ],
      true,
      undefined,
      false,
      { openStored: false },
    );
    await moving("sent", "up");
    expect(saved).toHaveBeenCalledTimes(1);
    expect(saved.mock.calls[0]![0].map((field) => field.name)).toEqual([
      "complaint",
      "sender",
    ]);
    expect(
      saved.mock.calls[0]![0][1]!.fields!.map((field) => field.name),
    ).toEqual(["sent", "addresses"]);
    await waitFor(() =>
      expect(screen.getByRole("status")).toHaveTextContent(
        `${setApart("sent")} moved to position 1 of 2.`,
      ),
    );
  });

  /** Moving re-inserts a row, which takes focus from whatever it held; focus follows the row moved. */
  it.each([
    ["complaint", "down"],
    ["received", "up"],
  ] as const)(
    "moves %s %s immediately and focuses the other direction once it reaches the end",
    async (name, way) => {
      await building(TAKES, [COMPLAINT, RECEIVED], true);

      await moving(name, way);

      const moved = readRows().map((each) =>
        each.closest("tbody")!.getAttribute("aria-label"),
      );
      expect(moved).toEqual(["received", "complaint"]);
      expect(document.querySelectorAll('fieldset[id$="-editor"]')).toHaveLength(
        1,
      );
      expect(
        screen.getByRole("button", {
          name: `Move ${setApart(name === "complaint" ? "The complaint" : name)} ${way === "up" ? "down" : "up"}`,
        }),
      ).toHaveFocus();
    },
  );

  it("puts focus on the row after the one removed, else the one before, else the one holding it, else the heading", async () => {
    await building(TAKES, [COMPLAINT, SENDER, RECEIVED], true);

    await removeAndSave("sender");
    expect(folded("received")).toHaveFocus();

    await removeAndSave("received");
    expect(folded("complaint")).toHaveFocus();

    await removeAndSave("complaint");
    expect(heading()).toHaveFocus();
  });

  it("focuses a folded neighbor after removal, then the other folded neighbor, then the heading", async () => {
    await building(
      TAKES,
      [COMPLAINT, SENDER, RECEIVED],
      true,
      undefined,
      false,
      {
        openStored: false,
      },
    );
    await userEvent.click(
      within(screen.getByRole("rowgroup", { name: "sender" })).getByRole(
        "button",
        { name: `Open ${setApart("sender")}` },
      ),
    );
    await removeAndSave("sender");
    const received = screen.getByRole("rowgroup", { name: "received" });
    const next = within(received).getByRole("button", {
      name: `Open ${setApart("received")}`,
    });
    expect(next).toHaveFocus();

    await userEvent.click(next);
    await removeAndSave("received");
    const complaint = screen.getByRole("rowgroup", { name: "complaint" });
    const previous = within(complaint).getByRole("button", {
      name: `Open ${setApart("The complaint")}`,
    });
    expect(previous).toHaveFocus();

    await userEvent.click(previous);
    await removeAndSave("complaint");
    expect(heading()).toHaveFocus();
  });

  it("puts focus on the row holding a field removed where it held no other", async () => {
    await building(TAKES, [SENDER], true);

    await removeAndSave("addresses");

    expect(folded("sender")).toHaveFocus();
  });

  it("puts focus in the name of a field added, on the first level or inside another", async () => {
    await building(TAKES, [SENDER], true);

    await userEvent.click(screen.getByRole("button", { name: "Add a field" }));
    expect(
      within(await row("Field 2, not named yet")).getByRole("textbox", {
        name: "Name",
      }),
    ).toHaveFocus();

    await userEvent.click(
      within(await row("sender")).getByRole("button", {
        name: `Add a field inside: ${setApart("sender")}`,
      }),
    );
    const unnamed = screen.getAllByRole("group", {
      name: "Field 2, not named yet",
    });
    expect(unnamed).toHaveLength(1);
    const inside = unnamed[0]!;
    expect(
      part().querySelector(
        'tbody[aria-label="Field 2, not named yet"][hidden] td',
      ),
    ).toHaveStyle({ paddingLeft: "36px" });
    expect(within(inside).getByRole("textbox", { name: "Name" })).toHaveFocus();
  });

  /** Unnamed rows are told apart by their place, and the one holding the save is marked as the one. */
  it("names an unnamed row by its place, marks it as the row holding the save with why, as a warning, and adds no second one beside it", async () => {
    await building(TAKES, [COMPLAINT], true);

    await userEvent.click(screen.getByRole("button", { name: "Add a field" }));
    await userEvent.click(screen.getByRole("button", { name: "Add a field" }));

    const why =
      "This field keeps the half from being saved: A field's name is one to 63 lowercase English letters, digits and underscores, starting with a letter.";
    expect(save()).toHaveAttribute("aria-disabled", "true");
    expect(await row("Field 2, not named yet")).toHaveAccessibleDescription(
      why,
    );
    expect(noticesIn(await row("Field 2, not named yet"))).toEqual([
      { severity: "warning", words: why },
    ]);
    await expect(row("Field 3, not named yet")).rejects.toThrow(
      "No actual field editor for Field 3, not named yet",
    );
    expect(await row("complaint")).not.toHaveAccessibleDescription();
    expect(noticesIn(await row("complaint"))).toEqual([]);
    expect(screen.queryByRole("button", { name: "Save" })).toBeNull();
  });

  it("warns before a field holding fields is made another kind that its fields are then left out", async () => {
    await building(TAKES, [SENDER], true);

    expect(
      within(await row("sender")).getAllByRole("combobox", {
        name: "What it is",
      })[0],
    ).toHaveAccessibleDescription(
      "Changing what it is leaves out the fields it holds; they are sent again only if it holds fields once more.",
    );
    expect(
      within(await row("addresses")).getByRole("combobox", {
        name: "What it is",
      }),
    ).not.toHaveAccessibleDescription();
  });

  /** An edit made while a save is out would be neither sent nor kept once the save answers. */
  it("takes no edit while a save is out, and every row's controls stay where focus can be", async () => {
    let answer: (fields: readonly DeclaredField[]) => void = () => {};
    const { saved } = await building(
      TAKES,
      [COMPLAINT],
      true,
      () =>
        new Promise((resolve) => {
          answer = resolve;
        }),
    );
    const name = within(await row("complaint")).getByRole("textbox", {
      name: "Name",
    });
    await userEvent.type(name, "_text");
    await userEvent.click(save());

    await userEvent.type(name, "_more");
    for (const button of [
      screen.getByRole("button", { name: "Remove this field" }),
      within(part()).getByRole("button", { name: "Add a field", hidden: true }),
    ]) {
      await act(async () => button.focus());
      await userEvent.keyboard("{Enter}");
    }
    const field = await row("complaint_text");
    const boxes = ["Holds many", "Must be given"].map((checkbox) =>
      within(field).getByRole("checkbox", { name: checkbox }),
    );
    for (const box of boxes) {
      await userEvent.click(box);
    }

    expect(name).toHaveValue("complaint_text");
    expect(name).toHaveAttribute("readonly");
    expect(boxes.map((box) => (box as HTMLInputElement).checked)).toEqual([
      false,
      true,
    ]);
    for (const box of boxes) {
      expect(box).toHaveAttribute("aria-readonly", "true");
    }
    expect(document.querySelectorAll('fieldset[id$="-editor"]')).toHaveLength(
      1,
    );
    expect(
      screen.getByRole("button", { name: "Remove this field" }),
    ).toHaveAttribute("aria-disabled", "true");
    answer([COMPLAINT]);
    await waitFor(() =>
      expect(screen.queryByRole("button", { name: "Save" })).toBeNull(),
    );
    expect(name).not.toBeInTheDocument();
    expect(
      within(await row("complaint")).getByRole("checkbox", {
        name: "Holds many",
      }),
    ).toHaveAttribute("aria-readonly", "false");
    expect(saved).toHaveBeenCalledTimes(1);
  });

  /** Refused where it is typed as well as where it arrives, and the reason is said under the save it holds. */
  it.each([
    [
      "Name",
      "Complaint",
      "A field's name is one to 63 lowercase English letters, digits and underscores, starting with a letter.",
    ],
    [
      "How long, in characters",
      "0",
      "A limit is a whole number from 1 to 2,147,483,647.",
    ],
    [
      "How long, in characters",
      "2147483648",
      "A limit is a whole number from 1 to 2,147,483,647.",
    ],
    [
      "Label",
      "l".repeat(129),
      "A label is at most 128 characters on one line, with something in it that shows.",
    ],
  ])(
    "holds the save back while a %s is one the server refuses, saying why",
    async (control, typed, why) => {
      const { saved } = await building(TAKES, [COMPLAINT], true);
      const box = within(await row("complaint")).getByRole("textbox", {
        name: control,
      });

      await userEvent.clear(box);
      // In one input: typed key by key, a long value's keys outlive a timed-out test and land in the next one's box.
      await userEvent.paste(typed);
      await act(async () => save().focus());
      await userEvent.keyboard("{Enter}");

      expect(box).toHaveValue(typed);
      expect(saved).not.toHaveBeenCalled();
      expect(save()).toHaveAttribute("aria-disabled", "true");
      expect(part()).toHaveTextContent(why);
    },
  );

  it("says a refused save beside the half that asked it, under the rule writing asks, and keeps what was typed", async () => {
    await building(TAKES, [COMPLAINT], true, () =>
      Promise.reject(
        new RequestFailed({ status: 400, code: "FIELD_NAME_UNUSABLE" }),
      ),
    );
    const name = within(await row("complaint")).getByRole("textbox", {
      name: "Name",
    });

    await userEvent.type(name, "_reference");
    await userEvent.click(save());

    expect(await within(part()).findByRole("alert")).toHaveTextContent(
      "A field's name is one to 63 lowercase English letters, digits and underscores, starting with a letter.",
    );
    expect(name).toHaveValue("complaint_reference");
    expect(
      within(part()).queryByRole("button", { name: LOAD_SAVED }),
    ).toBeNull();
  });

  /** Somebody else's write is not undone by this one, nor is what was typed here thrown away unasked. */
  it("offers to read the half afresh where the draft was written since it was read, keeping what was typed until that is pressed", async () => {
    const { readAfresh } = await building(TAKES, [COMPLAINT], true, () =>
      Promise.reject(
        new RequestFailed({ status: 409, code: "DRAFT_WRITTEN_SINCE_READ" }),
      ),
    );
    const name = within(await row("complaint")).getByRole("textbox", {
      name: "Name",
    });
    await userEvent.type(name, "_reference");
    await userEvent.click(save());
    expect(await within(part()).findByRole("alert")).toHaveTextContent(
      "Somebody changed this draft since it was read; nothing was saved.",
    );
    expect(name).toHaveValue("complaint_reference");
    expect(readAfresh).not.toHaveBeenCalled();

    await userEvent.click(
      within(part()).getByRole("button", { name: LOAD_SAVED }),
    );

    expect(readAfresh).toHaveBeenCalledTimes(1);
  });

  it("keeps saying a refused save once reading afresh leaves nothing to save, with no Save and no way on to saving, until the next change", async () => {
    const { readAfresh, refresh, saved } = await building(
      TAKES,
      [COMPLAINT],
      true,
      () =>
        Promise.reject(
          new RequestFailed({ status: 409, code: "DRAFT_WRITTEN_SINCE_READ" }),
        ),
    );
    readAfresh.mockImplementation(() =>
      refresh((now) => now.map((field) => ({ ...field, name: "theirs" }))),
    );
    await userEvent.type(
      within(await row("complaint")).getByRole("textbox", { name: "Name" }),
      "_reference",
    );
    await userEvent.click(save());
    await userEvent.click(
      await within(part()).findByRole("button", { name: LOAD_SAVED }),
    );

    expect(within(part()).getByRole("alert")).toHaveTextContent(
      "Somebody changed this draft since it was read; nothing was saved.",
    );
    expect(part().textContent).not.toMatch(/again|load the saved draft/i);
    expect(screen.queryByRole("button", { name: "Save" })).toBeNull();

    await userEvent.type(
      within(await row("theirs")).getByRole("textbox", { name: "Name" }),
      "x",
    );

    expect(within(part()).queryByRole("alert")).toBeNull();
    expect(save()).toBeInTheDocument();
    expect(saved).toHaveBeenCalledTimes(1);
  });

  it("holds the save while another write of the host's is out, which it would be refused beside, and sends nothing", async () => {
    const { saved } = await building(
      TAKES,
      [COMPLAINT],
      true,
      undefined,
      false,
      {
        waiting: true,
      },
    );

    await userEvent.type(
      within(await row("complaint")).getByRole("textbox", { name: "Name" }),
      "_text",
    );
    await act(async () => save().focus());
    await userEvent.keyboard("{Enter}");

    expect(save()).toHaveAttribute("aria-disabled", "true");
    expect(saved).not.toHaveBeenCalled();
  });

  it.each([
    [true, "is on its heading"],
    [false, "is left where it was"],
  ])(
    "where the half is drawn afresh by a press inside it (%s), the keyboard %s",
    async (arriving) => {
      await building(TAKES, [COMPLAINT], true, undefined, false, {
        focusOnArrival: arriving,
      });

      expect(heading() === document.activeElement).toBe(arriving);
    },
  );

  /** The page may read the version again after a refusal and find it no longer written here. */
  it("keeps a refused save said once the half may no longer be written, and puts focus on its heading", async () => {
    await building(
      TAKES,
      [COMPLAINT],
      true,
      () =>
        Promise.reject(
          new RequestFailed({ status: 409, code: "VERSION_STANDING_REFUSES" }),
        ),
      true,
    );

    await userEvent.type(
      within(await row("complaint")).getByRole("textbox", { name: "Name" }),
      "_text",
    );
    await userEvent.click(save());

    expect(await within(part()).findByRole("alert")).toHaveTextContent(
      "That version's standing does not admit this.",
    );
    expect(within(part()).queryByRole("textbox")).toBeNull();
    expect(heading()).toHaveFocus();
  });

  /** A route's half is sent with the steps, so the page holds it and the builder only edits it. */
  it("hands every edit to a host holding the half, and offers no save of its own", async () => {
    const changed = vi.fn<(drafts: DraftField[]) => void>();
    function Host() {
      const [drafts, setDrafts] = useState<readonly DraftField[]>([
        draftOf(COMPLAINT),
      ]);
      return (
        <DeclarationBuilder
          title="What it takes"
          heading="h3"
          depth={2}
          demands={TAKES}
          fields={[COMPLAINT]}
          scope="spec:controlled"
          editable
          controlled={{
            drafts,
            details: new Map(),
            unit: "flow",
            resolve: (_detail, transform) => {
              const next = transform(drafts);
              changed(next);
              setDrafts(next);
            },
            change: (next) => {
              changed(next);
              setDrafts(next);
            },
          }}
        />
      );
    }
    await render(<Host />, { wrapper: themed });

    await userEvent.click(screen.getByRole("button", { name: "Add a field" }));
    await userEvent.type(
      within(await row("Field 2, not named yet")).getByRole("textbox", {
        name: "Name",
      }),
      "note",
    );

    expect(changed).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole("button", { name: "Done" }));
    expect(changed).toHaveBeenLastCalledWith([
      expect.objectContaining({
        fieldId: COMPLAINT.fieldId,
        name: "complaint",
      }),
      expect.objectContaining({ fieldId: null, name: "note" }),
    ]);
    expect(await row("note")).toBeInTheDocument();
    expect(within(part()).queryByRole("button", { name: /^Save/ })).toBeNull();
  });

  it("hands a host holding a half whether each field must be given", async () => {
    const changed = vi.fn<(drafts: DraftField[]) => void>();
    await render(
      <DeclarationBuilder
        title="What it takes"
        heading="h5"
        depth={3}
        demands={WORKFLOW_GIVES}
        fields={[SENDER]}
        scope="spec:controlled"
        editable
        controlled={{
          drafts: [draftOf(SENDER)],
          details: new Map(),
          unit: "flow",
          resolve: (_detail, transform) =>
            changed(transform([draftOf(SENDER)])),
          change: changed,
        }}
      />,
      { wrapper: themed },
    );

    await row("sender");
    await userEvent.click(
      within(await row("addresses")).getByRole("checkbox", {
        name: "Must be given",
      }),
    );

    const read = draftOf(SENDER);
    expect(changed).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole("button", { name: "Done" }));
    expect(changed).toHaveBeenCalledTimes(1);
    expect(changed).toHaveBeenCalledWith([
      { ...read, fields: [{ ...read.fields[0]!, mustBeGiven: false }] },
    ]);
  });

  it.each([
    ["h3", 3, 5],
    ["h5", 5, 3],
  ] as const)(
    "titles the half at the level it is handed, %s, and at no other",
    async (heading, level, other) => {
      await render(
        <DeclarationBuilder
          title="What it takes"
          heading={heading}
          depth={2}
          demands={WORKFLOW_GIVES}
          fields={[SENDER]}
          scope="spec:controlled"
          editable
          controlled={{
            drafts: [draftOf(SENDER)],
            details: new Map(),
            unit: "flow",
            resolve: vi.fn(),
            change: vi.fn(),
          }}
        />,
        { wrapper: themed },
      );

      expect(
        screen.getByRole("heading", { level, name: "What it takes" }),
      ).toBeInTheDocument();
      expect(
        screen.queryByRole("heading", { level: other, name: "What it takes" }),
      ).toBeNull();
    },
  );

  it.each([
    [2, "a block"],
    [3, "a group"],
  ] as const)(
    "draws a half at depth %s as %s and indents the fields another holds in their table",
    async (depth, _drawn) => {
      await render(
        <DeclarationBuilder
          title="What it takes"
          heading="h3"
          depth={depth}
          demands={WORKFLOW_GIVES}
          fields={[SENDER]}
          scope="spec:controlled"
          editable
          controlled={{
            drafts: [draftOf(SENDER)],
            details: new Map(),
            unit: "flow",
            resolve: vi.fn(),
            change: vi.fn(),
          }}
        />,
        { wrapper: themed },
      );

      expect(drawnOf(part())).toEqual(drawnAtDepth(depth));
      expect(
        part().querySelector('tbody[aria-label="sender"] > tr > td'),
      ).toHaveStyle({ paddingLeft: "12px" });
      expect(
        part().querySelector('tbody[aria-label="addresses"] > tr > td'),
      ).toHaveStyle({ paddingLeft: "36px" });
    },
  );

  it("holds one editor in the side panel and leaves the fieldset no frame or ground of its own", async () => {
    await building(TAKES, [SENDER], true);

    const fieldset = await row("sender");

    expect(fieldset.closest("table")).toBeNull();
    expect(fieldset.closest("section")).toHaveAccessibleName(
      "What it takes \u00b7 Field 1 of 2",
    );
    expect(fieldset.parentElement!.tagName).toBe("DIV");
    expect(fieldset.querySelector(":scope > legend")).toHaveTextContent(
      "sender",
    );
    expect(drawnOf(fieldset)).toMatchObject({
      topEdge: "0px",
      leadingEdge: expect.stringMatching(/^0px /),
      ground: NO_GROUND,
      padding: "0px 0px",
    });
  });

  it.each([
    ["Saving", 1400],
    ["Saving", 900],
    ["Controlled", 1400],
    ["Controlled", 900],
  ] as const)(
    "steps past the fields a removed group held to the field after them, in the %s owner at width %s",
    async (owner, width) => {
      laidOutAt(width, 1600);
      const resolved = vi.fn<(drafts: DraftField[]) => void>();
      let saved = vi.fn<(sent: readonly SentField[]) => unknown>();
      if (owner === "Saving")
        saved = (
          await building(TAKES, [SENDER, COMPLAINT], true, undefined, false, {
            openStored: false,
          })
        ).saved;
      else {
        function Host() {
          const [drafts, setDrafts] = useState<readonly DraftField[]>(
            [SENDER, COMPLAINT].map(draftOf),
          );
          return (
            <DeclarationBuilder
              title="What it takes"
              heading="h3"
              depth={2}
              demands={TAKES}
              fields={[SENDER, COMPLAINT]}
              scope="spec:removing-next"
              editable
              controlled={{
                drafts,
                details: new Map(),
                unit: "flow",
                resolve: (_detail, transform) => {
                  const next = transform(drafts);
                  resolved(next);
                  setDrafts(next);
                },
                change: vi.fn(),
              }}
            />
          );
        }
        await render(<Host />, { wrapper: themed });
      }
      await row("sender");
      await userEvent.click(
        screen.getByRole("button", { name: "Remove this field" }),
      );

      await userEvent.click(screen.getByRole("button", { name: "Next field" }));

      await waitFor(() =>
        expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue(
          "complaint",
        ),
      );
      const name = screen.getByRole("textbox", { name: "Name" });
      expect(name).toHaveFocus();
      expect(
        screen.getByRole("heading", {
          level: 4,
          name: `What it takes ${String.fromCodePoint(0xb7)} Field 1 of 1`,
        }),
      ).toBeInTheDocument();
      expect(
        screen.queryByRole("button", {
          name: owner === "Saving" ? "Save" : "Done",
        }),
      ).toBeNull();
      expect(
        screen.getByRole("button", { name: "Remove this field" }),
      ).toBeInTheDocument();
      expect(screen.queryByRole("button", { name: "Next field" })).toBeNull();
      expect(editors()).toHaveLength(1);
      if (width === 900)
        expect(screen.getByRole("dialog")).toContainElement(name);
      else expect(screen.queryByRole("dialog")).toBeNull();
      expect(part().querySelector('tbody[aria-label="sender"]')).toBeNull();
      expect(part().querySelector('tbody[aria-label="addresses"]')).toBeNull();
      if (owner === "Saving") {
        expect(saved).toHaveBeenCalledTimes(1);
        expect(saved.mock.calls[0]![0].map((field) => field.name)).toEqual([
          "complaint",
        ]);
      } else {
        expect(resolved).toHaveBeenCalledTimes(1);
        expect(resolved.mock.calls[0]![0].map((field) => field.name)).toEqual([
          "complaint",
        ]);
      }
    },
  );

  it.each([
    [900, "dialog"],
    [1400, "region"],
  ] as const)(
    "closes a new field's %s-pixel %s by its own Close keeping it unsaved and counted, and Add opens that one again rather than another",
    async (width, form) => {
      laidOutAt(width, 1600);
      const report = vi.fn<ReportStatus>();
      const { saved } = await building(
        TAKES,
        [COMPLAINT],
        true,
        undefined,
        false,
        {
          openStored: false,
          report,
        },
      );
      const add = () =>
        within(part()).getByRole("button", { name: "Add a field" });
      await userEvent.click(add());
      const drawer = await screen.findByRole(form, {
        name: "What it takes \xb7 Field 2 of 2",
      });
      await userEvent.type(
        within(drawer).getByRole("textbox", { name: "Name" }),
        "reason",
      );

      await userEvent.click(
        within(drawer).getByRole("button", { name: "Close" }),
      );

      await waitFor(() => expect(editors()).toHaveLength(0));
      await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
      expect(
        within(heading().parentElement!).getByText("Unsaved"),
      ).toBeVisible();
      expect(lastSaidUnsaved(report)).toBe(true);
      expect(saved).not.toHaveBeenCalled();

      await userEvent.click(add());

      const again = await screen.findByRole(form, {
        name: "What it takes \xb7 Field 2 of 2",
      });
      expect(within(again).getByRole("textbox", { name: "Name" })).toHaveValue(
        "reason",
      );
      expect(editors()).toHaveLength(1);
      expect(part().querySelectorAll("tbody[hidden]")).toHaveLength(1);

      await userEvent.click(
        within(again).getByRole("button", { name: "Cancel" }),
      );

      await waitFor(() => expect(editors()).toHaveLength(0));
      expect(part().querySelectorAll("tbody[hidden]")).toHaveLength(0);
      expect(lastSaidUnsaved(report)).toBe(false);
      expect(saved).not.toHaveBeenCalled();
    },
  );

  it.each(["Close", "Escape", "backdrop"] as const)(
    "lets a field go when its drawer over a narrow list is shut by %s: no drawer or strip is left, the edit is kept unsaved, and the keyboard is back on its row",
    async (shutting) => {
      laidOutAt(900, 1600);
      const { saved } = await building(
        TAKES,
        [COMPLAINT],
        true,
        undefined,
        false,
        {
          openStored: false,
        },
      );
      const open = within(part()).getByRole("button", {
        name: `Open ${setApart("The complaint")}`,
      });
      await userEvent.click(open);
      const drawer = await screen.findByRole("dialog", {
        name: "What it takes \xb7 Field 1 of 1",
      });
      await userEvent.type(
        within(drawer).getByRole("textbox", { name: "Name" }),
        "_raw",
      );

      if (shutting === "Close")
        await userEvent.click(
          within(drawer).getByRole("button", { name: "Close" }),
        );
      else if (shutting === "Escape") await userEvent.keyboard("{Escape}");
      else
        await userEvent.click(
          document.querySelector<HTMLElement>(".MuiBackdrop-root")!,
        );
      await waitFor(() =>
        expect(screen.queryByRole("dialog", { hidden: true })).toBeNull(),
      );

      expect(editors()).toHaveLength(0);
      expect(
        screen.queryAllByRole("button", {
          name: "What it takes \xb7 Field 1 of 1",
          hidden: true,
        }),
      ).toEqual([]);
      const closed = screen.getByRole("rowgroup", { name: "complaint_raw" });
      expect(within(closed).getByText("Unsaved")).toBeInTheDocument();
      await waitFor(() =>
        expect(
          within(closed).getByRole("button", {
            name: `Open ${setApart("The complaint")}`,
          }),
        ).toHaveFocus(),
      );
      expect(saved).not.toHaveBeenCalled();
    },
  );

  it("keeps the field editor's fold under a device key of its own, apart from every row panel's", async () => {
    const before = {
      row: localStorage.getItem("lilradish.panel.row"),
      field: localStorage.getItem("lilradish.panel.field"),
    };
    localStorage.setItem("lilradish.panel.row", "collapsed");
    localStorage.removeItem("lilradish.panel.field");
    onTestFinished(() => {
      for (const [key, value] of Object.entries(before)) {
        if (value === null) localStorage.removeItem(`lilradish.panel.${key}`);
        else localStorage.setItem(`lilradish.panel.${key}`, value);
      }
    });
    laidOutAt(1400, 1600);
    await building(TAKES, [COMPLAINT], true);
    const fold = screen.getByRole("button", {
      name: "What it takes \xb7 Field 1 of 1",
    });
    expect(fold).toHaveAttribute("aria-expanded", "true");

    await userEvent.click(fold);

    expect(fold).toHaveAttribute("aria-expanded", "false");
    expect(localStorage.getItem("lilradish.panel.field")).toBe("collapsed");
    expect(localStorage.getItem("lilradish.panel.row")).toBe("collapsed");
    await userEvent.click(fold);
    expect(localStorage.getItem("lilradish.panel.field")).toBe("expanded");
    expect(localStorage.getItem("lilradish.panel.row")).toBe("collapsed");
  });

  it("says a refusal in the drawer over a narrow list, which has no fold to hide it in", async () => {
    laidOutAt(900, 1600);
    const answer = deferred<readonly DeclaredField[]>();
    await building(TAKES, [COMPLAINT], true, () => answer.promise);
    await userEvent.type(
      within(await row("complaint")).getByRole("textbox", { name: "Name" }),
      "_raw",
    );
    await userEvent.click(save());
    const dialog = screen.getByRole("dialog", {
      name: "What it takes \xb7 Field 1 of 1",
    });
    expect(
      within(dialog).queryByRole("button", {
        name: "What it takes \xb7 Field 1 of 1",
      }),
    ).toBeNull();
    expect(
      screen.queryAllByRole("button", {
        name: "What it takes \xb7 Field 1 of 1",
        hidden: true,
      }),
    ).toEqual([]);

    await act(async () => {
      answer.refuse(
        new RequestFailed({ status: 409, code: "DRAFT_WRITTEN_SINCE_READ" }),
      );
    });

    const alert = await within(dialog).findByRole("alert");
    expect(alert).toHaveTextContent(WRITTEN_SINCE);
    expect(alert).toBeVisible();
    expect(within(dialog).getByRole("textbox", { name: "Name" })).toHaveValue(
      "complaint_raw",
    );
  });

  it.each([1400])(
    "unfolds a field editor the reader folded while saving once for the refusal, with the keyboard on it, at width %s, keeping the device's fold",
    async (width) => {
      const kept = localStorage.getItem("lilradish.panel.field");
      localStorage.setItem("lilradish.panel.field", "expanded");
      onTestFinished(() => {
        if (kept === null) localStorage.removeItem("lilradish.panel.field");
        else localStorage.setItem("lilradish.panel.field", kept);
      });
      laidOutAt(width, 1600);
      const answer = deferred<readonly DeclaredField[]>();
      await building(TAKES, [COMPLAINT], true, () => answer.promise);
      await userEvent.type(
        within(await row("complaint")).getByRole("textbox", { name: "Name" }),
        "_raw",
      );
      await userEvent.click(save());
      const fold = () =>
        screen.getByRole("button", { name: "What it takes \xb7 Field 1 of 1" });
      await userEvent.click(fold());
      await waitFor(() =>
        expect(screen.queryByRole("textbox", { name: "Name" })).toBeNull(),
      );
      expect(localStorage.getItem("lilradish.panel.field")).toBe("collapsed");

      await act(async () => {
        answer.refuse(
          new RequestFailed({ status: 409, code: "DRAFT_WRITTEN_SINCE_READ" }),
        );
      });

      const alert = await screen.findByRole("alert");
      expect(alert).toHaveTextContent(WRITTEN_SINCE);
      await waitFor(() =>
        expect(document.activeElement).toContainElement(alert),
      );
      expect(document.activeElement).toHaveAttribute("tabindex", "-1");
      expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue(
        "complaint_raw",
      );
      expect(fold()).toHaveAttribute("aria-expanded", "true");
      expect(localStorage.getItem("lilradish.panel.field")).toBe("collapsed");

      await userEvent.click(fold());
      await act(async () => {});
      await waitFor(() =>
        expect(screen.queryByRole("textbox", { name: "Name" })).toBeNull(),
      );
      expect(screen.queryByRole("alert")).toBeNull();
      expect(localStorage.getItem("lilradish.panel.field")).toBe("collapsed");
    },
  );

  it.each([900, 1400])(
    "says a refusal and Load the saved draft once in the list after its drawer is closed, and in the drawer again once reopened, at width %s",
    async (width) => {
      laidOutAt(width, 1600);
      const { readAfresh, saved } = await building(
        TAKES,
        [COMPLAINT],
        true,
        () =>
          Promise.reject(
            new RequestFailed({
              status: 409,
              code: "DRAFT_WRITTEN_SINCE_READ",
            }),
          ),
      );
      await userEvent.type(
        within(await row("complaint")).getByRole("textbox", { name: "Name" }),
        "_raw",
      );
      await userEvent.click(save());
      await screen.findByRole("alert");

      await closeEditor();

      const list = within(part()).getByRole("group", { name: "What it takes" });
      const alerts = screen.getAllByRole("alert");
      expect(alerts).toHaveLength(1);
      expect(alerts[0]).toHaveTextContent(WRITTEN_SINCE);
      expect(list).toContainElement(alerts[0]!);
      const load = screen.getByRole("button", { name: LOAD_SAVED });
      expect(list).toContainElement(load);
      expect(editors()).toHaveLength(0);
      expect(screen.queryByRole("dialog")).toBeNull();
      expect(readAfresh).not.toHaveBeenCalled();
      await userEvent.click(load);
      expect(readAfresh).toHaveBeenCalledTimes(1);
      expect(saved).toHaveBeenCalledTimes(1);

      const editor = await row("complaint_raw");

      const again = screen.getAllByRole("alert");
      expect(again).toHaveLength(1);
      expect(list).not.toContainElement(again[0]!);
      expect(screen.getAllByRole("button", { name: LOAD_SAVED })).toHaveLength(
        1,
      );
      expect(within(editor).getByRole("textbox", { name: "Name" })).toHaveValue(
        "complaint_raw",
      );
      if (width === 900)
        expect(screen.getByRole("dialog")).toContainElement(again[0]!);
      expect(saved).toHaveBeenCalledTimes(1);
    },
  );

  it.each([900, 1400])(
    "drops a field added and named once Cancel is pressed, holding nothing back and sending nothing, at width %s",
    async (width) => {
      laidOutAt(width, 1600);
      const report = vi.fn<ReportStatus>();
      const { saved } = await building(
        TAKES,
        [COMPLAINT],
        true,
        undefined,
        false,
        {
          openStored: false,
          report,
        },
      );
      await userEvent.click(
        within(part()).getByRole("button", { name: "Add a field" }),
      );
      await userEvent.type(
        within(await row("Field 2, not named yet")).getByRole("textbox", {
          name: "Name",
        }),
        "note",
      );
      expect(lastSaidUnsaved(report)).toBe(true);

      await userEvent.click(screen.getByRole("button", { name: "Cancel" }));

      await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
      expect(editors()).toHaveLength(0);
      expect(part().querySelectorAll("tbody[hidden]")).toHaveLength(0);
      expect(
        within(part()).queryByRole("rowgroup", { name: "note" }),
      ).toBeNull();
      expect(within(part()).queryByText("Unsaved")).toBeNull();
      expect(lastSaidUnsaved(report)).toBe(false);
      expect(
        within(part()).getByRole("group", { name: "What it takes" }),
      ).toHaveFocus();
      expect(saved).not.toHaveBeenCalled();
    },
  );

  it("keeps a field added and named once its covering drawer is closed, out of the table and unsaved, with the keyboard back on Add", async () => {
    laidOutAt(900, 1600);
    const report = vi.fn<ReportStatus>();
    const { saved } = await building(
      TAKES,
      [COMPLAINT],
      true,
      undefined,
      false,
      {
        openStored: false,
        report,
      },
    );
    const add = within(part()).getByRole("button", { name: "Add a field" });
    await userEvent.click(add);
    await userEvent.type(
      within(await row("Field 2, not named yet")).getByRole("textbox", {
        name: "Name",
      }),
      "note",
    );

    await closeEditor();

    expect(editors()).toHaveLength(0);
    expect(part().querySelectorAll("tbody[hidden]")).toHaveLength(1);
    expect(within(part()).queryByRole("rowgroup", { name: "note" })).toBeNull();
    const shownMarks = within(part())
      .getAllByText("Unsaved")
      .filter((mark) => mark.closest("[hidden]") === null);
    expect(shownMarks).toHaveLength(1);
    expect(shownMarks[0]).toBeVisible();
    expect(lastSaidUnsaved(report)).toBe(true);
    expect(add).toHaveFocus();
    expect(saved).not.toHaveBeenCalled();
  });

  it("goes to the next field only through saving where the stored field is held, keeping the field and sending nothing", async () => {
    const { saved } = await building(
      TAKES,
      [{ ...COMPLAINT, name: "Complaint" }, DOCUMENTS],
      true,
    );
    const next = screen.getByRole("button", { name: "Next field" });
    expect(next).toHaveAttribute("aria-disabled", "true");

    await act(async () => next.focus());
    await userEvent.keyboard("{Enter}");

    expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue(
      "Complaint",
    );
    expect(editors()).toHaveLength(1);
    expect(saved).not.toHaveBeenCalled();
  });

  it("goes to the next field only through saving where the host refuses the half as it stands, keeping the field and sending nothing", async () => {
    const refusalOf = vi.fn(() => "Another write holds this half.");
    const { saved } = await building(
      TAKES,
      [COMPLAINT, DOCUMENTS],
      true,
      undefined,
      false,
      { refusalOf },
    );
    const next = screen.getByRole("button", { name: "Next field" });
    expect(next).toHaveAttribute("aria-disabled", "true");
    expect(next).toHaveAccessibleDescription("Another write holds this half.");

    await act(async () => next.focus());
    await userEvent.keyboard("{Enter}");

    expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue(
      "complaint",
    );
    expect(editors()).toHaveLength(1);
    expect(refusalOf).toHaveBeenCalled();
    expect(saved).not.toHaveBeenCalled();
  });

  it.each([
    [false, "puts the keyboard on the name"],
    [true, "leaves the keyboard on what the person moved it to"],
  ])(
    "where a covering drawer arrives after the field it holds and the keyboard moved meanwhile (%s), %s",
    async (moved) => {
      laidOutAt(399, 1600);
      await building(TAKES, [COMPLAINT], true, undefined, false, {
        openStored: false,
      });
      const add = within(part()).getByRole("button", { name: "Add a field" });
      await act(async () => add.focus());
      await act(async () => {
        fireEvent.click(add);
      });
      const close = screen.getByRole("button", { name: "Close" });
      if (moved) await act(async () => close.focus());

      await act(async () => {});

      const name = within(await row("Field 2, not named yet")).getByRole(
        "textbox",
        {
          name: "Name",
        },
      );
      if (moved) {
        expect(close).toHaveFocus();
        expect(name).not.toHaveFocus();
      } else {
        expect(name).toHaveFocus();
        expect(close).not.toHaveFocus();
      }
    },
  );

  it("keeps no watch on the page for a covering drawer, puts the keyboard on Name once it arrives, and leaves nothing when unmounted before it does", async () => {
    const watching = new Set<MutationObserver>();
    class Recorded extends MutationObserver {
      override observe(target: Node, options?: MutationObserverInit) {
        if (target === document.body) watching.add(this);
        super.observe(target, options);
      }
      override disconnect() {
        watching.delete(this);
        super.disconnect();
      }
    }
    vi.stubGlobal("MutationObserver", Recorded);
    laidOutAt(399, 1600);
    await building(TAKES, [COMPLAINT], true, undefined, false, {
      openStored: false,
    });
    const add = () =>
      within(part()).getByRole("button", { name: "Add a field" });
    await act(async () => {
      fireEvent.click(add());
    });
    expect(watching.size).toBe(0);
    await act(async () => {});
    expect(
      within(await row("Field 2, not named yet")).getByRole("textbox", {
        name: "Name",
      }),
    ).toHaveFocus();
    expect(watching.size).toBe(0);
    await closeEditor();
    await act(async () => {
      fireEvent.click(add());
    });
    expect(watching.size).toBe(0);

    cleanup();

    expect(watching.size).toBe(0);
    expect(document.body.childElementCount).toBe(0);
  });

  it.each([399, 1400])(
    "says a draft declaring nothing in a row of its table before the Add row, spanning every column and pressing to nothing, at width %s",
    async (width) => {
      laidOutAt(width, 1600);
      const { saved } = await building(TAKES, [], true);
      const table = within(part()).getByRole("table");
      const rows = [...table.querySelectorAll("tbody > tr")];
      expect(rows).toHaveLength(2);
      const [empty, adding] = rows as [HTMLElement, HTMLElement];
      expect(empty.textContent).toBe("Nothing is declared.");
      expect(adding).toContainElement(
        within(table).getByRole("button", { name: "Add a field" }),
      );
      expect(empty.children).toHaveLength(1);
      const cell = empty.firstElementChild as HTMLTableCellElement;
      expect(cell.colSpan).toBe(width === 399 ? 3 : 6);
      expect(cell.colSpan).toBe(
        (adding.firstElementChild as HTMLTableCellElement).colSpan,
      );
      expect(table.querySelectorAll("thead th")).toHaveLength(
        width === 399 ? 0 : 6,
      );
      expect(within(empty).queryAllByRole("button")).toHaveLength(0);
      expect(within(empty).queryAllByRole("link")).toHaveLength(0);
      expect(empty.querySelector("[tabindex]")).toBeNull();

      await userEvent.click(cell);

      expect(editors()).toHaveLength(0);
      expect(screen.queryByRole("dialog")).toBeNull();
      expect(saved).not.toHaveBeenCalled();
    },
  );

  it.each([true, false])(
    "draws the field table, editable %s, in every table's geometry: headings 36 high over a 2-pixel rule, rows 44 over a 1-pixel one, inset 12",
    async (editable) => {
      laidOutAt(1400, 1600);
      await building(TAKES, [COMPLAINT], editable, undefined, false, {
        openStored: false,
      });
      const table = within(part()).getByRole("table");
      const drawn = (cell: Element) => {
        const style = getComputedStyle(cell);
        return [
          style.boxSizing,
          style.height,
          style.padding,
          style.fontSize,
          style.lineHeight,
          style.fontWeight,
          style.borderBottomWidth,
          style.borderBottomStyle,
          style.borderBottomColor,
        ];
      };
      const headings = [...table.querySelectorAll("thead th")];
      const cells = [...table.querySelectorAll("tbody td")];
      expect(headings.length).toBeGreaterThan(0);
      expect(cells.length).toBeGreaterThan(0);

      for (const heading of headings) {
        expect(drawn(heading)).toEqual([
          "border-box",
          "36px",
          "7px 12px",
          "12px",
          "22px",
          "650",
          "2px",
          "solid",
          "rgb(214, 224, 228)",
        ]);
        expect(getComputedStyle(heading).color).toBe("rgb(88, 102, 108)");
      }
      for (const cell of cells) {
        expect(drawn(cell).slice(0, 2)).toEqual(["border-box", "44px"]);
        expect(drawn(cell).slice(3)).toEqual([
          "14px",
          "22px",
          "400",
          "1px",
          "solid",
          "rgb(228, 235, 238)",
        ]);
      }
      const first = table.querySelector("tbody td")!;
      expect(getComputedStyle(first).paddingLeft).toBe("12px");
      expect(getComputedStyle(first).paddingTop).toBe("11px");
    },
  );

  it("draws the empty draft's one row in the quieter ink at a row's height, its words where the first column's start", async () => {
    laidOutAt(1400, 1600);
    await building(TAKES, [], true);
    const table = within(part()).getByRole("table");
    const cell = [...table.querySelectorAll("tbody td")].find(
      (each) => each.textContent === "Nothing is declared.",
    )!;
    const style = getComputedStyle(cell);

    expect([
      style.color,
      style.fontSize,
      style.lineHeight,
      style.height,
      style.paddingLeft,
      style.paddingTop,
    ]).toEqual(["rgb(88, 102, 108)", "14px", "22px", "44px", "12px", "11px"]);
    expect(style.color).not.toBe("rgb(25, 32, 36)");
  });

  it("says a read half declaring nothing in one line of the quieter ink at a row's size, with no table", async () => {
    await building(TAKES, [], false);
    const line = within(part()).getByText("Nothing is declared.");
    const style = getComputedStyle(line);

    expect([
      style.color,
      style.fontSize,
      style.lineHeight,
      style.paddingTop,
      style.paddingBottom,
    ]).toEqual(["rgb(88, 102, 108)", "14px", "22px", "11px", "11px"]);
    expect(line.closest("table")).toBeNull();
  });

  it("says a half declaring nothing where it is only read in its one line, drawing no table", async () => {
    await building(TAKES, [], false);

    expect(part()).toHaveTextContent("Nothing is declared.");
    expect(within(part()).queryByRole("table")).toBeNull();
    expect(within(part()).queryByRole("columnheader")).toBeNull();
    expect(
      within(part()).queryByRole("button", { name: "Add a field" }),
    ).toBeNull();
  });

  it("spans the Add row across a declaration's Accepted when column as well", async () => {
    await building(GIVES, [CATEGORY], true, undefined, false, {
      openStored: false,
    });
    const table = within(part()).getByRole("table");
    const add = within(table).getByRole("button", { name: "Add a field" });
    const cell = add.closest("td")!;

    expect(cell.colSpan).toBe(7);
    expect(table.querySelectorAll("thead th")).toHaveLength(7);
    expect(
      within(table)
        .getByRole("rowgroup", { name: "category" })
        .querySelector("tr")!.children,
    ).toHaveLength(7);
  });
});
