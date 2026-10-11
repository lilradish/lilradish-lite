import { ThemeProvider } from "@mui/material/styles";
import { act, render, screen, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { RouterProvider, createMemoryRouter } from "react-router";
import { afterAll, describe, expect, it, vi } from "vitest";

import { expectConsoleErrors } from "../testutil/consoleErrors";
import { theme } from "../lib/theme/theme";
import { requestsTo, serving } from "../testutil/answering";
import { laidOutAt } from "../testutil/layout";
import { ROUTES } from "./routes";

const modules = vi.hoisted(() => {
  function held() {
    let release!: () => void;
    let entered!: () => void;
    let finished!: () => void;
    const gate = new Promise<void>((settle) => {
      release = settle;
    });
    const started = new Promise<void>((settle) => {
      entered = settle;
    });
    const completed = new Promise<void>((settle) => {
      finished = settle;
    });
    return {
      gate,
      started,
      completed,
      release,
      entered,
      finished,
      used: false,
    };
  }
  return {
    people: held(),
    entry: held(),
    entryFailure: new Error("The entry module could not load."),
  };
});

vi.mock(import("../features/people/PeoplePage"), async (importOriginal) => {
  modules.people.used = true;
  modules.people.entered();
  try {
    await modules.people.gate;
    return await importOriginal();
  } finally {
    modules.people.finished();
  }
});

vi.mock(import("../features/library/EntryPage"), async () => {
  modules.entry.used = true;
  modules.entry.entered();
  try {
    await modules.entry.gate;
    throw modules.entryFailure;
  } finally {
    modules.entry.finished();
  }
});

afterAll(async () => {
  modules.people.release();
  modules.entry.release();
  if (modules.people.used) await modules.people.completed;
  if (modules.entry.used) await modules.entry.completed;
});

const GROUP = "00000003-0000-4000-8000-000000000952";
const ENTRY = "00000006-0000-4000-8000-000000000952";
const GLINN = {
  subjectId: "00000002-0000-4000-8000-000000000140",
  userId: "000140",
  displayName: "Glinn Havven",
};

async function opening(address: string) {
  vi.stubGlobal("matchMedia", (query: string) => ({
    matches: /min-width/.test(query),
    media: query,
    addEventListener: () => {},
    removeEventListener: () => {},
  }));
  laidOutAt(0, 1920);
  const sent = serving({
    "GET /api/standing": [
      [
        JSON.stringify({
          acts: ["keep_pool", "check_soundness"],
          groups: [
            {
              groupId: GROUP,
              key: "TRIAGE",
              name: "Triage",
              permissions: ["start_run"],
            },
          ],
        }),
        200,
      ],
    ],
    "GET /api/pool/people": [
      [
        JSON.stringify({
          items: [{ ...GLINN, estateRoles: ["steward"], groupCount: 0 }],
        }),
        200,
      ],
    ],
  });
  const router = createMemoryRouter(ROUTES, { initialEntries: [address] });
  let drawn!: ReturnType<typeof render>;
  await act(async () => {
    drawn = render(
      <ThemeProvider theme={theme}>
        <RouterProvider router={router} />
      </ThemeProvider>,
    );
  });
  return { sent, router, unmount: drawn.unmount };
}

function pendingWithinFrame() {
  expect(screen.getByRole("link", { name: "Soundness" })).toBeInTheDocument();
  const main = screen.getByRole("main");
  const pending = within(main)
    .getAllByRole("status")
    .filter((each) => each.textContent === "Loading…");
  expect(pending).toHaveLength(1);
  expect(within(main).queryByRole("alert")).toBeNull();
  expect(screen.queryByRole("heading", { name: "No such screen" })).toBeNull();
  expect(
    screen.queryByRole("heading", { name: "Something went wrong." }),
  ).toBeNull();
}

describe("the frame while a page module loads", () => {
  it("keeps navigation and the pending screen until the real People module arrives", async () => {
    const { sent, router, unmount } = await opening("/system/people");
    try {
      await modules.people.started;
      pendingWithinFrame();
      expect(screen.queryByRole("heading", { name: "People" })).toBeNull();
      expect(requestsTo(sent)).toEqual(["GET /api/standing"]);

      await act(async () => {
        modules.people.release();
        await modules.people.completed;
      });

      expect(
        await screen.findByRole("heading", { name: "People" }),
      ).toBeInTheDocument();
      expect(
        await screen.findByRole("link", { name: GLINN.userId }),
      ).toBeInTheDocument();
      expect(
        screen.getByRole("link", { name: "Soundness" }),
      ).toBeInTheDocument();
      expect(requestsTo(sent)).toEqual([
        "GET /api/standing",
        "GET /api/pool/people?sort=userId",
      ]);
      expect(
        within(screen.getByRole("main")).queryByText("Loading…"),
      ).toBeNull();
    } finally {
      unmount();
      router.dispose();
      modules.people.release();
      if (modules.people.used) await modules.people.completed;
    }
  });

  it("contains a rejected Entry module within the frame and lets navigation leave its error", async () => {
    const { sent, router, unmount } = await opening(
      `/groups/TRIAGE/questions/${ENTRY}`,
    );
    try {
      await modules.entry.started;
      pendingWithinFrame();
      expect(requestsTo(sent)).toEqual(["GET /api/standing"]);

      await act(async () => {
        modules.entry.release();
        await modules.entry.completed;
      });

      expect(
        await screen.findByRole("heading", {
          name: "Something went wrong on this page.",
        }),
      ).toBeInTheDocument();
      const rejected = await import("../features/library/EntryPage").catch(
        (error: unknown) => error,
      );
      expect(rejected).toBeInstanceOf(Error);
      expect(rejected).toHaveProperty(
        "message",
        '[vitest] There was an error when mocking a module. If you are using "vi.mock" factory, make sure there are no top level variables inside, since this call is hoisted to top of the file. Read more: https://vitest.dev/api/vi.html#vi-mock',
      );
      expect(rejected).toHaveProperty("cause", modules.entryFailure);
      expectConsoleErrors(
        [
          "%o\n\n%s\n\n%s\n",
          rejected,
          "The above error occurred in one of your React components.",
          "React will try to recreate this component tree from scratch using the error boundary you provided, RenderErrorBoundary.",
        ],
        ["React Router caught the following error during render", rejected],
      );
      expect(
        screen.getByRole("link", { name: "Soundness" }),
      ).toBeInTheDocument();
      expect(screen.queryByText("The entry module could not load.")).toBeNull();
      expect(requestsTo(sent)).toEqual(["GET /api/standing"]);
      await userEvent.click(screen.getByRole("link", { name: "Soundness" }));
      expect(
        await screen.findByRole("heading", { name: "Soundness" }),
      ).toBeInTheDocument();
      expect(
        screen.queryByRole("heading", {
          name: "Something went wrong on this page.",
        }),
      ).toBeNull();
      expect(router.state.location.pathname).toBe("/system/soundness");
      expect(requestsTo(sent)).toEqual(["GET /api/standing"]);
    } finally {
      unmount();
      router.dispose();
      modules.entry.release();
      if (modules.entry.used) await modules.entry.completed;
    }
  });
});
