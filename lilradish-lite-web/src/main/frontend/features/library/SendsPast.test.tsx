import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import type { Step } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}";
import { isolatedInText } from "../../lib/direction/isolated";
import { SendsPast } from "./SendsPast";

const STEPS: readonly Step[] = [
  { stepId: "s1", name: "triage", bindings: [] },
  { stepId: "s2", name: "check", bindings: [] },
];

const TRIAGE = isolatedInText("triage");

const CHECK = isolatedInText("check");

describe("SendsPast", () => {
  it("says each step past what a model takes by its name, what it asks the model and about how far past", () => {
    render(
      <SendsPast
        sendsPast={[
          { stepId: "s1", role: "producing", model: "general", past: 1234 },
          { stepId: "s1", role: "reviewing", model: "small", past: 1 },
          { stepId: "s2", role: "producing", model: "small", past: 2 },
        ]}
        steps={STEPS}
      />,
    );

    const said = within(
      screen.getByRole("list", { name: "What could be too long to send" }),
    )
      .getAllByRole("listitem")
      .map((each) => each.textContent);
    expect(said).toEqual([
      `${TRIAGE}: what it could send ${isolatedInText("general")} to produce runs about 1,234 units past what that model takes.`,
      `${TRIAGE}: what it could send ${isolatedInText("small")} to review runs about 1 unit past what that model takes.`,
      `${CHECK}: what it could send ${isolatedInText("small")} to produce runs about 2 units past what that model takes.`,
    ]);
    expect(screen.queryByText("What does not hold yet")).toBeNull();
    expect(
      screen.getAllByText(
        "Worked out with every value at its limit, against what each model takes now, and only about. It is accepted and approved all the same; a run holds a step back only where what it would send is too long.",
      ),
    ).toHaveLength(1);
  });

  it("says a count past a double's exact integers only as past them, and one at them as it is", () => {
    render(
      <SendsPast
        sendsPast={[
          { stepId: "s1", role: "producing", model: "small", past: 2 ** 63 },
          { stepId: "s1", role: "reviewing", model: "small", past: 2 ** 63 },
          {
            stepId: "s2",
            role: "producing",
            model: "small",
            past: Number.MAX_SAFE_INTEGER,
          },
        ]}
        steps={STEPS}
      />,
    );

    expect(
      screen.getAllByRole("listitem").map((each) => each.textContent),
    ).toEqual([
      `${TRIAGE}: what it could send ${isolatedInText("small")} to produce runs more than 9,007,199,254,740,991 units past what that model takes.`,
      `${TRIAGE}: what it could send ${isolatedInText("small")} to review runs more than 9,007,199,254,740,991 units past what that model takes.`,
      `${CHECK}: what it could send ${isolatedInText("small")} to produce runs about 9,007,199,254,740,991 units past what that model takes.`,
    ]);
  });

  it("says a step the page did not read by its key", () => {
    render(
      <SendsPast
        sendsPast={[
          { stepId: "s9", role: "producing", model: "small", past: 3 },
        ]}
        steps={STEPS}
      />,
    );

    expect(screen.getByRole("listitem").textContent).toBe(
      `${isolatedInText("s9")}: what it could send ${isolatedInText("small")} to produce runs about 3 units past what that model takes.`,
    );
  });

  it("says nothing where no step could send past what its models take", () => {
    const { container } = render(<SendsPast sendsPast={[]} steps={STEPS} />);

    expect(container).toBeEmptyDOMElement();
    expect(screen.queryByRole("list")).toBeNull();
  });
});
