import { describe, expect, it } from "vitest";

import { answering, refusalOf } from "../../../../../../../testutil/answering";
import { NOT_A_PROBLEM_DOCUMENT } from "../../../../../../problem";
import { nameVersion } from "./name";

const GROUP = "00000003-0000-4000-8000-000000000a71";
const ENTRY = "00000006-0000-4000-8000-000000000a71";
const VERSION = "00000007-0000-4000-8000-000000000a71";
const READ = {
  entryId: ENTRY,
  kind: "workflow",
  name: "Mooncrate handling",
  stateActs: [],
  versions: [
    {
      versionId: VERSION,
      number: 3,
      versionName: "Current name",
      standing: "retired",
      writers: [{ userId: "000001" }],
      writtenByMigration: false,
      stateActs: [],
      pinnedBy: [],
    },
  ],
};

describe("naming the version independently of content", () => {
  it.each(["Current name", "", "\u{1F4E6}".repeat(64), "Do not trim "])(
    "sends exactly the name string %j through the separate PUT",
    async (name) => {
      const sent = answering([JSON.stringify(READ), 200]);
      const signal = new AbortController().signal;
      const read = await nameVersion(
        GROUP,
        "workflow",
        ENTRY,
        VERSION,
        name,
        signal,
      );
      const [address, init] = sent.mock.calls[0]!;
      expect(address).toBe(
        `/api/groups/${GROUP}/workflows/${ENTRY}/versions/${VERSION}/name`,
      );
      expect(init?.method).toBe("PUT");
      expect(init?.signal).toBe(signal);
      expect(JSON.parse(String(init?.body))).toEqual({ name });
      expect(read.versions[0]?.versionName).toBe("Current name");
      expect(read.versions[0]?.standing).toBe("retired");
    },
  );

  it("keeps the server's version-name refusal as a problem without interpreting its diagnostic text", async () => {
    answering([
      JSON.stringify({ code: "VERSION_NAME_UNUSABLE", detail: "diagnostic" }),
      400,
    ]);
    const refused = await refusalOf(
      nameVersion(
        GROUP,
        "workflow",
        ENTRY,
        VERSION,
        "bad  spacing",
        new AbortController().signal,
      ),
    );
    expect(refused.problem.code).toBe("VERSION_NAME_UNUSABLE");
  });

  it.each([undefined, "", "\u{1F4E6}".repeat(65), 3, false])(
    "refuses a malformed current name in a successful response: %j",
    async (versionName) => {
      answering([
        JSON.stringify({
          ...READ,
          versions: [{ ...READ.versions[0], versionName }],
        }),
        200,
      ]);
      const refused = await refusalOf(
        nameVersion(
          GROUP,
          "workflow",
          ENTRY,
          VERSION,
          "Current name",
          new AbortController().signal,
        ),
      );
      expect(refused.problem.code).toBe(NOT_A_PROBLEM_DOCUMENT);
    },
  );
});
