import { ThemeProvider } from "@mui/material/styles";
import { render, screen } from "@testing-library/react";
import type { ReactNode } from "react";
import { MemoryRouter } from "react-router";
import { describe, expect, it } from "vitest";

import { theme } from "../theme/theme";
import { Breadcrumbs, type Crumb } from "./Breadcrumbs";

/** `theme.palette.text.primary`, as a resolved colour is reported back. */
const CURRENT_COLOUR = "rgb(26, 26, 26)";

/**
 * `theme.palette.text.secondary`, which the trail's own root imposes on
 * anything that resolves no colour of its own — the colour a regression here
 * produces, not merely a colour this one is not.
 */
const TRAIL_COLOUR = "rgb(89, 89, 89)";

const TRAIL: readonly Crumb[] = [
  { label: "Groups", path: "/system/groups" },
  { label: "Finance", path: "/system/groups/finance" },
];

function arrivedWith(
  state: unknown,
): (props: { children: ReactNode }) => ReactNode {
  return ({ children }) => (
    <MemoryRouter
      initialEntries={[
        { pathname: "/system/groups/finance/members/a3f2", state },
      ]}
    >
      {/* The palette is what turns a colour prop into a colour, and nothing
          rendered says whether one resolved or was quietly dropped. */}
      <ThemeProvider theme={theme}>{children}</ThemeProvider>
    </MemoryRouter>
  );
}

function hrefOf(label: string): string {
  return screen.getByRole("link", { name: label }).getAttribute("href") ?? "";
}

describe("Breadcrumbs", () => {
  it("restores the journey's filter on the first crumb and on no other", () => {
    render(<Breadcrumbs trail={TRAIL} current="a3f2" />, {
      wrapper: arrivedWith({ from: "?lens=mine&page=2" }),
    });

    expect(hrefOf("Groups")).toBe("/system/groups?lens=mine&page=2");
    expect(hrefOf("Finance")).toBe("/system/groups/finance");
  });

  it("links to the plain list when the screen was arrived at directly", () => {
    render(<Breadcrumbs trail={TRAIL} current="a3f2" />, {
      wrapper: arrivedWith(undefined),
    });

    expect(hrefOf("Groups")).toBe("/system/groups");
    expect(hrefOf("Groups")).not.toContain("?");
  });

  it("leaves the screen you are on unlinked, which is what excuses it from aria-current", () => {
    render(<Breadcrumbs trail={TRAIL} current="a3f2" />, {
      wrapper: arrivedWith({ from: "?lens=mine" }),
    });

    const current = screen.getByText("a3f2");

    expect(current.tagName).toBe("P");
    expect(screen.queryByRole("link", { name: "a3f2" })).toBeNull();
  });

  it("paints the crumb you are on so it stands out from the trail behind it", () => {
    render(<Breadcrumbs trail={TRAIL} current="a3f2" />, {
      wrapper: arrivedWith({ from: "?lens=mine" }),
    });

    const current = screen.getByText("a3f2");

    expect(getComputedStyle(current).color).toBe(CURRENT_COLOUR);
    expect(getComputedStyle(current).color).not.toBe(TRAIL_COLOUR);
  });

  it("names its landmark, so it is not one unnamed navigation among several", () => {
    render(<Breadcrumbs trail={TRAIL} current="a3f2" />, {
      wrapper: arrivedWith(undefined),
    });

    expect(
      screen.getByRole("navigation", { name: "Breadcrumb" }),
    ).toBeDefined();
  });

  /** Past eight steps the middle folds into one control, named in the catalogue's words and not Material's. */
  it("names the control that unfolds a long trail in this side's own words", () => {
    const long = Array.from({ length: 8 }, (_, at) => ({
      label: `Step ${at + 1}`,
      path: `/step/${at + 1}`,
    }));
    render(<Breadcrumbs trail={long} current="a3f2" />, {
      wrapper: arrivedWith(undefined),
    });

    expect(
      screen.getByRole("button", { name: "Show the whole trail" }),
    ).toBeVisible();
    expect(screen.queryByRole("button", { name: "Show path" })).toBeNull();
  });

  it("walks the trail in the order it was given", () => {
    render(<Breadcrumbs trail={TRAIL} current="a3f2" />, {
      wrapper: arrivedWith(undefined),
    });

    const labels = screen.getAllByRole("link").map((link) => link.textContent);

    expect(labels).toEqual(["Groups", "Finance"]);
  });
});
