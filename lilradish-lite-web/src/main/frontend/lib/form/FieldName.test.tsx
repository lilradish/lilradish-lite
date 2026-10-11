import { ThemeProvider } from "@mui/material/styles";
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { theme } from "../theme/theme";
import { FieldName } from "./FieldName";

describe("FieldName", () => {
  it("keeps both separator spaces in the control's accessible name and gives only the dot the secondary ink and normal weight", () => {
    render(
      <ThemeProvider theme={theme}>
        <button style={{ fontWeight: 600 }}>
          <FieldName name="summary" label="Summary" />
        </button>
      </ThemeProvider>,
    );
    const button = screen.getByRole("button", {
      name: "Summary \u00b7 summary",
    });
    expect(button).toHaveTextContent("Summary \u00b7 summary");
    const dot = button.querySelector("span")!;
    expect(dot.textContent).toBe("\u00b7");
    expect(dot).toHaveStyle({
      color: "rgb(88, 102, 108)",
      fontWeight: "400",
    });
    expect(getComputedStyle(button).fontWeight).toBe("600");
  });
  it.each([
    [undefined, "topic"],
    ["topic", "topic"],
    ["Topic", "Topic · topic"],
  ] as const)(
    "reads label %s and the actual binding name once in one place",
    (label, words) => {
      const { container } = render(
        <ThemeProvider theme={theme}>
          <FieldName name="topic" label={label} />
        </ThemeProvider>,
      );
      expect(container.textContent).toBe(words);
      const key = container.querySelector("code");
      if (label === "Topic") {
        expect(key?.textContent).toBe("topic");
        expect(key).toHaveStyle({ color: theme.palette.text.secondary });
        expect(key!.previousElementSibling).toHaveTextContent("\u00b7");
        expect(key!.previousElementSibling).toHaveStyle({
          color: "rgb(88, 102, 108)",
          fontWeight: "400",
        });
      } else expect(key).toBeNull();
      expect(container.querySelector("bdi")?.textContent).toBe(
        label ?? "topic",
      );
    },
  );

  it("keeps a compound item title and its binding name as separate facts without interpreting authored markup", () => {
    const { container } = render(
      <FieldName name="evidence" label="<img>">
        Evidence 2
      </FieldName>,
    );
    expect(container.textContent).toBe("Evidence 2 · evidence");
    expect(container.querySelector("img")).toBeNull();
  });

  it.each([undefined, ""])(
    "reads an unlabelled name once, without a second key, label %s",
    (label) => {
      const { container } = render(
        <ThemeProvider theme={theme}>
          <FieldName name="a_b" label={label} />
        </ThemeProvider>,
      );
      expect(container.textContent).toBe("a b");
      expect(container.querySelector("code")).toBeNull();
    },
  );

  it("keeps an author's unlabelled binding spelling once", () => {
    const { container } = render(
      <FieldName name="first_name" context="author" />,
    );
    expect(container.textContent).toBe("first_name");
    expect(container.querySelector("code")).toBeNull();
  });

  it("removes only the outer isolation when comparing a supplied reading", () => {
    const { container, rerender } = render(
      <FieldName name="topic" label="Topic">
        {"\u2068topic\u2069"}
      </FieldName>,
    );
    expect(container.querySelector("code")).toBeNull();
    expect(container.textContent).toBe("\u2068topic\u2069");
    rerender(
      <FieldName name="topic" label="Topic">
        {"\u2068To\u2068pic\u2069\u2069"}
      </FieldName>,
    );
    expect(container.querySelector("code")?.textContent).toBe("topic");
    expect(container.querySelector("bdi")?.textContent).toBe(
      "\u2068To\u2068pic\u2069\u2069",
    );
  });
});
