import { ThemeProvider } from "@mui/material/styles";
import { render, screen, waitFor, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import type { ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";

import type { Version } from "../../api/groups/{groupId}/{kind}";
import type { Problem } from "../../api/problem";
import { theme } from "../../lib/theme/theme";
import { requestsTo, serving, type Reply } from "../../testutil/answering";
import { deferred } from "../../testutil/deferred";
import type { ReadContent } from "./ContentProblems";
import { QuestionContent } from "./QuestionContent";

const GROUP = "00000003-0000-4000-8000-000000000c41";

const ENTRY = "00000006-0000-4000-8000-000000000c41";

const VERSION = "00000007-0000-4000-8000-000000000c41";

const ADDRESS = `/api/groups/${GROUP}/questions/${ENTRY}/versions/${VERSION}`;

const DRAFT: Version = {
  versionId: VERSION,
  number: 3,
  standing: "draft",
  writers: [{ userId: "000c41" }],
  writtenByMigration: false,
  acts: new Set(["write", "submit"]),
  pinnedBy: [],
};

const QUESTION = {
  revision: 4,
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
  added: [
    {
      name: "summary",
      kind: "text",
      longest: 1000,
      many: false,
      mustBeGiven: true,
      confidence: false,
    },
  ],
  lists: [],
};

function reply(body: object, status = 200): readonly [string, number] {
  return [JSON.stringify(body), status];
}

function themed({ children }: { readonly children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

function opening(
  version: Version,
  routes: Readonly<Record<string, readonly Reply[]>> = {},
) {
  const sent = serving({ [`GET ${ADDRESS}`]: [reply(QUESTION)], ...routes });
  const refused = vi.fn<(problem: Problem) => void>();
  const written = vi.fn<() => void>();
  const shown = vi.fn<(content: ReadContent | null) => void>();
  const drawn = (readCount: number) => (
    <QuestionContent
      groupId={GROUP}
      entryId={ENTRY}
      version={version}
      readCount={readCount}
      onRefused={refused}
      onWritten={written}
      onShown={shown}
    />
  );
  const { rerender } = render(drawn(0), { wrapper: themed });
  return {
    sent,
    refused,
    written,
    shown,
    readAgain: (readCount: number) => rerender(drawn(readCount)),
  };
}

function part(name: string): HTMLElement {
  return screen.getByRole("region", { name });
}

async function opened(): Promise<void> {
  await screen.findByRole("region", { name: "The instruction" });
}

describe("QuestionContent", () => {
  /** Nothing to change where the server says the reader may not write: every part is only read. */
  it("reads every part of a version the reader may not write, and offers nothing to change", async () => {
    opening({ ...DRAFT, standing: "in_service", acts: new Set(["retire"]) });
    await opened();

    const instruction = within(part("The instruction")).getByText(
      /^Say which category\./,
    );
    expect(instruction.textContent).toBe("Say which category.\n\tBriefly.");
    expect(part("What it takes")).toHaveTextContent("complaint");
    expect(part("The declared answer")).toHaveTextContent("summary");
    expect(part("What is added when it runs")).toHaveTextContent(
      "Text, At most 1000 characters, Holds one",
    );
    expect(document.querySelectorAll("input, textarea, button")).toHaveLength(
      0,
    );
  });

  it("says a version telling nobody anything so, and that what is added waits on a whole declared answer", async () => {
    const { instruction: _said, added: _told, ...unsaid } = QUESTION;
    opening(
      { ...DRAFT, standing: "submitted", acts: new Set() },
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

  /** Written on its own, and drawn as the version then reads, the rest as it was. */
  it("writes the instruction of a draft as typed, lines and tabs and all, draws what the write answered, and tells the page", async () => {
    const { sent, written: told } = opening(DRAFT, {
      [`PUT ${ADDRESS}/instruction`]: [
        reply({ ...QUESTION, instruction: "Say why.\nBriefly." }),
      ],
    });
    await opened();
    const box = within(part("The instruction")).getByRole("textbox", {
      name: "The instruction",
    });

    await userEvent.clear(box);
    await userEvent.type(box, "Say why.{Enter}Briefly.");
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
    await waitFor(() => expect(box).toHaveValue("Say why.\nBriefly."));
    expect(told).toHaveBeenCalledTimes(1);
  });

  it("sends an instruction emptied as none, rather than as an empty one", async () => {
    const { sent } = opening(DRAFT, {
      [`PUT ${ADDRESS}/instruction`]: [reply(QUESTION)],
    });
    await opened();

    await userEvent.clear(
      within(part("The instruction")).getByRole("textbox", {
        name: "The instruction",
      }),
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

  it("holds an instruction unchanged, or one the server would refuse, saying why, and sends nothing", async () => {
    const { sent } = opening(DRAFT);
    await opened();
    const save = screen.getByRole("button", { name: "Save the instruction" });

    save.focus();
    await userEvent.keyboard("{Enter}");
    expect(part("The instruction")).toHaveTextContent(
      "Change the instruction first.",
    );

    await userEvent.clear(
      within(part("The instruction")).getByRole("textbox", {
        name: "The instruction",
      }),
    );
    await userEvent.type(
      within(part("The instruction")).getByRole("textbox", {
        name: "The instruction",
      }),
      "{Enter}{Enter}",
    );

    expect(save).toHaveAttribute("aria-disabled", "true");
    expect(requestsTo(sent)).toEqual([`GET ${ADDRESS}`]);
  });

  /** Refused as the server refuses it, each kind of character hidden from whoever reads said by its kind. */
  it.each([
    [
      "Say \u202Ewhy.",
      "What was written holds a direction control, which text sent to a model may not hold.",
    ],
    [
      "Say \u2066why.",
      "What was written holds a direction control, which text sent to a model may not hold.",
    ],
    [
      "Say \u{E0041}why.",
      "What was written holds a tag character, which shows nothing and which text sent to a model may not hold.",
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
      const box = within(part("The instruction")).getByRole("textbox", {
        name: "The instruction",
      });

      await userEvent.clear(box);
      await userEvent.click(box);
      await userEvent.paste(typed);

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
    const box = within(part("The instruction")).getByRole("textbox", {
      name: "The instruction",
    });

    await userEvent.clear(box);
    await userEvent.click(box);
    await userEvent.paste("Mi\u200Ctra a\u200Db");

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
      { ...DRAFT, standing: "in_service", acts: new Set() },
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
    const takenName = within(part("What it takes")).getByRole("textbox", {
      name: "Name",
    });
    await userEvent.type(takenName, "_text");

    await userEvent.click(
      within(part("The declared answer")).getByRole("checkbox", {
        name: "Holds many",
      }),
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Save the declared answer" }),
    );

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
          mustBeGiven: true,
          stands: "always",
          floor: null,
        },
      ],
    });
    await waitFor(() =>
      expect(
        within(part("The declared answer")).getByRole("checkbox", {
          name: "Holds many",
        }),
      ).not.toBeChecked(),
    );
    expect(takenName).toHaveValue("complaint_text");
  });

  it("hands a refused write to the page, and says it where it was asked", async () => {
    const { refused, written } = opening(DRAFT, {
      [`PUT ${ADDRESS}/takes`]: [
        reply({ code: "VERSION_STANDING_REFUSES" }, 409),
      ],
    });
    await opened();

    await userEvent.type(
      within(part("What it takes")).getByRole("textbox", { name: "Name" }),
      "_text",
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Save what it takes" }),
    );

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
    const box = within(part("The instruction")).getByRole("textbox", {
      name: "The instruction",
    });
    await userEvent.clear(box);
    await userEvent.type(box, "Say why.");
    await userEvent.click(
      screen.getByRole("button", { name: "Save the instruction" }),
    );
    await waitFor(() => expect(box).toHaveValue("Say why."));

    await userEvent.type(
      within(part("What it takes")).getByRole("textbox", { name: "Name" }),
      "_text",
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Save what it takes" }),
    );

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
    await userEvent.type(
      within(part("What it takes")).getByRole("textbox", { name: "Name" }),
      "_text",
    );
    await userEvent.type(
      within(part("The instruction")).getByRole("textbox", {
        name: "The instruction",
      }),
      " Now.",
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Save the instruction" }),
    );
    const takes = screen.getByRole("button", { name: "Save what it takes" });

    takes.focus();
    await userEvent.keyboard("{Enter}");

    expect(takes).toHaveAttribute("aria-disabled", "true");
    expect(requestsTo(sent)).not.toContain(`PUT ${ADDRESS}/takes`);
    held.settle([JSON.stringify({ ...QUESTION, revision: 5 }), 200]);
    await waitFor(() => expect(takes).not.toHaveAttribute("aria-disabled"));
  });

  /** Somebody else's write is not undone by this one, nor is anything typed here let go of unasked. */
  it("keeps everything typed where the draft was written since it was read, and lets go of it only when Reload is pressed", async () => {
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
    const taken = within(part("What it takes")).getByRole("textbox", {
      name: "Name",
    });
    await userEvent.type(taken, "_text");
    const box = within(part("The instruction")).getByRole("textbox", {
      name: "The instruction",
    });
    await userEvent.clear(box);
    await userEvent.type(box, "Mine.");
    await userEvent.click(
      screen.getByRole("button", { name: "Save the instruction" }),
    );
    expect(
      await within(part("The instruction")).findByRole("alert"),
    ).toHaveTextContent(
      "Somebody changed this draft since it was read; nothing was saved.",
    );
    expect(box).toHaveValue("Mine.");
    expect(taken).toHaveValue("complaint_text");
    expect(refused.mock.calls.map(([problem]) => problem.code)).toEqual([
      "DRAFT_WRITTEN_SINCE_READ",
    ]);
    expect(written).not.toHaveBeenCalled();
    expect(requestsTo(sent).filter((each) => each.startsWith("GET"))).toEqual([
      `GET ${ADDRESS}`,
    ]);

    await userEvent.click(
      within(part("The instruction")).getByRole("button", { name: "Reload" }),
    );

    await waitFor(() =>
      expect(
        within(part("The instruction")).getByRole("textbox", {
          name: "The instruction",
        }),
      ).toHaveValue("Somebody else's."),
    );
    expect(
      within(part("What it takes")).getByRole("textbox", { name: "Name" }),
    ).toHaveValue("complaint");
    expect(within(part("The instruction")).queryByRole("alert")).toBeNull();
    expect(
      screen.getByRole("heading", { level: 3, name: "The instruction" }),
    ).toHaveFocus();
    expect(requestsTo(sent).filter((each) => each.startsWith("GET"))).toEqual([
      `GET ${ADDRESS}`,
      `GET ${ADDRESS}`,
    ]);
    expect(written).toHaveBeenCalledTimes(1);
  });

  /** What is drawn while it is read afresh is the revision being replaced, which a write would be refused over. */
  it("holds every part's save while the draft is read afresh after Reload, and sends nothing until the read lands", async () => {
    const afresh = deferred<readonly [string, number]>();
    const { sent } = opening(DRAFT, {
      [`GET ${ADDRESS}`]: [reply(QUESTION), afresh.promise],
      [`PUT ${ADDRESS}/instruction`]: [
        reply({ code: "DRAFT_WRITTEN_SINCE_READ" }, 409),
      ],
    });
    await opened();
    await userEvent.type(
      within(part("The instruction")).getByRole("textbox", {
        name: "The instruction",
      }),
      " Now.",
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Save the instruction" }),
    );
    await userEvent.click(
      await within(part("The instruction")).findByRole("button", {
        name: "Reload",
      }),
    );
    await userEvent.type(
      within(part("The instruction")).getByRole("textbox", {
        name: "The instruction",
      }),
      " Again.",
    );
    await userEvent.type(
      within(part("What it takes")).getByRole("textbox", { name: "Name" }),
      "_text",
    );
    const held = [
      screen.getByRole("button", { name: "Save the instruction" }),
      screen.getByRole("button", { name: "Save what it takes" }),
    ];

    for (const control of held) {
      control.focus();
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
    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: "Save what it takes" }),
      ).not.toHaveAttribute("aria-disabled"),
    );
  });
});
