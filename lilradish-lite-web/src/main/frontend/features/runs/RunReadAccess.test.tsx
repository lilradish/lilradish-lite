import { act, fireEvent, render, screen } from "@testing-library/react";
import { StrictMode, useState } from "react";
import { describe, expect, it } from "vitest";

import { RequestFailed } from "../../api/problem";
import { deferred } from "../../testutil/deferred";
import { RunReadAccess, useRunReadAccess } from "./RunReadAccess";

function Reader() {
  const access = useRunReadAccess();
  const [controller] = useState(() => new AbortController());
  const [held] = useState(() => deferred<string>());
  const ask = (status?: number) => {
    void access
      .read(controller.signal, () =>
        Promise.reject(new RequestFailed({ status, code: "RUN_NOT_IN_VIEW" })),
      )
      .catch(() => undefined);
  };
  return (
    <>
      <span>{access.problem === null ? "Run shown" : "Run withheld"}</span>
      <button onClick={() => ask(404)}>Served refusal</button>
      <button onClick={() => ask()}>Local failure</button>
      <button
        onClick={() => {
          void access
            .read(controller.signal, () => held.promise)
            .catch(() => undefined);
        }}
      >
        Start delayed read
      </button>
      <button
        onClick={() => {
          controller.abort();
          held.refuse(
            new RequestFailed({ status: 404, code: "RUN_NOT_IN_VIEW" }),
          );
        }}
      >
        Abandon delayed read
      </button>
      <button
        onClick={() => {
          void access.read(new AbortController().signal, () =>
            Promise.resolve("read"),
          );
        }}
      >
        Other success
      </button>
    </>
  );
}

function Routes() {
  const [run, setRun] = useState("first");
  return (
    <>
      <button onClick={() => setRun("second")}>Other run</button>
      <RunReadAccess key={run}>
        <Reader />
      </RunReadAccess>
    </>
  );
}

describe("RunReadAccess", () => {
  it("closes only for a current served run refusal, then stays closed until the run changes", async () => {
    render(
      <StrictMode>
        <Routes />
      </StrictMode>,
    );
    fireEvent.click(screen.getByRole("button", { name: "Local failure" }));
    expect(await screen.findByText("Run shown")).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "Served refusal" }));
    expect(await screen.findByText("Run withheld")).toBeVisible();
    await act(async () =>
      fireEvent.click(screen.getByRole("button", { name: "Other success" })),
    );
    expect(screen.getByText("Run withheld")).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "Other run" }));
    expect(screen.getByText("Run shown")).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "Start delayed read" }));
    await act(async () =>
      fireEvent.click(
        screen.getByRole("button", { name: "Abandon delayed read" }),
      ),
    );
    expect(screen.getByText("Run shown")).toBeVisible();
  });
});
