import { act, screen, waitFor, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { theme } from "../../lib/theme/theme";
import { whenText } from "../../lib/time/When";
import { requestsTo, type Reply } from "../../testutil/answering";
import { deferred } from "../../testutil/deferred";
import { regionsSaid } from "../../testutil/heard";
import { accountOpened, stateControl } from "../../testutil/stateAccount";
import { resolved } from "../../testutil/grounds";
import { tryReplies } from "../../testutil/tryReplies";

import {
  GROUP,
  RUN_ID,
  ABOVE,
  RAISE,
  KEY,
  PAGE,
  KEPT_DRAWING_AS,
  RUN,
  AVO,
  GLINN,
  SUMMARISE,
  FILE_IT,
  CLAIM,
  STOPPED,
  RAISED,
  reply,
  refusal,
  UNANSWERED,
  STEPS,
  VERSION,
  QUESTION_VERSION,
  SUMMARY,
  STARTED,
  RUN_HEADER,
  DECLARATIONS,
  SUMMARISE_ROW,
  FILE_IT_ROW,
  stepsRead,
  reads,
  landing,
  opening,
  opened,
  setApart,
  ceiling,
  said,
  about,
  runReads,
} from "../../testutil/runPage";

afterEach(() => {
  localStorage.clear();
});

describe("RunPage flows", () => {
  it.each(["fullDetail", "conversation"] as const)(
    "rereads a renamed then cleared workflow version in %s over the same historical run",
    async (drawing) => {
      vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
      try {
        const snapshot = (versionName: string | null) =>
          reply({ ...CLAIM, workflow: { ...CLAIM.workflow, versionName } });
        const { sent, router } = opening(
          {
            [`GET ${RUN}`]: [
              snapshot("Earlier name"),
              snapshot("اسم 📦"),
              snapshot(null),
            ],
            [`GET ${STEPS}`]: [
              stepsRead({ rereadAfterSeconds: 1 }),
              stepsRead({ rereadAfterSeconds: 1 }),
              stepsRead(),
            ],
          },
          PAGE,
          drawing,
        );
        const workflow = () =>
          within(about()).getByRole("link", { name: /Handle a claim/ });
        await landing(
          () => screen.queryByRole("region", { name: "Overview" }) !== null,
        );
        expect(said().Workflow).toBe(
          `Handle a claim \u00b7 Version 3 \u00b7 Earlier name`,
        );
        const address = workflow().getAttribute("href");
        await act(() => vi.advanceTimersByTimeAsync(1000));
        await landing(() => said().Workflow.endsWith(" · اسم 📦"));
        expect(workflow().querySelector("bdi ~ bdi")).toHaveTextContent(
          "اسم 📦",
        );
        expect(said().Workflow).not.toContain("Earlier name");
        expect(workflow()).toHaveAttribute("href", address!);
        await act(() => vi.advanceTimersByTimeAsync(1000));
        await landing(
          () => said().Workflow === `Handle a claim \u00b7 Version 3`,
        );
        expect(workflow().querySelector("bdi ~ bdi")).toBeNull();
        expect(said().Workflow).not.toContain("اسم 📦");
        expect(workflow()).toHaveAttribute("href", address!);
        expect(said().Number).toBe("7");
        expect(router.state.location.pathname).toBe(PAGE);
        expect(requestsTo(sent)).toEqual([
          `GET ${RUN}`,
          `GET ${STEPS}`,
          `GET ${RUN}`,
          `GET ${STEPS}`,
          `GET ${RUN}`,
          `GET ${STEPS}`,
        ]);
      } finally {
        vi.useRealTimers();
      }
    },
  );

  describe("in Full detail", () => {
    function part(name: string): HTMLElement {
      return screen.getByRole("region", { name });
    }

    function stepRows(): string[][] {
      const [, ...rows] = within(
        screen.getByRole("table", { name: "Steps" }),
      ).getAllByRole<HTMLTableRowElement>("row");
      return rows.map((row) =>
        [...row.cells].map((cell) => cell.textContent ?? ""),
      );
    }

    afterEach(() => {
      vi.useRealTimers();
    });

    it("draws each first-rank part of Full detail as its own white card", async () => {
      opening({}, PAGE, "fullDetail");
      await screen.findByRole("table", { name: "Steps" });

      const cards = [part("Run inputs"), part("Result"), part("Steps")];
      expect(new Set(cards).size).toBe(3);
      expect(
        cards[0]!.compareDocumentPosition(cards[1]!) &
          Node.DOCUMENT_POSITION_FOLLOWING,
      ).toBeTruthy();
      expect(
        cards[1]!.compareDocumentPosition(cards[2]!) &
          Node.DOCUMENT_POSITION_FOLLOWING,
      ).toBeTruthy();
      for (const card of cards) {
        const style = getComputedStyle(card);
        expect(card.tagName).toBe("SECTION");
        expect(style.backgroundColor).toBe(
          resolved(theme.palette.background.paper),
        );
        expect(style.boxShadow).toBe("");
        expect(style.borderTopWidth).toBe("1px");
      }
    });
    it("offers a declared file started with for download through the run being read", async () => {
      const attachment = {
        name: "attachment",
        label: "Attachment",
        kind: "file",
        types: [".gz", ".tar.gz"],
        maximumBytes: 12,
        mustBeGiven: true,
      };
      const file = {
        id: "0000000f-0000-4000-8000-000000000c51",
        name: "Imaginary receipts.TAR.GZ",
        size: 5,
      };
      const { sent } = opening({
        [`GET ${RUN}`]: [
          reply({
            ...STARTED,
            startedWith: { ...STARTED.startedWith, attachment: file },
          }),
        ],
        [`GET ${STEPS}`]: [
          stepsRead({
            declarations: {
              ...DECLARATIONS,
              [VERSION]: {
                ...DECLARATIONS[VERSION],
                takes: [...DECLARATIONS[VERSION].takes, attachment],
              },
            },
          }),
        ],
      });

      const started = await screen.findByRole("region", {
        name: "Run inputs",
      });
      const link = within(started).getByRole("link", {
        name: `Download ${setApart("Imaginary receipts.TAR.GZ")}, 5 B`,
      });
      expect(
        within(started)
          .getAllByRole("term")
          .map((each) => each.textContent),
      ).toEqual([
        `${setApart("Ticket")} · ticket`,
        `${setApart("Due")} · due`,
        `${setApart("Attachment")} · attachment`,
      ]);
      expect(
        within(started)
          .getAllByRole("definition")
          .slice(0, 2)
          .map((each) => each.textContent),
      ).toEqual(["Kettle leaks", "Sep 30, 2026"]);
      expect(stepRows().map((row) => row[2])).toEqual([
        `${setApart("Text")}${setApart("Ticket")} (run input)`,
        `${setApart("summary")}${setApart("Summary")} (output of ${setApart("summarise")})`,
      ]);
      expect(link).toHaveAttribute(
        "href",
        `/api/groups/${GROUP}/runs/${RUN_ID}/files/${file.id}`,
      );
      expect(started).toHaveTextContent("5 B");
      expect(link).toHaveAttribute("download");
      expect(link).toHaveStyle({ display: "block" });
      expect(
        [...link.querySelectorAll("bdi")]
          .filter((each) => each.closest("[hidden]") === null)
          .map((each) => each.textContent),
      ).toEqual(["Imaginary receipts.TAR.GZ"]);
      expect(started.querySelector("iframe, img, embed, object")).toBeNull();
      expect(within(started).getAllByRole("link")).toHaveLength(1);
      expect(started).not.toHaveTextContent(file.id);
      expect(reads(sent)).toEqual([`GET ${RUN}`, `GET ${STEPS}`]);
    });

    it("draws as its work what it was started with, what it gave back that stands, and every step in order with where each input comes from", async () => {
      const { sent } = opening(
        { [`GET ${RUN}`]: [reply(STARTED)], [`GET ${STEPS}`]: [stepsRead()] },
        PAGE,
        "fullDetail",
      );

      await screen.findByRole("table", { name: "Steps" });

      expect(
        within(part("Run inputs"))
          .getAllByRole("definition")
          .map((each) => each.textContent),
      ).toEqual(["Kettle leaks", "Sep 30, 2026"]);
      expect(within(part("Result")).getByRole("term")).toHaveTextContent(
        "Summary",
      );
      expect(within(part("Result")).getByRole("definition")).toHaveTextContent(
        "A kettle leaks.",
      );
      expect(stepRows()).toEqual([
        [
          "1",
          "summariseQuestion",
          `${setApart("Text")}${setApart("Ticket")} (run input)`,
          "Done",
          "None \u2014 no model is used.",
        ],
        [
          "2",
          "file itCode step",
          `${setApart("summary")}${setApart("Summary")} (output of ${setApart("summarise")})`,
          "RunningIts code is running.",
          "None — no model is used.",
        ],
      ]);
      expect(screen.queryByText("No accepted result yet.")).toBeNull();
      expect(reads(sent)).toEqual([`GET ${RUN}`, `GET ${STEPS}`]);
    });

    it("says in the steps a step's cost is some or all of it as this system measured it, never as one try's", async () => {
      const measured = {
        ...SUMMARISE_ROW,
        cost: {
          callsAModel: true,
          sent: "1200",
          cameBack: "300",
          spent: "1500",
          cameBackUnknown: false,
          measuredHere: "partly",
        },
      };
      opening(
        { [`GET ${STEPS}`]: [stepsRead({ steps: [measured, FILE_IT_ROW] })] },
        PAGE,
        "fullDetail",
      );

      await screen.findByRole("table", { name: "Steps" });

      const [cost, uncalled] = stepRows().map((cells) => cells.at(-1));
      expect(cost).toBe(
        "1,500 in total: 1,200 sent, 300 receivedPart of this is estimated by this system, where a model did not report its own count.",
      );
      expect(cost).not.toContain("Both as");
      expect(uncalled).toBe("None — no model is used.");
    });

    it("opens each step's own page from its row, keeping the list's address", async () => {
      opening(
        { [`GET ${STEPS}`]: [stepsRead()] },
        `${PAGE}?sort=run`,
        "fullDetail",
      );

      const table = await screen.findByRole("table", { name: "Steps" });

      expect(
        within(table)
          .getAllByRole("link")
          .map((link) => link.getAttribute("href")),
      ).toEqual([
        `${PAGE}/steps/${SUMMARISE}?sort=run`,
        `${PAGE}/steps/${FILE_IT}?sort=run`,
      ]);
    });

    it.each([
      [
        "nothing standing yet",
        { declares: "values", standing: [] },
        "No accepted result yet.",
      ],
      [
        "a workflow that acts rather than answers",
        { declares: "nothing" },
        "This workflow returns no result: it carries out actions rather than giving answers.",
      ],
      [
        "what it declares in a word this build does not know",
        { declares: "later" },
        "This page can't show this yet.",
      ],
    ])(
      "says so of what it gave back where it has %s, and lists nothing",
      async (_case, gaveBack, sentence) => {
        opening(
          { [`GET ${STEPS}`]: [stepsRead({ gaveBack })] },
          PAGE,
          "fullDetail",
        );

        await screen.findByRole("table", { name: "Steps" });

        expect(part("Result")).toHaveTextContent(sentence);
        expect(within(part("Result")).queryByRole("term")).toBeNull();
      },
    );

    it.each([
      [
        "the run above started it",
        {
          ...CLAIM,
          name: undefined,
          startedBy: undefined,
          above: ABOVE,
          runsAbove: [{ runId: ABOVE, number: 6 }],
          from: {
            runId: ABOVE,
            number: 6,
            stepId: FILE_IT,
            stepName: "route",
          },
        },
        `Step ${setApart("route")} of run 6 started this run of ${setApart("Handle a claim")}.`,
      ],
      [
        "it takes nothing",
        { ...CLAIM, startedWith: {} },
        "This workflow takes no inputs.",
      ],
    ])(
      "says what it was started with in words where %s and no value is there to list",
      async (_case, run, sentence) => {
        opening(
          {
            [`GET ${RUN}`]: [reply(run)],
            [`GET ${STEPS}`]: [
              stepsRead({
                declarations: { [VERSION]: { takes: [], gives: [SUMMARY] } },
              }),
            ],
          },
          PAGE,
          "fullDetail",
        );

        await screen.findByRole("table", { name: "Steps" });

        expect(part("Run inputs")).toHaveTextContent(sentence);
        expect(within(part("Run inputs")).queryByRole("term")).toBeNull();
      },
    );

    /** Timeouts are faked, so what is in flight is let land a turn at a time rather than waited on. */
    it("keeps where each step is up for as long as the steps say to, never saying it is reading, and stops once they no longer say it", async () => {
      vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
      const { sent } = opening(
        {
          [`GET ${STEPS}`]: [
            stepsRead({ rereadAfterSeconds: 2 }),
            stepsRead({}, { state: "done", where: undefined }),
          ],
        },
        PAGE,
        "fullDetail",
      );
      await landing(() => screen.queryByRole("table") !== null);

      await act(() => vi.advanceTimersByTimeAsync(1999));
      expect(reads(sent)).toHaveLength(2);
      await act(() => vi.advanceTimersByTimeAsync(1));
      await landing(() => stepRows()[1]![3] === "Done");
      await act(() => vi.advanceTimersByTimeAsync(60_000));

      expect(reads(sent)).toEqual([
        `GET ${RUN}`,
        `GET ${STEPS}`,
        `GET ${RUN}`,
        `GET ${STEPS}`,
      ]);
      expect(vi.getTimerCount()).toBe(1);
      expect(screen.queryByText("Loading…")).toBeNull();
      expect(screen.queryByRole("alert")).toBeNull();
    });

    it("reads the steps again once an act on the run answers, as well as showing the run it answered with", async () => {
      const { sent } = opening(
        {
          [`GET ${STEPS}`]: [
            stepsRead(),
            stepsRead({}, { state: "failed", where: undefined }),
          ],
          [`PUT ${RUN}/stop`]: [reply(STOPPED)],
        },
        PAGE,
        "fullDetail",
      );
      await screen.findByRole("table", { name: "Steps" });

      await userEvent.click(screen.getByRole("button", { name: "Stop" }));

      await waitFor(() => expect(stepRows()[1]![3]).toBe("Failed"));
      const asked = requestsTo(sent);
      expect(asked.slice(asked.indexOf(`PUT ${RUN}/stop`))).toEqual([
        `PUT ${RUN}/stop`,
        `GET ${RUN}`,
        `GET ${STEPS}`,
      ]);
    });

    it("starts the keyboard at the ceiling once an act's answer is drawn, and leaves it where the reader put it when the run is read again after", async () => {
      let settle!: (answer: readonly [string, number]) => void;
      const readAgain = new Promise<readonly [string, number]>((done) => {
        settle = done;
      });
      opening(
        {
          [`GET ${RUN}`]: [reply(RAISED), readAgain],
          [`GET ${STEPS}`]: [stepsRead()],
          [`PUT ${RUN}/ceiling-changes/${RAISE}/approval`]: [reply(CLAIM)],
        },
        PAGE,
        "fullDetail",
        AVO.userId,
      );
      await screen.findByRole("table", { name: "Steps" });

      await userEvent.click(
        screen.getByRole("button", { name: "Approve change" }),
      );
      await waitFor(() => expect(document.activeElement).toBe(ceiling()));
      await act(async () => {
        ceiling().blur();
      });
      settle([JSON.stringify({ ...CLAIM, name: "Claim, read again" }), 200]);

      await waitFor(() =>
        expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent(
          setApart("Claim, read again"),
        ),
      );
      expect(document.activeElement).toBe(document.body);
    });

    /** Timeouts are faked, as where each step is kept up is. */
    it("says once, in a region of its own, where a read again finds a step in another state, and nothing where none is", async () => {
      vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
      const done = { state: "done", where: undefined };
      opening(
        {
          [`GET ${STEPS}`]: [
            stepsRead({ rereadAfterSeconds: 1 }),
            stepsRead({ rereadAfterSeconds: 1 }, done),
            stepsRead({ rereadAfterSeconds: 1 }, done),
          ],
        },
        PAGE,
        "fullDetail",
      );
      await landing(() => screen.queryByRole("table") !== null);
      expect(regionsSaid()).toEqual(["", "", "", ""]);

      await act(() => vi.advanceTimersByTimeAsync(1000));
      await landing(() => stepRows()[1]![3] === "Done");

      expect(regionsSaid()).toEqual([
        "",
        `${setApart("file it")}: now Done.`,
        "",
        "",
      ]);

      await act(() => vi.advanceTimersByTimeAsync(1000));
      await landing(() => regionsSaid().every((each) => each === ""));
      expect(stepRows()[1]![3]).toBe("Done");
    });

    it("starts the keyboard at the run's heading as the page arrives", async () => {
      opening({ [`GET ${STEPS}`]: [stepsRead()] }, PAGE, "fullDetail");

      const heading = await opened();

      await waitFor(() => expect(document.activeElement).toBe(heading));
      expect(document.activeElement).not.toBe(document.body);
    });

    it("says the run refused in place where its steps are refused, drawing nothing of it", async () => {
      opening(
        { [`GET ${STEPS}`]: [refusal("RUN_NOT_IN_VIEW", 404)] },
        PAGE,
        "fullDetail",
      );

      expect(await screen.findByRole("alert")).toHaveTextContent(
        "That run isn't here, or you can't see it.",
      );
      expect(screen.queryByRole("heading", { level: 1 })).toBeNull();
      expect(screen.queryByRole("table")).toBeNull();
    });

    it("draws no conversation, and names no step's message", async () => {
      opening();

      await screen.findByRole("table", { name: "Steps" });

      expect(
        screen.queryByRole("list", { name: "The conversation" }),
      ).toBeNull();
      expect(document.getElementById(`step-${SUMMARISE}`)).toBeNull();
    });

    it("draws the run in Conversation once the View control asks for it, keeping that on this device, the keyboard staying on the selected control, and reading nothing more of the run", async () => {
      const { sent } = opening();
      await screen.findByRole("table", { name: "Steps" });
      const toConversation = screen.getByRole("button", {
        name: "Conversation",
      });
      expect(screen.getByRole("group", { name: "View" })).toContainElement(
        toConversation,
      );
      expect(about()).not.toContainElement(toConversation);

      await userEvent.click(toConversation);

      expect(
        await screen.findByRole("list", { name: "The conversation" }),
      ).toBeInTheDocument();
      expect(toConversation).toHaveFocus();
      expect(toConversation).toHaveTextContent("Conversation");
      expect(toConversation).toHaveAttribute("aria-pressed", "true");
      expect(localStorage.getItem(KEPT_DRAWING_AS)).toBe("conversation");
      expect(screen.queryByRole("table", { name: "Steps" })).toBeNull();
      expect(
        screen.getByRole("button", { name: "Full detail" }),
      ).toHaveAttribute("aria-pressed", "false");
      expect(reads(sent)).toEqual([`GET ${RUN}`, `GET ${STEPS}`]);
    });
  });

  describe("in Conversation", () => {
    it("updates run and historical act dates at midnight without reading either resource again", async () => {
      vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout", "Date"] });
      const startedAt = new Date(2026, 8, 25, 8).toISOString();
      const stoppedAt = new Date(2026, 8, 25, 9).toISOString();
      vi.setSystemTime(new Date(2026, 8, 25, 23, 59, 59));
      const address = `${PAGE}?filter=claim&sort=run`;
      const { sent, router } = opening(
        {
          [`GET ${RUN}`]: [
            reply({
              ...STOPPED,
              startedAt,
              stopped: { at: stoppedAt, by: AVO },
              conversationEvents: [
                ...CLAIM.conversationEvents,
                {
                  id: "stopped:1",
                  kind: "stopped",
                  at: stoppedAt,
                  by: AVO,
                  run: { runId: RUN_ID, number: 7 },
                },
              ],
            }),
          ],
          [`GET ${STEPS}`]: [
            stepsRead({ run: { ...RUN_HEADER, state: "stopped" } }),
          ],
        },
        address,
        "conversation",
      );
      await landing(
        () => screen.queryByRole("list", { name: "The conversation" }) !== null,
      );
      const actMessage = screen.getByRole("listitem", {
        name: "Stopped this run",
      });
      const detailControl = screen.getByRole("button", {
        name: "Full detail",
      });
      await act(async () => {
        detailControl.focus();
      });
      expect(said()["Started"]).toContain("today");
      expect(said()["Status"]).toContain("today");
      expect(actMessage).toHaveTextContent("today");

      await act(() => vi.advanceTimersByTimeAsync(1000));

      expect(said()["Started"]).toContain("yesterday");
      expect(said()["Status"]).toContain("yesterday");
      expect(actMessage).toHaveTextContent("yesterday");
      expect(detailControl).toHaveFocus();
      expect(router.state.location.search).toBe("?filter=claim&sort=run");
      expect(reads(sent)).toEqual([`GET ${RUN}`, `GET ${STEPS}`]);
    });

    it("rereads a stopped steps snapshot before showing a newly reached step", async () => {
      const oldSteps = stepsRead({
        run: { ...RUN_HEADER, state: "stopped" },
        steps: [SUMMARISE_ROW, { ...FILE_IT_ROW, state: "not_started" }],
      });
      const { sent } = opening(
        {
          [`GET ${RUN}`]: [reply(CLAIM), reply(CLAIM)],
          [`GET ${STEPS}`]: [oldSteps, stepsRead()],
        },
        PAGE,
        "conversation",
      );

      await conversing();
      expect(messages()).toEqual([
        "Run inputs",
        setApart("Summarise"),
        setApart("file it"),
      ]);
      expect(reads(sent)).toEqual([
        `GET ${RUN}`,
        `GET ${STEPS}`,
        `GET ${RUN}`,
        `GET ${STEPS}`,
      ]);
    });

    it("rereads a stopped steps answer after reopening before the next step is reached, then keeps up", async () => {
      vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
      const beforeNextStep = {
        ...CLAIM,
        conversationEvents: CLAIM.conversationEvents.filter(
          (event) => event.stepId !== FILE_IT,
        ),
      };
      const stoppedSteps = stepsRead({
        run: { ...RUN_HEADER, state: "stopped" },
        steps: [SUMMARISE_ROW, notReached(FILE_IT, 2, "file_it")],
      });
      const runningSteps = stepsRead({
        rereadAfterSeconds: 1,
        steps: [SUMMARISE_ROW, notReached(FILE_IT, 2, "file_it")],
      });
      const { sent } = opening(
        {
          [`GET ${RUN}`]: [
            reply(beforeNextStep),
            reply(beforeNextStep),
            reply(CLAIM),
          ],
          [`GET ${STEPS}`]: [stoppedSteps, runningSteps, stepsRead()],
        },
        PAGE,
        "conversation",
      );

      await landing(() => reads(sent).length === 2 && vi.getTimerCount() > 0);
      expect(
        screen.queryByRole("list", { name: "The conversation" }),
      ).toBeNull();
      await act(() => vi.advanceTimersByTimeAsync(100));
      await landing(
        () => screen.queryByRole("list", { name: "The conversation" }) !== null,
      );
      expect(messages()).toEqual(["Run inputs", setApart("Summarise")]);
      expect(reads(sent)).toHaveLength(4);

      await act(() => vi.advanceTimersByTimeAsync(1000));
      await landing(() => messages().includes(setApart("file it")));
      expect(messages()).toEqual([
        "Run inputs",
        setApart("Summarise"),
        setApart("file it"),
      ]);
      expect(reads(sent)).toHaveLength(6);
      expect(screen.queryByRole("alert")).toBeNull();
    });

    it("keeps reading after three mismatched pairs until the reached steps agree", async () => {
      vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
      const oldSteps = stepsRead({
        steps: [
          SUMMARISE_ROW,
          { ...FILE_IT_ROW, stepId: "00000009-0000-4000-8000-000000000c53" },
        ],
      });
      const { sent } = opening(
        {
          [`GET ${RUN}`]: [
            reply(CLAIM),
            reply(CLAIM),
            reply(CLAIM),
            reply(CLAIM),
          ],
          [`GET ${STEPS}`]: [oldSteps, oldSteps, oldSteps, stepsRead()],
        },
        PAGE,
        "conversation",
      );

      await landing(() => reads(sent).length === 2 && vi.getTimerCount() > 0);
      await act(() => vi.advanceTimersByTimeAsync(100));
      await landing(() => reads(sent).length === 4 && vi.getTimerCount() > 0);
      await act(() => vi.advanceTimersByTimeAsync(100));
      await landing(() => reads(sent).length === 6 && vi.getTimerCount() > 0);
      expect(
        screen.queryByRole("list", { name: "The conversation" }),
      ).toBeNull();
      expect(screen.queryByRole("alert")).toBeNull();
      await act(() => vi.advanceTimersByTimeAsync(100));
      await landing(
        () => screen.queryByRole("list", { name: "The conversation" }) !== null,
      );
      expect(messages()).toEqual([
        "Run inputs",
        setApart("Summarise"),
        setApart("file it"),
      ]);
      expect(reads(sent)).toEqual([
        `GET ${RUN}`,
        `GET ${STEPS}`,
        `GET ${RUN}`,
        `GET ${STEPS}`,
        `GET ${RUN}`,
        `GET ${STEPS}`,
        `GET ${RUN}`,
        `GET ${STEPS}`,
      ]);
    });

    it("keeps the last coherent run visible across three mismatched background pairs", async () => {
      vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
      const mismatched = stepsRead({
        steps: [
          SUMMARISE_ROW,
          { ...FILE_IT_ROW, stepId: "00000009-0000-4000-8000-000000000c53" },
        ],
      });
      const { sent } = opening(
        {
          [`GET ${RUN}`]: [
            reply(CLAIM),
            reply(CLAIM),
            reply(CLAIM),
            reply(CLAIM),
            reply({ ...CLAIM, name: "Demo Sample" }),
          ],
          [`GET ${STEPS}`]: [
            stepsRead({ rereadAfterSeconds: 1 }),
            mismatched,
            mismatched,
            mismatched,
            stepsRead(),
          ],
        },
        PAGE,
        "conversation",
      );
      await landing(
        () => screen.queryByRole("list", { name: "The conversation" }) !== null,
      );
      expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent(
        "Claim from Avo",
      );

      await act(() => vi.advanceTimersByTimeAsync(1000));
      await landing(() => reads(sent).length === 4 && vi.getTimerCount() > 0);
      await act(() => vi.advanceTimersByTimeAsync(100));
      await landing(() => reads(sent).length === 6 && vi.getTimerCount() > 0);
      await act(() => vi.advanceTimersByTimeAsync(100));
      await landing(() => reads(sent).length === 8 && vi.getTimerCount() > 0);
      expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent(
        "Claim from Avo",
      );
      expect(screen.queryByRole("alert")).toBeNull();
      expect(screen.queryByText("Loading…")).toBeNull();

      await act(() => vi.advanceTimersByTimeAsync(100));
      await landing(() =>
        (
          screen.queryByRole("heading", { level: 1 })?.textContent ?? ""
        ).includes("Demo Sample"),
      );
      expect(reads(sent)).toHaveLength(10);
      expect(
        screen.getByRole("list", { name: "The conversation" }),
      ).toBeVisible();
    });

    it.each(["run", "steps"] as const)(
      "does not retry a %s read refused by the server as an incoherent pair",
      async (refused) => {
        vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
        const failed = refusal("READ_FAILED", 503);
        const { sent } = opening(
          {
            [`GET ${RUN}`]: [refused === "run" ? failed : reply(CLAIM)],
            [`GET ${STEPS}`]: [refused === "steps" ? failed : stepsRead()],
          },
          PAGE,
          "conversation",
        );
        await landing(() => screen.queryByRole("alert") !== null);
        expect(
          screen.queryByRole("list", { name: "The conversation" }),
        ).toBeNull();
        expect(reads(sent)).toEqual([`GET ${RUN}`, `GET ${STEPS}`]);

        await act(() => vi.advanceTimersByTimeAsync(3000));
        expect(reads(sent)).toEqual([`GET ${RUN}`, `GET ${STEPS}`]);
      },
    );

    const NOTIFY = "00000009-0000-4000-8000-000000000c53";

    const CLOSE_IT = "00000009-0000-4000-8000-000000000c54";

    function notReached(stepId: string, order: number, name: string) {
      return {
        stepId,
        order,
        name,
        runs: { versionName: null, kind: "route" },
        state: "not_started",
        takesFrom: [],
        cost: { callsAModel: false },
        stateActs: [],
        stateRefusals: [],
        producedByUserId: null,
        producedByKind: null,
      };
    }

    const ANSWERED_TRY = {
      wentWrongPresent: false,
      returnedPresent: false,
      declinedPresent: false,
      unreadableResponsePresent: false,
      modelReplyPresent: false,
      number: 1,
      beyond: false,
      producedBy: { kind: "person", person: AVO },
      values: [
        {
          field: "summary",
          value: "A kettle leaks.",
          now: "stands",
          decision: { outcome: "assured" },
        },
      ],
      review: {
        asked: true,
        by: { kind: "person", person: GLINN },
        declinedPresent: false,
        unreadableResponsePresent: false,
        modelReplyPresent: false,
      },
      ended: "stands",
      cost: { callsAModel: false },
    };

    const REFUSED_TRY = {
      ...ANSWERED_TRY,
      values: [
        {
          field: "summary",
          value: "Kettle.",
          now: "refused",
          decision: {
            outcome: "refused",
            whyLines: "several",
            why: "Too short.",
          },
        },
      ],
      ended: "refused_on_review",
    };

    const ASKED_TWICE = {
      ...SUMMARISE_ROW,
      tries: { current: 2, declared: 2, beyond: false },
    };

    function waitingOnReview() {
      return {
        ...SUMMARISE_ROW,
        state: "waiting",
        where: {
          kind: "waiting_on_review",
          number: 1,
          values: [{ field: "summary", on: "review_at_gate" }],
          since: "2026-09-24T08:05:00Z",
          waitsOn: "review_at_gate",
        },
        gaveBack: [
          {
            field: "summary",
            value: "A kettle leaks.",
            now: "waiting_on_review",
          },
        ],
        stateActs: ["review"],
        stateRefusals: [],
        producedByUserId: AVO.userId,
        producedByKind: "person",
      };
    }

    function stepPage(step: object, triesMade: readonly object[]): Reply {
      return tryReplies({
        run: RUN_HEADER,
        declarations: DECLARATIONS,
        step,
        triesMade,
      }).summary;
    }

    function conversation(): HTMLElement {
      return screen.getByRole("list", { name: "The conversation" });
    }

    async function conversing(): Promise<HTMLElement> {
      return screen.findByRole("list", { name: "The conversation" });
    }

    function message(name: string): HTMLElement {
      return within(conversation()).getByRole("listitem", { name });
    }

    function messages(): string[] {
      return [...conversation().children].map(
        (each) =>
          within(each as HTMLElement).getAllByRole("heading", {
            level: 2,
          })[0]!.textContent ?? "",
      );
    }

    function controlled(control: HTMLElement): HTMLElement {
      return document.getElementById(control.getAttribute("aria-controls")!)!;
    }

    function labelled(part: HTMLElement): Record<string, string> {
      const terms = within(part).getAllByRole("term");
      const definitions = within(part).getAllByRole("definition");
      return Object.fromEntries(
        terms.map((term, at) => {
          const definition = definitions[at]!;
          const card = definition.hasAttribute("data-value-content")
            ? definition.closest("section")
            : null;
          const siblings =
            card === null ? [] : Array.from(card.parentElement!.children);
          return [
            term.textContent ?? "",
            (definition.textContent ?? "") +
              (card === null
                ? ""
                : siblings
                    .slice(siblings.indexOf(card) + 1)
                    .filter((each) => each.tagName !== "SECTION")
                    .map((each) => each.textContent ?? "")
                    .join("")),
          ];
        }),
      );
    }

    afterEach(() => {
      vi.useRealTimers();
    });

    it("says About it where it is by the title of the step it is on, how far it has got, what it has cost and its ceiling", async () => {
      opening(
        { [`GET ${RUN}`]: [reply({ ...CLAIM, at: RUN_HEADER.at })] },
        PAGE,
        "conversation",
      );

      expect(await opened()).toHaveTextContent(setApart("Claim from Avo"));
      await conversing();

      expect(said()).toMatchObject({
        Number: "7",
        Status: `RunningNow at ${setApart("file it")}1 of 2 steps done`,
        Usage:
          "9,007,199,254,741,013 in total: 9,007,199,254,740,993 sent, 20 received",
      });
      expect(ceiling().textContent).toBe("9,007,199,254,740,990");
      expect(about()).toContainElement(ceiling());
      const runBadge = within(about())
        .getByText("Status")
        .nextElementSibling!.querySelector("span")!;
      expect(runBadge).toHaveTextContent("Running");
      expect(runBadge.querySelector("svg")).toHaveAttribute(
        "data-testid",
        "AutorenewIcon",
      );
      expect(
        within(about()).getByRole("progressbar", { name: "1 of 2 steps done" }),
      ).toHaveAttribute("aria-valuenow", "50");

      expect(
        within(conversation()).queryByRole("group", { name: "Usage limit" }),
      ).toBeNull();
    });

    it("keeps About it facts and actions alike when the View segment changes the drawing", async () => {
      opening({}, PAGE, "conversation");
      await conversing();
      const facts = said();
      const controls = within(about())
        .getAllByRole("button")
        .map((each) => each.getAttribute("aria-label") ?? each.textContent);
      const indexControls = within(
        screen.getByRole("navigation", { name: "Steps so far" }),
      )
        .getAllByRole("button")
        .map((each) => each.textContent);

      await userEvent.click(
        screen.getByRole("button", { name: "Full detail" }),
      );
      await screen.findByRole("table", { name: "Steps" });

      expect(
        screen.queryByRole("navigation", { name: "Steps so far" }),
      ).toBeNull();
      expect(said()).toEqual(facts);
      expect(
        within(about())
          .getAllByRole("button")
          .map((each) => each.getAttribute("aria-label") ?? each.textContent),
      ).toEqual(controls.filter((each) => !indexControls.includes(each)));

      expect(controls).toEqual([
        "Overview",
        "Stop",
        "9,007,199,254,740,990, editable",
        ...indexControls,
      ]);
    });

    it("says About it that some or all of what it has cost is as this system measured it, never as one try's", async () => {
      opening(
        {
          [`GET ${RUN}`]: [
            reply({
              ...CLAIM,
              spend: { ...CLAIM.spend, measuredHere: "partly" },
            }),
          ],
        },
        PAGE,
        "conversation",
      );
      await conversing();

      const cost = said()["Usage"];
      expect(cost).toBe(
        "9,007,199,254,741,013 in total: 9,007,199,254,740,993 sent, 20 receivedPart of this is estimated by this system, where a model did not report its own count.",
      );
      expect(cost).not.toContain("Both as");
    });

    it("draws the run in Full detail once the View control asks for it, keeping that on this device, the keyboard staying on the selected control, and reading nothing more of the run", async () => {
      const { sent } = opening({}, PAGE, "conversation");
      await conversing();
      const toFullDetail = screen.getByRole("button", {
        name: "Full detail",
      });

      await userEvent.click(toFullDetail);

      expect(
        await screen.findByRole("table", { name: "Steps" }),
      ).toBeInTheDocument();
      expect(toFullDetail).toHaveFocus();
      expect(toFullDetail).toHaveTextContent("Full detail");
      expect(toFullDetail).toHaveAttribute("aria-pressed", "true");
      expect(localStorage.getItem(KEPT_DRAWING_AS)).toBe("fullDetail");
      expect(
        screen.queryByRole("list", { name: "The conversation" }),
      ).toBeNull();
      expect(
        screen.getByRole("button", { name: "Conversation" }),
      ).toHaveAttribute("aria-pressed", "false");

      expect(reads(sent)).toEqual([`GET ${RUN}`, `GET ${STEPS}`]);
    });

    it("reads the steps again once an act on the run answers, heading it with the run the act answered with", async () => {
      const { sent } = opening(
        {
          [`GET ${RUN}`]: [reply(CLAIM), reply(STOPPED)],
          [`GET ${STEPS}`]: [
            stepsRead(),
            stepsRead({ run: { ...RUN_HEADER, state: "stopped" } }),
          ],
          [`PUT ${RUN}/stop`]: [reply(STOPPED)],
        },
        PAGE,
        "conversation",
      );
      await conversing();

      await userEvent.click(screen.getByRole("button", { name: "Stop" }));

      expect(
        await screen.findByRole("button", { name: "Resume" }),
      ).toBeInTheDocument();
      await waitFor(() => expect(messages().at(-1)).toMatch(/^Stopped by /));
      const asked = requestsTo(sent);
      expect(asked.slice(asked.indexOf(`PUT ${RUN}/stop`))).toEqual([
        `PUT ${RUN}/stop`,
        `GET ${RUN}`,
        `GET ${STEPS}`,
      ]);
    });

    it("keeps a stop answer visible while replacing an ordinary read in flight with one fresh pair", async () => {
      const { sent, readAgain } = opening(
        {
          [`GET ${RUN}`]: [reply(CLAIM), UNANSWERED, UNANSWERED],
          [`GET ${STEPS}`]: [stepsRead(), UNANSWERED, UNANSWERED],
          [`PUT ${RUN}/stop`]: [
            refusal("ACT_NOT_PERMITTED", 403),
            reply(STOPPED),
          ],
        },
        PAGE,
        "conversation",
      );
      await conversing();

      await userEvent.click(screen.getByRole("button", { name: "Stop" }));
      await screen.findByRole("alert");
      await waitFor(() => expect(reads(sent)).toHaveLength(4));
      await userEvent.click(screen.getByRole("button", { name: "Stop" }));

      expect(
        await screen.findByRole("button", { name: "Resume" }),
      ).toBeInTheDocument();
      expect(
        requestsTo(sent).filter((each) => each === `PUT ${RUN}/stop`),
      ).toHaveLength(2);
      expect(reads(sent)).toEqual([
        `GET ${RUN}`,
        `GET ${STEPS}`,
        `GET ${RUN}`,
        `GET ${STEPS}`,
        `GET ${RUN}`,
        `GET ${STEPS}`,
      ]);
      expect(readAgain).toHaveBeenCalledOnce();
      expect(screen.queryByRole("alert")).toBeNull();
    });

    /** The run read again after the act is held unanswered, so all that is drawn is what the act answered. */
    it("starts the keyboard at the ceiling once a stop takes its control away from one who may not open the run again", async () => {
      opening(
        {
          [`GET ${RUN}`]: [reply(CLAIM), UNANSWERED],
          [`PUT ${RUN}/stop`]: [
            reply({ ...STOPPED, stateActs: ["rename", "change_ceiling"] }),
          ],
        },
        PAGE,
        "conversation",
      );
      await conversing();

      await userEvent.click(screen.getByRole("button", { name: "Stop" }));

      await waitFor(() => expect(document.activeElement).toBe(ceiling()));
      expect(screen.getByRole("heading", { level: 1 })).not.toHaveFocus();
      expect(screen.queryByRole("button", { name: "Stop" })).toBeNull();
      expect(screen.queryByRole("button", { name: "Resume" })).toBeNull();
    });

    it("draws what it was started with first, then one message per step reached in the order they run, each named by its title, and indexes every step in About it", async () => {
      const { sent } = opening(
        {
          [`GET ${STEPS}`]: [
            stepsRead({
              steps: [
                SUMMARISE_ROW,
                FILE_IT_ROW,
                notReached(NOTIFY, 3, "notify_them"),
                notReached(CLOSE_IT, 4, "close_it"),
              ],
            }),
          ],
        },
        PAGE,
        "conversation",
      );
      await conversing();

      expect(messages()).toEqual([
        "Run inputs",
        setApart("Summarise"),
        setApart("file it"),
      ]);
      const index = screen.getByRole("navigation", {
        name: "Steps so far",
      });
      expect(within(index).getAllByRole("listitem")).toHaveLength(4);
      expect(
        within(index).getByText(setApart("notify them")),
      ).toHaveTextContent(setApart("notify them"));
      expect(within(index).getByText(setApart("close it"))).toHaveTextContent(
        setApart("close it"),
      );
      expect(within(index).getAllByText("Not started")).toHaveLength(2);
      expect(document.getElementById(`step-${NOTIFY}`)).toBeNull();
      expect(screen.queryByRole("table")).toBeNull();
      expect(reads(sent)).toEqual([`GET ${RUN}`, `GET ${STEPS}`]);
    });

    it("says in its first message who started it, with which workflow, and each value it was started with under its label", async () => {
      opening({ [`GET ${RUN}`]: [reply(STARTED)] }, PAGE, "conversation");
      await conversing();

      const first = message("Run inputs");

      expect(first).toHaveTextContent(
        `${setApart("Avo Lumerace")} started this run of ${setApart("Handle a claim")}.`,
      );
      expect(first).not.toHaveTextContent("version 3");
      expect(first).not.toHaveTextContent(whenText(STARTED.startedAt));
      expect(labelled(first)).toEqual({
        [`${setApart("Ticket")} · ticket`]: "Kettle leaks",
        [`${setApart("Due")} · due`]: "Sep 30, 2026",
      });
      expect(
        within(first).getAllByRole("button", { name: "Copy" }),
      ).toHaveLength(2);
    });

    it("shuts a long text it was started with until it is opened, saying whether it is open", async () => {
      vi.spyOn(HTMLElement.prototype, "clientHeight", "get").mockImplementation(
        function (this: HTMLElement) {
          return this.hasAttribute("data-value-content") ? 192 : 0;
        },
      );
      vi.spyOn(HTMLElement.prototype, "scrollHeight", "get").mockImplementation(
        function (this: HTMLElement) {
          return this.hasAttribute("data-value-content") &&
            this.textContent?.includes("Kettle leaks.")
            ? 384
            : 0;
        },
      );
      vi.spyOn(
        HTMLElement.prototype,
        "getBoundingClientRect",
      ).mockImplementation(function (this: HTMLElement) {
        return DOMRect.fromRect({
          height:
            this.parentElement?.hasAttribute("data-value-content") &&
            this.textContent?.includes("Kettle leaks.")
              ? 384
              : 0,
        });
      });
      opening(
        {
          [`GET ${RUN}`]: [
            reply({
              ...STARTED,
              startedWith: {
                ticket: "Kettle leaks.\nIt leaked again.",
                due: null,
              },
            }),
          ],
        },
        PAGE,
        "conversation",
      );
      await conversing();
      const first = message("Run inputs");
      const control = within(first).getByRole("button", {
        name: "Show all",
      });
      expect(control).toHaveAttribute("aria-expanded", "false");
      expect(getComputedStyle(controlled(control)).maxHeight).toBe("192px");
      expect(getComputedStyle(controlled(control)).overflow).toBe("hidden");
      expect(labelled(first)[`${setApart("Due")} · due`]).toBe("None");

      await userEvent.click(control);

      expect(control).toHaveAttribute("aria-expanded", "true");
      expect(controlled(control)).toHaveTextContent(
        "Kettle leaks. It leaked again.",
      );
      expect(control).toHaveAccessibleName("Show less");
      expect(getComputedStyle(controlled(control)).maxHeight).not.toBe("192px");
    });

    it("says in its first message which run above started it, with which workflow, leaving the way to that run About it, and lists nothing it was started with", async () => {
      const { name: _unnamed, startedBy: _nobody, ...beneath } = CLAIM;
      opening(
        {
          [`GET ${RUN}`]: [
            reply({
              ...beneath,
              above: ABOVE,
              runsAbove: [{ runId: ABOVE, number: 6 }],
              from: {
                runId: ABOVE,
                number: 6,
                stepId: FILE_IT,
                stepName: "route",
              },
              stateActs: [],
            }),
          ],
        },
        `${PAGE}?sort=run`,
        "conversation",
      );
      await conversing();

      const first = message("Run inputs");

      expect(first).toHaveTextContent(
        `Step ${setApart("route")} of run 6 started this run of ${setApart("Handle a claim")}.`,
      );
      expect(first).not.toHaveTextContent("version 3");
      expect(within(first).queryAllByRole("link")).toEqual([]);
      expect(
        within(about()).getByRole("link", { name: "Open run 6" }),
      ).toHaveAttribute("href", `/groups/${KEY}/work/${ABOVE}?sort=run`);
      expect(
        within(about()).getByText("Part of", { selector: "dt" }),
      ).toBeVisible();
      expect(
        within(about()).queryByText("Sub-run", { selector: "dt" }),
      ).toBeNull();
      expect(
        within(about()).getByRole("link", {
          name: "Open the step that started it",
        }),
      ).toHaveAttribute(
        "href",
        `/groups/${KEY}/work/${ABOVE}/steps/${FILE_IT}?sort=run`,
      );
      expect(within(first).queryByRole("term")).toBeNull();
    });

    it("keeps a top run's first message with its human starter and no parent links", async () => {
      opening({}, `${PAGE}?sort=run`, "conversation");
      await conversing();

      const first = message("Run inputs");
      expect(first).toHaveTextContent(
        `${setApart("Avo Lumerace")} started this run of ${setApart("Handle a claim")}.`,
      );
      expect(within(first).queryByRole("link", { name: "Part of" })).toBeNull();
      expect(
        within(first).queryByRole("link", {
          name: "Open the step that started it",
        }),
      ).toBeNull();
    });

    it("says in the first message of a run whose starter can no longer be found that somebody who cannot be found started it, and with which workflow", async () => {
      const { startedBy: _nobody, ...unstarted } = STARTED;
      opening({ [`GET ${RUN}`]: [reply(unstarted)] }, PAGE, "conversation");
      await conversing();

      const first = message("Run inputs");

      expect(first).toHaveTextContent(
        `Someone no longer in this system started this run of ${setApart("Handle a claim")}.`,
      );
      expect(first).not.toHaveTextContent("Avo Lumerace");
    });

    it.each([
      ["started it", AVO.userId, true],
      ["did not start it", GLINN.userId, false],
      ["is known to the store as nobody", undefined, false],
    ] as const)(
      "draws the first message on the trailing side, narrower, only where the reader %s, naming who did either way",
      async (_case, reader, trailing) => {
        opening(
          { [`GET ${RUN}`]: [reply(STARTED)] },
          PAGE,
          "conversation",
          reader,
        );
        await conversing();

        const first = message("Run inputs");

        expect(getComputedStyle(first).marginInlineStart === "auto").toBe(
          trailing,
        );
        expect(getComputedStyle(first).maxWidth).toBe("960px");
        expect(first).toHaveTextContent(
          `${setApart("Avo Lumerace")} started this run of ${setApart("Handle a claim")}.`,
        );
        expect(
          getComputedStyle(message(setApart("Summarise"))).marginInlineStart,
        ).not.toBe("auto");
      },
    );

    it.each([
      ["stopped it", GLINN.userId, true],
      ["did not stop it", AVO.userId, false],
    ] as const)(
      "draws the message saying a run stopped on the trailing side only where the reader %s, naming who did either way",
      async (_case, reader, trailing) => {
        opening(
          {
            [`GET ${RUN}`]: [reply({ ...STOPPED, stateActs: [] })],
            [`GET ${STEPS}`]: [
              stepsRead({
                run: { ...RUN_HEADER, state: "stopped", stateActs: [] },
              }),
            ],
          },
          PAGE,
          "conversation",
          reader,
        );
        await conversing();

        const last = conversation().lastElementChild as HTMLElement;

        expect(getComputedStyle(last).marginInlineStart === "auto").toBe(
          trailing,
        );
        expect(last).toHaveTextContent(
          new RegExp(`^Stopped by ${setApart("Glinn Havven")}, `),
        );
        expect(
          getComputedStyle(message("Run inputs")).marginInlineStart === "auto",
        ).toBe(!trailing);
      },
    );

    it.each([
      ["the reader stopped it earlier", GLINN.userId, true],
      ["somebody else stopped it earlier", AVO.userId, true],
      ["nobody stopped it earlier", GLINN.userId, false],
    ] as const)(
      "keeps a failed run's last message on the leading side when %s",
      async (_case, reader, stoppedEarlier) => {
        const stopped = stoppedEarlier
          ? {
              stopped: STOPPED.stopped,
              conversationEvents: [
                ...CLAIM.conversationEvents,
                {
                  id: "run-stopped",
                  kind: "stopped",
                  at: STOPPED.stopped.at,
                  by: GLINN,
                  run: { runId: RUN_ID, number: 7 },
                },
              ],
            }
          : {};
        const { sent } = opening(
          {
            [`GET ${RUN}`]: [
              reply({ ...CLAIM, ...stopped, state: "failed", stateActs: [] }),
            ],
            [`GET ${STEPS}`]: [
              stepsRead({
                run: { ...RUN_HEADER, state: "failed", stateActs: [] },
                steps: [
                  SUMMARISE_ROW,
                  {
                    ...FILE_IT_ROW,
                    state: "failed",
                    where: { kind: "failed", reason: "tries_spent" },
                  },
                ],
              }),
            ],
          },
          PAGE,
          "conversation",
          reader,
        );
        await conversing();

        const last = conversation().lastElementChild as HTMLElement;
        expect(last).toBe(message("Failed"));
        expect(getComputedStyle(last).marginInlineStart).not.toBe("auto");
        expect(getComputedStyle(last).maxWidth).toBe("960px");
        expect(last).toHaveTextContent(`Failed at ${setApart("file it")}.`);
        if (stoppedEarlier) {
          const historical = message("Stopped this run");
          expect(historical).toHaveTextContent(setApart("Glinn Havven"));
          expect(
            getComputedStyle(historical).marginInlineStart === "auto",
          ).toBe(reader === GLINN.userId);
        } else {
          expect(
            within(conversation()).queryByRole("listitem", {
              name: "Stopped this run",
            }),
          ).toBeNull();
        }
        expect(reads(sent)).toEqual([`GET ${RUN}`, `GET ${STEPS}`]);
      },
    );

    it("heads every part of a conversation one level under the part it is in, from the run's name down, and skips none", async () => {
      opening(
        {
          [`GET ${RUN}`]: [reply({ ...CLAIM, state: "failed" })],
          [`GET ${STEPS}`]: [
            stepsRead({
              run: { ...RUN_HEADER, state: "failed" },
              steps: [
                {
                  ...SUMMARISE_ROW,
                  state: "waiting",
                  where: {
                    kind: "waiting_on_review",
                    number: 1,
                    values: [{ field: "summary", on: "review_at_gate" }],
                    since: "2026-09-24T08:05:00Z",
                    waitsOn: "review_at_gate",
                  },
                  gaveBack: [
                    {
                      field: "summary",
                      value: "A kettle leaks.",
                      now: "waiting_on_review",
                    },
                  ],
                  wentIn: [
                    {
                      input: "text",
                      from: { kind: "run_input", path: "ticket" },
                      value: "Kettle leaks",
                    },
                  ],
                  stateActs: ["review"],
                  producedByUserId: AVO.userId,
                  producedByKind: "person",
                },
                {
                  ...FILE_IT_ROW,
                  state: "failed",
                  where: {
                    kind: "failed",
                    reason: "tries_spent",
                    waitsOn: "starter",
                  },
                },
              ],
            }),
          ],
        },
        PAGE,
        "conversation",
        GLINN.userId,
        ["read_own_runs", "review_at_gate"],
      );
      await conversing();

      const headings = screen
        .getAllByRole("heading")
        .map(
          (each) =>
            [Number(each.tagName.slice(1)), each.textContent ?? ""] as const,
        );

      expect(headings).toEqual([
        [1, setApart("Claim from Avo")],
        [2, "Run inputs"],
        [2, setApart("Summarise")],
        [3, "Inputs"],
        [3, `${setApart("Text")} · text`],
        [3, "Needs your review"],
        [3, `${setApart("Summary")} · summary`],
        [2, setApart("file it")],
        [2, "Failed"],
        [3, "Result so far"],
        [3, `${setApart("Summary")} · summary`],
        [2, "Overview"],
        [3, "Usage limit"],
        [3, "Steps so far"],
      ]);
      expect(
        headings.every(
          ([level], at) => at === 0 || level <= headings[at - 1]![0] + 1,
        ),
      ).toBe(true);
    });

    it("says in each step's message where it is in one word and what that try gave back under its label with where each value stands, and which try in its state account, and nothing more until Details is", async () => {
      const { sent } = opening({}, PAGE, "conversation");
      await conversing();

      const summarise = message(setApart("Summarise"));
      const fileIt = message(setApart("file it"));

      expect(stateControl(summarise, "Done")).toHaveAttribute(
        "aria-expanded",
        "false",
      );
      expect(summarise).not.toHaveTextContent("Attempt 1 of 2");

      expect(labelled(summarise)).toEqual({
        [`${setApart("Summary")} · summary`]: "A kettle leaks.",
      });
      expect(
        within(summarise).getByRole("button", { name: "Details" }),
      ).toHaveAttribute("aria-expanded", "false");
      expect(within(summarise).queryByText("Produced by")).toBeNull();
      expect(within(summarise).queryByRole("link")).toBeNull();
      expect(
        within(summarise).queryByRole("button", { name: "Previous attempt" }),
      ).toBeNull();
      expect(accountOpened(summarise, "Done")).toHaveTextContent(
        "Attempt 1 of 2",
      );
      expect(within(summarise).queryByText("Produced by")).toBeNull();
      expect(stateControl(fileIt, "Running")).toHaveAttribute(
        "aria-expanded",
        "false",
      );
      expect(fileIt).not.toHaveTextContent("Its code is running.");
      expect(within(fileIt).queryByRole("term")).toBeNull();

      expect(accountOpened(fileIt, "Running")).toHaveTextContent(
        "Its code is running.",
      );
      expect(fileIt).toHaveTextContent("Attempt 1 of 1");

      expect(within(summarise).queryByText("Produced by")).toBeNull();
      expect(reads(sent)).toEqual([`GET ${RUN}`, `GET ${STEPS}`]);
    });

    it("describes each message's controls by that message's title, so one message's Details is told from another's", async () => {
      opening(
        {
          [`GET ${STEPS}`]: [stepsRead({ steps: [ASKED_TWICE, FILE_IT_ROW] })],
        },
        PAGE,
        "conversation",
      );
      await conversing();
      const summarise = message(setApart("Summarise"));
      const fileIt = message(setApart("file it"));

      expect(
        within(summarise).getByRole("button", { name: "Details" }),
      ).toHaveAccessibleDescription(setApart("Summarise"));
      expect(
        within(fileIt).getByRole("button", { name: "Details" }),
      ).toHaveAccessibleDescription(setApart("file it"));
      expect(
        within(summarise)
          .getAllByRole("button", {
            name: /^(Done|Details|Previous attempt|Next attempt|Copy step)$/,
          })
          .map((each) => each.getAttribute("aria-describedby")),
      ).toEqual(
        Array(5).fill(within(summarise).getByRole("heading", { level: 2 }).id),
      );
      expect(
        within(summarise).getByRole("button", { name: "Copy" }),
      ).toHaveAccessibleDescription(`${setApart("Summary")} · summary`);
    });

    it("reads the step's own page as Details opens, drawing who produced its newest try, who reviewed it, where each input comes from, and the way to its page", async () => {
      const { sent } = opening(
        {
          [`GET ${STEPS}/${SUMMARISE}`]: [
            stepPage(SUMMARISE_ROW, [ANSWERED_TRY]),
          ],
        },
        `${PAGE}?sort=run`,
        "conversation",
      );
      await conversing();
      const details = within(message(setApart("Summarise"))).getByRole(
        "button",
        { name: "Details" },
      );

      await userEvent.click(details);

      expect(details).toHaveAttribute("aria-expanded", "true");
      const opened = controlled(details);
      await within(opened).findByText("Produced by");
      expect(labelled(opened)).toEqual({
        "Requested by": "The run itself",
        "Produced by": setApart("Avo Lumerace"),
        "Reviewed by": setApart("Glinn Havven"),
        Outcome: "Accepted.",
        Usage: "None \u2014 no model is used.",
      });
      const review = within(opened).getByRole("table", {
        name: "Review decision",
      });
      expect(
        within(review).getByRole("rowheader", { name: setApart("Summary") }),
      ).toBeVisible();
      expect(within(review).getByRole("cell")).toHaveTextContent("Accepted.");
      const inputs = within(opened).getByRole("table", { name: "Inputs" });
      expect(
        within(inputs).getByRole("rowheader", { name: setApart("Text") }),
      ).toBeVisible();
      expect(within(inputs).getByRole("cell")).toHaveTextContent(
        `${setApart("Ticket")} (run input)`,
      );
      expect(
        within(opened).getByRole("link", { name: "Open this step's page" }),
      ).toHaveAttribute("href", `${PAGE}/steps/${SUMMARISE}?sort=run`);
      expect(reads(sent)).toEqual([
        `GET ${RUN}`,
        `GET ${STEPS}`,
        `GET ${STEPS}/${SUMMARISE}`,
      ]);

      await userEvent.click(details);

      expect(details).toHaveAttribute("aria-expanded", "false");
      expect(within(opened).queryByText("Produced by")).toBeNull();
    });

    it("says in Details how sure a model was of each value it said so of, a quieter line each under who produced it, and nothing of a value it did not", async () => {
      const model = { kind: "model", model: "small", mode: "careful" };
      const spend = {
        callsAModel: true,
        sent: "12",
        cameBack: "4",
        spent: "16",
        cameBackUnknown: false,
      };
      const sorry = { field: "reply", value: "Sorry.", now: "stands" };
      const modelled = {
        ...SUMMARISE_ROW,
        producer: model,
        cost: spend,
        gaveBack: [...SUMMARISE_ROW.gaveBack, sorry],
      };
      const declarations = {
        ...DECLARATIONS,
        [QUESTION_VERSION]: {
          ...DECLARATIONS[QUESTION_VERSION],
          gives: [
            SUMMARY,
            { ...SUMMARY, name: "reply", label: "Reply" },
            { ...SUMMARY, name: "tone", label: "Tone" },
          ],
        },
      };
      opening(
        {
          [`GET ${STEPS}`]: [
            stepsRead({ declarations, steps: [modelled, FILE_IT_ROW] }),
          ],
          [`GET ${STEPS}/${SUMMARISE}`]: [
            reply({
              run: RUN_HEADER,
              declarations,
              step: modelled,
              triesMade: [
                {
                  ...ANSWERED_TRY,
                  producedBy: model,
                  values: [
                    {
                      field: "summary",
                      value: "A kettle leaks.",
                      now: "stands",
                      confidence: 87,
                      decision: { outcome: "assured" },
                    },
                    { ...sorry, decision: { outcome: "assured" } },
                    {
                      field: "tone",
                      value: "Calm.",
                      now: "stands",
                      confidence: 40,
                      decision: { outcome: "assured" },
                    },
                  ],
                  cost: spend,
                },
              ],
            }),
          ],
        },
        PAGE,
        "conversation",
      );
      await conversing();
      const details = within(message(setApart("Summarise"))).getByRole(
        "button",
        { name: "Details" },
      );

      await userEvent.click(details);

      const opened = controlled(details);
      await within(opened).findByText(`${setApart("Summary")}: 87%`);
      const fields = labelled(opened);
      expect(fields["Produced by"]).toBe(
        `small in careful${setApart("Summary")}: 87%${setApart("Tone")}: 40%`,
      );
      const produced = within(opened).getByText("Produced by", {
        selector: "dt",
      }).nextElementSibling!;
      expect(
        [...produced.querySelectorAll("p")].map((line) => line.textContent),
      ).toEqual([`${setApart("Summary")}: 87%`, `${setApart("Tone")}: 40%`]);
      expect(produced).not.toHaveTextContent(setApart("Reply"));
      expect(Object.keys(fields).slice(0, 3)).toEqual([
        "Requested by",
        "Produced by",
        "Usage",
      ]);
      expect(Object.keys(fields)).not.toContain("Confidence");
    });

    it("says nothing in Details of how sure where no value carries it, as none of a person's try does", async () => {
      opening(
        {
          [`GET ${STEPS}/${SUMMARISE}`]: [
            stepPage(SUMMARISE_ROW, [ANSWERED_TRY]),
          ],
        },
        PAGE,
        "conversation",
      );
      await conversing();
      const details = within(message(setApart("Summarise"))).getByRole(
        "button",
        { name: "Details" },
      );

      await userEvent.click(details);

      const opened = controlled(details);
      await within(opened).findByText("Produced by");
      expect(within(opened).queryByText("Confidence")).toBeNull();
      expect(opened).not.toHaveTextContent(/\d+%/);
    });

    it("turns to an earlier try in the same message, saying which of how many it is and what it gave back and was told, and back to the newest, the keyboard kept on the control left to press", async () => {
      const { sent } = opening(
        {
          [`GET ${STEPS}`]: [stepsRead({ steps: [ASKED_TWICE, FILE_IT_ROW] })],
          [`GET ${STEPS}/${SUMMARISE}`]: [
            stepPage(ASKED_TWICE, [
              REFUSED_TRY,
              { ...ANSWERED_TRY, number: 2 },
            ]),
          ],
        },
        PAGE,
        "conversation",
      );
      await conversing();
      const summarise = message(setApart("Summarise"));
      const earlier = within(summarise).getByRole("button", {
        name: "Previous attempt",
      });
      const later = within(summarise).getByRole("button", {
        name: "Next attempt",
      });
      expect(summarise).not.toHaveTextContent("Attempt 2 of 2");
      const account = accountOpened(summarise, "Done");
      expect(account).toHaveTextContent("Attempt 2 of 2");
      expect(summarise).not.toHaveTextContent("Try 0");
      expect(later).toHaveAttribute("aria-disabled", "true");

      await userEvent.click(earlier);

      await waitFor(() =>
        expect(labelled(summarise)).toEqual({
          [`${setApart("Summary")} · summary`]: "Kettle.",
        }),
      );
      expect(summarise).toHaveTextContent("Rejected in review");
      expect(summarise).toHaveTextContent("Rejected:");
      expect(
        within(summarise)
          .getByText("Too short.", { selector: "p" })
          .closest('[dir="auto"]'),
      ).toBeInTheDocument();
      expect(account).toHaveTextContent("Attempt 1 of 2");
      expect(earlier).toHaveAttribute("aria-disabled", "true");
      expect(later).toBeEnabled();
      expect(document.activeElement).toBe(later);

      await userEvent.click(later);

      expect(labelled(summarise)).toEqual({
        [`${setApart("Summary")} · summary`]: "A kettle leaks.",
      });
      expect(account).toHaveTextContent("Attempt 2 of 2");
      expect(summarise).not.toHaveTextContent("Try 0");
      expect(later).toHaveAttribute("aria-disabled", "true");
      expect(document.activeElement).toBe(earlier);
      expect(reads(sent)).toEqual([
        `GET ${RUN}`,
        `GET ${STEPS}`,
        `GET ${STEPS}/${SUMMARISE}`,
      ]);
    });

    it("says a value of an earlier try turned to that was refused for its length is refused for its length, and not what its review said of it", async () => {
      const refusedForLength = {
        ...REFUSED_TRY,
        values: [
          {
            field: "summary",
            value: "Kettle.",
            now: "refused_for_length",
            decision: {
              outcome: "refused",
              lengthUnits: "10",
              excessUnits: "1",
              lengthHeld: {
                run: { runId: RUN_ID, number: 1 },
                stepId: FILE_IT,
                stepName: "file_it",
              },
            },
          },
        ],
        ended: "refused_for_length",
      };
      opening(
        {
          [`GET ${STEPS}`]: [stepsRead({ steps: [ASKED_TWICE, FILE_IT_ROW] })],
          [`GET ${STEPS}/${SUMMARISE}`]: [
            stepPage(ASKED_TWICE, [
              refusedForLength,
              { ...ANSWERED_TRY, number: 2 },
            ]),
          ],
        },
        PAGE,
        "conversation",
      );
      await conversing();
      const summarise = message(setApart("Summarise"));

      await userEvent.click(
        within(summarise).getByRole("button", { name: "Previous attempt" }),
      );

      await waitFor(() =>
        expect(labelled(summarise)).toEqual({
          [`${setApart("Summary")} · summary`]: "Kettle.",
        }),
      );
      expect(summarise).toHaveTextContent("Rejected for length");
      expect(summarise).toHaveTextContent(
        `${setApart("Summary")}: made the request from ${setApart("file it")} in run 1 too long to send.`,
      );
      expect(summarise).toHaveTextContent(
        `${setApart("Summary")}: about 10 unitsToo long by: about 1 unit`,
      );
      expect(summarise).not.toHaveTextContent("Accepted.");
      expect(summarise).not.toHaveTextContent("Rejected in review");
      expect(
        within(summarise).getByRole("link", {
          name: `Open ${setApart("file it")}`,
        }),
      ).toHaveAttribute(
        "href",
        `/groups/CLAIMS/work/${RUN_ID}/steps/${FILE_IT}`,
      );
    });

    /** A model's try: a person's is never lost, and one of theirs giving nothing back is never followed by another. */
    it("says how a try turned to ended where it gave nothing back", async () => {
      const model = { kind: "model", model: "small", mode: "careful" };
      const spent = (sent: number, cameBack: number) => ({
        callsAModel: true,
        sent: String(sent),
        cameBack: String(cameBack),
        spent: String(sent + cameBack),
        cameBackUnknown: false,
      });
      const asked = { ...ASKED_TWICE, producer: model, cost: spent(20, 5) };
      opening(
        {
          [`GET ${STEPS}`]: [stepsRead({ steps: [asked, FILE_IT_ROW] })],
          [`GET ${STEPS}/${SUMMARISE}`]: [
            stepPage(asked, [
              {
                ...ANSWERED_TRY,
                producedBy: model,
                values: [],
                review: { asked: false },
                ended: "nothing_came_back",
                cost: spent(10, 0),
              },
              {
                ...ANSWERED_TRY,
                number: 2,
                producedBy: model,
                cost: spent(10, 5),
              },
            ]),
          ],
        },
        PAGE,
        "conversation",
      );
      await conversing();
      const summarise = message(setApart("Summarise"));

      await userEvent.click(
        within(summarise).getByRole("button", { name: "Previous attempt" }),
      );

      expect(
        await within(summarise).findByText("No response came back."),
      ).toBeInTheDocument();
      expect(summarise).not.toHaveTextContent("Attempt 1 of 2");
      expect(accountOpened(summarise, "Done")).toHaveTextContent(
        "Attempt 1 of 2",
      );
      expect(within(summarise).queryByRole("term")).toBeNull();
      expect(summarise).not.toHaveTextContent("A kettle leaks.");
    });

    it("says which way a try turned to did not fit, beneath how it ended", async () => {
      const model = { kind: "model", model: "small", mode: "careful" };
      const asked = { ...ASKED_TWICE, producer: model };
      opening(
        {
          [`GET ${STEPS}`]: [stepsRead({ steps: [asked, FILE_IT_ROW] })],
          [`GET ${STEPS}/${SUMMARISE}`]: [
            stepPage(asked, [
              {
                ...ANSWERED_TRY,
                producedBy: model,
                values: [],
                review: { asked: false },
                ended: "did_not_fit",
                didNotFit: "field_missing",
              },
              { ...ANSWERED_TRY, number: 2, producedBy: model },
            ]),
          ],
        },
        PAGE,
        "conversation",
      );
      await conversing();
      const summarise = message(setApart("Summarise"));

      await userEvent.click(
        within(summarise).getByRole("button", { name: "Previous attempt" }),
      );

      const ended = await within(summarise).findByText(
        "The answer did not match the expected format.",
      );
      expect(ended.nextElementSibling).toHaveTextContent(
        "The answer left out a value it was asked for.",
      );
      expect(summarise).not.toHaveTextContent("field_missing");
    });

    const WITH_FILE_CLAIM = {
      ...DECLARATIONS,
      file_claim: {
        takes: [],
        gives: [
          {
            name: "reference",
            label: "Claim reference",
            kind: "text",
            longest: 20,
            mustBeGiven: true,
          },
        ],
      },
    };

    it("says why this system found a code step's try turned to went wrong beneath how it ended, naming what the workflow gives back of the field by its label", async () => {
      const fileIt = {
        ...FILE_IT_ROW,
        tries: { current: 2, declared: 2, beyond: false },
      };
      opening(
        {
          [`GET ${STEPS}`]: [
            stepsRead({
              declarations: WITH_FILE_CLAIM,
              steps: [SUMMARISE_ROW, fileIt],
            }),
          ],
          [`GET ${STEPS}/${FILE_IT}`]: [
            stepPage(fileIt, [
              {
                number: 1,
                beyond: false,
                producedBy: { kind: "code" },
                values: [],
                review: { asked: false },
                ended: "errored",
                erroredFor: {
                  reason: "gives_otherwise",
                  field: "reference",
                  readBy: { output: "summary" },
                },
                cost: { callsAModel: false },
              },
              {
                number: 2,
                beyond: false,
                producedBy: { kind: "code" },
                values: [],
                review: { asked: false },
                ended: "open",
                cost: { callsAModel: false },
              },
            ]),
          ],
        },
        PAGE,
        "conversation",
      );
      await conversing();
      const filing = message(setApart("file it"));

      await userEvent.click(
        within(filing).getByRole("button", { name: "Previous attempt" }),
      );

      const ended = await within(filing).findByText("An error occurred.");
      expect(ended.nextElementSibling).toHaveTextContent(
        `The code's output ${setApart("Claim reference")} no longer matched this workflow's output ${setApart("Summary")}.`,
      );
      expect(filing).not.toHaveTextContent(/gives_otherwise|summary/);
    });

    it("names the later step reading the field of what a code step gives back that holds answering it back, by the title its message is drawn with", async () => {
      const notify = notReached(NOTIFY, 3, "notify_them");
      opening(
        {
          [`GET ${STEPS}`]: [
            stepsRead({
              declarations: WITH_FILE_CLAIM,
              steps: [
                SUMMARISE_ROW,
                {
                  ...FILE_IT_ROW,
                  state: "failed",
                  where: {
                    kind: "failed",
                    reason: "tries_spent",
                    waitsOn: "answer_step",
                  },
                  stateRefusals: [
                    {
                      act: "answer",
                      refusal: "CODE_STEP_GIVES_OTHERWISE",
                    },
                  ],
                  givesOtherwise: {
                    field: "reference",
                    readBy: { step: NOTIFY },
                  },
                },
                notify,
              ],
            }),
          ],
        },
        PAGE,
        "conversation",
        GLINN.userId,
        ["read_own_runs", "answer_step"],
      );
      await conversing();

      const filing = message(setApart("file it"));

      expect(filing).not.toHaveTextContent("no longer matches");
      expect(accountOpened(filing, "Failed")).toHaveTextContent(
        `This code step's output ${setApart("Claim reference")} no longer matches what ${setApart("notify them")} expects from it.`,
      );
      expect(filing).not.toHaveTextContent(/a later step|notify_them/);
      expect(filing).not.toHaveTextContent(NOTIFY);
    });

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
    ])(
      "draws a model review that is %s as waiting in Conversation",
      async (review, sentence) => {
        const fallback = [
          "too_long",
          "list_not_here",
          "takes_no_longer_declared",
          "no_longer_declared",
        ].includes(review);
        const waiting = {
          ...SUMMARISE_ROW,
          state: "waiting",
          reviewer: { kind: "model", model: "small" },
          where: {
            kind: "waiting_on_review",
            review,
            values: [{ field: "summary" }],
            waitsOn: fallback ? "review_at_gate" : "model",
          },
        };
        opening(
          { [`GET ${STEPS}`]: [stepsRead({ steps: [waiting, FILE_IT_ROW] })] },
          PAGE,
          "conversation",
        );
        await conversing();
        const shown = message(setApart("Summarise"));
        expect(stateControl(shown, "Waiting")).toHaveAttribute(
          "aria-expanded",
          "false",
        );
        expect(shown).not.toHaveTextContent(sentence);
        expect(accountOpened(shown, "Waiting")).toHaveTextContent(sentence);
        expect(shown).not.toHaveTextContent("Blocked");
        expect(
          within(shown).queryByText(
            "The model service turned the request away.",
          ),
        ).toBeNull();
      },
    );

    it("says each time a step held back was turned away beneath what holds it, and never again in the try its Details open", async () => {
      const model = { kind: "model", model: "small" };
      const turnedAway = [
        {
          at: "2026-09-24T08:04:00Z",
          said: "Too many asks.",
          cut: true,
          sentAgain: false,
        },
      ];
      const held = {
        ...SUMMARISE_ROW,
        producer: model,
        state: "held_back",
        where: {
          kind: "held_back",
          reason: "turned_away",
          since: "2026-09-24T08:04:00Z",
          turnedAway,
          waitsOn: "starter",
        },
        tries: { current: 0, declared: 2, beyond: false },
        gaveBack: undefined,
      };
      opening(
        {
          [`GET ${STEPS}`]: [stepsRead({ steps: [held, FILE_IT_ROW] })],
          [`GET ${STEPS}/${SUMMARISE}`]: [stepPage(held, [])],
        },
        PAGE,
        "conversation",
      );
      await conversing();
      const summarise = message(setApart("Summarise"));
      expect(summarise).not.toHaveTextContent(/Turned away/);
      accountOpened(summarise, "Blocked");
      const holding = within(summarise).getByText(
        "The model service turned the request away.",
      );
      const turned = within(summarise).getByText(
        `Turned away ${whenText("2026-09-24T08:04:00Z")}.`,
      );
      expect(
        holding.compareDocumentPosition(turned) &
          Node.DOCUMENT_POSITION_FOLLOWING,
      ).toBeTruthy();
      expect(summarise).toHaveTextContent(
        "The model said: Too many asks. (cut short here)",
      );
      expect(within(summarise).getByText("Too many asks.").tagName).toBe("BDI");

      await userEvent.click(
        within(summarise).getByRole("button", { name: "Details" }),
      );

      await within(summarise).findByText("No attempts yet.");
      expect(summarise).not.toHaveTextContent("Try 0");
      expect(within(summarise).queryByText("Produced by")).toBeNull();
      expect(within(summarise).queryByText("Attempt 1 of 2")).toBeNull();
      expect(within(summarise).getAllByText(/^Turned away/)).toEqual([turned]);
      expect(
        within(summarise).queryByText("Times the model service turned it away"),
      ).toBeNull();
    });

    it("lets the step's own page go once Details is shut in the middle of reading it, drawing nothing it answers", async () => {
      let settle!: (answer: readonly [string, number]) => void;
      const held = new Promise<readonly [string, number]>((done) => {
        settle = done;
      });
      const { sent } = opening(
        { [`GET ${STEPS}/${SUMMARISE}`]: [held] },
        PAGE,
        "conversation",
      );
      await conversing();
      const summarise = message(setApart("Summarise"));
      const details = within(summarise).getByRole("button", {
        name: "Details",
      });
      await userEvent.click(details);
      await waitFor(() =>
        expect(reads(sent)).toContain(`GET ${STEPS}/${SUMMARISE}`),
      );

      await userEvent.click(details);
      await act(async () => {
        settle([
          JSON.stringify({
            run: RUN_HEADER,
            declarations: DECLARATIONS,
            step: SUMMARISE_ROW,
            triesMade: [ANSWERED_TRY],
          }),
          200,
        ]);
        await held;
      });

      const [, asked] = sent.mock.calls.find(([address]) =>
        String(address).endsWith(`/steps/${SUMMARISE}`),
      )!;
      expect(asked?.signal?.aborted).toBe(true);
      expect(details).toHaveAttribute("aria-expanded", "false");
      expect(within(summarise).queryByText("Produced by")).toBeNull();
      expect(screen.queryByRole("alert")).toBeNull();
    });

    it("says once that the step's own page is being read, and once that it was refused, where a try turned to and Details both wait on it", async () => {
      let settle!: (answer: readonly [string, number]) => void;
      const held = new Promise<readonly [string, number]>((done) => {
        settle = done;
      });
      opening(
        {
          [`GET ${STEPS}`]: [stepsRead({ steps: [ASKED_TWICE, FILE_IT_ROW] })],
          [`GET ${STEPS}/${SUMMARISE}`]: [held],
        },
        PAGE,
        "conversation",
      );
      await conversing();
      const summarise = message(setApart("Summarise"));
      await userEvent.click(
        within(summarise).getByRole("button", { name: "Previous attempt" }),
      );
      await userEvent.click(
        within(summarise).getByRole("button", { name: "Details" }),
      );

      expect(within(summarise).getAllByText("Loading…")).toHaveLength(1);

      await act(async () => {
        settle([JSON.stringify({ code: "STEP_NOT_IN_VIEW" }), 404]);
        await held;
      });

      const refused = within(summarise).getAllByRole("alert");
      expect(refused).toHaveLength(1);
      expect(refused[0]).toHaveTextContent(
        "That step isn't here, or you can't see it.",
      );
      expect(within(summarise).queryByText("Loading…")).toBeNull();
      expect(within(summarise).queryByText("Produced by")).toBeNull();
    });

    it("reads a turned try once while Details opens and the run rereads unchanged steps", async () => {
      vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
      const { sent } = opening(
        {
          [`GET ${STEPS}`]: [
            stepsRead({
              rereadAfterSeconds: 1,
              steps: [ASKED_TWICE, FILE_IT_ROW],
            }),
          ],
          [`GET ${STEPS}/${SUMMARISE}`]: [
            stepPage(ASKED_TWICE, [
              REFUSED_TRY,
              { ...ANSWERED_TRY, number: 2 },
            ]),
          ],
        },
        PAGE,
        "conversation",
      );
      const stepReads = () =>
        reads(sent).filter((each) => each === `GET ${STEPS}/${SUMMARISE}`)
          .length;
      await landing(
        () => screen.queryByRole("list", { name: "The conversation" }) !== null,
      );
      const summarise = message(setApart("Summarise"));
      act(() =>
        within(summarise)
          .getByRole("button", { name: "Previous attempt" })
          .click(),
      );
      await landing(() => (summarise.textContent ?? "").includes("Kettle."));

      await act(() => vi.advanceTimersByTimeAsync(5000));
      expect(stepReads()).toBe(1);

      const details = within(summarise).getByRole("button", {
        name: "Details",
      });
      act(() => details.click());
      await act(() => vi.advanceTimersByTimeAsync(1000));
      expect(stepReads()).toBe(1);

      act(() => details.click());
      await act(() => vi.advanceTimersByTimeAsync(5000));
      expect(stepReads()).toBe(1);
      expect(vi.getTimerCount()).toBe(2);
    });

    it("refreshes opened Details when its step changes and stops when the run no longer runs", async () => {
      vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
      const done = { ...CLAIM, state: "done", stateActs: [] };
      const { sent } = opening(
        {
          [`GET ${RUN}`]: [reply(CLAIM), reply(CLAIM), reply(done)],
          [`GET ${STEPS}`]: [
            stepsRead({ rereadAfterSeconds: 2 }),
            stepsRead({ rereadAfterSeconds: 2 }),
            stepsRead(
              { run: { ...RUN_HEADER, state: "done", stateActs: [] } },
              { state: "done", where: undefined },
            ),
          ],
          [`GET ${STEPS}/${FILE_IT}`]: [stepPage(FILE_IT_ROW, [ANSWERED_TRY])],
        },
        PAGE,
        "conversation",
      );
      const stepReads = () =>
        reads(sent).filter((each) => each === `GET ${STEPS}/${FILE_IT}`).length;
      await landing(
        () => screen.queryByRole("list", { name: "The conversation" }) !== null,
      );
      await act(() => vi.advanceTimersByTimeAsync(1000));
      const details = within(message(setApart("file it"))).getByRole("button", {
        name: "Details",
      });
      act(() => details.click());
      await landing(() => screen.queryByText("Produced by") !== null);

      await act(() => vi.advanceTimersByTimeAsync(2000));
      expect(stepReads()).toBe(1);
      await act(() => vi.advanceTimersByTimeAsync(2000));
      await landing(() =>
        (message(setApart("file it")).textContent ?? "").includes("Done"),
      );
      await landing(() => stepReads() === 2);
      const whileItRan = stepReads();
      await act(() => vi.advanceTimersByTimeAsync(60_000));

      expect(stepReads()).toBe(whileItRan);
      expect(
        reads(sent).filter((each) => each === `GET ${STEPS}`),
      ).toHaveLength(3);
      expect(vi.getTimerCount()).toBe(1);
      expect(details).toHaveAttribute("aria-expanded", "true");
    });

    it.each([
      [
        "the reader gave it",
        "REVIEW_OWN_PRODUCTION",
        "You produced this, so someone else must review it.",
      ],
      [
        "the reader's roles reach no review",
        "ACT_NOT_PERMITTED",
        "You don't have permission to review it.",
      ],
    ])(
      "says a value waiting on review is not the reader's to review where %s, whom it waits on and since when, and offers nothing to review it with",
      async (_case, refusal, sentence) => {
        opening(
          {
            [`GET ${STEPS}`]: [
              stepsRead({ steps: [waitingOnReview(), FILE_IT_ROW] }),
            ],
          },
          PAGE,
          "conversation",
          refusal === "REVIEW_OWN_PRODUCTION" ? AVO.userId : undefined,
          refusal === "REVIEW_OWN_PRODUCTION"
            ? ["read_own_runs", "review_at_gate"]
            : ["read_own_runs"],
        );
        await conversing();

        const summarise = message(setApart("Summarise"));

        expect(stateControl(summarise, "Waiting")).toHaveAttribute(
          "aria-expanded",
          "false",
        );
        expect(summarise).not.toHaveTextContent(sentence);
        const account = accountOpened(summarise, "Waiting");
        expect(account).toHaveTextContent(
          `${setApart("Summary")} has not been reviewed yet.Waiting for a reviewer.`,
        );
        expect(account).toHaveTextContent(/Since /);
        expect(summarise).toHaveTextContent("Attempt 1 of 2");

        expect(account).toHaveTextContent(sentence);
        expect(within(summarise).getByText("Waiting for review")).toBeVisible();
        expect(labelled(summarise)).toEqual({
          [`${setApart("Summary")} · summary`]: "A kettle leaks.",
        });
        expect(
          within(summarise)
            .getAllByRole("button", { name: /^(Waiting|Details)$/ })
            .map((each) => each.textContent),
        ).toEqual(["Waiting", "Details"]);
        expect(summarise).not.toHaveTextContent(refusal);
      },
    );

    it("says who stopped what holds a step back and when, and says that moment no second time as since when it is held", async () => {
      const stoppedAt = "2026-09-24T08:05:00Z";
      opening(
        {
          [`GET ${RUN}`]: [
            reply({
              ...CLAIM,
              conversationEvents: CLAIM.conversationEvents.filter(
                (event) => event.stepId !== FILE_IT,
              ),
            }),
          ],
          [`GET ${STEPS}`]: [
            stepsRead({
              steps: [
                {
                  ...SUMMARISE_ROW,
                  state: "held_back",
                  where: {
                    kind: "held_back",
                    reason: "entry_stopped",
                    since: stoppedAt,
                    stopped: { what: "entry", by: GLINN, at: stoppedAt },
                    waitsOn: "starter",
                  },
                  gaveBack: [
                    { field: "summary", value: "Kettle.", now: "refused" },
                  ],
                  stateRefusals: [{ act: "answer", refusal: "ENTRY_STOPPED" }],
                },
                notReached(FILE_IT, 2, "file_it"),
              ],
            }),
          ],
        },
        PAGE,
        "conversation",
      );
      await conversing();

      const summarise = message(setApart("Summarise"));

      expect(accountOpened(summarise, "Blocked")).toHaveTextContent(
        `Stopped by ${setApart("Glinn Havven")}, `,
      );
      expect(summarise).not.toHaveTextContent(/Since /);
    });

    it("says since when a step failed that sending may mend, beside who stopped what it runs and when, each its own moment", async () => {
      const failedAt = "2026-09-24T08:02:00Z";
      const stoppedAt = "2026-09-24T08:05:00Z";
      opening(
        {
          [`GET ${RUN}`]: [
            reply({
              ...CLAIM,
              conversationEvents: CLAIM.conversationEvents.filter(
                (event) => event.stepId !== FILE_IT,
              ),
            }),
          ],
          [`GET ${STEPS}`]: [
            stepsRead({
              steps: [
                {
                  ...SUMMARISE_ROW,
                  state: "failed",
                  where: {
                    kind: "failed",
                    reason: "uncuttable_length",
                    since: failedAt,
                    stopped: { what: "entry", by: GLINN, at: stoppedAt },
                    waitsOn: "starter",
                  },
                  stateRefusals: [
                    { act: "try_sending", refusal: "ENTRY_STOPPED" },
                  ],
                },
                notReached(FILE_IT, 2, "file_it"),
              ],
            }),
          ],
        },
        PAGE,
        "conversation",
      );
      await conversing();

      const summarise = message(setApart("Summarise"));

      expect(accountOpened(summarise, "Failed")).toHaveTextContent(
        `Stopped by ${setApart("Glinn Havven")}, ${whenText(stoppedAt)}.`,
      );
      expect(summarise).toHaveTextContent(`Since ${whenText(failedAt)}`);
      expect(summarise).not.toHaveTextContent(`Since ${whenText(stoppedAt)}`);
      expect(summarise).not.toHaveTextContent("It waits on");
    });

    it.each([
      ["done, with what it gave back", CLAIM, {}, "Done", /^Result/],
      [
        "done, as one that acts rather than answers",
        CLAIM,
        { gaveBack: { declares: "nothing" } },
        "Done",
        /^This workflow returns no result: it carries out actions rather than giving answers\.$/,
      ],
      [
        "stopped, by whom, with what stood by then",
        STOPPED,
        {},
        new RegExp(`^Stopped by ${setApart("Glinn Havven")}, `),
        /^Result so far/,
      ],
    ])(
      "ends with its last message where it is %s",
      async (_case, run, changed, title, body) => {
        const state = run === STOPPED ? "stopped" : "done";
        opening(
          {
            [`GET ${RUN}`]: [reply({ ...run, state, stateActs: [] })],
            [`GET ${STEPS}`]: [
              stepsRead(
                { run: { ...RUN_HEADER, state, stateActs: [] }, ...changed },
                // A run whose every step is done is done, stopped or not: a stopped one is left a step short.
                state === "stopped" ? {} : { state: "done", where: undefined },
              ),
            ],
          },
          PAGE,
          "conversation",
        );
        await conversing();

        const last = conversation().lastElementChild as HTMLElement;
        const heading = within(last).getAllByRole("heading", { level: 2 })[0]!;

        expect(heading.textContent).toMatch(title);
        expect(last.textContent!.slice(heading.textContent!.length)).toMatch(
          body,
        );
        expect(
          within(last)
            .queryAllByRole("term")
            .map((term) => term.textContent),
        ).toEqual(
          "gaveBack" in changed ? [] : [`${setApart("Summary")} · summary`],
        );
      },
    );

    it("ends with its last message saying at which step it failed and why, with what stood by then", async () => {
      opening(
        {
          [`GET ${RUN}`]: [reply({ ...CLAIM, state: "failed" })],
          [`GET ${STEPS}`]: [
            stepsRead(
              { run: { ...RUN_HEADER, state: "failed" } },
              {
                state: "failed",
                where: {
                  kind: "failed",
                  reason: "tries_spent",
                  waitsOn: "starter",
                },
              },
            ),
          ],
        },
        PAGE,
        "conversation",
      );
      await conversing();

      const last = message("Failed");

      expect(last).toHaveTextContent(
        `Failed at ${setApart("file it")}.All allowed attempts are used up, and none was accepted.`,
      );
      expect(last).toHaveTextContent("Result so far");
      expect(labelled(last)).toEqual({
        [`${setApart("Summary")} · summary`]: "A kettle leaks.",
      });
      expect(messages().at(-1)).toBe("Failed");
    });

    /** Timeouts are faked, so what is in flight is let land a turn at a time rather than waited on. */
    it("keeps each message up for as long as the steps say to, saying what changed, and leaves the keyboard where the reader put it", async () => {
      vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
      opening(
        {
          [`GET ${STEPS}`]: [
            stepsRead({ rereadAfterSeconds: 1 }),
            stepsRead({}, { state: "done", where: undefined }),
          ],
        },
        PAGE,
        "conversation",
      );
      await landing(
        () => screen.queryByRole("list", { name: "The conversation" }) !== null,
      );
      const details = within(message(setApart("Summarise"))).getByRole(
        "button",
        { name: "Details" },
      );
      await act(async () => {
        details.focus();
      });

      await act(() => vi.advanceTimersByTimeAsync(1000));
      await landing(() =>
        (message(setApart("file it")).textContent ?? "").includes("Done"),
      );

      expect(document.activeElement).toBe(details);
      expect(regionsSaid()).toEqual([
        "",
        `${setApart("file it")}: now Done.`,
        "",
        "",
        "",
        "",
        "",
        "",
      ]);
      expect(message(setApart("file it"))).not.toHaveTextContent("Running");
    });

    it("removes the run, conversation and step values when a later run read loses access", async () => {
      vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
      const { sent } = opening(
        {
          [`GET ${RUN}`]: [reply(CLAIM), refusal("RUN_NOT_IN_VIEW", 404)],
          [`GET ${STEPS}`]: [
            stepsRead({ rereadAfterSeconds: 1 }),
            stepsRead({ rereadAfterSeconds: 1 }),
          ],
        },
        PAGE,
        "conversation",
      );
      await landing(
        () => screen.queryByRole("list", { name: "The conversation" }) !== null,
      );
      expect(screen.getByText("A kettle leaks.")).toBeVisible();

      await act(() => vi.advanceTimersByTimeAsync(1000));
      await landing(
        () =>
          screen.queryByText("That run isn't here, or you can't see it.") !==
          null,
      );

      expect(
        screen.queryByRole("list", { name: "The conversation" }),
      ).toBeNull();
      expect(screen.queryByText("A kettle leaks.")).toBeNull();
      expect(screen.queryByRole("heading", { level: 1 })).toBeNull();
      expect(
        requestsTo(sent).filter((each) => each === `GET ${RUN}`),
      ).toHaveLength(2);
    });

    it.each(["run", "steps"] as const)(
      "removes cached run content when a quiet %s read fails first and its partner later refuses access",
      async (first) => {
        vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
        const later = deferred<readonly [string, number]>();
        const ordinary = refusal("READ_FAILED", 503);
        const { sent } = opening(
          {
            [`GET ${RUN}`]: [
              reply(CLAIM),
              first === "run" ? ordinary : later.promise,
              UNANSWERED,
            ],
            [`GET ${STEPS}`]: [
              stepsRead({ rereadAfterSeconds: 1 }),
              first === "steps" ? ordinary : later.promise,
              UNANSWERED,
            ],
          },
          PAGE,
          "conversation",
        );
        await landing(
          () =>
            screen.queryByRole("list", { name: "The conversation" }) !== null,
        );
        expect(screen.getByText("A kettle leaks.")).toBeVisible();

        await act(() => vi.advanceTimersByTimeAsync(1000));
        await landing(() => screen.queryByText("A kettle leaks.") === null);
        expect(screen.getByRole("alert")).toBeVisible();
        expect(reads(sent)).toEqual([
          `GET ${RUN}`,
          `GET ${STEPS}`,
          `GET ${RUN}`,
          `GET ${STEPS}`,
        ]);
        await act(() => vi.advanceTimersByTimeAsync(3000));
        expect(reads(sent)).toHaveLength(4);

        await act(async () =>
          later.settle([JSON.stringify({ code: "RUN_NOT_IN_VIEW" }), 404]),
        );

        expect(
          screen.getByText("That run isn't here, or you can't see it."),
        ).toBeVisible();
        expect(screen.queryByText("A kettle leaks.")).toBeNull();
        expect(
          screen.queryByRole("list", { name: "The conversation" }),
        ).toBeNull();
        expect(screen.queryByRole("heading", { level: 1 })).toBeNull();
      },
    );

    it("removes the whole run when an open message's own detail read loses access", async () => {
      const { sent } = opening(
        {
          [`GET ${RUN}/steps/${SUMMARISE}`]: [refusal("RUN_NOT_IN_VIEW", 404)],
        },
        PAGE,
        "conversation",
      );
      await conversing();
      await userEvent.click(
        within(message(setApart("Summarise"))).getByRole("button", {
          name: "Details",
        }),
      );

      expect(
        await screen.findByText("That run isn't here, or you can't see it."),
      ).toBeVisible();
      expect(
        screen.queryByRole("list", { name: "The conversation" }),
      ).toBeNull();
      expect(screen.queryByRole("heading", { level: 1 })).toBeNull();
      expect(requestsTo(sent)).toContain(`GET ${RUN}/steps/${SUMMARISE}`);
    });

    it("stops background run reads after a detail refusal closes the page", async () => {
      vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
      const { sent } = opening(
        {
          [`GET ${STEPS}`]: [stepsRead({ rereadAfterSeconds: 1 }), UNANSWERED],
          [`GET ${RUN}/steps/${SUMMARISE}`]: [refusal("RUN_NOT_IN_VIEW", 404)],
        },
        PAGE,
        "conversation",
      );
      await landing(
        () => screen.queryByRole("list", { name: "The conversation" }) !== null,
      );
      act(() =>
        within(message(setApart("Summarise")))
          .getByRole("button", { name: "Details" })
          .click(),
      );
      await landing(
        () =>
          screen.queryByText("That run isn't here, or you can't see it.") !==
          null,
      );
      const runCount = runReads(sent);
      const stepsCount = requestsTo(sent).filter(
        (each) => each === `GET ${STEPS}`,
      ).length;

      await act(() => vi.advanceTimersByTimeAsync(3000));

      expect(runReads(sent)).toBe(runCount);
      expect(
        requestsTo(sent).filter((each) => each === `GET ${STEPS}`),
      ).toHaveLength(stepsCount);
      expect(
        screen.queryByRole("list", { name: "The conversation" }),
      ).toBeNull();
      expect(screen.queryByText("A kettle leaks.")).toBeNull();
    });

    /** Timeouts are faked, so what is in flight is let land a turn at a time rather than waited on. */
    it("says what changed of a step by its message's title, where what it runs is named otherwise than the step", async () => {
      vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
      opening(
        {
          [`GET ${STEPS}`]: [
            stepsRead({
              rereadAfterSeconds: 1,
              steps: [waitingOnReview(), FILE_IT_ROW],
            }),
            stepsRead(),
          ],
        },
        PAGE,
        "conversation",
      );
      await landing(
        () => screen.queryByRole("list", { name: "The conversation" }) !== null,
      );

      await act(() => vi.advanceTimersByTimeAsync(1000));
      await landing(() =>
        (message(setApart("Summarise")).textContent ?? "").includes("Done"),
      );

      expect(regionsSaid()).toEqual([
        "",
        `${setApart("Summarise")}: now Done.`,
        "",
        "",
        "",
        "",
        "",
        "",
      ]);
    });

    /** Timeouts are faked, as where each step is kept up is. */
    it("starts the keyboard at the message the address names as the page arrives, and never again as it is read again", async () => {
      vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
      const { sent, router } = opening(
        { [`GET ${STEPS}`]: [stepsRead({ rereadAfterSeconds: 1 })] },
        `${PAGE}?filter=claim#step-${FILE_IT}`,
        "conversation",
      );
      await landing(
        () => screen.queryByRole("list", { name: "The conversation" }) !== null,
      );
      const aimed = message(setApart("file it"));
      await landing(() => document.activeElement === aimed);
      expect(aimed.id).toBe(`step-${FILE_IT}`);
      expect(screen.getByRole("heading", { level: 1 })).not.toHaveFocus();

      const { pathname, search, hash } = router.state.location;
      expect(`${pathname}${search}${hash}`).toBe(`${PAGE}?filter=claim`);
      expect(router.state.historyAction).toBe("REPLACE");

      await act(() => router.navigate(-1));
      const before = router.state.location;
      expect(`${before.pathname}${before.search}${before.hash}`).toBe(
        `${PAGE}?filter=claim`,
      );

      await act(async () => {
        aimed.blur();
      });
      await act(() => vi.advanceTimersByTimeAsync(1000));
      await landing(() => reads(sent).length === 4);

      expect(document.activeElement).toBe(document.body);
    });

    it("forgets an address naming a step not reached yet, starting the keyboard at the run's name, which no message takes", async () => {
      const { router } = opening(
        {
          [`GET ${STEPS}`]: [
            stepsRead({
              steps: [
                SUMMARISE_ROW,
                FILE_IT_ROW,
                notReached(NOTIFY, 3, "notify_them"),
              ],
            }),
          ],
        },
        `${PAGE}?filter=claim#step-${NOTIFY}`,
        "conversation",
      );
      await conversing();

      await waitFor(() => expect(router.state.location.hash).toBe(""));
      expect(router.state.location.search).toBe("?filter=claim");
      await waitFor(() =>
        expect(screen.getByRole("heading", { level: 1 })).toHaveFocus(),
      );
      expect(document.activeElement).not.toBe(document.body);
    });

    it.each([
      ["a step whose message Full detail does not draw", "fullDetail", FILE_IT],
      [
        "a step of no run of this tree",
        "conversation",
        "00000009-0000-4000-8000-000000000c59",
      ],
    ])(
      "starts the keyboard at the run's name where the address names %s",
      async (_case, drawing, step) => {
        opening(
          {},
          `${PAGE}#step-${step}`,
          drawing as "fullDetail" | "conversation",
        );

        const heading = await opened();

        await waitFor(() => expect(heading).toHaveFocus());
        expect(document.activeElement?.id).not.toBe(`step-${step}`);
      },
    );
  });
});
