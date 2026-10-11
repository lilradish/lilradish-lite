import { render, screen, within, waitFor } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";

import type { Length } from "../../../api/stepWhereabouts";
import { isolatedInText } from "../../../lib/direction/isolated";
import { answered, inGroup } from "../../../testutil/standingRead";
import { StandingProvider } from "../../../app/standing/StandingContext";
import { RunReadAccess } from "../RunReadAccess";
import { serving, requestsTo } from "../../../testutil/answering";
import { LengthPanel } from "./LengthPanel";
import type { Problem } from "../../../api/problem";
import { ProblemView } from "../../../app/ProblemView";

const GROUP = inGroup("group-1", "CLAIMS", "Claims", []);
const DECLARING = {
  runId: "run-1",
  runVersionId: "version-1",
  declarations: new Map(),
  steps: undefined,
};
const LENGTH: Length = {
  holdId: "hold-1",
  model: "small",
  limitUnits: "600",
  wholeUnits: "9007199254740993",
  excessUnits: "1",
  fixedUnits: "10",
  tellsWhatHappened: false,
  values: [
    {
      valueId: "value-1",
      inputs: ["ticket", "context.summary"],
      units: "9007199254740983",
      from: {
        kind: "step",
        runId: "run-1",
        runNumber: 1,
        stepId: "source-1",
        name: "summarise",
        path: "summary",
      },
      canBeMadeAgain: true,
      fromSubRun: false,
      triesSpent: true,
      mayRefuse: false,
      used: [
        {
          runId: "run-2",
          runNumber: 2,
          stepId: "route-1",
          name: "route_it",
          choseBranch: true,
        },
      ],
    },
    {
      inputs: ["instructions"],
      units: "2",
      from: { kind: "constant" },
      canBeMadeAgain: false,
      fromSubRun: false,
      triesSpent: false,
      mayRefuse: false,
      used: [],
    },
  ],
};

function draw(length = LENGTH, message = false) {
  return render(
    <MemoryRouter initialEntries={["/work/run-1?order=newest&try=2"]}>
      <LengthPanel
        length={length}
        group={GROUP}
        declaring={DECLARING}
        message={message}
        stepId="held-step"
        onShown={() => undefined}
        onRefused={() => undefined}
      />
    </MemoryRouter>,
  );
}

describe("the length panel", () => {
  it("names the producer and its user by their display labels while their links still use step identities", () => {
    const value = LENGTH.values[0]!;
    draw({
      ...LENGTH,
      values: [
        {
          ...value,
          from: { ...value.from, label: "要約\u202e" },
          used: [
            { ...value.used[0]!, choseBranch: false, label: "確認\u202e" },
          ],
        },
      ],
    });
    const producer = screen.getByRole("link", { name: /要約/ });
    expect(producer).toHaveAttribute(
      "href",
      expect.stringContaining("/steps/source-1"),
    );
    expect(screen.getByRole("link", { name: /確認/ })).toHaveAttribute(
      "href",
      expect.stringContaining("/steps/route-1"),
    );
    expect(
      screen.getByText(
        (_, element) =>
          element?.tagName === "P" &&
          element.textContent?.includes("has no attempts left") === true,
      ),
    ).toHaveTextContent("要約");
    expect(screen.queryByText(/summarise/)).not.toBeInTheDocument();
    expect(screen.queryByText(/route it/)).not.toBeInTheDocument();
  });
  it.each([
    [
      "9007199254740993",
      "9007199254741003",
      "About 9,007,199,254,740,993 units now, down from about 9,007,199,254,741,003: about 10 shorter.",
    ],
    ["1", "2", "About 1 unit now, down from about 2: about 1 shorter."],
    ["2", "1", "About 2 units now, up from about 1."],
  ])(
    "compares the current contribution %s with its stored predecessor %s without rounding or a saved delta",
    (units, previousUnits, said) => {
      draw({
        ...LENGTH,
        values: [{ ...LENGTH.values[0]!, units, previousUnits }],
      });
      expect(
        screen.getByText(
          (_, element) =>
            element?.tagName === "P" &&
            element.textContent?.endsWith(said) === true,
        ),
      ).toBeVisible();
    },
  );

  it.each([false, true])(
    "confirms one row without a reason, with spent attempts %s",
    async (spent) => {
      const user = userEvent.setup();
      const refused = serving({
        "PUT /api/groups/group-1/runs/run-1/steps/held-step/length/refusal": [
          [JSON.stringify({ code: "LENGTH_VALUE_USED" }), 409],
        ],
        "GET /api/groups/group-1/runs/run-1/steps/held-step": [
          [JSON.stringify({ code: "RUN_NOT_IN_VIEW" }), 404],
        ],
      });
      const value = {
        ...LENGTH.values[0]!,
        triesSpent: spent,
        used: [],
        mayRefuse: true,
      };
      render(
        <StandingProvider read={answered([], [GROUP])}>
          <RunReadAccess>
            <MemoryRouter>
              <RefusingPanel
                length={{ ...LENGTH, values: [value, LENGTH.values[1]!] }}
              />
            </MemoryRouter>
          </RunReadAccess>
        </StandingProvider>,
      );
      expect(
        screen.getAllByRole("button", { name: "Reject for length" }),
      ).toHaveLength(1);
      await user.click(
        screen.getByRole("button", { name: "Reject for length" }),
      );
      const confirm = screen.getByRole("dialog");
      expect(within(confirm).queryByRole("textbox")).not.toBeInTheDocument();
      expect(
        within(confirm).getByText(
          spent
            ? `${isolatedInText("summarise")} has no attempts left, so it will fail until someone who can answer steps gives it another. This can't be undone.`
            : `${isolatedInText("summarise")} will make it again, using one of its attempts. This can't be undone.`,
        ),
      ).toBeVisible();
      const reject = within(confirm).getByRole("button", {
        name: "Reject for length",
      });
      expect(reject).not.toHaveClass("MuiButton-colorError");
      await user.click(reject);
      await screen.findByText(
        "It can't be rejected now: something has already been made from it.",
      );
      await waitFor(() =>
        expect(requestsTo(refused)).toEqual([
          "PUT /api/groups/group-1/runs/run-1/steps/held-step/length/refusal",
          "GET /api/groups/group-1/runs/run-1/steps/held-step",
        ]),
      );
      expect(JSON.parse(String(refused.mock.calls[0]![1]?.body))).toEqual({
        holdId: "hold-1",
        valueId: "value-1",
      });
      expect(
        screen.getByRole("button", { name: "Reject for length" }),
      ).toBeEnabled();
    },
  );
  it("opens on the page, keeps exact counts and combines repeated source positions", () => {
    draw();
    expect(
      screen.getByRole("heading", { name: "Why it's too long" }),
    ).toBeVisible();
    expect(
      screen.queryByRole("button", { name: /Show why/ }),
    ).not.toBeInTheDocument();
    expect(screen.getByText("600 units")).toBeVisible();
    expect(screen.getByText("about 9,007,199,254,740,993 units")).toBeVisible();
    expect(screen.getByText("about 1 unit")).toBeVisible();
    expect(
      screen.getByText(
        `${isolatedInText("ticket")}, ${isolatedInText("context")} › ${isolatedInText("summary")}: about 9,007,199,254,740,983 units`,
      ),
    ).toBeVisible();
    expect(screen.getAllByRole("listitem")).toHaveLength(2);
    expect(
      screen.getByText(
        `${isolatedInText("summarise")} can make it again, but has no attempts left.`,
      ),
    ).toBeVisible();
    expect(
      screen
        .getByText(
          "Written into this workflow. To shorten it, make a new version of the workflow.",
        )
        .closest("a"),
    ).toBeNull();
    expect(
      screen.getByRole("link", {
        name: "Can't be rejected: this route chose its branch by it.",
      }),
    ).toHaveAttribute(
      "href",
      expect.stringContaining("/run-2/steps/route-1?order=newest"),
    );
    expect(
      screen.getByText("The instruction, and what the system adds to it."),
    ).toBeVisible();
  });

  it("starts shut in a message and opens and shuts only its own details", async () => {
    const user = userEvent.setup();
    draw(LENGTH, true);
    expect(screen.queryByText("600 units")).not.toBeInTheDocument();
    const show = screen.getByRole("button", { name: "Show why it's too long" });
    expect(show).toHaveAttribute("aria-expanded", "false");
    await user.click(show);
    expect(screen.getByText("600 units")).toBeVisible();
    const hide = screen.getByRole("button", { name: "Hide why it's too long" });
    expect(hide).toHaveAttribute("aria-expanded", "true");
    await user.click(hide);
    expect(screen.queryByText("600 units")).not.toBeInTheDocument();
  });

  it("names the sub-run where another output can be made again and includes rejection context in fixed costs", () => {
    draw({
      ...LENGTH,
      tellsWhatHappened: true,
      values: [
        {
          ...LENGTH.values[0]!,
          fromSubRun: true,
          from: { ...LENGTH.values[0]!.from, runId: "run-3", runNumber: 3 },
          used: [],
        },
      ],
    });
    expect(
      screen.getByText(
        "Comes from run 3, a sub-run, where its attempts are made.",
      ),
    ).toBeVisible();
    expect(screen.getByRole("link", { name: "Open run 3" })).toHaveAttribute(
      "href",
      expect.stringContaining("/run-3?order=newest"),
    );
    expect(
      screen.getByText(
        "The instruction, what the system adds to it, and what it tells the model about the last rejected attempt.",
      ),
    ).toBeVisible();
    expect(screen.queryByText(/can make it again/)).not.toBeInTheDocument();
  });
});

function RefusingPanel({ length }: { readonly length: Length }) {
  const [problem, setProblem] = useState<Problem | null>(null);
  return (
    <>
      {problem === null ? null : <ProblemView problem={problem} />}
      <LengthPanel
        length={length}
        group={GROUP}
        declaring={DECLARING}
        stepId="held-step"
        onShown={vi.fn()}
        onRefused={setProblem}
      />
    </>
  );
}
