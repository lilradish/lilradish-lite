import {
  act,
  fireEvent,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import {
  AVO,
  AVON,
  BEGUN,
  GLINN,
  HANDLE,
  HISTORY,
  KEPT_AS,
  LISTED,
  OFFERED,
  OPENED_AT,
  PAGE,
  RUNS,
  RUN_DRAWING_KEPT_AS,
  SUMMARISE,
  isolatedName,
  opening,
  picking,
  rows,
  runOpened,
  runsAsked,
  startsAsked,
  tab,
  table,
} from "../../testutil/workPage";

describe("WorkPage across pages", () => {
  describe("its tabs", () => {
    it("draws the tab this device kept once an address naming no tab is opened again over another it named", async () => {
      localStorage.setItem(KEPT_AS, "newRun");
      const { router } = opening(HISTORY);
      await rows();

      await act(() => router.navigate(PAGE));

      expect(tab("New run")).toHaveAttribute("aria-selected", "true");
      expect(tab("History")).toHaveAttribute("aria-selected", "false");
      expect(
        await screen.findByRole("button", { name: /Handle a claim/ }),
      ).toBeInTheDocument();
      expect(localStorage.getItem(KEPT_AS)).toBe("newRun");
    });
  });

  describe("on New run", () => {
    it("starts the run of the newest version with its name and every value, and opens its own page, the list's address kept and the keyboard at its name", async () => {
      const { sent, at } = opening(`${PAGE}?filter=claim`, {
        routes: { [`POST ${RUNS}`]: [BEGUN] },
      });
      await userEvent.click(tab("New run"));
      await picking("Handle a claim");
      await userEvent.type(
        screen.getByRole("textbox", { name: "Complaint" }),
        "Kettle leaks",
      );

      await userEvent.click(screen.getByRole("button", { name: "Start" }));

      const opened = await runOpened(/Claim from Avo/);
      await waitFor(() => expect(opened).toHaveFocus());
      expect(at()).toBe(`${PAGE}/${AVO.runId}?filter=claim`);
      expect(startsAsked(sent)).toEqual([
        {
          name: `Handle a claim ${OPENED_AT}`,
          versionId: HANDLE.versions[0]!.versionId,
          values: { complaint: "Kettle leaks" },
        },
      ]);
      expect(screen.queryByRole("tablist")).toBeNull();
      expect(
        screen.queryByRole("table", { name: "What is running" }),
      ).toBeNull();
    });

    it("sends a chosen file after the start document with its original name only in the marker", async () => {
      const withFile = {
        ...HANDLE,
        versions: [
          {
            ...HANDLE.versions[0],
            takes: [
              {
                name: "attachment",
                label: "Attachment",
                kind: "file",
                types: ["text/plain"],
                maximumBytes: 20,
                mustBeGiven: true,
              },
            ],
          },
        ],
      };
      const { sent } = opening(PAGE, {
        routes: {
          [`GET ${OFFERED}`]: [[JSON.stringify({ items: [withFile] }), 200]],
          [`POST ${RUNS}`]: [BEGUN],
        },
      });
      await picking("Handle a claim");
      const chosen = new File(["sample"], "Demo Sample.txt", {
        type: "text/plain",
      });
      fireEvent.change(screen.getByLabelText(/Attachment/), {
        target: { files: [chosen] },
      });

      await userEvent.click(screen.getByRole("button", { name: "Start" }));
      await screen.findByRole("heading", { level: 1, name: /Claim from Avo/ });

      const posted = sent.mock.calls.find(
        ([, init]) => init?.method === "POST",
      )?.[1];
      expect(posted?.body).toBeInstanceOf(FormData);
      const parts = [...(posted?.body as FormData).entries()];
      expect(parts.map(([name]) => name)).toEqual(["document", "upload0"]);
      expect(JSON.parse(String(parts[0]?.[1]))).toEqual({
        name: `Handle a claim ${OPENED_AT}`,
        versionId: HANDLE.versions[0]!.versionId,
        values: { attachment: { upload: 0, name: "Demo Sample.txt" } },
      });
      expect(parts[1]?.[1]).toBeInstanceOf(File);
      expect((parts[1]?.[1] as File).name).toBe("file");
      expect(posted?.headers).not.toHaveProperty("Content-Type");
    });

    it("takes the keyboard to the run's name as its page opens, begun here or opened again from History", async () => {
      opening(PAGE, { routes: { [`POST ${RUNS}`]: [BEGUN] } });
      await picking("Pay a refund");
      await userEvent.click(screen.getByRole("button", { name: "Start" }));

      const begun = await runOpened(/Claim from Avo/);
      await waitFor(() => expect(begun).toHaveFocus());

      await userEvent.click(screen.getByRole("link", { name: "History" }));
      await userEvent.click(
        await within(table()).findByRole("link", { name: "SUPPORT-1" }),
      );

      const reopened = await runOpened(/Claim from Avo/);
      await waitFor(() => expect(reopened).toHaveFocus());
      expect(document.activeElement).not.toBe(document.body);
    });
  });

  describe("on History", () => {
    it("reads a different next page for the same filter and order, and stops at its last cursor", async () => {
      const { sent, at } = opening(`${HISTORY}&filter=claim&sort=name`, {
        routes: {
          [`GET ${RUNS}`]: [
            [
              JSON.stringify({
                items: [AVON],
                nextCursor: "next-claim",
                reading: "own",
              }),
              200,
            ],
            [JSON.stringify({ items: [AVO], reading: "own" }), 200],
          ],
        },
      });
      const first = await within(table()).findByRole("link", {
        name: "SUPPORT-3",
      });
      expect(first).toBeInTheDocument();
      expect(
        within(table()).queryByRole("link", { name: "SUPPORT-1" }),
      ).toBeNull();

      await userEvent.click(screen.getByRole("button", { name: "Next page" }));
      expect(
        await within(table()).findByRole("link", { name: "SUPPORT-1" }),
      ).toBeInTheDocument();
      expect(
        within(table()).queryByRole("link", { name: "SUPPORT-3" }),
      ).toBeNull();
      expect(at()).toBe(`${HISTORY}&filter=claim&sort=name`);
      expect(runsAsked(sent)).toEqual([
        `GET ${RUNS}?sort=name&filter=claim`,
        `GET ${RUNS}?sort=name&filter=claim&cursor=next-claim`,
      ]);

      const next = screen.getByRole("button", { name: "Next page" });
      expect(next).toHaveAttribute("aria-disabled", "true");
      next.focus();
      await userEvent.keyboard("{Enter}");
      expect(runsAsked(sent)).toHaveLength(2);
    });

    it.each([
      ["Run", "run", [AVO, GLINN, AVON], [AVON, GLINN, AVO]],
      ["Name", "name", [AVO, AVON, GLINN], [GLINN, AVON, AVO]],
      ["Workflow", "workflow", [AVON, AVO, GLINN], [GLINN, AVO, AVON]],
    ] as const)(
      "asks for %s in both directions and draws the server's order with one active heading",
      async (label, column, ascending, descending) => {
        const { sent, at } = opening(HISTORY, {
          routes: {
            [`GET ${RUNS}`]: [
              LISTED,
              [JSON.stringify({ items: ascending, reading: "own" }), 200],
              [JSON.stringify({ items: descending, reading: "own" }), 200],
            ],
          },
        });
        await rows();
        const heading = within(table()).getByRole("button", { name: label });
        const listed = () =>
          within(table())
            .getAllByRole("link")
            .map((link) => link.textContent);
        const numbers = (runs: readonly { readonly number: number }[]) =>
          runs.map((run) => `SUPPORT-${run.number}`);

        await userEvent.click(heading);
        await waitFor(() => expect(listed()).toEqual(numbers(ascending)));
        expect(at()).toBe(`${HISTORY}&sort=${column}`);
        expect(heading.closest("th")).toHaveAttribute("aria-sort", "ascending");
        expect(
          within(table())
            .getAllByRole("columnheader")
            .filter((header) => header.hasAttribute("aria-sort")),
        ).toEqual([heading.closest("th")]);

        await userEvent.click(heading);
        await waitFor(() => expect(listed()).toEqual(numbers(descending)));
        expect(at()).toBe(`${HISTORY}&sort=-${column}`);
        expect(heading.closest("th")).toHaveAttribute(
          "aria-sort",
          "descending",
        );
        expect(runsAsked(sent)).toEqual([
          `GET ${RUNS}?sort=-started`,
          `GET ${RUNS}?sort=${column}`,
          `GET ${RUNS}?sort=-${column}`,
        ]);
      },
    );

    it("opens a row's run as its own page in place of Work's tabs and table, its address keeping the list's filter and order", async () => {
      const { sent, at } = opening(`${HISTORY}&sort=startedBy&filter=claim`);
      await rows();

      await userEvent.click(
        within(table()).getByRole("link", { name: "SUPPORT-1" }),
      );

      expect(await runOpened(/Claim from Avo/)).toBeInTheDocument();
      expect(at()).toBe(`${PAGE}/${AVO.runId}?sort=startedBy&filter=claim`);
      expect(
        screen.queryByRole("table", { name: "What is running" }),
      ).toBeNull();
      expect(screen.queryByRole("tablist")).toBeNull();
      expect(screen.queryByRole("searchbox")).toBeNull();
      expect(runsAsked(sent)).toEqual([
        `GET ${RUNS}?sort=startedBy&filter=claim`,
      ]);
    });

    it("goes back from a run opened there by the way back above its name, to History with its filter and order as they were, on the first page", async () => {
      localStorage.setItem(KEPT_AS, "newRun");
      const { sent, at } = opening(`${HISTORY}&sort=startedBy&filter=claim`);
      await userEvent.click(
        await within(table()).findByRole("link", { name: "SUPPORT-1" }),
      );
      await runOpened(/Claim from Avo/);

      await userEvent.click(screen.getByRole("link", { name: "History" }));

      expect(await rows()).toHaveLength(3);
      expect(at()).toBe(`${PAGE}?sort=startedBy&filter=claim&tab=history`);
      expect(tab("History")).toHaveAttribute("aria-selected", "true");
      expect(screen.getByRole("searchbox")).toHaveValue("claim");
      expect(
        within(table()).getByRole("columnheader", { name: /Started by$/ }),
      ).toHaveAttribute("aria-sort", "ascending");
      expect(runsAsked(sent)).toEqual([
        `GET ${RUNS}?sort=startedBy&filter=claim`,
        `GET ${RUNS}?sort=startedBy&filter=claim`,
      ]);
      expect(localStorage.getItem(KEPT_AS)).toBe("newRun");
    });

    it("goes back to History from a run begun on New run, though this device keeps New run", async () => {
      const { at } = opening(PAGE, { routes: { [`POST ${RUNS}`]: [BEGUN] } });
      await picking("Pay a refund");
      await userEvent.click(screen.getByRole("button", { name: "Start" }));
      await runOpened(/Claim from Avo/);

      await userEvent.click(screen.getByRole("link", { name: "History" }));

      expect(await rows()).toHaveLength(3);
      expect(at()).toBe(HISTORY);
      expect(tab("History")).toHaveAttribute("aria-selected", "true");
      expect(tab("New run")).toHaveAttribute("aria-selected", "false");
      expect(localStorage.getItem(KEPT_AS)).toBeNull();
    });
  });
});

describe("OpenedRun", () => {
  it("names the run as the page's one first-level heading, and Work in no heading of its own", async () => {
    opening(`${PAGE}/${AVO.runId}`);
    const title = await runOpened(/Claim from Avo/);

    expect(screen.getAllByRole("heading")[0]).toBe(title);
    expect(title).toHaveTextContent(isolatedName("Claim from Avo"));
    expect(screen.getAllByRole("heading", { level: 1 })).toEqual([title]);
    expect(screen.queryByRole("heading", { name: /^Work/ })).toBeNull();
  });

  it("starts the keyboard at the step's message the address names as the page arrives, forgetting it in place, and not again once the run is drawn anew in Conversation", async () => {
    const { at, router } = opening(
      `${PAGE}/${AVO.runId}?filter=claim#step-${SUMMARISE}`,
    );
    const arrivedAt = await screen.findByRole("list", {
      name: "The conversation",
    });
    await waitFor(() =>
      expect(document.activeElement).toBe(
        document.getElementById(`step-${SUMMARISE}`),
      ),
    );
    expect(at()).toBe(`${PAGE}/${AVO.runId}?filter=claim`);
    expect(router.state.historyAction).toBe("REPLACE");
    await act(() => router.navigate(-1));
    expect(at()).toBe(`${PAGE}/${AVO.runId}?filter=claim`);

    const switching = screen.getByRole("button", {
      name: "Show in full detail",
    });
    await userEvent.click(switching);
    await screen.findByRole("table", { name: "The steps" });
    await userEvent.click(switching);

    const drawnAgain = await screen.findByRole("list", {
      name: "The conversation",
    });
    expect(drawnAgain).not.toBe(arrivedAt);
    expect(document.activeElement).toBe(switching);
  });

  it("opens a step in the run's place, with no tab or list, and a way back to the run keeping the list's address", async () => {
    localStorage.setItem(RUN_DRAWING_KEPT_AS, "fullDetail");
    const { sent, at } = opening(`${PAGE}/${AVO.runId}?sort=startedBy`);
    const steps = await screen.findByRole("table", { name: "The steps" });

    await userEvent.click(
      within(steps).getByRole("link", { name: "summarise" }),
    );

    expect(
      await screen.findByRole("heading", { level: 1, name: /summarise/ }),
    ).toBeInTheDocument();
    expect(at()).toBe(`${PAGE}/${AVO.runId}/steps/${SUMMARISE}?sort=startedBy`);
    expect(screen.queryByRole("table", { name: "The steps" })).toBeNull();
    expect(screen.queryByRole("tablist")).toBeNull();
    expect(screen.getAllByRole("heading", { level: 1 })).toHaveLength(1);
    expect(screen.queryByRole("heading", { name: /^Work/ })).toBeNull();

    await userEvent.click(
      screen.getByRole("link", { name: isolatedName("Claim from Avo") }),
    );

    expect(await runOpened(/Claim from Avo/)).toBeInTheDocument();
    expect(at()).toBe(`${PAGE}/${AVO.runId}?sort=startedBy`);
    expect(runsAsked(sent)).toEqual([]);
  });

  it("draws on a step's page the path through Work, History and its run, the step last and unlinked, and no other way back to the run", async () => {
    opening(
      `${PAGE}/${AVO.runId}/steps/${SUMMARISE}?sort=startedBy&value=summary`,
    );
    await screen.findByRole("heading", { level: 1, name: /summarise/ });

    const path = screen.getByRole("navigation", { name: "Breadcrumb" });

    await waitFor(() =>
      expect(within(path).getAllByRole("link")).toHaveLength(3),
    );
    expect(
      within(path)
        .getAllByRole("link")
        .map((link) => [link.textContent, link.getAttribute("href")]),
    ).toEqual([
      ["Work", PAGE],
      ["History", `${PAGE}?sort=startedBy&tab=history`],
      [isolatedName("Claim from Avo"), `${PAGE}/${AVO.runId}?sort=startedBy`],
    ]);
    expect(within(path).getAllByRole("listitem").at(-1)).toHaveTextContent(
      "summarise",
    );
    expect(within(path).queryByRole("link", { name: /summarise/ })).toBeNull();
    const toTheRun = screen
      .getAllByRole("link")
      .filter(
        (link) =>
          link.getAttribute("href") === `${PAGE}/${AVO.runId}?sort=startedBy`,
      );
    expect(toTheRun).toHaveLength(1);
    expect(path).toContainElement(toTheRun[0]!);
  });

  it("leaves the keyboard on the control that switched the drawing where the run was come back to from its step, starting it at the run's name only as it arrived", async () => {
    localStorage.setItem(RUN_DRAWING_KEPT_AS, "fullDetail");
    opening(`${PAGE}/${AVO.runId}/steps/${SUMMARISE}`);
    await screen.findByRole("heading", { level: 1, name: /summarise/ });
    await userEvent.click(
      screen.getByRole("link", { name: isolatedName("Claim from Avo") }),
    );

    const arrivedAt = await runOpened(/Claim from Avo/);
    expect(document.activeElement).toBe(arrivedAt);

    const switching = screen.getByRole("button", {
      name: "Show as a conversation",
    });
    await userEvent.click(switching);

    await screen.findByRole("list", { name: "The conversation" });
    expect(document.activeElement).toBe(switching);
    expect(document.activeElement).not.toBe(arrivedAt);
  });
});
