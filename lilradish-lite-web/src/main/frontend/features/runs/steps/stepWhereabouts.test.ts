import { render, screen } from "@testing-library/react";
import { createElement } from "react";
import { describe, expect, it } from "vitest";

import type { StepRow } from "../../../api/groups/{groupId}/runs/{runId}/steps";
import type { Where } from "../../../api/stepWhereabouts";
import { whenText } from "../../../lib/time/When";
import {
  FAILED,
  GIVES,
  HELD,
  HELD_ENTRY_STOPPED,
  NOBODY,
  NOBODY_ACTS,
  OWED,
  setApart,
  step,
  STOPPED_AT,
  UNKNOWN,
  WAITING_ON_REVIEW,
} from "../../../testutil/stepWording";
import { whereSaid } from "./stepWhereabouts";

const STARTER = "Waiting for the person who started the run.";

const HELD_WORKFLOW_STOPPED =
  "This run's workflow has been stopped, so nothing will be produced until it is allowed to be used again.";

const STOPPED_BY_AVO = `Stopped by ${setApart("Avo")}, ${whenText(STOPPED_AT)}.`;

const ZERO_WIDTH_SPACE = String.fromCodePoint(0x200b);

const TRY_SENDING_BY = "Someone who can start runs can try sending it.";

const LET_GO = "Someone who can stop entries can allow it to be used again.";

const LET_GO_THEN_SEND =
  "Someone who can stop entries can allow it to be used again; then someone who can start runs can try sending it.";

const TRY_AGAIN_BY = "Someone who can answer steps can make another attempt.";

const LET_GO_THEN_TRY_AGAIN =
  "Someone who can stop entries can allow it to be used again; then someone who can answer steps can make another attempt.";

const TRIES_SPENT = "All allowed attempts are used up, and none was accepted.";

const CODE_NOT_HELD =
  "The code this step runs isn't in the installed version of this system. The step continues automatically once a system update brings it back.";

const TOO_LONG =
  "Its request is too long for the model, so it was not sent and nothing was spent.";

const TURNED_AWAY = "The model service turned the request away.";

const UNCUTTABLE =
  "Even the parts of the request that can't be shortened are already too long for the model, so shortening inputs can't fix it.";

const MODEL_NOT_HELD =
  "It uses a model, or a model mode, that isn't available on this system.";

function offering(state: string, where: Where): StepRow {
  return { ...step(state, where), stateActs: new Set(["try_sending"]) };
}

describe("whereSaid", () => {
  it.each([
    [
      "Fictional evidence",
      3,
      null,
      `${setApart("Fictional evidence")} · Version 3`,
    ],
    [
      "Fictional evidence",
      3,
      "Spring intake",
      `${setApart("Fictional evidence")} \u00b7 Version 3 \u00b7 ${setApart("Spring intake")}`,
    ],
    [undefined, 3, null, undefined],
    ["Fictional evidence", undefined, null, undefined],
  ] as const)(
    "says an unsent shape mismatch without showing identifiers: %s / %s / %s",
    (name, version, versionName, question) => {
      const of = offering("failed", {
        kind: "failed",
        reason: "search_question_no_longer_matches",
        waitsOn: "starter",
      });
      const said = whereSaid(
        {
          ...of,
          runs: {
            versionName,
            kind: "search",
            searchWay: "web_search",
            name,
            version,
            versionId: "private-version-id",
            entryId: "private-entry-id",
          },
        },
        GIVES,
      );
      expect(said).toEqual([
        "Failed",
        question === undefined
          ? `Its question no longer matches what ${setApart("web_search")} gives back, so nothing was sent and no attempt was used.`
          : `${question} no longer matches what ${setApart("web_search")} gives back, so nothing was sent and no attempt was used.`,
        TRY_SENDING_BY,
      ]);
      expect(JSON.stringify(said)).not.toContain("private-version-id");
      expect(JSON.stringify(said)).not.toContain("private-entry-id");
    },
  );
  it.each([
    ["top-level", false, [STARTER]],
    [
      "beneath another run",
      true,
      ["Waiting for the person who started the top-level run."],
    ],
    ["not read yet", undefined, []],
  ] as const)(
    "says whom a starter wait belongs to when the run is %s",
    (_case, subRun, starter) => {
      const of = step("waiting", {
        ...WAITING_ON_REVIEW,
        number: 1,
        since: "2026-09-24T08:05:00Z",
        review: "turned_away",
        waitsOn: "starter",
      });

      const said = whereSaid(
        {
          ...of,
          waitsOnStarter:
            subRun === undefined
              ? undefined
              : subRun
                ? "top_level_run"
                : "this_run",
        },
        GIVES,
        undefined,
        undefined,
        "",
      );

      expect(said).toEqual([
        "Waiting",
        `${setApart("Summary")} has not been reviewed yet.`,
        "Waiting for the model's review, which could not be sent: the model service turned the request away.",
        TRY_SENDING_BY,
        ...starter,
      ]);
      for (const other of [
        STARTER,
        "Waiting for the person who started the top-level run.",
      ].filter(
        (sentence) => !starter.some((expected) => expected === sentence),
      )) {
        expect(said).not.toContain(other);
      }
      expect(said).not.toContain("It waits on whoever started the run.");
      expect(said.filter((line) => line === TRY_SENDING_BY)).toHaveLength(1);
    },
  );

  it("says a missing search way goes on after a release, without offering a person a retry", () => {
    const of = step("held_back", {
      kind: "held_back",
      reason: "search_way_not_held",
      waitsOn: "answer_step",
    });
    expect(whereSaid(of, GIVES)).toEqual([
      "Blocked",
      "The search method this step uses isn't in the installed version of this system. The step continues automatically once a system update brings it back.",
    ]);
  });

  it.each(
    [false, true, undefined].flatMap((subRun) =>
      [
        {
          state: "held_back",
          reason: "turned_away",
          expected: ["Blocked", TURNED_AWAY, TRY_SENDING_BY],
        },
        {
          state: "failed",
          reason: "tries_spent",
          expected: ["Failed", TRIES_SPENT, TRY_AGAIN_BY],
        },
      ].map((of) => ({ ...of, subRun })),
    ),
  )(
    "keeps $state distinct from waiting with run hierarchy $subRun",
    ({ state, reason, expected, subRun }) => {
      const where = { kind: state, reason, waitsOn: "starter" };
      const of =
        state === "held_back" ? offering(state, where) : step(state, where);

      const said = whereSaid(
        {
          ...of,
          waitsOnStarter:
            subRun === undefined
              ? undefined
              : subRun
                ? "top_level_run"
                : "this_run",
        },
        GIVES,
        undefined,
        undefined,
        "",
      );

      expect(said).toEqual(expected);
      expect(said).not.toContain("Waiting");
      expect(said.join(" ")).not.toContain("person who started");
    },
  );

  it("names the failed search's saved tool and producer mode, and offers sending rather than another try", () => {
    const of = {
      ...step("failed", {
        kind: "failed",
        reason: "tool_not_offered",
        model: "small",
        mode: "careful",
        tool: "web_search",
        waitsOn: "answer_step",
      }),
      stateActs: new Set(["try_sending" as const]),
      runs: {
        versionName: null,
        kind: "search",
        searchWay: "web",
        tool: "a_new_tool",
      },
    };
    const lines = whereSaid(of, GIVES);
    expect(lines).toContain(
      `It uses small in careful to search, but that model doesn't offer ${setApart("web_search")}, which this search method needs.`,
    );
    expect(lines.join(" ")).not.toContain("a_new_tool");
    expect(lines.join(" ")).not.toContain("another try");
    expect(lines).toContain("Someone who can start runs can try sending it.");
  });
  it.each([
    [
      "a value waiting on review at a gate",
      step("waiting", { ...WAITING_ON_REVIEW, waitsOn: "review_at_gate" }),
      [
        "Waiting",
        `${setApart("Summary")} has not been reviewed yet.`,
        "Waiting for a reviewer.",
      ],
    ],
    [
      "a value waiting on a model's review",
      step("waiting", { ...WAITING_ON_REVIEW, waitsOn: "model" }),
      [
        "Waiting",
        `${setApart("Summary")} has not been reviewed yet.`,
        "Waiting for the model's review.",
      ],
    ],
    [
      "a try asked for and owed",
      step("waiting", { ...OWED, waitsOn: "answer_step" }),
      [
        "Waiting",
        "Attempt 2 has been requested.",
        "Waiting for a person to make the next attempt.",
      ],
    ],
    [
      "a try owed and not asked for",
      step("waiting", { ...OWED, open: false, waitsOn: "answer_step" }),
      [
        "Waiting",
        "Attempt 2 has not been requested yet.",
        "Waiting for a person to make the next attempt.",
      ],
    ],
  ])("says whom %s waits on, after where it is and why", (_case, of, said) => {
    const lines = whereSaid(of, GIVES);

    expect(lines).toEqual(said);
    expect(lines).not.toContain(NOBODY);
  });

  it("names the actual source when a later step cannot send its code's value, without saying the later step runs missing code", () => {
    const of: StepRow = {
      ...step("held_back", {
        kind: "held_back",
        reason: "code_step_not_held",
        waitsOn: "starter",
        source: {
          runId: "00000008-0000-4000-8000-000000000c92",
          runNumber: 19,
          stepId: "00000009-0000-4000-8000-000000000c92",
          name: "Demo Sample source",
          codeStep: "tally",
        },
      }),
      runs: {
        versionName: null,
        kind: "question",
        name: "Demo Sample destination",
      },
    };

    const lines = whereSaid(of, GIVES);

    expect(lines).toEqual([
      "Blocked",
      `An input comes from ${setApart("Demo Sample source")}, whose code isn't in the installed version of this system, so it can't be sent. It continues automatically once a system update brings that code back.`,
    ]);
    expect(lines).not.toContain(CODE_NOT_HELD);
    expect(lines.join(" ")).not.toContain("Demo Sample destination");
    expect(lines).not.toContain(TRY_SENDING_BY);
    expect(lines.join(" ")).not.toMatch(/Waiting for/);
  });

  it("uses the held source's display name without borrowing the destination or changing its machine identity", () => {
    const source = {
      runId: "source-run",
      runNumber: 19,
      stepId: "source-step",
      name: "file_it",
      label: "申請を記録\u202e",
      codeStep: "tally",
    };
    const of: StepRow = {
      ...step("held_back", {
        kind: "held_back",
        reason: "code_step_not_held",
        waitsOn: "starter",
        source,
      }),
      label: "回答を確認",
      runs: { versionName: null, kind: "question", name: "Check the response" },
    };

    expect(whereSaid(of, GIVES)).toEqual([
      "Blocked",
      `An input comes from ${setApart("申請を記録\u202e")}, whose code isn't in the installed version of this system, so it can't be sent. It continues automatically once a system update brings that code back.`,
    ]);
    expect(whereSaid(of, GIVES).join(" ")).not.toContain("file it");
    expect(whereSaid(of, GIVES).join(" ")).not.toContain("回答を確認");
    expect(source.name).toBe("file_it");
    expect(source.stepId).toBe("source-step");
    expect(source.runId).toBe("source-run");
  });

  it.each([
    [
      "source_no_longer_matches",
      "failedSourceNoLongerMatches",
      `The output ${setApart("info.answer")} of ${setApart("Demo Sample source")} no longer matches what this step expects from it, now that the code step has changed, so nothing was sent.`,
    ],
    [
      "source_list_not_here",
      "failedSourceListNotHere",
      `The options for ${setApart("info.answer")}, from ${setApart("Demo Sample source")}, come from a reference list version that isn't in service in this group, so nothing was sent.`,
    ],
  ] as const)(
    "names the source field for %s and offers sending instead of another try or automatic release",
    (reason, member, sentence) => {
      const of: StepRow = {
        ...step("failed", {
          kind: "failed",
          reason,
          waitsOn: "starter",
          [member]: {
            source: {
              runId: "00000008-0000-4000-8000-000000000c92",
              runNumber: 19,
              stepId: "00000009-0000-4000-8000-000000000c92",
              name: "Demo Sample source",
              codeStep: "tally",
            },
            field: "info.answer",
          },
        }),
        stateActs: new Set(["try_sending"]),
        takesFrom: [
          { input: "text", from: { kind: "step", path: "info.answer" } },
        ],
      };

      const lines = whereSaid(of, GIVES);

      expect(lines).toEqual(["Failed", sentence, TRY_SENDING_BY]);
      expect(lines).not.toContain(TRY_AGAIN_BY);
      expect(lines).not.toContain(CODE_NOT_HELD);
      expect(lines.join(" ")).not.toContain("gave back as text");
      expect(lines.join(" ")).not.toContain("goes on by itself");
    },
  );

  /** Held back and failed never wait on anybody: they say who can deal with them, or nothing where nobody can. */
  it.each([
    [
      "a step held back on a stop, who can let it go",
      step("held_back", { ...HELD, waitsOn: "starter" }),
      ["Blocked", HELD_ENTRY_STOPPED, STOPPED_BY_AVO, LET_GO],
    ],
    [
      "a step whose code the running release does not hold, which goes on by itself, nothing more",
      {
        ...step("held_back", {
          kind: "held_back",
          reason: "code_step_not_held",
          waitsOn: "starter",
        }),
        runs: { versionName: null, kind: "code_step", codeStep: "tally" },
      },
      ["Blocked", CODE_NOT_HELD],
    ],
    [
      "a step whose every try is spent, which somebody may try again",
      step("failed", { ...FAILED, waitsOn: "starter" }),
      ["Failed", TRIES_SPENT, TRY_AGAIN_BY],
    ],
  ])("says of %s", (_case, of, said) => {
    const lines = whereSaid(of, GIVES);

    expect(lines).toEqual(said);
    expect(lines.join(" ")).not.toMatch(/Waiting for/);
    expect(lines).not.toContain(TRY_SENDING_BY);
  });

  it.each([
    ["held back", "too_long", TOO_LONG],
    ["held back", "turned_away", TURNED_AWAY],
    ["failed", "uncuttable_length", UNCUTTABLE],
    ["failed", "model_not_deployed", MODEL_NOT_HELD],
  ])(
    "says a step %s for %s why in the reader's words, then that somebody who may start a run can try sending it",
    (state, reason, sentence) => {
      const kind = state === "failed" ? "failed" : "held_back";
      const lines = whereSaid(
        offering(kind, { kind, reason, waitsOn: "starter" }),
        GIVES,
      );

      expect(lines).toEqual([
        state === "failed" ? "Failed" : "Blocked",
        sentence,
        TRY_SENDING_BY,
      ]);
      expect(lines).not.toContain(STARTER);
      expect(lines.join(" ")).not.toContain(reason);
    },
  );

  /** Whose roles reach it does not change the resource's possible sending. */
  it.each([
    ["the reader's roles do not reach it", ["try_sending"], TRY_SENDING_BY],
    ["nothing offers it", [], undefined],
  ])(
    "says of a step held back as too long, where %s, who can try sending it",
    (_case, offered, ending) => {
      const lines = whereSaid(
        {
          ...step("held_back", {
            kind: "held_back",
            reason: "too_long",
            waitsOn: "starter",
          }),
          stateActs: new Set(offered),
        },
        GIVES,
      );

      expect(lines.at(-1)).toBe(ending ?? TOO_LONG);
      expect(lines).toHaveLength(ending === undefined ? 2 : 3);
    },
  );

  /** Do not invent a route value absent from the read. */
  it("says a route failed as no branch took its value is something it cannot say yet, never without the value", () => {
    for (const waitsOn of ["starter", undefined]) {
      for (const stopped of [undefined, HELD.stopped]) {
        const lines = whereSaid(
          {
            ...step("failed", {
              kind: "failed",
              reason: "unclaimed_value",
              ...(waitsOn === undefined ? {} : { waitsOn }),
              ...(stopped === undefined
                ? {}
                : { stopped: { ...stopped, what: "workflow" } }),
            }),
            runs: { versionName: null, kind: "route" },
          },
          GIVES,
        );

        expect(lines).toEqual(["Failed", UNKNOWN]);
        expect(lines.join(" ")).not.toMatch(
          /branch|claimed|unclaimed_value|Waiting for/,
        );
      }
    }
  });

  it.each([
    ["held back as too long", "held_back", "too_long", TOO_LONG],
    [
      "held back as the model would not take it",
      "held_back",
      "turned_away",
      TURNED_AWAY,
    ],
    ["failed as too long to cut", "failed", "uncuttable_length", UNCUTTABLE],
    [
      "failed as its model is not held",
      "failed",
      "model_not_deployed",
      MODEL_NOT_HELD,
    ],
  ])(
    "says what it runs was stopped, and by whom and when, before why a step %s is so, then who lets it go before it is sent",
    (_case, kind, reason, sentence) => {
      const lines = whereSaid(
        {
          ...step(
            kind,
            { kind, reason, stopped: HELD.stopped!, waitsOn: "starter" },
            [{ act: "try_sending", refusal: "ENTRY_STOPPED" }],
          ),
          letGoThen: new Set(["try_sending"]),
        },
        GIVES,
      );

      expect(lines).toEqual([
        kind === "failed" ? "Failed" : "Blocked",
        HELD_ENTRY_STOPPED,
        STOPPED_BY_AVO,
        sentence,
        LET_GO_THEN_SEND,
      ]);
      expect(lines).not.toContain(TRY_SENDING_BY);
    },
  );

  it("says the stop first on a step held back as its code is not held, then that it goes on when a release holds it again", () => {
    const lines = whereSaid(
      {
        ...step("held_back", {
          kind: "held_back",
          reason: "code_step_not_held",
          stopped: { ...HELD.stopped!, what: "workflow" },
          waitsOn: "starter",
        }),
        runs: { versionName: null, kind: "code_step", codeStep: "tally" },
      },
      GIVES,
    );

    expect(lines).toEqual([
      "Blocked",
      HELD_WORKFLOW_STOPPED,
      STOPPED_BY_AVO,
      CODE_NOT_HELD,
    ]);
  });

  it("draws the term no branch of a route took isolated in its sentence, a character in it that shows nothing drawn as a marker and never itself", () => {
    const failed = step("failed", {
      kind: "failed",
      reason: "unclaimed_value",
    });
    const [state, ...why] = whereSaid(
      {
        ...failed,
        where: {
          ...failed.where!,
          failedUnclaimed: { term: `Demo${ZERO_WIDTH_SPACE}Sample` },
        },
      },
      GIVES,
    );
    const drawn = render(createElement("p", null, ...why)).container;

    expect(state).toBe("Failed");
    expect(why).toHaveLength(1);
    expect(drawn.textContent).toBe(
      "No branch of this route handles “DemoU+200BSample”, and it has no fallback. To go on, create a new version of this workflow and start a new run.",
    );
    expect(drawn.querySelector("bdi")?.textContent).toBe("DemoU+200BSample");
    expect(
      screen.getByRole("img", {
        name: "An invisible character is here: U+200B.",
      }),
    ).toBeInTheDocument();
    expect(drawn.innerHTML).not.toContain(ZERO_WIDTH_SPACE);
    expect(drawn.textContent).not.toContain("unclaimed_value");
  });

  it("says a route had no value to choose by in words alone, naming no term and drawing no marker", () => {
    const failed = step("failed", {
      kind: "failed",
      reason: "unclaimed_value",
    });

    expect(
      whereSaid(
        {
          ...failed,
          where: { ...failed.where!, failedUnclaimed: { term: null } },
        },
        GIVES,
      ),
    ).toEqual([
      "Failed",
      "This route had no value to choose a branch by, and it has no fallback. To go on, create a new version of this workflow and start a new run.",
    ]);
  });

  it("says a model-produced route choice is withheld without naming the value or an action", () => {
    const failed = step("failed", {
      kind: "failed",
      reason: "unclaimed_value",
    });
    const lines = whereSaid(
      {
        ...failed,
        where: { ...failed.where!, failedUnclaimed: { withheld: true } },
      },
      GIVES,
    );

    expect(lines).toEqual([
      "Failed",
      "No branch of this route handles the value it chose by — a model's answer you don't have permission to see — and it has no fallback. To go on, create a new version of this workflow and start a new run.",
    ]);
    expect(lines.join(" ")).not.toMatch(
      /Demo Sample|unclaimed_value|Reject|Try sending/,
    );
  });

  /** An entry stop cannot mend a route whose value no branch took. */
  it.each([["no branch taking its value", "unclaimed_value", UNKNOWN]])(
    "says only the failure of a step failed for %s while what it runs is stopped",
    (_case, reason, sentence) => {
      const lines = whereSaid(
        {
          ...step("failed", {
            kind: "failed",
            reason,
            stopped: { ...HELD.stopped!, what: "workflow" },
            waitsOn: "starter",
          }),
          runs: { versionName: null, kind: "route" },
        },
        GIVES,
      );

      expect(lines).toEqual(["Failed", sentence]);
      expect(lines).not.toContain(HELD_ENTRY_STOPPED);
      expect(lines.join(" ")).not.toMatch(/Stopped|allow it to be used again/);
    },
  );

  it.each([
    ["entry", HELD_ENTRY_STOPPED],
    ["workflow", HELD_WORKFLOW_STOPPED],
  ])(
    "says a stopped %s must be let go before a spent step is tried again, with or without its stopper named",
    (what, stop) => {
      for (const by of [HELD.stopped!.by, undefined]) {
        const lines = whereSaid(
          step("failed", {
            ...FAILED,
            stopped: {
              what,
              at: STOPPED_AT,
              ...(by === undefined ? {} : { by }),
            },
            waitsOn: "starter",
          }),
          GIVES,
        );

        expect(lines).toEqual([
          "Failed",
          stop,
          by === undefined
            ? `Stopped ${whenText(STOPPED_AT)}, by someone no longer in this system.`
            : STOPPED_BY_AVO,
          TRIES_SPENT,
          LET_GO_THEN_TRY_AGAIN,
        ]);
      }
    },
  );

  it.each([
    ["tries spent", "failed", "tries_spent", TRIES_SPENT],
    ["too long to send", "held_back", "too_long", TOO_LONG],
    ["a model not held", "failed", "model_not_deployed", MODEL_NOT_HELD],
    ["code not held", "held_back", "code_step_not_held", CODE_NOT_HELD],
  ])(
    "says the run must be opened again before %s can go on, even when what it runs is also stopped",
    (_case, kind, reason, why) => {
      for (const stopped of [undefined, HELD.stopped]) {
        const lines = whereSaid(
          {
            ...step(kind, {
              kind,
              reason,
              ...(stopped === undefined
                ? {}
                : {
                    stopped: {
                      ...stopped,
                      what:
                        reason === "code_step_not_held" ? "workflow" : "entry",
                    },
                  }),
            }),
            ...(reason === "code_step_not_held"
              ? {
                  runs: {
                    versionName: null,
                    kind: "code_step",
                    codeStep: "tally",
                  },
                }
              : {}),
          },
          GIVES,
        );

        expect(lines).toEqual([
          kind === "failed" ? "Failed" : "Blocked",
          ...(stopped === undefined
            ? []
            : [
                reason === "code_step_not_held"
                  ? HELD_WORKFLOW_STOPPED
                  : HELD_ENTRY_STOPPED,
                STOPPED_BY_AVO,
              ]),
          why,
          NOBODY_ACTS,
        ]);
      }
    },
  );

  it("says an unknown kind of stop as unknown, keeping who stopped it and when", () => {
    expect(
      whereSaid(
        step("held_back", {
          ...HELD,
          stopped: { ...HELD.stopped!, what: "a_ledger" },
          waitsOn: "starter",
        }),
        GIVES,
      ),
    ).toEqual(["Blocked", UNKNOWN, STOPPED_BY_AVO, LET_GO]);
  });

  it("says the run's workflow was stopped by somebody gone before a failure sending may mend", () => {
    const { by: _gone, ...unnamed } = HELD.stopped!;

    const lines = whereSaid(
      offering("failed", {
        kind: "failed",
        reason: "uncuttable_length",
        stopped: { ...unnamed, what: "workflow" },
        waitsOn: "starter",
      }),
      GIVES,
    );

    expect(lines).toEqual([
      "Failed",
      HELD_WORKFLOW_STOPPED,
      `Stopped ${whenText(STOPPED_AT)}, by someone no longer in this system.`,
      UNCUTTABLE,
      LET_GO_THEN_SEND,
    ]);
    expect(lines).not.toContain(HELD_ENTRY_STOPPED);
  });

  it("says a step held back as the model would not take it, its spending used up, as that, then who can try sending it", () => {
    const lines = whereSaid(
      offering("held_back", {
        kind: "held_back",
        reason: "turned_away",
        spentUp: true,
        waitsOn: "starter",
      }),
      GIVES,
    );

    expect(lines).toEqual([
      "Blocked",
      "The model service turned the request away because the usage allowance for that model is used up, so it won't be retried automatically.",
      TRY_SENDING_BY,
    ]);
    expect(lines).not.toContain(TURNED_AWAY);
  });

  it.each([
    [
      "to produce it, run as it is",
      { model: "small" },
      "It uses small to answer, which isn't available on this system.",
    ],
    [
      "to review it, in a mode",
      { model: "judge", mode: "strict", reviewing: true as const },
      "It uses judge in strict to review, which isn't available on this system.",
    ],
  ])(
    "says a step failed for a model not held %s by the model's name and mode",
    (_case, named, sentence) => {
      const lines = whereSaid(
        offering("failed", {
          kind: "failed",
          reason: "model_not_deployed",
          ...named,
          waitsOn: "starter",
        }),
        GIVES,
      );

      expect(lines).toEqual(["Failed", sentence, TRY_SENDING_BY]);
      expect(lines.join(" ")).not.toContain("It names a model");
      expect(lines.join(" ")).not.toContain("model_not_deployed");
    },
  );

  it.each([
    [
      1,
      "Sub-runs can be nested at most 1 level deep, counting the top-level run, and this step would have gone one level deeper. To go on, create a new version of this workflow and start a new run.",
    ],
    [
      32,
      "Sub-runs can be nested at most 32 levels deep, counting the top-level run, and this step would have gone one level deeper. To go on, create a new version of this workflow and start a new run.",
    ],
  ])(
    "says a step failed for starting a run deeper than runs may go, at most %i deep, and offers no way on",
    (most, sentence) => {
      const lines = whereSaid(
        {
          ...step("failed", {
            kind: "failed",
            reason: "too_deep",
            failedTooDeep: { most },
            waitsOn: "starter",
          }),
          runs: { versionName: null, kind: "workflow", name: "Demo Sample" },
        },
        GIVES,
      );

      expect(lines).toEqual(["Failed", sentence]);
      expect(lines.join(" ")).not.toMatch(
        /too_deep|\{|attempt|Waiting for|allow it to be used again/,
      );
    },
  );

  it.each([
    ["while the run is stopped", {}],
    [
      "while what it runs is stopped",
      { stopped: HELD.stopped!, waitsOn: "starter" },
    ],
    ["though sending is offered", { waitsOn: "starter" }],
  ])(
    "says only why a step that would have started a run too deep failed, %s",
    (_case, around) => {
      const lines = whereSaid(
        offering("failed", {
          kind: "failed",
          reason: "too_deep",
          failedTooDeep: { most: 32 },
          ...around,
        }),
        GIVES,
      );

      expect(lines).toEqual([
        "Failed",
        expect.stringMatching(/^Sub-runs can be nested at most 32 levels deep/),
      ]);
      expect(lines).not.toContain(NOBODY_ACTS);
      expect(lines).not.toContain(TRY_SENDING_BY);
      expect(lines).not.toContain(HELD_ENTRY_STOPPED);
      expect(lines).not.toContain(STOPPED_BY_AVO);
    },
  );

  it("says a step failed for going deeper without saying how deep is something it cannot say yet", () => {
    const lines = whereSaid(
      step("failed", {
        kind: "failed",
        reason: "too_deep",
        waitsOn: "starter",
      }),
      GIVES,
    );

    expect(lines).toEqual(["Failed", UNKNOWN]);
    expect(lines.join(" ")).not.toMatch(/Sub-runs can be nested|\{|too_deep/);
  });

  it("says what the run's own workflow being stopped holds back, and who stopped it and when", () => {
    const lines = whereSaid(
      step("held_back", {
        ...HELD,
        stopped: { ...HELD.stopped!, what: "workflow" },
        waitsOn: "starter",
      }),
      GIVES,
    );

    expect(lines).toEqual([
      "Blocked",
      HELD_WORKFLOW_STOPPED,
      STOPPED_BY_AVO,
      LET_GO,
    ]);
    expect(lines).not.toContain(HELD_ENTRY_STOPPED);
  });

  it("says when what holds a step back was stopped, by somebody who can no longer be found, where nobody is named", () => {
    const { by: _gone, ...unnamed } = HELD.stopped!;

    const lines = whereSaid(
      step("held_back", { ...HELD, stopped: unnamed, waitsOn: "starter" }),
      GIVES,
    );

    expect(lines).toEqual([
      "Blocked",
      HELD_ENTRY_STOPPED,
      `Stopped ${whenText(STOPPED_AT)}, by someone no longer in this system.`,
      LET_GO,
    ]);
    expect(lines.join(" ")).not.toMatch(/Stopped:/);
  });

  /** Let go, a model's step keeps its hold on the stop until this system next acts on it, which it does while the run runs. */
  it.each([
    [
      "runs",
      "starter",
      [
        "Blocked",
        "What stopped it is allowed to be used again, so it continues automatically while the run is running.",
      ],
    ],
    [
      "is stopped",
      undefined,
      [
        "Blocked",
        "What stopped it is allowed to be used again, so it continues automatically while the run is running.",
        NOBODY_ACTS,
      ],
    ],
  ])(
    "says a step held back on a stop let go goes on by itself, while its run %s",
    (_case, waitsOn, said) => {
      const lines = whereSaid(
        step("held_back", {
          kind: "held_back",
          reason: "entry_stopped",
          ...(waitsOn === undefined ? {} : { waitsOn }),
        }),
        GIVES,
      );

      expect(lines).toEqual(said);
      expect(lines).not.toContain(HELD_ENTRY_STOPPED);
      expect(lines).not.toContain(LET_GO);
    },
  );

  it.each([
    ["held back", "held_back", "Blocked"],
    ["failed", "failed", "Failed"],
  ])(
    "says why a step %s is so is something it cannot say yet, where the reason is spelt in a word this build does not know",
    (_case, kind, state) => {
      for (const reason of ["budget_gone", undefined]) {
        for (const waitsOn of ["starter", undefined]) {
          const lines = whereSaid(
            step(kind, {
              kind,
              ...(reason === undefined ? {} : { reason }),
              ...(waitsOn === undefined ? {} : { waitsOn }),
              stopped: HELD.stopped!,
            }),
            GIVES,
          );

          expect(lines).toEqual([state, UNKNOWN]);
          expect(lines.join(" ")).not.toMatch(/budget_gone|Waiting for/);
        }
      }
    },
  );

  it.each([
    ["a value waiting on review", step("waiting", WAITING_ON_REVIEW)],
    ["a try owed", step("waiting", OWED)],
  ])(
    "says %s waits on nobody on a stopped run, whom the server names nobody for",
    (_case, of) => {
      const lines = whereSaid(of, GIVES);

      expect(lines.at(-1)).toBe(NOBODY);
      expect(lines.join(" ")).not.toMatch(
        /Waiting for (a reviewer|a person|the model)/,
      );
    },
  );

  it.each([
    ["a step held back on a stop", step("held_back", HELD)],
    [
      "a step held back that sending may mend",
      offering("held_back", { kind: "held_back", reason: "turned_away" }),
    ],
    ["a step failed", step("failed", FAILED)],
  ])(
    "says nobody can act on %s on a stopped run, and never that it waits",
    (_case, of) => {
      const lines = whereSaid(of, GIVES);

      expect(lines.at(-1)).toBe(NOBODY_ACTS);
      expect(lines.join(" ")).not.toMatch(
        /Waiting for|can try sending|allow it/,
      );
    },
  );

  it("says whom it waits on is something it cannot say yet, where that is spelt in a word this build does not know", () => {
    const lines = whereSaid(
      step("waiting", { ...OWED, waitsOn: "treasurer" }),
      GIVES,
    );

    expect(lines).toEqual([
      "Waiting",
      "Attempt 2 has been requested.",
      UNKNOWN,
    ]);
  });

  it("says once, and nothing of whom it waits on, that a step is somewhere this build does not know", () => {
    const lines = whereSaid(
      step("paused", { kind: "on_hold", waitsOn: "starter" }),
      GIVES,
    );

    expect(lines).toEqual([UNKNOWN]);
  });

  it("says why a step in a state it knows is there is something it cannot say yet, where that is a kind this build does not know", () => {
    for (const waitsOn of ["starter", undefined]) {
      const lines = whereSaid(
        step("waiting", {
          kind: "on_hold",
          ...(waitsOn === undefined ? {} : { waitsOn }),
        }),
        GIVES,
      );

      expect(lines).toEqual(["Waiting", UNKNOWN]);
      expect(lines.join(" ")).not.toMatch(/Waiting for/);
    }
  });

  it.each([
    ["its code", "code", "Its code is running."],
    ["a call to its model", "call", "A request to the model is in progress."],
    [
      "a try this system makes by itself",
      "next_try",
      "The system makes the next attempt automatically while the run is running.",
    ],
  ])(
    "says of a running step, beneath where it is, that it has %s out, and nothing of whom it waits on",
    (_case, on, sentence) => {
      const lines = whereSaid(step("running", { kind: "running", on }), GIVES);

      expect(lines).toEqual(["Running", sentence]);
      expect(lines.join(" ")).not.toMatch(/Waiting for/);
    },
  );

  it.each([
    [
      "running with nothing said of what it has out",
      step("running", { kind: "running" }),
    ],
    [
      "running with what it has out spelt in a word this build does not know",
      step("running", { kind: "running", on: "a_ledger" }),
    ],
    ["not started", step("not_started")],
    ["done", step("done")],
  ])("says only where a step is where it is %s", (_case, of) => {
    const lines = whereSaid(of, GIVES);

    expect(lines).toHaveLength(1);
    expect(lines).not.toContain(UNKNOWN);
  });
});

describe("model review words", () => {
  it.each([
    [
      "unsent",
      "Waiting for the model's review, which the system will request automatically.",
    ],
    ["out", "Waiting for the model's review — request in progress."],
    [
      "turned_away",
      "Waiting for the model's review, which could not be sent: the model service turned the request away.",
    ],
    [
      "too_long",
      "The review request would be too long for the reviewing model, so it was not sent. Someone who can review does it instead.",
    ],
    [
      "list_not_here",
      "This couldn't go to the reviewing model: a reference list it uses isn't available here, so a person reviews it instead.",
    ],
    [
      "takes_no_longer_declared",
      "This couldn't go to the reviewing model: the installed version of this code step no longer defines its inputs, so a person reviews it instead.",
    ],
    [
      "no_longer_declared",
      "This couldn't go to the reviewing model: the installed version of this code step no longer defines its outputs, so a person reviews it instead.",
    ],
    [
      "holds_a_file",
      "This couldn't go to the reviewing model: it includes a file, and files are never sent to a model, so a person reviews it instead.",
    ],
  ])(
    "says a waiting review that is %s in its own words",
    (review, sentence) => {
      const said = whereSaid(
        step("waiting", { ...WAITING_ON_REVIEW, review, waitsOn: "model" }),
        GIVES,
      );
      expect(said).toEqual([
        "Waiting",
        `${setApart("Summary")} has not been reviewed yet.`,
        sentence,
        ...(review === "turned_away" ? [TRY_SENDING_BY] : []),
      ]);
      expect(said).not.toContain("Blocked");
      expect(said).not.toContain("It waits on the model it names.");
    },
  );

  it("says a spent-up review can be tried by a person and a stopped run permits nobody", () => {
    const where = {
      ...WAITING_ON_REVIEW,
      review: "turned_away",
      spentUp: true as const,
    };
    expect(
      whereSaid(step("waiting", { ...where, waitsOn: "starter" }), GIVES),
    ).toEqual([
      "Waiting",
      `${setApart("Summary")} has not been reviewed yet.`,
      "Waiting for the model's review, which could not be sent: the usage allowance for that model is used up, so it won't be retried automatically.",
      TRY_SENDING_BY,
    ]);
    expect(whereSaid(step("waiting", where), GIVES)).toEqual([
      "Waiting",
      `${setApart("Summary")} has not been reviewed yet.`,
      "Waiting for the model's review, which could not be sent: the usage allowance for that model is used up, so it won't be retried automatically.",
      NOBODY,
    ]);
  });

  it("says a review spelling unknown here as unknown without printing its wire text", () => {
    expect(
      whereSaid(
        step("waiting", {
          ...WAITING_ON_REVIEW,
          review: "later",
          waitsOn: "model",
        }),
        GIVES,
      ),
    ).toEqual([
      "Waiting",
      `${setApart("Summary")} has not been reviewed yet.`,
      UNKNOWN,
    ]);
  });
});
