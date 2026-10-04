import { ThemeProvider } from "@mui/material/styles";
import { render, screen, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import { theme } from "../../lib/theme/theme";
import { drawnAtDepth, drawnOf } from "../../testutil/depths";
import { WhatIsAdded } from "./WhatIsAdded";

const ADDED =
  'Answer with one JSON object.\nJSON Schema:\n{"properties":{"values":{"properties":{"category":{"enum":["Billing","Delivery"],"description":"A charge; it came late."},"details":{"properties":{"product":{"maxLength":128}}}}},"confidences":{"type":"object"}}}\n';

function part(): HTMLElement {
  return screen.getByRole("region", { name: "What is added when it runs" });
}

function disclosure(): HTMLElement {
  return screen.getByRole("button", { name: "What is added when it runs" });
}

describe("WhatIsAdded", () => {
  it("starts shut and explains the unchanged model instructions when the information icon has focus", async () => {
    render(<WhatIsAdded added={ADDED} />);
    expect(disclosure()).toHaveAttribute("aria-expanded", "false");
    expect(part()).toHaveTextContent(
      "The instructions and answer schema, exactly as sent to the model.",
    );
    expect(part().querySelector("pre")).toBeNull();
    screen
      .getByRole("button", {
        name: /What is sent to the model on top of the instruction/,
      })
      .focus();
    expect((await screen.findByRole("tooltip")).textContent).toContain(
      "Nobody edits it",
    );
  });

  it("shows the complete server text verbatim with whitespace and escaping intact and no editing control", async () => {
    const added =
      ADDED + '  <script>alert("Demo Sample")</script>\n\t"\\n" 😃 עברית\n';
    render(<WhatIsAdded added={added} />);
    await userEvent.click(disclosure());
    const text = part().querySelector("pre");
    expect(text?.textContent).toBe(added);
    expect(text).toHaveAttribute("dir", "auto");
    expect(part().querySelector("script")).toBeNull();
    expect(within(part()).queryByRole("textbox")).toBeNull();
    expect(within(part()).queryByRole("list")).toBeNull();
    await userEvent.click(disclosure());
    expect(part().querySelector("pre")).toBeNull();
  });

  it("keeps the framed version block and wraps long schema lines", async () => {
    render(
      <ThemeProvider theme={theme}>
        <WhatIsAdded added={ADDED} />
      </ThemeProvider>,
    );
    await userEvent.click(disclosure());
    expect(drawnOf(part())).toEqual(drawnAtDepth(2));
    const text = part().querySelector("pre")!;
    expect(getComputedStyle(text).whiteSpace).toBe("pre-wrap");
    expect(getComputedStyle(text).overflowWrap).toBe("anywhere");
  });

  it("waits on a whole declared answer without offering an empty expansion", () => {
    render(<WhatIsAdded added={undefined} />);
    expect(
      within(part()).getByRole("heading", {
        name: "What is added when it runs",
      }),
    ).toBeVisible();
    expect(part()).toHaveTextContent(
      "It is shown once every field of the declared answer says all it has to.",
    );
    expect(
      within(part()).queryByRole("button", {
        name: "What is added when it runs",
      }),
    ).toBeNull();
    expect(part().querySelector("pre")).toBeNull();
  });

  it("starts shut again after the declared answer becomes unavailable and returns", async () => {
    const { rerender } = render(<WhatIsAdded added={ADDED} />);
    await userEvent.click(disclosure());
    expect(part().querySelector("pre")?.textContent).toBe(ADDED);
    rerender(<WhatIsAdded added={undefined} />);
    expect(
      within(part()).queryByRole("button", {
        name: "What is added when it runs",
      }),
    ).toBeNull();
    expect(part().querySelector("pre")).toBeNull();
    rerender(<WhatIsAdded added={ADDED} />);
    expect(disclosure()).toHaveAttribute("aria-expanded", "false");
    expect(part().querySelector("pre")).toBeNull();
  });
});
