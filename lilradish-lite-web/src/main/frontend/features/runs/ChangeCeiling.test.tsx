import { ThemeProvider } from "@mui/material/styles";
import { render, screen, waitFor, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { useEffect, useState, type ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";

import type { OwnCeiling, Run } from "../../api/groups/{groupId}/runs/{runId}";
import { RequestFailed } from "../../api/problem";
import { useAction } from "../../lib/request/useAction";
import { theme } from "../../lib/theme/theme";
import { requestsTo, serving, type Reply } from "../../testutil/answering";
import { ChangeCeiling } from "./ChangeCeiling";

const GROUP = "00000003-0000-4000-8000-000000000c61";

const RUN_ID = "00000008-0000-4000-8000-000000000c61";

const ADDRESS = `/api/groups/${GROUP}/runs/${RUN_ID}/ceiling`;

const LIMIT =
  "A ceiling is a whole number from one to 9,007,199,254,740,991, written in digits.";

const APPROVAL =
  "A raise, or taking the ceiling away, waits on approval; until then the run keeps the ceiling it has.";

const WITHDRAWS =
  "A raise waiting on it is withdrawn by any other change asked for here.";

const FIVE_THOUSAND: OwnCeiling = {
  inForce: "5000",
  raiseNeedsApproval: false,
};

const ANSWERED = JSON.stringify({
  runId: RUN_ID,
  number: 7,
  workflow: {
    entryId: "00000006-0000-4000-8000-000000000c61",
    name: "Handle a claim",
    version: 3,
  },
  startedAt: "2026-09-24T08:00:00Z",
  state: "running",
  spend: { sent: "0", cameBack: "0", spent: "0", cameBackUnknown: false },
  ceiling: FIVE_THOUSAND,
  acts: ["change_ceiling"],
});

function themed({ children }: { readonly children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

/**
 * The dialog over the real action it is handed, opened on the ceiling given,
 * shut as the page shuts it; the action refused once already where asked.
 */
function changing(
  ceiling: OwnCeiling = FIVE_THOUSAND,
  routes: Readonly<Record<string, readonly Reply[]>> = {},
  refusedBefore = false,
) {
  const sent = serving(routes);
  const settled = vi.fn();
  function Holder() {
    const [open, setOpen] = useState(true);
    const action = useAction<Run>(settled);
    const { run } = action;
    useEffect(() => {
      if (refusedBefore) {
        run(() =>
          Promise.reject(
            new RequestFailed({ status: 409, code: "RUN_NOT_IN_VIEW" }),
          ),
        );
      }
    }, [run]);
    return (
      <ChangeCeiling
        groupId={GROUP}
        runId={RUN_ID}
        ceiling={ceiling}
        open={open}
        changing={action}
        onShut={() => setOpen(false)}
      />
    );
  }
  render(<Holder />, { wrapper: themed });
  return {
    sent,
    settled,
    dialog: screen.getByRole("dialog", { name: "Change the ceiling" }),
  };
}

function ceilingBox(): HTMLElement {
  return screen.getByRole("textbox", { name: "Ceiling" });
}

function change(): HTMLElement {
  return screen.getByRole("button", { name: "Change" });
}

async function typedAs(typed: string) {
  await userEvent.clear(ceilingBox());
  if (typed !== "") {
    await userEvent.type(ceilingBox(), typed);
  }
}

describe("ChangeCeiling", () => {
  it("opens holding the ceiling in force, and offers no change until it is another", () => {
    changing();

    expect(ceilingBox()).toHaveValue("5000");
    expect(document.activeElement).toBe(ceilingBox());
    expect(change()).toHaveAttribute("aria-disabled", "true");
    expect(change()).toHaveAccessibleDescription("That is the ceiling it has.");
  });

  it("opens empty on a run with no ceiling, which asking for none leaves as it is", () => {
    changing({ raiseNeedsApproval: false });

    expect(ceilingBox()).toHaveValue("");
    expect(change()).toHaveAccessibleDescription("That is the ceiling it has.");
  });

  it.each(["0", "007", "5.5", "5 000", "9007199254740992"])(
    "holds back %s, saying what a ceiling is where it is typed",
    async (typed) => {
      const { sent } = changing();

      await typedAs(typed);

      expect(ceilingBox()).toHaveAttribute("aria-invalid", "true");
      expect(change()).toHaveAttribute("aria-disabled", "true");
      expect(change()).toHaveAccessibleDescription(LIMIT);
      expect(requestsTo(sent)).toEqual([]);
    },
  );

  it.each([
    ["the digits typed", "9007199254740991", "9007199254740991"],
    ["none, where nothing is typed", "", null],
  ])(
    "sends %s, and shuts once the run answers",
    async (_case, typed, ceiling) => {
      const { sent, settled } = changing(FIVE_THOUSAND, {
        [`PATCH ${ADDRESS}`]: [[ANSWERED, 200]],
      });

      await typedAs(typed);
      await userEvent.click(change());

      await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
      expect(requestsTo(sent)).toEqual([`PATCH ${ADDRESS}`]);
      expect(JSON.parse(String(sent.mock.calls[0]![1]?.body))).toEqual({
        ceiling,
      });
      expect(settled).toHaveBeenCalledOnce();
    },
  );

  it.each([
    ["the version asks it", true, [APPROVAL]],
    ["the version does not", false, []],
  ])(
    "says a raise waits on approval only where %s",
    (_case, needsApproval, notes) => {
      const { dialog } = changing({
        inForce: "5000",
        raiseNeedsApproval: needsApproval,
      });

      expect(
        within(dialog)
          .queryAllByText(APPROVAL)
          .map((each) => each.textContent),
      ).toEqual(notes);
    },
  );

  it.each([
    [
      "a raise is waiting",
      {
        ...FIVE_THOUSAND,
        waiting: {
          changeId: "0000000b-0000-4000-8000-000000000c61",
          to: "9000",
          askedBy: { userId: "000c61" },
          askedAt: "2026-09-24T08:30:00Z",
        },
      },
      [WITHDRAWS],
    ],
    ["none is", FIVE_THOUSAND, []],
  ])(
    "says a change withdraws the raise waiting only where %s",
    (_case, ceiling, notes) => {
      const { dialog } = changing(ceiling);

      expect(
        within(dialog)
          .queryAllByText(WITHDRAWS)
          .map((each) => each.textContent),
      ).toEqual(notes);
    },
  );

  it("says a refusal of what it asked under the box, as the rule of starting a run, and stays open", async () => {
    const { dialog } = changing(FIVE_THOUSAND, {
      [`PATCH ${ADDRESS}`]: [
        [JSON.stringify({ code: "ACT_NOT_PERMITTED" }), 403],
      ],
    });

    await typedAs("6000");
    await userEvent.click(change());

    expect(await within(dialog).findByRole("alert")).toHaveTextContent(
      "A group's runs are started, renamed, stopped, opened again and given a ceiling, and a raise asked withdrawn, only by a role in it that may start one.",
    );
    expect(screen.getByRole("dialog", { name: "Change the ceiling" })).toBe(
      dialog,
    );
  });

  /** The action is the page's, so a refusal of some other act it ran is not this opening's to say. */
  it("says nothing of a refusal the action met before this opening asked anything", async () => {
    const { dialog } = changing(FIVE_THOUSAND, {}, true);

    await typedAs("6000");
    await waitFor(() => expect(change()).not.toHaveAttribute("aria-disabled"));

    expect(within(dialog).queryByRole("alert")).toBeNull();
  });
});
