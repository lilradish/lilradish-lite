import { ThemeProvider } from "@mui/material/styles";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import type { ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";

import type { Version } from "../../api/groups/{groupId}/{kind}";
import type { ReferenceListVersion } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}";
import type { Problem } from "../../api/problem";
import { theme } from "../../lib/theme/theme";
import { requestsTo, serving, type Reply } from "../../testutil/answering";
import { deferred } from "../../testutil/deferred";
import { ReferenceListContent } from "./ReferenceListContent";

const GROUP = "00000003-0000-4000-8000-000000000f11";

const ENTRY = "00000006-0000-4000-8000-000000000f11";

const VERSION = "00000007-0000-4000-8000-000000000f11";

const ADDRESS = `/api/groups/${GROUP}/reference-lists/${ENTRY}/versions/${VERSION}`;

const BILLING = "0000000c-0000-4000-8000-000000000f11";

const DELIVERY = "0000000c-0000-4000-8000-000000000f12";

const DRAFT: Version = {
  versionId: VERSION,
  number: 2,
  standing: "draft",
  writers: [{ userId: "000f11" }],
  writtenByMigration: false,
  acts: new Set(["write", "submit"]),
  pinnedBy: [],
};

const LIST = {
  revision: 3,
  note: "Pick the narrowest.\n\tNever two.",
  terms: [
    { termId: BILLING, term: "Billing", meaning: "Money taken wrongly." },
    {
      termId: DELIVERY,
      term: "Delivery",
      meaning: "Late, lost or broken in transit.",
    },
  ],
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
  const sent = serving({ [`GET ${ADDRESS}`]: [reply(LIST)], ...routes });
  const refused = vi.fn<(problem: Problem) => void>();
  const written = vi.fn<() => void>();
  const shown = vi.fn<(list: ReferenceListVersion | null) => void>();
  const drawn = (readCount: number) => (
    <ReferenceListContent
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
  await screen.findByRole("region", { name: "The terms" });
}

function setApart(words: string): string {
  return `${String.fromCodePoint(0x2068)}${words}${String.fromCodePoint(0x2069)}`;
}

/** Each body a write was sent with, in the order sent. */
function bodiesOf(sent: ReturnType<typeof serving>): unknown[] {
  return sent.mock.calls
    .filter(([, init]) => (init?.method ?? "GET") !== "GET")
    .map(([, init]) => JSON.parse(String(init?.body)));
}

/**
 * A draft somebody else wrote since it was read: a note and a meaning typed over here, and a removal refused
 * for it; read afresh, the list holds only their Delivery.
 */
async function refusedAsWrittenSince() {
  const theirs = {
    revision: 9,
    terms: [{ termId: DELIVERY, term: "Delivery", meaning: "Late." }],
  };
  const handles = opening(DRAFT, {
    [`GET ${ADDRESS}`]: [reply(LIST), reply(theirs)],
    [`POST ${ADDRESS}/terms/${BILLING}/removal`]: [
      reply({ code: "DRAFT_WRITTEN_SINCE_READ" }, 409),
    ],
  });
  await opened();
  await userEvent.type(
    within(part("The note")).getByRole("textbox", { name: "The note" }),
    " Now.",
  );
  await userEvent.type(
    within(part("The terms")).getByRole("textbox", {
      name: "What term 2 means",
    }),
    " Often.",
  );
  await userEvent.click(
    within(part("The terms")).getByRole("button", {
      name: `Remove: ${setApart("Billing")}`,
    }),
  );
  await within(part("The terms")).findByRole("alert");
  return handles;
}

describe("ReferenceListContent", () => {
  it("reads the note above the terms of a version the reader may not write, and offers nothing to change", async () => {
    opening({ ...DRAFT, standing: "in_service", acts: new Set(["retire"]) });
    await opened();

    expect(screen.getAllByRole("region")).toEqual([
      part("The note"),
      part("The terms"),
    ]);
    expect(
      within(part("The note")).getByText(/^Pick the narrowest\./).textContent,
    ).toBe(LIST.note);
    expect(part("The terms")).toHaveTextContent("Billing");
    expect(document.querySelectorAll("input, textarea, button")).toHaveLength(
      0,
    );
  });

  it("writes the note over the revision read", async () => {
    const { sent } = opening(DRAFT, {
      [`PUT ${ADDRESS}/note`]: [
        reply({ ...LIST, revision: 4, note: "Pick one." }),
      ],
    });
    await opened();
    const box = within(part("The note")).getByRole("textbox", {
      name: "The note",
    });

    await userEvent.clear(box);
    await userEvent.type(box, "Pick one.");
    await userEvent.click(
      screen.getByRole("button", { name: "Save the note" }),
    );

    await waitFor(() =>
      expect(requestsTo(sent)).toEqual([
        `GET ${ADDRESS}`,
        `PUT ${ADDRESS}/note`,
      ]),
    );
    expect(bodiesOf(sent)).toEqual([{ revision: 3, note: "Pick one." }]);
  });

  it("draws what the note's write answered, and tells the page it was written", async () => {
    const { written } = opening(DRAFT, {
      [`PUT ${ADDRESS}/note`]: [
        reply({ ...LIST, revision: 4, note: "Pick one." }),
      ],
    });
    await opened();
    const box = within(part("The note")).getByRole("textbox", {
      name: "The note",
    });

    await userEvent.clear(box);
    await userEvent.type(box, "Pick one.");
    await userEvent.click(
      screen.getByRole("button", { name: "Save the note" }),
    );

    await waitFor(() => expect(written).toHaveBeenCalledTimes(1));
    expect(box).toHaveValue("Pick one.");
  });

  it("holds a note the server would refuse, saying why in the refusal's own words, and sends nothing", async () => {
    const { sent } = opening(DRAFT);
    await opened();
    const box = within(part("The note")).getByRole("textbox", {
      name: "The note",
    });

    await userEvent.clear(box);
    await userEvent.click(box);
    await userEvent.paste("n".repeat(2049));

    expect(box).toHaveAccessibleDescription(
      "A note is one to 2048 characters with something in it that shows. Lines may be broken, and indented with tabs.",
    );
    expect(
      screen.getByRole("button", { name: "Save the note" }),
    ).toHaveAttribute("aria-disabled", "true");
    expect(requestsTo(sent)).toEqual([`GET ${ADDRESS}`]);
  });

  /** A write answers with the revision it landed at, which is the one the next write has read. */
  it("sends each change to the terms to its own address, over the revision the last answer carried", async () => {
    const added = {
      revision: 4,
      terms: [
        ...LIST.terms,
        {
          termId: "0000000c-0000-4000-8000-000000000f13",
          term: "Refunds",
          meaning: "Money owed back.",
        },
      ],
    };
    const { sent } = opening(DRAFT, {
      [`POST ${ADDRESS}/terms`]: [reply(added)],
      [`POST ${ADDRESS}/terms/${BILLING}/down`]: [
        reply({
          revision: 5,
          terms: [added.terms[1], added.terms[0], added.terms[2]],
        }),
      ],
      [`POST ${ADDRESS}/terms/${DELIVERY}/removal`]: [
        reply({ revision: 6, terms: [added.terms[0], added.terms[2]] }),
      ],
      [`PUT ${ADDRESS}/terms/${BILLING}`]: [
        reply({
          revision: 7,
          terms: [
            { ...added.terms[0], meaning: "Charged wrongly." },
            added.terms[2],
          ],
        }),
      ],
    });
    await opened();
    const terms = part("The terms");

    await userEvent.type(
      within(terms).getByRole("textbox", { name: "New term" }),
      "Refunds",
    );
    await userEvent.type(
      within(terms).getByRole("textbox", { name: "What the new term means" }),
      "Money owed back.",
    );
    await userEvent.click(
      within(terms).getByRole("button", { name: "Add the term" }),
    );
    await within(terms).findByRole("button", {
      name: `Remove: ${setApart("Refunds")}`,
    });
    await userEvent.click(
      within(terms).getByRole("button", {
        name: `Move down: ${setApart("Billing")}`,
      }),
    );
    await waitFor(() =>
      expect(
        within(terms).getByRole("textbox", { name: "Term 1" }),
      ).toHaveValue("Delivery"),
    );
    await userEvent.click(
      within(terms).getByRole("button", {
        name: `Remove: ${setApart("Delivery")}`,
      }),
    );
    await waitFor(() =>
      expect(
        within(terms).getByRole("textbox", { name: "Term 1" }),
      ).toHaveValue("Billing"),
    );
    const meaning = within(terms).getByRole("textbox", {
      name: "What term 1 means",
    });
    await userEvent.clear(meaning);
    await userEvent.type(meaning, "Charged wrongly.");
    await userEvent.click(
      within(terms).getByRole("button", {
        name: `Save: ${setApart("Billing")}`,
      }),
    );

    await waitFor(() =>
      expect(requestsTo(sent)).toContain(`PUT ${ADDRESS}/terms/${BILLING}`),
    );
    expect(requestsTo(sent)).toEqual([
      `GET ${ADDRESS}`,
      `POST ${ADDRESS}/terms`,
      `POST ${ADDRESS}/terms/${BILLING}/down`,
      `POST ${ADDRESS}/terms/${DELIVERY}/removal`,
      `PUT ${ADDRESS}/terms/${BILLING}`,
    ]);
    expect(bodiesOf(sent)).toEqual([
      { revision: 3, term: "Refunds", meaning: "Money owed back." },
      { revision: 4 },
      { revision: 5 },
      { revision: 6, term: "Billing", meaning: "Charged wrongly." },
    ]);
  });

  it("holds every change to the terms while the note is being saved, which it would be refused beside", async () => {
    const held = deferred<readonly [string, number]>();
    const { sent } = opening(DRAFT, {
      [`PUT ${ADDRESS}/note`]: [held.promise],
    });
    await opened();
    await userEvent.type(
      within(part("The note")).getByRole("textbox", { name: "The note" }),
      " Now.",
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Save the note" }),
    );
    const remove = within(part("The terms")).getByRole("button", {
      name: `Remove: ${setApart("Billing")}`,
    });

    remove.focus();
    await userEvent.keyboard("{Enter}");

    expect(remove).toHaveAttribute("aria-disabled", "true");
    expect(requestsTo(sent)).toEqual([`GET ${ADDRESS}`, `PUT ${ADDRESS}/note`]);
    held.settle([JSON.stringify({ ...LIST, revision: 4 }), 200]);
    await waitFor(() => expect(remove).not.toHaveAttribute("aria-disabled"));
  });

  /** Somebody else's write is not undone by this one, nor is anything typed here let go of unasked. */
  it("keeps everything typed where the list was written since it was read, saying so where it was asked", async () => {
    const { refused, written } = await refusedAsWrittenSince();

    expect(within(part("The terms")).getByRole("alert")).toHaveTextContent(
      "Somebody changed this draft since it was read; nothing was saved.",
    );
    expect(
      within(part("The note")).getByRole("textbox", { name: "The note" }),
    ).toHaveValue(`${LIST.note} Now.`);
    expect(
      within(part("The terms")).getByRole("textbox", {
        name: "What term 2 means",
      }),
    ).toHaveValue("Late, lost or broken in transit. Often.");
    expect(refused.mock.calls.map(([problem]) => problem.code)).toEqual([
      "DRAFT_WRITTEN_SINCE_READ",
    ]);
    expect(written).not.toHaveBeenCalled();
  });

  it("lets go of everything typed only once Reload is pressed, drawing the list as read afresh", async () => {
    const { sent, written } = await refusedAsWrittenSince();

    await userEvent.click(
      within(part("The terms")).getByRole("button", { name: "Reload" }),
    );

    await waitFor(() =>
      expect(
        within(part("The terms")).getByRole("textbox", {
          name: "What term 1 means",
        }),
      ).toHaveValue("Late."),
    );
    expect(
      within(part("The terms")).queryByRole("textbox", { name: "Term 2" }),
    ).toBeNull();
    expect(
      within(part("The note")).getByRole("textbox", { name: "The note" }),
    ).toHaveValue("");
    expect(within(part("The terms")).queryByRole("alert")).toBeNull();
    expect(requestsTo(sent).filter((each) => each.startsWith("GET"))).toEqual([
      `GET ${ADDRESS}`,
      `GET ${ADDRESS}`,
    ]);
    expect(written).toHaveBeenCalledTimes(1);
  });

  it("puts the keyboard on the heading of the part Reload was pressed in once the list is read afresh", async () => {
    await refusedAsWrittenSince();

    await userEvent.click(
      within(part("The terms")).getByRole("button", { name: "Reload" }),
    );

    await waitFor(() =>
      expect(
        screen.getByRole("heading", { level: 3, name: "The terms" }),
      ).toHaveFocus(),
    );
    expect(
      screen.getByRole("heading", { level: 3, name: "The note" }),
    ).not.toHaveFocus();
  });

  /** Drawing the parts anew would lose the answer of the note's write still out. */
  it("holds Reload in the terms while the note is being saved, and reads nothing afresh until it lands", async () => {
    const held = deferred<readonly [string, number]>();
    const { sent } = opening(DRAFT, {
      [`POST ${ADDRESS}/terms/${BILLING}/removal`]: [
        reply({ code: "DRAFT_WRITTEN_SINCE_READ" }, 409),
      ],
      [`PUT ${ADDRESS}/note`]: [held.promise],
    });
    await opened();
    await userEvent.click(
      within(part("The terms")).getByRole("button", {
        name: `Remove: ${setApart("Billing")}`,
      }),
    );
    const reload = await within(part("The terms")).findByRole("button", {
      name: "Reload",
    });
    await userEvent.type(
      within(part("The note")).getByRole("textbox", { name: "The note" }),
      " Now.",
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Save the note" }),
    );

    reload.focus();
    await userEvent.keyboard("{Enter}");

    expect(reload).toHaveAttribute("aria-disabled", "true");
    expect(requestsTo(sent).filter((each) => each.startsWith("GET"))).toEqual([
      `GET ${ADDRESS}`,
    ]);
    held.settle(reply({ ...LIST, revision: 4, note: `${LIST.note} Now.` }));
    await waitFor(() => expect(reload).not.toHaveAttribute("aria-disabled"));
  });

  /** The page reads the version again after some refusals; what it holds of the list is read with it. */
  it("reads the list again each time the page reads the entry again, and at no other redraw", async () => {
    const renamed = { ...LIST, revision: 4, note: "Pick again." };
    const { sent, readAgain } = opening(
      { ...DRAFT, standing: "in_service", acts: new Set() },
      { [`GET ${ADDRESS}`]: [reply(LIST), reply(renamed)] },
    );
    await opened();

    readAgain(0);
    expect(requestsTo(sent)).toEqual([`GET ${ADDRESS}`]);
    readAgain(1);

    expect(
      await within(part("The note")).findByText("Pick again."),
    ).toBeInTheDocument();
    expect(requestsTo(sent)).toEqual([`GET ${ADDRESS}`, `GET ${ADDRESS}`]);
  });

  it("tells the page what it shows, as first read and as read again", async () => {
    const renamed = { ...LIST, revision: 4, note: "Pick again." };
    const { shown, readAgain } = opening(
      { ...DRAFT, standing: "in_service", acts: new Set() },
      { [`GET ${ADDRESS}`]: [reply(LIST), reply(renamed)] },
    );
    await opened();
    await waitFor(() => expect(shown).toHaveBeenLastCalledWith(LIST));

    readAgain(1);

    await waitFor(() => expect(shown).toHaveBeenLastCalledWith(renamed));
  });

  it("marks a term the server answers is alike to one before it", async () => {
    opening(DRAFT, {
      [`GET ${ADDRESS}`]: [
        reply({
          ...LIST,
          terms: [
            LIST.terms[0],
            { ...LIST.terms[1], term: "BILLING", alikeEarlier: true },
          ],
        }),
      ],
    });
    await opened();

    expect(
      within(part("The terms")).getByRole("textbox", { name: "Term 2" }),
    ).toHaveAccessibleDescription(
      "Alike to a term above it, whatever the case either is written in. Submitting refuses it.",
    );
    expect(
      within(part("The terms")).getByRole("textbox", { name: "Term 1" }),
    ).not.toHaveAccessibleDescription();
  });

  /** What is drawn while it is read afresh is the revision being replaced, which a write would be refused over. */
  it("holds every change while the list is read afresh after Reload, and sends nothing until the read lands", async () => {
    const afresh = deferred<readonly [string, number]>();
    const { sent } = opening(DRAFT, {
      [`GET ${ADDRESS}`]: [reply(LIST), afresh.promise],
      [`POST ${ADDRESS}/terms/${BILLING}/removal`]: [
        reply({ code: "DRAFT_WRITTEN_SINCE_READ" }, 409),
      ],
    });
    await opened();
    await userEvent.click(
      within(part("The terms")).getByRole("button", {
        name: `Remove: ${setApart("Billing")}`,
      }),
    );
    await userEvent.click(
      await within(part("The terms")).findByRole("button", { name: "Reload" }),
    );
    const held = [
      within(part("The terms")).getByRole("button", {
        name: `Remove: ${setApart("Delivery")}`,
      }),
      within(part("The note")).getByRole("button", { name: "Save the note" }),
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
      `POST ${ADDRESS}/terms/${BILLING}/removal`,
      `GET ${ADDRESS}`,
    ]);
    afresh.settle(reply({ ...LIST, revision: 5 }));
    await waitFor(() =>
      expect(
        within(part("The terms")).getByRole("button", {
          name: `Remove: ${setApart("Delivery")}`,
        }),
      ).not.toHaveAttribute("aria-disabled"),
    );
  });

  /** The write's answer is the newer; the read, sent before it landed, may carry the revision it replaced. */
  it("keeps drawing what a write answered while a read of the page is out, and keeps it over that read landing older", async () => {
    const removal = deferred<readonly [string, number]>();
    const reread = deferred<readonly [string, number]>();
    const removed = { revision: 4, terms: [LIST.terms[1]] };
    const { shown, readAgain } = opening(DRAFT, {
      [`GET ${ADDRESS}`]: [reply(LIST), reread.promise],
      [`POST ${ADDRESS}/terms/${BILLING}/removal`]: [removal.promise],
    });
    await opened();
    await userEvent.type(
      within(part("The terms")).getByRole("textbox", {
        name: "What term 2 means",
      }),
      " Often.",
    );
    await userEvent.click(
      within(part("The terms")).getByRole("button", {
        name: `Remove: ${setApart("Billing")}`,
      }),
    );
    readAgain(1);

    await act(async () => {
      removal.settle(reply(removed));
    });

    expect(
      within(part("The terms")).getByRole("textbox", {
        name: "What term 1 means",
      }),
    ).toHaveValue("Late, lost or broken in transit. Often.");
    expect(
      within(part("The terms")).queryByRole("textbox", { name: "Term 2" }),
    ).toBeNull();

    await act(async () => {
      reread.settle(reply(LIST));
    });

    await waitFor(() =>
      expect(screen.queryByText("Still reading…")).toBeNull(),
    );
    expect(
      within(part("The terms")).getByRole("textbox", {
        name: "What term 1 means",
      }),
    ).toHaveValue("Late, lost or broken in transit. Often.");
    expect(
      within(part("The terms")).queryByRole("textbox", { name: "Term 2" }),
    ).toBeNull();
    expect(shown).toHaveBeenLastCalledWith(removed);
  });

  it("keeps drawing a read that landed newer than what a write, answering after it, carries", async () => {
    const removal = deferred<readonly [string, number]>();
    const theirs = {
      revision: 5,
      terms: [{ ...LIST.terms[1], meaning: "Late." }],
    };
    const { shown, readAgain } = opening(DRAFT, {
      [`GET ${ADDRESS}`]: [reply(LIST), reply(theirs)],
      [`POST ${ADDRESS}/terms/${BILLING}/removal`]: [removal.promise],
    });
    await opened();
    await userEvent.click(
      within(part("The terms")).getByRole("button", {
        name: `Remove: ${setApart("Billing")}`,
      }),
    );
    readAgain(1);
    await waitFor(() => expect(shown).toHaveBeenLastCalledWith(theirs));

    await act(async () => {
      removal.settle(reply({ revision: 4, terms: [LIST.terms[1]] }));
    });

    expect(
      within(part("The terms")).getByRole("textbox", {
        name: "What term 1 means",
      }),
    ).toHaveValue("Late.");
    expect(shown).toHaveBeenLastCalledWith(theirs);
  });

  /** A removal's keyboard is taken as it lands, drawn or not, and never left for some later read to take. */
  it("leaves the keyboard where the reader put it when a read lands after a removal that landed older than what is shown", async () => {
    const removal = deferred<readonly [string, number]>();
    const theirs = {
      revision: 5,
      terms: [{ ...LIST.terms[1], meaning: "Late." }],
    };
    const { shown, readAgain } = opening(DRAFT, {
      [`GET ${ADDRESS}`]: [
        reply(LIST),
        reply(theirs),
        reply({ ...theirs, revision: 6, terms: [LIST.terms[1]] }),
      ],
      [`POST ${ADDRESS}/terms/${BILLING}/removal`]: [removal.promise],
    });
    await opened();
    await userEvent.click(
      within(part("The terms")).getByRole("button", {
        name: `Remove: ${setApart("Billing")}`,
      }),
    );
    readAgain(1);
    await waitFor(() => expect(shown).toHaveBeenLastCalledWith(theirs));
    await act(async () => {
      removal.settle(reply({ revision: 4, terms: [LIST.terms[1]] }));
    });
    const newTerm = within(part("The terms")).getByRole("textbox", {
      name: "New term",
    });
    newTerm.focus();

    readAgain(2);

    await waitFor(() =>
      expect(
        within(part("The terms")).getByRole("textbox", {
          name: "What term 1 means",
        }),
      ).toHaveValue("Late, lost or broken in transit."),
    );
    expect(newTerm).toHaveFocus();
    expect(
      screen.getByRole("heading", { level: 3, name: "The terms" }),
    ).not.toHaveFocus();
  });
});
