import { act, cleanup, screen, waitFor, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import { requestsTo } from "../../testutil/answering";
import {
  PAGE,
  NEW_RUN,
  HISTORY,
  RUNS,
  OFFERED,
  KEPT_AS,
  isolatedName,
  opening,
  tab,
  panel,
  inHistory,
  table,
  precedes,
  rows,
  runsAsked,
  picking,
} from "../../testutil/workPage";

describe("WorkPage", () => {
  describe("its tabs", () => {
    it("names the permission missing from History and opens its full explanation without changing what may be started", async () => {
      const { sent, at } = opening(PAGE, {
        permissions: ["read_membership", "start_run"],
        routes: {
          [`GET ${RUNS}`]: [['{"items":[]}', 200]],
        },
      });
      expect(
        await screen.findByRole("button", { name: /Handle a claim/ }),
      ).toBeInTheDocument();
      expect(runsAsked(sent)).toEqual([]);

      await inHistory();
      const summary = await screen.findByText(
        "You don't have permission to see runs here.",
      );
      const explanation = screen.getByText(
        "Your roles in this group don't let you see runs, including the ones you started.",
      );
      const details = summary.closest("details");
      expect(summary.tagName).toBe("SUMMARY");
      expect(details).not.toHaveAttribute("open");
      expect(explanation).not.toBeVisible();
      expect(within(table()).queryAllByRole("link")).toHaveLength(0);
      expect(tab("History")).toHaveAttribute("aria-selected", "true");
      expect(at()).toBe(HISTORY);

      await userEvent.click(summary);
      expect(details).toHaveAttribute("open");
      expect(explanation).toBeVisible();
      expect(runsAsked(sent)).toEqual([`GET ${RUNS}?sort=-started`]);
      expect(requestsTo(sent)).toContain(`GET ${OFFERED}`);
      expect(at()).toBe(HISTORY);
      expect(within(table()).queryAllByRole("link")).toHaveLength(0);
    });

    it("opens on New run for one who may start a run on a first visit, drawing the workflows on offer and no run already started", async () => {
      const { sent } = opening(PAGE);

      const newRun = panel("New run");
      expect(
        await within(newRun).findByRole("button", { name: /Handle a claim/ }),
      ).toBeInTheDocument();
      expect(tab("New run")).toHaveAttribute("aria-selected", "true");
      expect(tab("History")).toHaveAttribute("aria-selected", "false");
      expect(screen.getAllByRole("tabpanel")).toEqual([newRun]);
      expect(screen.queryByRole("table")).toBeNull();
      expect(screen.queryByText(/Claim from/)).toBeNull();
      cleanup();
      expect(runsAsked(sent)).toEqual([]);
      expect(requestsTo(sent)).toContain(`GET ${OFFERED}`);
    });

    it("names the page first, then the tabs it holds as one list of tabs, each owning the panel it draws", async () => {
      opening(PAGE);
      await screen.findByRole("button", { name: /Pay a refund/ });

      const title = screen.getByRole("heading", { level: 1 });
      const tabs = screen.getByRole("tablist", {
        name: `Work of ${isolatedName("Claims")}`,
      });
      expect(precedes(title, tabs)).toBe(true);
      expect(within(tabs).getAllByRole("tab")).toEqual([
        tab("New run"),
        tab("History"),
      ]);
      expect(tab("New run")).toHaveAttribute(
        "aria-controls",
        panel("New run").id,
      );
      expect(title).toHaveTextContent(`Work of ${isolatedName("Claims")}`);
      const dialog = await picking("Pay a refund", false);
      expect(dialog).toHaveAccessibleName(
        `Start a run of ${isolatedName("Pay a refund")}`,
      );
      expect(
        within(dialog).getByRole("heading", { name: "Version and name" }),
      ).toBeInTheDocument();
    });

    it.each([
      ["nothing kept", null, "New run"],
      ["New run kept", "newRun", "New run"],
      ["History kept", "history", "History"],
      ["a word this build does not know", "sideways", "New run"],
      ["the name a run is drawn by", "fullDetail", "New run"],
    ])(
      "opens on the tab this device kept where the address names none, naming it there in place: %s",
      async (_case, kept, opened) => {
        if (kept !== null) {
          localStorage.setItem(KEPT_AS, kept);
        }
        const { at, router } = opening(PAGE);

        const other = opened === "New run" ? "History" : "New run";
        expect(tab(opened)).toHaveAttribute("aria-selected", "true");
        expect(tab(other)).toHaveAttribute("aria-selected", "false");
        expect(panel(opened)).toBeVisible();
        expect(screen.getAllByRole("tabpanel")).toHaveLength(1);
        expect(localStorage.getItem(KEPT_AS)).toBe(kept);
        await waitFor(() =>
          expect(at()).toBe(opened === "New run" ? NEW_RUN : HISTORY),
        );
        expect(router.state.historyAction).toBe("REPLACE");
      },
    );

    /** In place of the entry it was on, as a filter typed is: Back leaves Work rather than walking back through its tabs. */

    it("draws the other tab once switched to, naming it in the address in place, keeping it on this device for the next time and leaving the keyboard on the tab pressed", async () => {
      const { at, router } = opening(`${PAGE}?filter=claim&sort=name`);
      await screen.findByRole("button", { name: /Handle a claim/ });

      await inHistory();

      expect(await rows()).toHaveLength(3);
      expect(tab("History")).toHaveFocus();
      expect(localStorage.getItem(KEPT_AS)).toBe("history");
      expect(at()).toBe(`${PAGE}?filter=claim&sort=name&tab=history`);
      expect(router.state.historyAction).toBe("REPLACE");
      expect(
        screen.queryByRole("button", { name: /Handle a claim/ }),
      ).toBeNull();

      await userEvent.click(tab("New run"));

      expect(
        await screen.findByRole("button", { name: /Handle a claim/ }),
      ).toBeInTheDocument();
      expect(localStorage.getItem(KEPT_AS)).toBe("newRun");
      expect(screen.queryByRole("table")).toBeNull();
      expect(at()).toBe(`${PAGE}?filter=claim&sort=name&tab=newRun`);
    });

    /** Activation is manual; moving focus along tabs does not change the selected panel. */

    it("moves along the tabs by the arrow keys without drawing another, which Enter then draws", async () => {
      opening(PAGE);
      const dialog = await picking("Pay a refund", false);
      await userEvent.click(
        within(dialog).getByRole("button", { name: "Cancel" }),
      );
      await act(async () => {
        tab("New run").focus();
      });

      await userEvent.keyboard("{ArrowRight}");

      expect(tab("History")).toHaveFocus();
      expect(tab("History")).toHaveAttribute("aria-selected", "false");
      expect(panel("New run")).toBeVisible();
      expect(screen.queryByRole("dialog")).toBeNull();
      expect(localStorage.getItem(KEPT_AS)).toBeNull();

      await userEvent.keyboard("{Enter}");

      expect(tab("History")).toHaveAttribute("aria-selected", "true");
      expect(table()).toBeInTheDocument();
      expect(
        screen.queryByRole("textbox", { name: "Name of this run" }),
      ).toBeNull();
    });

    it.each([
      ["History, this device keeping New run", "newRun", "history", "History"],
      ["New run, this device keeping History", "history", "newRun", "New run"],
    ])(
      "opens on the tab the address names, %s, keeping what this device kept",
      async (_case, kept, named, opened) => {
        localStorage.setItem(KEPT_AS, kept);
        let at!: () => string;
        await act(async () => {
          ({ at } = opening(`${PAGE}?sort=startedBy&tab=${named}`));
        });

        const other = opened === "New run" ? "History" : "New run";
        expect(tab(opened)).toHaveAttribute("aria-selected", "true");
        expect(tab(other)).toHaveAttribute("aria-selected", "false");
        expect(localStorage.getItem(KEPT_AS)).toBe(kept);
        expect(at()).toBe(`${PAGE}?sort=startedBy&tab=${named}`);
      },
    );

    it("opens History as it was copied from it, filter and order kept", async () => {
      localStorage.setItem(KEPT_AS, "newRun");
      const { sent } = opening(
        `${PAGE}?tab=history&filter=claim&sort=startedBy`,
      );

      expect(await rows()).toHaveLength(3);
      expect(screen.getByRole("searchbox")).toHaveValue("claim");
      expect(
        within(table()).getByRole("columnheader", { name: /Started by$/ }),
      ).toHaveAttribute("aria-sort", "ascending");
      expect(runsAsked(sent)).toEqual([
        `GET ${RUNS}?sort=startedBy&filter=claim`,
      ]);
    });

    it.each([
      ["a filter", `${PAGE}?filter=avo`, `${PAGE}?filter=avo&tab=newRun`],
      [
        "an order",
        `${PAGE}?sort=startedBy`,
        `${PAGE}?sort=startedBy&tab=newRun`,
      ],
      ["what else it holds", `${PAGE}?utm=x`, `${PAGE}?utm=x&tab=newRun`],
      [
        "what a step's page picked",
        `${PAGE}?value=summary&try=1`,
        `${PAGE}?value=summary&try=1&tab=newRun`,
      ],
      ["a tab this build does not know", `${PAGE}?tab=sideways`, NEW_RUN],
    ])(
      "opens on the tab this device kept where the address holds %s and names no tab of itself",
      async (_case, address, named) => {
        localStorage.setItem(KEPT_AS, "newRun");
        const { sent, at } = opening(address);

        expect(tab("New run")).toHaveAttribute("aria-selected", "true");
        expect(tab("History")).toHaveAttribute("aria-selected", "false");
        expect(screen.queryByRole("table")).toBeNull();
        await waitFor(() => expect(at()).toBe(named));
        cleanup();
        expect(runsAsked(sent)).toEqual([]);
      },
    );

    it.each([
      ["nothing kept", null],
      ["New run kept", "newRun"],
    ])(
      "draws History alone, with no tab, for one who may not start a run, whatever the address names, never asking what may be started: %s",
      async (_case, kept) => {
        if (kept !== null) {
          localStorage.setItem(KEPT_AS, kept);
        }
        const { sent, at } = opening(NEW_RUN, {
          permissions: ["read_membership", "read_own_runs"],
        });

        expect(await rows()).toHaveLength(3);
        expect(at()).toBe(HISTORY);
        expect(screen.queryByRole("tablist")).toBeNull();
        expect(screen.queryByRole("tab")).toBeNull();
        expect(screen.queryByRole("tabpanel")).toBeNull();
        expect(
          screen.queryByRole("button", { name: /Handle a claim/ }),
        ).toBeNull();
        cleanup();
        expect(requestsTo(sent)).not.toContain(`GET ${OFFERED}`);
      },
    );
  });
});
