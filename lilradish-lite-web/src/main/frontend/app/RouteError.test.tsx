import { ThemeProvider } from "@mui/material/styles";
import { render, screen } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { act, type ReactNode } from "react";
import { createMemoryRouter, Outlet, RouterProvider } from "react-router";
import { describe, expect, it, vi } from "vitest";

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
                  throw new TypeError(BROKEN_BY);
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
  await act(() => router.navigate(address));
  return router;
}

const NAMES_NOTHING = "/somewhere-nobody-published";
const BREAKS = "/a-screen-that-breaks";

/** A TypeError, as the commonest fault in a screen is. */
const BROKEN_BY = "Cannot read properties of undefined (reading 'name')";

function aFrameThatBreaks(): ReactNode {
  throw new TypeError(BROKEN_BY);
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
    vi.spyOn(console, "error").mockImplementation(() => {});
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
    vi.spyOn(console, "error").mockImplementation(() => {});
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
    vi.spyOn(console, "error").mockImplementation(() => {});
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
      vi.spyOn(console, "error").mockImplementation(() => {});
      const router = await arrivingAt(address, frame);
      const browserAddress = window.location.href;
      const pageAddress = router.state.location.pathname;
      const environment = globalThis as typeof globalThis & {
        jsdom: {
          virtualConsole: {
            on(event: "jsdomError", listener: (error: Error) => void): void;
            off(event: "jsdomError", listener: (error: Error) => void): void;
          };
        };
      };
      const reported: Error[] = [];
      const onError = (error: Error) => reported.push(error);
      environment.jsdom.virtualConsole.on("jsdomError", onError);
      try {
        await userEvent.click(screen.getByRole("button", { name: label }));
        expect(reported.map((error) => error.message)).toEqual([
          "Not implemented: navigation to another Document",
        ]);
        expect(window.location.href).toBe(browserAddress);
        expect(router.state.location.pathname).toBe(pageAddress);
      } finally {
        environment.jsdom.virtualConsole.off("jsdomError", onError);
      }
    },
  );
});
