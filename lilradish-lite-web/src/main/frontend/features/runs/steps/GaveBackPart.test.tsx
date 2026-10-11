import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { GaveBackPart } from "./GaveBackPart";

describe("GaveBackPart", () => {
  it("switches each multiline output independently without turning links into active elements", () => {
    const { container } = render(
      <GaveBackPart
        gives={[
          { name: "body", kind: "text", lines: "several", mustBeGiven: true },
          {
            name: "references",
            kind: "text",
            lines: "several",
            mustBeGiven: true,
          },
        ]}
        gaveBack={{
          declares: "values",
          standing: [
            { field: "body", value: "# Imaginary claim\nKeep the box." },
            {
              field: "references",
              value: "## Imaginary sources\n[Guide](https://example.com/claim)",
            },
          ],
        }}
      />,
    );
    expect(container.querySelectorAll("h1,h2")).toHaveLength(2);
    expect(
      screen.getAllByRole("button", { name: "Show as written" }),
    ).toHaveLength(2);
    expect(container.querySelectorAll("h1,h2")).toHaveLength(2);
    expect(container.querySelectorAll("a,img")).toHaveLength(0);
    fireEvent.click(
      screen.getAllByRole("button", { name: "Show as written" })[0]!,
    );
    expect(container.querySelectorAll("h1,h2")).toHaveLength(1);
    expect(
      screen.getByRole("heading", { name: "Imaginary sources" }),
    ).toBeInTheDocument();
    expect(container.textContent).toContain("# Imaginary claim");
    fireEvent.click(screen.getByRole("button", { name: "Show as written" }));
    expect(container.querySelectorAll("h1,h2")).toHaveLength(0);
    expect(container.textContent).toContain(
      "[Guide](https://example.com/claim)",
    );
    screen
      .getAllByRole("button", { name: "Show formatted" })
      .forEach((button) => fireEvent.click(button));
    expect(container.querySelectorAll("h1,h2")).toHaveLength(2);
  });

  it("offers no switch for output declared to hold one line", () => {
    const { container } = render(
      <GaveBackPart
        gives={[
          { name: "code", kind: "text", lines: "one", mustBeGiven: true },
        ]}
        gaveBack={{
          declares: "values",
          standing: [{ field: "code", value: "**DEMO-CODE-17**" }],
        }}
      />,
    );
    expect(container.textContent).toContain("**DEMO-CODE-17**");
    expect(container.querySelectorAll("strong,a,img")).toHaveLength(0);
    expect(
      screen.queryByRole("button", { name: /Show as written|Show formatted/ }),
    ).toBeNull();
    expect(screen.getByRole("button", { name: "Copy" })).toBeInTheDocument();
  });
});
