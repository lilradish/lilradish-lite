import { ThemeProvider } from "@mui/material/styles";
import { render, screen, within } from "@testing-library/react";
import { createRef, type ReactNode } from "react";
import { describe, expect, it } from "vitest";

import { drawnAtDepth, drawnOf } from "../../testutil/depths";
import { NO_GROUND } from "../../testutil/grounds";
import { theme } from "../theme/theme";
import { Part } from "./Part";

/** `theme.palette.text.secondary`, as a resolved colour is reported back. */
const SUPPORTING_TEXT_COLOUR = "rgb(88, 102, 108)";

function themed({ children }: { readonly children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

function drawnAt(section: HTMLElement) {
  return { ...drawnOf(section), below: getComputedStyle(section).marginBottom };
}

describe("Part", () => {
  it("is a region named by its one heading, at the level given", () => {
    render(
      <Part title="What it takes" level="h3" depth={2}>
        <p>Held</p>
      </Part>,
      { wrapper: themed },
    );

    const part = screen.getByRole("region", { name: "What it takes" });
    const heading = within(part).getByRole("heading", { level: 3 });

    expect(part.tagName).toBe("SECTION");
    expect(heading).toHaveTextContent("What it takes");
    expect(part.firstElementChild).toBe(heading);
    expect(within(part).getAllByRole("heading")).toEqual([heading]);
    expect(within(part).getByText("Held")).toBeInTheDocument();
  });

  it("places actions after the heading in its line while naming the region only by the title", () => {
    render(
      <Part
        title="Version 2"
        level="h2"
        depth={1}
        actions={<button>Retire</button>}
      >
        <p>Held</p>
      </Part>,
      { wrapper: themed },
    );

    const part = screen.getByRole("region", { name: "Version 2" });
    const heading = within(part).getByRole("heading", { name: "Version 2" });
    const action = within(part).getByRole("button", { name: "Retire" });

    expect(heading.parentElement).toBe(action.parentElement!.parentElement);
    expect(heading.contains(action)).toBe(false);
    expect(getComputedStyle(heading.parentElement!).flexWrap).toBe("wrap");
    expect(getComputedStyle(action.parentElement!).marginLeft).toBe("auto");
    expect(getComputedStyle(heading).flexGrow).toBe("0");
  });

  it("keeps title additions outside the heading and puts markers and unsaved before trailing actions", () => {
    render(
      <Part
        title="Version 2"
        level="h2"
        depth={1}
        afterTitle={
          <form>
            <input aria-label="Version name" />
          </form>
        }
        marker={<button>About the version</button>}
        unsaved
        actions={<button>Submit</button>}
      >
        Held
      </Part>,
      { wrapper: themed },
    );
    const region = screen.getByRole("region", { name: "Version 2" });
    const heading = within(region).getByRole("heading");
    const row = heading.parentElement!;
    expect([...row.children].map((child) => child.tagName)).toEqual([
      "H2",
      "FORM",
      "BUTTON",
      "SPAN",
      "DIV",
    ]);
    expect(heading.querySelector("form, button, input")).toBeNull();
    expect(row.children[2]).toHaveTextContent("About the version");
    expect(row.children[3]).toHaveTextContent("Unsaved");
    expect(row.lastElementChild).toHaveTextContent("Submit");
  });

  it.each(["marker", "unsaved", "afterTitle"] as const)(
    "uses a title row with only %s and no actions",
    (slot) => {
      render(
        <Part
          title="Output"
          level="h3"
          depth={2}
          marker={slot === "marker" ? <button>About output</button> : undefined}
          unsaved={slot === "unsaved"}
          afterTitle={
            slot === "afterTitle" ? <span>Night side</span> : undefined
          }
        >
          Held
        </Part>,
        { wrapper: themed },
      );
      const region = screen.getByRole("region", { name: "Output" });
      const heading = within(region).getByRole("heading");
      expect(heading.parentElement).not.toBe(region);
      expect(heading.parentElement!.children).toHaveLength(2);
      expect(region).toHaveAccessibleName("Output");
    },
  );

  /** A table or a list within the part takes its name from the same heading. */
  it("names its heading by the id given, for what within it is named by the heading too", () => {
    render(
      <Part title="Versions" level="h2" depth={1} headingId="versions">
        <ul aria-labelledby="versions">
          <li>Version 1</li>
        </ul>
      </Part>,
      { wrapper: themed },
    );

    const heading = screen.getByRole("heading", { name: "Versions" });

    expect(heading.id).toBe("versions");
    expect(screen.getByRole("list", { name: "Versions" })).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "Versions" })).toHaveAttribute(
      "aria-labelledby",
      "versions",
    );
  });

  it("names itself by an id of its own where it is given none", () => {
    render(
      <>
        <Part title="Takes" level="h3" depth={2}>
          held
        </Part>
        <Part title="Gives" level="h3" depth={2}>
          held
        </Part>
      </>,
      { wrapper: themed },
    );

    const [takes, gives] = screen.getAllByRole("heading");

    expect(takes!.id).not.toBe("");
    expect(takes!.id).not.toBe(gives!.id);
    expect(screen.getByRole("region", { name: "Takes" })).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "Gives" })).toBeInTheDocument();
  });

  it("leaves the heading out of the keyboard's way where nothing holds it", () => {
    render(
      <Part title="Help" level="h3" depth={2}>
        held
      </Part>,
      { wrapper: themed },
    );

    expect(screen.getByRole("heading", { name: "Help" })).not.toHaveAttribute(
      "tabindex",
    );
  });

  /** Held only to be focused, by a host that put the keyboard down somewhere that went. */
  it("hands a held heading to its holder, focusable from script but not by tabbing", () => {
    const heading = createRef<HTMLHeadingElement>();
    render(
      <Part title="Roles" level="h3" depth={3} headingRef={heading}>
        held
      </Part>,
      { wrapper: themed },
    );

    heading.current!.focus();

    const drawn = screen.getByRole("heading", { name: "Roles" });
    expect(heading.current).toBe(drawn);
    expect(drawn).toHaveAttribute("tabindex", "-1");
    expect(document.activeElement).toBe(drawn);
  });

  it("keeps a held heading, and the keyboard on it, while the part turns unsaved and saved again", () => {
    const heading = createRef<HTMLHeadingElement>();
    const drawn = (unsaved: boolean) => (
      <Part
        title="Roles"
        level="h3"
        depth={3}
        headingRef={heading}
        unsaved={unsaved}
      >
        held
      </Part>
    );
    const { rerender } = render(drawn(true), { wrapper: themed });
    const held = heading.current!;
    held.focus();

    rerender(drawn(false));

    expect(heading.current).toBe(held);
    expect(document.activeElement).toBe(held);
    expect(within(held.parentElement!).queryByText("Unsaved")).toBeNull();
  });

  it("hands the section itself to whoever holds it", () => {
    const section = createRef<HTMLElement>();
    render(
      <Part title="The note" level="h3" depth={2} ref={section}>
        held
      </Part>,
      { wrapper: themed },
    );

    expect(section.current).toBe(
      screen.getByRole("region", { name: "The note" }),
    );
  });

  it("draws its heading in its own level's variant, not in another's", () => {
    render(
      <>
        <Part title="Version 3" level="h2" depth={1}>
          held
        </Part>
        <Part title="What it gives back" level="h5" depth={3}>
          held
        </Part>
        <Part title="What it takes" level="h6" depth={3}>
          held
        </Part>
      </>,
      { wrapper: themed },
    );

    const page = getComputedStyle(screen.getByRole("heading", { level: 2 }));
    const group = getComputedStyle(screen.getByRole("heading", { level: 5 }));
    const inner = getComputedStyle(screen.getByRole("heading", { level: 6 }));

    expect(page.fontSize).toBe("20px");
    expect(group.fontSize).toBe("16px");
    expect(inner.color).toBe(SUPPORTING_TEXT_COLOUR);
    expect(group.color).not.toBe(SUPPORTING_TEXT_COLOUR);
  });

  it.each([
    [
      "a first-rank white card",
      1 as const,
      {
        topEdge: "1px",
        leadingEdge: "1px solid",
        leadingEdgeColour: "rgb(214, 224, 228)",
        ground: "rgb(255, 255, 255)",
        padding: "22px 28px",
        below: "30px",
      },
    ],
    [
      "a block separated only by space",
      2 as const,
      {
        topEdge: "0px",
        leadingEdge: "0px none",
        leadingEdgeColour: "rgb(0, 0, 0)",
        ground: NO_GROUND,
        padding: "0px 0px",
        below: "30px",
      },
    ],
    [
      "a group separated only by space",
      3 as const,
      {
        topEdge: "0px",
        leadingEdge: "0px none",
        leadingEdgeColour: "rgb(0, 0, 0)",
        ground: NO_GROUND,
        padding: "0px 0px",
        below: "20px",
      },
    ],
  ])("draws %s", (_depth, depth, expected) => {
    render(
      <Part title="Drawn" level="h3" depth={depth}>
        held
      </Part>,
      { wrapper: themed },
    );

    const region = screen.getByRole("region", { name: "Drawn" });
    expect(drawnAt(region)).toEqual(expected);
    const drawn = getComputedStyle(region);
    if (depth === 1) {
      expect(drawn.borderRadius).toBe("10px");
      expect(drawn.boxShadow).toBe("");
    } else {
      expect(drawn.borderRadius).toBe("");
      expect(drawn.boxShadow).toBe("");
    }
  });

  it("indents what a group holds without a second border or ground", () => {
    expect(drawnAtDepth(4)).toEqual({
      topEdge: "0px",
      leadingEdge: "0px none",
      leadingEdgeColour: "rgb(0, 0, 0)",
      ground: NO_GROUND,
      padding: "0px 20px",
    });
  });

  /** The last child leaves no extra margin at any depth. */
  it.each([1, 2, 3] as const)(
    "keeps no margin below the last thing a part at depth %s holds, and leaves the rest theirs",
    (depth) => {
      render(
        <Part title="Drawn" level="h3" depth={depth}>
          <p>First</p>
          <p>Last</p>
        </Part>,
        { wrapper: themed },
      );

      expect(getComputedStyle(screen.getByText("Last")).marginBottom).toBe(
        "0px",
      );
      expect(getComputedStyle(screen.getByText("First")).marginBottom).not.toBe(
        "0px",
      );
    },
  );
});
