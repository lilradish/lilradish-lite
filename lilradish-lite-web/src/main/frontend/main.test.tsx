import { act, screen } from "@testing-library/react";
import type { ComponentProps } from "react";
import { describe, expect, it, vi } from "vitest";

import { reportCaught, reportRouterError } from "./lib/report/reportCaught";
import { serving } from "./testutil/answering";

const BROKEN = vi.hoisted(
  () => new TypeError("Cannot read properties of undefined (reading 'name')"),
);

const rootOptions = vi.hoisted(() => ({
  current: undefined as Parameters<
    typeof import("react-dom/client").createRoot
  >[1],
}));
const routerOptions = vi.hoisted(() => ({
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
      return actual.createRoot(...args);
    }),
  };
});

vi.mock("react-router/dom", async (importOriginal) => {
  const actual = await importOriginal<typeof import("react-router/dom")>();
  return {
    ...actual,
    RouterProvider: (props: ComponentProps<typeof actual.RouterProvider>) => {
      routerOptions.current = props.onError;
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
        element: <p>Reading</p>,
        loader: () => {
          throw BROKEN;
        },
        errorElement: <p role="alert">The read failed.</p>,
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
  vi.stubGlobal("IS_REACT_ACT_ENVIRONMENT", false);
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
  it("reports an error a screen throws once, through the one entry, and nothing else writes it down", async () => {
    const written = vi.spyOn(console, "error").mockImplementation(() => {});

    await startingAt("/system/soundness");

    expect(
      await screen.findByRole("heading", {
        level: 1,
        name: "Something went wrong on this page.",
      }),
    ).toBeInTheDocument();
    expect(vi.mocked(reportCaught)).toHaveBeenCalledOnce();
    expect(vi.mocked(reportCaught).mock.calls[0]?.[0]).toBe(BROKEN);
    expect(written.mock.calls).toEqual([[BROKEN]]);
  });

  it("connects uncaught and recoverable root errors to the same report entry", async () => {
    if (rootOptions.current === undefined) {
      await startingAt("/system/soundness");
      await screen.findByRole("heading", {
        level: 1,
        name: "Something went wrong on this page.",
      });
    }
    const written = vi.spyOn(console, "error").mockImplementation(() => {});
    const reported = vi.mocked(reportCaught);
    for (const callback of [
      rootOptions.current?.onUncaughtError,
      rootOptions.current?.onRecoverableError,
    ]) {
      reported.mockClear();
      written.mockClear();
      expect(callback).toBeTypeOf("function");
      callback?.(BROKEN, { componentStack: "" });
      expect(reported.mock.calls).toEqual([[BROKEN, { componentStack: "" }]]);
      expect(written.mock.calls).toEqual([[BROKEN]]);
    }
  });

  it("connects router errors to the router report entry without a second root report", async () => {
    if (routerOptions.current === undefined) {
      await startingAt("/system/soundness");
      await screen.findByRole("heading", {
        level: 1,
        name: "Something went wrong on this page.",
      });
    }
    const written = vi.spyOn(console, "error").mockImplementation(() => {});
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
    expect(written.mock.calls).toEqual([[BROKEN]]);
  });

  it("reports a real router loader failure once through the browser route", async () => {
    const written = vi.spyOn(console, "error").mockImplementation(() => {});
    if (routerOptions.current === undefined) {
      await startingAt("/reported-loader");
    } else {
      await act(async () => {
        window.history.pushState(null, "", "/reported-loader");
        window.dispatchEvent(new PopStateEvent("popstate"));
      });
    }

    expect(await screen.findByText("The read failed.")).toHaveAttribute(
      "role",
      "alert",
    );
    expect(written.mock.calls).toEqual([[BROKEN]]);
  });
});
