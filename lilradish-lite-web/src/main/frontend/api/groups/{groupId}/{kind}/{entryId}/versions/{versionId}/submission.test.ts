import { describe, expect, it } from "vitest";

import { answering } from "../../../../../../../testutil/answering";
import { submit, withdraw } from "./submission";

const GROUP = "00000003-0000-4000-8000-000000000ab1";

const ENTRY = "00000006-0000-4000-8000-000000000ab1";

const VERSION = "00000007-0000-4000-8000-000000000ab1";

const READ = {
  entryId: ENTRY,
  kind: "workflow",
  name: "Handle",
  acts: [],
  versions: [],
};

describe.each([
  ["submit", submit, "PUT"],
  ["withdraw", withdraw, "DELETE"],
] as const)("%s", (_name, change, method) => {
  it(`asks ${method} of the version's submission, sending nothing, and reads the entry it answers with`, async () => {
    const sent = answering([JSON.stringify(READ), 200]);

    const read = await change(
      GROUP,
      "workflow",
      ENTRY,
      VERSION,
      new AbortController().signal,
    );

    const [address, init] = sent.mock.calls[0]!;
    expect(address).toBe(
      `/api/groups/${GROUP}/workflows/${ENTRY}/versions/${VERSION}/submission`,
    );
    expect(init?.method).toBe(method);
    expect(init?.body).toBeUndefined();
    expect(read.entryId).toBe(ENTRY);
  });
});
