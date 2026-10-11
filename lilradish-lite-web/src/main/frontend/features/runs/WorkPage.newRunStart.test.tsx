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
import { deferred } from "../../testutil/deferred";
import {
  PAGE,
  NEW_RUN,
  RUNS,
  OFFERED,
  AVO,
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
  startsAsked,
  picking,
  inputting,
  naming,
  runOpened,
} from "../../testutil/workPage";

describe("WorkPage", () => {
  describe("on New run", () => {
    it.each([
      [false, false, false],
      [true, false, true],
      [false, true, true],
      [true, true, true],
    ])(
      "uses current all=%s and own=%s grants after the same start reply",
      async (all, own, mayRead) => {
        const { at } = opening(PAGE, {
          permissions: [
            "read_membership",
            "start_run",
            ...(all ? ["read_all_runs"] : []),
            ...(own ? ["read_own_runs"] : []),
          ],
          routes: { [`POST ${RUNS}`]: [BEGUN] },
        });
        await picking("Pay a refund");
        await inputting();
        await userEvent.click(
          screen.getByRole("button", { name: "Start run" }),
        );
        if (mayRead) {
          expect(await runOpened(/Claim from Avo/)).toBeInTheDocument();
          expect(at()).toBe(`${PAGE}/${AVO.runId}`);
        } else {
          expect(
            await screen.findByText(STARTED_UNREADABLE),
          ).toBeInTheDocument();
          expect(at()).toBe(NEW_RUN);
          expect(
            screen.queryByRole("heading", { name: /Claim from Avo/ }),
          ).toBeNull();
        }
      },
    );

    it("uses a changed standing when a start already out answers, without reading it on its own", async () => {
      const pending = deferred<readonly [string, number]>();
      const { at, sent, changePermissions } = opening(PAGE, {
        permissions: ["read_membership", "start_run", "read_own_runs"],
        routes: { [`POST ${RUNS}`]: [pending.promise] },
      });
      await picking("Pay a refund");
      await inputting();
      await userEvent.click(screen.getByRole("button", { name: "Start run" }));
      await waitFor(() => expect(startsAsked(sent)).toHaveLength(1));
      act(() => changePermissions(["read_membership", "start_run"]));
      pending.settle(BEGUN as readonly [string, number]);
      expect(await screen.findByText(STARTED_UNREADABLE)).toBeInTheDocument();
      expect(at()).toBe(NEW_RUN);
      expect(
        requestsTo(sent).filter((address) => address === "GET /api/standing"),
      ).toEqual([]);
      expect(
        requestsTo(sent).filter(
          (address) => address === `GET ${RUNS}/${AVO.runId}`,
        ),
      ).toEqual([]);
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

      await inputting();
      await userEvent.click(screen.getByRole("button", { name: "Start run" }));

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

    it("stays where it was when the current standing grants neither way of reading runs", async () => {
      const { at } = opening(PAGE, {
        routes: { [`POST ${RUNS}`]: [BEGUN_UNREADABLE] },
        permissions: ["read_membership", "start_run"],
      });
      await picking("Pay a refund");

      await inputting();
      await userEvent.click(screen.getByRole("button", { name: "Start run" }));

      expect(await screen.findByText(STARTED_UNREADABLE)).toBeInTheDocument();
      expect(at()).toBe(NEW_RUN);
      expect(
        screen.queryByRole("heading", { name: /Claim from Avo/ }),
      ).toBeNull();
    });

    it("opens the run begun when the current standing grants reading its own runs", async () => {
      const { at, router } = opening(PAGE, {
        permissions: ["read_membership", "start_run", "read_own_runs"],
        routes: { [`POST ${RUNS}`]: [BEGUN] },
      });
      await picking("Pay a refund");

      await inputting();
      await userEvent.click(screen.getByRole("button", { name: "Start run" }));

      expect(await runOpened(/Claim from Avo/)).toBeInTheDocument();
      expect(at()).toBe(`${PAGE}/${AVO.runId}`);
      // Pushed where it was going, not written again by the group's gate.
      expect(router.state.historyAction).toBe("PUSH");
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
      const complaint = screen.getByRole("textbox", {
        name: "Complaint · complaint",
      });
      await userEvent.type(complaint, "Kettle leaks");

      await inputting();
      await userEvent.click(screen.getByRole("button", { name: "Start run" }));

      await waitFor(() =>
        expect(complaint).toHaveAttribute("aria-invalid", "true"),
      );
      expect(complaint).toHaveAccessibleDescription(
        expect.stringContaining("This is longer than this field takes."),
      );
      expect(complaint).toHaveFocus();
      expect(await naming()).toHaveAttribute("aria-invalid", "false");
      expect(screen.getByText(VALUES_REFUSED)).toBeInTheDocument();
      expect(screen.queryByText(/more field/)).toBeNull();
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
      const items = screen.getByRole("group", { name: "Items · items" });
      await userEvent.click(
        within(items).getByRole("button", {
          name: `Add one to ${isolatedName("Items")}`,
        }),
      );
      const name = within(items).getByRole("textbox", { name: "Name · name" });
      await userEvent.type(name, "Kettle");

      await inputting();
      await userEvent.click(screen.getByRole("button", { name: "Start run" }));

      await waitFor(() => expect(name).toHaveFocus());
      expect(name).toHaveAttribute("aria-invalid", "true");
      expect(
        within(items).getByRole("textbox", { name: "Count · count" }),
      ).toHaveAttribute("aria-invalid", "false");
      expect(
        screen.getByRole("group", {
          name: `${isolatedName("Items")} 1 · items`,
        }),
      ).toHaveAccessibleDescription("");
      expect(items).toHaveAccessibleDescription("1 / 3");
    });

    it("says how many more fields the server found than it listed, marked nowhere, and says it no longer once anything is changed", async () => {
      opening(PAGE, {
        routes: {
          [`POST ${RUNS}`]: [
            doesNotFit([{ path: ["complaint"], reason: "too_long" }], 3),
          ],
        },
      });
      await picking("Handle a claim");
      const complaint = screen.getByRole("textbox", {
        name: "Complaint · complaint",
      });
      await userEvent.type(complaint, "Kettle leaks");

      await inputting();
      await userEvent.click(screen.getByRole("button", { name: "Start run" }));

      expect(
        await screen.findByText(
          "2 more fields have problems that aren't marked.",
        ),
      ).toBeInTheDocument();
      expect(screen.getByText(VALUES_REFUSED)).toBeInTheDocument();

      await userEvent.type(complaint, "!");

      expect(screen.queryByText(/more fields/)).toBeNull();
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
      const complaint = screen.getByRole("textbox", {
        name: "Complaint · complaint",
      });
      await userEvent.type(complaint, "Kettle leaks");
      await inputting();
      await userEvent.click(screen.getByRole("button", { name: "Start run" }));
      await userEvent.type(complaint, " badly");
      const name = await naming();
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

        await inputting();
        await userEvent.click(
          screen.getByRole("button", { name: "Start run" }),
        );

        expect(await screen.findByRole("alert")).toHaveTextContent(said);
        expect(at()).toBe(NEW_RUN);
        expect(await naming()).toHaveValue(`Pay a refund ${OPENED_AT}`);
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
      const due = screen.getByLabelText<HTMLInputElement>("Due · due");
      vi.spyOn(due.validity, "badInput", "get").mockReturnValue(true);

      fireEvent.blur(due);
      await inputting();
      await userEvent.click(screen.getByRole("button", { name: "Start run" }));

      expect(due).toHaveAttribute("aria-invalid", "true");
      expect(due).toHaveAccessibleDescription(
        "This isn't in the format this field expects.",
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

      await inputting();
      await userEvent.click(screen.getByRole("button", { name: "Start run" }));

      const name = await naming();
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
      const complaint = screen.getByRole("textbox", {
        name: "Complaint · complaint",
      });
      const name = await naming();
      await inputting();
      await userEvent.type(complaint, "Kettle");
      await inputting();
      await userEvent.click(screen.getByRole("button", { name: "Start run" }));
      await waitFor(() =>
        expect(name).toHaveAccessibleDescription(NAME_REFUSED),
      );

      await inputting();
      await userEvent.type(complaint, " leaks");
      expect(name).toHaveValue(`Handle a claim ${OPENED_AT}`);
      expect(name).toHaveAccessibleDescription(NAME_REFUSED);
      await naming();
      await userEvent.clear(name);
      await userEvent.type(name, "Mine");
      expect(name).toHaveValue("Mine");
      expect(name).toHaveAccessibleDescription("");
      expect(name).toHaveAttribute("aria-invalid", "false");
    });

    it("holds the pending start, then clears its refusal when another workflow is picked, sending nothing more", async () => {
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
      await inputting();
      await userEvent.click(screen.getByRole("button", { name: "Start run" }));
      expect(screen.getByRole("button", { name: "Cancel" })).toHaveAttribute(
        "aria-disabled",
        "true",
      );
      expect(
        screen.queryByRole("button", { name: /Handle a claim/ }),
      ).toBeNull();
      refuse(['{"code":"RUN_NAME_UNUSABLE"}', 400]);
      await screen.findByText(NAME_REFUSED);
      await picking("Handle a claim");
      const name = await naming();
      await inputting();

      expect(name).toHaveAttribute("aria-invalid", "false");
      expect(name).toHaveAccessibleDescription("");
      expect(screen.queryByRole("alert")).toBeNull();
      expect(
        screen.getByRole("button", { name: "Start run" }),
      ).not.toHaveAttribute("aria-disabled");
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
        screen.getByRole("textbox", { name: "Complaint · complaint" }),
        "Kettle leaks",
      );

      await inputting();
      await userEvent.click(screen.getByRole("button", { name: "Start run" }));

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
      expect(
        screen.queryByRole("textbox", { name: "Complaint · complaint" }),
      ).toBeNull();
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
      const complaint = screen.getByRole("textbox", {
        name: "Complaint · complaint",
      });
      await userEvent.type(complaint, "Kettle leaks");
      const name = await naming();
      await userEvent.clear(name);
      await userEvent.type(name, "My claim");

      await inputting();
      await userEvent.click(screen.getByRole("button", { name: "Start run" }));

      expect(
        await screen.findByRole("heading", {
          name: `Start a run of ${isolatedName("Claim handling")}`,
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
      expect(screen.getByRole("dialog")).toContainElement(
        document.activeElement as HTMLElement,
      );
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
      const name = await naming();
      await userEvent.clear(name);
      await userEvent.type(name, "My claim");
      await naming();
      await userEvent.click(
        screen.getByRole("button", { name: "Choose another version" }),
      );
      await userEvent.click(screen.getByRole("combobox", { name: "Version" }));
      await userEvent.click(screen.getByRole("option", { name: "Version 1" }));

      await inputting();
      await userEvent.click(screen.getByRole("button", { name: "Start run" }));

      await waitFor(() =>
        expect(
          requestsTo(sent).filter((each) => each.startsWith(`GET ${OFFERED}`)),
        ).toHaveLength(3),
      );
      await inputting();
      expect(
        await screen.findByRole("textbox", { name: "Complaint · complaint" }),
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

    it("names a version by its number and the name given it, and starts the one chosen by that name", async () => {
      const named = {
        ...HANDLE,
        versions: [
          HANDLE.versions[0]!,
          { ...HANDLE.versions[1]!, versionName: "Spring intake" },
        ],
      };
      const { sent } = opening(PAGE, {
        routes: {
          [`GET ${OFFERED}`]: [
            [JSON.stringify({ items: [named, REFUND] }), 200],
          ],
          [`POST ${RUNS}`]: [BEGUN],
        },
      });
      await picking("Handle a claim");
      await naming();
      await userEvent.click(
        screen.getByRole("button", { name: "Choose another version" }),
      );
      await userEvent.click(screen.getByRole("combobox", { name: "Version" }));

      expect(
        screen.getByRole("option", { name: "Version 2" }),
      ).toBeInTheDocument();
      await userEvent.click(
        screen.getByRole("option", { name: "Version 1 \u00b7 Spring intake" }),
      );

      expect(
        (screen.getByRole("combobox", { name: "Version" }) as HTMLInputElement)
          .value,
      ).toBe("Version 1 \u00b7 \u2068Spring intake\u2069");
      await inputting();
      await userEvent.click(screen.getByRole("button", { name: "Start run" }));
      await waitFor(() => expect(startsAsked(sent)).toHaveLength(1));
      expect(startsAsked(sent)).toEqual([
        expect.objectContaining({ versionId: HANDLE.versions[1]!.versionId }),
      ]);
      expect(JSON.stringify(startsAsked(sent))).not.toContain(
        HANDLE.versions[0]!.versionId,
      );
    });

    it("finishes checking a refused start inside the modal, retaining its draft until Cancel allows filtering", async () => {
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
      const name = await naming();
      await userEvent.clear(name);
      await userEvent.type(name, "My claim");
      await naming();
      await userEvent.click(
        screen.getByRole("button", { name: "Choose another version" }),
      );
      await userEvent.click(screen.getByRole("combobox", { name: "Version" }));
      await userEvent.click(screen.getByRole("option", { name: "Version 1" }));
      await inputting();
      await userEvent.click(screen.getByRole("button", { name: "Start run" }));
      await waitFor(() =>
        expect(requestsTo(sent)).toContain(
          `GET ${OFFERED}?filter=Handle+a+claim`,
        ),
      );

      expect(
        screen.queryByRole("searchbox", { name: "Part of a name" }),
      ).toBeNull();
      finishCheck([JSON.stringify({ items: [newestOnly] }), 200]);

      await naming();
      await waitFor(() =>
        expect(
          (
            screen.getByRole("combobox", {
              name: "Version",
            }) as HTMLInputElement
          ).value,
        ).toContain("Version 2"),
      );
      await inputting();
      expect(
        screen.getByRole("textbox", { name: "Complaint · complaint" }),
      ).toBeInTheDocument();
      expect(
        screen.getByRole("heading", {
          name: `Start a run of ${isolatedName("Handle a claim")}`,
        }),
      ).toBeInTheDocument();
      expect(name).toHaveValue("My claim");
      expect(startsAsked(sent)).toHaveLength(1);
      expect(
        requestsTo(sent).filter(
          (each) => each === `GET ${OFFERED}?filter=Handle+a+claim`,
        ),
      ).toHaveLength(1);
      await userEvent.click(screen.getByRole("button", { name: "Cancel" }));
      fireEvent.change(
        screen.getByRole("searchbox", { name: "Part of a name" }),
        { target: { value: "refund" } },
      );
      await waitFor(() =>
        expect(requestsTo(sent)).toContain(`GET ${OFFERED}?filter=refund`),
      );
      expect(name).not.toBeInTheDocument();
      expect(startsAsked(sent)).toHaveLength(1);
    });

    it.each([
      ["named", false],
      ["unfiltered", true],
    ] as const)(
      "stops a repeated %s verification cursor and retries without changing the selected draft",
      async (_case, unfiltered) => {
        const repeating = [
          JSON.stringify({ items: [REFUND], nextCursor: "repeat" }),
          200,
        ] as const;
        const { sent, at } = opening(PAGE, {
          routes: {
            [`POST ${RUNS}`]: [['{"code":"WORKFLOW_NOT_OFFERED"}', 409]],
            [`GET ${OFFERED}`]: [
              ON_OFFER,
              ...(unfiltered
                ? [[JSON.stringify({ items: [] }), 200] as const]
                : []),
              repeating,
              repeating,
              ON_OFFER,
              ON_OFFER,
            ],
          },
        });
        await picking("Handle a claim");
        const complaint = screen.getByRole("textbox", {
          name: "Complaint · complaint",
        });
        const name = await naming();
        await userEvent.clear(name);
        await userEvent.type(name, "My claim");
        await inputting();
        await userEvent.type(complaint, "Kettle leaks");
        await inputting();
        await userEvent.click(
          screen.getByRole("button", { name: "Start run" }),
        );
        const retry = await screen.findByRole("button", {
          name: "Try loading again",
        });
        expect(
          screen.getByText("Something went wrong on this page."),
        ).toBeInTheDocument();
        expect(name).toHaveValue("My claim");
        expect(complaint).toHaveValue("Kettle leaks");
        expect(at()).toBe(NEW_RUN);
        expect(startsAsked(sent)).toHaveLength(1);
        expect(
          requestsTo(sent).filter((each) => each.startsWith(`GET ${OFFERED}`)),
        ).toEqual([
          `GET ${OFFERED}`,
          `GET ${OFFERED}?filter=Handle+a+claim`,
          ...(unfiltered
            ? [`GET ${OFFERED}`, `GET ${OFFERED}?cursor=repeat`]
            : [`GET ${OFFERED}?filter=Handle+a+claim&cursor=repeat`]),
        ]);
        await userEvent.click(retry);
        await waitFor(() =>
          expect(
            screen.queryByRole("button", { name: "Try loading again" }),
          ).toBeNull(),
        );
        expect(
          screen.queryByText("Something went wrong on this page."),
        ).toBeNull();
        expect(name).toHaveValue("My claim");
        expect(complaint).toHaveValue("Kettle leaks");
        expect(
          screen.getByRole("heading", {
            name: `Start a run of ${isolatedName("Handle a claim")}`,
          }),
        ).toBeInTheDocument();
        expect(startsAsked(sent)).toHaveLength(1);
        expect(at()).toBe(NEW_RUN);
        expect(
          requestsTo(sent).filter((each) => each.startsWith(`GET ${OFFERED}`)),
        ).toHaveLength(unfiltered ? 6 : 5);
      },
    );

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
      const name = await naming();
      await userEvent.clear(name);
      await userEvent.type(name, "My refund");

      await inputting();
      await userEvent.click(screen.getByRole("button", { name: "Start run" }));

      expect(
        await screen.findByRole("button", { name: "Try loading again" }),
      ).toBeInTheDocument();
      expect(name).toHaveValue("My refund");
      expect(
        screen.getByRole("heading", {
          name: `Start a run of ${isolatedName("Pay a refund")}`,
        }),
      ).toBeInTheDocument();
      expect(
        requestsTo(sent).filter((each) => each.startsWith(`GET ${OFFERED}`)),
      ).toHaveLength(2);

      await naming();
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

    it("keeps failed verification and its draft inside the modal, allowing filtering only after Cancel", async () => {
      const { sent, readAgain } = opening(PAGE, {
        routes: {
          [`POST ${RUNS}`]: [['{"code":"WORKFLOW_NOT_OFFERED"}', 409]],
          [`GET ${OFFERED}`]: [
            ON_OFFER,
            ['{"code":"SERVICE_BUSY"}', 503],
            ON_OFFER,
            ON_OFFER,
            [JSON.stringify({ items: [HANDLE] }), 200],
          ],
        },
      });
      await picking("Pay a refund");
      const name = await naming();
      await userEvent.clear(name);
      await userEvent.type(name, "My refund");
      await inputting();
      await userEvent.click(screen.getByRole("button", { name: "Start run" }));
      expect(
        await screen.findByRole("button", { name: "Try loading again" }),
      ).toBeInTheDocument();

      expect(
        screen.queryByRole("searchbox", { name: "Part of a name" }),
      ).toBeNull();
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
      expect(name).toHaveValue("My refund");
      expect(startsAsked(sent)).toHaveLength(1);
      expect(readAgain).not.toHaveBeenCalled();
      await userEvent.click(screen.getByRole("button", { name: "Cancel" }));
      fireEvent.change(
        screen.getByRole("searchbox", { name: "Part of a name" }),
        { target: { value: "claim" } },
      );
      await waitFor(() =>
        expect(requestsTo(sent)).toContain(`GET ${OFFERED}?filter=claim`),
      );
      expect(name).not.toBeInTheDocument();
      expect(
        requestsTo(sent).filter((each) => each.startsWith(`GET ${OFFERED}`)),
      ).toHaveLength(5);
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
      await screen.findByRole("button", { name: /Handle a claim/ });
      await userEvent.click(
        screen.getByRole("button", { name: "Load more workflows" }),
      );
      const retry = await screen.findByRole("button", {
        name: "Try loading again",
      });
      await picking("Handle a claim");
      await userEvent.type(
        screen.getByRole("textbox", { name: "Complaint · complaint" }),
        "Kettle leaks",
      );
      await inputting();
      await userEvent.click(screen.getByRole("button", { name: "Start run" }));

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
      expect(screen.queryByText("Loading…")).toBeNull();
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
      await screen.findByRole("button", { name: /Handle a claim/ });
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
      await picking("Handle a claim");
      await userEvent.type(
        screen.getByRole("textbox", { name: "Complaint · complaint" }),
        "Kettle leaks",
      );
      await inputting();
      await userEvent.click(screen.getByRole("button", { name: "Start run" }));

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
      expect(screen.queryByText("Loading…")).toBeNull();
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
      await inputting();
      await userEvent.click(screen.getByRole("button", { name: "Start run" }));
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
      const name = await naming();

      await inputting();
      await userEvent.click(screen.getByRole("button", { name: "Start run" }));

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
});
