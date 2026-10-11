import Button from "@mui/material/Button";
import TextField from "@mui/material/TextField";
import { ThemeProvider } from "@mui/material/styles";
import { act, render, screen } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { useState, type ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";

import { InlineEdit } from "../action/InlineEdit";
import { InlineEditableValue } from "../action/InlineEditableValue";

import { theme } from "../theme/theme";
import { PageHeading } from "./PageHeading";

function themed({ children }: { readonly children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

describe("PageHeading", () => {
  it.each(["Enter", "Space", "title", "pen"])(
    "opens the title with %s while its kind remains outside the trigger and the heading's name",
    async (how) => {
      const user = userEvent.setup();
      const edit = vi.fn();
      function Heading() {
        const [open, setOpen] = useState(false);
        return (
          <PageHeading
            title="Invented complaint workflow"
            besideTitle={<span>Workflow</span>}
            titleValue={
              <InlineEditableValue
                value="Invented complaint workflow"
                characters={128}
                onEdit={() => {
                  edit();
                  setOpen(true);
                }}
              />
            }
            editingTitle={
              open ? (
                <InlineEdit
                  open
                  characters={128}
                  action={{ run: vi.fn(), running: false, problem: null }}
                  act={() => Promise.resolve(null)}
                  onShut={() => setOpen(false)}
                  refusal={null}
                >
                  <TextField
                    multiline
                    defaultValue="Invented complaint workflow"
                    autoFocus
                    slotProps={{ htmlInput: { "aria-label": "Name" } }}
                  />
                </InlineEdit>
              ) : undefined
            }
          />
        );
      }
      render(<Heading />, { wrapper: themed });
      const title = screen.getByRole("button", {
        name: "Invented complaint workflow, editable",
      });
      const kind = screen.getByText("Workflow");
      expect(title).not.toContainElement(kind);
      expect(
        screen.getByRole("heading", {
          level: 1,
          name: "Invented complaint workflow",
        }),
      ).not.toContainElement(kind);
      const ground = getComputedStyle(kind).backgroundColor;
      await user.hover(kind);
      await user.click(kind);
      expect(getComputedStyle(kind).backgroundColor).toBe(ground);
      expect(edit).not.toHaveBeenCalled();
      expect(screen.queryByRole("textbox")).toBeNull();
      if (how === "title") await user.click(title);
      else if (how === "pen") await user.click(title.querySelector("svg")!);
      else {
        await user.tab();
        expect(title).toHaveFocus();
        await user.keyboard(how === "Space" ? " " : "{Enter}");
      }
      expect(edit).toHaveBeenCalledOnce();
      expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue(
        "Invented complaint workflow",
      );
      expect(screen.getAllByRole("heading", { level: 1 })).toEqual([
        screen.getByRole("heading", {
          level: 1,
          name: "Invented complaint workflow",
        }),
      ]);
      expect(kind.closest("button, form, h1")).toBeNull();
      expect(kind).not.toHaveAttribute("aria-hidden");
    },
  );

  it("keeps the measured text limit when the box opens, remeasures the line and kind without moving focus, and disconnects", () => {
    let lineWidth = 400;
    let kindWidth = 80;
    vi.spyOn(Element.prototype, "getBoundingClientRect").mockImplementation(
      function (this: Element) {
        return new DOMRect(
          0,
          0,
          this.tagName === "SPAN" ? kindWidth : lineWidth,
          32,
        );
      },
    );
    const observed: {
      notify: () => void;
      disconnect: ReturnType<typeof vi.fn>;
    }[] = [];
    class Observer implements ResizeObserver {
      readonly disconnect = vi.fn();
      readonly observe = vi.fn();
      readonly unobserve = vi.fn();
      constructor(callback: ResizeObserverCallback) {
        observed.push({
          notify: () => callback([], this),
          disconnect: this.disconnect,
        });
      }
    }
    vi.stubGlobal("ResizeObserver", Observer);
    const title = "Replace a damaged invented delivery for a customer";
    const props = {
      title,
      focusOnArrival: true,
      besideTitle: <span>Workflow</span>,
      titleValue: (
        <InlineEditableValue value={title} characters={128} onEdit={vi.fn()} />
      ),
    };
    const { rerender, unmount } = render(<PageHeading {...props} />, {
      wrapper: themed,
    });
    const heading = screen.getByRole("heading", { level: 1, name: title });
    const slot = heading.parentElement!;
    const measure = getComputedStyle(slot).getPropertyValue(
      "--inline-text-measure",
    );
    expect(getComputedStyle(slot.parentElement!).gap).toBe(theme.spacing(1));
    expect(measure).toContain("310px");
    expect(
      getComputedStyle(
        screen.getByRole("button", { name: `${title}, editable` }),
      ).width,
    ).toBe("fit-content");
    expect(getComputedStyle(slot).maxWidth).toBe("310px");
    expect(getComputedStyle(slot.parentElement!).alignItems).toBe(
      "last baseline",
    );
    expect(heading).toHaveFocus();
    rerender(
      <PageHeading
        {...props}
        editingTitle={
          <InlineEdit
            open
            characters={128}
            action={{ run: vi.fn(), running: false, problem: null }}
            act={() => Promise.resolve(null)}
            onShut={vi.fn()}
            refusal={null}
          >
            <TextField
              multiline
              defaultValue={title}
              slotProps={{ htmlInput: { "aria-label": "Name" } }}
            />
          </InlineEdit>
        }
      />,
    );
    expect(
      getComputedStyle(slot).getPropertyValue("--inline-text-measure"),
    ).toBe(measure);
    expect(getComputedStyle(slot).maxWidth).not.toBe("310px");
    expect(
      screen.getByRole("textbox", { name: "Name" }).closest("form")!
        .parentElement,
    ).toBe(slot);
    expect(heading).toHaveFocus();
    lineWidth = 320;
    act(() => observed[0]!.notify());
    expect(
      getComputedStyle(slot).getPropertyValue("--inline-text-measure"),
    ).toContain("230px");
    kindWidth = 100;
    act(() => observed[0]!.notify());
    expect(
      getComputedStyle(slot).getPropertyValue("--inline-text-measure"),
    ).toContain("210px");
    expect(heading).toHaveFocus();
    unmount();
    expect(observed[0]!.disconnect).toHaveBeenCalledOnce();
  });
  it("names the page with its one first-level heading", () => {
    render(<PageHeading title="Things" />, { wrapper: themed });

    const heading = screen.getByRole("heading", { name: "Things" });

    expect(heading.tagName).toBe("H1");
    expect(screen.getAllByRole("heading")).toEqual([heading]);
  });

  it("places a small tag beside the title without adding it to the heading's name", () => {
    render(<PageHeading title="Triage" besideTitle={<span>Question</span>} />, {
      wrapper: themed,
    });

    const heading = screen.getByRole("heading", { level: 1, name: "Triage" });
    const tag = screen.getByText("Question");

    expect(heading.parentElement?.parentElement).toContainElement(tag);
    expect(
      heading.compareDocumentPosition(tag) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).not.toBe(0);
    expect(
      getComputedStyle(heading.parentElement!.parentElement!).flexWrap,
    ).toBe("wrap");
    expect(getComputedStyle(heading.parentElement!).fontSize).toBe("24px");
    expect(getComputedStyle(heading.parentElement!).flexGrow).toBe("0");
    expect(
      getComputedStyle(heading.parentElement!.parentElement!).flexGrow,
    ).toBe("1");
  });

  it("draws its heading in the first level's variant", () => {
    render(<PageHeading title="Run 7" />, { wrapper: themed });

    const page = getComputedStyle(screen.getByRole("heading", { level: 1 }));

    expect(page.fontSize).toBe("24px");
  });

  it.each([true, false])(
    "keeps an editable heading's block slot with a companion: %s",
    (beside) => {
      const { rerender } = render(
        <PageHeading
          title="Invented run"
          titleValue={<Button>Invented run</Button>}
          besideTitle={beside ? <span>Workflow</span> : undefined}
        />,
        { wrapper: themed },
      );
      const heading = screen.getByRole("heading", {
        level: 1,
        name: "Invented run",
      });
      const slot = heading.parentElement;
      expect(getComputedStyle(heading).display).toBe("flex");
      expect(getComputedStyle(heading).fontSize).toBe("24px");
      expect(heading).toContainElement(
        screen.getByRole("button", { name: "Invented run" }),
      );

      rerender(
        <PageHeading
          title="Invented run"
          editingTitle={
            <form aria-label="Edit run">
              <input aria-label="Name" />
            </form>
          }
          besideTitle={beside ? <span>Workflow</span> : undefined}
        />,
      );

      expect(
        screen.getByRole("heading", { level: 1, name: "Invented run" })
          .parentElement,
      ).toBe(slot);
      expect(slot).toContainElement(
        screen.getByRole("form", { name: "Edit run" }),
      );
      expect(screen.getByRole("form")).not.toContainElement(
        screen.getByRole("heading"),
      );
      expect(screen.getAllByRole("heading", { level: 1 })).toHaveLength(1);
    },
  );

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
