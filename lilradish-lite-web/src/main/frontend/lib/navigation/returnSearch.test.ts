import { renderHook } from "@testing-library/react";
import type { ReactNode } from "react";
import { createElement } from "react";
import { MemoryRouter } from "react-router";
import { describe, expect, it } from "vitest";

import { returnSearch, useReturnState } from "./returnSearch";

function atEntry(entry: { pathname: string; state?: unknown }) {
  return ({ children }: { children: ReactNode }) =>
    createElement(MemoryRouter, { initialEntries: [entry] }, children);
}

describe("returnSearch", () => {
  it("reads the search a journey carried in", () => {
    expect(returnSearch({ from: "?lens=mine&page=2" })).toBe(
      "?lens=mine&page=2",
    );
  });

  it.each([
    ["state that was never set", undefined],
    ["state carrying no journey", { scrollTo: 400 }],
    ["a journey whose search is not a string", { from: 404 }],
    ["a scalar in place of state", "?lens=mine"],
  ])("restores no filter from %s", (_case, state) => {
    expect(returnSearch(state)).toBe("");
  });
});

describe("useReturnState", () => {
  it("declares the search the screen passes in, ignoring the journey's own", () => {
    const { result } = renderHook(() => useReturnState("?lens=mine"), {
      wrapper: atEntry({
        pathname: "/system/people",
        state: { from: "?page=7" },
      }),
    });

    expect(result.current.from).toBe("?lens=mine");
  });

  it("treats a declared but empty search as an answer, not as an absence", () => {
    const { result } = renderHook(() => useReturnState(""), {
      wrapper: atEntry({
        pathname: "/system/groups",
        state: { from: "?page=7" },
      }),
    });

    expect(result.current.from).toBe("");
  });

  it("passes on the search it was reached with when the screen declares none", () => {
    const { result } = renderHook(() => useReturnState(), {
      wrapper: atEntry({
        pathname: "/system/groups/finance/members/a3f2",
        state: { from: "?lens=mine" },
      }),
    });

    expect(result.current.from).toBe("?lens=mine");
  });

  it("offers a plain return when the screen was arrived at directly", () => {
    const { result } = renderHook(() => useReturnState(), {
      wrapper: atEntry({ pathname: "/system/groups/finance/members/a3f2" }),
    });

    expect(result.current.from).toBe("");
  });
});
