import Box from "@mui/material/Box";
import { ThemeProvider } from "@mui/material/styles";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { useLayoutEffect, useState, type ReactNode } from "react";
import { describe, expect, it, onTestFinished, vi } from "vitest";

import { laidOutAt, pageLaidOutAt } from "../../testutil/layout";
import { styledClassOf, writtenRules } from "../../testutil/writtenRules";
import { theme } from "../theme/theme";
import { focusDrawerHeading } from "./RowDrawer";
import { PANEL_PART_SX, SidePanelHost } from "./SidePanelHost";

function choice(key: "row" | "about", kept: string | null) {
  const name = `lilradish.panel.${key}`;
  const previous = localStorage.getItem(name);
  if (kept === null) localStorage.removeItem(name);
  else localStorage.setItem(name, kept);
  onTestFinished(() => {
    if (previous === null) localStorage.removeItem(name);
    else localStorage.setItem(name, previous);
  });
}

const CRATE_NAME = {
  focus: () =>
    document.querySelector<HTMLElement>('[aria-label="Crate name"]')?.focus(),
};

function revealing(reveal: { readonly focus: () => void } | undefined) {
  return (
    <ThemeProvider theme={theme}>
      <SidePanelHost
        open={reveal !== undefined}
        reveal={reveal}
        title="Crate details"
        onClose={vi.fn()}
        listLabel="Crates"
        list={<button type="button">Crate 2</button>}
      >
        <input aria-label="Notes" />
        <input aria-label="Crate name" />
      </SidePanelHost>
    </ThemeProvider>
  );
}

const SAVED_SINCE = {
  focus: () => document.querySelector<HTMLElement>('[role="alert"]')?.focus(),
};

function Refusing({
  refusal,
}: {
  readonly refusal: { readonly focus: () => void } | null;
}) {
  return (
    <ThemeProvider theme={theme}>
      <SidePanelHost
        open
        insist={refusal}
        title="Crate details"
        onClose={vi.fn()}
        listLabel="Crates"
        list={<p>Crates</p>}
      >
        <input aria-label="Notes" />
        {refusal === null ? null : (
          <div role="alert" tabIndex={-1}>
            Somebody saved this crate since
          </div>
        )}
      </SidePanelHost>
    </ThemeProvider>
  );
}

/** A page whose rows open over it, one row's drawer holding a table of its own whose rows open a drawer too. */
function RowsOverThePage() {
  const [step, setStep] = useState(false);
  const [field, setField] = useState(false);
  return (
    <ThemeProvider theme={theme}>
      <SidePanelHost
        over
        open={step}
        onClose={() => setStep(false)}
        title="Step 2"
        besideHeading={<span>Route</span>}
        headingLevel="h4"
        listLabel="The steps"
        list={
          <button type="button" onClick={() => setStep(true)}>
            Open step 2
          </button>
        }
      >
        <SidePanelHost
          asksRoom
          closeBeside
          open={field}
          onClose={() => setField(false)}
          width={480}
          title="Field 1 of 1"
          headingLevel="h4"
          listLabel="What it gives back"
          listRole="group"
          above={<p>Field instructions</p>}
          foot={<button type="button">Keep field</button>}
          list={
            <button type="button" onClick={() => setField(true)}>
              Open summary
            </button>
          }
        >
          <input aria-label="Label" />
        </SidePanelHost>
      </SidePanelHost>
    </ThemeProvider>
  );
}

/** A row's drawer opened from the page with a drawer in it already open, as a change is located from a count. */
function LocatedFromThePage() {
  const [located, setLocated] = useState(false);
  const [field, setField] = useState(false);
  return (
    <ThemeProvider theme={theme}>
      <SidePanelHost
        over
        open={located}
        onClose={() => setLocated(false)}
        title="Step 2"
        headingLevel="h4"
        listLabel="The steps"
        list={
          <button
            type="button"
            onClick={() => {
              setLocated(true);
              setField(true);
            }}
          >
            Locate summary
          </button>
        }
      >
        <SidePanelHost
          asksRoom
          closeBeside
          open={field}
          onClose={() => setField(false)}
          width={480}
          title="Field 1 of 1"
          headingLevel="h4"
          listLabel="What it gives back"
          listRole="group"
          list={
            <button
              type="button"
              aria-controls="summary-editor"
              onClick={() => setField(true)}
            >
              Open summary
            </button>
          }
        >
          <fieldset id="summary-editor">
            <input aria-label="Label" />
          </fieldset>
        </SidePanelHost>
      </SidePanelHost>
    </ThemeProvider>
  );
}

async function stepOpened() {
  render(<RowsOverThePage />);
  await userEvent.click(screen.getByRole("button", { name: "Open step 2" }));
  return screen.findByRole("dialog", { name: "Step 2" });
}

async function fieldOpened(step: HTMLElement) {
  await userEvent.click(
    within(step).getByRole("button", { name: "Open summary" }),
  );
  return screen.findByRole("dialog", { name: "Field 1 of 1" });
}

function scrims() {
  return [...document.querySelectorAll(".MuiBackdrop-root")];
}

function movedBy(step: HTMLElement) {
  return new RegExp(
    `\\.${styledClassOf(step.firstElementChild!)}\\{[^}]*translate:(-?\\d+)px 0;`,
  ).exec(writtenRules())?.[1];
}

describe("SidePanelHost", () => {
  it.each([
    ["plain content", <p>Crate notes</p>, undefined, "20px"],
    ["text content", "Crate notes", undefined, "20px"],
    [
      "a ruled first part",
      <Box data-panel-part sx={PANEL_PART_SX}>
        <p>Crate notes</p>
      </Box>,
      undefined,
      "0px",
    ],
    [
      "a style node before a ruled first part",
      <>
        <style>{".panel-spacing-fixture {}"}</style>
        <Box component="section" data-panel-part sx={PANEL_PART_SX}>
          <p>Crate notes</p>
        </Box>
      </>,
      undefined,
      "0px",
    ],
    [
      "plain content before a later ruled part",
      <>
        <p>Crate notes</p>
        <Box data-panel-part sx={PANEL_PART_SX}>
          <p>Crate history</p>
        </Box>
      </>,
      undefined,
      "20px",
    ],
    [
      "instructions before the content holder",
      <Box data-panel-part sx={PANEL_PART_SX}>
        <p>Crate notes</p>
      </Box>,
      <p>Crate instructions</p>,
      "20px",
    ],
  ])(
    "separates the title row from %s without duplicating a first part's rule spacing",
    async (_case, content, above, gap) => {
      choice("row", "expanded");
      laidOutAt(1400, 1920);
      await act(async () => {
        render(
          <ThemeProvider theme={theme}>
            <SidePanelHost
              open
              onClose={vi.fn()}
              title="Crate details"
              listLabel="Crates"
              list={<p>Crates</p>}
              above={above}
            >
              {content}
            </SidePanelHost>
          </ThemeProvider>,
        );
      });
      const panel = screen.getByRole("region", { name: "Crate details" });
      const heading = within(panel).getByRole("heading", {
        name: "Crate details",
      });
      const header = heading.closest("header")!;
      const fold = within(header).getByRole("button", {
        name: "Crate details",
        expanded: true,
      });
      const body = document.getElementById(
        fold.getAttribute("aria-controls")!,
      )!;

      expect(header.nextElementSibling).toBe(body);
      expect(body).toHaveTextContent("Crate notes");
      expect(getComputedStyle(heading).marginBottom).toBe("0px");
      expect(getComputedStyle(header).marginBottom).toBe(gap);
      const parts = body.querySelectorAll("[data-panel-part]");
      for (const part of parts) {
        expect(getComputedStyle(part).marginTop).toBe("18px");
        expect(getComputedStyle(part).paddingTop).toBe("18px");
        expect(getComputedStyle(part).borderTopWidth).toBe("1px");
        expect(getComputedStyle(part).borderTopStyle).toBe("solid");
      }
    },
  );

  it("moves the gap with the heading line as a row's kind appears and goes", async () => {
    choice("row", "expanded");
    laidOutAt(1400, 1920);
    const drawing = (besideHeading?: ReactNode) => (
      <ThemeProvider theme={theme}>
        <SidePanelHost
          open
          onClose={vi.fn()}
          title="Crate details"
          besideHeading={besideHeading}
          listLabel="Crates"
          list={<p>Crates</p>}
        >
          <input aria-label="Notes" />
        </SidePanelHost>
      </ThemeProvider>
    );
    const view = render(drawing());
    await act(async () => {});
    const heading = screen.getByRole("heading", { name: "Crate details" });
    const input = screen.getByRole("textbox", { name: "Notes" });
    const header = heading.closest("header")!;
    expect(getComputedStyle(header).marginBottom).toBe("20px");

    await act(async () => view.rerender(drawing(<span>Member</span>)));

    const kind = within(header).getByText("Member");
    const named = within(header).getByRole("heading", {
      name: "Crate details",
    });
    expect(named.nextElementSibling).toBe(kind);
    expect(getComputedStyle(named.parentElement!).marginBottom).toBe("20px");
    expect(getComputedStyle(header).marginBottom).toBe("0");
    expect(screen.getByRole("textbox", { name: "Notes" })).toBe(input);

    await act(async () => view.rerender(drawing()));

    expect(within(header).queryByText("Member")).toBeNull();
    expect(getComputedStyle(header).marginBottom).toBe("20px");
    expect(
      getComputedStyle(within(header).getByRole("heading")).marginBottom,
    ).toBe("0px");
    expect(screen.getByRole("textbox", { name: "Notes" })).toBe(input);
  });

  it("keeps a staying panel's first part sixteen pixels below its heading without an extra row gap", async () => {
    choice("about", "expanded");
    laidOutAt(1400, 1920);
    await act(async () => {
      render(
        <ThemeProvider theme={theme}>
          <SidePanelHost
            open
            title="Overview"
            keptAs="lilradish.panel.about"
            list={<p>The work</p>}
          >
            <p>Crate notes</p>
          </SidePanelHost>
        </ThemeProvider>,
      );
    });
    const heading = screen.getByRole("heading", { name: "Overview" });
    expect(getComputedStyle(heading).marginBottom).toBe("16px");
    expect(getComputedStyle(heading.closest("header")!).marginBottom).toBe("0");
    expect(screen.getByText("Crate notes")).toBeVisible();
  });

  it.each([
    ["a wide page", 1800, 1920],
    ["a narrow page", 700, 760],
  ])(
    "lays a row's drawer over %s, 560 wide, never beside the list or folded to a strip, under one scrim",
    async (_case, frame, viewport) => {
      laidOutAt(frame, viewport);
      const step = await stepOpened();

      expect(screen.queryByRole("region", { name: "Step 2" })).toBeNull();
      expect(
        screen.queryByRole("button", { name: "Step 2", hidden: true }),
      ).toBeNull();
      expect(getComputedStyle(step).width).toBe("560px");
      expect(getComputedStyle(step).maxWidth).toBe("100%");
      expect(scrims()).toHaveLength(1);
      expect(getComputedStyle(scrims()[0]!).backgroundColor).toBe(
        "rgba(25, 32, 36, 0.24)",
      );
    },
  );

  /** jsdom answers no media query but print and screen, so the rule written for a narrow page is read as written. */
  it("fills a page narrower than 640 with a row's drawer, and leaves it 560 wide on any page as wide", async () => {
    laidOutAt(560, 639);
    const step = await stepOpened();

    const root = styledClassOf(step.closest(".MuiDrawer-root")!);
    expect(writtenRules()).toContain(
      `@media (width < 640px){.${root} .MuiDrawer-paper{width:100%;}`,
    );
    expect(getComputedStyle(step).width).toBe("560px");
  });

  it("heads a row's drawer on one ruled-off line, its name in the step title type and its kind beside it, its body inset 24 on each side for the rules across it", async () => {
    laidOutAt(1800, 1920);
    const step = await stepOpened();

    const sheet = step.firstElementChild!;
    const [header, body] = [...sheet.children];
    const title = within(step).getByRole("heading", { level: 4 });
    expect(title).toHaveTextContent("Step 2");
    expect(title.parentElement!.parentElement).toBe(header);
    expect(within(header as HTMLElement).getByText("Route")).toBeVisible();
    const close = within(header as HTMLElement).getByRole("button", {
      name: "Close",
    });
    expect(header!.lastElementChild).toBe(close);
    expect([
      getComputedStyle(title).fontSize,
      getComputedStyle(title).lineHeight,
      getComputedStyle(title).fontWeight,
    ]).toEqual(["18px", "26px", "650"]);
    expect([
      getComputedStyle(header!).padding,
      getComputedStyle(header!).borderBottomStyle,
      getComputedStyle(header!).borderBottomColor,
    ]).toEqual(["18px 24px 14px", "solid", "rgb(228, 235, 238)"]);
    expect([
      getComputedStyle(close).width,
      getComputedStyle(close).height,
    ]).toEqual(["32px", "32px"]);
    expect([
      getComputedStyle(body!).padding,
      getComputedStyle(body!).getPropertyValue("--card-side-padding"),
    ]).toEqual(["18px 24px", "24px"]);
    expect([
      getComputedStyle(sheet).borderLeftStyle,
      getComputedStyle(sheet).borderLeftColor,
      getComputedStyle(sheet).boxShadow,
    ]).toEqual([
      "solid",
      "rgb(214, 224, 228)",
      "-8px 0 24px rgba(25, 32, 36, 0.10)",
    ]);
  });

  it("lets the row go on Escape and gives the keyboard back to the row that opened it", async () => {
    laidOutAt(1800, 1920);
    const step = await stepOpened();
    within(step).getByRole("button", { name: "Open summary" }).focus();

    await userEvent.keyboard("{Escape}");

    await waitFor(() =>
      expect(screen.queryByRole("dialog", { name: "Step 2" })).toBeNull(),
    );
    expect(screen.getByRole("button", { name: "Open step 2" })).toHaveFocus();
  });

  it.each([
    [1440, -480],
    [1224, -480],
    [1223, -480],
    [1200, -480],
    [1199, 0],
  ])(
    "stands a drawer opened in a row's drawer at the trailing edge of a %s-pixel page, the row's drawer moved %s pixels, never modal and under the one scrim",
    async (viewport, moved) => {
      laidOutAt(viewport - 200, viewport);
      const step = await stepOpened();
      const field = await fieldOpened(step);

      expect(field).toHaveAttribute("aria-modal", "false");
      expect(step).toContainElement(field);
      expect(step.firstElementChild).not.toContainElement(field);
      expect(scrims()).toHaveLength(1);
      expect(Number(movedBy(step))).toBe(moved);
      expect(getComputedStyle(step).overflowX).toBe("visible");
      expect([
        getComputedStyle(field).position,
        getComputedStyle(field).right,
        getComputedStyle(field).width,
      ]).toEqual(["absolute", "0px", moved === 0 ? "100%" : "480px"]);
      expect(
        within(step).getByRole("group", { name: "What it gives back" }),
      ).toBeVisible();
      expect(screen.queryByRole("region", { name: "Field 1 of 1" })).toBeNull();
    },
  );

  it("changes the two-drawer arrangement at the viewport boundary without remounting its editor or moving the keyboard", async () => {
    laidOutAt(560, 1199);
    const step = await stepOpened();
    const field = await fieldOpened(step);
    const label = within(field).getByRole("textbox", { name: "Label" });
    await act(() => label.focus());
    await userEvent.type(label, "Unfinished");
    expect(Number(movedBy(step))).toBe(0);
    expect(step.firstElementChild).toHaveAttribute("inert");

    vi.stubGlobal("innerWidth", 1200);
    await act(() => window.dispatchEvent(new Event("resize")));

    expect(screen.getByRole("dialog", { name: "Step 2" })).toBe(step);
    expect(screen.getByRole("dialog", { name: "Field 1 of 1" })).toBe(field);
    expect(within(field).getByRole("textbox", { name: "Label" })).toBe(label);
    expect(label).toHaveValue("Unfinished");
    expect(label).toHaveFocus();
    expect(Number(movedBy(step))).toBe(-480);
    expect(step.firstElementChild).not.toHaveAttribute("inert");
    expect(field).toHaveStyle({ width: "480px" });

    vi.stubGlobal("innerWidth", 1199);
    await act(() => window.dispatchEvent(new Event("resize")));

    expect(Number(movedBy(step))).toBe(0);
    expect(field).toHaveStyle({ width: "100%" });
    expect(step.firstElementChild).toHaveAttribute("inert");
    expect(label).toHaveFocus();
    expect(label).toHaveValue("Unfinished");
  });

  it("keeps a nested drawer's instructions in its body and its actions after the scrolling body", async () => {
    laidOutAt(1240, 1440);
    const step = await stepOpened();
    const field = await fieldOpened(step);
    const instructions = within(field).getByText("Field instructions");
    const body = instructions.parentElement!;
    const action = within(field).getByRole("button", { name: "Keep field" });
    const foot = action.parentElement!;

    expect(body).toContainElement(
      within(field).getByRole("textbox", { name: "Label" }),
    );
    expect(body).not.toContainElement(action);
    expect(body.nextElementSibling).toBe(foot);
    expect(getComputedStyle(body).overflowY).toBe("auto");
    expect(getComputedStyle(foot).flexShrink).toBe("0");
  });

  it.each([1800, 700])(
    "keeps a field's instructions in its body and actions outside its only scrolling body at %s pixels",
    async (width) => {
      laidOutAt(width, 1920);
      render(
        <ThemeProvider theme={theme}>
          <SidePanelHost
            open
            closeBeside
            width={480}
            onClose={vi.fn()}
            title="Field 1 of 1"
            list={<p>Fields</p>}
            above={<p>Field instructions</p>}
            foot={<button type="button">Keep field</button>}
          >
            <input aria-label="Label" />
          </SidePanelHost>
        </ThemeProvider>,
      );
      const field = await screen.findByRole(
        width === 1800 ? "region" : "dialog",
        {
          name: "Field 1 of 1",
        },
      );
      const body = within(field).getByText("Field instructions").parentElement!;
      const action = within(field).getByRole("button", { name: "Keep field" });
      const foot = action.parentElement!;

      expect(body).toContainElement(
        within(field).getByRole("textbox", { name: "Label" }),
      );
      expect(body).not.toContainElement(action);
      expect(body.nextElementSibling).toBe(foot);
      expect(getComputedStyle(body).overflowY).toBe("auto");
      expect(getComputedStyle(body).overflowX).toBe("hidden");
      expect(getComputedStyle(foot).flexShrink).toBe("0");
      expect(getComputedStyle(foot).padding).toBe("14px 24px");
      expect(writtenRules()).toContain(
        `@media (height < 400px){.${styledClassOf(body)}{overflow-y:visible;overflow-x:clip;}`,
      );
    },
  );

  it("slides a drawer opened in a row's drawer in from the trailing edge as the row's drawer moves aside, over the drawers' own time, and moves nothing where the reader asks for less motion", async () => {
    laidOutAt(1240, 1440);
    const step = await stepOpened();
    const field = await fieldOpened(step);

    expect(field.style.transition).toMatch(/^transform 225ms /);
    const sheet = styledClassOf(step.firstElementChild!);
    expect(writtenRules()).toMatch(
      new RegExp(`\\.${sheet}\\{[^}]*transition:translate 225ms `),
    );
    expect(writtenRules()).toMatch(
      new RegExp(
        `@media \\(prefers-reduced-motion: reduce\\)\\{\\.${sheet}\\{[^}]*(?<!-)transition:none;`,
      ),
    );

    await userEvent.keyboard("{Escape}");

    expect(field).toBeInTheDocument();
    expect(field.style.transition).toMatch(/^transform 195ms /);
    await waitFor(() => expect(field).not.toBeInTheDocument());
    expect(writtenRules()).toMatch(
      new RegExp(
        `\\.${styledClassOf(step.firstElementChild!)}\\{[^}]*transition:translate 195ms `,
      ),
    );
  });

  it.each([
    [1224, false],
    [1223, false],
    [1200, false],
    [1199, true],
  ])(
    "on a %s-pixel page puts the row's drawer out of reach only where a drawer opened in it lies over it (%s), the keyboard then going round that drawer alone",
    async (viewport, covered) => {
      laidOutAt(viewport - 200, viewport);
      const step = await stepOpened();
      const field = await fieldOpened(step);
      const sheet = step.firstElementChild!;
      within(field).getByRole("textbox", { name: "Label" }).focus();

      const reached = new Set<Element>();
      for (let press = 0; press < 6; press += 1) {
        await userEvent.tab();
        reached.add(document.activeElement!);
      }

      expect(sheet.hasAttribute("inert")).toBe(covered);
      expect(field).toHaveAttribute("aria-modal", "false");
      expect([...reached].some((each) => sheet.contains(each))).toBe(!covered);
      expect([...reached].some((each) => field.contains(each))).toBe(true);
    },
  );

  it("lets a row's drawer, and one opened in it, scroll whole on a screen too short for a fixed head and foot, and only their content otherwise", async () => {
    laidOutAt(1240, 1440);
    const step = await stepOpened();
    const field = await fieldOpened(step);
    const sheet = step.firstElementChild!;
    const body = sheet.children[1]!;

    for (const drawn of [sheet, field])
      expect(writtenRules()).toContain(
        `@media (height < 400px){.${styledClassOf(drawn)}{display:block;overflow-y:auto;overflow-x:hidden;}`,
      );
    expect(writtenRules()).toContain(
      `@media (height < 400px){.${styledClassOf(body)}{overflow-y:visible;overflow-x:clip;}`,
    );
    expect([
      getComputedStyle(sheet).display,
      getComputedStyle(field).display,
      getComputedStyle(body).overflowY,
    ]).toEqual(["flex", "flex", "auto"]);
  });

  it("gives the keyboard back, from a drawer opened in a row's drawer from outside it, to the control in the row's drawer for what it held, never to anything beneath", async () => {
    laidOutAt(1240, 1440);
    render(<LocatedFromThePage />);
    const locate = screen.getByRole("button", { name: "Locate summary" });
    await userEvent.click(locate);
    const field = await screen.findByRole("dialog", { name: "Field 1 of 1" });

    await userEvent.click(within(field).getByRole("button", { name: "Close" }));

    await waitFor(() => expect(field).not.toBeInTheDocument());
    expect(screen.getByRole("button", { name: "Open summary" })).toHaveFocus();
    expect(locate).not.toHaveFocus();
  });

  it("names a row's drawer by a heading the keyboard can be put on, and puts it there from anything inside the drawer", async () => {
    laidOutAt(1240, 1440);
    const step = await stepOpened();
    const title = within(step).getByRole("heading", { level: 4 });
    const inside = within(step).getByRole("button", { name: "Open summary" });

    focusDrawerHeading(inside);

    expect(title).toHaveAttribute("tabindex", "-1");
    expect(title).toHaveFocus();
    expect(inside).not.toHaveFocus();
  });

  describe("where the reader asks for less motion", () => {
    function askingForLessMotion() {
      const asked = vi.fn((query: string) => ({
        matches: query === "(prefers-reduced-motion: reduce)",
        media: query,
        addEventListener: () => {},
        removeEventListener: () => {},
      }));
      vi.stubGlobal("matchMedia", asked);
      return asked;
    }

    it("slides both drawers in over no time at all, read from the reader's own setting", async () => {
      const asked = askingForLessMotion();
      laidOutAt(1240, 1440);
      const step = await stepOpened();
      const field = await fieldOpened(step);

      expect(asked).toHaveBeenCalledWith("(prefers-reduced-motion: reduce)");
      expect(field.style.transition).toMatch(/^transform 0ms /);
      expect(
        [...document.querySelectorAll<HTMLElement>(".MuiDrawer-paper")].map(
          (paper) => paper.style.transition.split(" ").slice(0, 2).join(" "),
        ),
      ).toEqual(["transform 0ms"]);
    });

    it("lets each drawer go on Escape, the one on top first, giving the keyboard back to the row that opened each and leaving no dialog or scrim behind", async () => {
      askingForLessMotion();
      laidOutAt(1240, 1440);
      const step = await stepOpened();
      const field = await fieldOpened(step);
      within(field).getByRole("textbox", { name: "Label" }).focus();

      await userEvent.keyboard("{Escape}");

      await waitFor(() => expect(field).not.toBeInTheDocument());
      expect(screen.getByRole("dialog", { name: "Step 2" })).toBe(step);
      expect(
        within(step).getByRole("button", { name: "Open summary" }),
      ).toHaveFocus();
      expect(scrims()).toHaveLength(1);

      await userEvent.keyboard("{Escape}");

      await waitFor(() => expect(step).not.toBeInTheDocument());
      expect(screen.getByRole("button", { name: "Open step 2" })).toHaveFocus();
      expect(screen.queryAllByRole("dialog", { hidden: true })).toEqual([]);
      expect(scrims()).toEqual([]);
    });
  });

  it("shuts only the drawer on top at a press on the scrim, the row's drawer staying and the keyboard going back to the row that opened it", async () => {
    laidOutAt(1240, 1440);
    const step = await stepOpened();
    const field = await fieldOpened(step);

    await userEvent.click(scrims()[0]!);

    await waitFor(() => expect(field).not.toBeInTheDocument());
    expect(screen.getByRole("dialog", { name: "Step 2" })).toBe(step);
    expect(Number(movedBy(step))).toBe(0);
    expect(
      within(step).getByRole("button", { name: "Open summary" }),
    ).toHaveFocus();
    expect(scrims()).toHaveLength(1);
  });

  it("fills the row's drawer with the drawer opened inside it when the two cannot stand beside each other", async () => {
    laidOutAt(560, 600);
    const step = await stepOpened();
    const field = await fieldOpened(step);
    expect(writtenRules()).toMatch(
      new RegExp(
        `@media \\(width < 640px\\)\\{\\.${styledClassOf(field)}\\{width:100%;\\}`,
      ),
    );
    expect(getComputedStyle(field).width).toBe("100%");
    expect(Number(movedBy(step))).toBe(0);
  });

  it.each([
    ["from the drawer on top", "Label"],
    ["from the row's drawer beneath it", "Open summary"],
  ])(
    "shuts only the drawer on top at each Escape %s, the row's drawer moving back and the keyboard going back to the row that opened each",
    async (_case, from) => {
      laidOutAt(1240, 1440);
      const step = await stepOpened();
      const field = await fieldOpened(step);
      expect(Number(movedBy(step))).toBe(-480);
      screen
        .getByRole(from === "Label" ? "textbox" : "button", {
          name: from,
        })
        .focus();

      await userEvent.keyboard("{Escape}");

      await waitFor(() => expect(field).not.toBeInTheDocument());
      expect(screen.getByRole("dialog", { name: "Step 2" })).toBe(step);
      expect(Number(movedBy(step))).toBe(0);
      expect(
        within(step).getByRole("button", { name: "Open summary" }),
      ).toHaveFocus();
      expect(scrims()).toHaveLength(1);

      await userEvent.keyboard("{Escape}");

      await waitFor(() =>
        expect(screen.queryByRole("dialog", { name: "Step 2" })).toBeNull(),
      );
      expect(screen.getByRole("button", { name: "Open step 2" })).toHaveFocus();
    },
  );

  it.each(["expanded", "collapsed"])(
    "keeps the page panel folded through a row drawer's closing transition and restores the device's %s choice afterwards",
    async (kept) => {
      choice("about", kept);
      pageLaidOutAt(1300, 1440);
      render(
        <ThemeProvider theme={theme}>
          <SidePanelHost
            open
            title="Details"
            keptAs="lilradish.panel.about"
            list={<RowsOverThePage />}
          >
            <input aria-label="Page notes" defaultValue="Kept notes" />
          </SidePanelHost>
        </ThemeProvider>,
      );
      const panel = await screen.findByRole("region", { name: "Details" });
      const notes = document.querySelector<HTMLInputElement>(
        '[aria-label="Page notes"]',
      )!;
      await userEvent.click(
        screen.getByRole("button", { name: "Open step 2" }),
      );
      const step = await screen.findByRole("dialog", { name: "Step 2" });
      const toggle = within(panel).getByRole("button", {
        name: "Details",
        hidden: true,
      });
      expect(toggle).toBeDisabled();
      expect(toggle).toHaveAttribute("aria-expanded", "false");
      expect(screen.queryByRole("dialog", { name: "Details" })).toBeNull();
      expect(localStorage.getItem("lilradish.panel.about")).toBe(kept);
      await userEvent.click(
        within(step).getByRole("button", { name: "Close" }),
      );
      expect(toggle).toBeDisabled();
      await waitFor(() => expect(step).not.toBeInTheDocument());
      const restored = within(panel).getByRole("button", { name: "Details" });
      expect(restored).not.toBeDisabled();
      expect(restored).toHaveAttribute(
        "aria-expanded",
        String(kept === "expanded"),
      );
      expect(localStorage.getItem("lilradish.panel.about")).toBe(kept);
      expect(document.querySelector('[aria-label="Page notes"]')).toBe(notes);
      expect(notes).toHaveValue("Kept notes");
    },
  );

  it.each(["reveal", "insist"] as const)(
    "defers a page panel's first %s request until both row drawers have left, without losing the request or changing the stored choice",
    async (kind) => {
      choice("about", "collapsed");
      pageLaidOutAt(700, 700);
      const focus = vi.fn(() =>
        document
          .querySelector<HTMLInputElement>('[aria-label="Page notes"]')
          ?.focus(),
      );
      const pageRequest = { focus };
      function Page({ requested }: { readonly requested: boolean }) {
        const request = requested ? pageRequest : undefined;
        return (
          <ThemeProvider theme={theme}>
            <SidePanelHost
              open
              title="Details"
              keptAs="lilradish.panel.about"
              reveal={kind === "reveal" ? request : undefined}
              insist={kind === "insist" ? request : undefined}
              list={<RowsOverThePage />}
            >
              <input aria-label="Page notes" defaultValue="Kept notes" />
            </SidePanelHost>
          </ThemeProvider>
        );
      }
      const { rerender } = render(<Page requested={false} />);
      const toggle = await screen.findByRole("button", { name: "Details" });
      await userEvent.click(
        screen.getByRole("button", { name: "Open step 2" }),
      );
      const step = await screen.findByRole("dialog", { name: "Step 2" });
      const field = await fieldOpened(step);

      rerender(<Page requested />);
      await act(() => toggle.click());

      expect(toggle).toBeDisabled();
      expect(toggle).toHaveAttribute("aria-expanded", "false");
      expect(screen.queryByRole("dialog", { name: "Details" })).toBeNull();
      expect(focus).not.toHaveBeenCalled();
      await userEvent.click(
        within(field).getByRole("button", { name: "Close" }),
      );
      await waitFor(() => expect(field).not.toBeInTheDocument());
      expect(toggle).toBeDisabled();
      expect(focus).not.toHaveBeenCalled();
      await userEvent.click(
        within(step).getByRole("button", { name: "Close" }),
      );
      expect(toggle).toBeDisabled();
      await waitFor(() => expect(step).not.toBeInTheDocument());
      const details = await screen.findByRole("dialog", { name: "Details" });
      await waitFor(() => expect(focus).toHaveBeenCalledOnce());
      expect(
        within(details).getByRole("textbox", { name: "Page notes" }),
      ).toHaveFocus();
      expect(localStorage.getItem("lilradish.panel.about")).toBe("collapsed");
    },
  );

  it("opens a drawer beside a list that a folding panel makes room for without first drawing it over the page", async () => {
    choice("about", "expanded");
    pageLaidOutAt(1300, 1440);
    function Page() {
      const [open, setOpen] = useState(false);
      return (
        <ThemeProvider theme={theme}>
          <SidePanelHost
            open
            title="Details"
            keptAs="lilradish.panel.about"
            list={
              <SidePanelHost
                asksRoom
                closeBeside
                open={open}
                onClose={() => setOpen(false)}
                width={480}
                title="Field 1 of 1"
                listLabel="What it takes"
                listRole="group"
                list={
                  <button type="button" onClick={() => setOpen(true)}>
                    Open summary
                  </button>
                }
              >
                <input aria-label="Label" />
              </SidePanelHost>
            }
          >
            <p>Facts</p>
          </SidePanelHost>
        </ThemeProvider>
      );
    }
    render(<Page />);
    await screen.findByRole("region", { name: "Details" });
    const covered: string[] = [];
    const watching = new MutationObserver(() => {
      if (document.querySelector('[role="dialog"]') !== null)
        covered.push("dialog");
    });
    watching.observe(document.body, { childList: true, subtree: true });
    onTestFinished(() => watching.disconnect());

    await userEvent.click(screen.getByRole("button", { name: "Open summary" }));

    expect(
      await screen.findByRole("region", { name: "Field 1 of 1" }),
    ).toBeVisible();
    expect(covered).toEqual([]);
    expect(scrims()).toEqual([]);
  });

  it.each([
    ["a drawer that shuts beside its list", true, "18px 24px", "24px"],
    ["a row's panel", false, "18px 22px", "22px"],
  ])(
    "insets %s standing beside a wide list as it is drawn",
    async (_case, closeBeside, padding, sides) => {
      laidOutAt(1800, 1920);
      render(
        <ThemeProvider theme={theme}>
          <SidePanelHost
            open
            closeBeside={closeBeside}
            onClose={vi.fn()}
            width={480}
            title="Field 1 of 1"
            listLabel="What it takes"
            listRole="group"
            list={<p>Fields</p>}
          >
            <input aria-label="Label" />
          </SidePanelHost>
        </ThemeProvider>,
      );
      const beside = getComputedStyle(
        await screen.findByRole("region", { name: "Field 1 of 1" }),
      );
      expect([
        beside.padding,
        beside.getPropertyValue("--card-side-padding"),
      ]).toEqual([padding, sides]);
    },
  );

  it("leaves a drawer opened outside any row's drawer as it was, beside its list where there is room", async () => {
    laidOutAt(1800, 1920);
    render(
      <ThemeProvider theme={theme}>
        <SidePanelHost
          open
          closeBeside
          onClose={vi.fn()}
          width={480}
          title="Field 1 of 1"
          listLabel="What it takes"
          listRole="group"
          list={<p>Fields</p>}
        >
          <input aria-label="Label" />
        </SidePanelHost>
      </ThemeProvider>,
    );
    expect(
      await screen.findByRole("region", { name: "Field 1 of 1" }),
    ).toBeVisible();
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(scrims()).toEqual([]);
  });

  it("disconnects the actual observer when placement inputs change and when the host leaves", async () => {
    choice("row", "expanded");
    const resize = laidOutAt(1030, 1920);
    const observe = vi.spyOn(ResizeObserver.prototype, "observe");
    const disconnect = vi.spyOn(ResizeObserver.prototype, "disconnect");
    const deliver = vi.spyOn(
      ResizeObserver.prototype as ResizeObserver & { deliver(): void },
      "deliver",
    );
    const drawing = (width: number, leastListWidth: number) => (
      <ThemeProvider theme={theme}>
        <SidePanelHost
          open
          title="Crate details"
          onClose={vi.fn()}
          width={width}
          leastListWidth={leastListWidth}
          listLabel="Crates"
          list={<p>Crates</p>}
        >
          <input aria-label="Notes" />
        </SidePanelHost>
      </ThemeProvider>
    );
    const view = render(drawing(360, 560));
    await screen.findByRole("region", { name: "Crate details" });
    const frame = view.container.firstElementChild!;
    const first =
      observe.mock.contexts[
        observe.mock.calls.findIndex(([target]) => target === frame)
      ];
    view.rerender(drawing(500, 560));
    await screen.findByRole("dialog", { name: "Crate details" });
    expect(disconnect.mock.contexts).toContain(first);
    const second = observe.mock.contexts.at(-1);
    expect(second).not.toBe(first);
    view.rerender(drawing(500, 1000));
    expect(disconnect.mock.contexts).toContain(second);
    const current = observe.mock.contexts.at(-1);
    expect(current).not.toBe(second);
    await resize(1400);
    expect(screen.getByRole("dialog", { name: "Crate details" })).toBeVisible();
    await resize(1600);
    await screen.findByRole("region", { name: "Crate details" });
    view.unmount();
    expect(disconnect.mock.contexts).toContain(current);
    deliver.mockClear();
    await resize(700);
    expect(deliver).not.toHaveBeenCalled();
  });
  it("lays a fresh row's panel over its own narrow box, inside a wide window, open and with no strip, moving the same editor beside once wide", async () => {
    choice("row", null);
    const resize = laidOutAt(1023, 1920);
    render(
      <ThemeProvider theme={theme}>
        <SidePanelHost
          open
          title="Crate details"
          onClose={vi.fn()}
          listLabel="Crates"
          list={<p>Crates</p>}
        >
          <input aria-label="Notes" defaultValue="Initial" />
        </SidePanelHost>
      </ThemeProvider>,
    );
    const dialog = await screen.findByRole("dialog", { name: "Crate details" });
    expect(
      screen.queryAllByRole("button", { name: "Crate details", hidden: true }),
    ).toEqual([]);
    expect(within(dialog).getByRole("button", { name: "Close" })).toBeVisible();
    const notes = within(dialog).getByRole("textbox", { name: "Notes" });
    await userEvent.type(notes, " draft");
    (notes as HTMLInputElement).setSelectionRange(2, 6);
    await resize(1024);
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    const beside = screen.getByRole("region", { name: "Crate details" });
    expect(within(beside).getByRole("textbox", { name: "Notes" })).toBe(notes);
    expect(notes).toHaveValue("Initial draft");
    expect(notes).toHaveFocus();
    expect([
      (notes as HTMLInputElement).selectionStart,
      (notes as HTMLInputElement).selectionEnd,
    ]).toEqual([2, 6]);
    expect(within(beside).queryByRole("button", { name: "Close" })).toBeNull();
    expect(
      within(beside).getByRole("button", { name: "Crate details" }),
    ).toHaveAttribute("aria-expanded", "true");
  });

  it("stands a covering panel beside a wide list only with a scrollbar's room to spare, and keeps it there down to the bare room", async () => {
    choice("row", "expanded");
    const resize = laidOutAt(1407, 1920);
    render(
      <ThemeProvider theme={theme}>
        <SidePanelHost
          open
          title="Crate details"
          onClose={vi.fn()}
          leastListWidth={1000}
          listLabel="Crates"
          list={<p>Crates</p>}
        >
          <p>Details</p>
        </SidePanelHost>
      </ThemeProvider>,
    );
    await screen.findByRole("dialog", { name: "Crate details" });
    expect(screen.queryByRole("region", { name: "Crate details" })).toBeNull();

    await resize(1408);
    await screen.findByRole("region", { name: "Crate details" });
    await resize(1384);
    expect(screen.getByRole("region", { name: "Crate details" })).toBeVisible();
    expect(screen.queryByRole("dialog")).toBeNull();

    await resize(1383);
    await screen.findByRole("dialog", { name: "Crate details" });
    expect(screen.queryByRole("region", { name: "Crate details" })).toBeNull();
  });

  it.each(["collapsed", "expanded"] as const)(
    "gives a kept device choice %s priority beside a wide list",
    async (kept) => {
      choice("row", kept);
      laidOutAt(1200, 1920);
      render(
        <ThemeProvider theme={theme}>
          <SidePanelHost
            open
            title="Crate details"
            onClose={vi.fn()}
            listLabel="Crates"
            list={<p>Crates</p>}
          >
            <p>Details</p>
          </SidePanelHost>
        </ThemeProvider>,
      );
      expect(
        await screen.findByRole("button", { name: "Crate details" }),
      ).toHaveAttribute("aria-expanded", String(kept === "expanded"));
      expect(screen.queryByRole("dialog")).toBeNull();
    },
  );

  it.each(["collapsed", "expanded", null] as const)(
    "lays a row's panel over a narrow list open whatever the device kept (%s), with no fold",
    async (kept) => {
      choice("row", kept);
      laidOutAt(700, 1920);
      render(
        <ThemeProvider theme={theme}>
          <SidePanelHost
            open
            title="Crate details"
            onClose={vi.fn()}
            listLabel="Crates"
            list={<p>Crates</p>}
          >
            <p>Details</p>
          </SidePanelHost>
        </ThemeProvider>,
      );
      const dialog = await screen.findByRole("dialog", {
        name: "Crate details",
      });
      expect(within(dialog).getByText("Details")).toBeVisible();
      expect(
        screen.queryAllByRole("button", {
          name: "Crate details",
          hidden: true,
        }),
      ).toEqual([]);
      expect(localStorage.getItem("lilradish.panel.row")).toBe(kept);
    },
  );

  it.each(["Close", "Escape", "backdrop"] as const)(
    "lets a narrow row go by %s, the panel gone with no strip and the keyboard back on the row",
    async (shutting) => {
      choice("row", null);
      laidOutAt(700, 1920);
      function Rows() {
        const [picked, setPicked] = useState(false);
        return (
          <ThemeProvider theme={theme}>
            <SidePanelHost
              open={picked}
              title="Crate details"
              onClose={() => setPicked(false)}
              listLabel="Crates"
              list={
                <button type="button" onClick={() => setPicked(true)}>
                  Crate 2
                </button>
              }
            >
              <p>Details</p>
            </SidePanelHost>
          </ThemeProvider>
        );
      }
      render(<Rows />);
      const crate = await screen.findByRole("button", { name: "Crate 2" });
      await userEvent.click(crate);
      const dialog = await screen.findByRole("dialog", {
        name: "Crate details",
      });

      if (shutting === "Close")
        await userEvent.click(
          within(dialog).getByRole("button", { name: "Close" }),
        );
      else if (shutting === "Escape") await userEvent.keyboard("{Escape}");
      else
        await userEvent.click(
          document.querySelector<HTMLElement>(".MuiBackdrop-root")!,
        );

      await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
      expect(screen.queryByText("Details")).toBeNull();
      expect(
        screen.queryAllByRole("button", {
          name: "Crate details",
          hidden: true,
        }),
      ).toEqual([]);
      await waitFor(() => expect(crate).toHaveFocus());
    },
  );

  it("puts the keyboard on a selection's editor over a narrow list", async () => {
    choice("row", "collapsed");
    laidOutAt(700, 1920);
    const view = render(revealing(undefined));
    screen.getByRole("button", { name: "Crate 2" }).focus();
    view.rerender(revealing(CRATE_NAME));
    const panel = await screen.findByRole("dialog", { name: "Crate details" });
    const name = within(panel).getByRole("textbox", { name: "Crate name" });
    await waitFor(() => expect(name).toHaveFocus());
    expect(within(panel).getByRole("textbox", { name: "Notes" })).not.toBe(
      document.activeElement,
    );
    expect(localStorage.getItem("lilradish.panel.row")).toBe("collapsed");
  });

  it.each(["expanded", "collapsed"] as const)(
    "puts the keyboard on a selection's editor beside a wide list with the panel kept %s, keeping the device's choice",
    async (kept) => {
      choice("row", kept);
      laidOutAt(1400, 1920);
      const view = render(revealing(undefined));
      screen.getByRole("button", { name: "Crate 2" }).focus();
      view.rerender(revealing(CRATE_NAME));
      const panel = await screen.findByRole("region", {
        name: "Crate details",
      });
      const name = within(panel).getByRole("textbox", { name: "Crate name" });
      await waitFor(() => expect(name).toHaveFocus());
      expect(within(panel).getByRole("textbox", { name: "Notes" })).not.toBe(
        document.activeElement,
      );
      expect(
        within(panel).getByRole("button", { name: "Crate details" }),
      ).toHaveAttribute("aria-expanded", "true");
      expect(localStorage.getItem("lilradish.panel.row")).toBe(kept);
    },
  );

  it("leaves the keyboard where the reader moved it before a selection's covering form arrived", async () => {
    choice("row", "expanded");
    laidOutAt(700, 1920);
    const view = render(revealing(undefined));
    const crate = screen.getByRole("button", { name: "Crate 2" });
    crate.focus();
    view.rerender(revealing(CRATE_NAME));
    const close = screen.getByRole("button", { name: "Close" });
    close.focus();

    await act(async () => {});

    const panel = screen.getByRole("dialog", { name: "Crate details" });
    expect(close).toHaveFocus();
    expect(
      within(panel).getByRole("textbox", { name: "Crate name" }),
    ).not.toHaveFocus();
  });

  it("reveals a panel the reader folded again only when asked anew", async () => {
    choice("row", "collapsed");
    laidOutAt(1400, 1920);
    const view = render(revealing(CRATE_NAME));
    const panel = await screen.findByRole("region", { name: "Crate details" });
    const name = within(panel).getByRole("textbox", { name: "Crate name" });
    await waitFor(() => expect(name).toHaveFocus());
    const fold = within(panel).getByRole("button", { name: "Crate details" });

    await userEvent.click(fold);
    view.rerender(revealing(CRATE_NAME));
    await act(async () => {});
    expect(fold).toHaveAttribute("aria-expanded", "false");
    expect(fold).toHaveFocus();

    view.rerender(revealing({ focus: CRATE_NAME.focus }));
    await waitFor(() => expect(name).toHaveFocus());
    expect(fold).toHaveAttribute("aria-expanded", "true");
    expect(localStorage.getItem("lilradish.panel.row")).toBe("collapsed");
  });

  it.each([[1400, "region"]] as const)(
    "unfolds a folded panel once at %s pixels for what it must not hide, without keeping that as the device's choice",
    async (width, role) => {
      choice("row", "collapsed");
      laidOutAt(width, 1920);
      const view = render(<Refusing refusal={null} />);
      const arrow = screen.getByRole("button", {
        name: "Crate details",
        expanded: false,
      });
      expect(screen.queryByRole("textbox", { name: "Notes" })).toBeNull();

      view.rerender(<Refusing refusal={SAVED_SINCE} />);
      const panel = await screen.findByRole(role, { name: "Crate details" });
      const refusal = within(panel).getByRole("alert");
      await waitFor(() => expect(refusal).toHaveFocus());
      expect(refusal).toBeVisible();
      expect(
        within(panel).getByRole("textbox", { name: "Notes" }),
      ).toBeVisible();
      expect(arrow).toHaveAttribute("aria-expanded", "true");
      expect(localStorage.getItem("lilradish.panel.row")).toBe("collapsed");

      await userEvent.click(
        within(panel).getByRole("button", { name: "Crate details" }),
      );
      view.rerender(<Refusing refusal={SAVED_SINCE} />);
      await act(() => Promise.resolve());
      await waitFor(() =>
        expect(screen.queryByRole("textbox", { name: "Notes" })).toBeNull(),
      );
      expect(screen.queryByRole("alert")).toBeNull();
      expect(arrow).toHaveAttribute("aria-expanded", "false");
      expect(arrow).toHaveFocus();
      expect(localStorage.getItem("lilradish.panel.row")).toBe("collapsed");

      view.rerender(<Refusing refusal={{ focus: SAVED_SINCE.focus }} />);
      await waitFor(() => expect(screen.getByRole("alert")).toHaveFocus());
      expect(arrow).toHaveAttribute("aria-expanded", "true");
      expect(localStorage.getItem("lilradish.panel.row")).toBe("collapsed");
    },
  );

  it("leaves the keyboard where it was when what must not be hidden arrives in an unfolded panel", async () => {
    choice("row", "expanded");
    laidOutAt(1400, 1920);
    const view = render(<Refusing refusal={null} />);
    const panel = await screen.findByRole("region", { name: "Crate details" });
    const notes = within(panel).getByRole("textbox", { name: "Notes" });
    notes.focus();

    view.rerender(<Refusing refusal={SAVED_SINCE} />);
    const refusal = within(panel).getByRole("alert");
    await act(() => Promise.resolve());
    expect(refusal).toBeVisible();
    expect(notes).toHaveFocus();
    expect(refusal).not.toHaveFocus();
    expect(localStorage.getItem("lilradish.panel.row")).toBe("expanded");
  });

  it("folds a staying panel, first on a narrow box, to a strip across before the work, and keeps what it holds through opening it over the page, shutting it and opening it again", async () => {
    choice("about", null);
    laidOutAt(700, 600);
    const mounts = vi.fn();
    function Notes() {
      useLayoutEffect(() => {
        mounts();
      }, []);
      return <input aria-label="Notes" defaultValue="Initial" />;
    }
    render(
      <ThemeProvider theme={theme}>
        <SidePanelHost
          open
          title="About it"
          keptAs="lilradish.panel.about"
          leastListWidth={600}
          list={<p>The work</p>}
        >
          <Notes />
        </SidePanelHost>
      </ThemeProvider>,
    );
    const work = screen.getByText("The work");
    const strip = await screen.findByRole("button", {
      name: "About it",
      expanded: false,
    });
    expect(
      strip.compareDocumentPosition(work) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
    expect(strip).not.toContainElement(work);
    expect(screen.queryByRole("textbox", { name: "Notes" })).toBeNull();
    expect(screen.queryByRole("dialog")).toBeNull();

    await userEvent.click(strip);
    const dialog = await screen.findByRole("dialog", { name: "About it" });
    const notes = within(dialog).getByRole("textbox", { name: "Notes" });
    expect(document.getElementById(strip.getAttribute("aria-controls")!)).toBe(
      dialog,
    );
    expect(work.closest('[aria-hidden="true"]')).not.toBeNull();
    await userEvent.type(notes, " draft");
    await userEvent.click(
      within(dialog).getByRole("button", { name: "Close" }),
    );
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(strip).toHaveAttribute("aria-expanded", "false");
    expect(strip).toHaveFocus();
    await userEvent.click(strip);

    const again = await screen.findByRole("dialog", { name: "About it" });
    expect(within(again).getByRole("textbox", { name: "Notes" })).toBe(notes);
    expect(notes).toHaveValue("Initial draft");
    expect(mounts).toHaveBeenCalledOnce();
    expect(localStorage.getItem("lilradish.panel.about")).toBe("expanded");
    expect(writtenRules()).toContain("container-type:inline-size;");
  });
});
