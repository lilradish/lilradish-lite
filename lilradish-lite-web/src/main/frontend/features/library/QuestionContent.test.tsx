import { ThemeProvider } from "@mui/material/styles";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { Version } from "../../api/groups/{groupId}/{kind}";
import type { Problem } from "../../api/problem";
import { StandingProvider } from "../../app/standing/StandingContext";
import { answered, inGroup } from "../../testutil/standingRead";
import { theme } from "../../lib/theme/theme";
import { isolatedInText } from "../../lib/direction/isolated";
import { requestsTo, serving, type Reply } from "../../testutil/answering";
import { deferred } from "../../testutil/deferred";
import { drawnAtDepth, drawnOf } from "../../testutil/depths";
import { wordsBeside } from "../../testutil/editable";
import { noticesIn } from "../../testutil/notices";
import { libraryLaidOutAt as laidOutAt } from "./libraryLayout.testHelper";
import type { ReadContent } from "./ContentProblems";
import { QuestionContent } from "./QuestionContent";
vi.mock("../../app/standing/RequireGroup", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../app/standing/RequireGroup")>()),
  useGroup: () => ({ groupId: GROUP }),
}));

const GROUP = "00000003-0000-4000-8000-000000000c41";

const ENTRY = "00000006-0000-4000-8000-000000000c41";

const VERSION = "00000007-0000-4000-8000-000000000c41";

const ADDRESS = `/api/groups/${GROUP}/questions/${ENTRY}/versions/${VERSION}`;
const STANDING = answered(
  [],
  [inGroup(GROUP, "CLAIMS", "Claims", ["author_entry"])],
  "000c41",
);

const FIELD_NAME_REFUSED =
  "A field's name is one to 63 lowercase English letters, digits and underscores, starting with a letter.";

const DRAFT: Version = {
  versionName: null,
  versionId: VERSION,
  number: 3,
  standing: "draft",
  writers: [{ userId: "000c41" }],
  writtenByMigration: false,
  stateActs: new Set(["write", "submit"]),
  pinnedBy: [],
};

const QUESTION = {
  revision: 4,
  searchWays: [],
  searchShapeNeedsRefresh: false,
  instruction: "Say which category.\n\tBriefly.",
  takes: [
    {
      fieldId: "0000000b-0000-4000-8000-000000000c41",
      name: "complaint",
      kind: "text",
      longest: 4000,
      many: false,
      mustBeGiven: true,
    },
  ],
  gives: [
    {
      fieldId: "0000000b-0000-4000-8000-000000000c42",
      name: "summary",
      kind: "text",
      longest: 1000,
      many: false,
      mustBeGiven: true,
      stands: "always",
    },
  ],
  added: 'Answer with one JSON object.\nJSON Schema:\n{"type":"object"}\n',
};

const SEARCH_WAY = {
  name: "web",
  tool: "web_search",
  gives: ["title", "body", "references", "other"].map((name, index) => ({
    fieldId: `field-${index}`,
    name,
    kind: "text",
    many: false,
    mustBeGiven: false,
    longest: 4000,
    stands: "never",
  })),
};

function reply(body: object, status = 200): readonly [string, number] {
  return [JSON.stringify(body), status];
}

function themed({ children }: { readonly children: ReactNode }) {
  return (
    <ThemeProvider theme={theme}>
      <StandingProvider read={STANDING}>{children}</StandingProvider>
    </ThemeProvider>
  );
}

async function editInstruction(box: HTMLElement, text: string) {
  await act(async () => {
    box.focus();
  });
  box.textContent = text;
  const range = document.createRange();
  range.selectNodeContents(box);
  range.collapse(false);
  window.getSelection()!.removeAllRanges();
  window.getSelection()!.addRange(range);
  fireEvent.input(box);
}

function opening(
  version: Version,
  routes: Readonly<Record<string, readonly Reply[]>> = {},
) {
  const sent = serving({ [`GET ${ADDRESS}`]: [reply(QUESTION)], ...routes });
  const refused = vi.fn<(problem: Problem) => void>();
  const written = vi.fn<() => void>();
  const shown = vi.fn<(content: ReadContent | null) => void>();
  const drawn = (readCount: number, shownVersion: Version = version) => (
    <QuestionContent
      groupId={GROUP}
      entryId={ENTRY}
      version={shownVersion}
      editable={shownVersion.stateActs?.has("write") === true}
      readCount={readCount}
      onRefused={refused}
      onWritten={written}
      onShown={shown}
    />
  );
  const { rerender, unmount } = render(drawn(0), { wrapper: themed });
  return {
    sent,
    refused,
    written,
    shown,
    unmount,
    readAgain: (readCount: number) => rerender(drawn(readCount)),
    showVersion: (shownVersion: Version) => rerender(drawn(0, shownVersion)),
  };
}

function part(name: string): HTMLElement {
  return screen.getByRole("region", { name });
}

async function openedField(partName: string): Promise<HTMLElement> {
  const region = screen.getByRole("region", { name: partName, hidden: true });
  let selected = region.querySelector<HTMLElement>(
    'button[aria-expanded="true"][aria-controls]',
  );
  if (selected === null) {
    const closed = region.querySelector<HTMLElement>(
      'tbody > tr button[aria-expanded="false"][aria-controls]',
    )!;
    await act(async () => {
      closed.focus();
    });
    await act(async () => {
      fireEvent.click(closed);
    });
    selected = region.querySelector<HTMLElement>(
      'button[aria-expanded="true"][aria-controls]',
    )!;
  }
  return document.getElementById(selected.getAttribute("aria-controls")!)!;
}

async function nameInOpenedField(partName: string): Promise<HTMLElement> {
  return within(await openedField(partName)).getByRole("textbox", {
    name: "Name",
  });
}

async function saveHalf(partName: string): Promise<HTMLElement> {
  const editor = await openedField(partName);
  return within(
    editor.closest<HTMLElement>('[role="dialog"], section')!,
  ).getByRole("button", { name: "Save" });
}

async function opened(): Promise<void> {
  await screen.findByRole("region", { name: "The instruction" });
}

async function editingPart(
  name: "The instruction" | "How it is drawn",
): Promise<HTMLElement> {
  const region = part(name);
  const edit = within(region).queryByRole("button", {
    name:
      name === "The instruction"
        ? "Edit the instruction"
        : "Edit how it is drawn",
  });
  if (edit !== null)
    await act(async () => {
      fireEvent.click(edit);
    });
  return region;
}

describe("QuestionContent", () => {
  beforeEach(() => laidOutAt(1800, 2000));
  it.each(
    [399, 400].flatMap((width) =>
      [false, true].map((editable) => [width, editable] as const),
    ),
  )(
    "draws declared-answer acceptance through the real caller at width %s, editable %s",
    async (width, editable) => {
      laidOutAt(width, 1600);
      vi.mocked(Element.prototype.getBoundingClientRect).mockImplementation(
        function (this: Element) {
          return new DOMRect(
            0,
            0,
            this.tagName === "SECTION"
              ? width
              : this === document.body || this === document.documentElement
                ? 1600
                : 0,
            0,
          );
        },
      );
      const { sent } = opening(
        editable
          ? DRAFT
          : { ...DRAFT, standing: "in_service", stateActs: new Set() },
        {
          [`GET ${ADDRESS}`]: [
            reply({
              ...QUESTION,
              gives: [
                {
                  fieldId: "root",
                  name: "root_name",
                  label: "Root label",
                  kind: "fields",
                  many: false,
                  mustBeGiven: true,
                  stands: "always",
                  fields: [
                    {
                      fieldId: "middle",
                      name: "middle",
                      label: "Middle label",
                      kind: "fields",
                      many: false,
                      mustBeGiven: false,
                      fields: [{ ...QUESTION.gives[0], name: "leaf" }],
                    },
                  ],
                },
              ],
            }),
          ],
        },
      );
      await opened();
      const gives = part("The declared answer");
      const takes = part("What it takes");
      if (width === 400) {
        expect(
          within(gives)
            .getAllByRole("columnheader")
            .map((head) => head.textContent),
        ).toEqual([
          "Field",
          "What it is",
          "How many",
          "Required",
          "Accepted when",
          ...(editable ? ["Open", ""] : []),
        ]);
        expect(
          within(takes)
            .getAllByRole("columnheader")
            .map((head) => head.textContent),
        ).toEqual([
          "Field",
          "What it is",
          "How many",
          "Required",
          ...(editable ? ["Open", ""] : []),
        ]);
      } else {
        expect(within(gives).queryByRole("columnheader")).toBeNull();
        expect(within(takes).queryByRole("columnheader")).toBeNull();
        expect(gives).toHaveTextContent("Accepted when Always");
        expect(gives).toHaveTextContent(
          "Accepted when Same as Root label \u00b7 root_name",
        );
      }
      expect(takes).not.toHaveTextContent("Accepted when");
      const lines = [...gives.querySelectorAll("tbody > tr")];
      const acceptance =
        width === 400 ? lines[2].children[4] : lines[2].children[0];
      expect(acceptance).toHaveTextContent(
        "Same as Root label \u00b7 root_name",
      );
      expect(acceptance).not.toHaveTextContent("Middle label");
      expect(acceptance.querySelector("code > bdi")).toHaveTextContent(
        "root_name",
      );
      if (editable) {
        const leaf = within(gives).getByRole("rowgroup", { name: "leaf" });
        await userEvent.click(
          within(leaf).getByRole("button", {
            name: `Open ${isolatedInText("leaf")}`,
          }),
        );
        expect(
          within(await openedField("The declared answer")).getByRole(
            "textbox",
            {
              name: "Name",
            },
          ),
        ).toHaveValue("leaf");
        expect(
          within(await openedField("The declared answer")).queryByRole(
            "combobox",
            {
              name: "Accepted when",
            },
          ),
        ).toBeNull();
      } else {
        expect(gives.querySelector("input, textarea, button")).toBeNull();
      }
      expect(requestsTo(sent)).toEqual([`GET ${ADDRESS}`]);
    },
  );

  it.each([
    ["dense references", "{{complaint}}".repeat(65535)],
    ["private-use characters", String.fromCodePoint(0xf0000).repeat(65535)],
    ["one long reference", "{{" + "a".repeat(1048572) + "}}"],
    ["unfinished references", "{{".repeat(524288)],
  ])(
    "keeps an excessive paste of %s editable and saves only after it is shortened",
    async (_case, raw) => {
      const recovered = "Read {{complaint}}.\n";
      const { sent, written, refused } = opening(DRAFT, {
        [`PUT ${ADDRESS}/instruction`]: [
          reply({ ...QUESTION, revision: 5, instruction: recovered }),
        ],
      });
      await opened();
      const instruction = within(
        await editingPart("The instruction"),
      ).getByRole("textbox");
      await act(async () => {
        instruction.focus();
      });
      const range = document.createRange();
      range.selectNodeContents(instruction);
      window.getSelection()!.removeAllRanges();
      window.getSelection()!.addRange(range);
      fireEvent.paste(instruction, {
        clipboardData: {
          getData: (kind: string) => (kind === "text/plain" ? raw : ""),
        },
      });

      expect(instruction.textContent).toBe(raw);
      expect(instruction).toHaveAttribute("aria-invalid", "true");
      const save = screen.getByRole("button", { name: "Save the instruction" });
      expect(save).toHaveAttribute("aria-disabled", "true");
      expect(save).toHaveAccessibleDescription(
        "An instruction is one to 8192 characters with something in it that shows. Lines may be broken, and indented with tabs.",
      );
      await act(async () => {
        save.focus();
      });
      await userEvent.keyboard("{Enter}");
      expect(instruction.textContent).toBe(raw);
      expect(requestsTo(sent)).toEqual([`GET ${ADDRESS}`]);
      expect(written).not.toHaveBeenCalled();
      expect(refused).not.toHaveBeenCalled();

      await editInstruction(instruction, recovered);
      expect(instruction).toHaveAttribute("aria-invalid", "false");
      expect(
        instruction.querySelector('[data-reference="complaint"]'),
      ).not.toBeNull();
      await userEvent.click(save);
      await waitFor(() => expect(written).toHaveBeenCalledTimes(1));
      expect(requestsTo(sent)).toEqual([
        `GET ${ADDRESS}`,
        `PUT ${ADDRESS}/instruction`,
      ]);
      expect(JSON.parse(String(sent.mock.calls[1]![1]?.body))).toEqual({
        revision: 4,
        instruction: recovered,
      });
      expect(refused).not.toHaveBeenCalled();
    },
  );

  it("renames both excessive dirty texts without passing their raw characters through finite marks or saving them", async () => {
    const question = {
      ...QUESTION,
      instruction: "Read {{complaint}}.",
      drawing: "## {{complaint}}",
    };
    const next = {
      ...question,
      revision: 5,
      instruction: "Read {{grievance}}.",
      drawing: "## {{grievance}}",
      takes: [
        { ...question.takes[0]!, fieldId: "new-field-id", name: "grievance" },
      ],
    };
    const tail = "\\{{complaint}} {{unknown}} &#xF0000; {{unfinished\n";
    const rawInstruction =
      "{{complaint}}".repeat(65535) + String.fromCodePoint(0xf0000) + tail;
    const rawDrawing =
      String.fromCodePoint(0xf0000).repeat(65535) +
      "{{complaint}}/{{complaint}}/" +
      tail;
    const { sent, written, refused } = opening(DRAFT, {
      [`GET ${ADDRESS}`]: [reply(question)],
      [`PUT ${ADDRESS}/takes`]: [reply(next)],
    });
    await opened();
    const instruction = within(await editingPart("The instruction")).getByRole(
      "textbox",
    );
    const drawing = within(await editingPart("How it is drawn")).getByRole(
      "textbox",
    );
    await editInstruction(instruction, rawInstruction);
    fireEvent.change(drawing, { target: { value: rawDrawing } });
    expect(instruction.textContent).toBe(rawInstruction);
    expect(drawing).toHaveValue(rawDrawing);
    const name = await nameInOpenedField("What it takes");
    await userEvent.clear(name);
    await userEvent.type(name, "grievance");
    await userEvent.click(await saveHalf("What it takes"));
    await waitFor(() => expect(written).toHaveBeenCalledTimes(1));

    expect(instruction.textContent).toBe(
      "{{grievance}}".repeat(65535) + String.fromCodePoint(0xf0000) + tail,
    );
    expect(drawing).toHaveValue(
      String.fromCodePoint(0xf0000).repeat(65535) +
        "{{grievance}}/{{grievance}}/" +
        tail,
    );
    expect(instruction).toHaveAttribute("aria-invalid", "true");
    expect(
      screen.getByRole("button", { name: "Save the instruction" }),
    ).toHaveAttribute("aria-disabled", "true");
    expect(
      screen.getByRole("button", { name: "Save how it is drawn" }),
    ).toHaveAttribute("aria-disabled", "true");
    expect(within(part("The instruction")).getByText("Unsaved")).toBeVisible();
    expect(within(part("How it is drawn")).getByText("Unsaved")).toBeVisible();
    expect(
      JSON.parse(String(sent.mock.calls[1]![1]?.body)).fields[0].fieldId,
    ).toBe(question.takes[0]!.fieldId);
    expect(requestsTo(sent)).toEqual([
      `GET ${ADDRESS}`,
      `PUT ${ADDRESS}/takes`,
    ]);
    expect(refused).not.toHaveBeenCalled();
  });

  it.each([true, false])(
    "keeps dirty prose while renaming its references, even when stored prose is unchanged (%s)",
    async (mentions) => {
      const question = {
        ...QUESTION,
        instruction: mentions ? "Read {{complaint}}." : "Read the case.",
        drawing: mentions ? "## {{complaint}}" : "## Case",
      };
      const next = {
        ...question,
        revision: 5,
        instruction: mentions ? "Read {{grievance}}." : question.instruction,
        drawing: mentions ? "## {{grievance}}" : question.drawing,
        takes: [
          { ...question.takes[0]!, fieldId: "new-field-id", name: "grievance" },
        ],
      };
      const rawInstruction =
        "Read {{complaint}} twice: {{complaint}}.\\{{complaint}}\nAuthor detail.\n";
      const rawDrawing = "## {{complaint}}\nAuthor drawing.\\{{complaint}}\n";
      const { sent, written } = opening(DRAFT, {
        [`GET ${ADDRESS}`]: [reply(question)],
        [`PUT ${ADDRESS}/takes`]: [reply(next)],
        [`PUT ${ADDRESS}/instruction`]: [
          reply({
            ...next,
            revision: 6,
            instruction:
              "Read {{grievance}} twice: {{grievance}}.\\{{complaint}}\nAuthor detail.\n",
          }),
        ],
      });
      await opened();
      const instruction = within(
        await editingPart("The instruction"),
      ).getByRole("textbox");
      const drawing = within(await editingPart("How it is drawn")).getByRole(
        "textbox",
      );
      await editInstruction(instruction, rawInstruction);
      fireEvent.change(drawing, { target: { value: rawDrawing } });
      const name = await nameInOpenedField("What it takes");
      await userEvent.clear(name);
      await userEvent.type(name, "grievance");
      await userEvent.click(await saveHalf("What it takes"));
      await waitFor(() => expect(written).toHaveBeenCalledTimes(1));
      expect(
        JSON.parse(String(sent.mock.calls[1]![1]?.body)).fields[0].fieldId,
      ).toBe(question.takes[0]!.fieldId);
      expect(drawing).toHaveValue(
        "## {{grievance}}\nAuthor drawing.\\{{complaint}}\n",
      );
      expect(instruction.textContent).toContain("Author detail.\n");
      expect(
        within(part("The instruction")).getByText("Unsaved"),
      ).toBeVisible();
      expect(
        within(part("How it is drawn")).getByText("Unsaved"),
      ).toBeVisible();
      expect(requestsTo(sent)).toEqual([
        `GET ${ADDRESS}`,
        `PUT ${ADDRESS}/takes`,
      ]);
      await userEvent.click(
        screen.getByRole("button", { name: "Save the instruction" }),
      );
      await waitFor(() => expect(sent).toHaveBeenCalledTimes(3));
      expect(JSON.parse(String(sent.mock.calls[2]![1]?.body))).toEqual({
        revision: 5,
        instruction:
          "Read {{grievance}} twice: {{grievance}}.\\{{complaint}}\nAuthor detail.\n",
      });
      expect(drawing).toHaveValue(
        "## {{grievance}}\nAuthor drawing.\\{{complaint}}\n",
      );
    },
  );

  it.each(["instruction", "drawing"])(
    "holds a takes save that would make the stored %s exceed its bound",
    async (where) => {
      const long = "x".repeat(8175) + "{{complaint}}";
      const question = {
        ...QUESTION,
        instruction: where === "instruction" ? long : "Read {{complaint}}.",
        drawing: where === "drawing" ? long : "## {{complaint}}",
      };
      const { sent } = opening(DRAFT, {
        [`GET ${ADDRESS}`]: [reply(question)],
      });
      await opened();
      const name = await nameInOpenedField("What it takes");
      await userEvent.clear(name);
      await userEvent.type(name, "complaint_longer");
      const save = await saveHalf("What it takes");
      expect(save).toHaveAttribute("aria-disabled", "true");
      expect(save).toHaveAccessibleDescription(
        where === "instruction"
          ? "An instruction is one to 8192 characters with something in it that shows. Lines may be broken, and indented with tabs."
          : "A drawing is one to 8192 characters with something in it that shows. Lines may be broken, and indented with tabs.",
      );
      await act(async () => {
        save.focus();
      });
      await userEvent.keyboard("{Enter}");
      expect(requestsTo(sent)).toEqual([`GET ${ADDRESS}`]);
    },
  );

  it("does not rewrite either dirty prose after a refused takes save", async () => {
    const question = {
      ...QUESTION,
      instruction: "Read {{complaint}}.",
      drawing: "## {{complaint}}",
    };
    const { sent } = opening(DRAFT, {
      [`GET ${ADDRESS}`]: [reply(question)],
      [`PUT ${ADDRESS}/takes`]: [
        reply({ code: "VERSION_STANDING_REFUSES" }, 409),
      ],
    });
    await opened();
    const instruction = within(await editingPart("The instruction")).getByRole(
      "textbox",
    );
    const drawing = within(await editingPart("How it is drawn")).getByRole(
      "textbox",
    );
    await editInstruction(instruction, "Read {{complaint}}. Author detail.");
    fireEvent.change(drawing, {
      target: { value: "## {{complaint}}\nAuthor detail." },
    });
    await userEvent.type(await nameInOpenedField("What it takes"), "_text");
    await userEvent.click(await saveHalf("What it takes"));
    await within(part("What it takes")).findByRole("alert");
    expect(instruction.textContent).toBe("Read {{complaint}}. Author detail.");
    expect(drawing).toHaveValue("## {{complaint}}\nAuthor detail.");
    expect(requestsTo(sent)).toEqual([
      `GET ${ADDRESS}`,
      `PUT ${ADDRESS}/takes`,
    ]);
  });

  it("uses both original identities when names are exchanged, with new response identities", async () => {
    const question = {
      ...QUESTION,
      instruction: "Read {{complaint}} then {{sender}}.",
      drawing: "## {{sender}}\n{{complaint}}",
      takes: [
        QUESTION.takes[0]!,
        { ...QUESTION.takes[0]!, fieldId: "sender-field", name: "sender" },
      ],
    };
    const first = {
      ...question,
      revision: 5,
      instruction: "Read {{temporary_name}} then {{sender}}.",
      drawing: "## {{sender}}\n{{temporary_name}}",
      takes: [
        { ...question.takes[0]!, name: "temporary_name" },
        question.takes[1]!,
      ],
    };
    const second = {
      ...first,
      revision: 6,
      instruction: "Read {{temporary_name}} then {{complaint}}.",
      drawing: "## {{complaint}}\n{{temporary_name}}",
      takes: [first.takes[0]!, { ...question.takes[1]!, name: "complaint" }],
    };
    const next = {
      ...question,
      revision: 7,
      instruction: "Read {{sender}} then {{complaint}}.",
      drawing: "## {{complaint}}\n{{sender}}",
      takes: [
        { ...question.takes[0]!, fieldId: "next-complaint", name: "sender" },
        { ...question.takes[1]!, fieldId: "next-sender", name: "complaint" },
      ],
    };
    const { sent } = opening(DRAFT, {
      [`GET ${ADDRESS}`]: [reply(question)],
      [`PUT ${ADDRESS}/takes`]: [reply(first), reply(second), reply(next)],
    });
    await opened();
    const takes = part("What it takes");
    const instruction = within(await editingPart("The instruction")).getByRole(
      "textbox",
    );
    const drawing = within(await editingPart("How it is drawn")).getByRole(
      "textbox",
    );
    await editInstruction(
      instruction,
      "Read {{complaint}} then {{sender}}. Author words.",
    );
    fireEvent.change(drawing, {
      target: { value: "## {{sender}}\n{{complaint}}\nAuthor words." },
    });
    for (const [index, [from, to]] of [
      ["complaint", "temporary_name"],
      ["sender", "complaint"],
      ["temporary_name", "sender"],
    ].entries()) {
      const summary = takes.querySelector<HTMLElement>(
        `tbody[aria-label="${from}"] button[aria-controls]`,
      )!;
      await act(async () => {
        summary.focus();
      });
      await act(async () => {
        fireEvent.click(summary);
      });
      const editor = document.getElementById(
        summary.getAttribute("aria-controls")!,
      )!;
      const name = within(editor).getByRole("textbox", { name: "Name" });
      await userEvent.clear(name);
      await userEvent.paste(to!);
      const save = within(
        editor.closest<HTMLElement>('[role="dialog"], section')!,
      ).getByRole("button", { name: "Save" });
      await userEvent.click(save);
      await waitFor(() => expect(sent).toHaveBeenCalledTimes(index + 2));
      await waitFor(() =>
        expect(takes.querySelector('button[aria-expanded="true"]')).toBeNull(),
      );
      expect(
        JSON.parse(String(sent.mock.calls[index + 1]![1]?.body)).fields.map(
          (field: { fieldId: string }) => field.fieldId,
        ),
      ).toEqual(question.takes.map((field) => field.fieldId));
    }
    await waitFor(() =>
      expect(drawing).toHaveValue(
        "## {{complaint}}\n{{sender}}\nAuthor words.",
      ),
    );
    expect(instruction.textContent).toBe(
      "Read {{sender}} then {{complaint}}. Author words.",
    );
    expect(requestsTo(sent)).toEqual([
      `GET ${ADDRESS}`,
      `PUT ${ADDRESS}/takes`,
      `PUT ${ADDRESS}/takes`,
      `PUT ${ADDRESS}/takes`,
    ]);
  });

  it.each(["unmount", "other version"])(
    "does not apply a late takes rename after %s",
    async (destination) => {
      const held = deferred<readonly [string, number]>();
      const question = {
        ...QUESTION,
        instruction: "Read {{complaint}}.",
        drawing: "## {{complaint}}",
      };
      const nextVersion = {
        ...DRAFT,
        versionId: "00000007-0000-4000-8000-000000000c42",
      };
      const nextAddress = `/api/groups/${GROUP}/questions/${ENTRY}/versions/${nextVersion.versionId}`;
      const { sent, written, showVersion, unmount } = opening(DRAFT, {
        [`GET ${ADDRESS}`]: [reply(question)],
        [`PUT ${ADDRESS}/takes`]: [held.promise],
        [`GET ${nextAddress}`]: [
          reply({
            ...question,
            instruction: "Another version.",
            drawing: "## Another version",
          }),
        ],
      });
      try {
        await opened();
        await editInstruction(
          within(await editingPart("The instruction")).getByRole("textbox"),
          "Read {{complaint}}. Author words.",
        );
        const name = await nameInOpenedField("What it takes");
        await userEvent.type(name, "_text");
        const save = await saveHalf("What it takes");
        await userEvent.click(save);
        await waitFor(() => expect(sent).toHaveBeenCalledTimes(2));
        const signal = sent.mock.calls[1]![1]?.signal;
        if (destination === "unmount")
          await act(async () => {
            unmount();
          });
        else {
          await act(async () => {
            showVersion(nextVersion);
          });
          await waitFor(async () =>
            expect(
              within(await editingPart("The instruction")).getByRole("textbox")
                .textContent,
            ).toBe("Another version."),
          );
        }
        await act(async () => {
          held.settle(
            reply({
              ...question,
              revision: 5,
              instruction: "Read {{complaint_text}}.",
              drawing: "## {{complaint_text}}",
              takes: [
                {
                  ...question.takes[0]!,
                  name: "complaint_text",
                  fieldId: "new-id",
                },
              ],
            }),
          );
          await held.promise;
        });
        expect(written).not.toHaveBeenCalled();
        if (destination === "unmount") expect(signal?.aborted).toBe(true);
        else {
          expect(
            within(await editingPart("The instruction")).getByRole("textbox")
              .textContent,
          ).toBe("Another version.");
          expect(
            within(await editingPart("How it is drawn")).getByRole("textbox"),
          ).toHaveValue("## Another version");
        }
      } finally {
        await act(async () => {
          held.settle(reply(question));
          await held.promise;
        });
      }
    },
  );

  it("does not apply an older takes answer after a newer question was read", async () => {
    const held = deferred<readonly [string, number]>();
    const question = {
      ...QUESTION,
      instruction: "Read {{complaint}}.",
      drawing: "## {{complaint}}",
    };
    const { sent, written, readAgain } = opening(DRAFT, {
      [`GET ${ADDRESS}`]: [
        reply(question),
        reply({ ...question, revision: 6, instruction: "A newer read." }),
      ],
      [`PUT ${ADDRESS}/takes`]: [held.promise],
    });
    try {
      await opened();
      await userEvent.type(await nameInOpenedField("What it takes"), "_text");
      await userEvent.click(await saveHalf("What it takes"));
      await waitFor(() => expect(sent).toHaveBeenCalledTimes(2));
      readAgain(1);
      await waitFor(async () =>
        expect(
          within(await editingPart("The instruction")).getByRole("textbox")
            .textContent,
        ).toBe("A newer read."),
      );
      const instruction = within(
        await editingPart("The instruction"),
      ).getByRole("textbox");
      await editInstruction(instruction, "A newer read. Author words.");
      held.settle(
        reply({
          ...question,
          revision: 5,
          instruction: "Read {{complaint_text}}.",
          takes: [{ ...question.takes[0]!, name: "complaint_text" }],
        }),
      );
      await waitFor(async () =>
        expect(await saveHalf("What it takes")).not.toHaveAttribute(
          "aria-disabled",
        ),
      );
      expect(instruction.textContent).toBe("A newer read. Author words.");
      expect(written).not.toHaveBeenCalled();
      expect(requestsTo(sent)).toEqual([
        `GET ${ADDRESS}`,
        `PUT ${ADDRESS}/takes`,
        `GET ${ADDRESS}`,
      ]);
    } finally {
      held.settle(reply(question));
    }
  });

  it("keeps a renamed input's identity in the request and reads both rewritten texts from the answer", async () => {
    const question = {
      ...QUESTION,
      instruction: "Read {{complaint}}.",
      drawing: "## {{complaint}}",
    };
    const next = {
      ...question,
      revision: 5,
      instruction: "Read {{grievance}}.",
      drawing: "## {{grievance}}",
      takes: [{ ...question.takes[0]!, name: "grievance" }],
    };
    const { sent } = opening(DRAFT, {
      [`GET ${ADDRESS}`]: [reply(question)],
      [`PUT ${ADDRESS}/takes`]: [reply(next)],
    });
    await opened();
    const name = await nameInOpenedField("What it takes");
    await userEvent.clear(name);
    await userEvent.type(name, "grievance");
    await userEvent.click(await saveHalf("What it takes"));
    await waitFor(() => expect(sent).toHaveBeenCalledTimes(2));
    const fields = JSON.parse(String(sent.mock.calls[1]![1]?.body)).fields;
    expect(fields[0]).toEqual(
      expect.objectContaining({
        name: "grievance",
        fieldId: question.takes[0]!.fieldId,
      }),
    );
    await waitFor(() =>
      expect(part("The instruction")).toHaveTextContent("{{grievance}}"),
    );
    expect(
      within(await editingPart("How it is drawn")).getByRole("textbox"),
    ).toHaveValue("## {{grievance}}");
  });

  it.each(["instruction", "drawing"])(
    "holds removal while the saved %s still refers to the input and sends no request",
    async (where) => {
      const question = {
        ...QUESTION,
        instruction:
          where === "instruction"
            ? "Read {{complaint}}."
            : QUESTION.instruction,
        drawing: where === "drawing" ? "## {{complaint}}" : "## Case",
      };
      const { sent } = opening(DRAFT, {
        [`GET ${ADDRESS}`]: [reply(question)],
      });
      await opened();
      await nameInOpenedField("What it takes");
      await userEvent.click(
        within(part("What it takes")).getByRole("button", {
          name: "Remove this field",
        }),
      );
      const save = await saveHalf("What it takes");
      expect(save).toHaveAttribute("aria-disabled", "true");
      expect(part("What it takes")).toHaveTextContent(
        "{{complaint}} is still mentioned in the instruction or how it is drawn; remove those mentions first.",
      );
      await act(async () => {
        save.focus();
      });
      await userEvent.keyboard("{Enter}");
      expect(requestsTo(sent)).toEqual([`GET ${ADDRESS}`]);
    },
  );

  it("marks a known input, warns by its label about unused inputs, and saves the author's raw multiline instruction", async () => {
    const question = {
      ...QUESTION,
      takes: [{ ...QUESTION.takes[0]!, label: "The complaint" }],
    };
    const raw = "Read {{complaint}}.\n\\{{literal}}\n";
    const { sent } = opening(DRAFT, {
      [`GET ${ADDRESS}`]: [reply(question)],
      [`PUT ${ADDRESS}/instruction`]: [
        reply({ ...question, revision: 5, instruction: raw }),
      ],
    });
    await opened();
    await editingPart("The instruction");
    expect(part("The instruction")).toHaveTextContent(
      "The instruction never mentions The complaint \u00b7 complaint.",
    );
    const box = within(await editingPart("The instruction")).getByRole(
      "textbox",
      {
        name: "The instruction",
      },
    );
    await editInstruction(box, raw);
    expect(box).toHaveTextContent("The complaint · {{complaint}}");
    expect(part("The instruction")).not.toHaveTextContent(
      "The instruction never mentions",
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Save the instruction" }),
    );
    await waitFor(() => expect(sent).toHaveBeenCalledTimes(2));
    expect(JSON.parse(String(sent.mock.calls[1]![1]?.body))).toEqual({
      revision: 4,
      instruction: raw,
    });
  });

  it("refuses complete unknown references in place, then accepts unfinished and escaped braces without discarding them", async () => {
    const raw = "Read \\{{missing}} and {{unfinished\n";
    const { sent } = opening(DRAFT, {
      [`PUT ${ADDRESS}/instruction`]: [
        reply({ ...QUESTION, revision: 5, instruction: raw }),
      ],
    });
    await opened();
    const box = within(await editingPart("The instruction")).getByRole(
      "textbox",
      {
        name: "The instruction",
      },
    );
    await editInstruction(box, "Read {{complaint.detail}} and {{}}.");
    expect(box).toHaveTextContent("{{complaint.detail}}");
    expect(box).toHaveAttribute("aria-invalid", "true");
    expect(part("The instruction")).toHaveTextContent(
      `${isolatedInText("{{complaint.detail}}")} and ${isolatedInText("{{}}")} aren't inputs of this question.`,
    );
    expect(
      screen.getByRole("button", { name: "Save the instruction" }),
    ).toHaveAttribute("aria-disabled", "true");
    expect(requestsTo(sent)).toEqual([`GET ${ADDRESS}`]);
    await editInstruction(box, raw);
    await userEvent.click(
      screen.getByRole("button", { name: "Save the instruction" }),
    );
    await waitFor(() => expect(sent).toHaveBeenCalledTimes(2));
    expect(JSON.parse(String(sent.mock.calls[1]![1]?.body))).toEqual({
      revision: 4,
      instruction: raw,
    });
  });

  it("keeps the typed raw instruction after a server reference refusal and sends no replacement automatically", async () => {
    const raw = "Read {{complaint}}.\n";
    const { sent, refused } = opening(DRAFT, {
      [`PUT ${ADDRESS}/instruction`]: [
        reply({ code: "INSTRUCTION_NAMES_UNKNOWN" }, 400),
      ],
    });
    await opened();
    const box = within(await editingPart("The instruction")).getByRole(
      "textbox",
      {
        name: "The instruction",
      },
    );
    await editInstruction(box, raw);
    await userEvent.click(
      screen.getByRole("button", { name: "Save the instruction" }),
    );
    await within(part("The instruction")).findByRole("alert");
    expect(box).toHaveTextContent("{{complaint}}");
    expect(requestsTo(sent)).toEqual([
      `GET ${ADDRESS}`,
      `PUT ${ADDRESS}/instruction`,
    ]);
    expect(refused).toHaveBeenCalledTimes(1);
    expect(JSON.parse(String(sent.mock.calls[1]![1]?.body)).instruction).toBe(
      raw,
    );
  });

  it.each([
    [409, "DRAFT_WRITTEN_SINCE_READ"],
    [403, "ACT_NOT_PERMITTED"],
    [200, "unreadable"],
  ])(
    "does not retry or claim an automatic refresh after status %i",
    async (status, code) => {
      const question = {
        ...QUESTION,
        searchWay: "web",
        searchWays: [SEARCH_WAY],
        searchShapeNeedsRefresh: true,
      };
      const { sent, written, shown } = opening(DRAFT, {
        [`GET ${ADDRESS}`]: [reply(question)],
        [`PUT ${ADDRESS}/searching`]: [
          reply(status === 200 ? { revision: "bad" } : { code }, status),
        ],
      });
      await screen.findByRole("alert");
      expect(requestsTo(sent)).toEqual([
        `GET ${ADDRESS}`,
        `PUT ${ADDRESS}/searching`,
      ]);
      expect(written).not.toHaveBeenCalled();
      expect(shown.mock.calls.every(([content]) => content === null)).toBe(
        true,
      );
      expect(
        screen.queryByRole("region", { name: "The instruction" }),
      ).not.toBeInTheDocument();
      expect(
        screen.queryByText(/this answer was updated to match/),
      ).not.toBeInTheDocument();
    },
  );

  it("stands what a draft searches with under its label above the box, none floating inside it, the box saying no way is chosen yet", async () => {
    opening(DRAFT);
    await opened();
    const region = part("For searching");

    const choice = within(region).getByRole("combobox", {
      name: "Search with",
    });

    expect(
      choice.closest(".MuiInputBase-root")!.previousElementSibling?.textContent,
    ).toBe("Search with");
    expect(region.querySelectorAll(".MuiInputLabel-root")).toHaveLength(0);
    expect(choice).toHaveTextContent("No way chosen yet");
    expect(
      getComputedStyle(choice.closest(".MuiFormControl-root")!).width,
    ).toBe("fit-content");
  });

  it("does not refresh a read whose editing permission was removed before the GET landed", async () => {
    const held = deferred<readonly [string, number]>();
    const { sent, written, showVersion } = opening(DRAFT, {
      [`GET ${ADDRESS}`]: [held.promise],
    });
    try {
      await waitFor(() => expect(sent).toHaveBeenCalledTimes(1));
      showVersion({ ...DRAFT, stateActs: new Set(["submit"]) });
      held.settle(
        reply({
          ...QUESTION,
          searchWay: "web",
          searchWays: [SEARCH_WAY],
          searchShapeNeedsRefresh: true,
        }),
      );
      await opened();
      expect(requestsTo(sent)).toEqual([`GET ${ADDRESS}`]);
      expect(written).not.toHaveBeenCalled();
      expect(
        screen.queryByRole("switch", { name: "For searching" }),
      ).toBeNull();
      expect(part("For searching")).toHaveTextContent(
        `Search with: ${isolatedInText("web")}`,
      );
      expect(
        screen.queryByText(/this answer was updated to match/),
      ).not.toBeInTheDocument();
    } finally {
      held.settle(reply(QUESTION));
    }
  });

  it.each(["unmount", "other version"])(
    "abandons an automatic PUT on %s without publishing its late answer",
    async (destination) => {
      const held = deferred<readonly [string, number]>();
      const question = {
        ...QUESTION,
        searchWay: "web",
        searchWays: [SEARCH_WAY],
        searchShapeNeedsRefresh: true,
      };
      const next = {
        ...DRAFT,
        versionId: "00000007-0000-4000-8000-000000000c42",
        number: 4,
      };
      const nextAddress = `/api/groups/${GROUP}/questions/${ENTRY}/versions/${next.versionId}`;
      const { sent, written, shown, unmount, showVersion } = opening(DRAFT, {
        [`GET ${ADDRESS}`]: [reply(question)],
        [`PUT ${ADDRESS}/searching`]: [held.promise],
        [`GET ${nextAddress}`]: [
          reply({ ...QUESTION, instruction: "Different instruction." }),
        ],
      });
      try {
        await waitFor(() =>
          expect(requestsTo(sent)).toContain(`PUT ${ADDRESS}/searching`),
        );
        const signal = sent.mock.calls[1]![1]?.signal;
        expect(signal?.aborted).toBe(false);
        if (destination === "unmount") unmount();
        else showVersion(next);
        expect(signal?.aborted).toBe(true);
        held.settle(
          reply({
            ...question,
            revision: 5,
            instruction: "Late old instruction.",
            searchShapeNeedsRefresh: false,
          }),
        );
        if (destination === "other version") {
          await waitFor(async () =>
            expect(
              within(await editingPart("The instruction")).getByRole(
                "textbox",
                {
                  name: "The instruction",
                },
              ),
            ).toHaveTextContent("Different instruction."),
          );
          expect(shown.mock.lastCall?.[0]?.kind).toBe("question");
        }
        expect(written).not.toHaveBeenCalled();
        expect(
          screen.queryByText("Late old instruction."),
        ).not.toBeInTheDocument();
        expect(
          screen.queryByText(/this answer was updated to match/),
        ).not.toBeInTheDocument();
        expect(requestsTo(sent)).toEqual(
          destination === "unmount"
            ? [`GET ${ADDRESS}`, `PUT ${ADDRESS}/searching`]
            : [
                `GET ${ADDRESS}`,
                `PUT ${ADDRESS}/searching`,
                `GET ${nextAddress}`,
              ],
        );
      } finally {
        held.settle(reply(QUESTION));
      }
    },
  );

  it.each([true, false])(
    "refreshes an editable search declaration with one PUT and reports only a real revision change: %s",
    async (changed) => {
      const question = {
        ...QUESTION,
        searchWay: "web",
        searchWays: [SEARCH_WAY],
        searchShapeNeedsRefresh: true,
      };
      const updated = {
        ...question,
        revision: changed ? 5 : 4,
        gives: SEARCH_WAY.gives,
        searchShapeNeedsRefresh: false,
      };
      const { sent } = opening(DRAFT, {
        [`GET ${ADDRESS}`]: [reply(question)],
        [`PUT ${ADDRESS}/searching`]: [reply(updated)],
      });
      await opened();
      expect(requestsTo(sent)).toEqual([
        `GET ${ADDRESS}`,
        `PUT ${ADDRESS}/searching`,
      ]);
      expect(JSON.parse(String(sent.mock.calls[1]![1]?.body))).toEqual({
        revision: 4,
        searchWay: "web",
      });
      expect(
        screen.getByRole("switch", { name: "For searching" }),
      ).toBeChecked();
      if (changed)
        expect(
          screen.getByText(
            /now gives back something different, so this answer was updated to match/,
          ),
        ).toBeInTheDocument();
      else
        expect(
          screen.queryByText(/now gives back something different/),
        ).toBeNull();
    },
  );

  it.each(["submitted", "draft"] as const)(
    "leaves a search declaration unchanged when the %s version cannot be written",
    async (standing) => {
      const { sent } = opening(
        { ...DRAFT, standing, stateActs: new Set() },
        {
          [`GET ${ADDRESS}`]: [
            reply({
              ...QUESTION,
              searchWay: "web",
              searchWays: [SEARCH_WAY],
              searchShapeNeedsRefresh: true,
            }),
          ],
        },
      );
      await opened();
      expect(requestsTo(sent)).toEqual([`GET ${ADDRESS}`]);
      expect(
        screen.queryByRole("switch", { name: "For searching" }),
      ).toBeNull();
      expect(part("For searching")).toHaveTextContent(
        `Search with: ${isolatedInText("web")}`,
      );
      expect(
        screen.queryByText(/now gives back something different/),
      ).toBeNull();
    },
  );

  it("turns on the selected search with its revision and uses the projected answer returned by that PUT", async () => {
    const { sent } = opening(DRAFT, {
      [`GET ${ADDRESS}`]: [reply({ ...QUESTION, searchWays: [SEARCH_WAY] })],
      [`PUT ${ADDRESS}/searching`]: [
        reply({
          ...QUESTION,
          revision: 5,
          searchWays: [SEARCH_WAY],
          searchWay: "web",
          gives: SEARCH_WAY.gives,
        }),
      ],
    });
    await opened();
    await userEvent.click(
      screen.getByRole("switch", { name: "For searching" }),
    );
    await waitFor(() =>
      expect(requestsTo(sent)).toEqual([
        `GET ${ADDRESS}`,
        `PUT ${ADDRESS}/searching`,
      ]),
    );
    expect(JSON.parse(String(sent.mock.calls[1]![1]?.body))).toEqual({
      revision: 4,
      searchWay: "web",
    });
    await waitFor(() =>
      expect(
        screen.getByRole("switch", { name: "For searching" }),
      ).toBeChecked(),
    );
    expect(part("The declared answer")).toHaveTextContent("references");
    expect(part("The declared answer")).not.toHaveTextContent("summary");
  });

  it("does not carry a successful refresh notice into another version shown by the same component", async () => {
    const nextVersion = {
      ...DRAFT,
      versionId: "00000007-0000-4000-8000-000000000c42",
      number: 4,
    };
    const nextAddress = `/api/groups/${GROUP}/questions/${ENTRY}/versions/${nextVersion.versionId}`;
    const search = {
      ...QUESTION,
      searchWay: "web",
      searchWays: [SEARCH_WAY],
      searchShapeNeedsRefresh: true,
    };
    const { sent, showVersion } = opening(DRAFT, {
      [`GET ${ADDRESS}`]: [reply(search)],
      [`PUT ${ADDRESS}/searching`]: [
        reply({ ...search, revision: 5, searchShapeNeedsRefresh: false }),
      ],
      [`GET ${nextAddress}`]: [
        reply({
          ...QUESTION,
          revision: 6,
          instruction: "Review the new question.",
        }),
      ],
    });
    await opened();
    expect(
      screen.getByText(/now gives back something different/),
    ).toBeInTheDocument();
    showVersion(nextVersion);
    await waitFor(async () =>
      expect(
        within(await editingPart("The instruction")).getByRole("textbox", {
          name: "The instruction",
        }).textContent,
      ).toBe("Review the new question."),
    );
    expect(screen.queryByText(/now gives back something different/)).toBeNull();
    expect(requestsTo(sent)).toEqual([
      `GET ${ADDRESS}`,
      `PUT ${ADDRESS}/searching`,
      `GET ${nextAddress}`,
    ]);
  });

  it("offers the exact Markdown guidance and saves the raw drawing on a draft", async () => {
    const drawing = "## Case\n{{complaint}} \\{{literal}}";
    const { sent } = opening(DRAFT, {
      [`PUT ${ADDRESS}/drawing`]: [
        reply({ ...QUESTION, revision: 5, drawing }),
      ],
    });
    await opened();

    const section = await editingPart("How it is drawn");
    const box = within(section).getByRole("textbox", {
      name: "How it is drawn",
    });
    expect(section).toHaveTextContent(
      "Written as Markdown. Put {{name}} where a value it takes should appear; write \\{{ for two braces as they are. A value of several lines put on a line by itself is laid out as Markdown there, and any other value is drawn as text. No value changes the drawing around it, or becomes where a link goes or an image.",
    );
    fireEvent.change(box, { target: { value: drawing } });
    await userEvent
      .setup()
      .click(screen.getByRole("button", { name: "Save how it is drawn" }));

    await waitFor(() => expect(sent).toHaveBeenCalledTimes(2));
    expect(JSON.parse(String(sent.mock.calls[1]![1]?.body))).toEqual({
      revision: 4,
      drawing,
    });
    expect(
      within(await editingPart("How it is drawn")).getByRole("textbox"),
    ).toHaveValue(drawing);
  });

  it("names an absent drawing as the normal field-by-field view", async () => {
    opening({ ...DRAFT, standing: "submitted", stateActs: new Set() });
    await opened();

    expect(part("How it is drawn")).toHaveTextContent(
      "It has no drawing, so a person answering it is shown what it takes field by field.",
    );
    expect(
      within(part("How it is drawn")).queryByRole("button", {
        name: "About how it is drawn",
      }),
    ).toBeNull();
  });

  it("says why a draft's drawing may be left empty behind an information mark beside its title, the line pressed into its box holding nothing to open", async () => {
    opening(DRAFT);
    await opened();
    const region = part("How it is drawn");

    const about = within(region).getByRole("button", {
      name: "About how it is drawn",
    });
    const pressed = within(region).getByRole("button", {
      name: "Edit how it is drawn",
    });

    expect(about).toHaveAccessibleDescription(
      "How the inputs are laid out for a person answering this question. Left empty, they are shown field by field.",
    );
    expect(region.querySelector("h3")!.parentElement!.contains(about)).toBe(
      true,
    );
    expect(wordsBeside(pressed)).toHaveTextContent(
      "Inputs are shown field by field.",
    );
    expect(wordsBeside(pressed).querySelector("summary, details")).toBeNull();
    expect(region.querySelector("details")).toBeNull();
    expect(region).not.toHaveTextContent("It has no drawing");
  });

  it.each([
    [
      " ",
      "A drawing is one to 8192 characters with something in it that shows. Lines may be broken, and indented with tabs.",
    ],
    [
      "\n\t",
      "A drawing is one to 8192 characters with something in it that shows. Lines may be broken, and indented with tabs.",
    ],
    [
      "a".repeat(8193),
      "A drawing is one to 8192 characters with something in it that shows. Lines may be broken, and indented with tabs.",
    ],
    [
      "🙂".repeat(8193),
      "A drawing is one to 8192 characters with something in it that shows. Lines may be broken, and indented with tabs.",
    ],
  ])(
    "refuses a drawing containing %j before it sends a request",
    async (typed, why) => {
      const { sent } = opening(DRAFT);
      await opened();
      const box = within(await editingPart("How it is drawn")).getByRole(
        "textbox",
      );
      fireEvent.change(box, { target: { value: typed } });

      const save = screen.getByRole("button", { name: "Save how it is drawn" });
      expect(save).toHaveAttribute("aria-disabled", "true");
      expect(box).toHaveAccessibleDescription(why);
      expect(requestsTo(sent)).toEqual([`GET ${ADDRESS}`]);
    },
  );

  it.each(["a".repeat(8192), "🙂".repeat(8192), "a\u202Eb", "a\u{E0041}b"])(
    "sends a drawing at its boundary or with a direction control or tag",
    async (typed) => {
      const { sent } = opening(DRAFT, {
        [`PUT ${ADDRESS}/drawing`]: [reply({ ...QUESTION, drawing: typed })],
      });
      await opened();
      const box = within(await editingPart("How it is drawn")).getByRole(
        "textbox",
      );
      fireEvent.change(box, { target: { value: typed } });
      await userEvent.click(
        screen.getByRole("button", { name: "Save how it is drawn" }),
      );

      await waitFor(() =>
        expect(requestsTo(sent)).toContain(`PUT ${ADDRESS}/drawing`),
      );
      expect(JSON.parse(String(sent.mock.calls[1]![1]?.body))).toEqual({
        revision: 4,
        drawing: typed,
      });
    },
  );

  it.each([
    ">".repeat(33),
    "> ".repeat(32) + "-\nx",
    " ".repeat(64) + "- x",
    "*a ".repeat(33) + "a* ".repeat(32) + "a*",
    "_a ".repeat(17) + "*a\n".repeat(16) + "a* ".repeat(16) + "a_ ".repeat(17),
  ])(
    "refuses a drawing that could nest past 32 deep, %j, before it sends a request",
    async (typed) => {
      const { sent } = opening(DRAFT);
      await opened();
      const box = within(await editingPart("How it is drawn")).getByRole(
        "textbox",
      );
      fireEvent.change(box, { target: { value: typed } });

      const save = screen.getByRole("button", { name: "Save how it is drawn" });
      expect(save).toHaveAttribute("aria-disabled", "true");
      expect(box).toHaveAccessibleDescription(
        "A drawing holds lists and quotes at most 32 deep, counting on each line every marker it opens with and every two spaces of indent before and between them, a tab as four; and emphasis at most 32 deep, counting every two asterisks or underscores between blank lines, an underscore inside a word aside.",
      );
      expect(requestsTo(sent)).toEqual([`GET ${ADDRESS}`]);
    },
  );

  it.each([
    [
      "too long",
      ">".repeat(8193),
      "A drawing is one to 8192 characters with something in it that shows. Lines may be broken, and indented with tabs.",
    ],
    [
      "showing nothing",
      " ".repeat(66),
      "A drawing is one to 8192 characters with something in it that shows. Lines may be broken, and indented with tabs.",
    ],
  ])(
    "refuses a drawing both too deep and %s for what else is wrong with it, before it sends a request",
    async (_case, typed, why) => {
      const { sent } = opening(DRAFT);
      await opened();
      const box = within(await editingPart("How it is drawn")).getByRole(
        "textbox",
      );
      fireEvent.change(box, { target: { value: typed } });

      const save = screen.getByRole("button", { name: "Save how it is drawn" });
      expect(save).toHaveAttribute("aria-disabled", "true");
      expect(box).toHaveAccessibleDescription(why);
      expect(requestsTo(sent)).toEqual([`GET ${ADDRESS}`]);
    },
  );

  it.each([
    ">".repeat(32),
    "> ".repeat(31) + "-\nx",
    " ".repeat(62) + "- x",
    "*a ".repeat(32) + "a* ".repeat(31) + "a*",
    "{{first_name}} *a\n\na* ".repeat(33),
  ])("sends a drawing nesting at most 32 deep, %j, as typed", async (typed) => {
    const { sent } = opening(DRAFT, {
      [`PUT ${ADDRESS}/drawing`]: [reply({ ...QUESTION, drawing: typed })],
    });
    await opened();
    const box = within(await editingPart("How it is drawn")).getByRole(
      "textbox",
    );
    fireEvent.change(box, { target: { value: typed } });
    const save = screen.getByRole("button", { name: "Save how it is drawn" });

    expect(save).not.toHaveAttribute("aria-disabled");
    expect(box).toHaveAccessibleDescription(
      "Written as Markdown. Put {{name}} where a value it takes should appear; write \\{{ for two braces as they are. A value of several lines put on a line by itself is laid out as Markdown there, and any other value is drawn as text. No value changes the drawing around it, or becomes where a link goes or an image.",
    );
    await userEvent.click(save);
    await waitFor(() =>
      expect(requestsTo(sent)).toEqual([
        `GET ${ADDRESS}`,
        `PUT ${ADDRESS}/drawing`,
      ]),
    );
    expect(JSON.parse(String(sent.mock.calls[1]![1]?.body))).toEqual({
      revision: 4,
      drawing: typed,
    });
  });

  /** Nothing to change where the server says the reader may not write: every part is only read. */
  it("reads every part of a version the reader may not write, and offers nothing to change", async () => {
    opening({
      ...DRAFT,
      standing: "in_service",
      stateActs: new Set(["retire"]),
    });
    await opened();

    const instruction = within(part("The instruction")).getByText(
      /^Say which category\./,
    );
    expect(instruction.textContent).toBe("Say which category.\n\tBriefly.");
    expect(part("What it takes")).toHaveTextContent("complaint");
    expect(part("The declared answer")).toHaveTextContent("summary");
    await userEvent.click(
      screen.getByRole("button", { name: "What is added when it runs" }),
    );
    expect(
      part("What is added when it runs").querySelector("pre")?.textContent,
    ).toBe(QUESTION.added);
    expect(
      document.querySelectorAll(
        "input:not([disabled]), textarea:not([disabled])",
      ),
    ).toHaveLength(0);
    expect(screen.queryByRole("switch", { name: "For searching" })).toBeNull();
    expect(screen.queryByRole("combobox", { name: "Search with" })).toBeNull();
    expect(screen.queryByRole("region", { name: "For searching" })).toBeNull();
    expect(screen.queryByRole("button", { name: /^Save / })).toBeNull();
  });

  it("draws every part as a block of the version, none within another", async () => {
    opening({
      ...DRAFT,
      standing: "in_service",
      stateActs: new Set(["retire"]),
    });
    await opened();

    const parts = [
      "The instruction",
      "How it is drawn",
      "What it takes",
      "The declared answer",
      "What is added when it runs",
    ].map(part);

    expect(parts.map(drawnOf)).toEqual(parts.map(() => drawnAtDepth(2)));
    expect(
      parts.flatMap((outer) =>
        parts.filter((inner) => inner !== outer && outer.contains(inner)),
      ),
    ).toEqual([]);
  });

  it("says a version telling nobody anything so, and that what is added waits on a whole declared answer", async () => {
    const { instruction: _said, added: _told, ...unsaid } = QUESTION;
    opening(
      { ...DRAFT, standing: "submitted", stateActs: new Set() },
      { [`GET ${ADDRESS}`]: [reply(unsaid)] },
    );
    await opened();

    expect(part("The instruction")).toHaveTextContent(
      "It tells nobody anything yet.",
    );
    expect(part("What is added when it runs")).toHaveTextContent(
      "It is shown once every field of the declared answer says all it has to.",
    );
  });

  it.each([
    ["the instruction", QUESTION.instruction],
    [
      "the line saying it tells nobody anything",
      "It tells nobody anything yet.",
    ],
  ] as const)(
    "offers %s of a draft as the one press into the instruction's box, with no Edit button beside its title",
    async (_case, read) => {
      const { instruction: _said, ...unsaid } = QUESTION;
      opening(
        DRAFT,
        read === QUESTION.instruction
          ? {}
          : { [`GET ${ADDRESS}`]: [reply(unsaid)] },
      );
      await opened();
      const region = part("The instruction");

      const buttons = within(region).getAllByRole("button");
      expect(
        buttons.map((button) => button.getAttribute("aria-label")),
      ).toEqual(["Edit the instruction"]);
      expect(within(region).queryByRole("button", { name: "Edit" })).toBeNull();
      expect(wordsBeside(buttons[0]!).textContent).toBe(read);

      await userEvent.click(wordsBeside(buttons[0]!));

      expect(
        within(region).getByRole("textbox", { name: "The instruction" }),
      ).toHaveFocus();
      expect(
        within(region).queryByRole("button", { name: "Edit the instruction" }),
      ).toBeNull();
    },
  );

  it.each([
    ["Cancel", "Say which category.\n\tBriefly."],
    ["Save the instruction", "Say which category.\n\tBriefly. Now."],
  ] as const)(
    "puts the keyboard back on the instruction's edit control once %s, pressed by keyboard, shuts its box",
    async (control, read) => {
      opening(DRAFT, {
        [`PUT ${ADDRESS}/instruction`]: [
          reply({ ...QUESTION, revision: 5, instruction: read }),
        ],
      });
      await opened();
      const region = part("The instruction");
      await userEvent.click(
        within(region).getByRole("button", { name: "Edit the instruction" }),
      );
      await editInstruction(
        within(region).getByRole("textbox", { name: "The instruction" }),
        "Say which category.\n\tBriefly. Now.",
      );

      await act(async () => {
        within(region).getByRole("button", { name: control }).focus();
      });
      await userEvent.keyboard("{Enter}");

      const edit = await within(region).findByRole("button", {
        name: "Edit the instruction",
      });
      await waitFor(() => expect(document.activeElement).toBe(edit));
      expect(within(region).queryByRole("textbox")).toBeNull();
      expect(wordsBeside(edit).textContent).toBe(read);
    },
  );

  /** Written on its own, and drawn as the version then reads, the rest as it was. */
  it("writes the instruction of a draft as typed, lines and tabs and all, draws what the write answered, and tells the page", async () => {
    const { sent, written: told } = opening(DRAFT, {
      [`PUT ${ADDRESS}/instruction`]: [
        reply({ ...QUESTION, instruction: "Say why.\nBriefly." }),
      ],
    });
    await opened();
    const box = within(await editingPart("The instruction")).getByRole(
      "textbox",
      {
        name: "The instruction",
      },
    );

    await editInstruction(box, "Say why.\nBriefly.");
    await userEvent.click(
      screen.getByRole("button", { name: "Save the instruction" }),
    );

    await waitFor(() =>
      expect(requestsTo(sent)).toContain(`PUT ${ADDRESS}/instruction`),
    );
    const written = sent.mock.calls.find(([, init]) => init?.method === "PUT");
    expect(JSON.parse(String(written![1]?.body))).toEqual({
      revision: 4,
      instruction: "Say why.\nBriefly.",
    });
    await waitFor(() => expect(box.textContent).toBe("Say why.\nBriefly."));
    expect(told).toHaveBeenCalledTimes(1);
  });

  it("sends an instruction emptied as none, rather than as an empty one", async () => {
    const { sent } = opening(DRAFT, {
      [`PUT ${ADDRESS}/instruction`]: [reply(QUESTION)],
    });
    await opened();

    await editInstruction(
      within(await editingPart("The instruction")).getByRole("textbox", {
        name: "The instruction",
      }),
      "",
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Save the instruction" }),
    );

    await waitFor(() =>
      expect(sent.mock.calls.some(([, init]) => init?.method === "PUT")).toBe(
        true,
      ),
    );
    const written = sent.mock.calls.find(([, init]) => init?.method === "PUT");
    expect(JSON.parse(String(written![1]?.body))).toEqual({
      revision: 4,
      instruction: null,
    });
  });

  it("keeps the Save of an instruction changed drawn as the leading button once its write is refused", async () => {
    opening(DRAFT, {
      [`PUT ${ADDRESS}/instruction`]: [
        reply({ code: "DRAFT_WRITTEN_SINCE_READ" }, 409),
      ],
    });
    await opened();
    await editInstruction(
      within(await editingPart("The instruction")).getByRole("textbox", {
        name: "The instruction",
      }),
      "Say why.",
    );

    await userEvent.click(
      screen.getByRole("button", { name: "Save the instruction" }),
    );

    expect(
      await within(part("The instruction")).findByRole("alert"),
    ).toBeInTheDocument();
    const save = screen.getByRole("button", { name: "Save the instruction" });
    expect(save).toHaveClass("MuiButton-contained");
    expect(within(part("The instruction")).getByText("Unsaved")).toBeVisible();
  });

  it("draws no Save of an instruction as it was saved, and holds one the server would refuse, saying why, sending nothing", async () => {
    const { sent } = opening(DRAFT);
    await opened();
    await editingPart("The instruction");
    expect(
      screen.queryByRole("button", { name: "Save the instruction" }),
    ).toBeNull();
    expect(
      within(part("The instruction")).getByRole("button", { name: "Cancel" }),
    ).toBeInTheDocument();
    expect(within(part("The instruction")).queryByText("Unsaved")).toBeNull();

    await editInstruction(
      within(await editingPart("The instruction")).getByRole("textbox", {
        name: "The instruction",
      }),
      "\n\n",
    );

    const save = screen.getByRole("button", { name: "Save the instruction" });
    expect(save).toHaveClass("MuiButton-contained");
    expect(save).toHaveAttribute("aria-disabled", "true");
    expect(requestsTo(sent)).toEqual([`GET ${ADDRESS}`]);
  });

  /** Refused as the server refuses it, each kind of character hidden from whoever reads said by its kind. */
  it.each([
    [
      "Say \u202Ewhy.",
      "What you wrote contains a text-direction character, which can't be sent to a model.",
    ],
    [
      "Say \u2066why.",
      "What you wrote contains a text-direction character, which can't be sent to a model.",
    ],
    [
      "Say \u{E0041}why.",
      "What you wrote contains an invisible tag character, which can't be sent to a model.",
    ],
    [
      "Bell\u0007",
      "An instruction is one to 8192 characters with something in it that shows. Lines may be broken, and indented with tabs.",
    ],
  ])(
    "holds an instruction holding %j, saying why as the server would",
    async (typed, why) => {
      const { sent } = opening(DRAFT);
      await opened();
      const box = within(await editingPart("The instruction")).getByRole(
        "textbox",
        {
          name: "The instruction",
        },
      );

      await editInstruction(box, "");
      fireEvent.paste(box, { clipboardData: { getData: () => typed } });

      const save = screen.getByRole("button", { name: "Save the instruction" });
      expect(save).toHaveAttribute("aria-disabled", "true");
      expect(save).toHaveAccessibleDescription(why);
      expect(box).toHaveAccessibleDescription(why);
      expect(requestsTo(sent)).toEqual([`GET ${ADDRESS}`]);
    },
  );

  it("takes a joiner and a non-joiner, which some words are spelt with", async () => {
    opening(DRAFT);
    await opened();
    const box = within(await editingPart("The instruction")).getByRole(
      "textbox",
      {
        name: "The instruction",
      },
    );

    await editInstruction(box, "");
    fireEvent.paste(box, {
      clipboardData: { getData: () => "Mi\u200Ctra a\u200Db" },
    });

    expect(
      screen.getByRole("button", { name: "Save the instruction" }),
    ).not.toHaveAttribute("aria-disabled");
  });

  /** The page reads the version again after some refusals; what it holds of the question is read with it. */
  it("reads the question again each time the page reads the entry again, and tells the page what it shows", async () => {
    const renamed = {
      ...QUESTION,
      instruction: "Say it again.",
      takes: [{ ...QUESTION.takes[0], name: "grievance" }],
    };
    const { sent, shown, readAgain } = opening(
      { ...DRAFT, standing: "in_service", stateActs: new Set() },
      { [`GET ${ADDRESS}`]: [reply(QUESTION), reply(renamed)] },
    );
    await opened();
    await waitFor(() =>
      expect(shown).toHaveBeenLastCalledWith({
        kind: "question",
        takes: [expect.objectContaining({ name: "complaint" })],
        gives: [expect.objectContaining({ name: "summary" })],
        steps: [],
        outputs: [],
        terms: [],
      }),
    );

    readAgain(0);
    expect(requestsTo(sent)).toEqual([`GET ${ADDRESS}`]);
    readAgain(1);

    expect(
      await within(part("The instruction")).findByText("Say it again."),
    ).toBeInTheDocument();
    expect(requestsTo(sent)).toEqual([`GET ${ADDRESS}`, `GET ${ADDRESS}`]);
    await waitFor(() =>
      expect(shown).toHaveBeenLastCalledWith(
        expect.objectContaining({
          takes: [expect.objectContaining({ name: "grievance" })],
        }),
      ),
    );
  });

  /** Each half is sent to the address of its own, and only the half saved is drawn afresh. */
  it("sends each half to its own address, keeping what is typed in the other", async () => {
    const answered = {
      ...QUESTION,
      gives: [
        {
          ...QUESTION.gives[0],
          fieldId: "0000000b-0000-4000-8000-000000000c49",
        },
      ],
    };
    const { sent } = opening(DRAFT, {
      [`PUT ${ADDRESS}/gives`]: [reply(answered)],
    });
    await opened();
    const takenName = await nameInOpenedField("What it takes");
    await userEvent.type(takenName, "_text");

    await nameInOpenedField("The declared answer");
    await userEvent.click(
      within(part("The declared answer")).getByRole("checkbox", {
        name: "Holds many",
      }),
    );
    await userEvent.click(await saveHalf("The declared answer"));

    await waitFor(() =>
      expect(requestsTo(sent)).toContain(`PUT ${ADDRESS}/gives`),
    );
    const written = sent.mock.calls.find(([, init]) => init?.method === "PUT");
    expect(JSON.parse(String(written![1]?.body))).toEqual({
      revision: 4,
      fields: [
        {
          fieldId: "0000000b-0000-4000-8000-000000000c42",
          name: "summary",
          label: null,
          help: null,
          kind: "text",
          many: true,
          most: null,
          longest: 1000,
          lines: "several",
          mustBeGiven: true,
          stands: "always",
          floor: null,
        },
      ],
    });
    await waitFor(() =>
      expect(
        within(part("The declared answer")).getByRole("button", {
          name: `Open ${isolatedInText("summary")}`,
        }),
      ).toBeInTheDocument(),
    );
    await nameInOpenedField("The declared answer");
    expect(
      within(part("The declared answer")).getByRole("checkbox", {
        name: "Holds many",
      }),
    ).not.toBeChecked();
    expect(takenName).toHaveValue("complaint_text");
  });

  /** That nothing has changed is what to do next; a name the server would refuse is a warning. */
  it("draws no Save of a declaration as read, and holds one holding a name the server would refuse with a warning", async () => {
    opening(DRAFT);
    await opened();
    const save = async () => await saveHalf("What it takes");
    const editor = (await openedField("What it takes")).closest<HTMLElement>(
      '[role="dialog"], section',
    )!;

    expect(within(editor).queryByRole("button", { name: "Save" })).toBeNull();
    expect(within(part("What it takes")).queryByText("Unsaved")).toBeNull();

    await userEvent.type(await nameInOpenedField("What it takes"), " Text");

    expect(await save()).toHaveAttribute("aria-disabled", "true");
    expect(noticesIn(await openedField("What it takes"))).toEqual([
      {
        severity: "warning",
        words: `This field keeps the half from being saved: ${FIELD_NAME_REFUSED}`,
      },
    ]);
  });

  it("hands a refused write to the page, and says it where it was asked", async () => {
    const { refused, written } = opening(DRAFT, {
      [`PUT ${ADDRESS}/takes`]: [
        reply({ code: "VERSION_STANDING_REFUSES" }, 409),
      ],
    });
    await opened();

    await userEvent.type(await nameInOpenedField("What it takes"), "_text");
    await userEvent.click(await saveHalf("What it takes"));

    expect(
      await within(part("What it takes")).findByRole("alert"),
    ).toHaveTextContent("That version's standing does not admit this.");
    expect(refused).toHaveBeenCalledTimes(1);
    expect(refused.mock.calls[0]![0].code).toBe("VERSION_STANDING_REFUSES");
    expect(within(part("The declared answer")).queryByRole("alert")).toBeNull();
    expect(written).not.toHaveBeenCalled();
  });

  /** A write answers with the revision it landed at, which is the one the next write has read. */
  it("sends each write the revision the last answer carried, not the one first read", async () => {
    const { sent } = opening(DRAFT, {
      [`PUT ${ADDRESS}/instruction`]: [
        reply({ ...QUESTION, revision: 5, instruction: "Say why." }),
      ],
      [`PUT ${ADDRESS}/takes`]: [reply({ ...QUESTION, revision: 6 })],
    });
    await opened();
    const box = within(await editingPart("The instruction")).getByRole(
      "textbox",
      {
        name: "The instruction",
      },
    );
    await editInstruction(box, "Say why.");
    await userEvent.click(
      screen.getByRole("button", { name: "Save the instruction" }),
    );
    await waitFor(() => expect(box.textContent).toBe("Say why."));

    await userEvent.type(await nameInOpenedField("What it takes"), "_text");
    await userEvent.click(await saveHalf("What it takes"));

    await waitFor(() =>
      expect(requestsTo(sent)).toContain(`PUT ${ADDRESS}/takes`),
    );
    const revisions = sent.mock.calls
      .filter(([, init]) => init?.method === "PUT")
      .map(([, init]) => JSON.parse(String(init?.body)).revision);
    expect(revisions).toEqual([4, 5]);
  });

  it("holds every other part's save while one write is out, which it would be refused beside", async () => {
    const held = deferred<readonly [string, number]>();
    const { sent } = opening(DRAFT, {
      [`PUT ${ADDRESS}/instruction`]: [held.promise],
    });
    await opened();
    await userEvent.type(await nameInOpenedField("What it takes"), "_text");
    await editInstruction(
      within(await editingPart("The instruction")).getByRole("textbox", {
        name: "The instruction",
      }),
      QUESTION.instruction + " Now.",
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Save the instruction" }),
    );
    const takes = await saveHalf("What it takes");

    await act(async () => {
      takes.focus();
    });
    await userEvent.keyboard("{Enter}");

    expect(takes).toHaveAttribute("aria-disabled", "true");
    expect(requestsTo(sent)).not.toContain(`PUT ${ADDRESS}/takes`);
    held.settle([JSON.stringify({ ...QUESTION, revision: 5 }), 200]);
    await waitFor(() => expect(takes).not.toHaveAttribute("aria-disabled"));
  });

  /** Somebody else's write is not undone by this one, nor is anything typed here let go of unasked. */
  it("keeps everything typed where the draft was written since it was read, and lets go of it only when Load the saved draft is pressed", async () => {
    const theirs = {
      ...QUESTION,
      revision: 7,
      instruction: "Somebody else's.",
    };
    const { sent, refused, written } = opening(DRAFT, {
      [`GET ${ADDRESS}`]: [reply(QUESTION), reply(theirs)],
      [`PUT ${ADDRESS}/instruction`]: [
        reply({ code: "DRAFT_WRITTEN_SINCE_READ" }, 409),
      ],
    });
    await opened();
    const taken = await nameInOpenedField("What it takes");
    await userEvent.type(taken, "_text");
    const box = within(await editingPart("The instruction")).getByRole(
      "textbox",
      {
        name: "The instruction",
      },
    );
    await editInstruction(box, "Mine.");
    await userEvent.click(
      screen.getByRole("button", { name: "Save the instruction" }),
    );
    expect(
      await within(part("The instruction")).findByRole("alert"),
    ).toHaveTextContent(
      "Somebody changed this draft since it was read; nothing was saved.",
    );
    expect(box.textContent).toBe("Mine.");
    expect(taken).toHaveValue("complaint_text");
    expect(refused.mock.calls.map(([problem]) => problem.code)).toEqual([
      "DRAFT_WRITTEN_SINCE_READ",
    ]);
    expect(written).not.toHaveBeenCalled();
    expect(requestsTo(sent).filter((each) => each.startsWith("GET"))).toEqual([
      `GET ${ADDRESS}`,
    ]);

    await userEvent.click(
      within(await editingPart("The instruction")).getByRole("button", {
        name: "Load the saved draft",
      }),
    );

    await waitFor(() =>
      expect(part("The instruction")).toHaveTextContent("Somebody else's."),
    );
    expect(within(part("The instruction")).queryByRole("alert")).toBeNull();
    expect(
      screen.getByRole("heading", { level: 3, name: "The instruction" }),
    ).toHaveFocus();
    expect(await nameInOpenedField("What it takes")).toHaveValue("complaint");
    expect(requestsTo(sent).filter((each) => each.startsWith("GET"))).toEqual([
      `GET ${ADDRESS}`,
      `GET ${ADDRESS}`,
    ]);
    expect(written).toHaveBeenCalledTimes(1);
  });

  it("keeps saying a refused instruction once it is typed back to what is saved, with no Save and no way on to saving, until the next change", async () => {
    const { sent } = opening(DRAFT, {
      [`PUT ${ADDRESS}/instruction`]: [
        reply({ code: "DRAFT_WRITTEN_SINCE_READ" }, 409),
      ],
    });
    await opened();
    const box = within(await editingPart("The instruction")).getByRole(
      "textbox",
      {
        name: "The instruction",
      },
    );
    await editInstruction(box, "Mine.");
    await userEvent.click(
      screen.getByRole("button", { name: "Save the instruction" }),
    );
    await within(part("The instruction")).findByRole("alert");

    await editInstruction(box, QUESTION.instruction);

    expect(
      within(part("The instruction")).getByRole("alert"),
    ).toHaveTextContent(
      "Somebody changed this draft since it was read; nothing was saved.",
    );
    expect(part("The instruction").textContent).not.toMatch(
      /again|load the saved draft/i,
    );
    expect(
      screen.queryByRole("button", { name: "Save the instruction" }),
    ).toBeNull();

    await editInstruction(box, "Mine once more.");

    expect(within(part("The instruction")).queryByRole("alert")).toBeNull();
    expect(
      screen.getByRole("button", { name: "Save the instruction" }),
    ).toBeInTheDocument();
    expect(requestsTo(sent)).toEqual([
      `GET ${ADDRESS}`,
      `PUT ${ADDRESS}/instruction`,
    ]);
  });

  /** What is drawn while it is read afresh is the revision being replaced, which a write would be refused over. */
  it("holds every part's save while the draft is read afresh after Load the saved draft, and sends nothing until the read lands", async () => {
    const afresh = deferred<readonly [string, number]>();
    const { sent } = opening(DRAFT, {
      [`GET ${ADDRESS}`]: [reply(QUESTION), afresh.promise],
      [`PUT ${ADDRESS}/instruction`]: [
        reply({ code: "DRAFT_WRITTEN_SINCE_READ" }, 409),
      ],
    });
    await opened();
    await editInstruction(
      within(await editingPart("The instruction")).getByRole("textbox", {
        name: "The instruction",
      }),
      QUESTION.instruction + " Now.",
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Save the instruction" }),
    );
    await userEvent.click(
      await within(part("The instruction")).findByRole("button", {
        name: "Load the saved draft",
      }),
    );
    await editInstruction(
      within(await editingPart("The instruction")).getByRole("textbox", {
        name: "The instruction",
      }),
      QUESTION.instruction + " Now. Again.",
    );
    await userEvent.type(await nameInOpenedField("What it takes"), "_text");
    const held = [
      screen.getByRole("button", { name: "Save the instruction" }),
      await saveHalf("What it takes"),
    ];

    for (const control of held) {
      await act(async () => {
        control.focus();
      });
      await userEvent.keyboard("{Enter}");
    }

    held.forEach((control) =>
      expect(control).toHaveAttribute("aria-disabled", "true"),
    );
    expect(requestsTo(sent)).toEqual([
      `GET ${ADDRESS}`,
      `PUT ${ADDRESS}/instruction`,
      `GET ${ADDRESS}`,
    ]);
    afresh.settle(reply({ ...QUESTION, revision: 7 }));
    await waitFor(async () =>
      expect(await saveHalf("What it takes")).not.toHaveAttribute(
        "aria-disabled",
      ),
    );
  });
});
