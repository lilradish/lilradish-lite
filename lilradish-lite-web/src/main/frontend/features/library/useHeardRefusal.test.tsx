import { act, render, renderHook, screen } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { useRef, useState } from "react";
import { describe, expect, it, vi } from "vitest";

import type { Problem } from "../../api/problem";
import type { Action } from "../../lib/request/useAction";
import { useHeardRefusal } from "./useHeardRefusal";

const REFUSED: Problem = { status: 409, code: "DRAFT_WRITTEN_SINCE_READ" };

function acting(over: Partial<Action<string>> = {}): Action<string> {
  return { run: vi.fn(), problem: null, running: false, ...over };
}

function hearing(action: Action<string> | undefined, unsaved: boolean) {
  return renderHook(
    (props: {
      readonly action: Action<string> | undefined;
      readonly unsaved: boolean;
    }) => useHeardRefusal(props.action, props.unsaved),
    { initialProps: { action, unsaved } },
  );
}

function Part({ refusing }: { readonly refusing: boolean }) {
  const [problem, setProblem] = useState<Problem | null>(null);
  const refusalAt = useRef<HTMLDivElement>(null);
  const heard = useHeardRefusal(acting({ problem }), true, refusalAt);
  return (
    <>
      <button
        onClick={() => {
          heard.sent();
          if (refusing) setTimeout(() => setProblem(REFUSED));
        }}
      >
        Save
      </button>
      <button>Elsewhere</button>
      {heard.problem === null ? null : (
        <div ref={refusalAt} tabIndex={-1} role="alert">
          Somebody changed this draft since it was read.
        </div>
      )}
    </>
  );
}

describe("useHeardRefusal", () => {
  it("says no refusal of the action's while the part has sent no write of its own", () => {
    const { result, rerender } = hearing(acting({ problem: REFUSED }), true);
    expect(result.current.problem).toBeNull();

    rerender({ action: acting({ problem: REFUSED }), unsaved: false });

    expect(result.current.problem).toBeNull();
  });

  it("says the refusal of the part's own write once it is sent, unsettled while something is unsaved", () => {
    const { result, rerender } = hearing(acting(), true);

    act(() => result.current.sent());
    rerender({ action: acting({ problem: REFUSED }), unsaved: true });

    expect(result.current.problem).toBe(REFUSED);
    expect(result.current.settled).toBe(false);
  });

  it.each([
    [false, false, true],
    [false, true, false],
    [true, false, false],
  ])(
    "with something unsaved %s and its write out %s, counts the part settled %s",
    (unsaved, running, settled) => {
      const { result } = hearing(acting({ running }), unsaved);

      expect(result.current.settled).toBe(settled);
    },
  );

  it("counts a part handing its changes to a host settled once nothing is unsaved, saying no refusal", () => {
    const { result } = hearing(undefined, false);

    act(() => result.current.sent());

    expect(result.current.settled).toBe(true);
    expect(result.current.problem).toBeNull();
  });

  it("keeps a refusal said once nothing is unsaved, and lets it go at the next change", () => {
    const refused = acting({ problem: REFUSED });
    const { result, rerender } = hearing(refused, true);
    act(() => result.current.sent());

    rerender({ action: refused, unsaved: false });
    expect(result.current.problem).toBe(REFUSED);
    expect(result.current.settled).toBe(true);

    rerender({ action: refused, unsaved: true });
    expect(result.current.problem).toBeNull();
    expect(result.current.settled).toBe(false);
  });

  it("lets a refusal go when the reader lets go of the part, though something is still unsaved", () => {
    const refused = acting({ problem: REFUSED });
    const { result } = hearing(refused, true);
    act(() => result.current.sent());
    expect(result.current.problem).toBe(REFUSED);

    act(() => result.current.letGo());

    expect(result.current.problem).toBeNull();
  });

  it("takes the keyboard from the Save pressed to where the refusal is said", async () => {
    render(<Part refusing />);
    const save = screen.getByRole("button", { name: "Save" });

    await userEvent.click(save);
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve));
    });

    expect(document.activeElement).toBe(screen.getByRole("alert"));
    expect(save).not.toHaveFocus();
  });

  it("leaves the keyboard where the reader has taken it before the refusal arrives", async () => {
    render(<Part refusing />);
    const elsewhere = screen.getByRole("button", { name: "Elsewhere" });

    await userEvent.click(screen.getByRole("button", { name: "Save" }));
    elsewhere.focus();
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve));
    });

    expect(screen.getByRole("alert")).toBeInTheDocument();
    expect(document.activeElement).toBe(elsewhere);
  });

  it("moves the keyboard nowhere when the write sent is not refused", async () => {
    render(<Part refusing={false} />);
    const save = screen.getByRole("button", { name: "Save" });

    await userEvent.click(save);
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve));
    });

    expect(screen.queryByRole("alert")).toBeNull();
    expect(document.activeElement).toBe(save);
  });
});
