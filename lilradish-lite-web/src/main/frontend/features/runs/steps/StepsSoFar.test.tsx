import { ThemeProvider } from "@mui/material/styles";
import { render, screen, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import type { StepRow } from "../../../api/groups/{groupId}/runs/{runId}/steps";
import { theme } from "../../../lib/theme/theme";
import { stepAnchor } from "../runAddress";
import { styledClassOf, writtenRules } from "../../../testutil/writtenRules";
import { StepsSoFar } from "./StepsSoFar";

const STATES = [
  ["not_started", "Not started"],
  ["running", "Running"],
  ["held_back", "Held back"],
  ["waiting", "Waiting"],
  ["failed", "Failed"],
  ["done", "Done"],
] as const;

const STEPS: StepRow[] = STATES.map(([state], index) => ({
  stepId: `step-${index}`,
  order: index + 1,
  name: `step_${index}`,
  runs: { kind: "question", name: `Demo Sample ${index}` },
  state,
  takesFrom: [],
  cost: {},
  acts: [],
  withheld: [],
}));

function drawn() {
  render(
    <ThemeProvider theme={theme}>
      <StepsSoFar steps={STEPS} wideAtLeast={600} />
      <ol>
        {STEPS.slice(1).map((step) => (
          <li id={stepAnchor(step.stepId)} tabIndex={-1} key={step.stepId}>
            Message for {step.name}
          </li>
        ))}
      </ol>
    </ThemeProvider>,
  );
  return screen.getByRole("navigation", { name: "The steps so far" });
}

describe("StepsSoFar", () => {
  it("lists every step in order with its own six-state badge, leaving only the not-started one inert", () => {
    const index = drawn();
    const rows = within(index).getAllByRole("listitem");

    expect(rows).toHaveLength(6);
    expect(rows.map((row) => row.textContent)).toEqual(
      STATES.map(
        ([, word], at) =>
          `${String.fromCodePoint(0x2068)}Demo Sample ${at}${String.fromCodePoint(0x2069)}${word}`,
      ),
    );
    expect(within(rows[0]!).queryByRole("button")).toBeNull();
    expect(within(index).getAllByRole("button")).toHaveLength(5);
    expect(index).not.toHaveTextContent("An act");
  });

  it("keeps a tall index within the viewport only once the columns stand beside each other", () => {
    const index = drawn();
    const written = writtenRules();

    expect(written).toContain(
      `@container (min-width: 944px){.${styledClassOf(index)}{position:-webkit-sticky;position:sticky;top:64px;max-height:calc(100dvh - 80px);overflow-y:auto;`,
    );
    expect(written).toContain(
      `.${styledClassOf(index)}{margin-top:30px;margin-bottom:30px;`,
    );
    expect(getComputedStyle(index).borderTopWidth).toBe("0px");
  });

  it.each(["click", "{Enter}", " "])(
    "brings a reached message into view and focuses it by %s",
    async (input) => {
      const index = drawn();
      const control = within(index).getByRole("button", {
        name: /Demo Sample 2.*Held back/,
      });
      const message = document.getElementById(stepAnchor(STEPS[2]!.stepId))!;
      const scroll = vi.fn();
      const scrollInIndex = vi.fn();
      Object.defineProperty(message, "scrollIntoView", { value: scroll });
      Object.defineProperty(control, "scrollIntoView", {
        value: scrollInIndex,
      });

      if (input === "click") {
        await userEvent.click(control);
      } else {
        control.focus();
        await userEvent.keyboard(input);
      }

      expect(scrollInIndex).toHaveBeenCalledWith({ block: "nearest" });
      expect(scroll).toHaveBeenCalledWith({ block: "nearest" });
      expect(message).toHaveFocus();
      expect(document.activeElement).not.toBe(control);
    },
  );
});
