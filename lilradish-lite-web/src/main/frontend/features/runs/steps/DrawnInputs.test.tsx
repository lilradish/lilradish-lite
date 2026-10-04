import { ThemeProvider } from "@mui/material/styles";
import { render, screen, within } from "@testing-library/react";
import Markdown from "react-markdown";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { ReadField } from "../../../api/filling";
import type { WentIn } from "../../../api/groups/{groupId}/runs/{runId}/steps";
import { theme } from "../../../lib/theme/theme";
import type { Declaring } from "./declared";
import { DrawnInputs, type Heading } from "./DrawnInputs";

vi.mock("react-markdown", async (importOriginal) => {
  const actual = await importOriginal<typeof import("react-markdown")>();
  return { ...actual, default: vi.fn(actual.default) };
});

const TAKES: readonly ReadField[] = [
  { name: "title", label: "Title", kind: "text", mustBeGiven: true },
  { name: "body", label: "Body", kind: "text", mustBeGiven: true },
  { name: "url", label: "Address", kind: "text", mustBeGiven: true },
  {
    name: "refs",
    label: "References",
    kind: "text",
    most: 5,
    mustBeGiven: true,
  },
  {
    name: "ship_to",
    label: "Ship to",
    kind: "fields",
    mustBeGiven: true,
    fields: [
      { name: "street", label: "Street", kind: "text", mustBeGiven: true },
      { name: "city", label: "City", kind: "text", mustBeGiven: true },
    ],
  },
  {
    name: "shipment",
    label: "Shipment",
    kind: "fields",
    mustBeGiven: true,
    fields: [
      { name: "carrier", label: "Carrier", kind: "text", mustBeGiven: true },
      { name: "due", label: "Due", kind: "date", mustBeGiven: true },
    ],
  },
  { name: "notes", label: "Notes", kind: "text", mustBeGiven: false },
  { name: "due", label: "Due", kind: "date", mustBeGiven: true },
  {
    name: "priority",
    label: "Priority",
    kind: "term",
    mustBeGiven: true,
    terms: { terms: [{ term: "P1", meaning: "Drop everything" }] },
  },
];

const FROM = { kind: "run_input", path: "ticket" };

const DECLARING: Declaring = {
  runId: "00000008-0000-4000-8000-000000000e01",
  declarations: new Map(),
  runVersionId: "00000007-0000-4000-8000-000000000e01",
  steps: undefined,
};

const TITLE = "Printer on fire";

function given(title: string = TITLE): WentIn[] {
  return [
    { input: "title", from: FROM, value: title },
    { input: "body", from: FROM, value: "It smokes.\nIt beeps." },
    { input: "url", from: FROM, value: "https://evil.example/x" },
  ];
}

const SHIPPED_TO: readonly WentIn[] = [
  { input: "ship_to.street", from: FROM, value: "1 High St" },
  { input: "ship_to.city", from: FROM, value: "Leeds" },
];

const ELEMENTS_A_VALUE_COULD_MAKE =
  "a, img, strong, em, h1, h2, ul, ol, li, b, code, pre, blockquote";

const TURNS_ORDER =
  "A character that would turn the order text is shown in is here, drawn instead of obeyed: U+202E.";

const WHAT_ELSE = "What else went in";

const NEW_TAB = "opens in a new tab";

const NOT_LAID_OUT =
  "How this question is drawn could not be laid out here, so what it takes is listed field by field.";

const BLOCKS_IN_A_LINE =
  "h1 :is(p, div, ul, dl), h2 :is(p, div, ul, dl), h3 :is(p, div, ul, dl), h4 :is(p, div, ul, dl), h5 :is(p, div, ul, dl), h6 :is(p, div, ul, dl), a :is(p, div, ul, dl), strong :is(p, div, ul, dl), code :is(p, div, ul, dl), p :is(p, div, ul, dl)";

const isolated = (words: string) =>
  `${String.fromCodePoint(0x2068)}${words}${String.fromCodePoint(0x2069)}`;

function element(
  drawing: string,
  wentIn: readonly WentIn[],
  under: Heading,
  takes: readonly ReadField[] = TAKES,
) {
  return (
    <ThemeProvider theme={theme}>
      <DrawnInputs
        drawing={drawing}
        wentIn={wentIn}
        takes={takes}
        declaring={DECLARING}
        under={under}
      />
    </ThemeProvider>
  );
}

function drawn(
  drawing: string,
  wentIn: readonly WentIn[] = given(),
  under: Heading = "h3",
  takes: readonly ReadField[] = TAKES,
): HTMLElement {
  return render(element(drawing, wentIn, under, takes)).container;
}

function laidOutText(container: HTMLElement): string | null {
  const drawing = container.cloneNode(true) as HTMLElement;
  drawing.querySelector("aside")?.remove();
  return drawing.textContent;
}

/** Laying out a drawing that opens with `Unlaid` throws, as one too deep for the stack would. */
async function unlaidThrowing(): Promise<void> {
  const { default: laidOut } =
    await vi.importActual<typeof import("react-markdown")>("react-markdown");
  vi.mocked(Markdown).mockImplementation((options) => {
    if (String(options.children).startsWith("Unlaid")) {
      throw new RangeError("Maximum call stack size exceeded");
    }
    return laidOut(options);
  });
}

describe("DrawnInputs", () => {
  beforeEach(() => {
    vi.mocked(Markdown).mockReset();
  });

  it("lays out what went in as the drawing writes it, each value where its name stands, and lists nothing field by field", () => {
    const container = drawn(
      "# Report\n\n**Title:** {{title}}\n\n{{body}}\n\n{{url}}",
    );

    expect(screen.getByRole("heading", { level: 4 })).toHaveTextContent(
      /^Report$/,
    );
    expect(container.querySelector("strong")?.textContent).toBe("Title:");
    expect(laidOutText(container)).toBe(
      `Report\nTitle: ${TITLE}\nIt smokes.\nIt beeps.\nhttps://evil.example/x`,
    );
    expect(container.querySelector("dl")).toBeNull();
    const sources = container.querySelector("aside")!;
    expect(
      sources.textContent?.match(/which the run was started with/g),
    ).toHaveLength(3);
    expect(sources).toHaveTextContent(isolated("Title"));
    expect(sources).toHaveTextContent(isolated("Body"));
    expect(sources).toHaveTextContent(isolated("Address"));
    expect(sources).not.toHaveTextContent(TITLE);
    expect(container).not.toHaveTextContent(WHAT_ELSE);
    expect(container).not.toHaveTextContent(NOT_LAID_OUT);
    expect(container.textContent).not.toContain("{{");
  });

  it.each([
    ["a link", "[click](https://x.example)"],
    ["an image", "![](https://x.example/p.png)"],
    ["strong text", "**x**"],
    ["a heading and a list", "# h\n- l"],
    ["HTML", "<b>y</b>"],
    ["code and a quote", "`c`\n> q"],
  ])(
    "draws a value holding %s as the text it is, making nothing of it",
    (_case, value) => {
      const container = drawn("{{title}}", [
        { input: "title", from: FROM, value },
      ]);

      expect(laidOutText(container)).toBe(value);
      expect(
        container.querySelectorAll(ELEMENTS_A_VALUE_COULD_MAKE),
      ).toHaveLength(0);
    },
  );

  it("draws a value holding Markdown inside the drawing's own emphasis as text, the emphasis around it the drawing's alone", () => {
    const container = drawn("**{{title}}** and *{{body}}*", given("*x* **y**"));

    const strong = container.querySelectorAll("strong");
    expect(strong).toHaveLength(1);
    expect(strong[0]!.textContent).toBe("*x* **y**");
    expect(container.querySelectorAll("em")).toHaveLength(1);
    expect(container.querySelector("strong strong, strong em")).toBeNull();
  });

  it("keeps a value's line breaks where they stand", () => {
    const container = drawn("Body: {{body}}");

    const value = container.querySelector("bdi")!;
    expect(value.textContent).toBe("It smokes.\nIt beeps.");
    expect(value).toHaveStyle({ whiteSpace: "pre-wrap" });
    expect(container.querySelector("br")).toBeNull();
  });

  it("draws a character in a value that would turn the order text is shown in as its code, and never the character", () => {
    const container = drawn(
      "{{title}}",
      given(`abc${String.fromCodePoint(0x202e)}fed`),
    );

    expect(screen.getByRole("img", { name: TURNS_ORDER })).toHaveTextContent(
      "U+202E",
    );
    expect(container.innerHTML).not.toContain(String.fromCodePoint(0x202e));
    expect(container.querySelector("img")).toBeNull();
  });

  it.each([
    ["an address that is a value", "[Open it]({{url}})"],
    ["an address holding a value", "[Open it](https://e.example/?q={{url}})"],
    ["a title holding a value", '[Open it](https://e.example "{{title}}")'],
    [
      "a definition holding a value",
      "[Open it][r]\n\n[r]: https://e.example/{{url}}",
    ],
    ["an address within angle brackets", "[Open it](<{{url}}>)"],
  ])(
    "draws a link with %s as its words alone, linking nowhere and saying nothing of it",
    (_case, drawing) => {
      const container = drawn(drawing, [
        { input: "url", from: FROM, value: "https://evil.example/x" },
        { input: "title", from: FROM, value: TITLE },
      ]);

      expect(container.querySelector("p")?.textContent).toBe("Open it");
      expect(container.querySelector("a")).toBeNull();
      expect(container.querySelector("p")?.innerHTML).not.toContain(
        "evil.example",
      );
      expect(container).not.toHaveTextContent(NEW_TAB);
    },
  );

  it("draws an address written within angle brackets around a value as its text alone, the value in it drawn as text", () => {
    const container = drawn(
      "<https://e.example/{{title}}>",
      given().slice(0, 1),
    );

    expect(laidOutText(container)).toBe(`https://e.example/${TITLE}`);
    expect(container.querySelector("a")).toBeNull();
  });

  it("keeps a link the drawing writes in full, opening it apart from the page and saying so to those who hear the page", () => {
    drawn(
      'See [the policy](https://e.example/policy "Policy") for {{title}}.',
      given().slice(0, 1),
    );

    const link = screen.getByRole("link");
    // Read as text: the name jsdom computes trims each child's text, losing the space a browser keeps.
    expect(link.textContent).toBe(`the policy ${NEW_TAB}`);
    expect(link).toHaveAttribute("href", "https://e.example/policy");
    expect(link).toHaveAttribute("title", "Policy");
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveAttribute("rel", "noopener noreferrer");
    const unseen = link.querySelector("span")!;
    expect(unseen.textContent).toBe(` ${NEW_TAB}`);
    expect(unseen).toHaveStyle({ position: "absolute", width: "1px" });
    expect(screen.getAllByRole("link")).toHaveLength(1);
  });

  it("keeps a link the drawing writes whose words hold a value, the value drawn as text within them", () => {
    drawn("[{{title}}](https://e.example/a)", given().slice(0, 1));

    const link = screen.getByRole("link");
    expect(link.textContent).toBe(`${TITLE} ${NEW_TAB}`);
    expect(link).toHaveAttribute("href", "https://e.example/a");
    expect(link.querySelector("a, strong")).toBeNull();
  });

  it.each([
    ["a script", "[x](javascript:alert(1))"],
    ["data", "[x](data:text/html;base64,PHNjcmlwdD4=)"],
    ["nowhere", "[x]()"],
  ])(
    "draws a link the drawing writes to %s as its words alone, the library having taken its address",
    (_case, drawing) => {
      const container = drawn(drawing, []);

      expect(container.textContent).toBe("x");
      expect(container.querySelector("a")).toBeNull();
      expect(container.innerHTML).not.toMatch(/javascript:|data:|href/);
    },
  );

  it.each([
    ["no words", "[](https://e.example/a)", []],
    ["only a space for words", "[ ](https://e.example/a)", []],
    [
      "for words only a value that is empty",
      "[{{title}}](https://e.example/a)",
      given("").slice(0, 1),
    ],
    [
      "for words only a value that is only space",
      "[ {{title}} ](https://e.example/a)",
      given(" \n\t").slice(0, 1),
    ],
  ])(
    "draws a link the drawing writes with %s as its address, as text linking nowhere",
    (_case, drawing, wentIn: readonly WentIn[]) => {
      const container = drawn(drawing, wentIn);

      expect(laidOutText(container)).toBe("https://e.example/a");
      expect(container.querySelector("a")).toBeNull();
      expect(container).not.toHaveTextContent(NEW_TAB);
    },
  );

  it.each([
    ["inline", "Before <b>bold</b> <img src=x onerror=alert(1)> after"],
    [
      "as a block",
      '<div onclick="alert(1)">\n<script>alert(1)</script>\n</div>',
    ],
    ["as a comment", "<!-- {{title}} -->"],
  ])("shows HTML the drawing writes %s as written", (_case, drawing) => {
    const container = drawn(drawing, []);

    expect(container.textContent).toBe(drawing);
    expect(
      container.querySelectorAll("b, img, script, [onclick], [onerror]"),
    ).toHaveLength(0);
  });

  it("shows HTML the drawing writes around a value's name as written, the name in it and not the value", () => {
    const container = drawn('<a href="{{url}}">x</a>', []);

    expect(container.textContent).toBe('<a href="{{url}}">x</a>');
    expect(container.querySelector("a")).toBeNull();
    expect(container.innerHTML).not.toContain("evil.example");
  });

  it("draws a value whose name stands between HTML tags the drawing writes in a line, the tags shown as written, and lists it nowhere else", () => {
    const container = drawn("a <b>{{body}}</b> z", given().slice(1, 2));

    expect(container.querySelector("p")?.textContent).toBe(
      "a <b>It smokes.\nIt beeps.</b> z",
    );
    expect(container.querySelector("b")).toBeNull();
    expect(container.textContent).not.toContain("{{");
    expect(container).not.toHaveTextContent(WHAT_ELSE);
  });

  it.each([
    [
      "naming values in its words, address and title",
      '![a {{title}}](https://e.example/{{url}}.png "t {{body}}")',
    ],
    [
      "written with two braces escaped",
      "![a \\{{x}}](https://e.example/a.png)",
    ],
    ["written across lines", "![a\nb](https://e.example/a.png)"],
  ])("shows an image %s as written, fetching nothing", (_case, drawing) => {
    const container = drawn(drawing, []);

    expect(container.textContent).toBe(drawing);
    expect(container.querySelector("img")).toBeNull();
    expect(container.innerHTML).not.toContain(TITLE);
  });

  it("shows an image by a definition as its words and label as written, the definition drawing nothing", () => {
    const container = drawn("![a][r] then\n\n[r]: https://e.example/a.png", []);

    expect(container.textContent).toBe("![a][r] then");
    expect(container.querySelector("img")).toBeNull();
  });

  it("shows an image within a link the drawing writes as written, inside the link", () => {
    drawn("[![a](https://e.example/a.png)](https://e.example/b)", []);

    const link = screen.getByRole("link");
    expect(link.textContent).toBe(`![a](https://e.example/a.png) ${NEW_TAB}`);
    expect(link).toHaveAttribute("href", "https://e.example/b");
    expect(link.querySelector("img")).toBeNull();
  });

  it("shows an image as written in a drawing opened by a byte order mark, which nothing draws", () => {
    const container = drawn(`${String.fromCharCode(0xfeff)}x ![a](b)`, []);

    expect(container.textContent).toBe("x ![a](b)");
    expect(container.querySelector("img")).toBeNull();
  });

  it.each([
    ["in text", "\\{{title}}", "{{title}}"],
    ["in code", "`\\{{title}}`", "{{title}}"],
  ])(
    "draws a backslash before two braces %s as the two braces as they are, and no value",
    (_case, drawing, shown) => {
      const container = drawn(drawing, []);

      expect(container.textContent).toBe(shown);
      expect(container.innerHTML).not.toContain(TITLE);
    },
  );

  it.each([
    ["spaces within its braces", "{{ title }}"],
    ["a capital letter", "{{Title}}"],
    ["a brace missing", "{{title}"],
    ["a name nothing went in by", "{{summary}}"],
  ])(
    "draws a name written with %s as it was written, and no value",
    (_case, drawing) => {
      const container = drawn(drawing, []);

      expect(container.textContent).toBe(drawing);
      expect(container.innerHTML).not.toContain(TITLE);
    },
  );

  it("says there is none where the drawing names a field the question takes that nothing went in by", () => {
    const container = drawn("Notes: {{notes}}", given().slice(0, 1));

    expect(container.querySelector("p")?.textContent).toBe("Notes: None");
    expect(container.textContent).not.toContain("{{");
    expect(
      [...container.querySelectorAll("dt")].map((each) => each.textContent),
    ).toEqual([isolated("Title")]);
  });

  it("draws a value written in code as text within the code", () => {
    const container = drawn("`{{title}}`", given("**x**").slice(0, 1));

    expect(container.querySelector("code")?.textContent).toBe("**x**");
    expect(container.querySelector("code strong")).toBeNull();
  });

  it("drops a code block's language where it names a value, drawing the code", () => {
    const container = drawn("```{{title}}\nq\n```", []);

    const code = container.querySelector("pre code")!;
    expect(code.textContent).toBe("q\n");
    expect(code).not.toHaveAttribute("class");
  });

  it.each([
    ["typed as it is", String.fromCodePoint(0xf0000)],
    ["written as a reference", "&#xF0000;"],
  ])(
    "draws a character the drawing holds among the marks %s as written, never as a value",
    (_case, written) => {
      const container = drawn(`${written}|{{body}}`, given().slice(1, 2));

      expect(laidOutText(container)).toBe(`${written}|It smokes.\nIt beeps.`);
      expect(container.innerHTML).not.toContain(TITLE);
    },
  );

  it("says a value withheld from the reader is withheld where its name stands, in no paragraph of its own within another", () => {
    const container = drawn("Title: {{title}}", [
      { input: "title", from: FROM, withheld: true },
    ]);

    expect(container).toHaveTextContent("Title: Withheld from you.");
    expect(container.querySelector("p")).toHaveTextContent(
      "Title: Withheld from you.",
    );
    expect(container.querySelector("p p, p div, p ul")).toBeNull();
  });

  it("says a value withheld from the reader is withheld within a heading, the heading holding no paragraph", () => {
    const container = drawn("# {{title}}", [
      { input: "title", from: FROM, withheld: true },
    ]);

    expect(screen.getByRole("heading", { level: 4 })).toHaveTextContent(
      "Withheld from you.",
    );
    expect(container.querySelector(BLOCKS_IN_A_LINE)).toBeNull();
  });

  it("draws a value of many where its name stands as the list it is, the paragraph holding it no paragraph", () => {
    const container = drawn("References: {{refs}}", [
      { input: "refs", from: FROM, value: ["[a](https://x.example)", "b"] },
    ]);

    const items = container.querySelectorAll("li");
    expect([...items].map((each) => each.textContent)).toEqual([
      "[a](https://x.example)",
      "b",
    ]);
    expect(container.querySelector("div > ul")).not.toBeNull();
    expect(container.querySelector("a")).toBeNull();
    expect(container.querySelector("p")).toBeNull();
  });

  it("keeps a paragraph holding no value, or values of one, a paragraph", () => {
    const container = drawn(
      "Plain words.\n\nTitle: {{title}}",
      given().slice(0, 1),
    );

    expect(
      [...container.querySelectorAll("p")].map((each) => each.textContent),
    ).toEqual(["Plain words.", `Title: ${TITLE}`]);
    expect(container.querySelector("p div")).toBeNull();
  });

  it.each([
    ["a heading", "# {{refs}}", "h4"],
    ["a link's words", "[{{refs}}](https://e.example/r)", "a"],
    ["strong text", "**{{refs}}**", "strong"],
  ])(
    "runs many on in one line within %s, joined as the reader's language joins a list",
    (_case, drawing, within) => {
      const container = drawn(drawing, [
        { input: "refs", from: FROM, value: ["a", "b", "c"] },
      ]);

      const line = container.querySelector(within)!;
      expect(line.textContent).toMatch(/^a, b, and c/);
      expect(
        [...line.querySelectorAll("bdi")].map((each) => each.textContent),
      ).toEqual(["a", "b", "c"]);
      expect(container.querySelector(BLOCKS_IN_A_LINE)).toBeNull();
      expect(container.querySelector("li")).toBeNull();
    },
  );

  it("runs fields on in one line within a heading, each after its label", () => {
    const container = drawn("# {{ship_to}}", [
      {
        input: "ship_to",
        from: FROM,
        value: { street: "1 High St", city: "Leeds" },
      },
    ]);

    expect(screen.getByRole("heading", { level: 4 }).textContent).toBe(
      "Street: 1 High St and City: Leeds",
    );
    expect(container.querySelector(BLOCKS_IN_A_LINE)).toBeNull();
    expect(container.querySelector("dl, dt")).toBeNull();
  });

  it("draws a value bound field by field where the name holding them stands, each under its label, and lists none of them again", () => {
    const container = drawn("Ship to: {{ship_to}}", SHIPPED_TO);

    expect(
      [...container.querySelectorAll("dt")].map((each) => each.textContent),
    ).toEqual([
      `${isolated("Ship to")} › ${isolated("Street")}`,
      `${isolated("Ship to")} › ${isolated("City")}`,
    ]);
    expect(
      [...container.querySelectorAll("dd")].map((each) => each.textContent),
    ).toEqual(["1 High St", "Leeds"]);
    expect(container.querySelector("div > dl")).not.toBeNull();
    expect(container.querySelector("p")).toBeNull();
    expect(container.textContent).not.toContain("{{");
    expect(container).not.toHaveTextContent(WHAT_ELSE);
  });

  it("runs a value bound field by field on in one line within a heading, each after its label", () => {
    const container = drawn("## {{ship_to}}", SHIPPED_TO);

    expect(screen.getByRole("heading", { level: 5 }).textContent).toBe(
      `${isolated("Ship to")} › ${isolated("Street")}: 1 High St and ${isolated("Ship to")} › ${isolated("City")}: Leeds`,
    );
    expect(container.querySelector("dl")).toBeNull();
  });

  it.each([
    ["under its label", "Shipment: {{shipment}}", "dd"],
    ["in one line within a heading", "# {{shipment}}", "h4"],
  ])(
    "draws each field of a value bound field by field as its own kind is read, %s",
    (_case, drawing, within) => {
      const container = drawn(drawing, [
        { input: "shipment.carrier", from: FROM, value: "2026-03-01 Post" },
        { input: "shipment.due", from: FROM, value: "2026-03-01" },
      ]);

      const days = container.querySelectorAll(`${within} time`);
      expect([...days].map((each) => each.getAttribute("datetime"))).toEqual([
        "2026-03-01",
      ]);
      expect(days[0]!.textContent).toBe("Mar 1, 2026");
      expect(container.textContent).toContain("2026-03-01 Post");
      expect(container).not.toHaveTextContent(
        "Written in a way this page cannot read.",
      );
    },
  );

  it("draws a composed value in its placeholder and lists its part sources once outside inline drawing", () => {
    const wentIn: readonly WentIn[] = [
      {
        input: "ship_to",
        value: { street: "1 High St", city: "Leeds" },
        sources: [
          { targetPath: "street", from: { kind: "constant" } },
          { targetPath: "city", from: { kind: "run_input", path: "ticket" } },
        ],
      },
    ];
    const container = drawn("# {{ship_to}}\n\n{{ship_to}}", wentIn);

    expect(container.querySelector("h4")).toHaveTextContent("1 High St");
    expect(container).toHaveTextContent("It was put together from these:");
    expect(
      container.textContent?.match(/It was put together from these:/g),
    ).toHaveLength(1);
    expect(
      [...container.querySelectorAll("li")].map((item) => item.textContent),
    ).toEqual([
      expect.stringContaining("Street"),
      expect.stringContaining("City"),
    ]);
    expect(container.querySelector("h4 :is(ul, dl), p :is(ul, dl)")).toBeNull();
    expect(container).not.toHaveTextContent(WHAT_ELSE);
  });

  it("lists a composed input once when the drawing cannot lay out its placeholder", () => {
    const container = drawn(`${"- ".repeat(33)}{{ship_to}}`, [
      {
        input: "ship_to",
        value: { street: "1 High St", city: "Leeds" },
        sources: [{ targetPath: "street", from: { kind: "constant" } }],
      },
    ]);

    expect(container).toHaveTextContent(NOT_LAID_OUT);
    expect(
      container.textContent?.match(/It was put together from these:/g),
    ).toHaveLength(1);
    expect(container).toHaveTextContent("1 High St");
    expect(container).not.toHaveTextContent(WHAT_ELSE);
  });

  it("keeps a composed input out of a drawing link address and lists its value with its sources", () => {
    const container = drawn("[Open it](https://e.example/?q={{ship_to}})", [
      {
        input: "ship_to",
        value: { street: "Demo Sample" },
        sources: [{ targetPath: "street", from: { kind: "constant" } }],
      },
    ]);

    expect(container).toHaveTextContent("Demo Sample");
    expect(container).toHaveTextContent(WHAT_ELSE);
    expect(
      container.textContent?.match(/It was put together from these:/g),
    ).toHaveLength(1);
    expect(
      [...container.querySelectorAll("a")].map((link) =>
        link.getAttribute("href"),
      ),
    ).not.toContain(expect.stringContaining("Demo Sample"));
  });

  it("draws a name only the start of another's as it was written, the other's fields listed under the drawing", () => {
    const container = drawn("Ship: {{ship}}", SHIPPED_TO);

    expect(container.querySelector("p")?.textContent).toBe("Ship: {{ship}}");
    expect(screen.getByRole("heading", { name: WHAT_ELSE }).tagName).toBe("H4");
    expect(
      [...container.querySelectorAll("dd")].map((each) => each.textContent),
    ).toEqual([
      `${isolated("ticket")}, which the run was started with1 High St`,
      `${isolated("ticket")}, which the run was started withLeeds`,
    ]);
  });

  it("draws a date as its kind is read, and a term with what it means", () => {
    const container = drawn("Due {{due}}, {{priority}}", [
      { input: "due", from: FROM, value: "2026-03-01" },
      { input: "priority", from: FROM, value: "P1" },
    ]);

    const day = container.querySelector("time")!;
    expect(day).toHaveAttribute("datetime", "2026-03-01");
    expect(day.textContent).toBe("Mar 1, 2026");
    expect(laidOutText(container)).toBe(
      `Due Mar 1, 2026, ${isolated("P1")}: ${isolated("Drop everything")}`,
    );
    expect(container.textContent).not.toContain("2026-03-01");
    expect(container).not.toHaveTextContent(
      "Written in a way this page cannot read.",
    );
  });

  it.each([
    ["h3", "h4", "h6"],
    ["h4", "h5", "h6"],
  ])(
    "sets every heading the drawing writes below a section headed %s, the deepest at the last level there is",
    (under, first, deepest) => {
      const container = drawn("# One\n\n###### Six", [], under as Heading);

      const headings = [
        ...container.querySelectorAll("h1, h2, h3, h4, h5, h6"),
      ];
      expect(headings.map((each) => each.tagName.toLowerCase())).toEqual([
        first,
        deepest,
      ]);
      expect(headings.map((each) => each.textContent)).toEqual(["One", "Six"]);
    },
  );

  it.each([
    ["# R", "h4", "MuiTypography-h4"],
    ["## R", "h5", "MuiTypography-h5"],
  ])(
    "draws a heading the drawing writes as %s at the size the pages draw their own at its level",
    (drawing, tag, variant) => {
      const container = drawn(drawing, []);

      const heading = container.querySelector(tag)!;
      expect(heading).toHaveClass(variant);
      expect(
        [...heading.classList].filter((name) =>
          /^MuiTypography-(h[1-6]|subtitle[12]|body[12])$/.test(name),
        ),
      ).toEqual([variant]);
    },
  );

  it("draws a value named again in full where it is short enough to be drawn twice, written out with its quotes", () => {
    const short = "x".repeat(254);
    const container = drawn("{{title}}\n\n{{title}}", given(short).slice(0, 1));

    expect(
      [...container.querySelectorAll("bdi")].map((each) => each.textContent),
    ).toEqual([short, short]);
    expect(container).not.toHaveTextContent("shown above");
  });

  it("counts a value's length in characters, not in the units text is held in", () => {
    const wide = String.fromCodePoint(0x1f600).repeat(254);
    const container = drawn("{{title}}\n\n{{title}}", given(wide).slice(0, 1));

    expect(container.querySelectorAll("bdi")).toHaveLength(2);
    expect(container).not.toHaveTextContent("shown above");
  });

  it("draws a value too long to draw twice in full where it is first named, and further on says it is shown above", () => {
    const long = "x".repeat(255);
    const container = drawn(
      "# {{title}}\n\nAgain: {{title}}\n\n[{{title}}](https://e.example/a)",
      given(long).slice(0, 1),
    );

    expect(
      [...container.querySelectorAll("bdi")].map((each) => each.textContent),
    ).toEqual([long]);
    expect(container.querySelector("h4 bdi")).not.toBeNull();
    expect(container.querySelector("p")!.textContent).toBe(
      `Again: ${isolated("Title")}, shown above`,
    );
    expect(screen.getByRole("link").textContent).toBe(
      `${isolated("Title")}, shown above ${NEW_TAB}`,
    );
  });

  it("draws a value in full where it is first drawn, a link's address naming it before then drawing nothing", () => {
    const long = "x".repeat(300);
    const container = drawn(
      "[a]({{title}}) then {{title}}",
      given(long).slice(0, 1),
    );

    expect(container.querySelector("bdi")?.textContent).toBe(long);
    expect(container).not.toHaveTextContent("shown above");
  });

  it.each([
    ["63 of one character", Array<string>(63).fill("1"), 2],
    ["64 of one character", Array<string>(64).fill("1"), 1],
    ["85 empty", Array<string>(85).fill(""), 2],
    ["86 empty", Array<string>(86).fill(""), 1],
    ["51 none", Array<null>(51).fill(null), 2],
    ["52 none", Array<null>(52).fill(null), 1],
  ])(
    "counts a value of many, %s, as it is written out, its brackets and commas included",
    (_case, value, drawnFully) => {
      const container = drawn("{{refs}}\n\n{{refs}}", [
        { input: "refs", from: FROM, value },
      ]);

      expect(container.querySelectorAll("ul")).toHaveLength(drawnFully);
      expect(container.querySelectorAll("li")).toHaveLength(
        drawnFully * value.length,
      );
      expect(container.textContent?.includes("shown above")).toBe(
        drawnFully === 1,
      );
    },
  );

  it.each([
    ["given whole", 33, 2],
    ["given whole", 34, 1],
    ["bound field by field", 33, 2],
    ["bound field by field", 34, 1],
  ])(
    "counts a value of fields %s as one value holding each by its name: a city of %i characters, drawn in full %i times",
    (shape, city, drawnFully) => {
      const street = "s".repeat(200);
      const container = drawn(
        "{{ship_to}}\n\n{{ship_to}}",
        shape === "given whole"
          ? [
              {
                input: "ship_to",
                from: FROM,
                value: { street, city: "c".repeat(city) },
              },
            ]
          : [
              { input: "ship_to.street", from: FROM, value: street },
              { input: "ship_to.city", from: FROM, value: "c".repeat(city) },
            ],
      );

      expect(container.querySelectorAll("dl")).toHaveLength(drawnFully);
      expect(container.textContent?.includes("shown above")).toBe(
        drawnFully === 1,
      );
    },
  );

  it("does not count hidden fields values as visible writing when the same input is drawn again", () => {
    const takes: readonly ReadField[] = [
      {
        name: "blocks",
        label: "Blocks",
        kind: "fields",
        most: 20,
        mustBeGiven: false,
        fields: [
          { name: "note", kind: "text", longest: 40, mustBeGiven: false },
        ],
      },
    ];
    const hidden = Array.from({ length: 20 }, () => ({
      withheld: true as const,
    }));
    const container = drawn(
      "{{blocks}}\n\n{{blocks}}",
      [{ input: "blocks", from: FROM, value: hidden }],
      "h3",
      takes,
    );

    expect(container.querySelectorAll("ul")).toHaveLength(2);
    expect(container.textContent?.match(/Withheld from you\./g)).toHaveLength(
      40,
    );
    expect(container).not.toHaveTextContent("shown above");
    expect(container).not.toHaveTextContent("None");
  });

  it("counts a real fields child named withheld as visible writing", () => {
    const takes: readonly ReadField[] = [
      {
        name: "details",
        label: "Details",
        kind: "fields",
        mustBeGiven: false,
        fields: [
          { name: "withheld", kind: "text", longest: 300, mustBeGiven: false },
        ],
      },
    ];
    const container = drawn(
      "{{details}}\n\n{{details}}",
      [
        {
          input: "details",
          from: FROM,
          value: { withheld: "Demo Sample".repeat(25) },
        },
      ],
      "h3",
      takes,
    );

    expect(container).toHaveTextContent("shown above");
    expect(container.querySelectorAll("dl")).toHaveLength(1);
    expect(container).not.toHaveTextContent("Withheld from you.");
  });

  it("counts a value kept in a shape the step had before by the text it was kept as, written out with its quotes", () => {
    const kept = JSON.stringify("k".repeat(253));
    const container = drawn("{{title}}\n\n{{title}}", [
      { input: "title", from: FROM, asKept: kept },
    ]);

    expect(
      [...container.querySelectorAll("bdi")].map((each) => each.textContent),
    ).toEqual([kept]);
    expect(container).toHaveTextContent(`${isolated("Title")}, shown above`);
  });

  it("lists under the drawing, field by field, every input it does not name, and nothing it does", () => {
    const container = drawn("{{title}}", given());

    const heading = screen.getByRole("heading", { name: WHAT_ELSE });
    expect(heading.tagName).toBe("H4");
    const list = container.querySelector("dl")!;
    expect(
      [...list.querySelectorAll("dt")].map((each) => each.textContent),
    ).toEqual([isolated("Body"), isolated("Address")]);
    expect(
      within(list).getAllByText(/which the run was started with/),
    ).toHaveLength(2);
    expect(list).not.toHaveTextContent(TITLE);
    expect(container.querySelector("p")?.textContent).toBe(TITLE);
  });

  it.each([
    ["HTML written as a block", "{{title}}\n\n<div>{{url}}</div>"],
    ["an image's words", "{{title}} ![{{url}}](https://e.example/a.png)"],
    ["an image's address", "{{title}} ![a]({{url}})"],
    ["a link's address", "{{title}} [Open it](https://e.example/?q={{url}})"],
    ["a link's title", '{{title}} [Open it](https://e.example "{{url}}")'],
  ])(
    "lists under the drawing an input it names only in %s, where no value is drawn",
    (_case, drawing) => {
      const container = drawn(drawing, [given()[0]!, given()[2]!]);

      const list = container.querySelector("dl")!;
      expect(
        [...list.querySelectorAll("dt")].map((each) => each.textContent),
      ).toEqual([isolated("Address")]);
      expect(list).toHaveTextContent("https://evil.example/x");
      expect(container.innerHTML.split("evil.example")).toHaveLength(2);
      expect(list).not.toHaveTextContent(TITLE);
    },
  );

  it("keeps every drawn value where it stands when what went in is read again unchanged", () => {
    const rendered = render(
      element("Title: {{title}}", given().slice(0, 1), "h3"),
    );
    const before = rendered.container.querySelector("bdi");

    rendered.rerender(element("Title: {{title}}", given().slice(0, 1), "h3"));

    expect(rendered.container.querySelector("bdi")).toBe(before);
    expect(before?.isConnected).toBe(true);
  });

  it.each([
    ["equal to what it was", TITLE],
    ["changed", "Printer cold"],
  ])(
    "reads the drawing once when what went in is read again %s, the value updated where it stands",
    (_case, title) => {
      const rendered = render(element("Title: {{title}}", given(), "h3"));
      const before = rendered.container.querySelector("bdi");
      vi.mocked(Markdown).mockClear();

      rendered.rerender(element("Title: {{title}}", given(title), "h3"));

      expect(vi.mocked(Markdown)).not.toHaveBeenCalled();
      expect(rendered.container.querySelector("bdi")).toBe(before);
      expect(before?.textContent).toBe(title);
      expect(rendered.container.querySelector("p")?.textContent).toBe(
        `Title: ${title}`,
      );
    },
  );

  it.each([
    ["a list", `${"- ".repeat(33)}{{title}}`],
    ["a quote", `${">".repeat(33)}{{title}}`],
    ["a numbered list", `${"1. ".repeat(33)}{{title}}`],
    ["a list by its indent", `${" ".repeat(64)}- {{title}}`],
    ["a quote as far as its length allows", `${">".repeat(8183)}{{title}}`],
    ["its emphasis", `${"*a ".repeat(32)}*{{title}}*${" a*".repeat(32)}`],
    [
      "its emphasis by underscores",
      `${"_a ".repeat(32)}_{{title}}_${" a_".repeat(32)}`,
    ],
  ])(
    "lists every input field by field, saying the drawing could not be laid out, where it could nest %s past 32 deep, and never reads it as Markdown",
    (_case, drawing) => {
      const container = drawn(drawing);

      expect(vi.mocked(Markdown)).not.toHaveBeenCalled();
      const said = container.querySelector("p")!;
      expect(said.textContent).toBe(NOT_LAID_OUT);
      expect(
        said.compareDocumentPosition(container.querySelector("dl")!) &
          Node.DOCUMENT_POSITION_FOLLOWING,
      ).toBeTruthy();
      expect(
        [...container.querySelectorAll("dt")].map((each) => each.textContent),
      ).toEqual([isolated("Title"), isolated("Body"), isolated("Address")]);
      expect(container).toHaveTextContent(TITLE);
      expect(container.querySelectorAll("blockquote, ol, li")).toHaveLength(0);
      expect(container).not.toHaveTextContent(WHAT_ELSE);
      expect(container.textContent).not.toContain("{{");
    },
  );

  it.each([
    ["a list", `${"- ".repeat(32)}{{title}}`, "li"],
    ["a quote", `${">".repeat(32)}{{title}}`, "blockquote"],
    ["a numbered list", `${"1. ".repeat(32)}{{title}}`, "li"],
    ["its emphasis", `${"*a ".repeat(31)}*{{title}}*${" a*".repeat(31)}`, "em"],
    [
      "its emphasis by underscores",
      `${"_a ".repeat(31)}_{{title}}_${" a_".repeat(31)}`,
      "em",
    ],
  ])(
    "lays out a drawing nesting %s 32 deep, the value at the deepest",
    (_case, drawing, nested) => {
      const container = drawn(drawing);

      const levels = container.querySelectorAll(nested);
      expect(levels).toHaveLength(32);
      expect(levels[31]!.textContent?.trim()).toBe(TITLE);
      expect(vi.mocked(Markdown)).toHaveBeenCalled();
      expect(container).not.toHaveTextContent(NOT_LAID_OUT);
      expect(
        [...container.querySelectorAll("dt")].map((each) => each.textContent),
      ).toEqual([isolated("Body"), isolated("Address")]);
    },
  );

  it("lists every input field by field, saying the drawing could not be laid out, where laying it out throws", async () => {
    await unlaidThrowing();
    vi.spyOn(console, "error").mockImplementation(() => {});

    const container = drawn("Unlaid {{title}}");

    expect(vi.mocked(Markdown)).toHaveBeenCalled();
    const said = container.querySelector("p")!;
    expect(said.textContent).toBe(NOT_LAID_OUT);
    expect(
      said.compareDocumentPosition(container.querySelector("dl")!) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(
      [...container.querySelectorAll("dt")].map((each) => each.textContent),
    ).toEqual([isolated("Title"), isolated("Body"), isolated("Address")]);
    expect(container).toHaveTextContent(TITLE);
    expect(container).not.toHaveTextContent("Unlaid");
    expect(container).not.toHaveTextContent(WHAT_ELSE);
    expect(container.textContent).not.toContain("{{");
  });

  it("lays out a drawing that can be laid out where the one before it could not", async () => {
    await unlaidThrowing();
    vi.spyOn(console, "error").mockImplementation(() => {});
    const rendered = render(element("Unlaid {{title}}", given(), "h3"));
    expect(rendered.container).toHaveTextContent(NOT_LAID_OUT);

    rendered.rerender(element("Title: {{title}}", given(), "h3"));

    expect(rendered.container.querySelector("p")?.textContent).toBe(
      `Title: ${TITLE}`,
    );
    expect(rendered.container).not.toHaveTextContent(NOT_LAID_OUT);
    expect(
      [...rendered.container.querySelectorAll("dt")].map(
        (each) => each.textContent,
      ),
    ).toEqual([isolated("Body"), isolated("Address")]);
  });
});
