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
  PAGE,
  RUNS,
  OFFERED,
  HANDLE,
  BEGUN,
  OPENED_AT,
  ORDER_ON_OFFER,
  isolatedName,
  opening,
  startsAsked,
  picking,
  inputting,
  naming,
} from "../../testutil/workPage";

describe("WorkPage", () => {
  describe("on New run", () => {
    it.each([false, true])(
      "takes the keyboard to the named dropdown when the input step starts, many %s",
      async (many) => {
        const offered = {
          ...HANDLE,
          versions: [
            {
              ...HANDLE.versions[0],
              takes: [
                {
                  name: "category",
                  label: "Category",
                  kind: "term",
                  mustBeGiven: true,
                  ...(many ? { most: 2 } : {}),
                  terms: {
                    terms: Array.from({ length: 6 }, (_, index) => ({
                      term: `Route ${index + 1}`,
                      meaning: `Destination ${index + 1}`,
                    })),
                  },
                },
              ],
            },
          ],
        };
        const { sent } = opening(PAGE, {
          routes: {
            [`GET ${OFFERED}`]: [[JSON.stringify({ items: [offered] }), 200]],
          },
        });
        const dialog = await picking(HANDLE.name);
        const select = within(dialog).getByRole("combobox", {
          name: "Category · category",
        });
        expect(select).toHaveFocus();
        await userEvent.keyboard("{ArrowDown}");
        expect(await screen.findByRole("listbox")).toBeInTheDocument();
        await userEvent.keyboard("{Escape}");
        expect(select).toHaveFocus();
        await userEvent.click(
          within(dialog).getByRole("button", { name: "Start run" }),
        );
        expect(select).toHaveFocus();
        expect(startsAsked(sent)).toEqual([]);
        await userEvent.click(
          within(dialog).getByRole("button", { name: "Cancel" }),
        );
        expect(screen.queryByRole("dialog")).toBeNull();
        expect(startsAsked(sent)).toEqual([]);
      },
    );
    it.each([false, true])(
      "focuses the visible file chooser for empty required input instead of the hidden native input, many %s",
      async (many) => {
        const offered = {
          ...HANDLE,
          versions: [
            {
              ...HANDLE.versions[0],
              takes: [
                {
                  name: "attachment",
                  label: "Attachment",
                  kind: "file",
                  mustBeGiven: true,
                  types: [".txt"],
                  maximumBytes: 8,
                  ...(many ? { most: 2 } : {}),
                },
              ],
            },
          ],
        };
        const { sent } = opening(PAGE, {
          routes: {
            [`GET ${OFFERED}`]: [[JSON.stringify({ items: [offered] }), 200]],
          },
        });
        const dialog = await picking(HANDLE.name);
        const chooser = within(dialog).getByRole("button", {
          name: many ? "Choose files" : "Choose a file",
        });
        expect(chooser).toHaveFocus();
        await userEvent.click(
          within(dialog).getByRole("button", { name: "Start run" }),
        );
        expect(chooser).toHaveFocus();
        expect(startsAsked(sent)).toEqual([]);
        await userEvent.click(
          within(dialog).getByRole("button", { name: "Cancel" }),
        );
        expect(screen.queryByRole("dialog")).toBeNull();
        expect(startsAsked(sent)).toEqual([]);
      },
    );
    it("says at once that what must be given is empty, and once Start is pressed marks it so, takes the keyboard there and asks nothing", async () => {
      const { sent } = opening(PAGE, {
        routes: { [`POST ${RUNS}`]: [BEGUN] },
      });
      await picking("Handle a claim");
      const complaint = screen.getByRole("textbox", {
        name: "Complaint · complaint",
      });
      const start = screen.getByRole("button", { name: "Start run" });
      expect(
        screen.getByText("A required field is empty."),
      ).toBeInTheDocument();
      expect(complaint).toHaveAttribute("aria-invalid", "false");

      await act(async () => {
        start.focus();
      });
      await userEvent.keyboard("{Enter}");

      expect(start).not.toHaveAttribute("aria-disabled");
      expect(complaint).toHaveAttribute("aria-invalid", "true");
      expect(complaint).toHaveAccessibleDescription(
        expect.stringContaining("Required."),
      );
      expect(complaint).toHaveFocus();
      expect(startsAsked(sent)).toEqual([]);
    });

    it("takes the keyboard to the name once Start is pressed where it is none a run may be called, and asks nothing", async () => {
      const { sent } = opening(PAGE, {
        routes: { [`POST ${RUNS}`]: [BEGUN] },
      });
      await picking("Pay a refund");
      const name = await naming();
      await userEvent.clear(name);

      await inputting();
      await userEvent.click(screen.getByRole("button", { name: "Start run" }));

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
      const items = screen.getByRole("group", { name: "Items · items" });
      await userEvent.click(
        within(items).getByRole("button", {
          name: `Add one to ${isolatedName("Items")}`,
        }),
      );
      await userEvent.type(
        within(items).getByRole("textbox", { name: "Count · count" }),
        "2",
      );

      await inputting();
      await userEvent.click(screen.getByRole("button", { name: "Start run" }));

      const name = within(items).getByRole("textbox", { name: "Name · name" });
      expect(name).toHaveFocus();
      expect(name).toHaveAttribute("aria-invalid", "true");
      expect(
        within(items).getByRole("textbox", { name: "Count · count" }),
      ).toHaveAttribute("aria-invalid", "false");
      expect(startsAsked(sent)).toEqual([]);
    });

    it("says why a value does not fit only once the keyboard has left it, while saying at once that a value does not fit", async () => {
      opening(PAGE);
      await picking("Handle a claim");
      const complaint = screen.getByRole("textbox", {
        name: "Complaint · complaint",
      });

      await userEvent.type(complaint, `Kettle${String.fromCodePoint(0x2066)}`);
      expect(complaint).toHaveAttribute("aria-invalid", "false");
      expect(
        screen.getByText("Some values aren't valid yet; each one says why."),
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
            versionName: null,
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
      const group = screen.getByRole("group", { name: "Address · address" });
      expect(group).toHaveAccessibleDescription("");

      const street = within(group).getByRole("textbox", {
        name: "Street · street",
      });
      await userEvent.type(street, "1");
      await userEvent.clear(street);

      expect(group).toHaveAccessibleDescription("Required.");
      expect(street).toHaveAttribute("aria-invalid", "false");
    });

    it("moves what was changed of each one of many down past one taken away, so one added in its place is not yet said to be empty", async () => {
      const labelled = {
        entryId: "00000006-0000-4000-8000-000000000c65",
        name: "Label a note",
        versions: [
          {
            versionName: null,
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
      const tags = screen.getByRole("group", { name: "Tags · tags" });
      const tag = (position: number) =>
        within(tags).getByRole("textbox", {
          name: `${isolatedName("Tags")} ${position} · tags`,
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
          name: `Remove ${isolatedName("Tags")} 1`,
        }),
      );
      await addOne();

      expect(tag(1)).toHaveValue("b");
      expect(tag(1)).toHaveAttribute("aria-invalid", "false");
      expect(tag(2)).toHaveValue("");
      expect(tag(2)).toHaveAttribute("aria-invalid", "false");
      expect(tag(2)).not.toHaveAccessibleDescription(/Required/);
      expect(tags).toHaveAccessibleDescription("2 / 2");
    });

    it("starts another version once asked for, filling what that one takes", async () => {
      const { sent } = opening(PAGE, {
        routes: { [`POST ${RUNS}`]: [BEGUN] },
      });
      await picking("Handle a claim");

      await naming();
      await userEvent.click(
        screen.getByRole("button", { name: "Choose another version" }),
      );
      expect(screen.getByRole("combobox", { name: "Version" })).toHaveFocus();
      await userEvent.click(screen.getByRole("combobox", { name: "Version" }));
      await userEvent.click(screen.getByRole("option", { name: "Version 1" }));
      expect(screen.getByRole("combobox", { name: "Version" })).toHaveFocus();
      await inputting();
      await userEvent.click(screen.getByRole("button", { name: "Start run" }));

      await waitFor(() =>
        expect(startsAsked(sent)).toEqual([
          {
            name: `Handle a claim ${OPENED_AT}`,
            versionId: HANDLE.versions[1]!.versionId,
            values: {},
          },
        ]),
      );
      expect(
        screen.queryByRole("textbox", { name: "Complaint · complaint" }),
      ).toBeNull();
    });

    it("clears a rejected file selection when a new version keeps the field name but changes its limits", async () => {
      const fileField = {
        name: "attachments",
        label: "Attachments",
        kind: "file",
        types: [".txt"],
        maximumBytes: 4,
        most: 1,
        mustBeGiven: false,
      };
      const offered = {
        entryId: "00000006-0000-4000-8000-000000000c66",
        name: "Archive a note",
        versions: [
          {
            versionName: null,
            versionId: "00000007-0000-4000-8000-000000000c67",
            number: 2,
            takes: [fileField],
          },
          {
            versionName: null,
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
      expect(screen.getByText("0 / 1")).toBeVisible();

      await naming();
      await userEvent.click(
        screen.getByRole("button", { name: "Choose another version" }),
      );
      await userEvent.click(screen.getByRole("combobox", { name: "Version" }));
      await userEvent.click(screen.getByRole("option", { name: "Version 1" }));

      await inputting();
      const newInput = screen.getByLabelText(/Choose files for/);
      expect(newInput).not.toBe(oldInput);
      expect(newInput).not.toHaveAttribute("aria-invalid", "true");
      expect(fileStatus("is larger than this field allows.")).toBeNull();
      expect(screen.getByText("0 / 2")).toBeVisible();
      expect(screen.getByText(/8 B/)).toBeVisible();
      fireEvent.change(newInput, { target: { files: [fiveBytes] } });
      expect(
        screen.getByRole("button", { name: /Remove .*Demo Sample.txt/ }),
      ).toBeVisible();
      expect(screen.queryByText("is selected and awaits upload.")).toBeNull();
      expect(newInput).not.toHaveAttribute("aria-invalid", "true");
    });
  });
});
