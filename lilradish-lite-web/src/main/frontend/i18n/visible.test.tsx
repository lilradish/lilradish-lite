import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { say } from "./app";
import { visible } from "./visible";

describe("visible", () => {
  it("keeps a sentence without values on the same text path", () => {
    const words = visible("workflow.steps");

    expect(words).toBe(say("workflow.steps"));
    expect(typeof words).toBe("string");
  });

  it.each([
    [
      "workflow.defaultName",
      "workflow.unnamedVisible",
      "workflow.unnamed",
      "Step",
    ],
    [
      "declaration.defaultName",
      "declaration.unnamedVisible",
      "declaration.unnamed",
      "Field",
    ],
  ] as const)(
    "keeps %s and its hint equal to the complete sentence",
    (defaultId, visibleId, wholeId, kind) => {
      const position = 3;
      const { container } = render(
        <p>
          {visible(visibleId, {
            name: <bdi>{say(defaultId, { position })}</bdi>,
            hint: <i>{say("name.notNamed")}</i>,
          })}
        </p>,
      );

      expect(container.textContent).toBe(say(wholeId, { position }));
      expect(screen.getByText(`${kind} 3`).tagName).toBe("BDI");
      expect(screen.getByText("not named yet").tagName).toBe("I");
      expect(container.textContent).not.toContain("[object Object]");
    },
  );

  it.each([
    [1, "stands always or waits"],
    [2, "stand always or wait"],
  ])("chooses a plural with %d alongside a node", (count, verb) => {
    const fields = "Demo Sample";
    const { container } = render(
      <p>
        {visible("contentProblem.search_standing_missing", {
          count,
          fields: <bdi>{fields}</bdi>,
        })}
      </p>,
    );

    expect(container.textContent).toBe(
      say("contentProblem.search_standing_missing", { count, fields }),
    );
    expect(container.textContent).toBe(
      `It does not yet say whether Demo Sample ${verb} on a review.`,
    );
    expect(screen.getByText(fields).tagName).toBe("BDI");
    expect(container.textContent).not.toContain("{count");
  });

  it.each([
    [0, "Nothing"],
    [1, "One value"],
    [2048, "2,048 values"],
  ])("formats a numeric plural of %d as %s", (count, expected) => {
    expect(visible("workflow.givesCount", { count })).toBe(expected);
    expect(visible("workflow.givesCount", { count })).toBe(
      say("workflow.givesCount", { count }),
    );
  });

  it("renders arbitrary names as text inside the element the caller supplied", () => {
    const name = 'Demo Sample <img src=x onerror="alert(1)"> مرحبا';
    const { container } = render(
      <p>
        {visible("pins.openNewest", {
          label: <bdi>{name}</bdi>,
        })}
      </p>,
    );

    expect(container.textContent).toBe(say("pins.openNewest", { label: name }));
    expect(screen.getByText(name).tagName).toBe("BDI");
    expect(container.querySelector("img")).toBeNull();
  });
});
