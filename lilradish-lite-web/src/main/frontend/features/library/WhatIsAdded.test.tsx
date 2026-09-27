import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { isolatedInText } from "../../lib/direction/isolated";
import { WhatIsAdded } from "./WhatIsAdded";

function part(): HTMLElement {
  return screen.getByRole("region", { name: "What is added when it runs" });
}

describe("WhatIsAdded", () => {
  /**
   * Each field as a model is told it: its name, what it is, its limits and whether it must be given, a term's
   * terms, and a confidence asked.
   */
  it("draws what a model is told of each field given back, the fields another holds under it", () => {
    render(
      <WhatIsAdded
        added={[
          {
            name: "category",
            kind: "term",
            many: true,
            most: 2,
            mustBeGiven: true,
            terms: [
              { term: "Billing", meaning: "A charge is what is disputed." },
              { term: "Delivery", meaning: "It came late." },
            ],
            note: "Choose what is to be put right.",
            confidence: true,
          },
          {
            name: "details",
            kind: "fields",
            many: false,
            mustBeGiven: false,
            confidence: false,
            fields: [
              {
                name: "product",
                kind: "text",
                longest: 128,
                many: false,
                mustBeGiven: true,
                confidence: false,
              },
            ],
          },
        ]}
      />,
    );

    const told = within(part()).getByRole("list", {
      name: "What is added when it runs",
    });
    const [category, details] = Array.from(told.children) as HTMLElement[];
    const [product] = within(details!).getAllByRole("listitem");
    expect(told.children).toHaveLength(2);
    expect(category).toHaveTextContent(
      "A term, Holds many, at most 2, Must be given",
    );
    expect(category).not.toHaveTextContent("May be left empty");
    expect(
      within(category!)
        .getAllByRole("listitem")
        .map((each) => each.textContent),
    ).toEqual([
      `${isolatedInText("Billing")}: ${isolatedInText("A charge is what is disputed.")}`,
      `${isolatedInText("Delivery")}: ${isolatedInText("It came late.")}`,
    ]);
    expect(category).toHaveTextContent(
      `On choosing among them: ${isolatedInText("Choose what is to be put right.")}`,
    );
    expect(within(category!).getAllByRole("list")).toHaveLength(1);
    expect(category).toHaveTextContent("Asked how sure it is.");
    expect(details).toHaveTextContent(
      "Fields of its own, Holds one, May be left empty",
    );
    expect(details).not.toHaveTextContent("Asked how sure it is.");
    expect(details).toContainElement(product!);
    expect(product).toHaveTextContent(
      "Text, At most 128 characters, Holds one, Must be given",
    );
  });

  it("says what is added waits on a whole declared answer, drawing nothing of one", () => {
    render(<WhatIsAdded added={undefined} />);

    expect(part()).toHaveTextContent(
      "It is shown once every field of the declared answer says all it has to.",
    );
    expect(within(part()).queryByRole("list")).toBeNull();
  });
});
