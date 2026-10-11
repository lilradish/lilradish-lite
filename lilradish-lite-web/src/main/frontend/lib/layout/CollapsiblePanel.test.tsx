import { ThemeProvider } from "@mui/material/styles";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, onTestFinished } from "vitest";

import { theme } from "../theme/theme";
import { CollapsiblePanel } from "./CollapsiblePanel";

describe("CollapsiblePanel", () => {
  it.each(["Order details", "Fenn".repeat(80)])(
    "ends the visible title row with the same reachable fold control, preserving its content and keyboard on reopening",
    async (title) => {
      const key = "lilradish.test.panel.heading";
      localStorage.removeItem(key);
      onTestFinished(() => localStorage.removeItem(key));
      const user = userEvent.setup();
      render(
        <ThemeProvider theme={theme}>
          <CollapsiblePanel
            title={title}
            heading={<h2>{title}</h2>}
            keptAs={key}
            defaultExpanded
          >
            <input aria-label="Notes" defaultValue="Kept" />
          </CollapsiblePanel>
        </ThemeProvider>,
      );
      const heading = screen.getByRole("heading", { name: title });
      const fold = screen.getByRole("button", { name: title });
      const notes = screen.getByRole("textbox", { name: "Notes" });
      const header = heading.closest("header")!;
      const content = document.getElementById(
        fold.getAttribute("aria-controls")!,
      )!;

      expect(header).toContainElement(fold);
      expect(
        heading.compareDocumentPosition(fold) &
          Node.DOCUMENT_POSITION_FOLLOWING,
      ).toBeTruthy();
      expect(header).toHaveStyle({ display: "flex" });
      expect(content).toContainElement(notes);
      expect(content).not.toContainElement(fold);
      await user.type(notes, " draft");
      await user.click(fold);
      expect(heading).not.toBeVisible();
      expect(notes).not.toBeVisible();
      expect(fold).toBeVisible();
      expect(fold).toHaveFocus();
      expect(fold).toHaveAttribute("aria-expanded", "false");
      await user.keyboard("{Enter}");
      expect(screen.getByRole("heading", { name: title })).toBe(heading);
      expect(screen.getByRole("button", { name: title })).toBe(fold);
      expect(screen.getByRole("textbox", { name: "Notes" })).toBe(notes);
      expect(notes).toHaveValue("Kept draft");
      expect(fold).toHaveFocus();
      expect(fold).toHaveAttribute("aria-expanded", "true");
    },
  );
  it("keeps the child's node and draft while folded and remembers the device choice", async () => {
    const user = userEvent.setup();
    const key = "lilradish.test.panel.order";
    localStorage.removeItem(key);
    onTestFinished(() => localStorage.removeItem(key));
    const { unmount } = render(
      <ThemeProvider theme={theme}>
        <CollapsiblePanel title="Order details" keptAs={key} defaultExpanded>
          <input aria-label="Notes" defaultValue="Initial" />
        </CollapsiblePanel>
      </ThemeProvider>,
    );
    const notes = screen.getByRole("textbox", { name: "Notes" });
    await user.type(notes, " draft");
    const toggle = screen.getByRole("button", { name: "Order details" });
    await user.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    expect(notes).not.toBeVisible();
    expect(notes).toBeInTheDocument();
    expect(toggle).toHaveFocus();
    await user.click(toggle);
    expect(screen.getByRole("textbox", { name: "Notes" })).toBe(notes);
    expect(notes).toHaveValue("Initial draft");
    await user.click(toggle);
    unmount();
    render(
      <ThemeProvider theme={theme}>
        <CollapsiblePanel title="Order details" keptAs={key} defaultExpanded>
          <p>Order</p>
        </CollapsiblePanel>
      </ThemeProvider>,
    );
    expect(
      screen.getByRole("button", { name: "Order details" }),
    ).toHaveAttribute("aria-expanded", "false");
    expect(screen.getByText("Order")).not.toBeVisible();
  });

  it("starts folded where its caller's first choice says so, as on a narrow box, when no choice has been kept", () => {
    const key = "lilradish.test.panel.narrow";
    localStorage.removeItem(key);
    onTestFinished(() => localStorage.removeItem(key));
    render(
      <ThemeProvider theme={theme}>
        <CollapsiblePanel title="Details" keptAs={key} defaultExpanded={false}>
          <p>Still mounted</p>
        </CollapsiblePanel>
      </ThemeProvider>,
    );
    expect(screen.getByRole("button", { name: "Details" })).toHaveAttribute(
      "aria-expanded",
      "false",
    );
    expect(screen.getByText("Still mounted")).toBeInTheDocument();
    expect(screen.getByText("Still mounted")).not.toBeVisible();
    const rail = screen.getByRole("button", { name: "Details" });
    expect(rail).toHaveAttribute("title", "Details");
    expect(rail.textContent).toBe("");
    expect(rail.querySelectorAll("svg")).toHaveLength(2);
    expect(rail.querySelector("div, p")).toBeNull();
    expect(getComputedStyle(rail.parentElement!).width).toBe("56px");
    expect(getComputedStyle(rail).alignItems).toBe("center");
  });

  it("keeps a borrowed panel folded without changing the remembered choice", async () => {
    const key = "lilradish.test.panel.borrowed";
    localStorage.setItem(key, "expanded");
    onTestFinished(() => localStorage.removeItem(key));
    const { rerender } = render(
      <ThemeProvider theme={theme}>
        <CollapsiblePanel
          title="Details"
          keptAs={key}
          defaultExpanded
          passing="folded"
          disabled
        >
          <input aria-label="Notes" defaultValue="Kept" />
        </CollapsiblePanel>
      </ThemeProvider>,
    );
    const folded = screen.getByRole("button", { name: "Details" });
    folded.click();
    expect(folded).toBeDisabled();
    expect(folded).toHaveAttribute("aria-expanded", "false");
    expect(localStorage.getItem(key)).toBe("expanded");

    rerender(
      <ThemeProvider theme={theme}>
        <CollapsiblePanel title="Details" keptAs={key} defaultExpanded>
          <input aria-label="Notes" defaultValue="Kept" />
        </CollapsiblePanel>
      </ThemeProvider>,
    );
    expect(screen.getByRole("textbox", { name: "Notes" })).toHaveValue("Kept");
    expect(screen.getByRole("button", { name: "Details" })).toHaveAttribute(
      "aria-expanded",
      "true",
    );
  });
});
