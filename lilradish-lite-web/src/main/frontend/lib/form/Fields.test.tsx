import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { Field, Fields } from "./Fields";

describe("Fields", () => {
  it("is the description list the pairs belong in", () => {
    const { container } = render(
      <Fields>
        <Field label="Outcome">Completed</Field>
      </Fields>,
    );

    const list = container.querySelector("dl");
    expect(list).not.toBeNull();
    expect(container.querySelector("ul")).toBeNull();
  });

  it("holds every pair it is given, in the order they were given", () => {
    const { container } = render(
      <Fields>
        <Field label="Outcome">Completed</Field>
        <Field label="Tokens">1200</Field>
      </Fields>,
    );

    const terms = [...container.querySelectorAll("dt")].map(
      (term) => term.textContent,
    );
    const values = [...container.querySelectorAll("dd")].map(
      (value) => value.textContent,
    );

    expect(terms).toEqual(["Outcome", "Tokens"]);
    expect(values).toEqual(["Completed", "1200"]);
  });

  it("breaks a value anywhere rather than let one unbroken identifier overflow, and leaves labels whole", () => {
    render(
      <Fields>
        <Field label="Subject">00000002-0000-4000-8000-000000000130</Field>
      </Fields>,
    );

    expect(
      getComputedStyle(screen.getByText("00000002-0000-4000-8000-000000000130"))
        .overflowWrap,
    ).toBe("anywhere");
    expect(getComputedStyle(screen.getByText("Subject")).overflowWrap).not.toBe(
      "anywhere",
    );
  });
});

describe("Field", () => {
  it("puts the label in a term and the value in a definition", () => {
    render(
      <Fields>
        <Field label="Budget">80% used</Field>
      </Fields>,
    );

    expect(screen.getByText("Budget").tagName).toBe("DT");
    expect(screen.getByText("80% used").tagName).toBe("DD");
  });

  it("wraps the pair in nothing, so both cells reach the grid", () => {
    const { container } = render(
      <Fields>
        <Field label="Budget">80% used</Field>
      </Fields>,
    );

    const list = container.querySelector("dl");
    const children = [...(list?.children ?? [])].map((child) => child.tagName);

    expect(children).toEqual(["DT", "DD"]);
  });

  it("takes an element as the label, not only a string", () => {
    render(
      <Fields>
        <Field label={<abbr title="Time to first token">TTFT</abbr>}>
          120ms
        </Field>
      </Fields>,
    );

    expect(screen.getByTitle("Time to first token").tagName).toBe("ABBR");
  });
});
