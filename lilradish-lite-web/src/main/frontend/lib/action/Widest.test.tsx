import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { Widest } from "./Widest";

describe("Widest", () => {
  it("lays every label in one cell while exposing only the shown label", () => {
    const { rerender } = render(
      <button>
        <Widest labels={["Copy", "Copied", "Copy step"]} shown={0} />
      </button>,
    );
    const button = screen.getByRole("button", { name: "Copy" });
    const labels = [...button.querySelectorAll("span > span")];
    expect(labels).toHaveLength(3);
    expect(labels.map((label) => getComputedStyle(label).gridArea)).toEqual([
      "1/1",
      "1/1",
      "1/1",
    ]);
    expect(screen.getByText("Copy step")).not.toBeVisible();
    expect(screen.getByText("Copy step")).toHaveAttribute(
      "aria-hidden",
      "true",
    );
    rerender(
      <button>
        <Widest labels={["Copy", "Copied", "Copy step"]} shown={1} />
      </button>,
    );
    expect(screen.getByRole("button", { name: "Copied" })).toBe(button);
    expect(screen.getByText("Copy")).not.toBeVisible();
    expect(screen.getByText("Copied")).not.toHaveAttribute("aria-hidden");
  });
});
