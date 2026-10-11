import type { ReadField } from "../api/filling";
import type {
  Declared,
  StepRow,
} from "../api/groups/{groupId}/runs/{runId}/steps";
import type { StateRefusal } from "../api/stepActs";
import type { Where } from "../api/stepWhereabouts";
import type { Declaring } from "../features/runs/steps/declared";

export const UNKNOWN = "This page can't show this yet.";

export const NOBODY = "Nothing happens while the run is stopped.";

export const HELD_ENTRY_STOPPED =
  "What this step runs has been stopped, so nothing will be produced until it is allowed to be used again.";

export const STOPPED_AT = "2026-09-26T08:55:00Z";

export const setApart = (words: string) =>
  `${String.fromCodePoint(0x2068)}${words}${String.fromCodePoint(0x2069)}`;

export const NOBODY_ACTS = "Nothing can be done until the run is resumed.";

export const GIVES: readonly ReadField[] = [
  {
    name: "summary",
    label: "Summary",
    kind: "text",
    longest: 100,
    mustBeGiven: true,
  },
];

export const WAITING_ON_REVIEW: Where = {
  kind: "waiting_on_review",
  values: [{ field: "summary" }],
};

export const OWED: Where = { kind: "owed_try", open: true };

export const HELD: Where = {
  kind: "held_back",
  reason: "entry_stopped",
  stopped: {
    what: "entry",
    by: { userId: "000cb1", displayName: "Avo" },
    at: STOPPED_AT,
  },
};

export const FAILED: Where = { kind: "failed", reason: "tries_spent" };

export function step(
  state: string,
  where?: Where,
  stateRefusals: readonly StateRefusal[] = [],
): StepRow {
  return {
    stepId: "00000009-0000-4000-8000-000000000cb1",
    order: 1,
    name: "summarise",
    runs: { versionName: null, kind: "question" },
    state,
    takesFrom: [],
    cost: {},
    next: { number: 2, beyond: false },
    stateActs: new Set(),
    stateRefusals,
    producedByUserId: null,
    producedByKind: null,
    ...(where === undefined ? {} : { where }),
  };
}

const RUN_VERSION = "00000007-0000-4000-8000-000000000cb1";

export const TALLY: Declared = {
  takes: [
    {
      name: "line_items",
      label: "Line items",
      kind: "text",
      longest: 100,
      mustBeGiven: true,
    },
  ],
  gives: [
    {
      name: "receipt",
      label: "Receipt",
      kind: "fields",
      mustBeGiven: true,
      fields: [
        {
          name: "total",
          label: "Total",
          kind: "text",
          longest: 20,
          mustBeGiven: true,
        },
      ],
    },
  ],
};

export const LATER: StepRow = {
  ...step("not_started"),
  stepId: "0000000a-0000-4000-8000-000000000cb1",
  order: 2,
  name: "check_it",
  runs: { versionName: null, kind: "question", name: "Check the receipt" },
};

export const DECLARING: Declaring = {
  declarations: new Map([
    [
      RUN_VERSION,
      {
        takes: [],
        gives: [
          {
            name: "total_claimed",
            label: "Total claimed",
            kind: "text",
            longest: 20,
            mustBeGiven: true,
          },
        ],
      },
    ],
    ["tally", TALLY],
  ]),
  runId: "00000008-0000-4000-8000-000000000c91",
  runVersionId: RUN_VERSION,
  steps: [LATER],
};

export const RECEIPT_TOTAL = `${setApart("Receipt")} › ${setApart("Total")}`;
