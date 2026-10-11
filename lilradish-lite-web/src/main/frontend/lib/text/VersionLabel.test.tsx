import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { VersionLabel, versionLabelText } from "./VersionLabel";

const ISOLATE_START = String.fromCodePoint(0x2068);
const ISOLATE_END = String.fromCodePoint(0x2069);
const NAME_FOLLOWS = " \u00b7 ";

describe("a version's current name", () => {
  it("shows the numbered text alone when there is no name", () => {
    const { container } = render(<VersionLabel number={3} name={null} />);
    expect(container).toHaveTextContent("Version 3");
    expect(container.querySelector("bdi")).toBeNull();
  });

  it("draws the exact current name after its number, quieter and isolated, including supplementary characters", () => {
    const { container, rerender } = render(
      <VersionLabel
        number={3}
        name="نسخة 📦"
        numberLabel="Mooncrate · Version 3"
      />,
    );
    const name = screen.getByText("نسخة 📦");
    expect(name.tagName).toBe("BDI");
    expect(name).toHaveAttribute("dir", "auto");
    expect(container).toHaveTextContent("Mooncrate · Version 3 · نسخة 📦");
    rerender(
      <VersionLabel
        number={3}
        name="Current 📦"
        numberLabel="Mooncrate · Version 3"
      />,
    );
    expect(screen.queryByText("نسخة 📦")).toBeNull();
    expect(container).toHaveTextContent("Mooncrate · Version 3 · Current 📦");
    rerender(
      <VersionLabel
        number={3}
        name={null}
        numberLabel="Mooncrate · Version 3"
      />,
    );
    expect(container).toHaveTextContent("Mooncrate · Version 3");
    expect(container.querySelector("bdi")).toBeNull();
  });

  it.each([
    ["no name and its own number", 4, null, undefined],
    ["a name and its own number", 4, "Spring sort", undefined],
    ["no name and the place's words", 2, null, "Regions \u00b7 Version 2"],
    [
      "a name and the place's words",
      2,
      "Spring sort",
      "Regions \u00b7 Version 2",
    ],
  ] as const)(
    "says as plain text, with %s, the very words it draws, its name alone isolated",
    (_case, number, name, numberLabel) => {
      const { container } = render(
        <VersionLabel number={number} name={name} numberLabel={numberLabel} />,
      );

      const text = versionLabelText({ number, name, numberLabel });

      const lead = numberLabel ?? `Version ${number}`;
      expect(text).toBe(
        name === null
          ? lead
          : `${lead}${NAME_FOLLOWS}${ISOLATE_START}${name}${ISOLATE_END}`,
      );
      expect(container.textContent).toBe(
        text.replaceAll(ISOLATE_START, "").replaceAll(ISOLATE_END, ""),
      );
      expect(container.textContent).not.toContain(ISOLATE_START);
    },
  );

  it.each([
    ["no name", null],
    ["a name", "Spring sort"],
  ] as const)(
    "leads with the entry it is a version of, isolated, where the place has not said it, with %s",
    (_case, name) => {
      const { container } = render(
        <VersionLabel entry="Triage" number={4} name={name} />,
      );

      const text = versionLabelText({ entry: "Triage", number: 4, name });

      const lead = `${ISOLATE_START}Triage${ISOLATE_END}${NAME_FOLLOWS}Version 4`;
      expect(text).toBe(
        name === null
          ? lead
          : `${lead}${NAME_FOLLOWS}${ISOLATE_START}${name}${ISOLATE_END}`,
      );
      expect(container.textContent).toBe(
        text.replaceAll(ISOLATE_START, "").replaceAll(ISOLATE_END, ""),
      );
      const entry = container.querySelector("bdi")!;
      expect([entry.textContent, entry.getAttribute("dir")]).toEqual([
        "Triage",
        "auto",
      ]);
      expect(container.textContent).not.toContain(ISOLATE_START);
      expect(versionLabelText({ number: 4, name })).not.toContain("Triage");
    },
  );

  it("leads the place's own number words with the entry where both are given, drawing and saying the same", () => {
    const { container } = render(
      <VersionLabel
        entry="Triage"
        number={4}
        name="Spring sort"
        numberLabel="Release 4"
      />,
    );

    const text = versionLabelText({
      entry: "Triage",
      number: 4,
      name: "Spring sort",
      numberLabel: "Release 4",
    });

    expect(text).toBe(
      `${ISOLATE_START}Triage${ISOLATE_END}${NAME_FOLLOWS}Release 4${NAME_FOLLOWS}${ISOLATE_START}Spring sort${ISOLATE_END}`,
    );
    expect(container.textContent).toBe(
      `Triage${NAME_FOLLOWS}Release 4${NAME_FOLLOWS}Spring sort`,
    );
    expect(container.textContent).not.toContain("Version 4");
    expect(
      [...container.querySelectorAll("bdi")].map((each) => each.textContent),
    ).toEqual(["Triage", "Spring sort"]);
  });
});
