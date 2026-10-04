import { buttonClasses } from "@mui/material/Button";
import {
  act,
  cleanup,
  fireEvent,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import { requestsTo } from "../../testutil/answering";
import {
  PAGE,
  NEW_RUN,
  HISTORY,
  RUNS,
  OFFERED,
  KEPT_AS,
  AVON,
  GLINN,
  AVO,
  LISTED,
  HANDLE,
  REFUND,
  BEGUN,
  BEGUN_UNREADABLE,
  STARTED_UNREADABLE,
  doesNotFit,
  VALUES_REFUSED,
  OPENED_AT,
  ON_OFFER,
  ORDER_ON_OFFER,
  NAME_REFUSED,
  ACT_REFUSED,
  NO_LONGER_OFFERED,
  isolatedName,
  opening,
  tab,
  panel,
  inHistory,
  table,
  precedes,
  rows,
  runsAsked,
  startsAsked,
  picking,
  runOpened,
} from "../../testutil/workPage";

describe("WorkPage", () => {
  describe("its tabs", () => {
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
      await picking("Pay a refund");

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
      expect(
        screen
          .getAllByRole("heading")
          .map((heading) => [heading.tagName, heading.textContent]),
      ).toEqual([
        ["H1", `Work of ${isolatedName("Claims")}`],
        ["H2", isolatedName("Pay a refund")],
      ]);
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

    /** Activated by hand: the form a reader is filling is not put away by a key that only moves along the tabs. */
    it("moves along the tabs by the arrow keys without drawing another, which Enter then draws", async () => {
      opening(PAGE);
      await picking("Pay a refund");
      tab("New run").focus();

      await userEvent.keyboard("{ArrowRight}");

      expect(tab("History")).toHaveFocus();
      expect(tab("History")).toHaveAttribute("aria-selected", "false");
      expect(
        screen.getByRole("textbox", { name: "Name of this run" }),
      ).toBeInTheDocument();
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
      (_case, kept, named, opened) => {
        localStorage.setItem(KEPT_AS, kept);
        const { at } = opening(`${PAGE}?sort=startedBy&tab=${named}`);

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

  describe("on New run", () => {
    it("loads one next page in server order and keeps the selected form while a different name is filtered", async () => {
      const { sent } = opening(PAGE, {
        routes: {
          [`GET ${OFFERED}`]: [
            [JSON.stringify({ items: [HANDLE], nextCursor: "next one" }), 200],
            [JSON.stringify({ items: [REFUND] }), 200],
            [JSON.stringify({ items: [REFUND] }), 200],
          ],
        },
      });
      await picking("Handle a claim");
      const complaint = screen.getByRole("textbox", { name: "Complaint" });
      const name = screen.getByRole("textbox", { name: "Name of this run" });
      await userEvent.type(complaint, "Kettle leaks");
      await userEvent.clear(name);
      await userEvent.type(name, "My claim");

      await userEvent.click(
        screen.getByRole("button", { name: "Load more workflows" }),
      );

      expect(
        await screen.findByRole("button", { name: /Pay a refund/ }),
      ).toBeInTheDocument();
      expect(
        screen.getByRole("button", { name: /Handle a claim/ }),
      ).toBeInTheDocument();
      expect(requestsTo(sent)).toContain(`GET ${OFFERED}?cursor=next+one`);
      expect(
        screen.queryByRole("button", { name: "Load more workflows" }),
      ).toBeNull();

      fireEvent.change(
        screen.getByRole("searchbox", { name: "Part of a name" }),
        {
          target: { value: "refund" },
        },
      );
      await waitFor(() =>
        expect(requestsTo(sent)).toContain(`GET ${OFFERED}?filter=refund`),
      );
      await waitFor(() =>
        expect(
          screen.queryByRole("button", { name: /Handle a claim/ }),
        ).toBeNull(),
      );
      expect(complaint).toHaveValue("Kettle leaks");
      expect(name).toHaveValue("My claim");
      expect(startsAsked(sent)).toEqual([]);
      expect(
        requestsTo(sent).filter((each) => each.startsWith(`GET ${OFFERED}`)),
      ).toHaveLength(3);
    });

    it("keeps accepted cards and the form through a failed next page, then retries that cursor alone", async () => {
      const { sent } = opening(PAGE, {
        routes: {
          [`GET ${OFFERED}`]: [
            [JSON.stringify({ items: [HANDLE], nextCursor: "next" }), 200],
            ['{"code":"SERVICE_BUSY"}', 503],
            [JSON.stringify({ items: [REFUND] }), 200],
          ],
        },
      });
      await picking("Handle a claim");
      const complaint = screen.getByRole("textbox", { name: "Complaint" });
      await userEvent.type(complaint, "Kettle leaks");

      await userEvent.click(
        screen.getByRole("button", { name: "Load more workflows" }),
      );

      expect(await screen.findByRole("alert")).toBeInTheDocument();
      expect(
        screen.getByRole("button", { name: /Handle a claim/ }),
      ).toBeInTheDocument();
      expect(complaint).toHaveValue("Kettle leaks");
      expect(
        requestsTo(sent).filter(
          (each) => each === `GET ${OFFERED}?cursor=next`,
        ),
      ).toHaveLength(1);

      await userEvent.click(
        screen.getByRole("button", { name: "Try loading again" }),
      );

      expect(
        await screen.findByRole("button", { name: /Pay a refund/ }),
      ).toBeInTheDocument();
      expect(
        requestsTo(sent).filter(
          (each) => each === `GET ${OFFERED}?cursor=next`,
        ),
      ).toHaveLength(2);
      expect(complaint).toHaveValue("Kettle leaks");
    });

    it("issues one next-page request for two presses before the next page can settle", async () => {
      let finishNext: (reply: readonly [string, number]) => void = () => {};
      const { sent } = opening(PAGE, {
        routes: {
          [`GET ${OFFERED}`]: [
            [JSON.stringify({ items: [HANDLE], nextCursor: "next" }), 200],
            new Promise((settle) => {
              finishNext = settle;
            }),
          ],
        },
      });
      await screen.findByRole("button", { name: /Handle a claim/ });
      const next = screen.getByRole("button", { name: "Load more workflows" });

      act(() => {
        fireEvent.click(next);
        fireEvent.click(next);
      });

      await waitFor(() =>
        expect(
          requestsTo(sent).filter(
            (each) => each === `GET ${OFFERED}?cursor=next`,
          ),
        ).toHaveLength(1),
      );
      finishNext([JSON.stringify({ items: [REFUND] }), 200]);
      expect(
        await screen.findByRole("button", { name: /Pay a refund/ }),
      ).toBeInTheDocument();
      expect(
        screen.getByRole("button", { name: /Handle a claim/ }),
      ).toBeInTheDocument();
      expect(
        screen.queryByRole("button", { name: "Load more workflows" }),
      ).toBeNull();
    });

    it("takes accepted cards and the selected form away when the next page refuses the reader's authority", async () => {
      const { sent, readAgain } = opening(PAGE, {
        routes: {
          [`GET ${OFFERED}`]: [
            [JSON.stringify({ items: [HANDLE], nextCursor: "next" }), 200],
            ['{"code":"ACT_NOT_PERMITTED"}', 403],
          ],
        },
      });
      await picking("Handle a claim");
      const name = screen.getByRole("textbox", { name: "Name of this run" });

      await userEvent.click(
        screen.getByRole("button", { name: "Load more workflows" }),
      );

      expect(await screen.findByRole("alert")).toBeInTheDocument();
      expect(
        screen.queryByRole("button", { name: /Handle a claim/ }),
      ).toBeNull();
      expect(name).not.toBeInTheDocument();
      expect(
        screen.queryByRole("button", { name: "Try loading again" }),
      ).toBeNull();
      expect(
        requestsTo(sent).filter((each) => each.startsWith(`GET ${OFFERED}`)),
      ).toHaveLength(2);
      await waitFor(() => expect(readAgain).toHaveBeenCalledTimes(1));
    });

    it("retries a failed first page without calling its failure an empty offer", async () => {
      const { sent } = opening(PAGE, {
        routes: {
          [`GET ${OFFERED}`]: [['{"code":"SERVICE_BUSY"}', 503], ON_OFFER],
        },
      });

      expect(await screen.findByRole("alert")).toBeInTheDocument();
      expect(
        screen.queryByText(
          "No workflow of this group can be started: none has a version in service that is not stopped.",
        ),
      ).toBeNull();
      expect(
        screen.queryByRole("button", { name: /Handle a claim/ }),
      ).toBeNull();

      await userEvent.click(
        screen.getByRole("button", { name: "Try loading again" }),
      );

      expect(
        await screen.findByRole("button", { name: /Handle a claim/ }),
      ).toBeInTheDocument();
      expect(
        requestsTo(sent).filter((each) => each === `GET ${OFFERED}`),
      ).toHaveLength(2);
    });

    it("hands on the final composed filter and says only after its empty page settles that no name matches", async () => {
      const { sent } = opening(PAGE, {
        routes: {
          [`GET ${OFFERED}`]: [ON_OFFER, [JSON.stringify({ items: [] }), 200]],
        },
      });
      await screen.findByRole("button", { name: /Handle a claim/ });
      const filter = screen.getByRole("searchbox", { name: "Part of a name" });

      fireEvent.compositionStart(filter);
      fireEvent.change(filter, { target: { value: "none" } });
      expect(filter).toHaveValue("none");
      expect(
        requestsTo(sent).filter((each) => each.startsWith(`GET ${OFFERED}`)),
      ).toHaveLength(1);
      fireEvent.compositionEnd(filter);

      expect(
        await screen.findByText(
          "No workflow on offer here has a name holding that.",
        ),
      ).toBeInTheDocument();
      expect(requestsTo(sent)).toContain(`GET ${OFFERED}?filter=none`);
      expect(
        screen.queryByRole("button", { name: /Handle a claim/ }),
      ).toBeNull();
      expect(screen.queryByRole("alert")).toBeNull();
    });

    it("does not send a half-typed workflow filter after leaving New run or alter History's address filter", async () => {
      const { sent, at } = opening(`${PAGE}?filter=claim`);
      await screen.findByRole("button", { name: /Handle a claim/ });
      vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
      fireEvent.change(
        screen.getByRole("searchbox", { name: "Part of a name" }),
        {
          target: { value: "refund" },
        },
      );

      fireEvent.click(tab("History"));
      act(() => vi.advanceTimersByTime(350));

      expect(at()).toBe(`${PAGE}?filter=claim&tab=history`);
      expect(
        requestsTo(sent).filter((each) => each.startsWith(`GET ${OFFERED}`)),
      ).toEqual([`GET ${OFFERED}`]);
      expect(
        screen.getByRole("searchbox", {
          name: "Part of a run's name, or its workflow's",
        }),
      ).toHaveValue("claim");
    });

    it("abandons old pages and a selected draft when the reader changes within the group", async () => {
      let finishOld: (reply: readonly [string, number]) => void = () => {};
      const { sent, changeReader } = opening(PAGE, {
        routes: {
          [`GET ${OFFERED}`]: [
            [JSON.stringify({ items: [HANDLE], nextCursor: "next" }), 200],
            new Promise((settle) => {
              finishOld = settle;
            }),
            [JSON.stringify({ items: [REFUND] }), 200],
          ],
        },
      });
      await picking("Handle a claim");
      await userEvent.type(
        screen.getByRole("textbox", { name: "Complaint" }),
        "Kettle leaks",
      );
      await userEvent.click(
        screen.getByRole("button", { name: "Load more workflows" }),
      );
      await waitFor(() =>
        expect(
          requestsTo(sent).filter((each) => each.startsWith(`GET ${OFFERED}`)),
        ).toHaveLength(2),
      );

      act(() => changeReader("another-reader"));
      finishOld([JSON.stringify({ items: [HANDLE] }), 200]);

      expect(
        await screen.findByRole("button", { name: /Pay a refund/ }),
      ).toBeInTheDocument();
      expect(
        screen.queryByRole("button", { name: /Handle a claim/ }),
      ).toBeNull();
      expect(screen.queryByRole("textbox", { name: "Complaint" })).toBeNull();
      expect(
        screen.queryByRole("textbox", { name: "Name of this run" }),
      ).toBeNull();
      expect(
        requestsTo(sent).filter((each) => each.startsWith(`GET ${OFFERED}`)),
      ).toHaveLength(3);
    });
    it("draws the workflows on offer as cards, each saying what it is for, pressable and none picked yet", async () => {
      const { sent } = opening(PAGE);

      const offered = panel("New run");

      expect(
        await within(offered).findByText(isolatedName("Handle a claim")),
      ).toBeInTheDocument();
      expect(
        within(offered).getByText(isolatedName("Sorts a claim out.")),
      ).toBeInTheDocument();
      expect(within(offered).getAllByTestId("AccountTreeIcon")).toHaveLength(2);
      expect(
        within(offered)
          .getAllByRole("button")
          .map((card) => [card.textContent, card.getAttribute("aria-pressed")]),
      ).toEqual([
        [
          `${isolatedName("Handle a claim")}${isolatedName("Sorts a claim out.")}`,
          "false",
        ],
        [isolatedName("Pay a refund"), "false"],
      ]);
      expect(within(offered).queryByRole("textbox")).toBeNull();
      expect(requestsTo(sent)).toContain(`GET ${OFFERED}`);

      const card = within(offered)
        .getByRole("button", { name: /Handle a claim/ })
        .closest<HTMLElement>(".MuiCard-root")!;
      const name = within(card).getByText(isolatedName("Handle a claim"));
      const purpose = within(card).getByText(
        isolatedName("Sorts a claim out."),
      );
      const icon = within(card).getByTestId("AccountTreeIcon");
      expect(name.parentElement).toBe(purpose.parentElement);
      expect(icon.parentElement).toBe(name.parentElement?.parentElement);
      expect(getComputedStyle(icon.parentElement!).display).toBe("flex");
    });

    it("draws under the cards, once one is picked, what its newest version takes with a name already suggested, the version not shown until asked for", async () => {
      opening(PAGE);

      const form = await picking("Handle a claim");

      const name = screen.getByRole("textbox", { name: "Name of this run" });
      expect(name).toHaveFocus();
      expect(form).not.toHaveFocus();
      const formSpace = form.closest("section") as HTMLElement;
      expect(formSpace.style.maxWidth).toBe("");
      const card = screen
        .getByRole("button", { name: /Handle a claim/ })
        .closest<HTMLElement>(".MuiCard-root")!;
      const browse = card.parentElement!;
      expect(browse.parentElement).toBe(formSpace.parentElement);
      expect(browse.style.maxWidth).toBe("");
      const browseStyle = getComputedStyle(browse);
      const formStyle = getComputedStyle(formSpace);
      expect(browse.contains(formSpace)).toBe(false);
      expect(formSpace).toContainElement(name);
      expect(browseStyle.display).toBe("grid");
      expect(browseStyle.borderWidth).toBe("1px");
      expect(formStyle.borderWidth).toBe("2px");
      expect(browseStyle.backgroundColor).not.toBe(formStyle.backgroundColor);
      expect(browseStyle.borderColor).not.toBe(formStyle.borderColor);
      expect(screen.getByRole("button", { name: "Start" })).toHaveClass(
        buttonClasses.contained,
      );
      expect(screen.getByRole("button", { name: "Cancel" })).toHaveClass(
        buttonClasses.outlined,
        buttonClasses.colorInherit,
      );
      expect(
        screen.getByRole("button", { name: /Handle a claim/ }),
      ).toHaveAttribute("aria-pressed", "true");
      expect(
        screen.getByRole("textbox", { name: "Name of this run" }),
      ).toHaveValue(`Handle a claim ${OPENED_AT}`);
      expect(
        screen.getByRole("textbox", { name: "Complaint" }),
      ).toBeInTheDocument();
      expect(screen.queryByRole("combobox", { name: "Version" })).toBeNull();
      expect(
        screen.getByRole("button", { name: "Choose another version" }),
      ).toBeInTheDocument();
    });

    it("returns focus to the name when the selected card is pressed again or another card is picked", async () => {
      opening(PAGE);
      await picking("Handle a claim");
      const firstName = screen.getByRole("textbox", {
        name: "Name of this run",
      });
      const complaint = screen.getByRole("textbox", { name: "Complaint" });
      complaint.focus();

      await userEvent.click(
        screen.getByRole("button", { name: /Handle a claim/ }),
      );

      expect(firstName).toHaveFocus();
      await picking("Pay a refund");
      const nextName = screen.getByRole("textbox", {
        name: "Name of this run",
      });
      expect(nextName).toHaveFocus();
      expect(nextName).not.toBe(firstName);
    });

    it("offers no other version where only one is in service, and nothing to fill where it takes nothing", async () => {
      opening(PAGE);

      await picking("Pay a refund");

      expect(
        screen.queryByRole("button", { name: "Choose another version" }),
      ).toBeNull();
      expect(
        screen.getByText("It takes nothing, so it may be started as it is."),
      ).toBeInTheDocument();
      expect(screen.getAllByRole("textbox")).toEqual([
        screen.getByRole("textbox", { name: "Name of this run" }),
      ]);
    });

    it("names the run afresh once another card is picked, forgetting a name typed for the one before", async () => {
      opening(PAGE);
      await picking("Pay a refund");
      const typed = screen.getByRole("textbox", { name: "Name of this run" });
      await userEvent.clear(typed);
      await userEvent.type(typed, "Mine");

      await picking("Handle a claim");

      expect(
        screen.getByRole("textbox", { name: "Name of this run" }),
      ).toHaveValue(`Handle a claim ${OPENED_AT}`);
      await picking("Pay a refund");
      expect(
        screen.getByRole("textbox", { name: "Name of this run" }),
      ).toHaveValue(`Pay a refund ${OPENED_AT}`);
    });

    it("suggests the workflow and the form's own drawing time, unaffected by filling, while allowing a chosen name", async () => {
      opening(PAGE);
      vi.setSystemTime(new Date("2026-09-18T13:07:00Z"));
      await picking("Handle a claim");
      const name = screen.getByRole("textbox", { name: "Name of this run" });
      const complaint = screen.getByRole("textbox", { name: "Complaint" });

      expect(name).toHaveValue("Handle a claim Sep 18, 03:07 PM");
      await userEvent.type(complaint, "Kettle leaks{Enter}Since Monday");
      vi.setSystemTime(new Date("2026-09-18T13:19:00Z"));
      await userEvent.type(complaint, ", badly");
      expect(name).toHaveValue("Handle a claim Sep 18, 03:07 PM");

      await userEvent.clear(name);
      expect(name).toHaveValue("");
      await userEvent.type(name, "Mine");
      await userEvent.type(complaint, " again");
      expect(name).toHaveValue("Mine");
    });

    it("cuts only a long workflow name so the whole drawing time remains in the suggested name", async () => {
      const symbol = String.fromCodePoint(0x1f600);
      const longName = symbol.repeat(140);
      opening(PAGE, {
        routes: {
          [`GET ${OFFERED}`]: [
            [JSON.stringify({ items: [{ ...REFUND, name: longName }] }), 200],
          ],
        },
      });
      await picking(longName);

      const expected = `${symbol.repeat(128 - 1 - OPENED_AT.length)} ${OPENED_AT}`;
      const name = screen.getByRole("textbox", { name: "Name of this run" });
      expect(name).toHaveValue(expected);
      expect([...name.getAttribute("value")!]).toHaveLength(128);
      expect(name.getAttribute("value")!.length).toBeGreaterThan(128);
      expect(name).not.toHaveAttribute("aria-invalid", "true");
    });

    it("keeps a card's continuous name and purpose available to wrap inside their shared text column", async () => {
      const longName = "W".repeat(128);
      const longPurpose = "P".repeat(180);
      opening(PAGE, {
        routes: {
          [`GET ${OFFERED}`]: [
            [
              JSON.stringify({
                items: [{ ...REFUND, name: longName, purpose: longPurpose }],
              }),
              200,
            ],
          ],
        },
      });

      const button = await screen.findByRole("button", {
        name: new RegExp(longName),
      });
      const name = within(button).getByText(isolatedName(longName));
      const purpose = within(button).getByText(isolatedName(longPurpose));
      const words = name.parentElement!;

      expect(words).toBe(purpose.parentElement);
      expect(getComputedStyle(words).overflowWrap).toBe("anywhere");
      expect(name.textContent).toBe(isolatedName(longName));
      expect(purpose.textContent).toBe(isolatedName(longPurpose));
      expect(getComputedStyle(button.closest(".MuiCard-root")!).overflow).toBe(
        "hidden",
      );
    });

    it("says at once that what must be given is empty, and once Start is pressed marks it so, takes the keyboard there and asks nothing", async () => {
      const { sent } = opening(PAGE, {
        routes: { [`POST ${RUNS}`]: [BEGUN] },
      });
      await picking("Handle a claim");
      const complaint = screen.getByRole("textbox", { name: "Complaint" });
      const start = screen.getByRole("button", { name: "Start" });
      expect(
        screen.getByText("A field that must be given is still empty."),
      ).toBeInTheDocument();
      expect(complaint).toHaveAttribute("aria-invalid", "false");

      start.focus();
      await userEvent.keyboard("{Enter}");

      expect(start).not.toHaveAttribute("aria-disabled");
      expect(complaint).toHaveAttribute("aria-invalid", "true");
      expect(complaint).toHaveAccessibleDescription(
        expect.stringContaining("This must be given."),
      );
      expect(complaint).toHaveFocus();
      expect(startsAsked(sent)).toEqual([]);
    });

    it("takes the keyboard to the name once Start is pressed where it is none a run may be called, and asks nothing", async () => {
      const { sent } = opening(PAGE, {
        routes: { [`POST ${RUNS}`]: [BEGUN] },
      });
      await picking("Pay a refund");
      const name = screen.getByRole("textbox", { name: "Name of this run" });
      await userEvent.clear(name);

      await userEvent.click(screen.getByRole("button", { name: "Start" }));

      expect(name).toHaveFocus();
      expect(name).toHaveAttribute("aria-invalid", "true");
      expect(startsAsked(sent)).toEqual([]);
    });

    it("takes the keyboard to the first place that does not fit, in declared order, marking every place and asking nothing", async () => {
      const { sent } = opening(PAGE, {
        routes: {
          [`GET ${OFFERED}`]: [ORDER_ON_OFFER],
          [`POST ${RUNS}`]: [BEGUN],
        },
      });
      await picking("Take an order");
      const items = screen.getByRole("group", { name: "Items" });
      await userEvent.click(
        within(items).getByRole("button", {
          name: `Add one to ${isolatedName("Items")}`,
        }),
      );
      await userEvent.type(
        within(items).getByRole("textbox", { name: "Count" }),
        "2",
      );

      await userEvent.click(screen.getByRole("button", { name: "Start" }));

      const name = within(items).getByRole("textbox", { name: "Name" });
      expect(name).toHaveFocus();
      expect(name).toHaveAttribute("aria-invalid", "true");
      expect(
        within(items).getByRole("textbox", { name: "Count" }),
      ).toHaveAttribute("aria-invalid", "false");
      expect(startsAsked(sent)).toEqual([]);
    });

    it("says why a value does not fit only once the keyboard has left it, while saying at once that a value does not fit", async () => {
      opening(PAGE);
      await picking("Handle a claim");
      const complaint = screen.getByRole("textbox", { name: "Complaint" });

      await userEvent.type(complaint, `Kettle${String.fromCodePoint(0x2066)}`);
      expect(complaint).toHaveAttribute("aria-invalid", "false");
      expect(
        screen.getByText(
          "A value does not fit its field yet; each one says why.",
        ),
      ).toBeInTheDocument();

      await userEvent.tab();

      expect(complaint).toHaveAttribute("aria-invalid", "true");
    });

    it("says fields that must be given hold nothing once what they hold has been changed and emptied", async () => {
      const address = {
        entryId: "00000006-0000-4000-8000-000000000c63",
        name: "Send a letter",
        versions: [
          {
            versionId: "00000007-0000-4000-8000-000000000c64",
            number: 1,
            takes: [
              {
                name: "address",
                label: "Address",
                kind: "fields",
                mustBeGiven: true,
                fields: [
                  {
                    name: "street",
                    label: "Street",
                    kind: "text",
                    longest: 20,
                    mustBeGiven: false,
                  },
                ],
              },
            ],
          },
        ],
      };
      opening(PAGE, {
        routes: {
          [`GET ${OFFERED}`]: [[JSON.stringify({ items: [address] }), 200]],
        },
      });
      await picking("Send a letter");
      const group = screen.getByRole("group", { name: "Address" });
      expect(group).toHaveAccessibleDescription("");

      const street = within(group).getByRole("textbox", { name: "Street" });
      await userEvent.type(street, "1");
      await userEvent.clear(street);

      expect(group).toHaveAccessibleDescription("This must be given.");
      expect(street).toHaveAttribute("aria-invalid", "false");
    });

    it("moves what was changed of each one of many down past one taken away, so one added in its place is not yet said to be empty", async () => {
      const labelled = {
        entryId: "00000006-0000-4000-8000-000000000c65",
        name: "Label a note",
        versions: [
          {
            versionId: "00000007-0000-4000-8000-000000000c66",
            number: 1,
            takes: [
              {
                name: "tags",
                label: "Tags",
                kind: "text",
                longest: 20,
                most: 2,
                mustBeGiven: false,
              },
            ],
          },
        ],
      };
      opening(PAGE, {
        routes: {
          [`GET ${OFFERED}`]: [[JSON.stringify({ items: [labelled] }), 200]],
        },
      });
      await picking("Label a note");
      const tags = screen.getByRole("group", { name: "Tags" });
      const tag = (position: number) =>
        within(tags).getByRole("textbox", {
          name: `${isolatedName("Tags")} ${position}`,
        });
      const addOne = () =>
        userEvent.click(
          within(tags).getByRole("button", {
            name: `Add one to ${isolatedName("Tags")}`,
          }),
        );
      await addOne();
      expect(tag(1)).toHaveFocus();
      await userEvent.type(tag(1), "a");
      await addOne();
      await userEvent.type(tag(2), "b");

      await userEvent.click(
        within(tags).getByRole("button", {
          name: `Take away ${isolatedName("Tags")} 1`,
        }),
      );
      await addOne();

      expect(tag(1)).toHaveValue("b");
      expect(tag(1)).toHaveAttribute("aria-invalid", "false");
      expect(tag(2)).toHaveValue("");
      expect(tag(2)).toHaveAttribute("aria-invalid", "false");
      expect(tag(2)).not.toHaveAccessibleDescription(/must be given/);
      expect(tags).toHaveAccessibleDescription("2 / at most 2");
    });

    it("starts another version once asked for, filling what that one takes", async () => {
      const { sent } = opening(PAGE, {
        routes: { [`POST ${RUNS}`]: [BEGUN] },
      });
      await picking("Handle a claim");

      await userEvent.click(
        screen.getByRole("button", { name: "Choose another version" }),
      );
      expect(screen.getByRole("combobox", { name: "Version" })).toHaveFocus();
      await userEvent.click(screen.getByRole("combobox", { name: "Version" }));
      await userEvent.click(screen.getByRole("option", { name: "Version 1" }));
      expect(screen.getByRole("combobox", { name: "Version" })).toHaveFocus();
      await userEvent.click(screen.getByRole("button", { name: "Start" }));

      await waitFor(() =>
        expect(startsAsked(sent)).toEqual([
          {
            name: `Handle a claim ${OPENED_AT}`,
            versionId: HANDLE.versions[1]!.versionId,
            values: {},
          },
        ]),
      );
      expect(screen.queryByRole("textbox", { name: "Complaint" })).toBeNull();
    });

    it("clears a rejected file selection when a new version keeps the field name but changes its limits", async () => {
      const fileField = {
        name: "attachments",
        label: "Attachments",
        kind: "file",
        types: ["text/plain"],
        maximumBytes: 4,
        most: 1,
        mustBeGiven: false,
      };
      const offered = {
        entryId: "00000006-0000-4000-8000-000000000c66",
        name: "Archive a note",
        versions: [
          {
            versionId: "00000007-0000-4000-8000-000000000c67",
            number: 2,
            takes: [fileField],
          },
          {
            versionId: "00000007-0000-4000-8000-000000000c68",
            number: 1,
            takes: [{ ...fileField, maximumBytes: 8, most: 2 }],
          },
        ],
      };
      opening(PAGE, {
        routes: {
          [`GET ${OFFERED}`]: [[JSON.stringify({ items: [offered] }), 200]],
        },
      });
      await picking("Archive a note");
      const oldInput = screen.getByLabelText(/Choose files for/);
      const fiveBytes = new File(["12345"], "Demo Sample.txt", {
        type: "text/plain",
      });
      fireEvent.change(oldInput, { target: { files: [fiveBytes] } });
      expect(oldInput).toHaveAttribute("aria-invalid", "true");
      const fileStatus = (words: string) =>
        screen.queryByText(
          (_, element) =>
            element?.firstElementChild?.tagName === "BDI" &&
            element.textContent ===
              `${isolatedName("Demo Sample.txt")} ${words}`,
        );
      expect(fileStatus("is larger than this field allows.")).toBeVisible();
      expect(screen.getByText("0 / at most 1")).toBeVisible();

      await userEvent.click(
        screen.getByRole("button", { name: "Choose another version" }),
      );
      await userEvent.click(screen.getByRole("combobox", { name: "Version" }));
      await userEvent.click(screen.getByRole("option", { name: "Version 1" }));

      const newInput = screen.getByLabelText(/Choose files for/);
      expect(newInput).not.toBe(oldInput);
      expect(newInput).not.toHaveAttribute("aria-invalid", "true");
      expect(fileStatus("is larger than this field allows.")).toBeNull();
      expect(screen.getByText("0 / at most 2")).toBeVisible();
      expect(screen.getByText(/at most 8 bytes/)).toBeVisible();
      fireEvent.change(newInput, { target: { files: [fiveBytes] } });
      expect(fileStatus("is selected and awaits upload.")).toBeVisible();
      expect(newInput).not.toHaveAttribute("aria-invalid", "true");
    });

    it("stays on New run for one who may start a run and not read it, saying it began, its key and number, and that it cannot be read, the keyboard at the panel", async () => {
      const { at } = opening(PAGE, {
        permissions: ["read_membership", "start_run"],
        routes: {
          [`GET ${RUNS}`]: [['{"items":[],"reading":"none"}', 200]],
          [`POST ${RUNS}`]: [BEGUN_UNREADABLE],
        },
      });
      await picking("Pay a refund");

      await userEvent.click(screen.getByRole("button", { name: "Start" }));

      expect(await screen.findByText(STARTED_UNREADABLE)).toBeInTheDocument();
      expect(at()).toBe(NEW_RUN);
      expect(tab("New run")).toHaveAttribute("aria-selected", "true");
      expect(panel("New run")).toHaveFocus();
      expect(
        screen.queryByRole("textbox", { name: "Name of this run" }),
      ).toBeNull();
      expect(
        screen.getByRole("button", { name: /Pay a refund/ }),
      ).toHaveAttribute("aria-pressed", "false");
    });

    it("stays where it was where the server says the run begun cannot be read, whatever the standing says of reading runs", async () => {
      const { at } = opening(PAGE, {
        routes: { [`POST ${RUNS}`]: [BEGUN_UNREADABLE] },
      });
      await picking("Pay a refund");

      await userEvent.click(screen.getByRole("button", { name: "Start" }));

      expect(await screen.findByText(STARTED_UNREADABLE)).toBeInTheDocument();
      expect(at()).toBe(NEW_RUN);
      expect(
        screen.queryByRole("heading", { name: /Claim from Avo/ }),
      ).toBeNull();
    });

    it("opens the run begun where the server says it can be read, whatever the standing says of reading runs", async () => {
      const { at } = opening(PAGE, {
        permissions: ["read_membership", "start_run"],
        routes: { [`POST ${RUNS}`]: [BEGUN] },
      });
      await picking("Pay a refund");

      await userEvent.click(screen.getByRole("button", { name: "Start" }));

      expect(await runOpened(/Claim from Avo/)).toBeInTheDocument();
      expect(at()).toBe(`${PAGE}/${AVO.runId}`);
      expect(screen.queryByText(STARTED_UNREADABLE)).toBeNull();
    });

    it("marks each value the server refused where it stands and nothing else, takes the keyboard to the first, and goes nowhere", async () => {
      const { at } = opening(PAGE, {
        routes: {
          [`POST ${RUNS}`]: [
            doesNotFit([{ path: ["complaint"], reason: "too_long" }]),
          ],
        },
      });
      await picking("Handle a claim");
      const complaint = screen.getByRole("textbox", { name: "Complaint" });
      await userEvent.type(complaint, "Kettle leaks");

      await userEvent.click(screen.getByRole("button", { name: "Start" }));

      await waitFor(() =>
        expect(complaint).toHaveAttribute("aria-invalid", "true"),
      );
      expect(complaint).toHaveAccessibleDescription(
        expect.stringContaining("This is longer than this field takes."),
      );
      expect(complaint).toHaveFocus();
      expect(
        screen.getByRole("textbox", { name: "Name of this run" }),
      ).toHaveAttribute("aria-invalid", "false");
      expect(screen.getByText(VALUES_REFUSED)).toBeInTheDocument();
      expect(screen.queryByText(/more place/)).toBeNull();
      expect(at()).toBe(NEW_RUN);
    });

    it("marks a place the server refused within one of many fields and takes the keyboard there, marking nothing beside it", async () => {
      opening(PAGE, {
        routes: {
          [`GET ${OFFERED}`]: [ORDER_ON_OFFER],
          [`POST ${RUNS}`]: [
            doesNotFit([{ path: ["items", 0, "name"], reason: "unusable" }]),
          ],
        },
      });
      await picking("Take an order");
      const items = screen.getByRole("group", { name: "Items" });
      await userEvent.click(
        within(items).getByRole("button", {
          name: `Add one to ${isolatedName("Items")}`,
        }),
      );
      const name = within(items).getByRole("textbox", { name: "Name" });
      await userEvent.type(name, "Kettle");

      await userEvent.click(screen.getByRole("button", { name: "Start" }));

      await waitFor(() => expect(name).toHaveFocus());
      expect(name).toHaveAttribute("aria-invalid", "true");
      expect(
        within(items).getByRole("textbox", { name: "Count" }),
      ).toHaveAttribute("aria-invalid", "false");
      expect(
        screen.getByRole("group", { name: `${isolatedName("Items")} 1` }),
      ).toHaveAccessibleDescription("");
      expect(items).toHaveAccessibleDescription("1 / at most 3");
    });

    it("says how many more places the server found than it listed, marked nowhere, and says it no longer once anything is changed", async () => {
      opening(PAGE, {
        routes: {
          [`POST ${RUNS}`]: [
            doesNotFit([{ path: ["complaint"], reason: "too_long" }], 3),
          ],
        },
      });
      await picking("Handle a claim");
      const complaint = screen.getByRole("textbox", { name: "Complaint" });
      await userEvent.type(complaint, "Kettle leaks");

      await userEvent.click(screen.getByRole("button", { name: "Start" }));

      expect(
        await screen.findByText("2 more places do not fit and are not marked."),
      ).toBeInTheDocument();
      expect(screen.getByText(VALUES_REFUSED)).toBeInTheDocument();

      await userEvent.type(complaint, "!");

      expect(screen.queryByText(/more places/)).toBeNull();
      expect(screen.queryByText(VALUES_REFUSED)).toBeNull();
      expect(complaint).toHaveAttribute("aria-invalid", "false");
    });

    it("marks nothing the server refused where what was sent has since been changed, saying only that values were refused", async () => {
      let refuse: (reply: readonly [string, number]) => void = () => {};
      opening(PAGE, {
        routes: {
          [`POST ${RUNS}`]: [
            new Promise((settle) => {
              refuse = settle;
            }),
          ],
        },
      });
      await picking("Handle a claim");
      const complaint = screen.getByRole("textbox", { name: "Complaint" });
      await userEvent.type(complaint, "Kettle leaks");
      await userEvent.click(screen.getByRole("button", { name: "Start" }));
      await userEvent.type(complaint, " badly");
      const name = screen.getByRole("textbox", { name: "Name of this run" });
      await userEvent.click(name);

      refuse(
        doesNotFit([{ path: ["complaint"], reason: "too_long" }]) as readonly [
          string,
          number,
        ],
      );

      expect(await screen.findByText(VALUES_REFUSED)).toBeInTheDocument();
      expect(complaint).toHaveAttribute("aria-invalid", "false");
      expect(name).toHaveFocus();
    });

    it.each([
      [
        "busy",
        '{"code":"SERVICE_BUSY"}',
        503,
        "This is busy with another large request; send it again shortly.",
      ],
      [
        "sent too slowly",
        '{"code":"BODY_SENT_TOO_SLOWLY"}',
        400,
        "What was sent arrived too slowly to be read; send it again.",
      ],
    ])(
      "says a start refused as %s where it is refused, keeping the form as it was",
      async (_case, body, status, said) => {
        const { at } = opening(PAGE, {
          routes: { [`POST ${RUNS}`]: [[body, status]] },
        });
        await picking("Pay a refund");

        await userEvent.click(screen.getByRole("button", { name: "Start" }));

        expect(await screen.findByRole("alert")).toHaveTextContent(said);
        expect(at()).toBe(NEW_RUN);
        expect(
          screen.getByRole("textbox", { name: "Name of this run" }),
        ).toHaveValue(`Pay a refund ${OPENED_AT}`);
      },
    );

    it("marks a day the browser holds and cannot read as not written as it is taken once the keyboard leaves it, and asks nothing", async () => {
      const { sent } = opening(PAGE, {
        routes: {
          [`GET ${OFFERED}`]: [ORDER_ON_OFFER],
          [`POST ${RUNS}`]: [BEGUN],
        },
      });
      await picking("Take an order");
      const due = screen.getByLabelText<HTMLInputElement>("Due");
      vi.spyOn(due.validity, "badInput", "get").mockReturnValue(true);

      fireEvent.blur(due);
      await userEvent.click(screen.getByRole("button", { name: "Start" }));

      expect(due).toHaveAttribute("aria-invalid", "true");
      expect(due).toHaveAccessibleDescription(
        "This is not written as this field takes it.",
      );
      expect(due).toHaveFocus();
      expect(startsAsked(sent)).toEqual([]);
    });

    it("says a name the server refused on the name, and nowhere else, taking the keyboard there", async () => {
      opening(PAGE, {
        routes: {
          [`POST ${RUNS}`]: [['{"code":"RUN_NAME_UNUSABLE"}', 400]],
        },
      });
      await picking("Pay a refund");

      await userEvent.click(screen.getByRole("button", { name: "Start" }));

      const name = screen.getByRole("textbox", { name: "Name of this run" });
      await waitFor(() =>
        expect(name).toHaveAccessibleDescription(NAME_REFUSED),
      );
      expect(screen.getAllByText(NAME_REFUSED)).toHaveLength(1);
      expect(name).toHaveFocus();
    });

    it("keeps a name refusal through changes to what is filled, then clears it when the name changes", async () => {
      opening(PAGE, {
        routes: {
          [`POST ${RUNS}`]: [['{"code":"RUN_NAME_UNUSABLE"}', 400]],
        },
      });
      await picking("Handle a claim");
      const complaint = screen.getByRole("textbox", { name: "Complaint" });
      const name = screen.getByRole("textbox", { name: "Name of this run" });
      await userEvent.type(complaint, "Kettle");
      await userEvent.click(screen.getByRole("button", { name: "Start" }));
      await waitFor(() =>
        expect(name).toHaveAccessibleDescription(NAME_REFUSED),
      );

      await userEvent.type(complaint, " leaks");
      expect(name).toHaveValue(`Handle a claim ${OPENED_AT}`);
      expect(name).toHaveAccessibleDescription(NAME_REFUSED);
      await userEvent.clear(name);
      await userEvent.type(name, "Mine");
      expect(name).toHaveValue("Mine");
      expect(name).toHaveAccessibleDescription("");
      expect(name).toHaveAttribute("aria-invalid", "false");
    });

    it("says nothing of a refusal on another card picked while the start was out, sending nothing more", async () => {
      let refuse: (reply: readonly [string, number]) => void = () => {};
      const { sent } = opening(PAGE, {
        routes: {
          [`POST ${RUNS}`]: [
            new Promise((settle) => {
              refuse = settle;
            }),
          ],
        },
      });
      await picking("Pay a refund");
      await userEvent.click(screen.getByRole("button", { name: "Start" }));
      await picking("Handle a claim");
      const name = screen.getByRole("textbox", { name: "Name of this run" });

      refuse(['{"code":"RUN_NAME_UNUSABLE"}', 400]);
      await act(async () => {});

      expect(name).toHaveAttribute("aria-invalid", "false");
      expect(name).toHaveAccessibleDescription("");
      expect(screen.queryByRole("alert")).toBeNull();
      expect(screen.getByRole("button", { name: "Start" })).not.toHaveAttribute(
        "aria-disabled",
      );
      expect(startsAsked(sent)).toHaveLength(1);
    });

    it("reads what may be started again where the version is no longer offered, says so, and takes the keyboard back to the panel once the workflow is gone", async () => {
      const { sent } = opening(PAGE, {
        routes: {
          [`POST ${RUNS}`]: [['{"code":"WORKFLOW_NOT_OFFERED"}', 409]],
          [`GET ${OFFERED}`]: [
            ON_OFFER,
            [JSON.stringify({ items: [REFUND] }), 200],
            [JSON.stringify({ items: [REFUND] }), 200],
            [JSON.stringify({ items: [REFUND] }), 200],
          ],
        },
      });
      await picking("Handle a claim");
      await userEvent.type(
        screen.getByRole("textbox", { name: "Complaint" }),
        "Kettle leaks",
      );

      await userEvent.click(screen.getByRole("button", { name: "Start" }));

      expect(await screen.findByText(NO_LONGER_OFFERED)).toBeInTheDocument();
      await waitFor(() =>
        expect(
          screen.queryByRole("button", { name: /Handle a claim/ }),
        ).toBeNull(),
      );
      expect(
        requestsTo(sent).filter((each) => each === `GET ${OFFERED}`),
      ).toHaveLength(3);
      expect(requestsTo(sent)).toContain(
        `GET ${OFFERED}?filter=Handle+a+claim`,
      );
      expect(screen.queryByRole("textbox", { name: "Complaint" })).toBeNull();
      expect(panel("New run")).toHaveFocus();
    });

    it("checks the selected entry across name pages, then unfiltered pages after a rename, without losing its draft", async () => {
      const renamed = { ...HANDLE, name: "Claim handling" };
      const { sent } = opening(PAGE, {
        routes: {
          [`POST ${RUNS}`]: [['{"code":"WORKFLOW_NOT_OFFERED"}', 409]],
          [`GET ${OFFERED}`]: [
            ON_OFFER,
            [JSON.stringify({ items: [] }), 200],
            [JSON.stringify({ items: [REFUND], nextCursor: "more" }), 200],
            [JSON.stringify({ items: [renamed] }), 200],
            [JSON.stringify({ items: [renamed, REFUND] }), 200],
          ],
        },
      });
      await picking("Handle a claim");
      const complaint = screen.getByRole("textbox", { name: "Complaint" });
      const name = screen.getByRole("textbox", { name: "Name of this run" });
      await userEvent.type(complaint, "Kettle leaks");
      await userEvent.clear(name);
      await userEvent.type(name, "My claim");

      await userEvent.click(screen.getByRole("button", { name: "Start" }));

      expect(
        await screen.findByRole("heading", {
          name: isolatedName("Claim handling"),
        }),
      ).toBeInTheDocument();
      expect(
        requestsTo(sent).filter((each) => each.startsWith(`GET ${OFFERED}`)),
      ).toEqual([
        `GET ${OFFERED}`,
        `GET ${OFFERED}?filter=Handle+a+claim`,
        `GET ${OFFERED}`,
        `GET ${OFFERED}?cursor=more`,
        `GET ${OFFERED}`,
      ]);
      expect(name).toHaveValue("My claim");
      expect(complaint).toHaveValue("Kettle leaks");
      expect(panel("New run")).not.toHaveFocus();
      expect(startsAsked(sent)).toHaveLength(1);
    });

    it("uses the newest remaining version when the chosen one disappeared after a start refusal", async () => {
      const newestOnly = { ...HANDLE, versions: [HANDLE.versions[0]] };
      const { sent } = opening(PAGE, {
        routes: {
          [`POST ${RUNS}`]: [['{"code":"WORKFLOW_NOT_OFFERED"}', 409]],
          [`GET ${OFFERED}`]: [
            ON_OFFER,
            [JSON.stringify({ items: [newestOnly] }), 200],
            [JSON.stringify({ items: [newestOnly] }), 200],
          ],
        },
      });
      await picking("Handle a claim");
      const name = screen.getByRole("textbox", { name: "Name of this run" });
      await userEvent.clear(name);
      await userEvent.type(name, "My claim");
      await userEvent.click(
        screen.getByRole("button", { name: "Choose another version" }),
      );
      await userEvent.click(screen.getByRole("combobox", { name: "Version" }));
      await userEvent.click(screen.getByRole("option", { name: "Version 1" }));

      await userEvent.click(screen.getByRole("button", { name: "Start" }));

      expect(
        await screen.findByRole("textbox", { name: "Complaint" }),
      ).toBeInTheDocument();
      expect(name).toHaveValue("My claim");
      expect(screen.queryByRole("option", { name: "Version 1" })).toBeNull();
      expect(startsAsked(sent)).toEqual([
        {
          name: "My claim",
          versionId: HANDLE.versions[1]!.versionId,
          values: {},
        },
      ]);
    });

    it("finishes checking a refused start while the cards change filter and keeps the selected draft", async () => {
      let finishCheck: (reply: readonly [string, number]) => void = () => {};
      const newestOnly = { ...HANDLE, versions: [HANDLE.versions[0]] };
      const { sent } = opening(PAGE, {
        routes: {
          [`POST ${RUNS}`]: [['{"code":"WORKFLOW_NOT_OFFERED"}', 409]],
          [`GET ${OFFERED}`]: [
            ON_OFFER,
            new Promise((settle) => {
              finishCheck = settle;
            }),
            [JSON.stringify({ items: [REFUND] }), 200],
            [JSON.stringify({ items: [REFUND] }), 200],
          ],
        },
      });
      await picking("Handle a claim");
      const name = screen.getByRole("textbox", { name: "Name of this run" });
      await userEvent.clear(name);
      await userEvent.type(name, "My claim");
      await userEvent.click(
        screen.getByRole("button", { name: "Choose another version" }),
      );
      await userEvent.click(screen.getByRole("combobox", { name: "Version" }));
      await userEvent.click(screen.getByRole("option", { name: "Version 1" }));
      await userEvent.click(screen.getByRole("button", { name: "Start" }));
      await waitFor(() =>
        expect(requestsTo(sent)).toContain(
          `GET ${OFFERED}?filter=Handle+a+claim`,
        ),
      );

      fireEvent.change(
        screen.getByRole("searchbox", { name: "Part of a name" }),
        { target: { value: "refund" } },
      );
      await waitFor(() =>
        expect(requestsTo(sent)).toContain(`GET ${OFFERED}?filter=refund`),
      );
      finishCheck([JSON.stringify({ items: [newestOnly] }), 200]);

      await waitFor(() =>
        expect(
          screen.getByRole("combobox", { name: "Version" }),
        ).toHaveTextContent("Version 2"),
      );
      expect(
        screen.getByRole("textbox", { name: "Complaint" }),
      ).toBeInTheDocument();
      expect(
        screen.getByRole("heading", { name: isolatedName("Handle a claim") }),
      ).toBeInTheDocument();
      expect(name).toHaveValue("My claim");
      expect(startsAsked(sent)).toHaveLength(1);
      expect(
        requestsTo(sent).filter(
          (each) => each === `GET ${OFFERED}?filter=Handle+a+claim`,
        ),
      ).toHaveLength(1);
    });

    it("keeps the selected form on an ordinary verification failure until Try loading again finds it", async () => {
      const { sent } = opening(PAGE, {
        routes: {
          [`POST ${RUNS}`]: [['{"code":"WORKFLOW_NOT_OFFERED"}', 409]],
          [`GET ${OFFERED}`]: [
            ON_OFFER,
            ['{"code":"SERVICE_BUSY"}', 503],
            ON_OFFER,
            ON_OFFER,
          ],
        },
      });
      await picking("Pay a refund");
      const name = screen.getByRole("textbox", { name: "Name of this run" });
      await userEvent.clear(name);
      await userEvent.type(name, "My refund");

      await userEvent.click(screen.getByRole("button", { name: "Start" }));

      expect(
        await screen.findByRole("button", { name: "Try loading again" }),
      ).toBeInTheDocument();
      expect(name).toHaveValue("My refund");
      expect(
        screen.getByRole("heading", { name: isolatedName("Pay a refund") }),
      ).toBeInTheDocument();
      expect(
        requestsTo(sent).filter((each) => each.startsWith(`GET ${OFFERED}`)),
      ).toHaveLength(2);

      await userEvent.type(name, " now");
      await act(async () => {});
      expect(
        requestsTo(sent).filter((each) => each.startsWith(`GET ${OFFERED}`)),
      ).toHaveLength(2);

      await userEvent.click(
        screen.getByRole("button", { name: "Try loading again" }),
      );

      await waitFor(() =>
        expect(
          requestsTo(sent).filter((each) => each.startsWith(`GET ${OFFERED}`)),
        ).toHaveLength(4),
      );
      expect(name).toHaveValue("My refund now");
      expect(
        screen.queryByRole("button", { name: "Try loading again" }),
      ).toBeNull();
    });

    it("keeps failed start verification available for an explicit retry after the cards change filter", async () => {
      const { sent, readAgain } = opening(PAGE, {
        routes: {
          [`POST ${RUNS}`]: [['{"code":"WORKFLOW_NOT_OFFERED"}', 409]],
          [`GET ${OFFERED}`]: [
            ON_OFFER,
            ['{"code":"SERVICE_BUSY"}', 503],
            [JSON.stringify({ items: [HANDLE] }), 200],
            ON_OFFER,
            ON_OFFER,
          ],
        },
      });
      await picking("Pay a refund");
      const name = screen.getByRole("textbox", { name: "Name of this run" });
      await userEvent.clear(name);
      await userEvent.type(name, "My refund");
      await userEvent.click(screen.getByRole("button", { name: "Start" }));
      expect(
        await screen.findByRole("button", { name: "Try loading again" }),
      ).toBeInTheDocument();

      fireEvent.change(
        screen.getByRole("searchbox", { name: "Part of a name" }),
        { target: { value: "claim" } },
      );
      await waitFor(() =>
        expect(requestsTo(sent)).toContain(`GET ${OFFERED}?filter=claim`),
      );
      expect(
        screen.getByRole("button", { name: "Try loading again" }),
      ).toBeInTheDocument();
      await act(async () => {});
      expect(
        requestsTo(sent).filter((each) => each.startsWith(`GET ${OFFERED}`)),
      ).toHaveLength(3);

      await userEvent.click(
        screen.getByRole("button", { name: "Try loading again" }),
      );
      await waitFor(() =>
        expect(
          requestsTo(sent).filter((each) => each.startsWith(`GET ${OFFERED}`)),
        ).toHaveLength(5),
      );
      expect(name).toHaveValue("My refund");
      expect(startsAsked(sent)).toHaveLength(1);
      expect(readAgain).not.toHaveBeenCalled();
    });

    it("closes a failed card page when start verification refuses authority and cannot retry its old cursor", async () => {
      const { sent, readAgain } = opening(PAGE, {
        routes: {
          [`POST ${RUNS}`]: [['{"code":"WORKFLOW_NOT_OFFERED"}', 409]],
          [`GET ${OFFERED}`]: [
            [JSON.stringify({ items: [HANDLE], nextCursor: "next" }), 200],
            ['{"code":"SERVICE_BUSY"}', 503],
            ['{"code":"ACT_NOT_PERMITTED"}', 403],
          ],
        },
      });
      await picking("Handle a claim");
      await userEvent.type(
        screen.getByRole("textbox", { name: "Complaint" }),
        "Kettle leaks",
      );
      await userEvent.click(
        screen.getByRole("button", { name: "Load more workflows" }),
      );
      const retry = await screen.findByRole("button", {
        name: "Try loading again",
      });
      await userEvent.click(screen.getByRole("button", { name: "Start" }));

      await waitFor(() =>
        expect(requestsTo(sent)).toContain(
          `GET ${OFFERED}?filter=Handle+a+claim`,
        ),
      );
      await waitFor(() =>
        expect(
          screen.queryByRole("button", { name: "Try loading again" }),
        ).toBeNull(),
      );
      await waitFor(() => expect(readAgain).toHaveBeenCalledTimes(1));
      expect(
        screen.queryByRole("button", { name: /Handle a claim/ }),
      ).toBeNull();
      expect(
        screen.queryByRole("textbox", { name: "Name of this run" }),
      ).toBeNull();
      expect(screen.queryByText("Still reading…")).toBeNull();
      fireEvent.click(retry);
      await act(async () => {});
      expect(
        requestsTo(sent).filter((each) => each.startsWith(`GET ${OFFERED}`)),
      ).toHaveLength(3);
      expect(startsAsked(sent)).toHaveLength(1);
      expect(readAgain).toHaveBeenCalledTimes(1);
    });

    it("abandons a pending card page after start verification refuses authority", async () => {
      let finishNext: (reply: readonly [string, number]) => void = () => {};
      const { sent, readAgain } = opening(PAGE, {
        routes: {
          [`POST ${RUNS}`]: [['{"code":"WORKFLOW_NOT_OFFERED"}', 409]],
          [`GET ${OFFERED}`]: [
            [JSON.stringify({ items: [HANDLE], nextCursor: "next" }), 200],
            new Promise((settle) => {
              finishNext = settle;
            }),
            ['{"code":"ACT_NOT_PERMITTED"}', 403],
          ],
        },
      });
      await picking("Handle a claim");
      await userEvent.type(
        screen.getByRole("textbox", { name: "Complaint" }),
        "Kettle leaks",
      );
      await userEvent.click(
        screen.getByRole("button", { name: "Load more workflows" }),
      );
      await waitFor(() =>
        expect(requestsTo(sent)).toContain(`GET ${OFFERED}?cursor=next`),
      );
      const nextSignal = sent.mock.calls.find(([input]) =>
        String(input).includes("cursor=next"),
      )?.[1]?.signal;
      expect(nextSignal?.aborted).toBe(false);
      await userEvent.click(screen.getByRole("button", { name: "Start" }));

      await waitFor(() => expect(nextSignal?.aborted).toBe(true));
      await waitFor(() => expect(readAgain).toHaveBeenCalledTimes(1));
      finishNext([JSON.stringify({ items: [REFUND] }), 200]);
      await act(async () => {});
      expect(screen.queryByRole("button", { name: /Pay a refund/ })).toBeNull();
      expect(
        screen.queryByRole("button", { name: /Handle a claim/ }),
      ).toBeNull();
      expect(
        screen.queryByRole("button", { name: "Try loading again" }),
      ).toBeNull();
      expect(screen.queryByText("Still reading…")).toBeNull();
      expect(
        requestsTo(sent).filter((each) => each.startsWith(`GET ${OFFERED}`)),
      ).toHaveLength(3);
      expect(startsAsked(sent)).toHaveLength(1);
      expect(readAgain).toHaveBeenCalledTimes(1);
    });

    it("says no longer that a version is not offered once another card is picked", async () => {
      opening(PAGE, {
        routes: {
          [`POST ${RUNS}`]: [['{"code":"WORKFLOW_NOT_OFFERED"}', 409]],
          [`GET ${OFFERED}`]: [ON_OFFER, ON_OFFER],
        },
      });
      await picking("Pay a refund");
      await userEvent.click(screen.getByRole("button", { name: "Start" }));
      expect(await screen.findByText(NO_LONGER_OFFERED)).toBeVisible();

      await picking("Handle a claim");

      expect(screen.queryByText(NO_LONGER_OFFERED)).toBeNull();
    });

    it("reads the standing again where starting is refused for the act itself, saying so and keeping the form where it is", async () => {
      const { readAgain, at } = opening(PAGE, {
        routes: {
          [`POST ${RUNS}`]: [['{"code":"ACT_NOT_PERMITTED"}', 403]],
        },
      });
      await picking("Pay a refund");
      const name = screen.getByRole("textbox", { name: "Name of this run" });

      await userEvent.click(screen.getByRole("button", { name: "Start" }));

      await waitFor(() => expect(readAgain).toHaveBeenCalledTimes(1));
      expect(await screen.findByRole("alert")).toHaveTextContent(ACT_REFUSED);
      expect(at()).toBe(NEW_RUN);
      expect(name).toBeInTheDocument();
      expect(name).toHaveValue(`Pay a refund ${OPENED_AT}`);
    });

    it("puts the form away when it is abandoned, starting nothing and taking the keyboard back to the panel", async () => {
      const { sent } = opening(PAGE, {
        routes: { [`POST ${RUNS}`]: [BEGUN] },
      });
      await picking("Pay a refund");

      await userEvent.click(screen.getByRole("button", { name: "Cancel" }));

      expect(
        screen.queryByRole("textbox", { name: "Name of this run" }),
      ).toBeNull();
      expect(panel("New run")).toHaveFocus();
      expect(startsAsked(sent)).toEqual([]);
    });

    it("says so where nothing can be started", async () => {
      opening(PAGE, {
        routes: { [`GET ${OFFERED}`]: [['{"items":[]}', 200]] },
      });

      expect(
        await screen.findByText(
          "No workflow of this group can be started: none has a version in service that is not stopped.",
        ),
      ).toBeInTheDocument();
      expect(
        within(panel("New run")).queryAllByText(isolatedName("Handle a claim")),
      ).toHaveLength(0);
    });
  });

  describe("on History", () => {
    it("draws what is running as a table sorted newest first by when each started", async () => {
      const { sent } = opening(PAGE);

      await inHistory();

      expect(await rows()).toEqual([
        [
          "SUPPORT-3",
          "Claim from Avonel",
          `${isolatedName("Handle a claim")}, version 1`,
          "02:00 PM today",
          "Cat Example",
          "Stopped",
        ],
        [
          "SUPPORT-2",
          "Refund for Glinn",
          `${isolatedName("Pay a refund")}, version 2`,
          "11:00 AM yesterday",
          "000c62",
          `Running, on ${isolatedName("summarise")}`,
        ],
        [
          "SUPPORT-1",
          "Claim from Avo",
          `${isolatedName("Handle a claim")}, version 1`,
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
          element.textContent === `Running, on ${isolatedName("summarise")}`,
      );
      expect(running).toHaveLength(1);
      expect(running[0]).toBeVisible();
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
                reading: "own",
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
        "Not something this page can say yet.",
      ],
    ])(
      "says where a run is %s without naming a step, and every other run as it was",
      async (_case, run, said) => {
        localStorage.setItem(KEPT_AS, "history");
        opening(PAGE, {
          routes: {
            [`GET ${RUNS}`]: [
              [JSON.stringify({ items: [run, AVO], reading: "own" }), 200],
            ],
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
          [`GET ${RUNS}`]: [
            LISTED,
            [JSON.stringify({ items: [AVO], reading: "own" }), 200],
          ],
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
      "says no row matches where the server %s",
      async (_case, reading, said, unsaid) => {
        opening(`${HISTORY}&filter=zz`, {
          routes: {
            [`GET ${RUNS}`]: [[`{"items":[],"reading":"${reading}"}`, 200]],
          },
        });

        expect(await screen.findByText(said)).toBeInTheDocument();
        expect(screen.queryByText(unsaid)).toBeNull();
        expect(screen.getByRole("searchbox")).toHaveValue("zz");
      },
    );

    /** Every row opened as an operator, so what is said follows the server's reading and not the standing. */
    it.each([
      [
        "read none",
        "none",
        "Reading a run is a permission this group has not given you, so no run is listed, including the ones you started.",
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
    ])(
      "says why no run is listed where the server %s",
      async (_case, reading, said) => {
        localStorage.setItem(KEPT_AS, "history");
        opening(PAGE, {
          routes: {
            [`GET ${RUNS}`]: [[`{"items":[],"reading":"${reading}"}`, 200]],
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
        name: "Where it is",
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
