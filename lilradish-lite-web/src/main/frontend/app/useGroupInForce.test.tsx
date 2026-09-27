import { act, renderHook } from "@testing-library/react";
import type { ReactNode } from "react";
import { MemoryRouter, useLocation, useNavigate } from "react-router";
import { describe, expect, it } from "vitest";

import type { GroupStanding } from "../api/standing";
import { inGroup } from "../testutil/standingRead";
import { useGroupInForce } from "./useGroupInForce";

const PAYROLL = inGroup(
  "00000003-0000-4000-8000-000000000961",
  "PAYROLL",
  "Payroll",
  ["read_membership"],
);
const TRIAGE = inGroup(
  "00000003-0000-4000-8000-000000000962",
  "TRIAGE",
  "Triage",
  [],
);
const FINANCE = inGroup(
  "00000003-0000-4000-8000-000000000963",
  "FINANCE",
  "Finance",
  ["read_membership"],
);

const MINE = [PAYROLL, TRIAGE, FINANCE];

const NOT_THEIRS = "00000003-0000-4000-8000-000000000969";

/** The hook at an address, with where the router is and a way to move it. */
function at(path: string, groups: readonly GroupStanding[] = MINE) {
  const { result } = renderHook(
    () => ({
      inForce: useGroupInForce(groups),
      path: useLocation().pathname,
      navigate: useNavigate(),
    }),
    {
      wrapper: ({ children }: { readonly children: ReactNode }) => (
        <MemoryRouter initialEntries={[path]}>{children}</MemoryRouter>
      ),
    },
  );
  return result;
}

describe("useGroupInForce", () => {
  it.each([
    ["the group the address names", `/groups/${TRIAGE.groupId}/work`, TRIAGE],
    [
      "the group the address names with a row picked on its page",
      `/groups/${FINANCE.groupId}/members/00000002-0000-4000-8000-000000000961`,
      FINANCE,
    ],
    [
      "the first where the address names a group the reader is not in",
      `/groups/${NOT_THEIRS}/work`,
      PAYROLL,
    ],
    ["the first away from every group's pages", "/system/people", PAYROLL],
  ])("holds %s in force", (_case, path, held) => {
    expect(at(path).current.inForce.group).toBe(held);
  });

  it("holds no group in force for a reader in none", () => {
    expect(at("/system/people", []).current.inForce.group).toBeNull();
  });

  /** The address is remembered once it has named a group, over the first. */
  it("keeps the group the address last named once the reader leaves its pages", () => {
    const hooked = at(`/groups/${FINANCE.groupId}/members`);

    act(() => {
      hooked.current.navigate("/system/people");
    });

    expect(hooked.current.path).toBe("/system/people");
    expect(hooked.current.inForce.group).toBe(FINANCE);
  });

  it("holds a group chosen away from every group's pages in force, and moves nowhere", () => {
    const hooked = at("/system/people");

    act(() => hooked.current.inForce.choose(TRIAGE));

    expect(hooked.current.inForce.group).toBe(TRIAGE);
    expect(hooked.current.path).toBe("/system/people");
  });

  it.each([
    [
      "the same page where the group chosen reaches it",
      `/groups/${PAYROLL.groupId}/members`,
      FINANCE,
      `/groups/${FINANCE.groupId}/members`,
    ],
    [
      "its first page where it does not",
      `/groups/${PAYROLL.groupId}/members`,
      TRIAGE,
      `/groups/${TRIAGE.groupId}/work`,
    ],
    [
      "the same page from a group the reader is not in",
      `/groups/${NOT_THEIRS}/workflows`,
      TRIAGE,
      `/groups/${TRIAGE.groupId}/workflows`,
    ],
    [
      "the same page, leaving behind the row picked on it",
      `/groups/${PAYROLL.groupId}/members/00000002-0000-4000-8000-000000000961`,
      FINANCE,
      `/groups/${FINANCE.groupId}/members`,
    ],
  ])("opens on a group's page %s", (_case, from, chosen, to) => {
    const hooked = at(from);

    act(() => hooked.current.inForce.choose(chosen));

    expect(hooked.current.path).toBe(to);
    expect(hooked.current.inForce.group).toBe(chosen);
  });
});
