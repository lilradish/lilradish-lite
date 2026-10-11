import { ThemeProvider } from "@mui/material/styles";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { describe, expect, it } from "vitest";
import { runFrom } from "../../../api/groups/{groupId}/runs/{runId}";
import type { StepRow } from "../../../api/groups/{groupId}/runs/{runId}/steps";
import { theme } from "../../../lib/theme/theme";
import { STARTED } from "../../../testutil/runPage";
import { inGroup } from "../../../testutil/standingRead";
import { RunInFullDetail } from "./RunInFullDetail";

describe("RunInFullDetail prose", () => {
  it("names the actual parent step by its display metadata while retaining its run identity", () => {
    const {
      startedBy: _starter,
      name: _name,
      startedWith: _values,
      ...nested
    } = STARTED;
    const run = runFrom({
      ...nested,
      above: "parent",
      runsAbove: [
        { runId: "top", number: 5 },
        { runId: "parent", number: 6 },
      ],
      from: {
        runId: "parent",
        number: 6,
        stepId: "parent-step",
        stepName: "handle_claim",
        label: "\u89aa\u306e\u7533\u8acb\u51e6\u7406\u202e",
      },
    });
    expect(run).not.toBeNull();
    render(
      <MemoryRouter>
        <RunInFullDetail
          group={inGroup("imaginary-group", "IMAGINARY", "Imaginary", [])}
          reading={{
            run: run!,
            steps: {
              run: {
                stateActs: null,
                runId: run!.runId,
                number: 7,
                versionId: "version",
                state: "running",
                progress: { done: 0, of: 0 },
              },
              declarations: new Map(),
              gaveBack: { declares: "nothing" },
              steps: [],
            },
          }}
        />
      </MemoryRouter>,
    );

    expect(
      screen.getByRole("region", { name: "Run inputs" }),
    ).toHaveTextContent(/\u89aa\u306e\u7533\u8acb\u51e6\u7406.*run 6/);
    expect(
      screen.getByRole("region", { name: "Run inputs" }),
    ).not.toHaveTextContent("handle claim");
    expect(
      screen.getByRole("region", { name: "Run inputs" }),
    ).not.toHaveTextContent("run 5");
    expect(run!.from!.stepId).toBe("parent-step");
    expect(run!.from!.runId).toBe("parent");
    expect(run!.from!.stepName).toBe("handle_claim");
  });

  it.each([
    [[], "Waiting for the person who started the run."],
    [
      [{ runId: "parent", number: 6 }],
      "Waiting for the person who started the top-level run.",
    ],
    [
      [
        { runId: "top", number: 5 },
        { runId: "parent", number: 6 },
      ],
      "Waiting for the person who started the top-level run.",
    ],
  ] as const)(
    "uses the full run rather than its steps' header beneath %j",
    (runsAbove, sentence) => {
      const { startedBy: _starter, name: _name, ...nested } = STARTED;
      const run = runFrom({
        ...(runsAbove.length === 0 ? STARTED : nested),
        runsAbove,
        ...(runsAbove.length === 0
          ? {}
          : {
              above: runsAbove.at(-1)!.runId,
              from: {
                runId: "parent",
                number: 6,
                stepId: "parent-step",
                stepName: "handle_claim",
              },
            }),
      });
      expect(run).not.toBeNull();
      const step: StepRow = {
        producedByUserId: null,
        producedByKind: null,
        stepId: "prepare-claim",
        order: 1,
        name: "prepare_claim",
        runs: { versionName: null, kind: "question", name: "Prepare a claim" },
        state: "waiting",
        reviewer: { kind: "model", model: "small" },
        where: {
          kind: "waiting_on_review",
          number: 1,
          since: "2026-09-24T08:05:00Z",
          review: "turned_away",
          waitsOn: "starter",
          values: [{ field: "summary" }],
        },
        takesFrom: [],
        cost: {
          spend: {
            sent: "0",
            cameBack: "0",
            spent: "0",
            cameBackUnknown: false,
          },
        },
        stateActs: new Set([]),
        stateRefusals: [],
        waitsOnStarter: runsAbove.length === 0 ? "this_run" : "top_level_run",
      };
      render(
        <ThemeProvider theme={theme}>
          <MemoryRouter>
            <RunInFullDetail
              group={inGroup("imaginary-group", "IMAGINARY", "Imaginary", [])}
              reading={{
                run: run!,
                steps: {
                  run: {
                    stateActs: null,
                    runId: run!.runId,
                    number: 7,
                    versionId: "version",
                    state: "running",
                    progress: { done: 0, of: 1 },
                  },
                  declarations: new Map(),
                  gaveBack: { declares: "nothing" },
                  steps: [step],
                },
              }}
            />
          </MemoryRouter>
        </ThemeProvider>,
      );

      const row = screen.getAllByRole("row")[1]!;
      expect(row).toHaveTextContent(sentence);
      expect(row).toHaveTextContent(
        "Waiting for the model's review, which could not be sent: the model service turned the request away.",
      );
      expect(row).toHaveTextContent(
        "Someone who can start runs can try sending it.",
      );
      expect(row).toHaveTextContent("has not been reviewed yet.");
      expect(row).not.toHaveTextContent(
        runsAbove.length === 0
          ? "Waiting for the person who started the top-level run."
          : "Waiting for the person who started the run.",
      );
      expect(
        within(row).getByRole("link", { name: "prepare claim" }),
      ).toHaveAttribute(
        "href",
        `/groups/IMAGINARY/work/${run!.runId}/steps/prepare-claim`,
      );
    },
  );
  it("switches started-with cards separately, independently of all output cards", () => {
    const first = "**Imaginary packing**\nKeep it.";
    const second = "*Imaginary label*\nCopy it.";
    const code = "**DEMO-CODE-17**";
    const run = runFrom({ ...STARTED, startedWith: { first, second, code } });
    expect(run).not.toBeNull();
    const takes = [
      { name: "first", kind: "text", lines: "several", mustBeGiven: true },
      { name: "second", kind: "text", lines: "several", mustBeGiven: true },
      { name: "code", kind: "text", lines: "one", mustBeGiven: true },
    ] as const;
    render(
      <MemoryRouter>
        <RunInFullDetail
          group={inGroup("imaginary-group", "IMAGINARY", "Imaginary", [])}
          reading={{
            run: run!,
            steps: {
              run: {
                stateActs: null,
                runId: run!.runId,
                number: 7,
                versionId: "imaginary-version",
                state: "done",
                progress: { done: 0, of: 0 },
              },
              declarations: new Map([
                ["imaginary-version", { takes, gives: takes.slice(0, 2) }],
              ]),
              gaveBack: {
                declares: "values",
                standing: [
                  { field: "first", value: "**Imaginary output**\nReady." },
                  { field: "second", value: "*Imaginary conclusion*\nRetain." },
                ],
              },
              steps: [],
            },
          }}
        />
      </MemoryRouter>,
    );
    const started = screen.getByRole("region", {
      name: "Run inputs",
    });
    const output = screen.getByRole("region", { name: "Result" });
    expect(started.querySelectorAll("strong,em")).toHaveLength(2);
    expect(output.querySelectorAll("strong,em")).toHaveLength(2);
    expect(
      within(started).getAllByRole("button", { name: "Show as written" }),
    ).toHaveLength(2);
    expect(
      within(output).getAllByRole("button", { name: "Show as written" }),
    ).toHaveLength(2);
    fireEvent.click(
      within(started).getAllByRole("button", { name: "Show as written" })[0]!,
    );
    expect(started.querySelectorAll("strong,em")).toHaveLength(1);
    fireEvent.click(
      within(started).getByRole("button", { name: "Show as written" }),
    );
    expect(started.querySelectorAll("strong,em")).toHaveLength(0);
    expect(
      within(started)
        .getAllByRole("definition")
        .map((node) => node.textContent),
    ).toEqual([first, second, code]);
    expect(output.querySelectorAll("strong,em")).toHaveLength(2);
    within(started)
      .getAllByRole("button", { name: "Show formatted" })
      .forEach((button) => fireEvent.click(button));
    expect(started.querySelectorAll("strong,em")).toHaveLength(2);
    within(output)
      .getAllByRole("button", { name: "Show as written" })
      .forEach((button) => fireEvent.click(button));
    expect(output.querySelectorAll("strong,em")).toHaveLength(0);
    expect(started.querySelectorAll("strong,em")).toHaveLength(2);
  });
});
