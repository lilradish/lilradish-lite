import { buttonClasses } from "@mui/material/Button";
import { act, screen, waitFor, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { afterEach, describe, expect, it } from "vitest";

import { LEAST_TABLE_WIDTH } from "../../lib/collection/RowPanel";
import { theme } from "../../lib/theme/theme";
import { requestsTo } from "../../testutil/answering";
import { deferred } from "../../testutil/deferred";
import { resolved } from "../../testutil/grounds";
import {
  laysPairsBeside,
  wideKeptBesideNarrow,
} from "../../testutil/writtenRules";

import {
  GROUP,
  RUN_ID,
  ABOVE,
  WORKFLOW,
  RAISE,
  PAGE,
  KEPT_DRAWING_AS,
  RUN,
  AVO,
  GLINN,
  START_RUN_RULE,
  APPROVE_ENTRY_RULE,
  SUMMARISE,
  FILE_IT,
  CLAIM,
  STOPPED,
  RAISED,
  ASKED,
  reply,
  refusal,
  UNANSWERED,
  STEPS,
  VERSION,
  FROM_ABOVE,
  SUMMARY,
  RUN_HEADER,
  DECLARATIONS,
  SUMMARISE_ROW,
  FILE_IT_ROW,
  stepsRead,
  opening,
  opened,
  setApart,
  ceiling,
  said,
  about,
  drawn,
  runReads,
} from "../../testutil/runPage";

afterEach(() => {
  localStorage.clear();
});
describe("RunPage", () => {
  /** Every count grouped as the reader groups one, and none of its digits lost on the way. */
  it("heads the page with the run's name, and says About it what ran, who started it, where it is and how far it has got, what it has cost and its ceiling", async () => {
    opening();

    expect(await opened()).toHaveTextContent(setApart("Claim from Avo"));
    const facts = said();
    expect(Object.keys(facts)).toEqual([
      "Number",
      "Workflow",
      "Started",
      "Where it is",
      "What it has cost",
      "Ceiling",
    ]);
    expect(facts).toMatchObject({
      Number: "7",
      Workflow: `${setApart("Handle a claim")}, version 3`,
      "Where it is": "Running1 of 2 steps done",
      "What it has cost":
        "9,007,199,254,741,013 in all: 9,007,199,254,740,993 sent, 20 came back",
      Ceiling: "9,007,199,254,740,990Change the ceiling",
    });
    expect(facts.Started).toMatch(
      new RegExp(`^By ${setApart("Avo Lumerace")}, .+$`),
    );
    const where = within(about()).getByText("Where it is").nextElementSibling!;
    expect(where.querySelector("bdi")).toBeNull();
    expect(within(where as HTMLElement).queryByText("Running, on")).toBeNull();
    expect(
      screen.getByRole("link", { name: /Handle a claim/ }),
    ).toHaveAttribute("href", `/groups/${GROUP}/workflows/${WORKFLOW}`);
  });

  it("names the run as the page's one first-level heading, the parts of its work and About it each under it at the second", async () => {
    opening();
    const title = await opened();
    await screen.findByRole("table", { name: "The steps" });

    expect(
      screen
        .getAllByRole("heading")
        .map((heading) => [heading.tagName, heading.textContent]),
    ).toEqual([
      ["H1", setApart("Claim from Avo")],
      ["H2", "What it was started with"],
      ["H2", "What it gave back"],
      ["H2", "The steps"],
      ["H2", "About it"],
    ]);
    expect(screen.getAllByRole("heading", { level: 1 })).toEqual([title]);
  });

  it.each([
    ["fullDetail", () => screen.getByRole("table", { name: "The steps" })],
    [
      "conversation",
      () => screen.getByRole("list", { name: "The conversation" }),
    ],
  ] as const)(
    "draws in %s the work first and About it after it, in a column of its own beside it",
    async (drawing, work) => {
      opening({}, PAGE, drawing);
      await opened();
      await waitFor(() => expect(work()).toBeInTheDocument());

      const aboutIt = about();
      expect(
        work().compareDocumentPosition(aboutIt) &
          Node.DOCUMENT_POSITION_FOLLOWING,
      ).toBeTruthy();
      expect(aboutIt).not.toContainElement(work());
      const aboutColumn = aboutIt.parentElement!.parentElement!;
      expect(aboutColumn.previousElementSibling).toContainElement(work());
      expect(aboutColumn.nextElementSibling).toBeNull();
      expect(getComputedStyle(aboutIt).backgroundColor).toBe(
        resolved(theme.palette.background.paper),
      );
      expect(getComputedStyle(aboutIt).boxShadow).not.toBe("none");
      if (drawing === "conversation") {
        expect(getComputedStyle(aboutIt.parentElement!).height).toBe("100%");
        expect(getComputedStyle(aboutIt).minHeight).toBe("100%");
      }
    },
  );

  it("draws Stop under The facts, Rename beside it, and the way to the other drawing at the foot of About it", async () => {
    opening();
    await opened();

    const facts = about().querySelector("dl")!;
    const stop = within(about()).getByRole("button", { name: "Stop" });
    const rename = within(about()).getByRole("button", { name: "Rename" });
    const controls = within(about()).getAllByRole("button");
    expect(
      facts.compareDocumentPosition(stop) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(facts).not.toContainElement(stop);
    // Each control is wrapped by the one drawing it; the row holding the wrappers is the one asked of.
    const row = stop.parentElement!.parentElement!;
    expect(row).toContainElement(rename);
    expect(row).not.toContainElement(controls.at(-1)!);
    expect(controls.indexOf(stop) + 1).toBe(controls.indexOf(rename));
    expect(controls.at(-1)).toHaveTextContent("Show as a conversation");
    expect(screen.queryByRole("button", { name: "More" })).toBeNull();
    expect(laysPairsBeside(facts)).toBe(false);
  });

  it.each([
    ["conversation", "Summarise", "summarise"],
    ["fullDetail", "summarise", "Summarise"],
  ] as const)(
    "names in About it, drawn in %s, the step the run is on as the rest of that drawing names it: %s, not %s",
    async (drawing, named, notNamed) => {
      opening(
        {
          [`GET ${RUN}`]: [
            reply({ ...CLAIM, at: { stepId: SUMMARISE, name: "summarise" } }),
          ],
        },
        PAGE,
        drawing,
      );
      await opened();

      await waitFor(() =>
        expect(said()["Where it is"]).toBe(
          `Running, on ${setApart(named)}1 of 2 steps done`,
        ),
      );
      expect(said()["Where it is"]).not.toContain(setApart(notNamed));
      const where = within(about()).getByText("Where it is")
        .nextElementSibling as HTMLElement;
      const hint = within(where).getByText("Running, on");
      const step = where.querySelector("bdi");
      expect(getComputedStyle(hint).fontStyle).toBe("italic");
      expect(step).toHaveTextContent(setApart(named));
      expect(hint).not.toContainElement(step);
      expect(where).toHaveTextContent(
        `Running, on ${setApart(named)}1 of 2 steps done`,
      );
    },
  );

  it("keeps the work wide enough for a table where About it stands beside it", async () => {
    opening();
    await opened();

    expect(wideKeptBesideNarrow()).toBeGreaterThanOrEqual(LEAST_TABLE_WIDTH);
  });

  it("heads a run beneath another with its number, links the run above, and says it is held to that one's ceiling", async () => {
    const { name: _unnamed, startedBy: _nobody, ...beneath } = CLAIM;
    opening({
      [`GET ${STEPS}`]: [
        stepsRead({ run: { ...RUN_HEADER, state: "stopped" } }),
      ],
      [`GET ${RUN}`]: [
        reply({
          ...beneath,
          above: ABOVE,
          runsAbove: [{ runId: ABOVE, number: 6 }],
          from: FROM_ABOVE,
          state: "stopped",
          stopped: {
            at: "2026-09-24T08:15:00Z",
            ceilingOf: { runId: ABOVE, number: 6 },
          },
          ceiling: { heldBy: { runId: ABOVE, number: 6 } },
          acts: [],
        }),
      ],
    });

    expect(await opened()).toHaveTextContent("Run 7");
    const facts = said();
    expect(facts.Started).toMatch(
      new RegExp(`^By ${setApart("route")} in run 6, .+$`),
    );
    expect(facts["Where it is"]).toMatch(
      /^Stopped by the ceiling of run 6, .+$/,
    );
    expect(facts.Ceiling).toBe("Held to the ceiling of run 6, at the top");
    expect(
      [
        screen.getByRole("link", { name: "Open run 6" }),
        screen.getByRole("link", { name: "Open the step that started it" }),
        screen.getByRole("link", { name: /Held to the ceiling of run 6/ }),
      ].map((link) => link.getAttribute("href")),
    ).toEqual([
      `/groups/${GROUP}/work/${ABOVE}`,
      `/groups/${GROUP}/work/${ABOVE}/steps/${FILE_IT}`,
      `/groups/${GROUP}/work/${ABOVE}`,
    ]);
    expect(drawn()).toEqual(["Show as a conversation"]);
  });

  /** Kept apart from the tab Work was left on, which says nothing of how a run is drawn. */
  it.each([
    ["nothing kept", null, "conversation"],
    ["Conversation kept", "conversation", "conversation"],
    ["Full detail kept", "fullDetail", "fullDetail"],
    ["a word this build does not know", "sideways", "conversation"],
    ["the name of a Work tab", "history", "conversation"],
  ])(
    "draws the run as this device last left a run: %s",
    async (_case, kept, drawn) => {
      localStorage.setItem("lilradish.work.tab", "history");
      if (kept !== null) {
        localStorage.setItem(KEPT_DRAWING_AS, kept);
      }
      opening({}, PAGE, null);
      await opened();

      expect(
        screen.queryByRole("list", { name: "The conversation" }) !== null,
      ).toBe(drawn === "conversation");
      expect(screen.queryByRole("table", { name: "The steps" }) !== null).toBe(
        drawn === "fullDetail",
      );
      expect(localStorage.getItem(KEPT_DRAWING_AS)).toBe(kept);
    },
  );

  it.each(
    (["fullDetail", "conversation"] as const).flatMap((drawing) => [
      [
        drawing,
        "the list's filter and order, and none of what a step's page picked",
        `${PAGE}?filter=claim&sort=startedBy&value=summary&try=1`,
        `/groups/${GROUP}/work?filter=claim&sort=startedBy&tab=history`,
      ],
      [
        drawing,
        "its filter, and History named in place of the tab the address named",
        `${PAGE}?tab=newRun&filter=claim`,
        `/groups/${GROUP}/work?filter=claim&tab=history`,
      ],
      [drawing, "History alone", PAGE, `/groups/${GROUP}/work?tab=history`],
    ]),
  )(
    "draws in %s, above the run's name, the path through Work and History to it, History carrying %s",
    async (drawing, _case, address, history) => {
      opening({}, address, drawing);
      const heading = await opened();

      const path = screen.getByRole("navigation", { name: "Breadcrumb" });
      expect(
        within(path)
          .getAllByRole("link")
          .map((link) => [link.textContent, link.getAttribute("href")]),
      ).toEqual([
        ["Work", `/groups/${GROUP}/work`],
        ["History", history],
      ]);
      expect(within(path).getAllByRole("listitem").at(-1)).toHaveTextContent(
        setApart("Claim from Avo"),
      );
      expect(
        within(path).queryByRole("link", { name: /Claim from Avo/ }),
      ).toBeNull();
      expect(
        path.compareDocumentPosition(heading) &
          Node.DOCUMENT_POSITION_FOLLOWING,
      ).toBeTruthy();
    },
  );

  it("draws a run beneath others with every run above it in the path, from the top down, each opening its own page", async () => {
    const top = "00000008-0000-4000-8000-000000000c59";
    const { name: _unnamed, startedBy: _nobody, ...beneath } = CLAIM;
    opening(
      {
        [`GET ${RUN}`]: [
          reply({
            ...beneath,
            above: ABOVE,
            from: FROM_ABOVE,
            runsAbove: [
              { runId: top, number: 5, name: "Claim from Glinn" },
              { runId: ABOVE, number: 6 },
            ],
            ceiling: { heldBy: { runId: top, number: 5 } },
            acts: [],
          }),
        ],
      },
      `${PAGE}?filter=claim`,
    );
    await opened();

    const path = screen.getByRole("navigation", { name: "Breadcrumb" });

    expect(
      within(path)
        .getAllByRole("link")
        .map((link) => [link.textContent, link.getAttribute("href")]),
    ).toEqual([
      ["Work", `/groups/${GROUP}/work`],
      ["History", `/groups/${GROUP}/work?filter=claim&tab=history`],
      [
        setApart("Claim from Glinn"),
        `/groups/${GROUP}/work/${top}?filter=claim`,
      ],
      ["Run 6", `/groups/${GROUP}/work/${ABOVE}?filter=claim`],
    ]);
    expect(within(path).getAllByRole("listitem").at(-1)).toHaveTextContent(
      "Run 7",
    );
    expect(within(path).queryByRole("link", { name: "Run 7" })).toBeNull();
  });

  it.each([
    ["while the run is still read", UNANSWERED],
    ["where the run is refused", refusal("RUN_NOT_IN_VIEW", 404)],
  ])(
    "draws the path through Work and History alone %s, saying nothing of the run",
    async (_case, answer) => {
      opening({ [`GET ${RUN}`]: [answer] });

      const path = await screen.findByRole("navigation", {
        name: "Breadcrumb",
      });

      expect(
        within(path)
          .getAllByRole("link")
          .map((link) => link.textContent),
      ).toEqual(["Work", "History"]);
      expect(within(path).getAllByRole("listitem")).toHaveLength(2);
      expect(document.body).not.toHaveTextContent("Claim from Avo");
      expect(document.body).not.toHaveTextContent("Run 7");
    },
  );

  it.each(["run", "steps"] as const)(
    "closes the run when %s first fails ordinarily and the other read later refuses run access",
    async (first) => {
      const held = deferred<readonly [string, number]>();
      const ordinary = refusal("READ_FAILED", 503);
      const { sent } = opening({
        [`GET ${RUN}`]: [first === "run" ? ordinary : held.promise],
        [`GET ${STEPS}`]: [first === "steps" ? ordinary : held.promise],
      });
      await screen.findByRole("alert");
      expect(screen.queryByText("That run is not in view.")).toBeNull();

      await act(async () =>
        held.settle([JSON.stringify({ code: "RUN_NOT_IN_VIEW" }), 404]),
      );

      expect(screen.getByText("That run is not in view.")).toBeVisible();
      expect(screen.queryByRole("heading", { level: 1 })).toBeNull();
      expect(screen.queryByRole("table", { name: "The steps" })).toBeNull();
      expect(
        requestsTo(sent).filter((each) => each === `GET ${RUN}`),
      ).toHaveLength(1);
      expect(
        requestsTo(sent).filter((each) => each === `GET ${STEPS}`),
      ).toHaveLength(1);
    },
  );

  it.each(["fullDetail", "conversation"] as const)(
    "shows one route's actual child workflow and deepest step in %s and its own step page",
    async (drawing) => {
      const direction = String.fromCodePoint(0x05d0);
      const workflowName = `Demo Sample ${direction}`;
      const deepestName = `make_${direction}`;
      const deepestStep = "00000009-0000-4000-8000-000000000c55";
      const beneath = {
        runId: ABOVE,
        number: 8,
        runs: {
          kind: "workflow",
          entryId: WORKFLOW,
          name: workflowName,
          version: 2,
          versionId: VERSION,
        },
        at: {
          runId: ABOVE,
          number: 8,
          stepId: deepestStep,
          name: deepestName,
        },
      };
      const route = {
        ...FILE_IT_ROW,
        name: "choose",
        runs: { kind: "route" },
        producer: undefined,
        reviewer: undefined,
        tries: undefined,
        state: "waiting",
        acts: [],
        beneath,
      };
      const { router } = opening(
        {
          [`GET ${STEPS}`]: [stepsRead({}, route), stepsRead({}, route)],
          [`GET ${RUN}/steps/${FILE_IT}`]: [
            reply({
              run: RUN_HEADER,
              declarations: DECLARATIONS,
              step: route,
              triesMade: [],
            }),
          ],
        },
        PAGE,
        drawing,
      );
      await opened();
      const versionWords = `${setApart(workflowName)}, version 2`;
      const childWords = `It started run 8 beneath this one. Open run 8 ${versionWords} ${setApart(deepestName)}`;
      expect(document.body.textContent).toContain(childWords);
      expect(document.body.textContent).not.toContain(
        `Open run 8${versionWords}`,
      );
      expect(document.body.textContent).not.toContain(
        `${versionWords}${setApart(deepestName)}`,
      );
      expect(
        screen
          .getByRole("link", { name: setApart(deepestName) })
          .getAttribute("href"),
      ).toBe(`/groups/${GROUP}/work/${ABOVE}/steps/${deepestStep}`);
      expect(
        screen.getByRole("link", { name: "Open run 8" }).getAttribute("href"),
      ).toBe(`/groups/${GROUP}/work/${ABOVE}`);
      expect(document.body.textContent).not.toContain(
        `${workflowName}, version 2`,
      );

      await act(() =>
        router.navigate(`/groups/${GROUP}/work/${RUN_ID}/steps/${FILE_IT}`),
      );
      await screen.findByRole("heading", { level: 1 });
      expect(document.body.textContent).toContain(childWords);
      expect(document.body.textContent).not.toContain(
        `Open run 8${versionWords}`,
      );
      expect(document.body.textContent).not.toContain(
        `${versionWords}${setApart(deepestName)}`,
      );
      expect(
        screen
          .getByRole("link", { name: setApart(deepestName) })
          .getAttribute("href"),
      ).toBe(`/groups/${GROUP}/work/${ABOVE}/steps/${deepestStep}`);
      expect(document.body.textContent).not.toContain(
        `${workflowName}, version 2`,
      );
    },
  );

  it("links a child run's launch and actual source step but offers no ceiling change there", async () => {
    const sourceStep = "00000009-0000-4000-8000-000000000c53";
    const launchStep = "00000009-0000-4000-8000-000000000c54";
    const { name: _name, startedBy: _startedBy, ...child } = CLAIM;
    opening({
      [`GET ${RUN}`]: [
        reply({
          ...child,
          above: ABOVE,
          runsAbove: [{ runId: ABOVE, number: 6 }],
          from: {
            runId: ABOVE,
            number: 6,
            stepId: launchStep,
            stepName: "route",
          },
          startedWith: { ticket: "Demo Sample" },
          startedWithFrom: [
            {
              input: "ticket",
              from: {
                kind: "step",
                runId: ABOVE,
                runNumber: 6,
                stepId: sourceStep,
                name: "made",
                path: "summary",
              },
            },
          ],
          acts: ["change_ceiling"],
        }),
      ],
    });
    await opened();

    expect(
      screen
        .getByRole("link", { name: "Open the step that started it" })
        .getAttribute("href"),
    ).toBe(`/groups/${GROUP}/work/${ABOVE}/steps/${launchStep}`);
    expect(said().Started).toMatch(
      new RegExp(`^By ${setApart("route")} in run 6, .+$`),
    );
    expect(screen.getByRole("link", { name: "Open run 6" })).toHaveAttribute(
      "href",
      `/groups/${GROUP}/work/${ABOVE}`,
    );
    expect(
      screen
        .getAllByRole("link")
        .find(
          (link) =>
            link.getAttribute("href") ===
            `/groups/${GROUP}/work/${ABOVE}/steps/${sourceStep}`,
        ),
    ).toHaveTextContent(/which.*in run 6 gave back/);
    expect(
      within(ceiling()).queryByRole("button", { name: "Change the ceiling" }),
    ).toBeNull();
  });

  it("shows a child run's whole composed input and links each part to its actual maker", async () => {
    const sourceStep = "00000009-0000-4000-8000-000000000c53";
    const maker = "00000008-0000-4000-8000-000000000c57";
    const { name: _name, startedBy: _startedBy, ...child } = CLAIM;
    const ticket = {
      name: "ticket",
      label: "Ticket",
      kind: "fields",
      mustBeGiven: true,
      fields: [
        {
          name: "name",
          label: "Name",
          kind: "text",
          longest: 4000,
          mustBeGiven: true,
        },
        {
          name: "id",
          label: "Identifier",
          kind: "text",
          longest: 100,
          mustBeGiven: true,
        },
        {
          name: "note",
          label: "Note",
          kind: "text",
          longest: 4000,
          mustBeGiven: true,
        },
      ],
    };
    opening(
      {
        [`GET ${RUN}`]: [
          reply({
            ...child,
            above: ABOVE,
            runsAbove: [{ runId: ABOVE, number: 6 }],
            from: FROM_ABOVE,
            startedWith: {
              ticket: { name: "Demo Sample", id: "7", note: "Ready" },
            },
            startedWithFrom: [
              {
                input: "ticket",
                sources: [
                  { targetPath: "name", from: { kind: "constant" } },
                  {
                    targetPath: "id",
                    from: {
                      kind: "run_input",
                      runId: ABOVE,
                      runNumber: 6,
                      path: "ticket",
                    },
                  },
                  {
                    targetPath: "note",
                    from: {
                      kind: "step",
                      runId: maker,
                      runNumber: 4,
                      stepId: sourceStep,
                      name: "made",
                      path: "summary",
                    },
                  },
                ],
              },
            ],
            acts: [],
          }),
        ],
        [`GET ${STEPS}`]: [
          stepsRead({
            declarations: {
              ...DECLARATIONS,
              [VERSION]: { takes: [ticket], gives: [SUMMARY] },
            },
            steps: [
              {
                ...SUMMARISE_ROW,
                takesFrom: [
                  {
                    input: "text",
                    from: { kind: "run_input", path: "ticket.name" },
                  },
                ],
              },
              FILE_IT_ROW,
            ],
          }),
        ],
      },
      `${PAGE}?filter=claim`,
    );
    await opened();

    const region = screen.getByRole("region", {
      name: "What it was started with",
    });
    expect(region).toHaveTextContent("Demo Sample");
    expect(region).toHaveTextContent("It was put together from these:");
    const parts = within(region).getAllByRole("listitem");
    expect(parts.at(-3)).toHaveTextContent("Name");
    expect(parts.at(-2)).toHaveTextContent("Identifier");
    expect(parts.at(-1)).toHaveTextContent("Note");
    expect(within(parts.at(-3)!).queryByRole("link")).toBeNull();
    expect(within(parts.at(-2)!).getByRole("link")).toHaveAttribute(
      "href",
      `/groups/${GROUP}/work/${ABOVE}?filter=claim`,
    );
    expect(within(parts.at(-1)!).getByRole("link")).toHaveAttribute(
      "href",
      `/groups/${GROUP}/work/${maker}/steps/${sourceStep}?filter=claim`,
    );
    expect(region).not.toHaveTextContent("undefined");
  });

  it.each([
    ["a named ancestor", ABOVE],
    ["an unidentifiable source", undefined],
  ])(
    "keeps %s run-input path apart from the child's same-path label",
    async (_case, sourceRunId) => {
      const { name: _name, startedBy: _startedBy, ...child } = CLAIM;
      opening({
        [`GET ${RUN}`]: [
          reply({
            ...child,
            above: ABOVE,
            runsAbove: [{ runId: ABOVE, number: 6 }],
            from: FROM_ABOVE,
            startedWith: { ticket: "Demo Sample" },
            startedWithFrom: [
              {
                input: "ticket",
                from: {
                  kind: "run_input",
                  path: "ticket",
                  ...(sourceRunId === undefined
                    ? {}
                    : { runId: sourceRunId, runNumber: 6 }),
                },
              },
            ],
            acts: [],
          }),
        ],
      });
      await opened();

      const region = screen.getByRole("region", {
        name: "What it was started with",
      });
      const sourceWords = `${setApart("Ticket")}: ${setApart("ticket")}, which ${sourceRunId === undefined ? "the run" : "run 6"} was started with`;
      expect(region).toHaveTextContent(sourceWords);
      expect(region).not.toHaveTextContent(
        `${setApart("Ticket")}: ${setApart("Ticket")}, which ${sourceRunId === undefined ? "the run" : "run 6"} was started with`,
      );
      const sourceLink = within(region).queryByRole("link", {
        name: sourceWords,
      });
      expect(sourceLink?.getAttribute("href") ?? null).toBe(
        sourceRunId === undefined ? null : `/groups/${GROUP}/work/${ABOVE}`,
      );
    },
  );

  /** Opened beside a list, a run's links keep the list's filter and order, as the list's own links do. */
  it("keeps the address's query on each link to another run", async () => {
    const { name: _unnamed, startedBy: _nobody, ...beneath } = CLAIM;
    opening(
      {
        [`GET ${RUN}`]: [
          reply({
            ...beneath,
            above: ABOVE,
            runsAbove: [{ runId: ABOVE, number: 6 }],
            from: FROM_ABOVE,
            ceiling: { heldBy: { runId: ABOVE, number: 6 } },
            acts: [],
          }),
        ],
      },
      `${PAGE}?filter=claim&sort=run`,
    );

    await opened();

    expect(
      [
        screen.getByRole("link", { name: "Open run 6" }),
        screen.getByRole("link", { name: "Open the step that started it" }),
        screen.getByRole("link", { name: /Held to the ceiling of run 6/ }),
      ].map((link) => link.getAttribute("href")),
    ).toEqual([
      `/groups/${GROUP}/work/${ABOVE}?filter=claim&sort=run`,
      `/groups/${GROUP}/work/${ABOVE}/steps/${FILE_IT}?filter=claim&sort=run`,
      `/groups/${GROUP}/work/${ABOVE}?filter=claim&sort=run`,
    ]);
  });

  it("names every reached child ceiling, and keeps a human stop separate", async () => {
    const children = [
      { runId: ABOVE, number: 8 },
      { runId: "00000008-0000-4000-8000-000000000c53", number: 9 },
    ];
    opening({
      [`GET ${STEPS}`]: [
        stepsRead({ run: { ...RUN_HEADER, state: "stopped" } }),
      ],
      [`GET ${RUN}`]: [
        reply({
          ...STOPPED,
          stopped: {
            at: "2026-09-24T08:15:00Z",
            ceilingOf: children[0],
            ceilings: children,
          },
        }),
      ],
    });
    await opened();
    expect(said()["Where it is"]).toMatch(
      /^Stopped by the ceilings of runs 8 and 9, /,
    );
    expect(said()["Where it is"]).not.toContain("Glinn");
  });

  it("names its own ceiling with each lower ceiling when both are reached", async () => {
    const ceilings = [
      { runId: RUN_ID, number: 7 },
      { runId: ABOVE, number: 8 },
    ];
    opening({
      [`GET ${STEPS}`]: [
        stepsRead({ run: { ...RUN_HEADER, state: "stopped" } }),
      ],
      [`GET ${RUN}`]: [
        reply({
          ...STOPPED,
          stopped: {
            at: "2026-09-24T08:15:00Z",
            ceilingOf: ceilings[0],
            ceilings,
          },
        }),
      ],
    });
    await opened();
    expect(said()["Where it is"]).toMatch(
      /^Stopped by its ceiling and the ceiling of run 8, /,
    );
  });

  it("lists child-owned ceilings at the root and targets only the selected child", async () => {
    const child = {
      run: { runId: ABOVE, number: 8 },
      ceiling: { inForce: "50", raiseNeedsApproval: false },
      acts: ["change_ceiling"],
    };
    const { sent } = opening({
      [`GET ${RUN}`]: [
        reply({ ...CLAIM, ceilingsBeneath: [child] }),
        UNANSWERED,
      ],
      [`PATCH ${RUN}/ceiling`]: [reply({ ...CLAIM, ceilingsBeneath: [child] })],
    });
    await opened();
    const childGroup = screen.getByRole("group", { name: "Ceiling of run 8" });
    expect(within(childGroup).getByText("50")).toBeVisible();
    expect(
      within(childGroup).getByRole("button", {
        name: "Change the ceiling of run 8",
      }),
    ).toBeVisible();
    await userEvent.click(
      within(childGroup).getByRole("button", {
        name: "Change the ceiling of run 8",
      }),
    );
    const dialog = screen.getByRole("dialog", {
      name: "Change the ceiling of run 8",
    });
    await userEvent.clear(
      within(dialog).getByRole("textbox", { name: "Ceiling" }),
    );
    await userEvent.click(
      within(dialog).getByRole("button", { name: "Change" }),
    );
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    const changed = sent.mock.calls.find(
      ([, init]) => init?.method === "PATCH",
    );
    expect(changed?.[0]).toBe(`${RUN}/ceiling`);
    expect(JSON.parse(String(changed?.[1]?.body))).toEqual({
      ceiling: null,
      runId: ABOVE,
    });
  });

  it.each([
    ["Approve the raise", "approval", "approve_raise", GLINN],
    ["Refuse the raise", "refusal", "refuse_raise", GLINN],
    ["Withdraw the raise", "withdrawal", "withdraw_raise", AVO],
  ] as const)(
    "decides only the selected child raise by the root %s endpoint",
    async (control, decision, act, askedBy) => {
      const siblingId = "00000008-0000-4000-8000-000000000c53";
      const child = {
        run: { runId: ABOVE, number: 8 },
        ceiling: {
          inForce: "50",
          raiseNeedsApproval: true,
          waiting: {
            changeId: RAISE,
            to: "100",
            askedBy,
            askedAt: "2026-09-24T08:30:00Z",
          },
        },
        acts: [act],
      };
      const sibling = {
        ...child,
        run: { runId: siblingId, number: 9 },
        ceiling: {
          ...child.ceiling,
          waiting: { ...child.ceiling.waiting, changeId: `${RAISE}-other` },
        },
        acts: [],
      };
      const before = { ...CLAIM, ceilingsBeneath: [child, sibling] };
      const after = {
        ...CLAIM,
        ceilingsBeneath: [
          {
            ...child,
            ceiling: { ...child.ceiling, waiting: undefined },
            acts: [],
          },
          sibling,
        ],
      };
      const address = `PUT ${RUN}/ceiling-changes/${RAISE}/${decision}`;
      const { sent } = opening({
        [`GET ${RUN}`]: [reply(before), UNANSWERED],
        [address]: [reply(after)],
      });
      await opened();
      const selected = screen.getByRole("group", { name: "Ceiling of run 8" });
      const other = screen.getByRole("group", { name: "Ceiling of run 9" });
      expect(within(other).getByText(/asked/)).toBeVisible();

      await userEvent.click(
        within(selected).getByRole("button", { name: control }),
      );

      await waitFor(() =>
        expect(within(selected).queryByText(/asked/)).toBeNull(),
      );
      expect(within(other).getByText(/asked/)).toBeVisible();
      expect(
        within(ceiling()).getByText("9,007,199,254,740,990"),
      ).toBeVisible();
      expect(requestsTo(sent).filter((each) => each.startsWith("PUT"))).toEqual(
        [address],
      );
      expect(requestsTo(sent)).not.toContain(
        `PUT /api/groups/${GROUP}/runs/${ABOVE}/ceiling-changes/${RAISE}/${decision}`,
      );
    },
  );

  it.each([
    ["somebody", { by: GLINN }, `^Stopped by ${setApart("Glinn Havven")}, .+$`],
    [
      "its own ceiling, naming nobody",
      { ceilingOf: { runId: RUN_ID, number: 7 } },
      "^Stopped by its ceiling, .+$",
    ],
  ])("says a run is stopped, and by %s", async (_case, by, where) => {
    opening({
      [`GET ${RUN}`]: [
        reply({ ...STOPPED, stopped: { at: "2026-09-24T08:15:00Z", ...by } }),
      ],
      [`GET ${STEPS}`]: [
        stepsRead({ run: { ...RUN_HEADER, state: "stopped" } }),
      ],
    });
    await opened();

    expect(said()["Where it is"]).toMatch(new RegExp(where));
  });

  it.each([
    ["done", "Done", { ...RUN_HEADER, state: "done", at: undefined }],
    ["running", `Running, on ${setApart("file it")}`, RUN_HEADER],
  ])(
    "says where a %s run is now despite its earlier stop",
    async (state, words, header) => {
      opening({
        [`GET ${RUN}`]: [reply({ ...STOPPED, state, at: RUN_HEADER.at })],
        [`GET ${STEPS}`]: [stepsRead({ run: header })],
      });
      await opened();

      expect(said()["Where it is"]).toContain(words);
      expect(said()["Where it is"]).not.toContain("Stopped by");
    },
  );

  /** A state named after this build is said plainly as one it cannot say, never in the server's spelling. */
  it("says where a run is in words only, even where the server works out a state this build has none for", async () => {
    opening({
      [`GET ${RUN}`]: [reply({ ...CLAIM, state: "resting" })],
      [`GET ${STEPS}`]: [
        stepsRead({ run: { ...RUN_HEADER, state: "resting" } }),
      ],
    });
    await opened();

    expect(said()["Where it is"]).toBe(
      "Not something this page can say yet.1 of 2 steps done",
    );
    expect(screen.queryByText(/resting/)).toBeNull();
  });

  it.each([
    [
      "with some of what came back unknown, says so",
      { cameBackUnknown: true },
      "What came back is not known for every call yet, and is counted here as nothing.",
    ],
    [
      "with some calls counted here, says part of it is measured here",
      { measuredHere: "partly" },
      "Part of it is as this system measured it, where a model did not say what it counted.",
    ],
    [
      "with every call counted here, says all of it is measured here",
      { measuredHere: "wholly" },
      "All of it is as this system measured it, as no model said what it counted.",
    ],
    [
      "with both, says who counted it before what is not known",
      { cameBackUnknown: true, measuredHere: "partly" },
      "Part of it is as this system measured it, where a model did not say what it counted.What came back is not known for every call yet, and is counted here as nothing.",
    ],
    ["with all of it known as the model counted it, says nothing more", {}, ""],
  ])("says what a run has cost, and %s", async (_case, marked, more) => {
    opening({
      [`GET ${RUN}`]: [
        reply({
          ...CLAIM,
          spend: { ...CLAIM.spend, cameBackUnknown: false, ...marked },
        }),
      ],
    });
    await opened();

    expect(said()["What it has cost"]).toBe(
      `9,007,199,254,741,013 in all: 9,007,199,254,740,993 sent, 20 came back${more}`,
    );
  });

  it.each([
    [
      "every act on a running run, a raise waiting",
      [
        "stop",
        "open_again",
        "rename",
        "change_ceiling",
        "approve_raise",
        "refuse_raise",
        "withdraw_raise",
      ],
      [
        "Change the ceiling",
        "Approve the raise",
        "Refuse the raise",
        "Withdraw the raise",
        "Stop",
        "Rename",
        "Show as a conversation",
      ],
    ],
    [
      "opening again alone",
      ["open_again"],
      ["Open again", "Show as a conversation"],
    ],
    ["none of them", [], ["Show as a conversation"]],
  ])(
    "draws only the controls the server says the reader may use, the way to Conversation last: %s",

    async (_case, acts, controls) => {
      opening({ [`GET ${RUN}`]: [reply({ ...RAISED, acts })] });
      await opened();

      expect(drawn()).toEqual(controls);
    },
  );

  /** The run read again after the act is held unanswered, so all that is drawn is what the act answered. */
  it("stops the run, showing it as the act answered before the run read again after it comes back", async () => {
    const { sent } = opening({
      [`GET ${RUN}`]: [reply(CLAIM), UNANSWERED],
      [`PUT ${RUN}/stop`]: [reply(STOPPED)],
    });
    await opened();
    const control = screen.getByRole("button", { name: "Stop" });

    await userEvent.click(control);

    expect(await screen.findByRole("button", { name: "Open again" })).toBe(
      control,
    );
    expect(said()["Where it is"]).toMatch(/^Stopped by /);
    await waitFor(() =>
      expect(requestsTo(sent)).toEqual([
        `GET ${RUN}`,
        `GET ${STEPS}`,
        `PUT ${RUN}/stop`,
        `GET ${RUN}`,
        `GET ${STEPS}`,
      ]),
    );
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("opens a stopped run again", async () => {
    const { sent } = opening({
      [`GET ${RUN}`]: [reply(STOPPED), reply(CLAIM)],
      [`GET ${STEPS}`]: [
        stepsRead({ run: { ...RUN_HEADER, state: "stopped" } }),
        stepsRead(),
      ],
      [`DELETE ${RUN}/stop`]: [reply(CLAIM)],
    });
    await opened();

    await userEvent.click(screen.getByRole("button", { name: "Open again" }));

    expect(
      await screen.findByRole("button", { name: "Stop" }),
    ).toBeInTheDocument();
    expect(requestsTo(sent).filter((each) => !each.startsWith("GET"))).toEqual([
      `DELETE ${RUN}/stop`,
    ]);
  });

  it("draws Stop outlined in the warning colour, and the same control once it reads Open again in none, Rename in none either way", async () => {
    opening({
      [`GET ${RUN}`]: [reply(CLAIM), UNANSWERED],
      [`PUT ${RUN}/stop`]: [reply(STOPPED)],
    });
    await opened();
    const control = screen.getByRole("button", { name: "Stop" });
    const rename = screen.getByRole("button", { name: "Rename" });

    expect(control).toHaveClass(buttonClasses.colorWarning);
    expect(control).toHaveClass(buttonClasses.outlined);
    expect(rename).not.toHaveClass(buttonClasses.colorWarning);

    await userEvent.click(control);

    expect(await screen.findByRole("button", { name: "Open again" })).toBe(
      control,
    );
    expect(control).not.toHaveClass(buttonClasses.colorWarning);
    expect(control).toHaveClass(buttonClasses.outlined);
    expect(rename).not.toHaveClass(buttonClasses.colorWarning);
  });

  /** A refusal of the act says the reader's standing moved, which is read again with the run. */
  it("says a refused stop as the rule of starting a run, and reads the standing and the run again", async () => {
    const { sent, readAgain } = opening({
      [`PUT ${RUN}/stop`]: [refusal("ACT_NOT_PERMITTED", 403)],
    });
    await opened();

    await userEvent.click(screen.getByRole("button", { name: "Stop" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(START_RUN_RULE);
    expect(readAgain).toHaveBeenCalledOnce();
    await waitFor(() => expect(runReads(sent)).toBe(2));
  });

  it("renames the run through its dialog, heading the page with the name it answers with", async () => {
    const { sent } = opening({
      [`GET ${RUN}`]: [reply(CLAIM), UNANSWERED],
      [`PATCH ${RUN}`]: [reply({ ...CLAIM, name: "Claim from Avo, again" })],
    });
    await opened();

    await userEvent.click(screen.getByRole("button", { name: "Rename" }));
    const dialog = screen.getByRole("dialog", { name: "Rename this run" });
    await userEvent.type(
      within(dialog).getByRole("textbox", { name: "Name" }),
      ", again",
    );
    await userEvent.click(
      within(dialog).getByRole("button", { name: "Rename" }),
    );

    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(await opened()).toHaveTextContent(setApart("Claim from Avo, again"));
    expect(requestsTo(sent).filter((each) => !each.startsWith("GET"))).toEqual([
      `PATCH ${RUN}`,
    ]);
  });

  it.each([
    [
      "raised, says to what",
      RAISED.ceiling.waiting,
      "for it to be raised to 20,000\\. That waits on approval\\.$",
    ],
    [
      "taken away, says so",
      { changeId: RAISE, askedBy: GLINN, askedAt: "2026-09-24T08:30:00Z" },
      "for it to be taken away\\. That waits on approval\\.$",
    ],
  ])(
    "says a raise waiting beside the ceiling in force: asked for it to be %s",
    async (_case, waiting, words) => {
      opening({
        [`GET ${RUN}`]: [
          reply({ ...RAISED, ceiling: { ...RAISED.ceiling, waiting } }),
        ],
      });
      await opened();

      expect(within(ceiling()).getByText(/asked/)).toHaveTextContent(
        new RegExp(`^${setApart("Glinn Havven")} asked, .+, ${words}`),
      );
      expect(
        within(ceiling()).getByText("9,007,199,254,740,990"),
      ).toBeInTheDocument();
    },
  );

  /** The raise is named by its identifier, so nobody decides one they never saw. */
  it.each([
    ["Approve the raise", "approval", RAISED],
    ["Refuse the raise", "refusal", RAISED],
    ["Withdraw the raise", "withdrawal", ASKED],
  ])(
    "decides the raise shown when %s is pressed, keeping the keyboard at the ceiling",
    async (control, decision, run) => {
      const { sent } = opening({
        [`GET ${RUN}`]: [reply(run), UNANSWERED],
        [`PUT ${RUN}/ceiling-changes/${RAISE}/${decision}`]: [reply(CLAIM)],
      });
      await opened();

      await userEvent.click(screen.getByRole("button", { name: control }));

      await waitFor(() =>
        expect(within(ceiling()).queryByText(/asked/)).toBeNull(),
      );
      expect(
        requestsTo(sent).filter((each) => !each.startsWith("GET")),
      ).toEqual([`PUT ${RUN}/ceiling-changes/${RAISE}/${decision}`]);
      expect(document.activeElement).toBe(ceiling());
      expect(screen.queryByRole("alert")).toBeNull();
    },
  );

  /** Decided by somebody else meanwhile: the refusal is said, and the run read again shows how it stands now. */
  it.each([
    ["Approve the raise", "approval", RAISED],
    ["Withdraw the raise", "withdrawal", ASKED],
  ])(
    "says %s refused as the raise no longer waiting, and reads the run again and not the standing",
    async (control, decision, run) => {
      const { sent, readAgain } = opening({
        [`GET ${RUN}`]: [reply(run), reply(CLAIM)],
        [`PUT ${RUN}/ceiling-changes/${RAISE}/${decision}`]: [
          refusal("CEILING_RAISE_NOT_WAITING", 409),
        ],
      });
      await opened();

      await userEvent.click(screen.getByRole("button", { name: control }));

      expect(await within(ceiling()).findByRole("alert")).toHaveTextContent(
        "That raise is not waiting on anybody.",
      );
      await waitFor(() => expect(runReads(sent)).toBe(2));
      expect(readAgain).not.toHaveBeenCalled();
    },
  );

  it.each([
    ["Approve the raise", "approval", RAISED, APPROVE_ENTRY_RULE],
    ["Refuse the raise", "refusal", RAISED, APPROVE_ENTRY_RULE],
    ["Withdraw the raise", "withdrawal", ASKED, START_RUN_RULE],
  ])(
    "says %s refused as the act itself as the rule of the permission it takes, and reads the standing again",
    async (control, decision, run, rule) => {
      const { readAgain } = opening({
        [`GET ${RUN}`]: [reply(run)],
        [`PUT ${RUN}/ceiling-changes/${RAISE}/${decision}`]: [
          refusal("ACT_NOT_PERMITTED", 403),
        ],
      });
      await opened();

      await userEvent.click(screen.getByRole("button", { name: control }));

      expect(await within(ceiling()).findByRole("alert")).toHaveTextContent(
        rule,
      );
      expect(readAgain).toHaveBeenCalledOnce();
    },
  );

  it("says a withdrawal refused as the raise being another's, and reads the run again", async () => {
    const { sent, readAgain } = opening({
      [`GET ${RUN}`]: [reply(ASKED), reply(RAISED)],
      [`PUT ${RUN}/ceiling-changes/${RAISE}/withdrawal`]: [
        refusal("CEILING_RAISE_ASKED_BY_ANOTHER", 403),
      ],
    });
    await opened();

    await userEvent.click(
      screen.getByRole("button", { name: "Withdraw the raise" }),
    );

    expect(await within(ceiling()).findByRole("alert")).toHaveTextContent(
      "A raise is withdrawn only by whoever asked for it.",
    );
    await waitFor(() => expect(runReads(sent)).toBe(2));
    expect(readAgain).not.toHaveBeenCalled();
  });

  it("changes the ceiling through its dialog, showing the run as the change answered", async () => {
    const { sent } = opening({
      [`GET ${RUN}`]: [reply(CLAIM), UNANSWERED],
      [`PATCH ${RUN}/ceiling`]: [
        reply({ ...CLAIM, ceiling: { raiseNeedsApproval: true } }),
      ],
    });
    await opened();

    await userEvent.click(
      screen.getByRole("button", { name: "Change the ceiling" }),
    );
    const dialog = screen.getByRole("dialog", { name: "Change the ceiling" });
    await userEvent.clear(
      within(dialog).getByRole("textbox", { name: "Ceiling" }),
    );
    await userEvent.click(
      within(dialog).getByRole("button", { name: "Change" }),
    );

    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(
      await within(ceiling()).findByText("None. It may spend without limit."),
    ).toBeInTheDocument();
    const changed = sent.mock.calls.find(
      ([, init]) => init?.method === "PATCH",
    );
    expect(JSON.parse(String(changed![1]?.body))).toEqual({ ceiling: null });
  });

  it.each([
    [
      "the run is not in view",
      "RUN_NOT_IN_VIEW",
      "That run is not in view.",
      0,
    ],
    [
      "the group is not the reader's",
      "GROUP_NOT_IN_VIEW",
      "That group is not in view.",
      1,
    ],
  ])(
    "says a read refused as %s in place, drawing nothing of the run, and reads the standing again only where it moved",
    async (_case, code, sentence, standingReads) => {
      const { readAgain } = opening({ [`GET ${RUN}`]: [refusal(code, 404)] });

      expect(await screen.findByRole("alert")).toHaveTextContent(sentence);
      expect(screen.queryByRole("heading", { level: 1 })).toBeNull();
      await waitFor(() =>
        expect(readAgain).toHaveBeenCalledTimes(standingReads),
      );
    },
  );
});
