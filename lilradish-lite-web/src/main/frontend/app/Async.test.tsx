import { ThemeProvider } from "@mui/material/styles";
import { act, render, screen } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { useState, type ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";

import { RequestFailed, type Problem } from "../api/problem";
import { useResource, type Resource } from "../lib/request/useResource";
import { theme } from "../lib/theme/theme";
import { deferred } from "../testutil/deferred";
import { noticesIn } from "../testutil/notices";
import { Async } from "./Async";

interface Gate {
  readonly id: string;
  readonly title: string;
}

const WAITING: readonly Gate[] = [
  { id: "9f1c", title: "Invoice 4417" },
  { id: "2b8e", title: "Invoice 4418" },
];

const NOTHING = "Nothing is waiting for you.";

const REFUSED: Problem = { status: 404, code: "PERSON_NOT_IN_VIEW" };

const NOT_IN_VIEW = "That person is not in the pool.";

/** `theme.palette.text.secondary`, as a resolved colour is reported back. */
const QUIET_COLOUR = "rgb(89, 89, 89)";

function themed({ children }: { children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

function read(over: Partial<Resource<readonly Gate[]>> = {}) {
  return {
    value: [] as readonly Gate[],
    problem: null,
    loading: false,
    reload: vi.fn(),
    ...over,
  };
}

function showing(
  resource: Resource<readonly Gate[]>,
  empty: (gates: readonly Gate[]) => string | null = (gates) =>
    gates.length === 0 ? NOTHING : null,
) {
  return render(
    <Async read={resource} empty={empty}>
      {(gates) => (
        <ul>
          {gates.map((gate) => (
            <li key={gate.id}>{gate.title}</li>
          ))}
        </ul>
      )}
    </Async>,
    { wrapper: themed },
  );
}

describe("Async", () => {
  /**
   * The whole reason this decides the order once rather than on every screen:
   * "nothing is waiting for you" written over a queue the server would not show
   * is a lie the reader has no way to detect.
   */
  it("reports a refusal instead of calling the queue empty", () => {
    showing(read({ problem: REFUSED }));

    expect(screen.getByText(NOT_IN_VIEW)).toBeVisible();
    expect(screen.queryByText(NOTHING)).toBeNull();
  });

  it("says a refusal of the act the read takes as that act's rule", () => {
    render(
      <Async
        read={read({ problem: { status: 403, code: "ACT_NOT_PERMITTED" } })}
        rule={{ act: "keep_pool" }}
        empty={() => null}
      >
        {() => <p>read</p>}
      </Async>,
      { wrapper: themed },
    );

    expect(screen.getByRole("alert")).toHaveTextContent(
      "The pool is seen and changed only by a role that may keep it.",
    );
    expect(screen.queryByText("read")).toBeNull();
  });

  it("says a refusal of the permission a read inside a group takes as that permission's rule", () => {
    render(
      <Async
        read={read({ problem: { status: 403, code: "ACT_NOT_PERMITTED" } })}
        rule={{ inGroup: "read_membership" }}
        empty={() => null}
      >
        {() => <p>read</p>}
      </Async>,
      { wrapper: themed },
    );

    expect(screen.getByRole("alert")).toHaveTextContent(
      "A group's members are seen only by a role in it that may see them.",
    );
    expect(screen.queryByText("read")).toBeNull();
  });

  it("says nothing about emptiness while the answer is still out", () => {
    showing(read({ loading: true }));

    expect(screen.getByText("Still reading…")).toBeVisible();
    expect(screen.queryByText(NOTHING)).toBeNull();
    expect(screen.queryAllByRole("listitem")).toEqual([]);
  });

  it("explains an empty answer rather than rendering an empty list", () => {
    const { container } = showing(read());

    expect(screen.getByText(NOTHING)).toBeVisible();
    expect(container.querySelector("ul")).toBeNull();
  });

  it("hands the value over once there is one", () => {
    showing(read({ value: WAITING }));

    expect(
      screen.getAllByRole("listitem").map((row) => row.textContent),
    ).toEqual(["Invoice 4417", "Invoice 4418"]);
    expect(screen.queryByText("Still reading…")).toBeNull();
  });

  /**
   * Nothing is the caller's word — no rows, no approved version, no attempt yet
   * — so a value this side would call full can still be an answer that came
   * back with nothing in it.
   */
  it("asks the caller whether the answer is nothing rather than counting it", () => {
    showing(read({ value: WAITING }), () => "No approved version yet.");

    expect(screen.getByText("No approved version yet.")).toBeVisible();
    expect(screen.queryAllByRole("listitem")).toEqual([]);
  });

  it("sets the line standing in for an answer apart from an answer while the read is still out", () => {
    showing(read({ loading: true }));

    expect(getComputedStyle(screen.getByText("Still reading…")).color).toBe(
      QUIET_COLOUR,
    );
  });

  /** Nothing is a hint about where to look next, drawn as any hint is, and no news to announce. */
  it("says an answer that came back with nothing as an information notice, announcing nothing", () => {
    const { container } = showing(read());

    expect(noticesIn(container)).toEqual([
      { severity: "info", words: NOTHING },
    ]);
    expect(screen.queryByRole("alert")).toBeNull();
    expect(screen.getByRole("status")).toHaveTextContent("");
  });

  /**
   * What is drawn holds the control that asked again. Swapped out while the
   * answer is out, it takes that control with it, and the keyboard with it.
   */
  it("keeps what it drew while the same read is asked again, and the keyboard on the control that asked", async () => {
    const queue = reading();
    await queue.answer(WAITING);
    const asking = screen.getByRole("button", { name: "Read again" });

    await userEvent.click(asking);
    const whileReading = document.activeElement;
    await queue.answer([WAITING[0]!]);

    expect(queue.asked()).toBe(2);
    expect(whileReading).toBe(asking);
    expect(document.activeElement).toBe(asking);
    expect(titles()).toEqual(["Invoice 4417"]);
  });

  it("says it is reading beside what it drew, in a region that stood there before it spoke", async () => {
    const queue = reading();
    await queue.answer(WAITING);
    const region = screen.getByRole("status");
    const before = region.textContent;

    await userEvent.click(screen.getByRole("button", { name: "Read again" }));

    expect(before).toBe("");
    expect(screen.getByRole("status")).toBe(region);
    expect(region).toHaveTextContent("Still reading…");
    expect(titles()).toEqual(["Invoice 4417", "Invoice 4418"]);
  });

  it("stops saying it is reading once the answer asked again for has landed", async () => {
    const queue = reading();
    await queue.answer(WAITING);
    await userEvent.click(screen.getByRole("button", { name: "Read again" }));

    await queue.answer(WAITING);

    expect(screen.getByRole("status")).toHaveTextContent("");
    expect(screen.queryByText("Still reading…")).toBeNull();
  });

  /**
   * A read that moved on is about something else, and what it drew for the
   * source it left is no answer to that.
   */
  it("draws nothing of the source it left while a new one is read, waiting as on a first read", async () => {
    const queue = reading();
    await queue.answer(WAITING);

    await userEvent.click(screen.getByRole("button", { name: "Read another" }));

    expect(screen.getByText("Still reading…")).toBeVisible();
    expect(screen.queryAllByRole("listitem")).toEqual([]);
    expect(screen.queryByRole("button", { name: "Read again" })).toBeNull();
  });

  it("puts a refusal where the rows it drew were, once the read asked again is refused", async () => {
    const queue = reading();
    await queue.answer(WAITING);
    await userEvent.click(screen.getByRole("button", { name: "Read again" }));

    await queue.refuse(REFUSED);

    expect(screen.getByText(NOT_IN_VIEW)).toBeVisible();
    expect(screen.queryAllByRole("listitem")).toEqual([]);
    expect(screen.queryByText("Still reading…")).toBeNull();
  });
});

function titles(): string[] {
  return screen.getAllByRole("listitem").map((row) => row.textContent ?? "");
}

/**
 * The real read, each answer held until the test gives it: what matters is
 * what is on screen while one is out, and a stand-in resource would show only
 * what the test chose to hand it. Two sources, so a read can move to another.
 */
function reading() {
  const answers: ReturnType<typeof deferred<readonly Gate[]>>[] = [];
  const load = () => {
    const answer = deferred<readonly Gate[]>();
    answers.push(answer);
    return answer.promise;
  };
  const other = () => load();
  function Queue() {
    const [source, setSource] = useState(() => load);
    const gates = useResource(source, []);
    return (
      <>
        <button type="button" onClick={() => setSource(() => other)}>
          Read another
        </button>
        <Async
          read={gates}
          empty={(value) => (value.length === 0 ? NOTHING : null)}
        >
          {(value) => (
            <>
              <button type="button" onClick={gates.reload}>
                Read again
              </button>
              <ul>
                {value.map((gate) => (
                  <li key={gate.id}>{gate.title}</li>
                ))}
              </ul>
            </>
          )}
        </Async>
      </>
    );
  }
  render(<Queue />, { wrapper: themed });
  return {
    asked: () => answers.length,
    async answer(gates: readonly Gate[]) {
      await act(async () => answers.at(-1)!.settle(gates));
    },
    async refuse(problem: Problem) {
      await act(async () => answers.at(-1)!.refuse(new RequestFailed(problem)));
    },
  };
}
