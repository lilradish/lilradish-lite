import { screen, waitFor, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import { requestsTo } from "../../testutil/answering";
import {
  PAGE,
  HISTORY,
  RUNS,
  KEPT_AS,
  AVON,
  GLINN,
  AVO,
  LISTED,
  isolatedName,
  opening,
  panel,
  inHistory,
  table,
  precedes,
  rows,
  runsAsked,
} from "../../testutil/workPage";

describe("WorkPage", () => {
  describe("on History", () => {
    it("draws the historical step display name beside the original run link", async () => {
      localStorage.setItem(KEPT_AS, "history");
      const { sent } = opening(PAGE, {
        routes: {
          [`GET ${RUNS}`]: [
            [
              JSON.stringify({
                items: [{ ...GLINN, atLabel: "返金を確認\u202e" }, AVO],
                reading: "own",
              }),
              200,
            ],
          ],
        },
      });
      expect(await rows()).toEqual([
        [
          "SUPPORT-2",
          "Refund for Glinn",
          `${isolatedName("Pay a refund")} \u00b7 Version 2`,
          "11:00 AM yesterday",
          "000c62",
          `RunningNow at ${isolatedName(String.fromCodePoint(0x8fd4, 0x91d1, 0x3092, 0x78ba, 0x8a8d) + "\u202e")}`,
        ],
        [
          "SUPPORT-1",
          "Claim from Avo",
          `${isolatedName("Handle a claim")} \u00b7 Version 1`,
          "Sep 11 10:00 AM",
          "Cat Example",
          "Failed",
        ],
      ]);
      const row = within(table()).getAllByRole("row")[1]!;
      expect(
        within(row).getByRole("link", { name: "SUPPORT-2" }),
      ).toHaveAttribute("href", `/groups/SUPPORT/work/${GLINN.runId}`);
      expect(row).not.toHaveTextContent("summarise");
      expect(runsAsked(sent)).toEqual([`GET ${RUNS}?sort=-started`]);
      expect(GLINN.at).toBe("summarise");
    });

    it("keeps a long step name whole on the line under a running run's badge, wrapping there rather than cutting it short", async () => {
      const long =
        "Check every imaginary receipt against the refund policy and list each line the supplied files fail to support";
      localStorage.setItem(KEPT_AS, "history");
      opening(PAGE, {
        routes: {
          [`GET ${RUNS}`]: [
            [
              JSON.stringify({
                items: [{ ...GLINN, atLabel: long }, AVO],
                reading: "own",
              }),
              200,
            ],
          ],
        },
      });
      await rows();

      const cell = within(table()).getByTestId("AutorenewIcon").closest("td")!;
      const badge = within(cell).getByTestId("AutorenewIcon").parentElement!;
      const nowAt = badge.nextElementSibling as HTMLElement;
      expect(badge.textContent).toBe("Running");
      expect(badge).not.toContainElement(nowAt);
      expect(nowAt.textContent).toBe(`Now at ${isolatedName(long)}`);
      const drawn = getComputedStyle(nowAt);
      expect([
        drawn.display,
        drawn.overflowWrap,
        drawn.whiteSpace,
        drawn.textOverflow,
        drawn.overflow,
      ]).toEqual(["block", "anywhere", "", "clip", ""]);
      expect(cell.textContent).toBe(`RunningNow at ${isolatedName(long)}`);
    });

    it("draws what is running as a table sorted newest first by when each started", async () => {
      const { sent } = opening(PAGE);

      await inHistory();

      expect(await rows()).toEqual([
        [
          "SUPPORT-3",
          "Claim from Avonel",
          `${isolatedName("Handle a claim")} \u00b7 Version 1`,
          "02:00 PM today",
          "Cat Example",
          "Stopped",
        ],
        [
          "SUPPORT-2",
          "Refund for Glinn",
          `${isolatedName("Pay a refund")} \u00b7 Version 2`,
          "11:00 AM yesterday",
          "000c62",
          `RunningNow at ${isolatedName("summarise")}`,
        ],
        [
          "SUPPORT-1",
          "Claim from Avo",
          `${isolatedName("Handle a claim")} \u00b7 Version 1`,
          "Sep 11 10:00 AM",
          "Cat Example",
          "Failed",
        ],
      ]);
      expect(
        within(table()).getByRole("columnheader", { name: /Started$/ }),
      ).toHaveAttribute("aria-sort", "descending");
      expect(runsAsked(sent)).toEqual([`GET ${RUNS}?sort=-started`]);
      const [, ...listed] = within(table()).getAllByRole("row");
      const name = within(listed[0]!).getByText("Claim from Avonel");
      expect(name.tagName).toBe("BDI");
      expect(name.parentElement?.tagName).toBe("STRONG");
      expect(
        within(listed[0]!).getByTestId("PauseCircleOutlinedIcon"),
      ).toBeInTheDocument();
      expect(
        within(listed[1]!).getByTestId("AutorenewIcon"),
      ).toBeInTheDocument();
      const running = within(listed[1]!).getAllByText(
        (_, element) =>
          element?.firstElementChild?.tagName.toLowerCase() === "svg" &&
          element.textContent === "Running",
      );
      expect(running).toHaveLength(1);
      expect(running[0]).toBeVisible();
      const nowAt = running[0]!.nextElementSibling!;
      expect(nowAt.textContent).toBe(`Now at ${isolatedName("summarise")}`);
      expect(nowAt.closest("td")).toBe(running[0]!.closest("td"));
      expect([
        getComputedStyle(nowAt).display,
        getComputedStyle(nowAt).color,
        getComputedStyle(nowAt).fontSize,
        getComputedStyle(nowAt).lineHeight,
      ]).toEqual(["block", "rgb(88, 102, 108)", "13px", "20px"]);
      expect(
        within(listed[2]!).getByTestId("ErrorOutlinedIcon"),
      ).toBeInTheDocument();

      expect(within(table()).queryAllByRole("link", { current: true })).toEqual(
        [],
      );
    });

    it("isolates a mixed-direction run name while keeping the row's link name", async () => {
      const mixedName = "Demo Sample שלום";
      localStorage.setItem(KEPT_AS, "history");
      opening(PAGE, {
        routes: {
          [`GET ${RUNS}`]: [
            [
              JSON.stringify({
                items: [{ ...AVON, name: mixedName }, AVO],
              }),
              200,
            ],
          ],
        },
      });
      await rows();

      const row = within(table()).getAllByRole("row")[1]!;
      const nameCell = within(row).getAllByRole("cell")[1]!;
      const name = within(nameCell).getByText(mixedName);
      expect(name.tagName).toBe("BDI");
      expect(name.parentElement?.tagName).toBe("STRONG");
      expect(nameCell).toHaveTextContent(mixedName);
      expect(
        within(row).getByRole("link", { name: "SUPPORT-3" }),
      ).toBeInTheDocument();
    });

    it.each([
      ["running where no step arrived", { ...GLINN, at: undefined }, "Running"],
      [
        "in a state this build has not heard of, whatever step arrived",
        { ...GLINN, state: "paused" },
        "This page can't show this status yet.",
      ],
    ])(
      "says where a run is %s without naming a step, and every other run as it was",
      async (_case, run, said) => {
        localStorage.setItem(KEPT_AS, "history");
        opening(PAGE, {
          routes: {
            [`GET ${RUNS}`]: [[JSON.stringify({ items: [run, AVO] }), 200]],
          },
        });

        expect((await rows()).map((cells) => cells[5])).toEqual([
          said,
          "Failed",
        ]);
        const [, first] = within(table()).getAllByRole("row");
        if (run.state === "paused") {
          expect(within(first!).queryByTestId("AutorenewIcon")).toBeNull();
          expect(within(first!).queryByRole("img")).toBeNull();
        } else {
          expect(
            within(first!).getByTestId("AutorenewIcon"),
          ).toBeInTheDocument();
        }
      },
    );

    it("draws a narrow leading-side filter under the tabs and above the table", async () => {
      localStorage.setItem(KEPT_AS, "history");
      opening(PAGE);
      await rows();

      const filter = screen.getByRole("searchbox", {
        name: "Part of a run's name, or its workflow's",
      });

      expect(precedes(screen.getByRole("tablist"), filter)).toBe(true);
      expect(precedes(filter, table())).toBe(true);
      expect(panel("History")).toContainElement(filter);
      const filterSpace = filter.closest(".MuiTextField-root")
        ?.parentElement as HTMLElement;
      expect(filterSpace.style.maxWidth).toBe("22rem");
      expect(filterSpace.style.width).toBe("100%");

      expect(screen.getAllByRole("searchbox")).toHaveLength(1);
    });

    it("narrows the rows to what the filter typed holds, as the server matches it, and keeps the filter in the address", async () => {
      localStorage.setItem(KEPT_AS, "history");
      const { sent, at } = opening(PAGE, {
        routes: {
          [`GET ${RUNS}`]: [LISTED, [JSON.stringify({ items: [AVO] }), 200]],
        },
      });
      await rows();

      await userEvent.type(
        screen.getByRole("searchbox", {
          name: "Part of a run's name, or its workflow's",
        }),
        "avo",
      );

      await waitFor(() =>
        expect(runsAsked(sent)).toContain(
          `GET ${RUNS}?sort=-started&filter=avo`,
        ),
      );
      await waitFor(() =>
        expect(
          within(table()).queryByRole("link", { name: "SUPPORT-3" }),
        ).toBeNull(),
      );
      expect(
        within(table()).getByRole("link", { name: "SUPPORT-1" }),
      ).toBeInTheDocument();
      expect(at()).toBe(`${HISTORY}&filter=avo`);
    });

    it.each([
      [
        "read their own, and the filter matches none",
        "own",
        "No run you started matches that.",
        "You have started no run here yet.",
      ],
      [
        "read all, and the filter matches none",
        "all",
        "No run here, or its workflow, is called anything holding that.",
        "Nothing has been run here yet.",
      ],
    ])(
      "says no row matches according to the standing that %s",
      async (_case, reading, said, unsaid) => {
        opening(`${HISTORY}&filter=zz`, {
          permissions: [
            "read_membership",
            ...(reading === "all" ? ["read_all_runs"] : ["read_own_runs"]),
          ],
          routes: {
            [`GET ${RUNS}`]: [['{"items":[]}', 200]],
          },
        });

        expect(await screen.findByText(said)).toBeInTheDocument();
        expect(screen.queryByText(unsaid)).toBeNull();
        expect(screen.getByRole("searchbox")).toHaveValue("zz");
      },
    );

    /** The same empty resource is explained from the current standing, with all taking precedence. */

    it.each([
      [
        "read none",
        "none",
        "Your roles in this group don't let you see runs, including the ones you started.",
      ],
      [
        "read their own, and they started none",
        "own",
        "You have started no run here yet.",
      ],
      [
        "read all, and nothing has been run",
        "all",
        "Nothing has been run here yet.",
      ],
      [
        "read both all and their own, and nothing has been run",
        "both",
        "Nothing has been run here yet.",
      ],
    ])(
      "says why no run is listed according to the standing that %s",
      async (_case, reading, said) => {
        localStorage.setItem(KEPT_AS, "history");
        opening(PAGE, {
          permissions: [
            "read_membership",
            ...(reading === "both"
              ? ["read_all_runs", "read_own_runs"]
              : reading === "all"
                ? ["read_all_runs"]
                : reading === "own"
                  ? ["read_own_runs"]
                  : []),
          ],
          routes: {
            [`GET ${RUNS}`]: [['{"items":[]}', 200]],
          },
        });

        expect(await screen.findByText(said)).toBeInTheDocument();
        expect(within(table()).queryAllByRole("link")).toHaveLength(0);
      },
    );

    it("asks the server for another order when a heading is pressed, and keeps it in the address", async () => {
      localStorage.setItem(KEPT_AS, "history");
      const { sent, at } = opening(PAGE);
      await rows();

      await userEvent.click(
        within(table()).getByRole("button", { name: "Started by" }),
      );

      await waitFor(() =>
        expect(requestsTo(sent)).toContain(`GET ${RUNS}?sort=startedBy`),
      );
      expect(at()).toBe(`${HISTORY}&sort=startedBy`);
    });

    it("offers no order by where a run is, and asks for the runs newest first where the address asks for that order", async () => {
      const { sent } = opening(`${HISTORY}&sort=where`);
      await rows();

      const where = within(table()).getByRole("columnheader", {
        name: "Status",
      });
      expect(within(where).queryByRole("button")).toBeNull();
      expect(where).not.toHaveAttribute("aria-sort");
      expect(
        within(table()).getByRole("columnheader", { name: /Started$/ }),
      ).toHaveAttribute("aria-sort", "descending");
      expect(runsAsked(sent)).toEqual([`GET ${RUNS}?sort=-started`]);
    });

    it("reads the standing again where the server says the reader is in the group no longer", async () => {
      localStorage.setItem(KEPT_AS, "history");
      const { readAgain } = opening(PAGE, {
        routes: {
          [`GET ${RUNS}`]: [['{"code":"GROUP_NOT_IN_VIEW"}', 404]],
        },
      });

      await waitFor(() => expect(readAgain).toHaveBeenCalledTimes(1));
      expect(within(table()).queryAllByRole("link")).toHaveLength(0);
    });
  });
});
