import { ThemeProvider } from "@mui/material/styles";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { useState, type ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";

import type { Entry } from "../../api/groups/{groupId}/{kind}";
import { nameVersion } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}/name";
import { useAction } from "../../lib/request/useAction";
import { theme } from "../../lib/theme/theme";
import { requestsTo, serving, type Reply } from "../../testutil/answering";
import { VersionName } from "./VersionName";

const GROUP = "00000003-0000-4000-8000-000000000b31";
const ENTRY = "00000006-0000-4000-8000-000000000b31";
const VERSION = "00000007-0000-4000-8000-000000000b31";
const ADDRESS = `/api/groups/${GROUP}/questions/${ENTRY}/versions/${VERSION}/name`;

function answer(name: string | null) {
  return {
    entryId: ENTRY,
    kind: "question",
    name: "Triage",
    stateActs: [],
    versions: [
      {
        versionId: VERSION,
        number: 3,
        versionName: name,
        standing: "retired",
        writers: [{ userId: "000001" }],
        writtenByMigration: false,
        stateActs: [],
        pinnedBy: [],
      },
    ],
  };
}

function themed({ children }: { readonly children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

function shown(initial: string | null, reply?: Reply) {
  const sent = serving(
    reply === undefined ? {} : { [`PUT ${ADDRESS}`]: [reply] },
  );
  const settled = vi.fn();
  function Holder() {
    const [name, setName] = useState(initial);
    const action = useAction<Entry>((entry) => {
      settled(entry);
      setName(entry.versions[0]!.versionName);
    });
    return (
      <>
        <VersionName
          number={3}
          value={name}
          action={action}
          send={(next, signal) =>
            nameVersion(GROUP, "question", ENTRY, VERSION, next, signal)
          }
        />
        <button>Outside</button>
      </>
    );
  }
  render(<Holder />, { wrapper: themed });
  return { sent, settled };
}

function entrance(name: string | null) {
  return screen.getByRole("button", {
    name: name === null ? "Add a name for Version 3" : `${name}, editable`,
  });
}

function input() {
  return screen.getByRole("textbox", { name: "Version name" });
}

const NAME_HINT =
  "Optional. A short name to tell this version apart, such as \"Stricter tone\". It's shown wherever this version is and can be changed at any time; it doesn't change what the version holds.";

describe("VersionName", () => {
  it.each([null, "Current name"])(
    "keeps %s editing in a slot sized for its longest allowed name",
    async (name) => {
      shown(name);
      const trigger = entrance(name);
      const readWidth = getComputedStyle(trigger).width;
      const readSlot = trigger.parentElement!;
      if (name === null) {
        expect(trigger.querySelector("svg")).not.toBeNull();
        expect(getComputedStyle(trigger).fontSize).toBe("14px");
      }
      await userEvent.click(trigger);
      expect(input()).not.toHaveAttribute("placeholder");
      expect(input().tagName).toBe("TEXTAREA");
      expect(document.querySelector("label")).toBeNull();
      const slot = input().closest("form")!.parentElement!;
      expect(getComputedStyle(slot).maxWidth).toBe(readWidth);
      expect(getComputedStyle(slot).maxWidth).toBe(
        getComputedStyle(input().closest("form")!).width,
      );
      expect(getComputedStyle(slot).flexGrow).toBe("1");
      expect(getComputedStyle(slot).flexBasis).toBe(
        getComputedStyle(readSlot).flexBasis,
      );
      expect(getComputedStyle(slot).fontSize).toBe(
        name === null ? "14px" : "20px",
      );
    },
  );

  it("keeps the exact input open with the approved explanation when the server refuses its name", async () => {
    const { sent, settled } = shown("Old name", [
      JSON.stringify({
        status: 400,
        code: "VERSION_NAME_UNUSABLE",
        detail: "must-not-be-displayed",
      }),
      400,
    ]);
    await userEvent.click(entrance("Old name"));
    fireEvent.change(input(), { target: { value: "Refused name" } });
    await userEvent.keyboard("{Enter}");
    const explanation = await screen.findByRole("alert");
    expect(explanation).toHaveTextContent(
      "A version name is up to 64 characters on one line, with single spaces between words, none at the start or end, and something in it that shows. Leave it empty for none.",
    );
    expect(explanation).not.toHaveTextContent(
      "The server refused this request.",
    );
    expect(explanation).not.toHaveTextContent("must-not-be-displayed");
    expect(input()).toHaveValue("Refused name");
    expect(input()).toHaveAccessibleDescription(NAME_HINT);
    await waitFor(() => expect(input()).toHaveFocus());
    expect(settled).not.toHaveBeenCalled();
    expect(requestsTo(sent)).toEqual([`PUT ${ADDRESS}`]);
    expect(JSON.parse(String(sent.mock.calls[0]![1]?.body))).toEqual({
      name: "Refused name",
    });
  });

  it.each([null, "Current name"])(
    "opens %s in the same inline editor and sends the exact name by Enter",
    async (before) => {
      const next = "📦".repeat(64);
      const { sent, settled } = shown(before, [
        JSON.stringify(answer(next)),
        200,
      ]);
      await userEvent.click(entrance(before));
      expect(input()).toHaveValue(before ?? "");
      expect(input()).toHaveAccessibleDescription(NAME_HINT);
      expect(input()).not.toHaveAttribute("maxlength");
      fireEvent.change(input(), { target: { value: next } });
      await userEvent.keyboard("{Enter}");
      await waitFor(() => expect(settled).toHaveBeenCalledTimes(1));
      expect(requestsTo(sent)).toEqual([`PUT ${ADDRESS}`]);
      expect(JSON.parse(String(sent.mock.calls[0]![1]?.body))).toEqual({
        name: next,
      });
      expect(screen.queryByRole("textbox")).toBeNull();
      expect(entrance(next).querySelector("bdi")).toHaveTextContent(next);
      expect(entrance(next)).toHaveAccessibleName(`${next}, editable`);
      await waitFor(() => expect(entrance(next)).toHaveFocus());
    },
  );

  it("clears a named version with the exact empty string and receives null", async () => {
    const { sent, settled } = shown("Old name", [
      JSON.stringify(answer(null)),
      200,
    ]);
    await userEvent.click(entrance("Old name"));
    await userEvent.clear(input());
    await userEvent.click(screen.getByRole("button", { name: "Outside" }));
    await waitFor(() => expect(settled).toHaveBeenCalledTimes(1));
    expect(JSON.parse(String(sent.mock.calls[0]![1]?.body))).toEqual({
      name: "",
    });
    expect(entrance(null)).toHaveTextContent("Add a name");
    expect(screen.getByRole("button", { name: "Outside" })).toHaveFocus();
  });

  it.each([null, "Current name"])(
    "abandons changed %s by Escape and cancels unchanged without a request",
    async (before) => {
      const { sent } = shown(before);
      await userEvent.click(entrance(before));
      await userEvent.type(input(), " revised{Escape}");
      expect(screen.queryByRole("textbox")).toBeNull();
      await waitFor(() => expect(entrance(before)).toHaveFocus());
      await userEvent.click(entrance(before));
      await userEvent.keyboard("{Enter}");
      expect(screen.queryByRole("textbox")).toBeNull();
      await waitFor(() => expect(entrance(before)).toHaveFocus());
      expect(requestsTo(sent)).toEqual([]);
    },
  );

  it("does not steal focus from the clicked destination when unchanged editing closes on blur", async () => {
    const { sent } = shown("Current name");
    await userEvent.click(entrance("Current name"));
    const outside = screen.getByRole("button", { name: "Outside" });
    await userEvent.click(outside);
    expect(screen.queryByRole("textbox")).toBeNull();
    await waitFor(() => expect(outside).toHaveFocus());
    expect(requestsTo(sent)).toEqual([]);
  });

  it.each([null, "Current name"])(
    "does not save composing %s and retains a refused name and focus",
    async (before) => {
      const { sent } = shown(before, [
        JSON.stringify({ status: 403, code: "ACT_NOT_PERMITTED" }),
        403,
      ]);
      await userEvent.click(entrance(before));
      fireEvent.change(input(), { target: { value: "New name" } });
      fireEvent.compositionStart(input());
      fireEvent.keyDown(input(), { key: "Enter", isComposing: true });
      expect(requestsTo(sent)).toEqual([]);
      fireEvent.compositionEnd(input());
      await userEvent.keyboard("{Enter}");
      await waitFor(() => expect(input()).toHaveFocus());
      await waitFor(() =>
        expect(screen.getByRole("alert")).toBeInTheDocument(),
      );
      expect(input()).toHaveValue("New name");
      expect(requestsTo(sent)).toEqual([`PUT ${ADDRESS}`]);
      await userEvent.keyboard("{Escape}");
      expect(entrance(before)).toBeInTheDocument();
    },
  );

  it.each([
    "📦".repeat(65),
    " Leading",
    "Trailing ",
    "\u115f",
    "\u034f",
    "\ufe0f",
  ])("does not trim or send an invalid raw name", async (name) => {
    const { sent } = shown(null);
    await userEvent.click(entrance(null));
    fireEvent.change(input(), { target: { value: name } });
    expect(input()).toHaveAccessibleDescription(
      `${NAME_HINT} A version name is up to 64 characters on one line, with single spaces between words, none at the start or end, and something in it that shows. Leave it empty for none.`,
    );
    await userEvent.keyboard("{Enter}");
    await userEvent.click(screen.getByRole("button", { name: "Outside" }));
    expect(input()).toHaveValue(name);
    expect(requestsTo(sent)).toEqual([]);
  });

  it("draws the way to add a name, once the keyboard reaches it, on the row-hover ground with the card edge for its border and the act-coloured ring outside it, its pen in the secondary ink", async () => {
    shown(null);
    const trigger = entrance(null);
    expect(getComputedStyle(trigger).outlineStyle).not.toBe("solid");
    expect(getComputedStyle(trigger).borderColor).toBe("rgba(0, 0, 0, 0)");

    await userEvent.tab();

    expect(trigger).toHaveFocus();
    const reached = getComputedStyle(trigger);
    expect([
      reached.backgroundColor,
      reached.borderColor,
      reached.outlineStyle,
      reached.outlineWidth,
      reached.outlineColor,
    ]).toEqual([
      "rgb(243, 248, 250)",
      "rgb(214, 224, 228)",
      "solid",
      "2px",
      "rgb(28, 91, 129)",
    ]);
    expect(getComputedStyle(trigger.querySelector("svg")!).color).toBe(
      "rgb(88, 102, 108)",
    );
  });
});
