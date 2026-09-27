import Button from "@mui/material/Button";
import { ThemeProvider } from "@mui/material/styles";
import { render, screen } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import type { ReactNode } from "react";
import { describe, expect, it } from "vitest";

import { theme } from "../theme/theme";
import { PageHeading } from "./PageHeading";

function themed({ children }: { readonly children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

describe("PageHeading", () => {
  it("names the page with its one first-level heading", () => {
    render(<PageHeading title="Things" />, { wrapper: themed });

    const heading = screen.getByRole("heading", { name: "Things" });

    expect(heading.tagName).toBe("H1");
    expect(screen.getAllByRole("heading")).toEqual([heading]);
  });

  it("names a page drawn within another's at the second level, leaving the first to that page", () => {
    render(<PageHeading title="Things" level={2} />, { wrapper: themed });

    const heading = screen.getByRole("heading", { name: "Things" });

    expect(heading.tagName).toBe("H2");
    expect(screen.queryAllByRole("heading", { level: 1 })).toEqual([]);
  });

  it("leaves the keyboard where it was, and the heading out of it, on a page opened plainly", () => {
    render(<PageHeading title="Things" />, { wrapper: themed });

    const heading = screen.getByRole("heading", { name: "Things" });

    expect(document.activeElement).toBe(document.body);
    expect(heading).not.toHaveAttribute("tabindex");
  });

  /** The control that brought the reader here was on the page they left, so the keyboard starts here. */
  it("takes the keyboard on a page arrived at by an act, and only once", () => {
    const { rerender } = render(<PageHeading title="Things" focusOnArrival />, {
      wrapper: themed,
    });
    const heading = screen.getByRole("heading", { name: "Things" });
    heading.blur();

    rerender(<PageHeading title="Things renamed" focusOnArrival />);

    expect(heading).toHaveAttribute("tabindex", "-1");
    expect(document.activeElement).toBe(document.body);
  });

  it("is where the keyboard is once a page arrived at by an act opens", () => {
    render(<PageHeading title="Things" focusOnArrival />, { wrapper: themed });

    expect(document.activeElement).toBe(
      screen.getByRole("heading", { name: "Things" }),
    );
  });

  /** Nothing the reader may not use is drawn, so there is nothing to take the place of one. */
  it("draws no control where the page offers the reader nothing", () => {
    const { container } = render(<PageHeading title="Readings" />, {
      wrapper: themed,
    });

    expect(screen.queryAllByRole("button")).toEqual([]);
    expect(container.firstElementChild!.children).toHaveLength(1);
  });

  /** Reading on from the heading, and moving on from it, both reach the page's own act first. */
  it("puts the page's actions after its title, level with it", async () => {
    const { container } = render(
      <PageHeading title="Things" actions={<Button>Make one</Button>} />,
      { wrapper: themed },
    );

    const [title, action] = container.firstElementChild!.children;
    await userEvent.tab();

    expect(title).toBe(screen.getByRole("heading", { name: "Things" }));
    expect(action).toBe(screen.getByRole("button", { name: "Make one" }));
    expect(document.activeElement).toBe(action);
  });

  /**
   * Laid out by the flex rules alone, which this environment computes but
   * never applies, so what is pinned is the rules: wrapping rather than
   * overflowing, and a long name that breaks rather than pushing the actions out.
   */
  it("wraps the actions onto a line of their own at the trailing side rather than overflow", () => {
    const { container } = render(
      <PageHeading title="Things" actions={<Button>Make one</Button>} />,
      { wrapper: themed },
    );

    const root = container.firstElementChild as HTMLElement;
    const drawn = getComputedStyle(root);
    const title = getComputedStyle(screen.getByRole("heading"));

    expect(root.tagName).toBe("DIV");
    expect(drawn.display).toBe("flex");
    expect(drawn.flexWrap).toBe("wrap");
    expect(drawn.justifyContent).toBe("flex-end");
    expect(title.flexGrow).toBe("1");
    expect(title.minWidth).toBe("0px");
    expect(title.overflowWrap).toBe("anywhere");
  });
});
