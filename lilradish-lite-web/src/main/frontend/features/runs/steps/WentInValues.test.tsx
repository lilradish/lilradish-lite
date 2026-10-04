import { ThemeProvider } from "@mui/material/styles";
import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import type { ReadField } from "../../../api/filling";
import type {
  From,
  StepRow,
  WentIn,
} from "../../../api/groups/{groupId}/runs/{runId}/steps";
import { CurrentFileRunContext } from "../fill/CurrentFileRun";
import { theme } from "../../../lib/theme/theme";
import type { Declaring } from "./declared";
import { WentInValues } from "./WentInValues";

const TAKES: readonly ReadField[] = [
  { name: "title", label: "Title", kind: "text", mustBeGiven: true },
  {
    name: "customer",
    label: "Customer",
    kind: "fields",
    mustBeGiven: true,
    fields: [
      { name: "name", label: "Name", kind: "text", mustBeGiven: true },
      { name: "id", label: "Identifier", kind: "text", mustBeGiven: true },
      {
        name: "address",
        label: "Address",
        kind: "fields",
        mustBeGiven: true,
        fields: [
          { name: "city", label: "City", kind: "text", mustBeGiven: true },
        ],
      },
    ],
  },
];

const WENT_IN: readonly WentIn[] = [
  { input: "title", from: { kind: "constant" }, value: "**Printer** on fire" },
];

const DECLARING: Declaring = {
  runId: "00000008-0000-4000-8000-000000000e01",
  declarations: new Map(),
  runVersionId: "00000007-0000-4000-8000-000000000e01",
  steps: undefined,
};

function listed(
  drawing?: string,
  wentIn: readonly WentIn[] = WENT_IN,
): HTMLElement {
  return render(
    <ThemeProvider theme={theme}>
      <WentInValues
        wentIn={wentIn}
        takes={TAKES}
        declaring={DECLARING}
        drawing={drawing}
        under="h3"
      />
    </ThemeProvider>,
  ).container;
}

describe("WentInValues", () => {
  it.each([
    [{ kind: "step", versionId: "pinned", path: "amount" }, "Pinned amount"],
    [{ kind: "step", codeStep: "web", path: "amount" }, "Code amount"],
    [{ kind: "step", searchWay: "web", path: "amount" }, "Search amount"],
    [{ kind: "step", stepId: "route-step", path: "amount" }, "Route amount"],
  ] satisfies readonly (readonly [From, string])[])(
    "takes a search input's declaration from its planned source %j",
    (from, label) => {
      const sourcePartId = "0000000a-0000-4000-8000-000000000e01";
      const step: StepRow = {
        stepId: "search-step",
        name: "search",
        order: 1,
        runs: { kind: "search", searchWay: "web" },
        takesFrom: [{ sourcePartId, input: "amount", from }],
        state: "waiting",
        cost: {},
        acts: [],
        withheld: [],
      };
      const declaring: Declaring = {
        ...DECLARING,
        step,
        declarations: new Map([
          ...["pinned", "web", "search:web", "route:route-step"].map(
            (key, at) =>
              [
                key,
                {
                  takes: [],
                  gives: [
                    {
                      name: "amount",
                      label: [
                        "Pinned amount",
                        "Code amount",
                        "Search amount",
                        "Route amount",
                      ][at],
                      kind: "number",
                      mustBeGiven: true,
                    },
                  ],
                },
              ] as const,
          ),
        ]),
      };
      const container = render(
        <ThemeProvider theme={theme}>
          <WentInValues
            takes={[]}
            declaring={declaring}
            wentIn={[
              {
                sourcePartId,
                input: "amount",
                from: {
                  kind: "run_input",
                  path: "old_amount",
                  runId: "parent",
                  runNumber: 4,
                },
                value: "1200",
              },
            ]}
          />
        </ThemeProvider>,
      ).container;

      expect(container.querySelector("dt")).toHaveTextContent(label);
      expect(container).toHaveTextContent("1,200");
      expect(container).not.toHaveTextContent("old amount:");
      expect(container).not.toHaveTextContent(sourcePartId);
    },
  );
  it("draws a search composite with the planned source container's labels while retaining cross-run leaf provenance", () => {
    const sourcePartId = "0000000a-0000-4000-8000-000000000e01";
    const step: StepRow = {
      stepId: "00000009-0000-4000-8000-000000000e01",
      name: "search",
      order: 1,
      runs: { kind: "search", searchWay: "web" },
      takesFrom: [
        {
          sourcePartId,
          input: "customer",
          from: { kind: "run_input", path: "customer" },
        },
      ],
      state: "waiting",
      cost: {},
      acts: [],
      withheld: [],
    };
    const declaring: Declaring = {
      ...DECLARING,
      step,
      declarations: new Map([
        [DECLARING.runVersionId, { takes: TAKES, gives: [] }],
        ["search:web", { takes: [], gives: [] }],
      ]),
    };
    const container = render(
      <ThemeProvider theme={theme}>
        <WentInValues
          takes={[]}
          declaring={declaring}
          wentIn={[
            {
              sourcePartId,
              input: "customer",
              value: {
                name: "Demo Sample",
                id: "7",
                address: { city: "Leeds" },
              },
              sources: [
                {
                  targetPath: "name",
                  from: {
                    kind: "run_input",
                    path: "ticket",
                    runId: "00000008-0000-4000-8000-000000000e02",
                    runNumber: 4,
                  },
                },
                { targetPath: "id", from: { kind: "constant" } },
                { targetPath: "address.city", from: { kind: "constant" } },
              ],
            },
          ]}
        />
      </ThemeProvider>,
    ).container;

    expect(container.querySelector("dt")).toHaveTextContent("Customer");
    expect(
      [...container.querySelectorAll("li")].map((each) => each.textContent),
    ).toEqual([
      expect.stringContaining("Name"),
      expect.stringContaining("Identifier"),
      expect.stringContaining("City"),
    ]);
    expect(container.querySelector("li")).toHaveTextContent("run 4");
    expect(container).toHaveTextContent("Demo Sample");
    expect(container).not.toHaveTextContent(sourcePartId);
    expect(container).not.toHaveTextContent("undefined");
  });

  it("keeps repeated source paths as separate search inputs, uses their source types, and shows an optional missing value as nothing", () => {
    const first = "0000000a-0000-4000-8000-000000000e01";
    const second = "0000000a-0000-4000-8000-000000000e02";
    const third = "0000000a-0000-4000-8000-000000000e03";
    const file = "11111111-1111-4111-8111-111111111111";
    const step: StepRow = {
      stepId: "00000009-0000-4000-8000-000000000e01",
      name: "search",
      order: 1,
      runs: { kind: "search", searchWay: "web" },
      takesFrom: [
        {
          sourcePartId: first,
          input: "amount",
          from: { kind: "run_input", path: "amount" },
        },
        {
          sourcePartId: second,
          input: "amount",
          from: { kind: "run_input", path: "amount" },
        },
        {
          sourcePartId: third,
          input: "attachment",
          from: {
            kind: "step",
            path: "attachment",
            codeStep: "archive",
            name: "archive",
            stepId: "00000009-0000-4000-8000-000000000e02",
          },
        },
      ],
      state: "waiting",
      cost: {},
      acts: [],
      withheld: [],
    };
    const declaring: Declaring = {
      ...DECLARING,
      step,
      declarations: new Map([
        [
          DECLARING.runVersionId,
          {
            takes: [
              {
                name: "amount",
                label: "Claim amount",
                kind: "number",
                mustBeGiven: false,
              },
            ],
            gives: [],
          },
        ],
        [
          "archive",
          {
            takes: [],
            gives: [
              {
                name: "attachment",
                label: "Receipt",
                kind: "file",
                mustBeGiven: false,
              },
            ],
          },
        ],
      ]),
    };
    const container = render(
      <ThemeProvider theme={theme}>
        <CurrentFileRunContext.Provider
          value={{ groupId: "group-a", runId: DECLARING.runId }}
        >
          <WentInValues
            takes={[]}
            declaring={declaring}
            wentIn={[
              {
                sourcePartId: first,
                input: "amount",
                from: {
                  kind: "run_input",
                  path: "parent_total",
                  runId: "00000008-0000-4000-8000-000000000e02",
                  runNumber: 4,
                },
                value: "1200.50",
              },
              {
                sourcePartId: second,
                input: "amount",
                from: { kind: "run_input", path: "amount" },
                value: null,
              },
              {
                sourcePartId: third,
                input: "attachment",
                from: {
                  kind: "step",
                  path: "attachment",
                  codeStep: "archive",
                  name: "archive",
                  stepId: "00000009-0000-4000-8000-000000000e02",
                },
                value: {
                  id: file,
                  name: "Demo Sample.pdf",
                  type: "application/pdf",
                  size: 7,
                },
              },
            ]}
          />
        </CurrentFileRunContext.Provider>
      </ThemeProvider>,
    ).container;

    expect(
      [...container.querySelectorAll("dt")].map((each) => each.textContent),
    ).toEqual([
      expect.stringContaining("Claim amount"),
      expect.stringContaining("Claim amount"),
      expect.stringContaining("Receipt"),
    ]);
    expect(container).toHaveTextContent("1,200.50");
    expect(container).not.toHaveTextContent("null");
    expect(container.querySelector("a")).toHaveAttribute(
      "href",
      `/api/groups/group-a/runs/${DECLARING.runId}/files/${file}`,
    );
    expect(container.querySelectorAll("a")).toHaveLength(1);
    expect(container).not.toHaveTextContent(first);
  });
  it("lists every input by its label, where it came from and what it was given, where the question says nothing of how it is drawn", () => {
    const container = listed();

    expect(container.querySelector("dt")?.textContent).toBe(
      `${String.fromCodePoint(0x2068)}Title${String.fromCodePoint(0x2069)}`,
    );
    expect(container.querySelector("dd")).toHaveTextContent(
      /^a value written into the workflow\*\*Printer\*\* on fire$/,
    );
    expect(container.querySelector("strong")).toBeNull();
  });

  it("keeps a composed value whole and names its parts in declaration order", () => {
    const container = listed(undefined, [
      {
        input: "customer",
        sources: [
          { targetPath: "name", from: { kind: "constant" } },
          { targetPath: "id", from: { kind: "run_input", path: "ticket" } },
          { targetPath: "address.city", from: { kind: "constant" } },
        ],
        value: { name: "Demo Sample", id: "7", address: { city: "Leeds" } },
      },
    ]);

    expect(container).toHaveTextContent("Demo Sample");
    expect(container).toHaveTextContent("It was put together from these:");
    expect(
      [...container.querySelectorAll("li")].map((item) => item.textContent),
    ).toEqual([
      expect.stringContaining("Name"),
      expect.stringContaining("Identifier"),
      expect.stringContaining("Address"),
    ]);
    expect(container.querySelectorAll("li")[2]).toHaveTextContent("›");
    expect(container.querySelectorAll("li")[2]).toHaveTextContent("City");
    expect(container).not.toHaveTextContent("undefined");
    expect(container.querySelectorAll("button")).toHaveLength(0);
  });

  it("draws the input value once in the question's drawing and names its source beside it without a field list", () => {
    const container = listed("## Ticket\n\n{{title}}");

    expect(container.querySelector("h5")?.textContent).toBe("Ticket");
    expect(container.querySelector("h2")).toBeNull();
    expect(container.querySelector("dl, strong")).toBeNull();
    const [source] = container.querySelectorAll("aside");
    expect(container.querySelectorAll("aside")).toHaveLength(1);
    expect(source).toHaveTextContent("Title");
    expect(source).toHaveTextContent("a value written into the workflow");
    expect(source).not.toHaveTextContent("**Printer** on fire");
    expect(
      container.textContent?.split("a value written into the workflow"),
    ).toHaveLength(2);
    expect(
      [...container.querySelectorAll("p")].filter(
        (paragraph) => paragraph.textContent === "**Printer** on fire",
      ),
    ).toHaveLength(1);
    expect(container.textContent?.split("**Printer** on fire")).toHaveLength(2);
  });
});
