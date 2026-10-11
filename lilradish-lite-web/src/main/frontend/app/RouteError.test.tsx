import { ThemeProvider } from "@mui/material/styles";
import { render, screen } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { act, type ReactNode } from "react";
import { createMemoryRouter, Outlet, RouterProvider } from "react-router";
import { describe, expect, it, vi } from "vitest";

import { expectConsoleErrors } from "../testutil/consoleErrors";
import { theme } from "../lib/theme/theme";
import { drawnAtDepth, drawnOf } from "../testutil/depths";
import { FrameError, NoSuchScreen, ScreenError } from "./RouteError";

/**
 * The screens are mounted where the application mounts them, but in a router
 * of their own: two screens standing in for the work, and a frame standing in
 * for the application's, which a case may make throw.
 *
 * Reached by going there from somewhere, because that is how a reader meets
 * each: the frame is already up and one move fails.
 */
async function arrivingAt(
  address: string,
  frame: () => ReactNode = () => <Outlet />,
) {
  const router = createMemoryRouter(
    [
      {
        path: "/",
        Component: frame,
        ErrorBoundary: FrameError,
        children: [
          {
            ErrorBoundary: ScreenError,
            children: [
              { index: true, Component: () => <p>Your day</p> },
              {
                path: "a-screen-that-breaks",
                Component: () => {
                  throw BROKEN;
                },
              },
              { path: "*", Component: NoSuchScreen },
            ],
          },
        ],
      },
    ],
    { initialEntries: ["/"] },
  );

  render(
    <ThemeProvider theme={theme}>
      <RouterProvider router={router} />
    </ThemeProvider>,
  );
  if (frame === aFrameThatBreaks) expectRouteFailure("aFrameThatBreaks");
  await act(() => router.navigate(address));
  if (address === BREAKS || frame === aFrameThatBreaks) {
    expectRouteFailure(
      frame === aFrameThatBreaks ? "aFrameThatBreaks" : "Component",
    );
  }
  return router;
}

const NAMES_NOTHING = "/somewhere-nobody-published";
const BREAKS = "/a-screen-that-breaks";

/** A TypeError, as the commonest fault in a screen is. */
const BROKEN_BY = "Cannot read properties of undefined (reading 'name')";
const BROKEN = new TypeError(BROKEN_BY);
const FRAME_BROKEN = new TypeError(BROKEN_BY);

function expectRouteFailure(component: string) {
  const broken = component === "aFrameThatBreaks" ? FRAME_BROKEN : BROKEN;
  expectConsoleErrors(
    [
      "%o\n\n%s\n\n%s\n",
      broken,
      `The above error occurred in the <${component}> component.`,
      "React will try to recreate this component tree from scratch using the error boundary you provided, RenderErrorBoundary.",
    ],
    ["React Router caught the following error during render", broken],
  );
}

function aFrameThatBreaks(): ReactNode {
  throw FRAME_BROKEN;
}

describe("NoSuchScreen", () => {
  /** The screen's own name, as any screen's is, and the alert beneath it says what happened to it. */
  it("names an address that names nothing with its one first-level heading, over an alert that holds only that", async () => {
    await arrivingAt(NAMES_NOTHING);

    const heading = screen.getByRole("heading", { level: 1 });
    const alert = screen.getByRole("alert");

    expect(heading).toHaveTextContent("No such screen");
    expect(screen.getAllByRole("heading")).toEqual([heading]);
    expect(alert.textContent).toBe("The address does not name anything here.");
    expect(
      heading.compareDocumentPosition(alert) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).not.toBe(0);
    expect(screen.queryByText(/Something went wrong/)).toBeNull();
  });

  it("is one part of the page, named by its heading, holding the alert and the way back", async () => {
    await arrivingAt(NAMES_NOTHING);

    const part = screen.getByRole("region", { name: "No such screen" });

    expect(part).toContainElement(screen.getByRole("alert"));
    expect(part).toContainElement(
      screen.getByRole("link", { name: "Back to the start" }),
    );
    expect(drawnOf(part)).toEqual(drawnAtDepth(1));
    expect(screen.getAllByRole("region")).toEqual([part]);
  });

  /**
   * The application is running and is the one thing known to be sound, so the
   * way back out of a bad address costs nothing but a render.
   */
  it("walks back from a bad address without fetching the application again", async () => {
    const router = await arrivingAt(NAMES_NOTHING);

    await userEvent.click(
      screen.getByRole("link", { name: "Back to the start" }),
    );

    expect(router.state.location.pathname).toBe("/");
    expect(screen.getByText("Your day")).toBeVisible();
  });
});

describe("ScreenError", () => {
  it("tells a reader a screen broke and the menu is the way on, without saying what broke it", async () => {
    await arrivingAt(BREAKS);

    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent(
      "Something went wrong on this page.",
    );
    expect(screen.getByRole("alert").textContent).toBe(
      "Nothing you saved is lost. Go to another page from the menu, or reload this one.",
    );
    expect(screen.queryByText(/reading 'name'/)).toBeNull();
    expect(screen.queryByText("No such screen")).toBeNull();
  });

  /** A reload keeps the address and whatever the history entry holds; a link to either would not. */
  it("offers to reload the page that broke as an action, not as a way somewhere else", async () => {
    await arrivingAt(BREAKS);

    expect(
      screen.getByRole("button", { name: "Reload this page" }),
    ).toBeInTheDocument();
    expect(screen.queryAllByRole("link")).toEqual([]);
  });
});

describe("FrameError", () => {
  /** The menu went down with the frame, so a sentence sending the reader to it would send them nowhere. */
  it("tells a reader the frame broke and only a reload carries on, pointing at no menu and saying nothing of the cause", async () => {
    await arrivingAt("/", aFrameThatBreaks);

    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent(
      "Something went wrong.",
    );
    expect(screen.getByRole("alert").textContent).toBe(
      "Nothing you saved is lost. Reload to carry on.",
    );
    expect(screen.getByRole("button", { name: "Reload" })).toBeInTheDocument();
    expect(screen.queryByText(/menu/)).toBeNull();
    expect(screen.queryByText(/reading 'name'/)).toBeNull();
    expect(screen.queryAllByRole("link")).toEqual([]);
    const containers = document.querySelectorAll(".MuiContainer-root");
    expect(containers).toHaveLength(1);
    expect(containers[0]).toHaveClass("MuiContainer-maxWidthXl");
    expect(containers[0]).toContainElement(screen.getByRole("alert"));
  });
});

describe("reload from a broken page", () => {
  it.each([
    ["screen", BREAKS, "Reload this page", undefined],
    ["frame", "/", "Reload", aFrameThatBreaks],
  ] as const)(
    "asks the browser to reload the %s at its current address",
    async (_case, address, label, frame) => {
      const router = await arrivingAt(address, frame);
      const browserAddress = window.location.href;
      const pageAddress = router.state.location.pathname;
      const originalWindow = window;
      const windowProperty = Object.getOwnPropertyDescriptor(
        globalThis,
        "window",
      )!;
      const reload = vi.fn();
      const browser = new Proxy(originalWindow, {
        get(target, key, receiver) {
          return key === "location"
            ? { href: browserAddress, reload }
            : Reflect.get(target, key, receiver);
        },
      });
      Object.defineProperty(globalThis, "window", {
        configurable: true,
        writable: true,
        value: browser,
      });
      try {
        await userEvent.click(screen.getByRole("button", { name: label }));
        expect(reload.mock.calls).toEqual([[]]);
        expect(originalWindow.location.href).toBe(browserAddress);
        expect(window.location.href).toBe(browserAddress);
        expect(router.state.location.pathname).toBe(pageAddress);
      } finally {
        Object.defineProperty(globalThis, "window", windowProperty);
      }
    },
  );
});
