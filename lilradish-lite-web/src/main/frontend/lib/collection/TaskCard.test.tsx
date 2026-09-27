import { ThemeProvider } from "@mui/material/styles";
import { render, screen } from "@testing-library/react";
import type { ReactNode } from "react";
import { MemoryRouter } from "react-router";
import { describe, expect, it } from "vitest";

import { theme } from "../theme/theme";
import { TaskCard, type Task } from "./TaskCard";

/** `theme.palette.error.main`, as a resolved colour is reported back. */
const URGENT_COLOUR = "rgb(140, 29, 24)";

/** `theme.palette.divider`, which `theme.ts` leaves at Material's own value. */
const RESTING_COLOUR = "rgba(0, 0, 0, 0.12)";

/**
 * Paths a browser resolves to another host, some only after dropping or reading
 * a character, or once the router has resolved `..`; and one no parser reads.
 */
const LEAVING_THE_SITE = [
  "https://elsewhere.example/x",
  "//elsewhere.example/x",
  "/\\elsewhere.example/x",
  "/\t/elsewhere.example/x",
  "/x/../\t/elsewhere.example",
  "//exa mple",
];

/**
 * The addresses are stand-ins and are deliberately no address this application
 * answers: this card is built before the screens it would lead to, and a
 * plausible-looking one here would read as a destination that exists.
 */
function waiting(count: number): Task[] {
  return Array.from({ length: count }, (_unused, index) => ({
    id: `id${index}`,
    title: `gate ${index}`,
    detail: `held since day ${index}`,
    path: `/a-screen-that-settles-one/${index}`,
  }));
}

/**
 * The router is what turns a row into an anchor; the theme is what turns a
 * palette path into a colour. `sx` is not type-checked against the property it
 * is written on, so an unresolved path is emitted verbatim as invalid CSS and
 * dropped — and only a rendered colour tells that from a working mark.
 */
function inApp({ children }: { children: ReactNode }) {
  return (
    <MemoryRouter>
      <ThemeProvider theme={theme}>{children}</ThemeProvider>
    </MemoryRouter>
  );
}

function card(over: Partial<Parameters<typeof TaskCard>[0]> = {}) {
  return render(
    <TaskCard
      heading="Past deadline"
      tasks={waiting(2)}
      loading={false}
      emptyMessage="Nothing is past its deadline."
      listScreen={{ path: "/a-screen-that-lists-them", label: "All overdue" }}
      {...over}
    />,
    { wrapper: inApp },
  );
}

function edgeColour(container: HTMLElement): string {
  const [edge] = container.children;
  return getComputedStyle(edge).borderColor;
}

describe("TaskCard", () => {
  it("says how many are waiting rather than how many it has room to show", () => {
    card({ tasks: waiting(9) });

    expect(screen.getByText("9 waiting")).toBeVisible();
    expect(screen.getAllByRole("listitem")).toHaveLength(3);
    expect(screen.queryByText("gate 8")).toBeNull();
  });

  it.each([
    [2, 2],
    [3, 3],
    [4, 3],
  ])(
    "turns %i waiting into %i rows, so the card stays a summary",
    (count, rows) => {
      card({ tasks: waiting(count) });

      expect(screen.getAllByRole("listitem")).toHaveLength(rows);
    },
  );

  /**
   * An anchor, because opening a row in a new tab, copying its address and
   * reading the destination off the status bar are all things a reader does
   * with a queue and none of them survive a button.
   */
  it("makes every row an anchor to the screen that settles it", () => {
    const { container } = card();

    const destinations = screen
      .getAllByRole("listitem")
      .map((row) => row.querySelector("a")?.getAttribute("href"));

    expect(destinations).toEqual([
      "/a-screen-that-settles-one/0",
      "/a-screen-that-settles-one/1",
    ]);
    expect(container.querySelector("button")).toBeNull();
  });

  it.each(LEAVING_THE_SITE)(
    "neither draws nor counts a task whose path %j would leave the site, and draws every other row still",
    (path) => {
      card({
        tasks: [
          ...waiting(1),
          { id: "away", title: "gate away", detail: "held", path },
        ],
      });

      expect(screen.queryByText("gate away")).toBeNull();
      expect(
        screen.getAllByRole("link").map((link) => link.getAttribute("href")),
      ).toEqual(["/a-screen-that-settles-one/0", "/a-screen-that-lists-them"]);
      expect(screen.getByText("1 waiting")).toBeVisible();
      expect(screen.queryByText("2 waiting")).toBeNull();
    },
  );

  it.each(["/x?page=2", "/x/%20y", "/x/", "/x#part", "/%09/host"])(
    "draws and counts a task whose encoded path %j stays on the site, as it was given",
    (path) => {
      card({
        tasks: [
          ...waiting(1),
          { id: "here", title: "gate here", detail: "held", path },
        ],
      });

      expect(
        screen.getAllByRole("link").map((link) => link.getAttribute("href")),
      ).toEqual([
        "/a-screen-that-settles-one/0",
        path,
        "/a-screen-that-lists-them",
      ]);
      expect(screen.getByText("2 waiting")).toBeVisible();
      expect(screen.queryByText("1 waiting")).toBeNull();
    },
  );

  /** A count over rows that are nowhere would contradict the empty card beneath it. */
  it("says nothing is waiting, under a count of none, when every task would leave the site", () => {
    card({
      tasks: [
        {
          id: "away",
          title: "gate away",
          detail: "held",
          path: "//elsewhere.example/x",
        },
      ],
      urgent: true,
    });

    expect(screen.getByText("Nothing is past its deadline.")).toBeVisible();
    expect(screen.getByText("0 waiting")).toBeVisible();
    expect(screen.queryByRole("list")).toBeNull();
    expect(screen.queryByText("1 waiting")).toBeNull();
    expect(screen.queryByText("Urgent")).toBeNull();
  });

  /**
   * "Nothing is waiting" is not "nothing to look at": the queue behind the card
   * still holds everything already dealt with, and an empty card is the only
   * door to it on this screen.
   */
  it("still offers the way through to the rest when nothing is waiting", () => {
    card({ tasks: [] });

    expect(screen.getByText("Nothing is past its deadline.")).toBeVisible();
    expect(screen.getByRole("link", { name: "All overdue" })).toHaveAttribute(
      "href",
      "/a-screen-that-lists-them",
    );
    expect(screen.queryAllByRole("listitem")).toEqual([]);
  });

  it.each(LEAVING_THE_SITE)(
    "offers no way through to a list whose path %j would leave the site, and keeps the rows",
    (path) => {
      card({ listScreen: { path, label: "All overdue" } });

      expect(screen.queryByRole("link", { name: "All overdue" })).toBeNull();
      expect(
        screen.getAllByRole("link").map((link) => link.getAttribute("href")),
      ).toEqual([
        "/a-screen-that-settles-one/0",
        "/a-screen-that-settles-one/1",
      ]);
    },
  );

  it("says it is still reading instead of counting an answer it has not got", () => {
    card({ tasks: [], loading: true });

    expect(screen.getByText("Still reading…")).toBeVisible();
    expect(screen.queryByText("0 waiting")).toBeNull();
  });

  it("counts nothing as nothing once the read is done", () => {
    card({ tasks: [] });

    expect(screen.getByText("0 waiting")).toBeVisible();
    expect(screen.queryByText("Still reading…")).toBeNull();
  });

  /**
   * A reload holds the previous answer on screen while it runs, so there is a
   * count to show for as long as there are rows it belongs to.
   */
  it("keeps the count beside the rows a reload has not replaced yet", () => {
    card({ tasks: waiting(4), loading: true });

    expect(screen.getByText("4 waiting")).toBeVisible();
    expect(screen.getAllByRole("listitem")).toHaveLength(3);
    expect(screen.queryByText("Still reading…")).toBeNull();
  });

  /**
   * WCAG 2.2 SC 1.4.1: colour may not be the only means of conveying
   * information. The edge is what carries across a wall of cards at a glance;
   * the word is what carries to everyone the edge does not reach.
   */
  it("marks an urgent card that has something in it, in colour and in words", () => {
    const { container } = card({ tasks: waiting(1), urgent: true });

    expect(edgeColour(container)).toBe(URGENT_COLOUR);
    expect(screen.getByText("Urgent")).toBeVisible();
  });

  it("raises no alarm about an urgent card with nothing in it", () => {
    const { container } = card({ tasks: [], urgent: true });

    expect(edgeColour(container)).toBe(RESTING_COLOUR);
    expect(screen.queryByText("Urgent")).toBeNull();
  });

  /**
   * The card's heading is one level below the page's, and a heading may sit
   * no more than one level below the one before it.
   */
  it("names the card with a heading one level below the page's own", () => {
    card();

    expect(screen.getByRole("heading", { name: "Past deadline" }).tagName).toBe(
      "H2",
    );
  });
});
