import { ThemeProvider } from "@mui/material/styles";
import { render, screen, waitFor, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import { theme } from "../../lib/theme/theme";
import { requestsTo, serving, type Reply } from "../../testutil/answering";
import { deferred } from "../../testutil/deferred";
import { CurrencyPicker } from "./CurrencyPicker";

const GROUP = "00000003-0000-4000-8000-000000000e61";

const CURRENCY = `/api/groups/${GROUP}/currency`;

const READ = `GET ${CURRENCY}`;

const CHOOSE = `PUT ${CURRENCY}`;

const NO_LONGER_PRICED = "No model is priced in it any longer.";

function choice(body: object): readonly [string, number] {
  return [JSON.stringify(body), 200];
}

function refusal(code: string, status: number): readonly [string, number] {
  return [JSON.stringify({ code }), status];
}

/** The picker as the page draws it, with only the server stood in for; what it tells the page is counted. */
function opening(routes: Readonly<Record<string, readonly Reply[]>>) {
  const sent = serving(routes);
  const onChosen = vi.fn();
  const onMoved = vi.fn();
  render(
    <ThemeProvider theme={theme}>
      <CurrencyPicker groupId={GROUP} onChosen={onChosen} onMoved={onMoved} />
    </ThemeProvider>,
  );
  return { sent, onChosen, onMoved };
}

function picker(): Promise<HTMLElement> {
  return screen.findByRole("combobox", { name: "Currency" });
}

/** Every option the open list offers, by what it says. */
async function offeredOn(select: HTMLElement): Promise<string[]> {
  await userEvent.click(select);
  const list = await screen.findByRole("listbox");
  return within(list)
    .getAllByRole("option")
    .map((option) => option.textContent ?? "");
}

async function picking(select: HTMLElement, words: string) {
  await userEvent.click(select);
  await userEvent.click(await screen.findByRole("option", { name: words }));
}

describe("CurrencyPicker", () => {
  it("offers each currency priced, by its code and its name in the reader's language, with the one chosen shown and nothing to unchoose it", async () => {
    opening({ [READ]: [choice({ chosen: "EUR", offered: ["EUR", "JPY"] })] });

    const select = await picker();

    expect(select).toHaveTextContent("EUR — Euro");
    expect(await offeredOn(select)).toEqual([
      "EUR — Euro",
      "JPY — Japanese Yen",
    ]);
    expect(screen.getByRole("option", { name: "EUR — Euro" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    expect(screen.queryByText(NO_LONGER_PRICED)).toBeNull();
  });

  it("says none is chosen before one is, selecting nothing and offering no choice of none", async () => {
    opening({ [READ]: [choice({ offered: ["EUR", "JPY"] })] });

    const select = await picker();

    expect(select).toHaveTextContent("None chosen");
    expect(await offeredOn(select)).toEqual([
      "EUR — Euro",
      "JPY — Japanese Yen",
    ]);
    expect(
      screen
        .getAllByRole("option")
        .filter((option) => option.getAttribute("aria-selected") === "true"),
    ).toEqual([]);
  });

  it("names a currency by its code alone where the reader's language has no name for it", async () => {
    opening({ [READ]: [choice({ chosen: "QQQ", offered: ["EUR", "QQQ"] })] });

    const select = await picker();

    expect(select).toHaveTextContent(/^QQQ$/);
    expect(await offeredOn(select)).toEqual(["EUR — Euro", "QQQ"]);
  });

  it.each([
    [
      "the one chosen, named as the choice is",
      { chosen: "EUR" },
      "Currency: EUR — Euro",
    ],
    ["that none is chosen", {}, "Currency: None chosen"],
  ])(
    "shows a reader offered nothing %s in words, and draws nothing to choose with",
    async (_case, body, words) => {
      opening({ [READ]: [choice(body)] });

      expect(await screen.findByText(words)).toBeInTheDocument();
      expect(screen.queryByRole("combobox")).toBeNull();
      expect(screen.queryByText(NO_LONGER_PRICED)).toBeNull();
    },
  );

  it("shows a currency chosen that no model is priced in now as the choice still, says so, and offers only the ones priced", async () => {
    const warned = vi.spyOn(console, "warn");
    opening({ [READ]: [choice({ chosen: "GBP", offered: ["EUR", "JPY"] })] });

    const select = await picker();

    expect(select).toHaveTextContent("GBP — British Pound");
    expect(select).toHaveAccessibleDescription(NO_LONGER_PRICED);
    expect(await offeredOn(select)).toEqual([
      "EUR — Euro",
      "JPY — Japanese Yen",
    ]);
    expect(warned).not.toHaveBeenCalled();
  });

  it("says a currency chosen is no longer priced where nothing at all is priced, and draws nothing to choose with", async () => {
    opening({ [READ]: [choice({ chosen: "GBP", offered: [] })] });

    expect(
      await screen.findByText("Currency: GBP — British Pound"),
    ).toBeInTheDocument();
    expect(screen.getByText(NO_LONGER_PRICED)).toBeInTheDocument();
    expect(screen.queryByRole("combobox")).toBeNull();
  });

  it("saves a currency as it is chosen, sending its code alone", async () => {
    const { sent } = opening({
      [READ]: [choice({ chosen: "GBP", offered: ["EUR", "JPY"] })],
      [CHOOSE]: [choice({ chosen: "JPY", offered: ["EUR", "JPY"] })],
    });

    const select = await picker();
    await picking(select, "JPY — Japanese Yen");

    await waitFor(() =>
      expect(requestsTo(sent)).toEqual([`GET ${CURRENCY}`, `PUT ${CURRENCY}`]),
    );
    expect(JSON.parse(String(sent.mock.calls[1]![1]?.body))).toEqual({
      currency: "JPY",
    });
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("shows a currency saved as the one chosen, no longer saying the one before is unpriced", async () => {
    opening({
      [READ]: [choice({ chosen: "GBP", offered: ["EUR", "JPY"] })],
      [CHOOSE]: [choice({ chosen: "JPY", offered: ["EUR", "JPY"] })],
    });

    const select = await picker();
    await picking(select, "JPY — Japanese Yen");

    await waitFor(() => expect(select).toHaveTextContent("JPY — Japanese Yen"));
    expect(screen.queryByText(NO_LONGER_PRICED)).toBeNull();
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("tells the page what was chosen once it is saved, and nothing of the reader's standing", async () => {
    const { onChosen, onMoved } = opening({
      [READ]: [choice({ chosen: "GBP", offered: ["EUR", "JPY"] })],
      [CHOOSE]: [choice({ chosen: "JPY", offered: ["EUR", "JPY"] })],
    });

    await picking(await picker(), "JPY — Japanese Yen");

    await waitFor(() =>
      expect(onChosen.mock.calls).toEqual([["JPY — Japanese Yen"]]),
    );
    expect(onMoved).not.toHaveBeenCalled();
  });

  /** A choice cannot be taken back, so a key pressed in passing must not make one. */
  it("chooses nothing for a letter typed on the closed list, and sends nothing", async () => {
    const { sent, onChosen } = opening({
      [READ]: [choice({ chosen: "EUR", offered: ["EUR", "JPY"] })],
      [CHOOSE]: [choice({ chosen: "JPY", offered: ["EUR", "JPY"] })],
    });
    const select = await picker();
    select.focus();

    await userEvent.keyboard("j");

    expect(select).toHaveTextContent("EUR — Euro");
    expect(select).toHaveAttribute("aria-expanded", "false");
    expect(requestsTo(sent)).toEqual([`GET ${CURRENCY}`]);
    expect(onChosen).not.toHaveBeenCalled();
  });

  it("opens on Space and saves what the keyboard chooses in the opened list", async () => {
    const { sent, onChosen } = opening({
      [READ]: [choice({ chosen: "EUR", offered: ["EUR", "JPY"] })],
      [CHOOSE]: [choice({ chosen: "JPY", offered: ["EUR", "JPY"] })],
    });
    const select = await picker();
    select.focus();

    await userEvent.keyboard(" ");
    await screen.findByRole("listbox");
    await userEvent.keyboard("{ArrowDown}{Enter}");

    await waitFor(() => expect(select).toHaveTextContent("JPY — Japanese Yen"));
    expect(requestsTo(sent)).toEqual([`GET ${CURRENCY}`, `PUT ${CURRENCY}`]);
    expect(onChosen.mock.calls).toEqual([["JPY — Japanese Yen"]]);
  });

  it("holds the choice while one is being saved, and lets it go once it is", async () => {
    const saving = deferred<readonly [string, number]>();
    opening({
      [READ]: [choice({ chosen: "EUR", offered: ["EUR", "JPY"] })],
      [CHOOSE]: [saving.promise],
    });

    const select = await picker();
    await picking(select, "JPY — Japanese Yen");
    const whileSaving = select.getAttribute("aria-readonly");
    saving.settle(choice({ chosen: "JPY", offered: ["EUR", "JPY"] }));

    await waitFor(() => expect(select).not.toHaveAttribute("aria-readonly"));
    expect(whileSaving).toBe("true");
    expect(select).toHaveTextContent("JPY — Japanese Yen");
  });

  it("says a currency no longer priced was refused beside the choice, and tells the page nothing", async () => {
    const { onChosen, onMoved } = opening({
      [READ]: [
        choice({ chosen: "EUR", offered: ["EUR", "JPY"] }),
        choice({ chosen: "EUR", offered: ["EUR"] }),
      ],
      [CHOOSE]: [refusal("CURRENCY_NOT_PRICED", 400)],
    });

    await picking(await picker(), "JPY — Japanese Yen");

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "No model is priced in that currency.",
    );
    expect(onChosen).not.toHaveBeenCalled();
    expect(onMoved).not.toHaveBeenCalled();
  });

  it("reads what is offered again once a currency is refused as no longer priced, keeping the one chosen", async () => {
    const { sent, onChosen } = opening({
      [READ]: [
        choice({ chosen: "EUR", offered: ["EUR", "JPY"] }),
        choice({ chosen: "EUR", offered: ["EUR"] }),
      ],
      [CHOOSE]: [refusal("CURRENCY_NOT_PRICED", 400)],
    });

    await picking(await picker(), "JPY — Japanese Yen");

    await waitFor(() =>
      expect(requestsTo(sent)).toEqual([
        `GET ${CURRENCY}`,
        `PUT ${CURRENCY}`,
        `GET ${CURRENCY}`,
      ]),
    );
    const again = await picker();
    expect(again).toHaveTextContent("EUR — Euro");
    expect(await offeredOn(again)).toEqual(["EUR — Euro"]);
    expect(onChosen).not.toHaveBeenCalled();
  });

  it("says the rule a refusal of the change met, keeps the one chosen, and reads nothing again", async () => {
    const { sent, onChosen } = opening({
      [READ]: [choice({ chosen: "EUR", offered: ["EUR", "JPY"] })],
      [CHOOSE]: [refusal("ACT_NOT_PERMITTED", 403)],
    });

    const select = await picker();
    await picking(select, "JPY — Japanese Yen");

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "A group's membership is changed only by a role in it that may change it.",
    );
    expect(select).toHaveTextContent("EUR — Euro");
    expect(onChosen).not.toHaveBeenCalled();
    expect(requestsTo(sent)).toEqual([`GET ${CURRENCY}`, `PUT ${CURRENCY}`]);
  });

  it("tells the page the reader's standing has moved where the change is refused by a rule, and nothing chosen", async () => {
    const { onChosen, onMoved } = opening({
      [READ]: [choice({ chosen: "EUR", offered: ["EUR", "JPY"] })],
      [CHOOSE]: [refusal("ACT_NOT_PERMITTED", 403)],
    });

    await picking(await picker(), "JPY — Japanese Yen");

    await waitFor(() => expect(onMoved).toHaveBeenCalledTimes(1));
    expect(onChosen).not.toHaveBeenCalled();
  });

  it("says the rule a refused read met in place of any currency", async () => {
    const { onMoved } = opening({
      [READ]: [refusal("ACT_NOT_PERMITTED", 403)],
    });

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "A group's members are seen only by a role in it that may see them.",
    );
    expect(screen.queryByRole("combobox")).toBeNull();
    expect(screen.queryByText(/^Currency:/)).toBeNull();
    expect(onMoved).not.toHaveBeenCalled();
  });
});
