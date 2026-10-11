import { expectConsoleErrors } from "./testutil/consoleErrors";
import { act, screen } from "@testing-library/react";
import { createElement, type ComponentProps } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import "./app/routes";

import { reportCaught, reportRouterError } from "./lib/report/reportCaught";
import { serving } from "./testutil/answering";

const BROKEN = vi.hoisted(
  () => new TypeError("Cannot read properties of undefined (reading 'name')"),
);

const rootOptions = vi.hoisted(() => ({
  root: undefined as
    ReturnType<typeof import("react-dom/client").createRoot> | undefined,
  current: undefined as Parameters<
    typeof import("react-dom/client").createRoot
  >[1],
}));
const routerOptions = vi.hoisted(() => ({
  router: undefined as
    | ComponentProps<typeof import("react-router/dom").RouterProvider>["router"]
    | undefined,
  current: undefined as ComponentProps<
    typeof import("react-router/dom").RouterProvider
  >["onError"],
}));

vi.mock("react-dom/client", async (importOriginal) => {
  const actual = await importOriginal<typeof import("react-dom/client")>();
  return {
    ...actual,
    createRoot: vi.fn((...args: Parameters<typeof actual.createRoot>) => {
      rootOptions.current = args[1];
      rootOptions.root = actual.createRoot(...args);
      return rootOptions.root;
    }),
  };
});

vi.mock("react-router/dom", async (importOriginal) => {
  const actual = await importOriginal<typeof import("react-router/dom")>();
  return {
    ...actual,
    RouterProvider: (props: ComponentProps<typeof actual.RouterProvider>) => {
      routerOptions.current = props.onError;
      routerOptions.router = props.router;
      return <actual.RouterProvider {...props} />;
    },
  };
});

vi.mock("./app/routes", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./app/routes")>();
  return {
    ...actual,
    ROUTES: [
      ...actual.ROUTES,
      {
        path: "/reported-loader",
        element: createElement("p", null, "Reading"),
        loader: () => {
          throw BROKEN;
        },
        errorElement: createElement("p", { role: "alert" }, "The read failed."),
      },
    ],
  };
});

vi.mock("./lib/report/reportCaught", async (importOriginal) => {
  const actual =
    await importOriginal<typeof import("./lib/report/reportCaught")>();
  return { ...actual, reportCaught: vi.fn(actual.reportCaught) };
});

vi.mock("./app/SystemPage", () => ({
  SystemPage: () => {
    throw BROKEN;
  },
}));

/**
 * The application as a browser starts it, which is not through `act`: left on,
 * React writes a warning of its own to the console for every update.
 */
async function startingAt(path: string) {
  (
    globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }
  ).IS_REACT_ACT_ENVIRONMENT = false;
  serving({
    "GET /api/standing": [
      [JSON.stringify({ acts: ["check_soundness"], groups: [] }), 200],
    ],
  });
  document.body.innerHTML = '<div id="root"></div>';
  window.history.replaceState(null, "", path);
  await import("./main");
}

describe("main", () => {
  beforeEach(() => {
    const actEnvironment = Object.getOwnPropertyDescriptor(
      globalThis,
      "IS_REACT_ACT_ENVIRONMENT",
    )!;
    const fetch = Object.getOwnPropertyDescriptor(globalThis, "fetch")!;
    vi.resetModules();
    rootOptions.root = undefined;
    rootOptions.current = undefined;
    routerOptions.router = undefined;
    routerOptions.current = undefined;
    return async () => {
      try {
        await act(async () => {
          rootOptions.root?.unmount();
        });
      } finally {
        try {
          routerOptions.router?.dispose();
        } finally {
          document.body.replaceChildren();
          Object.defineProperty(
            globalThis,
            "IS_REACT_ACT_ENVIRONMENT",
            actEnvironment,
          );
          Object.defineProperty(globalThis, "fetch", fetch);
        }
      }
    };
  });

  it("reports an error a screen throws once, through the one entry, and nothing else writes it down", async () => {
    await startingAt("/system/soundness");

    expect(
      await screen.findByRole("heading", {
        level: 1,
        name: "Something went wrong on this page.",
      }),
    ).toBeInTheDocument();
    expect(vi.mocked(reportCaught)).toHaveBeenCalledOnce();
    expect(vi.mocked(reportCaught).mock.calls[0]?.[0]).toBe(BROKEN);
    expectConsoleErrors([BROKEN]);
  });

  it("connects uncaught and recoverable root errors to the same report entry", async () => {
    await startingAt("/system/soundness");
    await screen.findByRole("heading", {
      level: 1,
      name: "Something went wrong on this page.",
    });
    expectConsoleErrors([BROKEN]);
    const reported = vi.mocked(reportCaught);
    for (const callback of [
      rootOptions.current?.onUncaughtError,
      rootOptions.current?.onRecoverableError,
    ]) {
      reported.mockClear();
      expect(callback).toBeTypeOf("function");
      callback?.(BROKEN, { componentStack: "" });
      expect(reported.mock.calls).toEqual([[BROKEN, { componentStack: "" }]]);
      expectConsoleErrors([BROKEN]);
    }
  });

  it("connects router errors to the router report entry without a second root report", async () => {
    await startingAt("/system/soundness");
    await screen.findByRole("heading", {
      level: 1,
      name: "Something went wrong on this page.",
    });
    expectConsoleErrors([BROKEN]);
    const reported = vi.mocked(reportCaught);
    reported.mockClear();
    expect(routerOptions.current).toBe(reportRouterError);

    routerOptions.current?.(BROKEN, {
      location: {
        pathname: "/system/soundness",
        search: "",
        hash: "",
        state: null,
        key: "default",
      },
      params: {},
      pattern: "/system/soundness",
    });

    expect(reported).not.toHaveBeenCalled();
    expectConsoleErrors([BROKEN]);
  });

  it("reports a real router loader failure once through the browser route", async () => {
    await startingAt("/system/soundness");
    await screen.findByRole("heading", {
      level: 1,
      name: "Something went wrong on this page.",
    });
    expectConsoleErrors([BROKEN]);

    await act(async () => {
      window.history.pushState(null, "", "/reported-loader");
      window.dispatchEvent(new PopStateEvent("popstate"));
    });

    expect(await screen.findByText("The read failed.")).toHaveAttribute(
      "role",
      "alert",
    );
    expectConsoleErrors([BROKEN]);
  });
});
