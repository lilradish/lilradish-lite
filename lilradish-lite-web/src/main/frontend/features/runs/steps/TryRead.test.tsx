import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { Try } from "../../../api/stepTries";
import { StandingProvider } from "../../../app/standing/StandingContext";
import { deferred } from "../../../testutil/deferred";
import { requestsTo, serving, type Reply } from "../../../testutil/answering";
import { answered, inGroup } from "../../../testutil/standingRead";
import { RunReadAccess, useRunReadAccess } from "../RunReadAccess";
import { TryMaterial, TryRead } from "./TryRead";

const GROUP = "00000003-0000-4000-8000-000000000c51";
const RUN = "00000008-0000-4000-8000-000000000c51";
const STEP = "00000009-0000-4000-8000-000000000c51";
const PATH = `/api/groups/${GROUP}/runs/${RUN}/steps/${STEP}/tries`;
const GROUP_STANDING = inGroup(GROUP, "CLAIMS", "Imaginary claims", [
  "read_own_runs",
  "read_inference_content",
]);

function attempt(number = 1): Try {
  return {
    number,
    beyond: false,
    producedBy: { kind: "model", model: "Imaginary model" },
    values: [],
    ended: "stands",
    cost: {},
    wentWrongPresent: false,
    returnedPresent: false,
    declinedPresent: false,
    unreadableResponsePresent: false,
    modelReplyPresent: true,
    review: {
      asked: false,
      declinedPresent: false,
      unreadableResponsePresent: false,
      modelReplyPresent: false,
    },
  };
}

function material(number: number, text: string): Reply {
  return [
    JSON.stringify({
      number,
      modelReply: { text, altered: false, truncated: false },
    }),
    200,
  ];
}

function Guard() {
  const access = useRunReadAccess();
  return <span>{access.problem === null ? "Run visible" : "Run refused"}</span>;
}

function drawing(
  shown: Try | undefined,
  waiting?: Try,
  userId = "reader-a",
  permissions = GROUP_STANDING.permissions,
  stepId = STEP,
) {
  const group = { ...GROUP_STANDING, permissions };
  return (
    <StandingProvider read={answered([], [group], userId)}>
      <RunReadAccess>
        <Guard />
        <TryRead
          group={group}
          runId={RUN}
          stepId={stepId}
          shown={shown}
          waiting={waiting}
        >
          <TryMaterial number={1}>
            {(details) => (
              <span data-testid="one">
                {details?.modelReply?.text ?? "No material one"}
                {details?.review?.modelReply?.text}
              </span>
            )}
          </TryMaterial>
          <TryMaterial number={2}>
            {(details) => (
              <span data-testid="two">
                {details?.modelReply?.text ?? "No material two"}
              </span>
            )}
          </TryMaterial>
        </TryRead>
      </RunReadAccess>
    </StandingProvider>
  );
}

describe("TryRead", () => {
  it.each([200, 404])(
    "ignores a late old-route response with status %i even when transport delivers after abort",
    async (status) => {
      const late = deferred<Response>();
      const sent = vi
        .fn<typeof fetch>()
        .mockImplementationOnce(() => late.promise)
        .mockResolvedValueOnce(
          new Response(
            JSON.stringify({
              number: 1,
              modelReply: {
                text: "Imaginary new route",
                altered: false,
                truncated: false,
              },
            }),
            { status: 200 },
          ),
        );
      vi.stubGlobal("fetch", sent);
      const drawn = render(drawing(attempt()));
      await waitFor(() => expect(sent).toHaveBeenCalledTimes(1));
      const signal = sent.mock.calls[0]![1]!.signal!;
      drawn.rerender(
        drawing(
          attempt(),
          undefined,
          "reader-a",
          GROUP_STANDING.permissions,
          "00000009-0000-4000-8000-000000000c52",
        ),
      );
      expect(signal.aborted).toBe(true);
      expect(await screen.findByText("Imaginary new route")).toBeVisible();
      await act(async () =>
        late.settle(
          new Response(
            JSON.stringify(
              status === 200
                ? {
                    number: 1,
                    modelReply: {
                      text: "Imaginary abandoned route",
                      altered: false,
                      truncated: false,
                    },
                  }
                : { code: "RUN_NOT_IN_VIEW" },
            ),
            { status },
          ),
        ),
      );
      expect(screen.queryByText("Imaginary abandoned route")).toBeNull();
      expect(screen.getByText("Imaginary new route")).toBeVisible();
      expect(screen.getByText("Run visible")).toBeVisible();
      expect(sent).toHaveBeenCalledTimes(2);
    },
  );
  it("shares one read for the displayed and waiting attempt and ignores fresh equivalent poll objects", async () => {
    const sent = serving({
      [`GET ${PATH}/1`]: [material(1, "Imaginary original")],
    });
    const drawn = render(drawing(attempt(), attempt()));
    expect(await screen.findByText("Imaginary original")).toBeVisible();
    drawn.rerender(drawing(attempt(), attempt()));
    await act(async () => undefined);
    expect(requestsTo(sent)).toEqual([`GET ${PATH}/1`]);
  });

  it("keeps two reads in their slots when the displayed and waiting numbers exchange", async () => {
    const sent = serving({
      [`GET ${PATH}/1`]: [material(1, "Imaginary one")],
      [`GET ${PATH}/2`]: [material(2, "Imaginary two")],
    });
    const drawn = render(drawing(attempt(1), attempt(2)));
    expect(await screen.findByText("Imaginary one")).toBeVisible();
    expect(await screen.findByText("Imaginary two")).toBeVisible();
    drawn.rerender(drawing(attempt(2), attempt(1)));
    await act(async () => undefined);
    expect(requestsTo(sent)).toEqual([`GET ${PATH}/1`, `GET ${PATH}/2`]);
  });

  it("clears the same number immediately and reads again when its review finishes later", async () => {
    const later = deferred<readonly [string, number]>();
    const sent = serving({
      [`GET ${PATH}/1`]: [material(1, "Imaginary production"), later.promise],
    });
    const drawn = render(drawing(attempt()));
    expect(await screen.findByText("Imaginary production")).toBeVisible();
    drawn.rerender(
      drawing({
        ...attempt(),
        review: {
          asked: true,
          by: { kind: "model", model: "Imaginary reviewer" },
          declinedPresent: false,
          unreadableResponsePresent: false,
          modelReplyPresent: true,
        },
      }),
    );
    expect(screen.queryByText("Imaginary production")).toBeNull();
    await waitFor(() => expect(sent).toHaveBeenCalledTimes(2));
    await act(async () =>
      later.settle([
        JSON.stringify({
          number: 1,
          modelReply: {
            text: "Imaginary production",
            altered: false,
            truncated: false,
          },
          review: {
            modelReply: {
              text: "Imaginary review",
              altered: false,
              truncated: false,
            },
          },
        }),
        200,
      ]),
    );
    expect(screen.getByTestId("one")).toHaveTextContent("Imaginary review");
  });

  it("aborts closed material and rereads it on reopening without reviving a delayed answer", async () => {
    const late = deferred<readonly [string, number]>();
    const sent = serving({
      [`GET ${PATH}/1`]: [late.promise, material(1, "Imaginary reopened")],
    });
    const drawn = render(drawing(attempt()));
    await waitFor(() => expect(sent).toHaveBeenCalledTimes(1));
    const signal = sent.mock.calls[0]![1]!.signal!;
    drawn.rerender(drawing(undefined));
    expect(signal.aborted).toBe(true);
    await act(async () =>
      late.settle(
        material(1, "Imaginary abandoned") as readonly [string, number],
      ),
    );
    expect(screen.queryByText("Imaginary abandoned")).toBeNull();
    drawn.rerender(drawing(attempt()));
    expect(await screen.findByText("Imaginary reopened")).toBeVisible();
    expect(sent).toHaveBeenCalledTimes(2);
  });

  it.each(["identity", "inference", "all reads"])(
    "clears old material synchronously on a change of %s",
    async (change) => {
      const fresh = deferred<readonly [string, number]>();
      const sent = serving({
        [`GET ${PATH}/1`]: [
          material(1, "Imaginary private answer"),
          fresh.promise,
        ],
      });
      const drawn = render(drawing(attempt()));
      expect(await screen.findByText("Imaginary private answer")).toBeVisible();
      drawn.rerender(
        drawing(
          attempt(),
          undefined,
          change === "identity" ? "reader-b" : "reader-a",
          new Set(change === "all reads" ? [] : ["read_own_runs"]),
        ),
      );
      expect(screen.queryByText("Imaginary private answer")).toBeNull();
      await act(async () => undefined);
      expect(sent).toHaveBeenCalledTimes(change === "all reads" ? 1 : 2);
      await act(async () => fresh.settle([JSON.stringify({ number: 1 }), 200]));
      expect(screen.queryByText("Imaginary private answer")).toBeNull();
    },
  );

  it("retries a local material failure without rereading the other number", async () => {
    const sent = serving({
      [`GET ${PATH}/1`]: [
        [JSON.stringify({ code: "TEMPORARY_FAILURE" }), 503],
        material(1, "Imaginary recovered"),
      ],
      [`GET ${PATH}/2`]: [material(2, "Imaginary waiting")],
    });
    render(drawing(attempt(1), attempt(2)));
    expect(await screen.findByText("Imaginary waiting")).toBeVisible();
    fireEvent.click(
      await screen.findByRole("button", { name: "Try loading again" }),
    );
    expect(await screen.findByText("Imaginary recovered")).toBeVisible();
    expect(requestsTo(sent).filter((path) => path.endsWith("/2"))).toHaveLength(
      1,
    );
  });

  it("closes both materials for a served run refusal", async () => {
    const sent = serving({
      [`GET ${PATH}/1`]: [[JSON.stringify({ code: "RUN_NOT_IN_VIEW" }), 404]],
      [`GET ${PATH}/2`]: [material(2, "Imaginary other raw")],
    });
    render(drawing(attempt(1), attempt(2)));
    expect(await screen.findByText("Run refused")).toBeVisible();
    expect(screen.queryByText("Imaginary other raw")).toBeNull();
    expect(requestsTo(sent)).toEqual([`GET ${PATH}/1`, `GET ${PATH}/2`]);
    const signals = sent.mock.calls.map(([, init]) => init?.signal);
    expect(signals).toHaveLength(2);
    expect(
      signals.every((signal) => signal !== undefined && signal !== null),
    ).toBe(true);
    await waitFor(() =>
      expect(signals.every((signal) => signal?.aborted === true)).toBe(true),
    );
  });
});
