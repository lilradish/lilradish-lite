import { ThemeProvider } from "@mui/material/styles";
import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { useState, type ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { RequestFailed } from "../../api/problem";
import { deferred } from "../../testutil/deferred";
import { ActButton } from "../action/ActButton";
import { noticesIn } from "../../testutil/notices";
import type { Reason } from "../action/Press";
import { useAction } from "../request/useAction";
import { theme } from "../theme/theme";
import { FindOne, type Found } from "./FindOne";

/** How long typing has to pause before what was typed is sent. */
const QUIET_MS = 300;

/** `theme.palette.text.secondary`, as a resolved colour is reported back. */
const SECONDARY_COLOUR = "rgb(89, 89, 89)";

interface Row {
  readonly id: string;
  readonly name: string;
  readonly here: boolean;
}

const ADA: Row = { id: "u1", name: "Ada Lovelace", here: false };
const ADAM: Row = { id: "u2", name: "Adam Smith", here: false };
const ADAH: Row = { id: "u3", name: "Adah Menken", here: true };

const EMPTY = "Nothing matches that.";

const NOT_OFFERED = "Not offered here.";

const PICK_FIRST: Reason = { severity: "info", words: "Pick one first." };

function themed({ children }: { readonly children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

/** The words of every information notice inside `container`. */
function infoNotices(container: HTMLElement): string[] {
  return noticesIn(container)
    .filter((notice) => notice.severity === "info")
    .map((notice) => notice.words);
}

function notHere(row: Row): string | null {
  return row.here ? NOT_OFFERED : null;
}

type Answer = ReturnType<typeof deferred<Found<Row>>>;

interface Options {
  readonly whyNot?: (row: Row) => string | null;
  readonly title?: (row: Row) => ReactNode;
  readonly autoFocus?: boolean;
}

/**
 * The search as a dialog holds one: the pick is the caller's, and so is the
 * act it is picked for, run through the real action and refused every time.
 * Each search is held until the test answers it.
 */
function finding({
  whyNot,
  title = (row) => row.name,
  autoFocus,
}: Options = {}) {
  const searches: { typed: string; signal: AbortSignal; answer: Answer }[] = [];
  const search = (typed: string, signal: AbortSignal) => {
    const answer = deferred<Found<Row>>();
    searches.push({ typed, signal, answer });
    return answer.promise;
  };
  const picks: (Row | null)[] = [];

  function Choosing() {
    const [picked, setPicked] = useState<Row | null>(null);
    const confirming = useAction<never>(() => {});
    return (
      <>
        <FindOne
          label="Find"
          search={search}
          keyOf={(row) => row.id}
          title={title}
          whyNot={whyNot}
          picked={picked}
          onPick={(row) => {
            picks.push(row);
            setPicked(row);
          }}
          empty={EMPTY}
          refusal={(problem) => (
            <p role="alert">Search refused: {problem.code}</p>
          )}
          autoFocus={autoFocus}
        />
        {confirming.problem === null ? null : (
          <p role="alert">Confirming refused: {confirming.problem.code}</p>
        )}
        <ActButton
          action={confirming}
          act={() =>
            Promise.reject(
              new RequestFailed({ status: 409, code: "OTHER_REFUSAL" }),
            )
          }
          reason={picked === null ? PICK_FIRST : undefined}
        >
          Confirm
        </ActButton>
      </>
    );
  }

  render(<Choosing />, { wrapper: themed });
  return {
    box: screen.getByRole<HTMLInputElement>("searchbox", { name: "Find" }),
    searches,
    picks,
    async answer(found: Found<Row>) {
      await act(async () => searches.at(-1)!.answer.settle(found));
    },
    async refuse() {
      await act(async () =>
        searches
          .at(-1)!
          .answer.refuse(
            new RequestFailed({ status: 400, code: "SOME_REFUSAL" }),
          ),
      );
    },
  };
}

function type(box: HTMLInputElement, text: string) {
  fireEvent.change(box, { target: { value: text } });
}

function wait(ms: number) {
  act(() => {
    vi.advanceTimersByTime(ms);
  });
}

async function searchedFor(text: string, found: Found<Row>, options?: Options) {
  const search = finding(options);
  type(search.box, text);
  wait(QUIET_MS);
  await search.answer(found);
  return search;
}

function status(): string {
  return screen.getByRole("status").textContent ?? "";
}

function radio(name: string): HTMLInputElement {
  return screen.getByRole<HTMLInputElement>("radio", { name });
}

/**
 * Only the two timer functions are faked, and nothing here waits on the
 * testing library, for the reason `useTypingPause.test.tsx` gives.
 */
beforeEach(() => {
  vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
});

afterEach(() => {
  vi.useRealTimers();
});

/**
 * Focus left on an element a spec has since removed is kept by this
 * environment as the document itself, which the next focus then reports as
 * where it came from. Focusing and blurring an element clears that.
 */
beforeEach(() => {
  const reset = document.createElement("button");
  document.body.append(reset);
  reset.focus();
  reset.blur();
  reset.remove();
});

describe("FindOne", () => {
  it("asks for nothing, offers nothing and says nothing until something is typed", async () => {
    const { searches } = finding();

    await act(async () => {});

    expect(searches).toEqual([]);
    expect(status()).toBe("");
    expect(screen.queryByRole("radiogroup")).toBeNull();
    expect(screen.queryByRole("list")).toBeNull();
  });

  /** When a pause ends is `useTypingPause`'s to say; here, only that the search waits for one. */
  it("asks only once typing has paused", () => {
    const { box, searches } = finding();
    type(box, "a");
    wait(QUIET_MS - 100);
    type(box, "ad");

    wait(QUIET_MS - 1);
    const beforeThePause = searches.length;
    wait(1);

    expect(beforeThePause).toBe(0);
    expect(searches.map((each) => each.typed)).toEqual(["ad"]);
  });

  /** The server's own test for nothing typed is this one, and a second test would be a second answer. */
  it.each([
    ["one space", " "],
    ["a no-break space", String.fromCodePoint(0xa0)],
    [
      "an em space and an ideographic space",
      String.fromCodePoint(0x2003, 0x3000),
    ],
  ])(
    "takes %s alone for nothing typed, and asks for nothing",
    async (_case, text) => {
      const { box, searches } = finding();

      type(box, text);
      wait(QUIET_MS);
      await act(async () => {});

      expect(box).toHaveValue(text);
      expect(searches).toEqual([]);
      expect(status()).toBe("");
    },
  );

  /** Which part of it matches what is the server's to say, so none of it is taken away here. */
  it.each([
    ["with the spaces round it", " ada "],
    ["a tab, which is not a space separator", String.fromCodePoint(0x09)],
    [
      "a zero-width no-break space, which trimming would take",
      String.fromCodePoint(0xfeff),
    ],
  ])("sends what was typed as it stands, %s", (_case, text) => {
    const { box, searches } = finding();

    type(box, text);
    wait(QUIET_MS);

    expect(searches.map((each) => each.typed)).toEqual([text]);
  });

  it("abandons a search still out once a different one is sent", () => {
    const { box, searches } = finding();
    type(box, "ad");
    wait(QUIET_MS);

    type(box, "ada");
    wait(QUIET_MS);

    expect(searches.map((each) => each.typed)).toEqual(["ad", "ada"]);
    expect(searches[0]!.signal.aborted).toBe(true);
    expect(searches[1]!.signal.aborted).toBe(false);
  });

  /** A refusal may have been passing, and typing the same again is how a reader asks again. */
  it("asks again when the search sent is the one that was refused", async () => {
    const { box, searches, refuse, answer } = finding();
    type(box, "ad");
    wait(QUIET_MS);
    await refuse();

    type(box, "adx");
    type(box, "ad");
    wait(QUIET_MS);
    const whileAskedAgain = status();
    const refusalWhileAskedAgain = screen.queryByRole("alert");
    await answer({ items: [ADA], more: false });

    expect(searches.map((each) => each.typed)).toEqual(["ad", "ad"]);
    expect(whileAskedAgain).toBe("Still reading…");
    expect(refusalWhileAskedAgain).toBeNull();
    expect(status()).toBe("1 found");
  });

  /** Typed away and back within one pause: what is sent is what was sent already. */
  it("keeps the pick, and asks nothing, when the search sent is the one already answered", async () => {
    const { box, picks, searches } = await searchedFor("ad", {
      items: [ADA],
      more: false,
    });
    fireEvent.click(radio("Ada Lovelace"));

    type(box, "ada");
    type(box, "ad");
    wait(QUIET_MS);

    expect(picks).toEqual([ADA]);
    expect(radio("Ada Lovelace").checked).toBe(true);
    expect(searches).toHaveLength(1);
  });

  /** Every keystroke drawing every match again would make typing slower the more was found. */
  it("draws none of its matches again while the reader types", async () => {
    const title = vi.fn((row: Row) => row.name);
    const rows = Array.from({ length: 20 }, (_unused, index) => ({
      id: `u${index}`,
      name: `Ada ${index}`,
      here: false,
    }));
    const { box } = await searchedFor(
      "ad",
      { items: rows, more: false },
      {
        title,
      },
    );
    const drawnSoFar = title.mock.calls.length;

    for (const text of ["ada", "ada ", "ada l", "ada lo", "ada lov"]) {
      type(box, text);
    }

    expect(drawnSoFar).toBeGreaterThanOrEqual(rows.length);
    expect(title.mock.calls.length).toBe(drawnSoFar);
    expect(box).toHaveValue("ada lov");
  });

  it("says it is still reading while a search is out", () => {
    const { box } = finding();

    type(box, "ad");
    wait(QUIET_MS);

    expect(status()).toBe("Still reading…");
    expect(screen.queryByRole("radiogroup")).toBeNull();
  });

  it.each([
    ["the caller's words where nothing was found", [], false, EMPTY],
    [
      "how many were found where all of them are shown",
      [ADA, ADAM],
      false,
      "2 found",
    ],
    [
      "that there are more, and how to reach them, where some are not shown",
      [ADA, ADAM],
      true,
      "More were found than the 2 shown. Type more to narrow them down.",
    ],
  ])("says %s", async (_case, items, more, said) => {
    await searchedFor("ad", { items, more });

    expect(status()).toBe(said);
  });

  it("says how many it found as a reading, in the secondary text colour and no notice", async () => {
    await searchedFor("ad", { items: [ADA], more: false });

    const said = within(screen.getByRole("status")).getByText("1 found");

    expect(getComputedStyle(said).color).toBe(SECONDARY_COLOUR);
    expect(infoNotices(screen.getByRole("status"))).toEqual([]);
  });

  /** Nothing found, or more than shown, asks the reader to type something else. */
  it.each([
    ["nothing was found", [], false, EMPTY],
    [
      "more were found than shown",
      [ADA],
      true,
      "More were found than the 1 shown. Type more to narrow them down.",
    ],
  ])(
    "says as an information notice, inside the region that announces it, that %s",
    async (_case, items, more, said) => {
      await searchedFor("ad", { items, more });

      expect(infoNotices(screen.getByRole("status"))).toEqual([said]);
      expect(screen.queryByRole("alert")).toBeNull();
    },
  );

  /** A region put there with its words in it is not reliably read out; one already there is. */
  it("keeps one status region in place from before anything is typed", async () => {
    const { box, answer } = finding();
    const region = screen.getByRole("status");

    type(box, "ad");
    wait(QUIET_MS);
    await answer({ items: [ADA], more: false });

    expect(screen.getByRole("status")).toBe(region);
    expect(region.textContent).toBe("1 found");
  });

  /** A refusal said beside "nothing matches" would tell the reader nothing does. */
  it("shows a refused search in the caller's words, and says nothing was found nowhere", async () => {
    const { box, refuse } = finding();
    type(box, "ad");
    wait(QUIET_MS);

    await refuse();

    expect(screen.getByRole("alert").textContent).toBe(
      "Search refused: SOME_REFUSAL",
    );
    expect(status()).toBe("");
    expect(screen.queryByText(EMPTY)).toBeNull();
    expect(screen.queryByRole("radiogroup")).toBeNull();
  });

  /** Native radios in one group: the arrow keys and the announcing are the browser's own. */
  it("offers every match as a radio in one group named for the matches, none picked", async () => {
    await searchedFor("ad", { items: [ADA, ADAM], more: false });

    const group = screen.getByRole("radiogroup", { name: "Matches" });
    const radios = within(group).getAllByRole<HTMLInputElement>("radio");

    expect(radios.map((each) => each.labels?.[0]?.textContent)).toEqual([
      "Ada Lovelace",
      "Adam Smith",
    ]);
    expect(radios.every((each) => each.type === "radio")).toBe(true);
    expect(new Set(radios.map((each) => each.name)).size).toBe(1);
    expect(radios.some((each) => each.checked)).toBe(false);
  });

  it("picks the one match chosen, and lets go of the one chosen before", async () => {
    const { picks } = await searchedFor("ad", {
      items: [ADA, ADAM],
      more: false,
    });
    fireEvent.click(radio("Ada Lovelace"));

    fireEvent.click(radio("Adam Smith"));

    expect(picks).toEqual([ADA, ADAM]);
    expect(radio("Adam Smith").checked).toBe(true);
    expect(radio("Ada Lovelace").checked).toBe(false);
  });

  it("lets go of the pick once a different search is sent, and not while it is typed", async () => {
    const { box, picks } = await searchedFor("ad", {
      items: [ADA],
      more: false,
    });
    fireEvent.click(radio("Ada Lovelace"));

    type(box, "ada");
    wait(QUIET_MS - 1);
    const whileTyped = radio("Ada Lovelace").checked;
    wait(1);

    expect(whileTyped).toBe(true);
    expect(picks).toEqual([ADA, null]);
    expect(screen.queryByRole("radio")).toBeNull();
  });

  it("takes back everything it offered once the box holds nothing again", async () => {
    const { box, picks, searches } = await searchedFor("ad", {
      items: [ADA],
      more: false,
    });
    fireEvent.click(radio("Ada Lovelace"));

    type(box, "");
    wait(QUIET_MS);
    await act(async () => {});

    expect(picks).toEqual([ADA, null]);
    expect(screen.queryByRole("radiogroup")).toBeNull();
    expect(status()).toBe("");
    expect(searches).toHaveLength(1);
  });

  /** The refusal is the reader's to act on, and the pick is what they would act with. */
  it("keeps the pick when what it was picked for is refused", async () => {
    const { picks } = await searchedFor("ad", { items: [ADA], more: false });
    fireEvent.click(radio("Ada Lovelace"));

    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Confirm" }));
    });

    expect(screen.getByRole("alert").textContent).toBe(
      "Confirming refused: OTHER_REFUSAL",
    );
    expect(radio("Ada Lovelace").checked).toBe(true);
    expect(picks).toEqual([ADA]);
  });

  /** What is and is not isolated is `isolated`'s to say; here, only that a title goes through it. */
  it("isolates a title given as words", async () => {
    await searchedFor("ad", { items: [ADA], more: false });

    const label = radio("Ada Lovelace").labels![0]!;

    expect(within(label).getByText("Ada Lovelace").tagName).toBe("BDI");
  });

  /** Not offered is no option: a disabled one would still be a stop on the way through. */
  it("says why a match is not offered, in words outside the choice", async () => {
    await searchedFor(
      "ad",
      { items: [ADA, ADAH], more: false },
      { whyNot: notHere },
    );

    const withheld = within(screen.getByRole("list")).getAllByRole("listitem");

    expect(
      within(screen.getByRole("radiogroup"))
        .getAllByRole("radio")
        .map((each) => (each as HTMLInputElement).labels?.[0]?.textContent),
    ).toEqual(["Ada Lovelace"]);
    expect(withheld.map((each) => each.textContent)).toEqual([
      `Adah Menken${NOT_OFFERED}`,
    ]);
    expect(withheld[0]!.querySelector("input, button")).toBeNull();
    expect(status()).toBe("2 found");
  });

  it("isolates the title of a match it does not offer, and says why as an information notice beside it", async () => {
    await searchedFor(
      "adah",
      { items: [ADAH], more: false },
      { whyNot: notHere },
    );

    const withheld = screen.getByRole("listitem");

    expect(within(withheld).getByText("Adah Menken").tagName).toBe("BDI");
    expect(infoNotices(withheld)).toEqual([NOT_OFFERED]);
  });

  /**
   * Stated as the attribute: this environment gives a `ul` its role whatever
   * its markers, so asking by role would pass without it.
   */
  it("names what it does not offer a list outright, its markers being taken away", async () => {
    await searchedFor(
      "ad",
      { items: [ADAH], more: false },
      { whyNot: notHere },
    );

    const withheld = screen.getByText(NOT_OFFERED).closest("ul")!;

    expect(withheld.getAttribute("role")).toBe("list");
    expect(getComputedStyle(withheld).listStyleType).toBe("none");
  });

  it("draws no choice at all where every match is withheld", async () => {
    await searchedFor(
      "adah",
      { items: [ADAH], more: false },
      { whyNot: notHere },
    );

    expect(screen.queryByRole("radiogroup")).toBeNull();
    expect(screen.queryAllByRole("radio")).toEqual([]);
    expect(screen.getByRole("listitem").textContent).toBe(
      `Adah Menken${NOT_OFFERED}`,
    );
  });

  it.each([
    ["takes focus as it appears where it is asked to", true, "searchbox"],
    ["leaves focus where it was otherwise", false, "body"],
  ])("%s", (_case, autoFocus, focused) => {
    const { box } = finding({ autoFocus });

    expect(document.activeElement).toBe(
      focused === "searchbox" ? box : document.body,
    );
  });
});
