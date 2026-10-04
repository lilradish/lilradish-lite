import Box from "@mui/material/Box";
import { ThemeProvider } from "@mui/material/styles";
import { render, screen, within } from "@testing-library/react";
import type { ReactNode } from "react";
import { describe, expect, it } from "vitest";

import { BLOCK_SX, HEADING_SX } from "../../../lib/layout/parts";
import { theme } from "../../../lib/theme/theme";
import { drawnOf } from "../../../testutil/depths";
import { NO_GROUND, resolved } from "../../../testutil/grounds";
import { Message } from "./Message";

/** The room under a heading as `parts.ts` writes it, in pixels as a style reports it back. */
const UNDER_A_HEADING = theme.spacing(HEADING_SX.mb);

/** Each kind's colour as `theme.ts` writes it, copied by hand so a path nothing resolves shows. */
const KIND_COLOURS = [
  ["question", "#00695c"],
  ["workflow", "#283593"],
  ["code_step", "#37474f"],
  ["route", "#5d4037"],
] as const;

function themed({ children }: { readonly children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

function drawn(message: ReactNode) {
  render(<ol>{message}</ol>, { wrapper: themed });
  return screen.getByRole("listitem");
}

function drawnAsBlock() {
  const probe = render(
    <ThemeProvider theme={theme}>
      <Box sx={BLOCK_SX} />
    </ThemeProvider>,
  );
  const drawn = drawnOf(probe.container.firstElementChild!);
  probe.unmount();
  return drawn;
}

describe("Message", () => {
  it.each([
    ["an anchor finds it", "step-1", "-1"],
    ["nothing finds it", undefined, null],
  ] as const)(
    "is an item of the conversation named by its title, taking the keyboard only where %s",
    (_case, anchor, tabIndex) => {
      const item = drawn(
        <Message anchor={anchor} title="Summarise">
          <p>Running</p>
        </Message>,
      );

      expect(item.tagName).toBe("LI");
      expect(item).toHaveAccessibleName("Summarise");
      expect(item.getAttribute("id")).toBe(anchor ?? null);
      expect(item.getAttribute("tabindex")).toBe(tabIndex);
      expect(getComputedStyle(item).scrollMarginTop).toBe(
        anchor === undefined ? "0" : "64px",
      );
    },
  );

  it("says the kind a step runs beside its title on one line above the rest, no part of the title", () => {
    const item = drawn(
      <Message title="Summarise" kind="question">
        <p>Running</p>
      </Message>,
    );

    const title = within(item).getByRole("heading", { level: 2 });
    const head = title.parentElement!;

    expect(head.parentElement).toBe(item);
    expect(head.firstElementChild).toBe(title);
    expect(title.nextElementSibling?.textContent).toBe("Question");
    expect(head.children).toHaveLength(2);
    expect(title).toHaveAccessibleName("Summarise");
    expect(getComputedStyle(head).display).toBe("flex");
    expect(getComputedStyle(head).alignItems).toBe("center");
    expect(getComputedStyle(head).marginBottom).toBe(UNDER_A_HEADING);
    expect(head.nextElementSibling?.textContent).toBe("Running");
    expect(head).not.toHaveTextContent("Running");
  });

  it("says what a step cost on the trailing side of its head, after the kind and no part of the title", () => {
    const item = drawn(
      <Message
        title="Summarise"
        kind="question"
        cost={{
          spend: {
            sent: "1200",
            cameBack: "300",
            spent: "1500",
            cameBackUnknown: false,
          },
        }}
      >
        <p>Running</p>
      </Message>,
    );

    const title = within(item).getByRole("heading", { level: 2 });
    const cost = title.parentElement!.lastElementChild!;

    expect(cost.textContent).toBe("1,500 in all: 1,200 sent, 300 came back");
    expect(cost.previousElementSibling?.textContent).toBe("Question");
    expect(getComputedStyle(cost).marginLeft).toBe("auto");
    expect(title).toHaveAccessibleName("Summarise");
    expect(item).toHaveAccessibleName("Summarise");
    expect(title.parentElement!.nextElementSibling?.textContent).toBe(
      "Running",
    );
  });

  it("says nothing beside the title of a message that is no step's", () => {
    const item = drawn(
      <Message title="What it was started with">
        <p>Avo Lumerace started it</p>
      </Message>,
    );

    const title = within(item).getByRole("heading", { level: 2 });

    expect(title.parentElement!.children).toHaveLength(1);
    expect(getComputedStyle(title.parentElement!).marginBottom).toBe(
      UNDER_A_HEADING,
    );
  });

  it("heads a message at the second level, as a part of the run's page", () => {
    const item = drawn(
      <Message title="Summarise" kind="question">
        <p>Running</p>
      </Message>,
    );

    expect(within(item).getAllByRole("heading")).toEqual([
      within(item).getByRole("heading", { level: 2, name: "Summarise" }),
    ]);
    expect(within(item).queryByRole("heading", { level: 3 })).toBeNull();
  });

  it("sets a message the reader made apart on the trailing side, narrower than the rest and on its own ground, its frame and edge as any other's", () => {
    const item = drawn(
      <Message title="What it was started with" byTheReader={true}>
        <p>Avo Lumerace started it, running the workflow Handle a claim.</p>
      </Message>,
    );

    const drawnAs = getComputedStyle(item);

    expect(drawnAs.marginInlineStart).toBe("auto");
    expect(drawnAs.maxWidth).toBe("85%");
    expect(drawnAs.backgroundColor).toBe(resolved(theme.palette.part.mine));
    expect({ ...drawnOf(item), ground: NO_GROUND }).toEqual(drawnAsBlock());
    expect(item).toHaveAccessibleName("What it was started with");
  });

  it.each([
    ["no reader is said to have made it", undefined],
    ["the reader did not make it", false],
  ] as const)(
    "leaves a message on the leading side, as wide as the conversation and on no ground of its own, where %s",
    (_case, byTheReader) => {
      const item = drawn(
        <Message title="What it was started with" byTheReader={byTheReader}>
          <p>Glinn Havven started it, running the workflow Handle a claim.</p>
        </Message>,
      );

      const drawnAs = getComputedStyle(item);

      expect(drawnAs.marginInlineStart).not.toBe("auto");
      expect(drawnAs.maxWidth).not.toBe("85%");
      expect(drawnAs.backgroundColor).not.toBe(
        resolved(theme.palette.part.mine),
      );
      expect(drawnOf(item)).toEqual(drawnAsBlock());
    },
  );

  /** Colours as `theme.ts` writes them, copied by hand so a path nothing resolves shows. */
  it.each([
    ["done", "#1b5e20"],
    ["failed", "#8c1d18"],
    ["stopped", "#595959"],
  ] as const)(
    "edges the run's last message, ended %s, all round and thicker than any other, in that ending's colour",
    (ending, colour) => {
      const item = drawn(
        <Message title="Done" ending={ending}>
          <p>What it gave back</p>
        </Message>,
      );

      const drawnAs = getComputedStyle(item);
      const other = drawnAsBlock();
      const sides = ["Top", "Right", "Bottom", "Left"] as const;

      expect(sides.map((side) => drawnAs[`border${side}Width`])).toEqual(
        Array(4).fill("2px"),
      );
      expect(sides.map((side) => drawnAs[`border${side}Color`])).toEqual(
        Array(4).fill(resolved(colour)),
      );
      expect(drawnOf(item).topEdge).not.toBe(other.topEdge);
      expect(drawnOf(item).leadingEdgeColour).not.toBe(other.leadingEdgeColour);
      expect(item).toHaveAccessibleName("Done");
    },
  );

  it("frames a message that is no step's as a block of the page, with no edge of its own", () => {
    const item = drawn(
      <Message title="What it was started with">
        <p>Avo Lumerace started it</p>
      </Message>,
    );

    expect(drawnOf(item)).toEqual(drawnAsBlock());
  });

  it.each(KIND_COLOURS)(
    "rules a message of a step that runs %s down its leading edge in that kind's colour and in no other's, framed otherwise as a block",
    (kind, colour) => {
      const item = drawn(
        <Message title="Summarise" kind={kind}>
          <p>Running</p>
        </Message>,
      );

      const others = KIND_COLOURS.filter(([other]) => other !== kind).map(
        ([, other]) => resolved(other),
      );

      expect(drawnOf(item)).toEqual({
        ...drawnAsBlock(),
        leadingEdge: "3px solid",
        leadingEdgeColour: resolved(colour),
      });
      expect(others).not.toContain(drawnOf(item).leadingEdgeColour);
    },
  );

  /** Colour never says a kind alone: one this build has no words for is said so, and nothing is ruled in its name. */
  it("frames a message of a step running a kind it has no words for as a block, saying so beside the title", () => {
    const item = drawn(
      <Message title="Find it" kind="unmapped_kind">
        <p>Running</p>
      </Message>,
    );

    const title = within(item).getByRole("heading", { level: 2 });

    expect(drawnOf(item)).toEqual(drawnAsBlock());
    expect(title.nextSibling?.textContent).toBe(
      "Not something this page can say yet.",
    );
    expect(item).not.toHaveTextContent(/unmapped_kind/i);
  });
});
