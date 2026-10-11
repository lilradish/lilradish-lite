import { ThemeProvider } from "@mui/material/styles";
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { theme } from "../theme/theme";

import {
  laysPairsBeside,
  styledClassOf,
  writtenRules,
} from "../../testutil/writtenRules";
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

  it.each([
    ["not where asked to stack", true, false],
    ["where left to the screen's width", undefined, true],
  ] as const)(
    "lays each label beside its value on a wider screen %s",
    (_case, stacked, beside) => {
      const { container } = render(
        <Fields stacked={stacked}>
          <Field label="Outcome">Completed</Field>
        </Fields>,
      );

      expect(laysPairsBeside(container.querySelector("dl")!)).toBe(beside);
    },
  );

  it("lays a side panel's labels in a column 112px wide beside their values at every width, 14 between rows and 12 between columns, with no margin of its own", () => {
    const { container } = render(
      <ThemeProvider theme={theme}>
        <Fields inPanel>
          <Field label="Outcome">Completed</Field>
          <Field label="Subject">00000002-0000-4000-8000-000000000130</Field>
        </Fields>
      </ThemeProvider>,
    );
    const list = container.querySelector("dl")!;
    const drawn = getComputedStyle(list);

    expect([
      drawn.display,
      drawn.gridTemplateColumns,
      drawn.alignItems,
      drawn.margin,
    ]).toEqual(["grid", "112px minmax(0, 1fr)", "baseline", "0px"]);
    expect(writtenRules()).toContain(
      `.${styledClassOf(list)}{display:grid;margin:0px;`,
    );
    expect(writtenRules()).toMatch(
      new RegExp(`\\.${styledClassOf(list)}\\{[^}]*gap:14px 12px;`),
    );
    expect(writtenRules()).not.toMatch(
      new RegExp(`@media[^{]*\\{\\.${styledClassOf(list)}\\{`),
    );
    expect(
      getComputedStyle(screen.getByText("00000002-0000-4000-8000-000000000130"))
        .overflowWrap,
    ).toBe("anywhere");
  });

  it("draws a side panel's labels quieter and smaller than their values, each line 24 high, and wraps a long label inside its column", () => {
    render(
      <ThemeProvider theme={theme}>
        <Fields inPanel>
          <Field label="Approved by the person who last reviewed it">
            Avo Lumerace
          </Field>
        </Fields>
      </ThemeProvider>,
    );
    const label = getComputedStyle(
      screen.getByText("Approved by the person who last reviewed it"),
    );
    const value = getComputedStyle(screen.getByText("Avo Lumerace"));

    expect([
      label.fontSize,
      label.lineHeight,
      label.fontWeight,
      label.color,
      label.overflowWrap,
      label.whiteSpace,
    ]).toEqual(["13px", "24px", "600", "rgb(88, 102, 108)", "anywhere", ""]);
    expect([value.fontSize, value.lineHeight, value.fontWeight]).toEqual([
      "14px",
      "24px",
      "400",
    ]);
    expect(value.color).not.toBe("rgb(88, 102, 108)");
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

  it("gives two properties a row only when their own container has room", () => {
    const { container } = render(
      <Fields properties>
        <Field label="Outcome">Completed</Field>
        <Field label="Tokens">1200</Field>
      </Fields>,
    );
    const list = container.querySelector("dl")!;
    const className = styledClassOf(list);

    expect(getComputedStyle(list.parentElement!).containerType).toBe(
      "inline-size",
    );
    expect(getComputedStyle(list).gridTemplateColumns).toBe(
      "112px minmax(0, 1fr)",
    );
    expect(writtenRules()).toContain(
      `@container (min-width: 560px){.${className}{grid-template-columns:112px minmax(0, 1fr) 112px minmax(0, 1fr);`,
    );
    expect([...list.children].map((cell) => cell.textContent)).toEqual([
      "Outcome",
      "Completed",
      "Tokens",
      "1200",
    ]);
  });

  it("keeps a property's label 12 from its own value and 24 from the value before it, 14 between rows", () => {
    const { container } = render(
      <Fields properties>
        <Field label="Outcome">Completed</Field>
        <Field label="Tokens">1200</Field>
      </Fields>,
    );
    const list = container.querySelector("dl")!;
    const value = getComputedStyle(screen.getByText("Completed"));

    expect(writtenRules()).toMatch(
      new RegExp(`\\.${styledClassOf(list)}\\{[^}]*gap:14px 24px;`),
    );
    expect([
      value.marginLeft,
      value.marginRight,
      value.marginTop,
      value.marginBottom,
    ]).toEqual(["-12px", "0px", "0px", "0px"]);
    expect(getComputedStyle(screen.getByText("Outcome")).marginLeft).not.toBe(
      "-12px",
    );
  });

  it("wraps a long property label inside its 112px column rather than widen the column at either width", () => {
    const { container } = render(
      <Fields properties>
        <Field label="Approved by the person who last reviewed it">
          Avo Lumerace
        </Field>
      </Fields>,
    );
    const className = styledClassOf(container.querySelector("dl")!);
    const ownRules = writtenRules()
      .split("}")
      .filter((rule) => rule.includes(`.${className}`));

    expect(
      getComputedStyle(
        screen.getByText("Approved by the person who last reviewed it"),
      ).overflowWrap,
    ).toBe("anywhere");
    expect(
      ownRules.filter((rule) => rule.includes("grid-template-columns:")),
    ).toHaveLength(2);
    expect(
      ownRules.filter((rule) =>
        /grid-template-columns:112px minmax\(0, 1fr\)/.test(rule),
      ),
    ).toHaveLength(2);
    expect(ownRules.filter((rule) => rule.includes("max-content"))).toEqual([]);
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

  it("gives a pair asked to go across a row of its own where properties lie two to a row, its label in the first column and its value to the row's end", () => {
    const { container } = render(
      <Fields properties>
        <Field label="Outcome">Completed</Field>
        <Field label="Instructions" across>
          Summarise the ticket in two sentences.
        </Field>
        <Field label="Tokens">1200</Field>
      </Fields>,
    );
    const list = container.querySelector("dl")!;
    const className = styledClassOf(list);
    const wide = writtenRules().split(
      `@container (min-width: 560px){.${className}{`,
    );

    expect(wide).toHaveLength(2);
    expect(wide[0]).not.toContain(`.${className} dt[data-across]`);
    expect(wide[1]!.split("}}")[0]).toMatch(
      new RegExp(
        `\\.${className} dt\\[data-across\\]\\{grid-column:1;\\}\\.${className} dt\\[data-across\\]\\+dd\\{grid-column:2/-1;`,
      ),
    );
    expect(
      [...list.children].map((cell) => [
        cell.tagName,
        cell.hasAttribute("data-across"),
      ]),
    ).toEqual([
      ["DT", false],
      ["DD", false],
      ["DT", true],
      ["DD", false],
      ["DT", false],
      ["DD", false],
    ]);
  });

  it.each([
    ["a side panel's facts", { inPanel: true }],
    ["a stacked list", { stacked: true }],
    ["a list left to the screen's width", {}],
  ] as const)(
    "changes nothing for a pair asked to go across in %s",
    (_case, kind) => {
      const { container } = render(
        <ThemeProvider theme={theme}>
          <Fields {...kind}>
            <Field label="Instructions" across>
              Summarise the ticket in two sentences.
            </Field>
          </Fields>
        </ThemeProvider>,
      );
      const list = container.querySelector("dl")!;

      expect([...list.children].map((cell) => cell.tagName)).toEqual([
        "DT",
        "DD",
      ]);
      expect(writtenRules()).not.toContain(
        `.${styledClassOf(list)} dt[data-across]`,
      );
    },
  );
});
