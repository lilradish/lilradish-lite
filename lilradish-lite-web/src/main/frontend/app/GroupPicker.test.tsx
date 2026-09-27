import { ThemeProvider } from "@mui/material/styles";
import { render, screen } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import type { GroupStanding } from "../api/standing";
import { theme } from "../lib/theme/theme";
import { inGroup } from "../testutil/standingRead";
import { GroupPicker } from "./GroupPicker";

/** A name read right to left, which the list must not let reorder the text around it. */
const WAGES = String.fromCodePoint(0x5e9, 0x5db, 0x5e8);

const CAPITAL_E_ACUTE = String.fromCodePoint(0xc9);

const PAYROLL = inGroup(
  "00000003-0000-4000-8000-000000000901",
  "PAY",
  "Payroll",
  [],
);
const FINANCE = inGroup(
  "00000003-0000-4000-8000-000000000902",
  "BOOKS",
  "Finance",
  [],
);
const ELAN = inGroup(
  "00000003-0000-4000-8000-000000000903",
  "SPIRIT",
  `${CAPITAL_E_ACUTE}lan`,
  [],
);
const RIGHT_TO_LEFT = inGroup(
  "00000003-0000-4000-8000-000000000904",
  "WAGES",
  WAGES,
  [],
);

const MINE = [PAYROLL, FINANCE, ELAN, RIGHT_TO_LEFT];

function picking(group: GroupStanding = PAYROLL) {
  const onPick = vi.fn<(next: GroupStanding) => void>();
  render(
    <ThemeProvider theme={theme}>
      <GroupPicker groups={MINE} group={group} onPick={onPick} />
    </ThemeProvider>,
  );
  return onPick;
}

function box(): HTMLElement {
  return screen.getByRole("combobox", { name: "Group" });
}

function offered(): string[] {
  return screen
    .queryAllByRole("option")
    .map((option) => option.textContent ?? "");
}

describe("GroupPicker", () => {
  it("names the group in force where it can be read, and offers nothing until opened", () => {
    picking(FINANCE);

    expect(box()).toHaveValue("Finance");
    expect(offered()).toEqual([]);
  });

  /** A name in either direction sets the box's own direction, rather than the page's. */
  it("lets the name in the box take its direction from its own words", () => {
    picking(RIGHT_TO_LEFT);

    expect(box()).toHaveAttribute("dir", "auto");
  });

  it("lists every group of the reader's once opened, each by its name with its key beside it", async () => {
    picking();

    await userEvent.click(box());

    expect(offered()).toEqual([
      "Payroll PAY",
      "Finance BOOKS",
      `${CAPITAL_E_ACUTE}lan SPIRIT`,
      `${WAGES} WAGES`,
    ]);
  });

  /** Narrowed as this system folds text: case folded, accents kept, on the name or the key. */
  it.each([
    ["part of a name", "nanc", ["Finance BOOKS"]],
    ["a name in another case", "FIN", ["Finance BOOKS"]],
    ["a key", "books", ["Finance BOOKS"]],
    [
      "a name's accent, in capitals",
      `${CAPITAL_E_ACUTE}LAN`,
      [`${CAPITAL_E_ACUTE}lan SPIRIT`],
    ],
    [
      "what several hold",
      "a",
      [
        "Payroll PAY",
        "Finance BOOKS",
        `${CAPITAL_E_ACUTE}lan SPIRIT`,
        `${WAGES} WAGES`,
      ],
    ],
  ])("narrows to the groups holding %s", async (_case, typed, left) => {
    picking();

    await userEvent.clear(box());
    await userEvent.type(box(), typed);

    expect(offered()).toEqual(left);
  });

  it("finds nothing where only an accent was left off, and says so", async () => {
    picking();

    await userEvent.clear(box());
    await userEvent.type(box(), "elan");

    expect(offered()).toEqual([]);
    expect(
      screen.getByText("None of your groups has a name or key holding that."),
    ).toBeInTheDocument();
  });

  it("hands on the group picked, and nothing else", async () => {
    const onPick = picking();

    await userEvent.click(box());
    await userEvent.click(
      screen.getByRole("option", { name: `${CAPITAL_E_ACUTE}lan SPIRIT` }),
    );

    expect(onPick.mock.calls).toEqual([[ELAN]]);
  });

  /** Typed until one group is left, Enter takes it: the first match is already highlighted. */
  it("takes the first group left by Enter alone", async () => {
    const onPick = picking();

    await userEvent.clear(box());
    await userEvent.type(box(), "books{Enter}");

    expect(onPick.mock.calls).toEqual([[FINANCE]]);
  });

  /** The group already in force is no change, and handing it on would open its page again. */
  it("hands on nothing where the group picked is the one in force", async () => {
    const onPick = picking();

    await userEvent.click(box());
    await userEvent.click(screen.getByRole("option", { name: "Payroll PAY" }));

    expect(onPick).not.toHaveBeenCalled();
  });

  it("sets each name in the list apart from the text around it", async () => {
    picking();

    await userEvent.click(box());

    const option = screen.getByRole("option", { name: `${WAGES} WAGES` });
    expect(option.querySelector("bdi")?.textContent).toBe(WAGES);
    expect(
      screen.getByRole("option", { name: "Payroll PAY" }).querySelector("bdi")
        ?.textContent,
    ).toBe("Payroll");
  });
});
