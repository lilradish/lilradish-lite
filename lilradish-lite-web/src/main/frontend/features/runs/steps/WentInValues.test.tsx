import { ThemeProvider } from "@mui/material/styles";
import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import type { ReadField } from "../../../api/filling";
import type { StepRow } from "../../../api/groups/{groupId}/runs/{runId}/steps";
import type { From, WentIn } from "../../../api/stepSources";
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
    "uses the question target declaration despite different planned sources %j",
    (from, label) => {
      const step: StepRow = {
        producedByUserId: null,
        producedByKind: null,
        stepId: "search-step",
        name: "search",
        order: 1,
        runs: { versionName: null, kind: "search", searchWay: "web" },
        takesFrom: [{ input: "amount", from }],
        state: "waiting",
        cost: {},
        stateActs: new Set([]),
        stateRefusals: [],
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
            takes={[
              {
                name: "amount",
                label: "Requested amount",
                kind: "number",
                mustBeGiven: true,
              },
            ]}
            declaring={declaring}
            wentIn={[
              {
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

      expect(container.querySelector("dt,[role=term]")).toHaveTextContent(
        "Requested amount",
      );
      expect(container.querySelector("dt,[role=term]")).not.toHaveTextContent(
        label,
      );
      expect(container).toHaveTextContent("1,200");
      expect(container).not.toHaveTextContent("old amount:");
    },
  );
  it("draws a search composite with the question target labels while retaining cross-run leaf provenance", () => {
    const step: StepRow = {
      producedByUserId: null,
      producedByKind: null,
      stepId: "00000009-0000-4000-8000-000000000e01",
      name: "search",
      order: 1,
      runs: { versionName: null, kind: "search", searchWay: "web" },
      takesFrom: [
        {
          input: "customer",
          from: { kind: "run_input", path: "customer" },
        },
      ],
      state: "waiting",
      cost: {},
      stateActs: new Set([]),
      stateRefusals: [],
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
          takes={TAKES}
          declaring={declaring}
          wentIn={[
            {
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

    expect(container.querySelector("dt,[role=term]")).toHaveTextContent(
      "Customer",
    );
    expect(
      [...container.querySelectorAll("dl > dt")].map(
        (each) => each.textContent,
      ),
    ).toEqual([
      expect.stringContaining("Name"),
      expect.stringContaining("Identifier"),
      expect.stringContaining("City"),
    ]);
    expect(container.querySelector("dl > dd")).toHaveTextContent("run 4");
    expect(container).toHaveTextContent("Demo Sample");
    expect(container).not.toHaveTextContent("undefined");
  });

  it("keeps repeated source paths in distinct question inputs with target types and an optional missing value", () => {
    const file = "11111111-1111-4111-8111-111111111111";
    const step: StepRow = {
      producedByUserId: null,
      producedByKind: null,
      stepId: "00000009-0000-4000-8000-000000000e01",
      name: "search",
      order: 1,
      runs: { versionName: null, kind: "search", searchWay: "web" },
      takesFrom: [
        {
          input: "first_amount",
          from: { kind: "run_input", path: "amount" },
        },
        {
          input: "second_amount",
          from: { kind: "run_input", path: "amount" },
        },
        {
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
      stateActs: new Set([]),
      stateRefusals: [],
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
            takes={[
              {
                name: "first_amount",
                label: "Claim amount",
                kind: "number",
                mustBeGiven: false,
              },
              {
                name: "second_amount",
                label: "Claim amount",
                kind: "number",
                mustBeGiven: false,
              },
              {
                name: "attachment",
                label: "Receipt",
                kind: "file",
                mustBeGiven: false,
              },
            ]}
            declaring={declaring}
            wentIn={[
              {
                input: "first_amount",
                from: {
                  kind: "run_input",
                  path: "parent_total",
                  runId: "00000008-0000-4000-8000-000000000e02",
                  runNumber: 4,
                },
                value: "1200.50",
              },
              {
                input: "second_amount",
                from: { kind: "run_input", path: "amount" },
                value: null,
              },
              {
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
                  size: 7,
                },
              },
            ]}
          />
        </CurrentFileRunContext.Provider>
      </ThemeProvider>,
    ).container;

    expect(
      [...container.querySelectorAll("dt,[role=term]")].map(
        (each) => each.textContent,
      ),
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
    expect(container.querySelectorAll("dt,[role=term]")).toHaveLength(3);
  });
  it("lists every input by its label, where it came from and what it was given, where the question says nothing of how it is drawn", () => {
    const container = listed();

    expect(container.querySelector("dt,[role=term]")?.textContent).toBe(
      `${String.fromCodePoint(0x2068)}Title${String.fromCodePoint(0x2069)} · title`,
    );
    expect(container.querySelector("p")).toHaveTextContent(
      "a fixed value set in the workflow",
    );
    expect(container.querySelector("[role=definition]")).toHaveTextContent(
      /^\*\*Printer\*\* on fire$/,
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
    expect(container).toHaveTextContent("Leeds");
    const copies = [...container.querySelectorAll("button")];
    expect(copies).toHaveLength(5);
    for (const copy of copies) expect(copy).toHaveAccessibleName("Copy");
    expect(container).toHaveTextContent("Combined from:");
    expect(
      [...container.querySelectorAll("dl > dt")].map(
        (item) => item.textContent,
      ),
    ).toEqual([
      expect.stringContaining("Name"),
      expect.stringContaining("Identifier"),
      expect.stringContaining("Address"),
    ]);
    expect(container.querySelectorAll("dl > dt")[2]).toHaveTextContent("›");
    expect(container.querySelectorAll("dl > dt")[2]).toHaveTextContent("City");
    expect(container).not.toHaveTextContent("undefined");
    expect(container.querySelectorAll('[role="term"]')).toHaveLength(5);
  });

  it("draws the input value once in the question's drawing and names its source beside it without a field list", () => {
    const container = listed("## Ticket\n\n{{title}}");

    expect(container.querySelector("h5")?.textContent).toBe("Ticket");
    expect(container.querySelector("h2")).toBeNull();
    expect(container.querySelector("dl:not(aside *), strong")).toBeNull();
    const [source] = container.querySelectorAll("aside");
    expect(container.querySelectorAll("aside")).toHaveLength(1);
    expect(source).toHaveTextContent("Title");
    expect(source).toHaveTextContent("a fixed value set in the workflow");
    expect(source).not.toHaveTextContent("**Printer** on fire");
    expect(
      container.textContent?.split("a fixed value set in the workflow"),
    ).toHaveLength(2);
    expect(
      [...container.querySelectorAll("p")].filter(
        (paragraph) => paragraph.textContent === "**Printer** on fire",
      ),
    ).toHaveLength(1);
    expect(container.textContent?.split("**Printer** on fire")).toHaveLength(2);
  });
});
