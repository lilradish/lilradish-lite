import Box from "@mui/material/Box";
import MenuItem from "@mui/material/MenuItem";
import { ThemeProvider } from "@mui/material/styles";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactNode } from "react";
import { describe, expect, it } from "vitest";

import { theme } from "../theme/theme";
import {
  BESIDE_LABELLED_SX,
  LabelledTextField,
  LINES_BESIDE_LABELLED_SX,
} from "./LabelledTextField";

/** `theme.palette.text.secondary`, as a resolved colour is reported back. */
const SUPPORTING_TEXT_COLOUR = "rgb(88, 102, 108)";

/** `theme.palette.error.main`, as a resolved colour is reported back. */
const ERROR_COLOUR = "rgb(161, 38, 31)";

/** `theme.palette.text.disabled`, as a resolved colour is reported back. */
const DISABLED_COLOUR = "rgb(128, 143, 150)";

function drawn(children: ReactNode) {
  render(<ThemeProvider theme={theme}>{children}</ThemeProvider>);
}

function fieldOf(box: HTMLElement): HTMLElement {
  return box.closest<HTMLElement>(".MuiFormControl-root")!;
}

describe("LabelledTextField", () => {
  it.each([false, true])(
    "places a helper between label and control and merges existing descriptions, select %s",
    (select) => {
      drawn(
        <>
          <p id="held-field">The field cannot be saved yet.</p>
          <LabelledTextField
            label="Title"
            select={select}
            value=""
            helperBefore
            helperText="Choose a title."
            describedBy="held-field"
          >
            {select ? <MenuItem value="">None</MenuItem> : null}
          </LabelledTextField>
        </>,
      );
      const box = screen.getByRole(select ? "combobox" : "textbox", {
        name: "Title",
      });
      const field = fieldOf(box);
      const [label, helper, control] = [...field.children];
      expect(label).toHaveTextContent("Title");
      expect(helper).toHaveTextContent("Choose a title.");
      expect(control).toContainElement(box);
      expect(box).toHaveAccessibleDescription(
        "The field cannot be saved yet. Choose a title.",
      );
      expect(
        box
          .getAttribute("aria-describedby")!
          .split(" ")
          .every((id) => document.getElementById(id) !== null),
      ).toBe(true);
    },
  );
  it("stands its label above a box it names, with no label floating inside the box", async () => {
    drawn(
      <LabelledTextField
        label="How many attempts"
        value=""
        onChange={() => {}}
      />,
    );
    const box = screen.getByRole("textbox", { name: "How many attempts" });
    const field = fieldOf(box);
    const label = field.firstElementChild!;

    expect(label.tagName).toBe("LABEL");
    expect(label.textContent).toBe("How many attempts");
    expect(label).toHaveAttribute("for", box.id);
    expect(field.querySelector(".MuiInputLabel-root")).toBeNull();
    expect(box.closest(".MuiInputBase-root")).toHaveClass(
      "MuiInputBase-fullWidth",
    );
    expect(getComputedStyle(label).display).toBe("block");

    await userEvent.click(label);

    expect(box).toHaveFocus();
  });

  it("names a choice by the label above it alone and shows the words of an empty choice rather than nothing", () => {
    drawn(
      <LabelledTextField
        select
        label="Who may review"
        value=""
        onChange={() => {}}
      >
        <MenuItem value="">Not chosen yet</MenuItem>
        <MenuItem value="person">A person</MenuItem>
      </LabelledTextField>,
    );
    const choice = screen.getByRole("combobox", { name: "Who may review" });
    const label = document.getElementById(
      choice.getAttribute("aria-labelledby")!,
    )!;

    expect(label.tagName).toBe("SPAN");
    expect(label.textContent).toBe("Who may review");
    expect(label.nextElementSibling).toBe(choice.closest(".MuiInputBase-root"));
    expect(choice).not.toHaveAttribute("aria-label");
    expect(choice).toHaveTextContent("Not chosen yet");
    expect(choice).not.toHaveTextContent("A person");
  });

  it("sets a control with no label of its own down by exactly the label's line and gap, standing as high as a small box and centring on it", () => {
    drawn(
      <>
        <LabelledTextField
          label="Term"
          size="small"
          value=""
          onChange={() => {}}
        />
        <Box sx={BESIDE_LABELLED_SX}>Beside</Box>
        <Box sx={LINES_BESIDE_LABELLED_SX}>Lines</Box>
      </>,
    );
    const label = screen.getByText("Term");
    const beside = screen.getByText("Beside");

    const above = getComputedStyle(label);
    expect([above.lineHeight, above.marginBottom]).toEqual(["18px", "5px"]);
    expect([
      getComputedStyle(beside).marginTop,
      getComputedStyle(beside).minHeight,
      getComputedStyle(beside).alignItems,
    ]).toEqual(["23px", "37.125px", "center"]);
    expect(getComputedStyle(screen.getByText("Lines")).marginTop).toBe(
      "31.5625px",
    );
  });

  it.each([
    [
      "as wide as its box or its label, whichever is wider, and never wider than its room",
      false,
      "fit-content",
      "100%",
    ],
    ["across all the room it has where asked to fill it", true, "auto", "none"],
  ] as const)(
    "stands a labelled field %s, the place's own settings going to label and box together",
    (_case, fullWidth, width, widest) => {
      drawn(
        <LabelledTextField
          label="Search with"
          value=""
          onChange={() => {}}
          fullWidth={fullWidth}
          sx={{ flexGrow: 1 }}
        />,
      );
      const box = screen.getByRole("textbox", { name: "Search with" });
      const place = getComputedStyle(fieldOf(box));
      const boxRoot = box.closest(".MuiInputBase-root")!;

      expect(place.width).toBe(width);
      expect(place.maxWidth).toBe(widest);
      expect(place.minWidth).toBe("0px");
      expect(place.flexGrow).toBe("1");
      expect(getComputedStyle(boxRoot).flexGrow).not.toBe("1");
      expect(boxRoot).toHaveClass("MuiInputBase-fullWidth");
    },
  );

  it.each([
    ["in error", true, ERROR_COLOUR, SUPPORTING_TEXT_COLOUR],
    ["not in error", false, SUPPORTING_TEXT_COLOUR, ERROR_COLOUR],
  ] as const)(
    "draws the label and words beneath of a field %s in its own colour, never in the other's",
    (_case, error, colour, other) => {
      drawn(
        <LabelledTextField
          label="Usage limit"
          value="01"
          onChange={() => {}}
          error={error}
          helperText="Write a whole number."
        />,
      );
      const box = screen.getByRole("textbox", { name: "Usage limit" });
      const label = fieldOf(box).firstElementChild!;

      expect(getComputedStyle(label).color).toBe(colour);
      expect(getComputedStyle(label).color).not.toBe(other);
      expect(box).toHaveAttribute("aria-invalid", String(error));
      expect(box).toHaveAccessibleDescription("Write a whole number.");
    },
  );

  it("keeps its label's colour while its box holds the keyboard", async () => {
    drawn(<LabelledTextField label="Term" value="" onChange={() => {}} />);
    const box = screen.getByRole("textbox", { name: "Term" });
    const label = fieldOf(box).firstElementChild!;

    await userEvent.click(box);

    expect(label).toHaveClass("Mui-focused");
    expect(getComputedStyle(label).color).toBe(SUPPORTING_TEXT_COLOUR);
  });

  it("draws the label of a field that may not be changed in the disabled colour, its box unavailable", () => {
    drawn(
      <LabelledTextField label="Term" value="" onChange={() => {}} disabled />,
    );
    const box = screen.getByRole("textbox", { name: "Term" });
    const label = fieldOf(box).firstElementChild!;

    expect(getComputedStyle(label).color).toBe(DISABLED_COLOUR);
    expect(getComputedStyle(label).color).not.toBe(SUPPORTING_TEXT_COLOUR);
    expect(box).toBeDisabled();
  });

  it("draws a field with no label as the plain box it is, with nothing standing above it", () => {
    drawn(
      <LabelledTextField
        value=""
        onChange={() => {}}
        slotProps={{ htmlInput: { "aria-label": "Term 1" } }}
      />,
    );
    const box = screen.getByRole("textbox", { name: "Term 1" });
    const field = fieldOf(box);

    expect(field.firstElementChild).toBe(box.closest(".MuiInputBase-root"));
    expect(document.querySelector("label")).toBeNull();
    expect(field).not.toHaveClass("MuiFormControl-fullWidth");
  });
});
