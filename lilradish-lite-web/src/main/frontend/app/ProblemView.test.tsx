import { ThemeProvider } from "@mui/material/styles";
import { render, screen, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import type { ReactNode } from "react";
import { describe, expect, it } from "vitest";

import { STOPPED_HERE, type Problem } from "../api/problem";
import { theme } from "../lib/theme/theme";
import { noticesIn } from "../testutil/notices";
import { ProblemView } from "./ProblemView";

const STILL_HOLDS =
  "Somebody holding an estate role cannot be taken out of the pool. Withdraw their estate roles first.";

const SERVED_DETAIL =
  "That person holds an estate role. Withdraw their estate roles first.";

const REFUSED: Problem = {
  status: 409,
  code: "PERSON_HOLDS_ESTATE_ROLES",
  detail: SERVED_DETAIL,
};

/**
 * Bean validation is the one refusal that names values to fix, and it names
 * them in three ways — a body field, a header, a query or path parameter — plus
 * a complaint about the request as a whole, which points at nothing.
 */
const REJECTED: Problem = {
  status: 400,
  code: "BAD_REQUEST",
  detail: "Invalid request content.",
  errors: [
    { detail: "must not be blank", source: { pointer: "/note" } },
    { detail: "must be present", source: { header: "X-Purpose" } },
    { detail: "must be a positive number", source: { parameter: "limit" } },
    { detail: "the window has to start before it ends" },
  ],
};

function themed({ children }: { children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

function view(problem: Problem) {
  return render(<ProblemView problem={problem} />, { wrapper: themed });
}

/**
 * By text, because `summary` has no role a query can ask for: the accessibility
 * mapping this suite computes gives it none, and `details` itself comes back as
 * a group.
 */
async function disclose() {
  await userEvent.click(screen.getByText("Technical detail"));
}

describe("ProblemView", () => {
  /**
   * The contract says nothing may branch on `detail`, which is another way of
   * saying it was not written for the person who came here to approve
   * something. It is present, and it is one disclosure away.
   */
  it("reads the refusal out as an alert and keeps the served sentence out of the way", () => {
    view(REFUSED);

    expect(
      within(screen.getByRole("alert")).getByText(STILL_HOLDS),
    ).toBeVisible();
    expect(screen.getByText(SERVED_DETAIL)).not.toBeVisible();
  });

  /**
   * `alert` is assertive and atomic, so whatever the region holds is read out
   * whole whenever any of it changes — and opening the disclosure is a change.
   * Inside the region, asking to look at the developer half would have it
   * announced at you instead.
   */
  it("puts nothing but the sentence in the live region", () => {
    view(REJECTED);

    expect(screen.getByRole("alert").textContent).toBe(
      "Some of what was sent was not accepted.",
    );
  });

  it("draws the refusal as an error notice, the one live region it has", () => {
    view(REJECTED);

    expect(noticesIn(document.body)).toEqual([
      { severity: "error", words: "Some of what was sent was not accepted." },
    ]);
    expect(screen.getAllByRole("alert")).toHaveLength(1);
    expect(screen.queryByRole("status")).toBeNull();
  });

  /** What somebody reading a log or a response body will look for. */
  it("names the members it hands over as the document names them", async () => {
    view(REFUSED);

    await disclose();

    expect(
      [...document.querySelectorAll("dt")].map((term) => term.textContent),
    ).toEqual(["code", "status", "detail"]);
  });

  it("hands the developer half over whole when it is asked for", async () => {
    view(REFUSED);

    await disclose();

    expect(screen.getByText("PERSON_HOLDS_ESTATE_ROLES")).toBeVisible();
    expect(screen.getByText("409")).toBeVisible();
    expect(screen.getByText(SERVED_DETAIL)).toBeVisible();
  });

  /**
   * The value each complaint points at is the reason a complaint is worth
   * reading at all: "must not be blank" alone names nothing to go and fix. The
   * run-together text is the separator being a margin rather than a space,
   * which is what keeps a complaint that points nowhere from being indented by
   * one.
   */
  it("names what every complaint points at, in whichever way it said so", async () => {
    view(REJECTED);

    await disclose();

    expect(
      screen.getAllByRole("listitem").map((row) => row.textContent),
    ).toEqual([
      "/notemust not be blank",
      "X-Purposemust be present",
      "limitmust be a positive number",
      "the window has to start before it ends",
    ]);
  });

  /**
   * An empty list is not nothing: it is announced as a list of no items, under
   * a heading promising complaints there are none of. `queryAllByRole` cannot
   * tell the two apart, because an empty list has no items either.
   */
  it("says nothing of the members the document did not carry", async () => {
    const { container } = view({ code: STOPPED_HERE });

    await disclose();

    expect(screen.getByText(STOPPED_HERE)).toBeVisible();
    expect(screen.queryByText("status")).toBeNull();
    expect(screen.queryByText("detail")).toBeNull();
    expect(container.querySelector("ul")).toBeNull();
  });
});
