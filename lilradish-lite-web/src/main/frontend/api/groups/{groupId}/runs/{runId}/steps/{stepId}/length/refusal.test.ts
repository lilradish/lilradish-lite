import { describe, expect, it } from "vitest";

import {
  answering,
  refusalOf,
} from "../../../../../../../../testutil/answering";
import { refuseForLength, refusedAsChanged } from "./refusal";

describe("one length refusal", () => {
  it.each([
    "LENGTH_VALUE_USED",
    "LENGTH_VALUE_NOT_STANDING",
    "STEP_NOT_HELD_FOR_LENGTH",
  ])(
    "sends only the hold and single value, and recognizes served %s for rereading",
    async (code) => {
      const sent = answering([JSON.stringify({ code }), 409]);
      const failure = await refusalOf(
        refuseForLength(
          "group",
          "run",
          "step",
          "hold",
          "value",
          new AbortController().signal,
        ),
      );
      expect(sent).toHaveBeenCalledTimes(1);
      const [address, request] = sent.mock.calls[0]!;
      expect(address).toBe(
        "/api/groups/group/runs/run/steps/step/length/refusal",
      );
      expect(request?.method).toBe("PUT");
      expect(JSON.parse(String(request?.body))).toEqual({
        holdId: "hold",
        valueId: "value",
      });
      expect(new Headers(request?.headers).get("Content-Type")).toBe(
        "application/json",
      );
      expect(refusedAsChanged(failure.problem)).toBe(true);
      expect(refusedAsChanged({ code })).toBe(false);
      expect(refusedAsChanged({ status: 409, code: "STEP_MOVED_ON" })).toBe(
        false,
      );
    },
  );
});
