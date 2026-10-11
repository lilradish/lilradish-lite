import { buttonClasses } from "@mui/material/Button";
import {
  act,
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
  OFFERED,
  HANDLE,
  REFUND,
  OPENED_AT,
  ON_OFFER,
  isolatedName,
  opening,
  tab,
  panel,
  startsAsked,
  picking,
  naming,
  inputting,
  renaming,
  cancelling,
  loadingMore,
  filtering,
} from "../../testutil/workPage";

describe("WorkPage", () => {
  describe("on New run", () => {
    it("loads one next page in server order after a draft is cancelled, then filters a different name without starting it", async () => {
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
      const complaint = screen.getByRole("textbox", {
        name: "Complaint · complaint",
      });
      await userEvent.click(complaint);
      await userEvent.paste("Kettle leaks");
      expect(complaint).toHaveValue("Kettle leaks");
      const name = await naming();
      await renaming(name, "My claim");

      await cancelling();
      expect(screen.queryByRole("dialog")).toBeNull();
      expect(complaint).not.toBeInTheDocument();

      await loadingMore();

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

      await filtering("refund");

      expect(requestsTo(sent)).toContain(`GET ${OFFERED}?filter=refund`);
      expect(
        screen.queryByRole("button", { name: /Handle a claim/ }),
      ).toBeNull();
      expect(complaint).not.toBeInTheDocument();
      expect(name).not.toBeInTheDocument();
      expect(startsAsked(sent)).toEqual([]);
      expect(
        requestsTo(sent).filter((each) => each.startsWith(`GET ${OFFERED}`)),
      ).toHaveLength(3);
    });

    it("keeps accepted cards through a failed next page after cancelling a draft, then retries that cursor alone", async () => {
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
      const complaint = screen.getByRole("textbox", {
        name: "Complaint · complaint",
      });
      await userEvent.click(complaint);
      await userEvent.paste("Kettle leaks");
      expect(complaint).toHaveValue("Kettle leaks");

      await userEvent.click(screen.getByRole("button", { name: "Cancel" }));
      expect(screen.queryByRole("dialog")).toBeNull();
      expect(complaint).not.toBeInTheDocument();

      await userEvent.click(
        screen.getByRole("button", { name: "Load more workflows" }),
      );

      expect(await screen.findByRole("alert")).toBeInTheDocument();
      expect(
        screen.getByRole("button", { name: /Handle a claim/ }),
      ).toBeInTheDocument();
      expect(complaint).not.toBeInTheDocument();
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
      expect(complaint).not.toBeInTheDocument();
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
      let refuseNext!: (reply: readonly [string, number]) => void;
      const { sent, readAgain } = opening(PAGE, {
        routes: {
          [`GET ${OFFERED}`]: [
            [JSON.stringify({ items: [HANDLE], nextCursor: "next" }), 200],
            new Promise<readonly [string, number]>((resolve) => {
              refuseNext = resolve;
            }),
          ],
        },
      });
      await screen.findByRole("button", { name: /Handle a claim/ });

      await userEvent.click(
        screen.getByRole("button", { name: "Load more workflows" }),
      );

      await picking("Handle a claim", false);
      const name = await naming();
      refuseNext(['{"code":"ACT_NOT_PERMITTED"}', 403]);

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

      vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
      fireEvent.compositionStart(filter);
      fireEvent.change(filter, { target: { value: "none" } });
      expect(filter).toHaveValue("none");
      expect(
        requestsTo(sent).filter((each) => each.startsWith(`GET ${OFFERED}`)),
      ).toHaveLength(1);
      fireEvent.compositionEnd(filter);
      await act(() => vi.advanceTimersByTimeAsync(350));

      expect(
        screen.getByText("No workflow on offer here has a name holding that."),
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

      await act(async () => {
        fireEvent.click(tab("History"));
      });
      await act(async () => {
        await vi.advanceTimersByTimeAsync(350);
      });

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
      await screen.findByRole("button", { name: /Handle a claim/ });
      await userEvent.click(
        screen.getByRole("button", { name: "Load more workflows" }),
      );
      await waitFor(() =>
        expect(
          requestsTo(sent).filter((each) => each.startsWith(`GET ${OFFERED}`)),
        ).toHaveLength(2),
      );

      await picking("Handle a claim");
      const complaint = screen.getByRole("textbox", {
        name: "Complaint \u00b7 complaint",
      });
      await userEvent.click(complaint);
      await userEvent.paste("Kettle leaks");
      expect(complaint).toHaveValue("Kettle leaks");

      act(() => changeReader("another-reader"));
      finishOld([JSON.stringify({ items: [HANDLE] }), 200]);

      expect(
        await screen.findByRole("button", { name: /Pay a refund/ }),
      ).toBeInTheDocument();
      expect(
        screen.queryByRole("button", { name: /Handle a claim/ }),
      ).toBeNull();
      expect(
        screen.queryByRole("textbox", { name: "Complaint · complaint" }),
      ).toBeNull();
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

    it("opens Version and name in a dialog, then Inputs with the newest version, preserving the name on Back", async () => {
      const { sent } = opening(PAGE);
      const form = await picking("Handle a claim", false);
      const name = await naming();
      expect(form).toHaveAccessibleName(
        `Start a run of ${isolatedName("Handle a claim")}`,
      );
      expect(name).toHaveFocus();
      expect(name).toHaveValue(`Handle a claim ${OPENED_AT}`);
      expect(
        within(form).getByRole("heading", { name: "Version and name" }),
      ).toBeVisible();
      expect(
        screen.queryByRole("textbox", { name: "Complaint · complaint" }),
      ).toBeNull();
      expect(screen.queryByRole("combobox", { name: "Version" })).toBeNull();
      expect(
        screen.getByRole("button", { name: "Choose another version" }),
      ).toBeVisible();
      await inputting();
      const complaint = within(form).getByRole("textbox", {
        name: "Complaint · complaint",
      });
      expect(complaint).toHaveFocus();
      expect(
        screen.queryByRole("textbox", { name: "Name of this run" }),
      ).toBeNull();
      expect(
        within(form).getByRole("button", { name: "Start run" }),
      ).toHaveClass(buttonClasses.contained);
      await userEvent.click(complaint);
      await userEvent.paste("Kettle leaks");
      expect(complaint).toHaveValue("Kettle leaks");
      expect(await naming()).toBe(name);
      expect(name).toHaveValue(`Handle a claim ${OPENED_AT}`);
      await inputting();
      expect(
        screen.getByRole("textbox", { name: "Complaint · complaint" }),
      ).toBe(complaint);
      expect(complaint).toHaveValue("Kettle leaks");
      expect(startsAsked(sent)).toEqual([]);
    });

    it("starts each dialog at its name when the prior draft is cancelled or another workflow is picked", async () => {
      const { sent } = opening(PAGE);
      await picking("Handle a claim", false);
      const firstName = await naming();
      await inputting();
      const complaint = screen.getByRole("textbox", {
        name: "Complaint \u00b7 complaint",
      });
      await userEvent.click(complaint);
      await userEvent.paste("Kettle leaks");
      expect(complaint).toHaveValue("Kettle leaks");
      await picking("Handle a claim", false);
      const reopened = await naming();
      expect(reopened).toHaveFocus();
      expect(reopened).not.toBe(firstName);
      await inputting();
      expect(
        screen.getByRole("textbox", { name: "Complaint · complaint" }),
      ).toHaveValue("");
      await picking("Pay a refund");
      const nextName = await naming();
      expect(nextName).toHaveFocus();
      expect(nextName).not.toBe(reopened);
      expect(startsAsked(sent)).toEqual([]);
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
      expect(screen.getAllByRole("textbox")).toEqual([await naming()]);
    });

    it("names the run afresh once another card is picked, forgetting a name typed for the one before", async () => {
      opening(PAGE);
      await picking("Pay a refund");
      const typed = await naming();
      await userEvent.clear(typed);
      await userEvent.type(typed, "Mine");

      await picking("Handle a claim", false);

      expect(await naming()).toHaveValue(`Handle a claim ${OPENED_AT}`);
      await picking("Pay a refund");
      expect(await naming()).toHaveValue(`Pay a refund ${OPENED_AT}`);
    });

    it("suggests the workflow and the form's own drawing time, unaffected by filling, while allowing a chosen name", async () => {
      opening(PAGE);
      vi.setSystemTime(new Date("2026-09-18T13:07:00Z"));
      await picking("Handle a claim", false);
      const name = await naming();
      await inputting();
      const complaint = screen.getByRole("textbox", {
        name: "Complaint · complaint",
      });

      expect(name).toHaveValue("Handle a claim Sep 18, 03:07 PM");
      await userEvent.click(complaint);
      await userEvent.paste("Kettle leaks\nSince Monday");
      expect(complaint).toHaveValue("Kettle leaks\nSince Monday");
      vi.setSystemTime(new Date("2026-09-18T13:19:00Z"));
      await userEvent.click(complaint);
      await userEvent.paste(", badly");
      expect(complaint).toHaveValue("Kettle leaks\nSince Monday, badly");
      expect(name).toHaveValue("Handle a claim Sep 18, 03:07 PM");

      await naming();
      await userEvent.clear(name);
      expect(name).toHaveValue("");
      await userEvent.click(name);
      await userEvent.paste("Mine");
      expect(name).toHaveValue("Mine");
      await inputting();
      await userEvent.click(complaint);
      await userEvent.paste(" again");
      expect(complaint).toHaveValue("Kettle leaks\nSince Monday, badly again");
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
      const name = await naming();
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
  });
});
