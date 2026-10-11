import { ThemeProvider } from "@mui/material/styles";
import { act, render, screen, within } from "@testing-library/react";
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
  ["held_back", "Blocked"],
  ["waiting", "Waiting"],
  ["failed", "Failed"],
  ["done", "Done"],
] as const;

const STEPS: StepRow[] = STATES.map(([state], index) => ({
  stepId: `step-${index}`,
  order: index + 1,
  name: `step_${index}`,
  runs: { versionName: null, kind: "question", name: `Demo Sample ${index}` },
  state,
  takesFrom: [],
  cost: {},
  producedByUserId: null,
  producedByKind: null,
  stateActs: new Set<string>(),
  stateRefusals: [],
}));

function drawn() {
  render(
    <ThemeProvider theme={theme}>
      <StepsSoFar steps={STEPS} />
      <ol>
        {STEPS.slice(1).map((step) => (
          <li id={stepAnchor(step.stepId)} tabIndex={-1} key={step.stepId}>
            Message for {step.name}
          </li>
        ))}
      </ol>
    </ThemeProvider>,
  );
  return screen.getByRole("navigation", { name: "Steps so far" });
}

function hoverStyleFor(element: HTMLElement): CSSStyleDeclaration | undefined {
  const selector = `.${styledClassOf(element)}:hover`;
  let applied: CSSStyleDeclaration | undefined;
  for (const sheet of document.styleSheets) {
    for (const outer of sheet.cssRules) {
      const rules = outer instanceof CSSMediaRule ? outer.cssRules : [outer];
      for (const rule of rules) {
        if (rule instanceof CSSStyleRule && rule.selectorText === selector) {
          applied = rule.style;
        }
      }
    }
  }
  return applied;
}

describe("StepsSoFar", () => {
  it("navigates by the step key while displaying its own human name", async () => {
    const reached: StepRow = { ...STEPS[2]!, label: "申請を確認\u202e" };
    render(
      <ThemeProvider theme={theme}>
        <StepsSoFar steps={[reached]} />
        <div id={stepAnchor(reached.stepId)} tabIndex={-1}>
          Claim details
        </div>
      </ThemeProvider>,
    );
    const index = screen.getByRole("navigation", { name: "Steps so far" });
    const control = within(index).getByRole("button", {
      name: /申請を確認.*Blocked/,
    });
    const target = document.getElementById(stepAnchor(reached.stepId))!;
    const scroll = vi.fn();
    Object.defineProperty(target, "scrollIntoView", { value: scroll });
    Object.defineProperty(control, "scrollIntoView", { value: vi.fn() });

    expect(control.textContent).toBe(
      `1${String.fromCodePoint(0x2068)}申請を確認\u202e${String.fromCodePoint(0x2069)}Blocked`,
    );
    expect(index).not.toHaveTextContent("Demo Sample 2");
    expect(index).not.toHaveTextContent("step_2");
    await userEvent.click(control);
    expect(target).toHaveFocus();
    expect(scroll).toHaveBeenCalledWith({ block: "nearest" });
    expect(reached.stepId).toBe("step-2");
    expect(reached.name).toBe("step_2");
  });

  it("lists every step in order with its own six-state badge, leaving only the not-started one inert", () => {
    const index = drawn();
    const rows = within(index).getAllByRole("listitem");

    expect(rows).toHaveLength(6);
    expect(rows.map((row) => row.textContent)).toEqual(
      STATES.map(
        ([, word], at) =>
          `${at + 1}${String.fromCodePoint(0x2068)}Demo Sample ${at}${String.fromCodePoint(0x2069)}${word}`,
      ),
    );
    expect(within(rows[0]!).queryByRole("button")).toBeNull();
    expect(within(index).getAllByRole("button")).toHaveLength(5);
    expect(index).not.toHaveTextContent("An act");
  });

  it("draws each row at least 36 high, its state as an icon beside its word and never as a colour alone", () => {
    const index = drawn();
    const rows = within(index)
      .getAllByRole("listitem")
      .map((item) => item.firstElementChild as HTMLElement);
    const icons = [
      "RadioButtonUncheckedIcon",
      "AutorenewIcon",
      "PauseCircleOutlinedIcon",
      "HourglassEmptyIcon",
      "ErrorOutlinedIcon",
      "CheckCircleIcon",
    ];

    expect(rows).toHaveLength(STATES.length);
    rows.forEach((row, at) => {
      const state = row.querySelector(`svg[data-testid="${icons[at]}"]`)!;
      expect(getComputedStyle(row).minHeight).toBe("36px");
      expect(state).toHaveAttribute("aria-hidden", "true");
      expect(state.parentElement!.textContent).toBe(STATES[at]![1]);
    });
    expect(
      rows.flatMap((row) => [...row.querySelectorAll("svg")]),
    ).toHaveLength(STATES.length);
  });

  it("rings a reached row in the act colour once the keyboard reaches it, and no row before", async () => {
    const index = drawn();
    const [first, second] = within(index).getAllByRole("button");
    const keptInView = vi.fn();
    Object.defineProperty(first, "scrollIntoView", { value: keptInView });

    expect(first).not.toHaveClass("Mui-focusVisible");
    await userEvent.tab();

    expect(first).toHaveFocus();
    expect(keptInView).toHaveBeenCalledWith({ block: "nearest" });
    expect(first).toHaveClass("Mui-focusVisible");
    expect(second).not.toHaveClass("Mui-focusVisible");
    const ring = getComputedStyle(first!);
    expect([ring.outlineStyle, ring.outlineWidth, ring.outlineColor]).toEqual([
      "solid",
      "2px",
      "rgb(28, 91, 129)",
    ]);
    expect(getComputedStyle(second!).outlineStyle).not.toBe("solid");
  });

  it.each([
    ["running", "Running", "rgb(227, 235, 247)", "#e3ebf7"],
    ["waiting", "Waiting", "rgb(251, 238, 219)", "#fbeedb"],
    ["held_back", "Blocked", "rgb(243, 247, 249)", "#f3f7f9"],
    ["failed", "Failed", "rgba(0, 0, 0, 0)", "transparent"],
  ])(
    "tints the row of only the current state %s, keeps that ground under the pointer, and edges and fills no row",
    (_state, word, ground, written) => {
      const index = drawn();
      const row = within(index).getByRole("button", {
        name: new RegExp(`Demo Sample.*${word}`),
      });
      expect(getComputedStyle(row).width).toBe("100%");
      expect(getComputedStyle(row).backgroundColor).toBe(ground);
      expect(getComputedStyle(row).borderWidth).toBe("0px");
      expect(["", "none"]).toContain(getComputedStyle(row).boxShadow);
      expect(getComputedStyle(row).outlineStyle).not.toBe("solid");
      const hovered = hoverStyleFor(row);
      const expectedColour = document.createElement("span").style;
      expectedColour.backgroundColor = written;
      expect(hovered?.getPropertyValue("background-color")).toBe(
        expectedColour.backgroundColor,
      );
      expect(hovered?.getPropertyValue("--variant-textBg")).toBe(written);
      expect(row.querySelector("div, p")).toBeNull();
      expect(row).not.toHaveAttribute("disabled");
    },
  );

  it("underlines a reached step's name in the primary ink under the pointer, and leaves an unreached one's alone", () => {
    const index = drawn();
    const name = within(index)
      .getByText(/Demo Sample 1/)
      .closest("span")!;
    const unreached = within(index)
      .getByText(/Demo Sample 0/)
      .closest("span")!;
    const row = name.closest("button")!;

    const hovered = hoverStyleFor(row);
    expect(getComputedStyle(row).cursor).toBe("pointer");
    expect(getComputedStyle(name).textDecorationLine).not.toBe("underline");
    expect(hovered?.getPropertyValue("background-color")).toBe(
      getComputedStyle(row).backgroundColor,
    );
    for (const edge of [
      "border-color",
      "border-width",
      "box-shadow",
      "outline",
    ])
      expect(hovered?.getPropertyValue(edge)).toBe("");
    expect(writtenRules()).toContain(
      `.MuiButtonBase-root:hover .${styledClassOf(name)}{color:#192024;-webkit-text-decoration:underline;text-decoration:underline;text-underline-offset:3px;}`,
    );
    expect(unreached.closest(".MuiButtonBase-root")).toBeNull();
  });

  it.each([
    [40, 40, false],
    [60, 40, true],
  ])(
    "gives the full name as a description only when %i pixels exceed %i",
    (height, visible, truncated) => {
      vi.spyOn(HTMLElement.prototype, "scrollHeight", "get").mockReturnValue(
        height,
      );
      vi.spyOn(HTMLElement.prototype, "clientHeight", "get").mockReturnValue(
        visible,
      );
      const name =
        "Check every imaginary crate and list what the supplied files fail to support";
      render(
        <ThemeProvider theme={theme}>
          <StepsSoFar steps={[{ ...STEPS[1]!, label: name }]} />
        </ThemeProvider>,
      );
      const row = screen.getByRole("button", {
        name: new RegExp(`${name}.*Running`),
      });
      expect(row).not.toHaveAttribute("aria-label");
      if (truncated) expect(row).toHaveAccessibleDescription(name);
      else expect(row).not.toHaveAccessibleDescription();
      expect(row).not.toHaveAccessibleName(/^1/);
    },
  );

  it("updates the full-name description when the observed name stops fitting", () => {
    let height = 40;
    let resized: (() => void) | undefined;
    const disconnect = vi.fn();
    vi.spyOn(HTMLElement.prototype, "scrollHeight", "get").mockImplementation(
      () => height,
    );
    vi.spyOn(HTMLElement.prototype, "clientHeight", "get").mockReturnValue(40);
    vi.stubGlobal(
      "ResizeObserver",
      class {
        constructor(callback: () => void) {
          resized = callback;
        }
        observe() {}
        disconnect = disconnect;
      },
    );
    try {
      const name = "Check the imaginary crate";
      const view = render(
        <ThemeProvider theme={theme}>
          <StepsSoFar steps={[{ ...STEPS[1]!, label: name }]} />
        </ThemeProvider>,
      );
      const row = screen.getByRole("button", {
        name: new RegExp(`${name}.*Running`),
      });
      expect(row).not.toHaveAccessibleDescription();
      height = 60;
      act(() => resized!());
      expect(row).toHaveAccessibleDescription(name);
      height = 40;
      act(() => resized!());
      expect(row).not.toHaveAccessibleDescription();
      expect(row).toHaveAccessibleName(new RegExp(`${name}.*Running`));
      view.unmount();
      expect(disconnect).toHaveBeenCalledOnce();
    } finally {
      vi.unstubAllGlobals();
    }
  });

  /** The panel it is drawn in is the one scroll kept in view; a second inside it would scroll within a scroll. */
  it("keeps no scroll, height limit or place in view of its own, at any width", () => {
    const index = drawn();
    const style = getComputedStyle(index);

    expect([style.position, style.maxHeight, style.overflowY]).toEqual([
      "static",
      "none",
      "visible",
    ]);
    expect(writtenRules()).not.toMatch(
      new RegExp(
        `\\.${styledClassOf(index)}\\{[^}]*(sticky|max-height|overflow)`,
      ),
    );
  });

  it("rules itself off across the whole card above it, reading the card's inset and setting none of its own, and sets its steps apart by space alone", () => {
    const index = drawn();
    const list = within(index).getByRole("list");
    const style = getComputedStyle(index);

    expect(style.borderTopWidth).toBe("1px");
    expect(style.borderTopStyle).toBe("solid");
    expect(style.borderTopColor).toBe("rgb(228, 235, 238)");
    expect(style.borderBottomWidth).toBe("0px");
    expect([style.marginTop, style.paddingTop]).toEqual(["18px", "18px"]);
    expect([style.marginLeft, style.marginRight]).toEqual([
      "calc(-1 * var(--card-side-padding))",
      "calc(-1 * var(--card-side-padding))",
    ]);
    expect([style.paddingLeft, style.paddingRight]).toEqual([
      "var(--card-side-padding)",
      "var(--card-side-padding)",
    ]);
    expect(style.getPropertyValue("--card-side-padding")).toBe("");
    expect(writtenRules()).toContain(
      `.${styledClassOf(index)}{margin-left:calc(-1 * var(--card-side-padding));margin-right:calc(-1 * var(--card-side-padding));padding-left:var(--card-side-padding);padding-right:var(--card-side-padding);margin-top:18px;padding-top:18px;border-top-width:1px;border-top-style:solid;border-top-color:#e4ebee;}`,
    );
    expect(writtenRules()).toContain(
      `.${styledClassOf(list)}{margin:0px;padding:0px;list-style:none;display:-webkit-box;display:-webkit-flex;display:-ms-flexbox;display:flex;-webkit-flex-direction:column;-ms-flex-direction:column;flex-direction:column;gap:10px;}`,
    );
  });

  it.each(["click", "{Enter}", " "])(
    "brings a reached message into view and focuses it by %s",
    async (input) => {
      const index = drawn();
      const control = within(index).getByRole("button", {
        name: /Demo Sample 2.*Blocked/,
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
