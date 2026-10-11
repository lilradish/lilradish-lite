import { render, screen } from "@testing-library/react";
import { ThemeProvider } from "@mui/material/styles";
import { describe, expect, it } from "vitest";

import { focusDrawerHeading, StandingDrawer } from "./RowDrawer";
import { theme } from "../theme/theme";

describe("StandingDrawer", () => {
  it("covers the whole underlying drawer and keeps the footer outside its scrolling body", () => {
    const slot = document.createElement("div");
    document.body.append(slot);
    const { unmount } = render(
      <ThemeProvider theme={theme}>
        <StandingDrawer
          slot={slot}
          covers
          open
          onExited={() => undefined}
          width={560}
          headingId="field-title"
          heading={<h4 id="field-title">The field</h4>}
          onShut={() => undefined}
          said={null}
          attending={null}
          above={<p>Field notice</p>}
          foot={<button>Save field</button>}
        >
          <p>Field controls</p>
        </StandingDrawer>
      </ThemeProvider>,
    );
    expect(screen.getByRole("dialog", { name: "The field" })).toHaveStyle({
      width: "100%",
    });
    const body = screen.getByText("Field controls").parentElement!;
    const focusRoot = body.parentElement!;
    expect(focusRoot).toHaveAttribute("tabindex", "-1");
    expect(focusRoot).toHaveStyle({
      display: "flex",
      flexDirection: "column",
      minHeight: "0",
    });
    focusRoot.focus();
    expect(focusRoot).toHaveFocus();
    expect(body).toHaveStyle({ overflowY: "auto", overflowX: "hidden" });
    expect(body).not.toContainElement(
      screen.getByRole("button", { name: "Save field" }),
    );
    expect(screen.getByText("Field notice")).toBeVisible();
    expect(screen.getByRole("button", { name: "Save field" })).toBeVisible();
    unmount();
    slot.remove();
  });
});

function drawerBesideThePage() {
  render(
    <>
      <button type="button">On the page</button>
      <div role="dialog" aria-labelledby="drawer-name">
        <h4 id="drawer-name" tabIndex={-1}>
          Step 2
        </h4>
        <button type="button">In the drawer</button>
      </div>
    </>,
  );
}

describe("focusDrawerHeading", () => {
  it("puts the keyboard on the heading naming the drawer it is handed something inside", () => {
    drawerBesideThePage();
    const inside = screen.getByRole("button", { name: "In the drawer" });
    inside.focus();

    focusDrawerHeading(inside);

    expect(screen.getByRole("heading", { name: "Step 2" })).toHaveFocus();
    expect(inside).not.toHaveFocus();
  });

  it.each([
    ["something in no drawer", "On the page"],
    ["nothing", null],
  ])("leaves the keyboard where it was, handed %s", (_case, handed) => {
    drawerBesideThePage();
    const page = screen.getByRole("button", { name: "On the page" });
    page.focus();

    focusDrawerHeading(
      handed === null ? null : screen.getByRole("button", { name: handed }),
    );

    expect(page).toHaveFocus();
    expect(screen.getByRole("heading", { name: "Step 2" })).not.toHaveFocus();
  });
});
