import { ThemeProvider } from "@mui/material/styles";
import { render, screen, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import type { ReactNode } from "react";
import { MemoryRouter } from "react-router";
import { describe, expect, it } from "vitest";

import type { Version } from "../../api/groups/{groupId}/{kind}";
import { DEPTH_SX } from "../../lib/layout/parts";
import { theme } from "../../lib/theme/theme";
import { drawnAtDepth, drawnOf } from "../../testutil/depths";
import { Versions } from "./Versions";

const AVO = { userId: "000d51", displayName: "Avo Lumerace" };

function version(
  number: number,
  standing: string,
  over: Partial<Version> = {},
): Version {
  return {
    versionName: null,
    versionId: `00000007-0000-4000-8000-00000000d${String(number).padStart(3, "0")}`,
    number,
    standing,
    writers: [AVO],
    writtenByMigration: false,
    stateActs: new Set(),
    pinnedBy: [],
    ...over,
  };
}

const DRAFT = version(4, "draft");

const RETIRED_SINCE = version(3, "retired", { approval: { approver: AVO } });

const IN_SERVICE = version(2, "in_service", { approval: { approver: AVO } });

const RETIRED = version(1, "retired", { approval: { approver: AVO } });

/** Newest first, as the server sends them: a newer version retired while an older one still serves. */
const SENT = [DRAFT, RETIRED_SINCE, IN_SERVICE, RETIRED];

const NEWEST_FIRST = ["Version 4", "Version 3", "Version 2", "Version 1"];

function themed({ children }: { readonly children: ReactNode }) {
  return (
    <MemoryRouter>
      <ThemeProvider theme={theme}>{children}</ThemeProvider>
    </MemoryRouter>
  );
}

/** A fresh element on every call: a rerender handed the same element back draws nothing again. */
function versionsOf(
  versions: readonly Version[] = SENT,
  open: Version | null = IN_SERVICE,
) {
  return (
    <Versions
      sx={DEPTH_SX[1]}
      versions={versions}
      open={open ?? undefined}
      hrefOf={(each) => `/entry?version=${each.versionId}`}
      headingRef={() => {}}
      actions={<button type="button">New draft</button>}
    >
      <p>Said under the heading</p>
    </Versions>
  );
}

function part(): HTMLElement {
  return screen.getByRole("region", { name: "Versions" });
}

describe("Versions", () => {
  it("draws optional names on the next line inside actual newest-first links", () => {
    const named = version(4, "draft", { versionName: "Night-side only" });
    render(versionsOf([IN_SERVICE, named]), { wrapper: themed });
    const list = screen.getByRole("list", { name: "Versions" });
    expect(list.tagName).toBe("OL");
    expect(within(list).getAllByRole("listitem")).toHaveLength(2);
    const links = within(list).getAllByRole("link");
    expect(links.map((link) => link.getAttribute("href"))).toEqual([
      `/entry?version=${named.versionId}`,
      `/entry?version=${IN_SERVICE.versionId}`,
    ]);
    const name = within(links[0]!).getByText("Night-side only");
    expect(name.tagName).toBe("BDI");
    expect(getComputedStyle(name).gridColumn).toBe("1/-1");
    expect(within(links[1]!).queryByText("Night-side only")).toBeNull();
    expect(screen.queryByRole("table")).toBeNull();
    expect(screen.queryByRole("columnheader")).toBeNull();
    expect(screen.queryByRole("textbox")).toBeNull();
  });
  it("keeps a single version readable without a separate disclosure or closed summary", () => {
    render(versionsOf([IN_SERVICE], IN_SERVICE), { wrapper: themed });
    expect(
      screen.getByRole("link", { name: "Version 2 In service" }),
    ).toHaveAttribute("aria-current", "true");
    expect(screen.queryByRole("button", { name: "Versions" })).toBeNull();
    expect(screen.queryByText("Version 2 \u00b7 In service")).toBeNull();
  });
  it("updates only the selected version's current mark when selection changes", () => {
    const { rerender } = render(versionsOf(), { wrapper: themed });
    rerender(versionsOf(SENT, DRAFT));
    const links = within(part()).getAllByRole("link");
    expect(links.map((link) => link.getAttribute("aria-current"))).toEqual([
      "true",
      null,
      null,
      null,
    ]);
    expect(links.map((link) => link.firstElementChild!.textContent)).toEqual(
      NEWEST_FIRST,
    );
  });
  it("heads its actual list and accepts script focus on the heading", () => {
    render(versionsOf(), { wrapper: themed });
    const heading = screen.getByRole("heading", { level: 2, name: "Versions" });
    expect(screen.getByRole("list")).toHaveAttribute(
      "aria-labelledby",
      heading.id,
    );
    heading.focus();
    expect(heading).toHaveFocus();
  });
  it("frames itself as a part of the page", () => {
    render(versionsOf(), { wrapper: themed });
    expect(drawnOf(part())).toEqual(drawnAtDepth(1));
  });
  it("keeps actions beside the heading and refusals before the list", async () => {
    render(versionsOf(), { wrapper: themed });
    const heading = screen.getByRole("heading", { name: "Versions" });
    const action = screen.getByRole("button", { name: "New draft" });
    const said = screen.getByText("Said under the heading");
    expect(action.parentElement).toBe(heading.parentElement);
    expect(heading.contains(action)).toBe(false);
    expect(
      heading.parentElement!.compareDocumentPosition(said) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).not.toBe(0);
    expect(
      said.compareDocumentPosition(screen.getByRole("list")) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).not.toBe(0);
    await userEvent.click(action);
    expect(action).toHaveFocus();
    expect(screen.getAllByRole("link")).toHaveLength(4);
  });
  it("marks the open link and preserves every actual version address", () => {
    render(versionsOf(), { wrapper: themed });
    expect(
      within(part())
        .getAllByRole("link")
        .map((link) => [
          link.firstElementChild!.textContent,
          link.getAttribute("href"),
          link.getAttribute("aria-current"),
        ]),
    ).toEqual([
      ["Version 4", `/entry?version=${DRAFT.versionId}`, null],
      ["Version 3", `/entry?version=${RETIRED_SINCE.versionId}`, null],
      ["Version 2", `/entry?version=${IN_SERVICE.versionId}`, "true"],
      ["Version 1", `/entry?version=${RETIRED.versionId}`, null],
    ]);
  });
  it("uses the prose face for version labels rather than the key face", () => {
    render(versionsOf(), { wrapper: themed });
    expect(
      screen
        .getAllByRole("link")
        .map((link) => getComputedStyle(link).fontFamily),
    ).toEqual(SENT.map(() => theme.typography.fontFamily));
  });
  it("reorders shuffled input newest first and leaves the supplied array unchanged", () => {
    const handed = [RETIRED, IN_SERVICE, DRAFT, RETIRED_SINCE];
    const before = [...handed];
    render(versionsOf(handed), { wrapper: themed });
    expect(
      screen
        .getAllByRole("link")
        .map((link) => link.firstElementChild!.textContent),
    ).toEqual(NEWEST_FIRST);
    expect(handed).toEqual(before);
    expect(screen.queryByRole("button", { name: "Standing" })).toBeNull();
  });
  it.each(["draft", "submitted", "in_service", "retired", "archived"])(
    "keeps the full %s standing in the real link",
    (standing) => {
      render(versionsOf([version(2, standing)]), { wrapper: themed });
      const words = {
        draft: "Draft",
        submitted: "Submitted",
        in_service: "In service",
        retired: "Retired",
        archived: "archived",
      }[standing]!;
      const link = screen.getByRole("link", { name: `Version 2 ${words}` });
      expect(within(link).getByText(words)).toBeVisible();
      expect(link.getAttribute("href")).toBe(
        `/entry?version=${IN_SERVICE.versionId}`,
      );
    },
  );
  it("remounts another entry with its own current mark and newest-first list", () => {
    const { unmount } = render(versionsOf(SENT, DRAFT), { wrapper: themed });
    unmount();
    render(versionsOf([IN_SERVICE, RETIRED], RETIRED), { wrapper: themed });
    expect(
      screen
        .getAllByRole("link")
        .map((link) => link.getAttribute("aria-current")),
    ).toEqual([null, "true"]);
    expect(
      screen
        .getAllByRole("link")
        .map((link) => link.firstElementChild!.textContent),
    ).toEqual(["Version 2", "Version 1"]);
  });
  it("says when no version exists, keeps the actions, and draws no invented link", () => {
    render(versionsOf([], null), { wrapper: themed });
    expect(screen.getByText("It has no version yet.")).toBeVisible();
    expect(screen.queryByRole("list")).toBeNull();
    expect(screen.queryAllByRole("link")).toEqual([]);
    expect(screen.getByRole("button", { name: "New draft" })).toBeVisible();
    expect(screen.getByText("Said under the heading")).toBeVisible();
  });
});
