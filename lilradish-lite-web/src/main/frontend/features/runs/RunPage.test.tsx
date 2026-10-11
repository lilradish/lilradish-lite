import { buttonClasses } from "@mui/material/Button";
import { act, screen, waitFor, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { LEAST_TABLE_WIDTH } from "../../lib/collection/RowPanel";
import { theme } from "../../lib/theme/theme";
import { requestsTo } from "../../testutil/answering";
import { deferred } from "../../testutil/deferred";
import { resolved } from "../../testutil/grounds";
import { styledClassOf, writtenRules } from "../../testutil/writtenRules";

import {
  GROUP,
  RUN_ID,
  ABOVE,
  WORKFLOW,
  RAISE,
  KEY,
  PAGE,
  KEPT_DRAWING_AS,
  RUN,
  AVO,
  GLINN,
  START_RUN_RULE,
  APPROVE_ENTRY_RULE,
  SUMMARISE,
  FILE_IT,
  CLAIM,
  STOPPED,
  RAISED,
  reply,
  refusal,
  UNANSWERED,
  STEPS,
  VERSION,
  FROM_ABOVE,
  SUMMARY,
  RUN_HEADER,
  DECLARATIONS,
  SUMMARISE_ROW,
  FILE_IT_ROW,
  stepsRead,
  opening,
  opened,
  setApart,
  ceiling,
  said,
  about,
  runReads,
} from "../../testutil/runPage";

const ACROSS_THE_CARD = "calc(-1 * var(--card-side-padding))";

const THE_CARDS_SIDES = "var(--card-side-padding)";

function foldedBox(fold: HTMLElement): HTMLElement {
  return fold.closest<HTMLElement>("[data-panel-collapsed]")!;
}

function heldBy(fold: HTMLElement): HTMLElement {
  return document.getElementById(fold.getAttribute("aria-controls")!)!;
}

afterEach(() => {
  localStorage.clear();
});
describe("RunPage", () => {
  it.each(
    (["fullDetail", "conversation"] as const).flatMap((drawing) =>
      [null, "اسم 📦"].map((versionName) => ({ drawing, versionName })),
    ),
  )(
    "reads the workflow's current $versionName from HTTP in $drawing without changing its address or number",
    async ({ drawing, versionName }) => {
      const { sent, router } = opening(
        {
          [`GET ${RUN}`]: [
            reply({ ...CLAIM, workflow: { ...CLAIM.workflow, versionName } }),
          ],
        },
        PAGE,
        drawing,
      );
      await opened();
      await waitFor(() =>
        expect(said().Workflow).toBe(
          `Handle a claim \u00b7 Version 3` +
            (versionName === null ? "" : ` · ${versionName}`),
        ),
      );
      const link = within(about()).getByRole("link", {
        name: /Handle a claim/,
      });
      expect(link).toHaveAttribute(
        "href",
        `/groups/${KEY}/workflows/${WORKFLOW}`,
      );
      expect(link.querySelectorAll("bdi")).toHaveLength(
        versionName === null ? 1 : 2,
      );
      expect(link.querySelector("bdi")).toHaveTextContent("Handle a claim");
      expect(link.querySelector("bdi")).toHaveAttribute("dir", "auto");
      if (versionName !== null) {
        expect(link.querySelector("bdi ~ bdi")).toHaveTextContent(versionName);
        expect(link.querySelector("bdi ~ bdi")).toHaveAttribute("dir", "auto");
      }
      expect(said().Number).toBe("7");
      expect(router.state.location.pathname).toBe(PAGE);
      expect(requestsTo(sent)).toEqual([`GET ${RUN}`, `GET ${STEPS}`]);
    },
  );

  it.each(
    [
      ["de-DE", 1234, "1.234"],
      ["de-DE", -1234, "-1.234"],
      ["de-DE", -0, "0"],
      ["de-DE", Number.MAX_SAFE_INTEGER, "9.007.199.254.740.991"],
      ["de-DE", Number.MIN_SAFE_INTEGER, "-9.007.199.254.740.991"],
      ["fr-FR", 1234, "1\u202f234"],
      ["fr-FR", -1234, "-1\u202f234"],
      ["fr-FR", -0, "0"],
      [
        "fr-FR",
        Number.MAX_SAFE_INTEGER,
        "9\u202f007\u202f199\u202f254\u202f740\u202f991",
      ],
      [
        "fr-FR",
        Number.MIN_SAFE_INTEGER,
        "-9\u202f007\u202f199\u202f254\u202f740\u202f991",
      ],
    ].flatMap(([locale, number, expected]) =>
      (["fullDetail", "conversation"] as const).map((drawing) => ({
        locale: locale as string,
        number: number as number,
        expected: expected as string,
        drawing,
      })),
    ),
  )(
    "shows the exact number $number in $locale in $drawing's overview",
    async ({ locale, number, expected, drawing }) => {
      const language = Object.getOwnPropertyDescriptor(navigator, "language");
      Object.defineProperty(navigator, "language", {
        configurable: true,
        value: locale,
      });
      vi.resetModules();
      const { cleanup } = await import("@testing-library/react");
      try {
        const page = await import("../../testutil/runPage");
        const { runFrom } =
          await import("../../api/groups/{groupId}/runs/{runId}");
        const run = { ...page.CLAIM, number };
        const parsed = runFrom(run);
        expect(parsed).not.toBeNull();
        expect(Object.is(parsed!.number, number)).toBe(true);
        page.opening(
          { [`GET ${page.RUN}`]: [page.reply(run)] },
          page.PAGE,
          drawing,
        );
        await page.opened();
        await waitFor(() => expect(page.said().Number).toBe(expected));
      } finally {
        cleanup();
        if (language === undefined) {
          Reflect.deleteProperty(navigator, "language");
        } else {
          Object.defineProperty(navigator, "language", language);
        }
        vi.resetModules();
      }
    },
  );

  it("takes raise controls from the current reader and standing over the same pending request", async () => {
    const grants = ["start_run", "read_own_runs", "approve_entry"];
    const { sent, changeStanding } = opening(
      { [`GET ${RUN}`]: [reply(RAISED)] },
      PAGE,
      "fullDetail",
      AVO.userId,
      grants,
    );
    await opened();
    expect(
      screen.getByRole("button", { name: "Approve change" }),
    ).toBeVisible();
    expect(screen.getByRole("button", { name: "Reject change" })).toBeVisible();
    expect(
      screen.queryByRole("button", { name: "Withdraw request" }),
    ).toBeNull();

    await act(async () => changeStanding(grants, GLINN.userId));
    expect(screen.queryByRole("button", { name: "Approve change" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Reject change" })).toBeNull();
    expect(
      screen.getByRole("button", { name: "Withdraw request" }),
    ).toBeVisible();

    await act(async () => changeStanding(grants));
    for (const name of [
      "Approve change",
      "Reject change",
      "Withdraw request",
    ]) {
      expect(screen.queryByRole("button", { name })).toBeNull();
    }
    await act(async () => changeStanding(["read_own_runs"], AVO.userId));
    for (const name of [
      "Approve change",
      "Reject change",
      "Withdraw request",
      "Stop",
      `${setApart("Claim from Avo")}, editable`,
      "9,007,199,254,740,990, editable",
    ]) {
      expect(screen.queryByRole("button", { name })).toBeNull();
    }
    await act(async () => changeStanding(grants, AVO.userId));
    expect(
      screen.getByRole("button", { name: "Approve change" }),
    ).toBeVisible();
    expect(runReads(sent)).toBe(1);
    expect(
      requestsTo(sent).some((request) => !request.startsWith("GET ")),
    ).toBe(false);
  });

  /** Every count grouped as the reader groups one, and none of its digits lost on the way. */
  it("heads the page with the run's name, and says About it what ran, who started it, where it is and how far it has got, what it has cost and its ceiling", async () => {
    opening();

    expect(await opened()).toHaveTextContent(setApart("Claim from Avo"));
    const facts = said();
    expect(Object.keys(facts)).toEqual([
      "Status",
      "Number",
      "Workflow",
      "Started",
      "Usage",
    ]);
    expect(facts).toMatchObject({
      Number: "7",
      Workflow: `Handle a claim \u00b7 Version 3`,
      Status: "Running1 of 2 steps done",
      Usage:
        "9,007,199,254,741,013 in total: 9,007,199,254,740,993 sent, 20 received",
    });
    expect(
      within(about()).getByRole("progressbar", { name: "1 of 2 steps done" }),
    ).toHaveAttribute("aria-valuenow", "50");
    expect(ceiling().textContent).toBe("9,007,199,254,740,990");
    expect(facts.Started).toMatch(
      new RegExp(`^By ${setApart("Avo Lumerace")}, .+$`),
    );
    const where = within(about()).getByText("Status").nextElementSibling!;
    expect(where.querySelector("bdi")).toBeNull();
    expect(within(where as HTMLElement).queryByText(/^Now at/)).toBeNull();
    expect(
      screen.getByRole("link", { name: /Handle a claim/ }),
    ).toHaveAttribute("href", `/groups/${KEY}/workflows/${WORKFLOW}`);
  });

  it("names the run as the page's one first-level heading, the parts of its work and About it each under it at the second", async () => {
    opening();
    const title = await opened();
    await screen.findByRole("table", { name: "Steps" });

    expect(
      screen
        .getAllByRole("heading")
        .map((heading) => [heading.tagName, heading.textContent]),
    ).toEqual([
      ["H1", setApart("Claim from Avo")],
      ["H2", "Run inputs"],
      ["H2", "Result"],
      ["H3", `${setApart("Summary")} · summary`],
      ["H2", "Steps"],
      ["H2", "Overview"],
      ["H3", "Usage limit"],
    ]);
    expect(screen.getAllByRole("heading", { level: 1 })).toEqual([title]);
  });

  it.each([
    ["fullDetail", () => screen.getByRole("table", { name: "Steps" })],
    [
      "conversation",
      () => screen.getByRole("list", { name: "The conversation" }),
    ],
  ] as const)(
    "draws in %s the work first and About it after it, in a column of its own beside it",
    async (drawing, work) => {
      opening({}, PAGE, drawing);
      await opened();
      await waitFor(() => expect(work()).toBeInTheDocument());

      const aboutIt = about();
      expect(
        work().compareDocumentPosition(aboutIt) &
          Node.DOCUMENT_POSITION_FOLLOWING,
      ).toBeTruthy();
      expect(aboutIt).not.toContainElement(work());
      const aboutColumn = aboutIt;
      expect(aboutColumn.previousElementSibling).toContainElement(work());
      expect(aboutColumn.nextElementSibling).toBeNull();
      expect(getComputedStyle(aboutIt).backgroundColor).toBe(
        resolved(theme.palette.background.paper),
      );
      expect(getComputedStyle(aboutIt).boxShadow).toBe("");
      const placed = getComputedStyle(aboutIt);
      expect([
        placed.position,
        placed.top,
        placed.maxHeight,
        placed.overflowY,
      ]).toEqual(
        drawing === "conversation"
          ? ["sticky", "64px", "calc(100dvh - 80px)", "auto"]
          : ["relative", "auto", "none", "visible"],
      );
      expect(placed.alignSelf).not.toBe("stretch");
    },
  );

  it.each([
    ["fullDetail", ["Usage limit"]],
    ["conversation", ["Usage limit", "Steps so far"]],
  ] as const)(
    "leads About it in %s with its status on the facts' one grid, how far it has got under it across both columns and Stop at the facts' foot, and rules off each part after the facts across the whole card",
    async (drawing, after) => {
      opening({}, PAGE, drawing);
      await opened();
      const bar = await screen.findByRole("progressbar", {
        name: "1 of 2 steps done",
      });

      const lists = about().querySelectorAll("dl");
      expect(lists).toHaveLength(1);
      const facts = lists[0]!;
      const label = within(facts).getAllByRole("term")[0]!;
      expect(label.textContent).toBe("Status");
      expect(facts.firstElementChild).toBe(label);
      expect(label.nextElementSibling!.tagName).toBe("DD");
      const grid = getComputedStyle(facts);
      expect([
        grid.display,
        grid.gridTemplateColumns,
        grid.alignItems,
        grid.marginBottom,
      ]).toEqual(["grid", "112px minmax(0, 1fr)", "baseline", "0px"]);
      expect(writtenRules()).toMatch(
        new RegExp(`\\.${styledClassOf(facts)}\\{[^}]*gap:14px 12px;`),
      );
      const labelDrawn = getComputedStyle(label);
      const number = getComputedStyle(within(facts).getByText("7"));
      expect([
        labelDrawn.fontSize,
        labelDrawn.lineHeight,
        labelDrawn.fontWeight,
        labelDrawn.color,
        labelDrawn.overflowWrap,
      ]).toEqual(["13px", "24px", "600", "rgb(88, 102, 108)", "anywhere"]);
      expect([number.fontSize, number.lineHeight, number.fontWeight]).toEqual([
        "14px",
        "24px",
        "400",
      ]);
      const badge = label.nextElementSibling!.firstElementChild!;
      expect(badge.textContent).toBe("Running");
      expect(badge.querySelector("svg")).toHaveAttribute(
        "data-testid",
        "AutorenewIcon",
      );
      const heading = within(about()).getByRole("heading", {
        name: "Overview",
      });
      expect(
        [...heading.parentElement!.children].filter(
          (beside) => beside !== heading && !beside.contains(facts),
        ),
      ).toEqual([]);
      expect(getComputedStyle(heading).marginBottom).toBe("16px");
      expect(
        heading.closest("header")!.nextElementSibling!.firstElementChild!
          .firstElementChild!.firstElementChild,
      ).toBe(facts);
      expect(getComputedStyle(facts).marginTop).toBe("0px");

      expect([...facts.children].map((child) => child.tagName)).toEqual([
        "DT",
        "DD",
        "DD",
        "DT",
        "DD",
        "DT",
        "DD",
        "DT",
        "DD",
        "DT",
        "DD",
      ]);
      expect(Object.keys(said())).toEqual([
        "Status",
        "Number",
        "Workflow",
        "Started",
        "Usage",
      ]);
      const across = label.nextElementSibling!.nextElementSibling!;
      expect(getComputedStyle(across).gridColumn).toBe("1/-1");
      expect(across.firstElementChild).toBe(bar);
      expect(across.lastElementChild!.textContent).toBe("1 of 2 steps done");
      expect(across.children).toHaveLength(2);
      const count = getComputedStyle(across.lastElementChild!);
      expect([count.color, count.fontSize, count.lineHeight]).toEqual([
        "rgb(88, 102, 108)",
        "13px",
        "20px",
      ]);
      const foot = facts.nextElementSibling!;
      expect(
        within(foot as HTMLElement).getByRole("button", { name: "Stop" }),
      ).toBeVisible();
      expect(within(facts).queryByRole("button", { name: "Stop" })).toBeNull();
      expect(getComputedStyle(foot).marginTop).toBe("16px");
      expect(getComputedStyle(foot).borderTopStyle).not.toBe("solid");

      const parts = [...facts.parentElement!.children].slice(2);
      expect(parts.map((part) => part.firstElementChild!.textContent)).toEqual(
        after,
      );
      for (const part of parts) {
        const style = getComputedStyle(part);
        expect(style.borderTopWidth).toBe("1px");
        expect(style.borderTopStyle).toBe("solid");
        expect(style.borderTopColor).toBe("rgb(228, 235, 238)");
        expect([style.marginTop, style.paddingTop]).toEqual(["18px", "18px"]);
        expect([style.marginLeft, style.paddingLeft]).toEqual([
          ACROSS_THE_CARD,
          THE_CARDS_SIDES,
        ]);
        expect([
          style.marginRight,
          style.paddingRight,
          style.getPropertyValue("--card-side-padding"),
        ]).toEqual([ACROSS_THE_CARD, THE_CARDS_SIDES, "22px"]);
      }
      expect(getComputedStyle(facts).borderTopStyle).not.toBe("solid");
    },
  );

  it("folds About it on a page first measured narrow to a strip across before the work, named as its heading and naming the panel it opens", async () => {
    localStorage.removeItem("lilradish.panel.about");
    opening({}, PAGE, "fullDetail", undefined, undefined, 900);
    await opened();
    const work = await screen.findByRole("table", { name: "Steps" });

    const strip = screen.getByRole("button", {
      name: "Overview",
      expanded: false,
    });
    expect(strip.textContent).toBe("Overview");
    expect(getComputedStyle(strip).height).toBe("48px");
    expect(
      strip.compareDocumentPosition(work) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
    expect(screen.queryByRole("region", { name: "Overview" })).toBeNull();
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(work).toBeVisible();

    await userEvent.click(strip);

    const panel = await screen.findByRole("dialog", { name: "Overview" });
    expect(strip).toHaveAttribute("aria-expanded", "true");
    expect(document.getElementById(strip.getAttribute("aria-controls")!)).toBe(
      panel,
    );
    expect(within(panel).getByRole("button", { name: "Stop" })).toBeVisible();
    expect(work.closest('[aria-hidden="true"]')).not.toBeNull();
  });

  it.each(["Close", "Escape", "backdrop"] as const)(
    "folds About it, open over a narrow page, back to its strip by %s, with the keyboard on the strip and the reader's fold kept",
    async (shutting) => {
      localStorage.setItem("lilradish.panel.about", "expanded");
      opening({}, PAGE, "fullDetail", undefined, undefined, 900);
      const panel = await screen.findByRole("dialog", { name: "Overview" });
      expect(screen.queryByRole("heading", { level: 1 })).toBeNull();

      if (shutting === "Close")
        await userEvent.click(
          within(panel).getByRole("button", { name: "Close" }),
        );
      else if (shutting === "Escape") await userEvent.keyboard("{Escape}");
      else
        await userEvent.click(
          document.querySelector<HTMLElement>(".MuiBackdrop-root")!,
        );

      await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
      const strip = screen.getByRole("button", { name: "Overview" });
      expect(strip).toHaveAttribute("aria-expanded", "false");
      expect(strip).toHaveFocus();
      expect(screen.getByRole("table", { name: "Steps" })).toBeVisible();
      expect(localStorage.getItem("lilradish.panel.about")).toBe("collapsed");
    },
  );

  it("starts About it's facts under the drawer's ruled-off header where it lies over a narrow page, and rules its parts off across the whole drawer, inset as the drawer is", async () => {
    localStorage.setItem("lilradish.panel.about", "expanded");
    opening({}, PAGE, "conversation", undefined, undefined, 900);
    const panel = await screen.findByRole("dialog", { name: "Overview" });
    await within(panel).findByRole("navigation", { name: "Steps so far" });

    const heading = within(panel).getByRole("heading", { name: "Overview" });
    const header = heading.parentElement!;
    const body = header.nextElementSibling!;
    const facts = panel.querySelector("dl")!;
    expect(getComputedStyle(body).padding).toBe("18px 24px");
    expect([
      getComputedStyle(header).padding,
      getComputedStyle(header).borderBottomStyle,
    ]).toEqual(["18px 24px 14px", "solid"]);
    expect(getComputedStyle(heading).marginBottom).toBe("0px");
    expect(body.firstElementChild).toHaveAttribute("role", "status");
    expect(body.firstElementChild!.textContent).toBe("");
    expect(body.firstElementChild!.nextElementSibling).toContainElement(facts);
    expect(getComputedStyle(facts).marginTop).toBe("0px");
    const parts = [...facts.parentElement!.children].slice(2);
    expect(facts.nextElementSibling).toContainElement(
      within(panel).getByRole("button", { name: "Stop" }),
    );
    expect(parts.map((part) => part.firstElementChild!.textContent)).toEqual([
      "Usage limit",
      "Steps so far",
    ]);
    for (const part of parts) {
      const style = getComputedStyle(part);
      expect([
        style.marginLeft,
        style.marginRight,
        style.paddingLeft,
        style.paddingRight,
        style.getPropertyValue("--card-side-padding"),
        style.borderTopWidth,
        style.borderTopColor,
      ]).toEqual([
        ACROSS_THE_CARD,
        ACROSS_THE_CARD,
        THE_CARDS_SIDES,
        THE_CARDS_SIDES,
        "24px",
        "1px",
        "rgb(228, 235, 238)",
      ]);
    }
  });

  it.each(["conversation", "fullDetail"] as const)(
    "opens Overview beside a wide %s unfolded at its own width, everything it holds drawn",
    async (drawing) => {
      localStorage.removeItem("lilradish.panel.about");
      opening({}, PAGE, drawing);
      await opened();

      const fold = screen.getByRole("button", { name: "Overview" });
      expect(fold).toHaveAttribute("aria-expanded", "true");
      expect(getComputedStyle(foldedBox(fold).parentElement!).width).toBe(
        "360px",
      );
      expect(getComputedStyle(heldBy(fold)).display).toBe("block");
      expect(heldBy(fold)).toContainElement(about().querySelector("dl"));
    },
  );

  /** Column layout rules must not override the folded content's hidden state. */
  it.each([
    ["kept folded on this device", "kept"],
    ["folded by the reader", "pressed"],
    ["first measured narrow and then widened", "widened"],
  ] as const)(
    "draws nothing of Overview %s beside a wide conversation but its 56px rail, and everything at its own width once unfolded",
    async (_case, way) => {
      if (way === "kept")
        localStorage.setItem("lilradish.panel.about", "collapsed");
      else localStorage.removeItem("lilradish.panel.about");
      const { resize } = opening(
        {},
        PAGE,
        "conversation",
        undefined,
        undefined,
        way === "widened" ? 900 : 1400,
      );
      await opened();
      if (way === "pressed")
        await userEvent.click(screen.getByRole("button", { name: "Overview" }));
      if (way === "widened") await resize(1400);

      const fold = await screen.findByRole("button", {
        name: "Overview",
        expanded: false,
      });
      const strip = foldedBox(fold).parentElement!;
      expect(strip.tagName).toBe("SECTION");
      expect(getComputedStyle(strip).width).toBe("56px");
      expect(getComputedStyle(heldBy(fold)).display).toBe("none");
      expect(screen.queryByRole("heading", { name: "Overview" })).toBeNull();
      expect(screen.queryByRole("dialog")).toBeNull();

      await userEvent.click(fold);

      expect(fold).toHaveAttribute("aria-expanded", "true");
      expect(getComputedStyle(strip).width).toBe("360px");
      expect(getComputedStyle(heldBy(fold)).display).toBe("block");
      expect(heldBy(fold)).toContainElement(about().querySelector("dl"));
    },
  );

  it("folds and unfolds Overview beside a wide conversation time after time, drawing what it holds only while unfolded", async () => {
    localStorage.removeItem("lilradish.panel.about");
    opening({}, PAGE, "conversation");
    await opened();
    const fold = screen.getByRole("button", { name: "Overview" });
    const strip = foldedBox(fold).parentElement!;

    for (let round = 0; round < 3; round += 1) {
      await userEvent.click(fold);

      expect(fold).toHaveAttribute("aria-expanded", "false");
      expect(getComputedStyle(strip).width).toBe("56px");
      expect(getComputedStyle(heldBy(fold)).display).toBe("none");
      expect(within(strip).queryByRole("progressbar")).toBeNull();
      expect(within(strip).queryAllByRole("term")).toEqual([]);

      await userEvent.click(fold);

      expect(fold).toHaveAttribute("aria-expanded", "true");
      expect(getComputedStyle(strip).width).toBe("360px");
      expect(getComputedStyle(heldBy(fold)).display).toBe("block");
      expect(within(about()).getByRole("progressbar")).toBeVisible();
      expect(
        within(about())
          .getAllByRole("term")
          .map((term) => term.textContent),
      ).toEqual(expect.arrayContaining(["Status", "Number", "Workflow"]));
    }
  });

  it("keeps About it's fold beside a wide page an icon named and tipped as its heading", async () => {
    localStorage.removeItem("lilradish.panel.about");
    opening();
    await opened();
    const aboutIt = about();
    const fold = within(aboutIt).getByRole("button", { name: "Overview" });

    expect(fold).toHaveAttribute("aria-expanded", "true");
    expect(fold.textContent).toBe("");
    expect(
      document.getElementById(fold.getAttribute("aria-controls")!),
    ).not.toBeNull();
    await userEvent.hover(fold);
    expect((await screen.findByRole("tooltip")).textContent).toBe("Overview");
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(localStorage.getItem("lilradish.panel.about")).toBeNull();
  });

  it("draws Stop below the facts, the name edit beside the title, and the View segment before the work", async () => {
    opening();
    const title = await opened();
    const facts = [...about().querySelectorAll("dl")].at(-1)!;
    const stop = within(about()).getByRole("button", { name: "Stop" });
    const rename = within(title.parentElement!).getByRole("button", {
      name: `${setApart("Claim from Avo")}, editable`,
    });
    const views = screen.getByRole("group", { name: "View" });
    expect(
      facts.compareDocumentPosition(stop) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(facts).not.toContainElement(stop);
    expect(about()).not.toContainElement(rename);
    expect(about()).not.toContainElement(views);
    expect(title.parentElement).toContainElement(rename);
    expect(
      views.compareDocumentPosition(
        screen.getByRole("table", { name: "Steps" }),
      ) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(
      within(views)
        .getAllByRole("button")
        .map((button) => button.textContent),
    ).toEqual(["Conversation", "Full detail"]);
    expect(screen.queryByRole("button", { name: "More" })).toBeNull();
    expect(getComputedStyle(facts).gridTemplateColumns).toBe(
      "112px minmax(0, 1fr)",
    );
  });

  it.each([
    ["conversation", "Summarise", "summarise"],
    ["fullDetail", "summarise", "Summarise"],
  ] as const)(
    "names in About it, drawn in %s, the step the run is on as the rest of that drawing names it: %s, not %s",
    async (drawing, named, notNamed) => {
      opening(
        {
          [`GET ${RUN}`]: [
            reply({ ...CLAIM, at: { stepId: SUMMARISE, name: "summarise" } }),
          ],
        },
        PAGE,
        drawing,
      );
      await opened();

      await waitFor(() =>
        expect(said()["Status"]).toBe(
          `RunningNow at ${setApart(named)}1 of 2 steps done`,
        ),
      );
      expect(said()["Status"]).not.toContain(setApart(notNamed));
      const where = within(about()).getByText("Status")
        .nextElementSibling as HTMLElement;
      const badge = where.firstElementChild!;
      const step = where.querySelector("bdi")!;
      const nowAt = step.parentElement!;
      expect(badge.textContent).toBe("Running");
      expect(step).toHaveTextContent(setApart(named));
      expect(badge).not.toContainElement(step);
      expect(nowAt.previousElementSibling).toBe(badge);
      expect(nowAt.textContent).toBe(`Now at ${setApart(named)}`);
      expect([
        getComputedStyle(nowAt).display,
        getComputedStyle(nowAt).color,
        getComputedStyle(nowAt).overflowWrap,
        getComputedStyle(nowAt).fontSize,
        getComputedStyle(nowAt).lineHeight,
      ]).toEqual(["block", "rgb(88, 102, 108)", "anywhere", "13px", "20px"]);
      expect(where.textContent).toBe(`RunningNow at ${setApart(named)}`);
    },
  );

  it.each(["conversation", "fullDetail"] as const)(
    "uses current step display metadata in About it and its matching drawing in %s",
    async (drawing) => {
      opening(
        {
          [`GET ${RUN}`]: [
            reply({
              ...CLAIM,
              at: {
                stepId: SUMMARISE,
                name: "summarise",
                label: "\u7533\u8acb\u3092\u8981\u7d04\u202e",
              },
            }),
          ],
          [`GET ${STEPS}`]: [
            stepsRead({
              steps: [
                {
                  ...SUMMARISE_ROW,
                  label: "\u7533\u8acb\u3092\u8981\u7d04\u202e",
                },
                FILE_IT_ROW,
              ],
            }),
          ],
        },
        PAGE,
        drawing,
      );
      await opened();
      await waitFor(() =>
        expect(said().Status).toBe(
          `RunningNow at ${setApart("\u7533\u8acb\u3092\u8981\u7d04\u202e")}1 of 2 steps done`,
        ),
      );
      expect(said().Status).not.toContain("Summarise");
      expect(said().Status).not.toContain("summarise");
      const where = within(about()).getByText("Status")
        .nextElementSibling as HTMLElement;
      expect(where.querySelector("bdi")).toHaveTextContent(
        setApart("\u7533\u8acb\u3092\u8981\u7d04\u202e"),
      );
      if (drawing === "fullDetail") {
        expect(
          screen.getByRole("link", {
            name: "\u7533\u8acb\u3092\u8981\u7d04\u202e",
          }),
        ).toHaveAttribute(
          "href",
          `/groups/${KEY}/work/${RUN_ID}/steps/${SUMMARISE}`,
        );
      } else {
        expect(
          screen.getByRole("heading", {
            name: setApart("\u7533\u8acb\u3092\u8981\u7d04\u202e"),
          }),
        ).toBeVisible();
      }
    },
  );

  it("keeps the work wide enough for a table where About it stands beside it", async () => {
    const { resize } = opening();
    await opened();

    await resize(1024);
    const besideFrom = about();
    const work = besideFrom.previousElementSibling!;
    expect(work.getBoundingClientRect().width).toBeGreaterThanOrEqual(
      LEAST_TABLE_WIDTH,
    );
    await resize(1023);
    await waitFor(() =>
      expect(screen.queryByRole("region", { name: "Overview" })).toBeNull(),
    );
  });

  it.each(["conversation", "fullDetail"] as const)(
    "names the parent source by its own display metadata in %s without changing Part of links",
    async (drawing) => {
      const { name: _name, startedBy: _starter, ...beneath } = CLAIM;
      opening(
        {
          [`GET ${RUN}`]: [
            reply({
              ...beneath,
              above: ABOVE,
              runsAbove: [{ runId: ABOVE, number: 6 }],
              from: { ...FROM_ABOVE, label: "\u89aa\u306e\u5206\u5c90\u202e" },
            }),
          ],
        },
        PAGE,
        drawing,
      );
      await opened();
      expect(
        said().Started.startsWith(
          `By step ${setApart("\u89aa\u306e\u5206\u5c90\u202e")} of run 6, `,
        ),
      ).toBe(true);
      expect(said().Started).not.toContain(setApart("route"));
      expect(screen.getByRole("link", { name: "Open run 6" })).toHaveAttribute(
        "href",
        `/groups/${KEY}/work/${ABOVE}`,
      );
      expect(
        screen.getByRole("link", { name: "Open the step that started it" }),
      ).toHaveAttribute(
        "href",
        `/groups/${KEY}/work/${FROM_ABOVE.runId}/steps/${FROM_ABOVE.stepId}`,
      );
    },
  );

  it("heads a run beneath another with its number, links the run above, and says it is held to that one's ceiling", async () => {
    const { name: _unnamed, startedBy: _nobody, ...beneath } = CLAIM;
    opening({
      [`GET ${STEPS}`]: [
        stepsRead({ run: { ...RUN_HEADER, state: "stopped" } }),
      ],
      [`GET ${RUN}`]: [
        reply({
          ...beneath,
          above: ABOVE,
          runsAbove: [{ runId: ABOVE, number: 6 }],
          from: FROM_ABOVE,
          state: "stopped",
          stopped: {
            at: "2026-09-24T08:15:00Z",
            ceilingOf: { runId: ABOVE, number: 6 },
          },
          ceiling: { heldBy: { runId: ABOVE, number: 6 } },
          stateActs: [],
        }),
      ],
    });

    expect(await opened()).toHaveTextContent("Run 7");
    const facts = said();
    expect(facts["Part of"]).toContain("Open run 6");
    expect(facts.Beneath).toBeUndefined();
    expect(facts.Started).toMatch(
      new RegExp(`^By step ${setApart("route")} of run 6, .+$`),
    );
    expect(facts["Status"]).toMatch(
      /^StoppedStopped: usage limit of run 6 reached, .+$/,
    );
    expect(ceiling().textContent).toBe(
      "Uses the usage limit of run 6, the top-level run",
    );
    expect(
      [
        screen.getByRole("link", { name: "Open run 6" }),
        screen.getByRole("link", { name: "Open the step that started it" }),
        screen.getByRole("link", { name: /Uses the usage limit of run 6/ }),
      ].map((link) => link.getAttribute("href")),
    ).toEqual([
      `/groups/${KEY}/work/${ABOVE}`,
      `/groups/${KEY}/work/${ABOVE}/steps/${FILE_IT}`,
      `/groups/${KEY}/work/${ABOVE}`,
    ]);
    expect(
      screen.getByRole("button", { name: "Conversation" }),
    ).toHaveAttribute("aria-pressed", "false");
    expect(screen.getByRole("button", { name: "Full detail" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
  });

  /** Kept apart from the tab Work was left on, which says nothing of how a run is drawn. */
  it.each([
    ["nothing kept", null, "conversation"],
    ["Conversation kept", "conversation", "conversation"],
    ["Full detail kept", "fullDetail", "fullDetail"],
    ["a word this build does not know", "sideways", "conversation"],
    ["the name of a Work tab", "history", "conversation"],
  ])(
    "draws the run as this device last left a run: %s",
    async (_case, kept, drawn) => {
      localStorage.setItem("lilradish.work.tab", "history");
      if (kept !== null) {
        localStorage.setItem(KEPT_DRAWING_AS, kept);
      }
      opening({}, PAGE, null);
      await opened();

      expect(
        screen.queryByRole("list", { name: "The conversation" }) !== null,
      ).toBe(drawn === "conversation");
      expect(screen.queryByRole("table", { name: "Steps" }) !== null).toBe(
        drawn === "fullDetail",
      );
      expect(localStorage.getItem(KEPT_DRAWING_AS)).toBe(kept);
    },
  );

  it.each(
    (["fullDetail", "conversation"] as const).flatMap((drawing) => [
      [
        drawing,
        "the list's filter and order, and none of what a step's page picked",
        `${PAGE}?filter=claim&sort=startedBy&value=summary&try=1`,
        `/groups/${KEY}/work?filter=claim&sort=startedBy&tab=history`,
      ],
      [
        drawing,
        "its filter, and History named in place of the tab the address named",
        `${PAGE}?tab=newRun&filter=claim`,
        `/groups/${KEY}/work?filter=claim&tab=history`,
      ],
      [drawing, "History alone", PAGE, `/groups/${KEY}/work?tab=history`],
    ]),
  )(
    "draws in %s, above the run's name, the path through Work and History to it, History carrying %s",
    async (drawing, _case, address, history) => {
      opening({}, address, drawing);
      const heading = await opened();

      const path = screen.getByRole("navigation", { name: "Breadcrumb" });
      expect(
        within(path)
          .getAllByRole("link")
          .map((link) => [link.textContent, link.getAttribute("href")]),
      ).toEqual([
        ["Work", `/groups/${KEY}/work`],
        ["History", history],
      ]);
      expect(within(path).getAllByRole("listitem").at(-1)).toHaveTextContent(
        setApart("Claim from Avo"),
      );
      expect(
        within(path).queryByRole("link", { name: /Claim from Avo/ }),
      ).toBeNull();
      expect(
        path.compareDocumentPosition(heading) &
          Node.DOCUMENT_POSITION_FOLLOWING,
      ).toBeTruthy();
    },
  );

  it("draws a run beneath others with every run above it in the path, from the top down, each opening its own page", async () => {
    const top = "00000008-0000-4000-8000-000000000c59";
    const { name: _unnamed, startedBy: _nobody, ...beneath } = CLAIM;
    opening(
      {
        [`GET ${RUN}`]: [
          reply({
            ...beneath,
            above: ABOVE,
            from: FROM_ABOVE,
            runsAbove: [
              { runId: top, number: 5, name: "Claim from Glinn" },
              { runId: ABOVE, number: 6 },
            ],
            ceiling: { heldBy: { runId: top, number: 5 } },
            stateActs: [],
          }),
        ],
      },
      `${PAGE}?filter=claim`,
    );
    await opened();

    const path = screen.getByRole("navigation", { name: "Breadcrumb" });

    expect(
      within(path)
        .getAllByRole("link")
        .map((link) => [link.textContent, link.getAttribute("href")]),
    ).toEqual([
      ["Work", `/groups/${KEY}/work`],
      ["History", `/groups/${KEY}/work?filter=claim&tab=history`],
      [setApart("Claim from Glinn"), `/groups/${KEY}/work/${top}?filter=claim`],
      ["Run 6", `/groups/${KEY}/work/${ABOVE}?filter=claim`],
    ]);
    expect(within(path).getAllByRole("listitem").at(-1)).toHaveTextContent(
      "Run 7",
    );
    expect(within(path).queryByRole("link", { name: "Run 7" })).toBeNull();
  });

  it.each([
    ["while the run is still read", UNANSWERED],
    ["where the run is refused", refusal("RUN_NOT_IN_VIEW", 404)],
  ])(
    "draws the path through Work and History alone %s, saying nothing of the run",
    async (_case, answer) => {
      opening({ [`GET ${RUN}`]: [answer] });

      const path = await screen.findByRole("navigation", {
        name: "Breadcrumb",
      });

      expect(
        within(path)
          .getAllByRole("link")
          .map((link) => link.textContent),
      ).toEqual(["Work", "History"]);
      expect(within(path).getAllByRole("listitem")).toHaveLength(2);
      expect(document.body).not.toHaveTextContent("Claim from Avo");
      expect(document.body).not.toHaveTextContent("Run 7");
    },
  );

  it.each(["run", "steps"] as const)(
    "closes the run when %s first fails ordinarily and the other read later refuses run access",
    async (first) => {
      const held = deferred<readonly [string, number]>();
      const ordinary = refusal("READ_FAILED", 503);
      const { sent } = opening({
        [`GET ${RUN}`]: [first === "run" ? ordinary : held.promise],
        [`GET ${STEPS}`]: [first === "steps" ? ordinary : held.promise],
      });
      await screen.findByRole("alert");
      expect(
        screen.queryByText("That run isn't here, or you can't see it."),
      ).toBeNull();

      await act(async () =>
        held.settle([JSON.stringify({ code: "RUN_NOT_IN_VIEW" }), 404]),
      );

      expect(
        screen.getByText("That run isn't here, or you can't see it."),
      ).toBeVisible();
      expect(screen.queryByRole("heading", { level: 1 })).toBeNull();
      expect(screen.queryByRole("table", { name: "Steps" })).toBeNull();
      expect(
        requestsTo(sent).filter((each) => each === `GET ${RUN}`),
      ).toHaveLength(1);
      expect(
        requestsTo(sent).filter((each) => each === `GET ${STEPS}`),
      ).toHaveLength(1);
    },
  );

  it.each(["fullDetail", "conversation"] as const)(
    "shows one route's actual child workflow and deepest step in %s and its own step page",
    async (drawing) => {
      const direction = String.fromCodePoint(0x05d0);
      const workflowName = `Demo Sample ${direction}`;
      const deepestName = `make_${direction}`;
      const deepestStep = "00000009-0000-4000-8000-000000000c55";
      const beneath = {
        runId: ABOVE,
        number: 8,
        runs: {
          versionName: null,
          kind: "workflow",
          entryId: WORKFLOW,
          name: workflowName,
          version: 2,
          versionId: VERSION,
        },
        at: {
          runId: ABOVE,
          number: 8,
          stepId: deepestStep,
          name: deepestName,
        },
      };
      const route = {
        ...FILE_IT_ROW,
        name: "choose",
        runs: { versionName: null, kind: "route" },
        producer: undefined,
        reviewer: undefined,
        tries: undefined,
        state: "waiting",
        stateActs: [],
        beneath,
      };
      const { router } = opening(
        {
          [`GET ${STEPS}`]: [stepsRead({}, route), stepsRead({}, route)],
          [`GET ${RUN}/steps/${FILE_IT}`]: [
            reply({
              run: RUN_HEADER,
              declarations: DECLARATIONS,
              step: route,
              triesMade: [],
            }),
          ],
        },
        PAGE,
        drawing,
      );
      await opened();
      const versionWords = `${workflowName} \u00b7 Version 2`;
      const setApartByTag = () =>
        [...document.querySelectorAll("bdi")].map((each) => each.textContent);
      const childWords = `This step started run 8 as a sub-run. Open run 8 ${versionWords} ${setApart(deepestName)}`;
      expect(document.body.textContent).toContain(childWords);
      expect(document.body.textContent).not.toContain(
        `Open run 8${versionWords}`,
      );
      expect(document.body.textContent).not.toContain(
        `${versionWords}${setApart(deepestName)}`,
      );
      expect(
        screen
          .getByRole("link", { name: setApart(deepestName) })
          .getAttribute("href"),
      ).toBe(`/groups/${KEY}/work/${ABOVE}/steps/${deepestStep}`);
      expect(
        screen.getByRole("link", { name: "Open run 8" }).getAttribute("href"),
      ).toBe(`/groups/${KEY}/work/${ABOVE}`);
      expect(setApartByTag()).toContain(workflowName);

      await act(() =>
        router.navigate(`/groups/${KEY}/work/${RUN_ID}/steps/${FILE_IT}`),
      );
      await screen.findByRole("heading", { level: 1 });
      expect(document.body.textContent).toContain(childWords);
      expect(document.body.textContent).not.toContain(
        `Open run 8${versionWords}`,
      );
      expect(document.body.textContent).not.toContain(
        `${versionWords}${setApart(deepestName)}`,
      );
      expect(
        screen
          .getByRole("link", { name: setApart(deepestName) })
          .getAttribute("href"),
      ).toBe(`/groups/${KEY}/work/${ABOVE}/steps/${deepestStep}`);
      expect(setApartByTag()).toContain(workflowName);
    },
  );

  it("links a child run's launch and actual source step but offers no ceiling change there", async () => {
    const sourceStep = "00000009-0000-4000-8000-000000000c53";
    const launchStep = "00000009-0000-4000-8000-000000000c54";
    const { name: _name, startedBy: _startedBy, ...child } = CLAIM;
    opening({
      [`GET ${RUN}`]: [
        reply({
          ...child,
          above: ABOVE,
          runsAbove: [{ runId: ABOVE, number: 6 }],
          from: {
            runId: ABOVE,
            number: 6,
            stepId: launchStep,
            stepName: "route",
          },
          startedWith: { ticket: "Demo Sample" },
          startedWithFrom: [
            {
              input: "ticket",
              from: {
                kind: "step",
                runId: ABOVE,
                runNumber: 6,
                stepId: sourceStep,
                name: "made",
                path: "summary",
              },
            },
          ],
          stateActs: ["change_ceiling"],
        }),
      ],
    });
    await opened();

    expect(
      screen
        .getByRole("link", { name: "Open the step that started it" })
        .getAttribute("href"),
    ).toBe(`/groups/${KEY}/work/${ABOVE}/steps/${launchStep}`);
    expect(said().Started).toMatch(
      new RegExp(`^By step ${setApart("route")} of run 6, .+$`),
    );
    expect(screen.getByRole("link", { name: "Open run 6" })).toHaveAttribute(
      "href",
      `/groups/${KEY}/work/${ABOVE}`,
    );
    expect(
      screen
        .getAllByRole("link")
        .find(
          (link) =>
            link.getAttribute("href") ===
            `/groups/${KEY}/work/${ABOVE}/steps/${sourceStep}`,
        ),
    ).toHaveTextContent(/output of.*in run 6/);
    expect(
      within(ceiling()).queryByRole("button", {
        name: "9,007,199,254,740,990, editable",
      }),
    ).toBeNull();
  });

  it("shows a child run's whole composed input and links each part to its actual maker", async () => {
    const sourceStep = "00000009-0000-4000-8000-000000000c53";
    const maker = "00000008-0000-4000-8000-000000000c57";
    const { name: _name, startedBy: _startedBy, ...child } = CLAIM;
    const ticket = {
      name: "ticket",
      label: "Ticket",
      kind: "fields",
      mustBeGiven: true,
      fields: [
        {
          name: "name",
          label: "Name",
          kind: "text",
          longest: 4000,
          mustBeGiven: true,
        },
        {
          name: "id",
          label: "Identifier",
          kind: "text",
          longest: 100,
          mustBeGiven: true,
        },
        {
          name: "note",
          label: "Note",
          kind: "text",
          longest: 4000,
          mustBeGiven: true,
        },
      ],
    };
    opening(
      {
        [`GET ${RUN}`]: [
          reply({
            ...child,
            above: ABOVE,
            runsAbove: [{ runId: ABOVE, number: 6 }],
            from: FROM_ABOVE,
            startedWith: {
              ticket: { name: "Demo Sample", id: "7", note: "Ready" },
            },
            startedWithFrom: [
              {
                input: "ticket",
                sources: [
                  { targetPath: "name", from: { kind: "constant" } },
                  {
                    targetPath: "id",
                    from: {
                      kind: "run_input",
                      runId: ABOVE,
                      runNumber: 6,
                      path: "ticket",
                    },
                  },
                  {
                    targetPath: "note",
                    from: {
                      kind: "step",
                      runId: maker,
                      runNumber: 4,
                      stepId: sourceStep,
                      name: "made",
                      path: "summary",
                    },
                  },
                ],
              },
            ],
            stateActs: [],
          }),
        ],
        [`GET ${STEPS}`]: [
          stepsRead({
            declarations: {
              ...DECLARATIONS,
              [VERSION]: { takes: [ticket], gives: [SUMMARY] },
            },
            steps: [
              {
                ...SUMMARISE_ROW,
                takesFrom: [
                  {
                    input: "text",
                    from: { kind: "run_input", path: "ticket.name" },
                  },
                ],
              },
              FILE_IT_ROW,
            ],
          }),
        ],
      },
      `${PAGE}?filter=claim`,
    );
    await opened();

    const region = screen.getByRole("region", {
      name: "Run inputs",
    });
    expect(region).toHaveTextContent("Demo Sample");
    expect(region).toHaveTextContent("Combined from:");
    const sources = within(region).getByText("Combined from:")
      .nextElementSibling as HTMLElement;
    expect(
      within(sources)
        .getAllByRole("term")
        .map((term) => term.textContent),
    ).toEqual([setApart("Name"), setApart("Identifier"), setApart("Note")]);
    const parts = within(sources).getAllByRole("definition");
    expect(parts).toHaveLength(3);
    expect(within(parts.at(-3)!).queryByRole("link")).toBeNull();
    expect(within(parts.at(-2)!).getByRole("link")).toHaveAttribute(
      "href",
      `/groups/${KEY}/work/${ABOVE}?filter=claim`,
    );
    expect(within(parts.at(-1)!).getByRole("link")).toHaveAttribute(
      "href",
      `/groups/${KEY}/work/${maker}/steps/${sourceStep}?filter=claim`,
    );
    expect(region).not.toHaveTextContent("undefined");
  });

  it.each([
    ["a named ancestor", ABOVE],
    ["an unidentifiable source", undefined],
  ])(
    "keeps %s run-input path apart from the child's same-path label",
    async (_case, sourceRunId) => {
      const { name: _name, startedBy: _startedBy, ...child } = CLAIM;
      opening({
        [`GET ${RUN}`]: [
          reply({
            ...child,
            above: ABOVE,
            runsAbove: [{ runId: ABOVE, number: 6 }],
            from: FROM_ABOVE,
            startedWith: { ticket: "Demo Sample" },
            startedWithFrom: [
              {
                input: "ticket",
                from: {
                  kind: "run_input",
                  path: "ticket",
                  ...(sourceRunId === undefined
                    ? {}
                    : { runId: sourceRunId, runNumber: 6 }),
                },
              },
            ],
            stateActs: [],
          }),
        ],
      });
      await opened();

      const region = screen.getByRole("region", {
        name: "Run inputs",
      });
      const sourceWords = `${setApart("ticket")} (${sourceRunId === undefined ? "run input" : "input of run 6"})`;
      expect(region).toHaveTextContent(`${setApart("Ticket")}${sourceWords}`);
      expect(region).not.toHaveTextContent(
        `${setApart("Ticket")} (${sourceRunId === undefined ? "run input" : "input of run 6"})`,
      );
      const sourceLink = within(region).queryByRole("link", {
        name: sourceWords,
      });
      expect(sourceLink?.getAttribute("href") ?? null).toBe(
        sourceRunId === undefined ? null : `/groups/${KEY}/work/${ABOVE}`,
      );
    },
  );

  /** Opened beside a list, a run's links keep the list's filter and order, as the list's own links do. */
  it("keeps the address's query on each link to another run", async () => {
    const { name: _unnamed, startedBy: _nobody, ...beneath } = CLAIM;
    opening(
      {
        [`GET ${RUN}`]: [
          reply({
            ...beneath,
            above: ABOVE,
            runsAbove: [{ runId: ABOVE, number: 6 }],
            from: FROM_ABOVE,
            ceiling: { heldBy: { runId: ABOVE, number: 6 } },
            stateActs: [],
          }),
        ],
      },
      `${PAGE}?filter=claim&sort=run`,
    );

    await opened();

    expect(
      [
        screen.getByRole("link", { name: "Open run 6" }),
        screen.getByRole("link", { name: "Open the step that started it" }),
        screen.getByRole("link", { name: /Uses the usage limit of run 6/ }),
      ].map((link) => link.getAttribute("href")),
    ).toEqual([
      `/groups/${KEY}/work/${ABOVE}?filter=claim&sort=run`,
      `/groups/${KEY}/work/${ABOVE}/steps/${FILE_IT}?filter=claim&sort=run`,
      `/groups/${KEY}/work/${ABOVE}?filter=claim&sort=run`,
    ]);
  });

  it("names every reached child ceiling, and keeps a human stop separate", async () => {
    const children = [
      { runId: ABOVE, number: 8 },
      { runId: "00000008-0000-4000-8000-000000000c53", number: 9 },
    ];
    opening({
      [`GET ${STEPS}`]: [
        stepsRead({ run: { ...RUN_HEADER, state: "stopped" } }),
      ],
      [`GET ${RUN}`]: [
        reply({
          ...STOPPED,
          stopped: {
            at: "2026-09-24T08:15:00Z",
            ceilingOf: children[0],
            ceilings: children,
          },
        }),
      ],
    });
    await opened();
    expect(said()["Status"]).toMatch(
      /^StoppedStopped: usage limits of runs 8 and 9 reached, /,
    );
    expect(said()["Status"]).not.toContain("Glinn");
  });

  it("names its own ceiling with each lower ceiling when both are reached", async () => {
    const ceilings = [
      { runId: RUN_ID, number: 7 },
      { runId: ABOVE, number: 8 },
    ];
    opening({
      [`GET ${STEPS}`]: [
        stepsRead({ run: { ...RUN_HEADER, state: "stopped" } }),
      ],
      [`GET ${RUN}`]: [
        reply({
          ...STOPPED,
          stopped: {
            at: "2026-09-24T08:15:00Z",
            ceilingOf: ceilings[0],
            ceilings,
          },
        }),
      ],
    });
    await opened();
    expect(said()["Status"]).toMatch(
      /^StoppedStopped: its usage limit and the limit of run 8 reached, /,
    );
  });

  it("lists child-owned ceilings at the root and targets only the selected child", async () => {
    const child = {
      run: { runId: ABOVE, number: 8 },
      ceiling: { inForce: "50", raiseNeedsApproval: false },
      acts: ["change_ceiling"],
    };
    const { sent } = opening({
      [`GET ${RUN}`]: [
        reply({ ...CLAIM, ceilingsBeneath: [child] }),
        UNANSWERED,
      ],
      [`PATCH ${RUN}/ceiling`]: [reply({ ...CLAIM, ceilingsBeneath: [child] })],
    });
    await opened();
    const childGroup = screen.getByRole("group", {
      name: "Usage limit of run 8",
    });
    expect(within(childGroup).getByText("50")).toBeVisible();
    expect(
      within(childGroup).getByRole("button", {
        name: "50, editable",
      }),
    ).toBeVisible();
    await userEvent.click(
      within(childGroup).getByRole("button", {
        name: "50, editable",
      }),
    );
    const dialog = within(childGroup)
      .getByRole("textbox", { name: "Usage limit" })
      .closest("form")!;
    expect(screen.queryByRole("dialog")).toBeNull();
    await userEvent.clear(
      within(dialog).getByRole("textbox", { name: "Usage limit" }),
    );
    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    const changed = sent.mock.calls.find(
      ([, init]) => init?.method === "PATCH",
    );
    expect(changed?.[0]).toBe(`${RUN}/ceiling`);
    expect(JSON.parse(String(changed?.[1]?.body))).toEqual({
      ceiling: null,
      runId: ABOVE,
    });
  });

  it.each([
    ["Approve change", "approval", "approve_raise", GLINN],
    ["Reject change", "refusal", "refuse_raise", GLINN],
    ["Withdraw request", "withdrawal", "withdraw_raise", AVO],
  ] as const)(
    "decides only the selected child raise by the root %s endpoint",
    async (control, decision, act, askedBy) => {
      const siblingId = "00000008-0000-4000-8000-000000000c53";
      const child = {
        run: { runId: ABOVE, number: 8 },
        ceiling: {
          inForce: "50",
          raiseNeedsApproval: true,
          waiting: {
            changeId: RAISE,
            to: "100",
            askedBy,
            askedAt: "2026-09-24T08:30:00Z",
          },
        },
        acts: [act],
      };
      const sibling = {
        ...child,
        run: { runId: siblingId, number: 9 },
        ceiling: {
          ...child.ceiling,
          waiting: { ...child.ceiling.waiting, changeId: `${RAISE}-other` },
        },
        acts: [],
      };
      const before = { ...CLAIM, ceilingsBeneath: [child, sibling] };
      const after = {
        ...CLAIM,
        ceilingsBeneath: [
          {
            ...child,
            ceiling: { ...child.ceiling, waiting: undefined },
            acts: [],
          },
          sibling,
        ],
      };
      const address = `PUT ${RUN}/ceiling-changes/${RAISE}/${decision}`;
      const { sent } = opening({
        [`GET ${RUN}`]: [reply(before), UNANSWERED],
        [address]: [reply(after)],
      });
      await opened();
      const selected = screen.getByRole("group", {
        name: "Usage limit of run 8",
      });
      const other = screen.getByRole("group", { name: "Usage limit of run 9" });
      expect(within(other).getByText(/asked/)).toBeVisible();

      await userEvent.click(
        within(selected).getByRole("button", { name: control }),
      );

      await waitFor(() =>
        expect(within(selected).queryByText(/asked/)).toBeNull(),
      );
      expect(within(other).getByText(/asked/)).toBeVisible();
      expect(
        within(ceiling()).getByText("9,007,199,254,740,990"),
      ).toBeVisible();
      expect(requestsTo(sent).filter((each) => each.startsWith("PUT"))).toEqual(
        [address],
      );
      expect(requestsTo(sent)).not.toContain(
        `PUT /api/groups/${GROUP}/runs/${ABOVE}/ceiling-changes/${RAISE}/${decision}`,
      );
    },
  );

  it.each([
    [
      "somebody",
      { by: GLINN },
      `^StoppedStopped by ${setApart("Glinn Havven")}, .+$`,
    ],
    [
      "its own ceiling, naming nobody",
      { ceilingOf: { runId: RUN_ID, number: 7 } },
      "^StoppedStopped: usage limit reached, .+$",
    ],
  ])("says a run is stopped, and by %s", async (_case, by, where) => {
    opening({
      [`GET ${RUN}`]: [
        reply({ ...STOPPED, stopped: { at: "2026-09-24T08:15:00Z", ...by } }),
      ],
      [`GET ${STEPS}`]: [
        stepsRead({ run: { ...RUN_HEADER, state: "stopped" } }),
      ],
    });
    await opened();

    expect(said()["Status"]).toMatch(new RegExp(where));
    const status = within(about()).getByText("Status").nextElementSibling!;
    const note = status.lastElementChild!;
    expect(note.previousElementSibling!.textContent).toBe("Stopped");
    expect(note.textContent).toMatch(new RegExp(`^${where.slice(8)}`));
    const drawn = getComputedStyle(note);
    expect([
      drawn.display,
      drawn.color,
      drawn.fontSize,
      drawn.lineHeight,
    ]).toEqual(["block", "rgb(88, 102, 108)", "13px", "20px"]);
  });

  it.each([
    ["done", "Done", { ...RUN_HEADER, state: "done", at: undefined }],
    ["running", `RunningNow at ${setApart("file it")}`, RUN_HEADER],
  ])(
    "says where a %s run is now despite its earlier stop",
    async (state, words, header) => {
      opening({
        [`GET ${RUN}`]: [reply({ ...STOPPED, state, at: RUN_HEADER.at })],
        [`GET ${STEPS}`]: [stepsRead({ run: header })],
      });
      await opened();

      expect(said()["Status"]).toContain(words);
      expect(said()["Status"]).not.toContain("Stopped by");
    },
  );

  /** A state named after this build is said plainly as one it cannot say, never in the server's spelling. */
  it("says where a run is in words only, even where the server works out a state this build has none for", async () => {
    opening({
      [`GET ${RUN}`]: [reply({ ...CLAIM, state: "resting" })],
      [`GET ${STEPS}`]: [
        stepsRead({ run: { ...RUN_HEADER, state: "resting" } }),
      ],
    });
    await opened();

    expect(said()["Status"]).toBe(
      "This page can't show this status yet.1 of 2 steps done",
    );
    expect(
      within(about()).getByRole("progressbar", { name: "1 of 2 steps done" }),
    ).toBeVisible();
    expect(screen.queryByText(/resting/)).toBeNull();
  });

  it.each([
    [
      "with some of what came back unknown, says so",
      { cameBackUnknown: true },
      "The amount received is not known for every call, and is counted here as zero.",
    ],
    [
      "with some calls counted here, says part of it is measured here",
      { measuredHere: "partly" },
      "Part of this is estimated by this system, where a model did not report its own count.",
    ],
    [
      "with every call counted here, says all of it is measured here",
      { measuredHere: "wholly" },
      "All of this is estimated by this system: no model reported its own count.",
    ],
    [
      "with both, says who counted it before what is not known",
      { cameBackUnknown: true, measuredHere: "partly" },
      "Part of this is estimated by this system, where a model did not report its own count.The amount received is not known for every call, and is counted here as zero.",
    ],
    ["with all of it known as the model counted it, says nothing more", {}, ""],
  ])("says what a run has cost, and %s", async (_case, marked, more) => {
    opening({
      [`GET ${RUN}`]: [
        reply({
          ...CLAIM,
          spend: { ...CLAIM.spend, cameBackUnknown: false, ...marked },
        }),
      ],
    });
    await opened();

    expect(said()["Usage"]).toBe(
      `9,007,199,254,741,013 in total: 9,007,199,254,740,993 sent, 20 received${more}`,
    );
  });

  it.each([
    [
      "every act on a running run, a raise waiting",
      [
        "stop",
        "open_again",
        "rename",
        "change_ceiling",
        "approve_raise",
        "refuse_raise",
        "withdraw_raise",
      ],
      [
        `${setApart("Claim from Avo")}, editable`,
        "9,007,199,254,740,990, editable",
        "Approve change",
        "Reject change",
        "Stop",
      ],
    ],
    ["opening again alone", ["open_again"], ["Resume"]],
    ["none of them", [], []],
  ])(
    "draws only controls allowed by state facts and the current reader: %s",

    async (_case, acts, controls) => {
      opening(
        { [`GET ${RUN}`]: [reply({ ...RAISED, stateActs: acts })] },
        PAGE,
        "fullDetail",
        AVO.userId,
      );
      await opened();

      const actsDrawn = [
        `${setApart("Claim from Avo")}, editable`,
        "9,007,199,254,740,990, editable",
        "Approve change",
        "Reject change",
        "Stop",
        "Resume",
        "Withdraw request",
      ].filter((name) => screen.queryByRole("button", { name }) !== null);
      expect(actsDrawn).toEqual(controls);
      expect(
        screen.getByRole("button", { name: "Full detail" }),
      ).toHaveAttribute("aria-pressed", "true");
      expect(
        screen.getByRole("button", { name: "Conversation" }),
      ).toHaveAttribute("aria-pressed", "false");
    },
  );

  /** The run read again after the act is held unanswered, so all that is drawn is what the act answered. */
  it("stops the run, showing it as the act answered before the run read again after it comes back", async () => {
    const { sent } = opening({
      [`GET ${RUN}`]: [reply(CLAIM), UNANSWERED],
      [`PUT ${RUN}/stop`]: [reply(STOPPED)],
    });
    await opened();
    const control = screen.getByRole("button", { name: "Stop" });

    await userEvent.click(control);

    expect(await screen.findByRole("button", { name: "Resume" })).toBe(control);
    expect(said()["Status"]).toMatch(/^StoppedStopped by /);
    await waitFor(() =>
      expect(requestsTo(sent)).toEqual([
        `GET ${RUN}`,
        `GET ${STEPS}`,
        `PUT ${RUN}/stop`,
        `GET ${RUN}`,
        `GET ${STEPS}`,
      ]),
    );
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("opens a stopped run again", async () => {
    const { sent } = opening({
      [`GET ${RUN}`]: [reply(STOPPED), reply(CLAIM)],
      [`GET ${STEPS}`]: [
        stepsRead({ run: { ...RUN_HEADER, state: "stopped" } }),
        stepsRead(),
      ],
      [`DELETE ${RUN}/stop`]: [reply(CLAIM)],
    });
    await opened();

    await userEvent.click(screen.getByRole("button", { name: "Resume" }));

    expect(
      await screen.findByRole("button", { name: "Stop" }),
    ).toBeInTheDocument();
    expect(requestsTo(sent).filter((each) => !each.startsWith("GET"))).toEqual([
      `DELETE ${RUN}/stop`,
    ]);
  });

  it("draws Stop outlined in the warning colour, and the same control once it reads Open again in none, Rename in none either way", async () => {
    opening({
      [`GET ${RUN}`]: [reply(CLAIM), UNANSWERED],
      [`PUT ${RUN}/stop`]: [reply(STOPPED)],
    });
    await opened();
    const control = screen.getByRole("button", { name: "Stop" });
    const rename = screen.getByRole("button", {
      name: `${setApart("Claim from Avo")}, editable`,
    });

    expect(control).toHaveClass(buttonClasses.colorWarning);
    expect(control).toHaveClass(buttonClasses.outlined);
    expect(rename).not.toHaveClass(buttonClasses.colorWarning);

    await userEvent.click(control);

    expect(await screen.findByRole("button", { name: "Resume" })).toBe(control);
    expect(control).not.toHaveClass(buttonClasses.colorWarning);
    expect(control).toHaveClass(buttonClasses.outlined);
    expect(rename).not.toHaveClass(buttonClasses.colorWarning);
  });

  /** A refusal of the act says the reader's standing moved, which is read again with the run. */
  it("says a refused stop as the rule of starting a run, and reads the standing and the run again", async () => {
    const { sent, readAgain } = opening({
      [`PUT ${RUN}/stop`]: [refusal("ACT_NOT_PERMITTED", 403)],
    });
    await opened();

    await userEvent.click(screen.getByRole("button", { name: "Stop" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(START_RUN_RULE);
    expect(readAgain).toHaveBeenCalledOnce();
    await waitFor(() => expect(runReads(sent)).toBe(2));
  });

  it("renames the run in place, heading the page with the name it answers with", async () => {
    const { sent } = opening({
      [`GET ${RUN}`]: [reply(CLAIM), UNANSWERED],
      [`PATCH ${RUN}`]: [reply({ ...CLAIM, name: "Claim from Avo, again" })],
    });
    await opened();

    await userEvent.click(
      screen.getByRole("button", {
        name: `${setApart("Claim from Avo")}, editable`,
      }),
    );
    const dialog = screen
      .getByRole("textbox", { name: "Name" })
      .closest("form")!;
    expect(screen.queryByRole("dialog")).toBeNull();
    await userEvent.type(
      within(dialog).getByRole("textbox", { name: "Name" }),
      ", again",
    );
    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() =>
      expect(screen.queryByRole("textbox", { name: "Name" })).toBeNull(),
    );
    expect(dialog).not.toBeInTheDocument();
    expect(await opened()).toHaveTextContent(setApart("Claim from Avo, again"));
    expect(
      screen.getByRole("button", {
        name: `${setApart("Claim from Avo, again")}, editable`,
      }),
    ).toHaveFocus();
    expect(requestsTo(sent).filter((each) => !each.startsWith("GET"))).toEqual([
      `PATCH ${RUN}`,
    ]);
  });

  it.each([
    ["name", "Name", `${setApart("Claim from Avo")}, editable`],
    ["own usage limit", "Usage limit", "9,007,199,254,740,990, editable"],
    ["child usage limit", "Usage limit", "50, editable"],
  ])(
    "returns to the same %s after cancelling without writing",
    async (_place, label, control) => {
      const child = {
        run: { runId: ABOVE, number: 8 },
        ceiling: { inForce: "50", raiseNeedsApproval: false },
        acts: ["change_ceiling"],
      };
      const { sent } = opening({
        [`GET ${RUN}`]: [reply({ ...CLAIM, ceilingsBeneath: [child] })],
      });
      await opened();
      const before = requestsTo(sent);
      await userEvent.click(screen.getByRole("button", { name: control }));
      await userEvent.type(screen.getByRole("textbox", { name: label }), "1");
      await userEvent.keyboard("{Escape}");
      expect(screen.queryByRole("textbox", { name: label })).toBeNull();
      expect(screen.getByRole("button", { name: control })).toHaveFocus();
      expect(requestsTo(sent)).toEqual(before);
      expect(
        requestsTo(sent).filter((request) => !request.startsWith("GET ")),
      ).toEqual([]);
    },
  );

  it.each([
    [
      "raised, says to what",
      RAISED.ceiling.waiting,
      "to raise it to 20,000\\. Needs approval\\.$",
    ],
    [
      "taken away, says so",
      { changeId: RAISE, askedBy: GLINN, askedAt: "2026-09-24T08:30:00Z" },
      "to remove the limit\\. Needs approval\\.$",
    ],
  ])(
    "says a raise waiting beside the ceiling in force: asked for it to be %s",
    async (_case, waiting, words) => {
      opening({
        [`GET ${RUN}`]: [
          reply({ ...RAISED, ceiling: { ...RAISED.ceiling, waiting } }),
        ],
      });
      await opened();

      expect(within(ceiling()).getByText(/asked/)).toHaveTextContent(
        new RegExp(`^${setApart("Glinn Havven")} asked .+ ${words}`),
      );
      expect(
        within(ceiling()).getByText("9,007,199,254,740,990"),
      ).toBeInTheDocument();
    },
  );

  /** The raise is named by its identifier, so nobody decides one they never saw. */
  it.each([
    ["Approve change", "approval", RAISED],
    ["Reject change", "refusal", RAISED],
    ["Withdraw request", "withdrawal", RAISED],
  ])(
    "decides the raise shown when %s is pressed, keeping the keyboard at the ceiling",
    async (control, decision, run) => {
      const { sent } = opening(
        {
          [`GET ${RUN}`]: [reply(run), UNANSWERED],
          [`PUT ${RUN}/ceiling-changes/${RAISE}/${decision}`]: [reply(CLAIM)],
        },
        PAGE,
        "fullDetail",
        decision === "withdrawal" ? GLINN.userId : AVO.userId,
      );
      await opened();

      await userEvent.click(screen.getByRole("button", { name: control }));

      await waitFor(() =>
        expect(within(ceiling()).queryByText(/asked/)).toBeNull(),
      );
      expect(
        requestsTo(sent).filter((each) => !each.startsWith("GET")),
      ).toEqual([`PUT ${RUN}/ceiling-changes/${RAISE}/${decision}`]);
      expect(document.activeElement).toBe(ceiling());
      expect(screen.queryByRole("alert")).toBeNull();
    },
  );

  /** Decided by somebody else meanwhile: the refusal is said, and the run read again shows how it stands now. */
  it.each([
    ["Approve change", "approval", RAISED],
    ["Withdraw request", "withdrawal", RAISED],
  ])(
    "says %s refused as the raise no longer waiting, and reads the run again and not the standing",
    async (control, decision, run) => {
      const { sent, readAgain } = opening(
        {
          [`GET ${RUN}`]: [reply(run), reply(CLAIM)],
          [`PUT ${RUN}/ceiling-changes/${RAISE}/${decision}`]: [
            refusal("CEILING_RAISE_NOT_WAITING", 409),
          ],
        },
        PAGE,
        "fullDetail",
        decision === "withdrawal" ? GLINN.userId : AVO.userId,
      );
      await opened();

      await userEvent.click(screen.getByRole("button", { name: control }));

      expect(await within(ceiling()).findByRole("alert")).toHaveTextContent(
        "That request is no longer pending.",
      );
      await waitFor(() => expect(runReads(sent)).toBe(2));
      expect(readAgain).not.toHaveBeenCalled();
    },
  );

  it.each([
    ["Approve change", "approval", RAISED, APPROVE_ENTRY_RULE],
    ["Reject change", "refusal", RAISED, APPROVE_ENTRY_RULE],
    ["Withdraw request", "withdrawal", RAISED, START_RUN_RULE],
  ])(
    "says %s refused as the act itself as the rule of the permission it takes, and reads the standing again",
    async (control, decision, run, rule) => {
      const { readAgain } = opening(
        {
          [`GET ${RUN}`]: [reply(run)],
          [`PUT ${RUN}/ceiling-changes/${RAISE}/${decision}`]: [
            refusal("ACT_NOT_PERMITTED", 403),
          ],
        },
        PAGE,
        "fullDetail",
        decision === "withdrawal" ? GLINN.userId : AVO.userId,
      );
      await opened();

      await userEvent.click(screen.getByRole("button", { name: control }));

      expect(await within(ceiling()).findByRole("alert")).toHaveTextContent(
        rule,
      );
      expect(readAgain).toHaveBeenCalledOnce();
    },
  );

  it("says a withdrawal refused as the raise being another's, and reads the run again", async () => {
    const { sent, readAgain } = opening(
      {
        [`GET ${RUN}`]: [reply(RAISED), reply(RAISED)],
        [`PUT ${RUN}/ceiling-changes/${RAISE}/withdrawal`]: [
          refusal("CEILING_RAISE_ASKED_BY_ANOTHER", 403),
        ],
      },
      PAGE,
      "fullDetail",
      GLINN.userId,
    );
    await opened();

    await userEvent.click(
      screen.getByRole("button", { name: "Withdraw request" }),
    );

    expect(await within(ceiling()).findByRole("alert")).toHaveTextContent(
      "Only the person who asked for this change can withdraw it.",
    );
    await waitFor(() => expect(runReads(sent)).toBe(2));
    expect(readAgain).not.toHaveBeenCalled();
  });

  it("changes the ceiling in place, showing the run as the change answered", async () => {
    const { sent } = opening({
      [`GET ${RUN}`]: [reply(CLAIM), UNANSWERED],
      [`PATCH ${RUN}/ceiling`]: [
        reply({ ...CLAIM, ceiling: { raiseNeedsApproval: true } }),
      ],
    });
    await opened();

    await userEvent.click(
      screen.getByRole("button", { name: "9,007,199,254,740,990, editable" }),
    );
    const dialog = screen
      .getByRole("textbox", { name: "Usage limit" })
      .closest("form")!;
    expect(screen.queryByRole("dialog")).toBeNull();
    await userEvent.clear(
      within(dialog).getByRole("textbox", { name: "Usage limit" }),
    );
    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() =>
      expect(screen.queryByRole("textbox", { name: "Usage limit" })).toBeNull(),
    );
    expect(dialog).not.toBeInTheDocument();
    expect(
      await within(ceiling()).findByText("None — no usage limit."),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "None — no usage limit., editable" }),
    ).toHaveFocus();
    const changed = sent.mock.calls.find(
      ([, init]) => init?.method === "PATCH",
    );
    expect(JSON.parse(String(changed![1]?.body))).toEqual({ ceiling: null });
  });

  it.each([
    [
      "the run is not in view",
      "RUN_NOT_IN_VIEW",
      "That run isn't here, or you can't see it.",
      0,
    ],
    [
      "the group is not the reader's",
      "GROUP_NOT_IN_VIEW",
      "That group is not in view.",
      1,
    ],
  ])(
    "says a read refused as %s in place, drawing nothing of the run, and reads the standing again only where it moved",
    async (_case, code, sentence, standingReads) => {
      const { readAgain } = opening({ [`GET ${RUN}`]: [refusal(code, 404)] });

      expect(await screen.findByRole("alert")).toHaveTextContent(sentence);
      expect(screen.queryByRole("heading", { level: 1 })).toBeNull();
      await waitFor(() =>
        expect(readAgain).toHaveBeenCalledTimes(standingReads),
      );
    },
  );
});
