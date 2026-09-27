import { Fragment, useCallback } from "react";

import type { Version } from "../../api/groups/{groupId}/{kind}";
import {
  readQuestion,
  type QuestionVersion,
} from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}";
import { instruct } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}/instruction";
import { declare } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}/{side}";
import type { Problem } from "../../api/problem";
import { Async } from "../../app/Async";
import { say, type MessageId } from "../../i18n/app";
import { useAction } from "../../lib/request/useAction";
import { proseRefused, type ProseRefusal } from "../../lib/text/legibility";
import { VERSION_ACT_RULES } from "./actRules";
import { contentOf, type ReadContent } from "./ContentProblems";
import { DeclarationBuilder } from "./DeclarationBuilder";
import type { Demands } from "./declarationDrafts";
import { ProsePart, type ProseWords } from "./ProsePart";
import QUESTION_DEMANDS from "./questionDemands.json";
import { useVersionContent } from "./useVersionContent";
import { WhatIsAdded } from "./WhatIsAdded";

const TAKES = QUESTION_DEMANDS.takes as Demands;

const GIVES = QUESTION_DEMANDS.gives as Demands;

/** Each reason the server refuses an instruction, said as the refusal it would answer with. */
const PROSE_REFUSALS = {
  crlf: "refusal.PROSE_LINE_BREAK_CRLF",
  direction_control: "refusal.PROSE_DIRECTION_CONTROL",
  tag: "refusal.PROSE_TAG_CHARACTER",
  unusable: "refusal.INSTRUCTION_UNUSABLE",
} as const satisfies Record<ProseRefusal, MessageId>;

const INSTRUCTION_WORDS: ProseWords = {
  title: "question.instruction",
  hint: "question.instructionHint",
  saysNothing: "question.saysNothing",
  save: "question.saveInstruction",
  unchanged: "question.instructionUnchanged",
  underway: "question.underway",
};

/** Which part a press to read the version afresh came from, which is where the keyboard goes back to. */
type Part = "instruction" | "takes" | "gives";

/** Each part is written on its own over the revision last shown; while one is out, or a read, nothing else is sent. */
export function QuestionContent({
  groupId,
  entryId,
  version,
  readCount,
  onRefused,
  onWritten,
  onShown,
}: {
  readonly groupId: string;
  readonly entryId: string;
  readonly version: Version;
  /** Changed each time the page reads the entry again, which is when this reads the question again too. */
  readonly readCount: number;
  readonly onRefused: (problem: Problem) => void;
  /** Somebody wrote a part, here or elsewhere, so whatever the page holds of the version before it is stale. */
  readonly onWritten: () => void;
  readonly onShown: (content: ReadContent | null) => void;
}) {
  const load = useCallback(
    (signal: AbortSignal): Promise<QuestionVersion | null> =>
      readQuestion(groupId, entryId, version.versionId, signal),
    [groupId, entryId, version.versionId],
  );
  const shownAs = useCallback(
    (shown: QuestionVersion | null) =>
      onShown(shown === null ? null : contentOf(shown)),
    [onShown],
  );
  const { read, shown, written, afresh, readAfresh } = useVersionContent<
    QuestionVersion,
    Part
  >(load, readCount, onWritten, shownAs);
  const instructing = useAction<QuestionVersion>(written, onRefused);
  const taking = useAction<QuestionVersion>(written, onRefused);
  const giving = useAction<QuestionVersion>(written, onRefused);
  const editable = version.acts.has("write");
  const question = shown;

  return (
    // Drawn from what is shown rather than what was read: a write landing mid-read neither waits on it nor goes.
    <Async read={read} empty={() => null}>
      {() =>
        question === null ? null : (
          <Fragment key={afresh.count}>
            <ProsePart
              words={INSTRUCTION_WORDS}
              said={question.instruction}
              editable={editable}
              waiting={taking.running || giving.running || read.loading}
              refusalOf={(typed) => {
                const refused = proseRefused(typed);
                return refused === null ? null : PROSE_REFUSALS[refused];
              }}
              rule={VERSION_ACT_RULES.write}
              action={instructing}
              save={(instruction, signal) =>
                instruct(
                  groupId,
                  entryId,
                  version.versionId,
                  question.revision,
                  instruction,
                  signal,
                )
              }
              focusOnArrival={afresh.from === "instruction"}
              onReadAfresh={readAfresh("instruction")}
            />
            <DeclarationBuilder
              title={say("question.takes")}
              heading="h3"
              demands={TAKES}
              fields={question.takes}
              lists={question.lists}
              editable={editable}
              waiting={instructing.running || giving.running || read.loading}
              saveLabel={say("question.saveTakes")}
              underway={say("question.underway")}
              rule={VERSION_ACT_RULES.write}
              action={taking}
              save={(fields, signal) =>
                declare(
                  groupId,
                  entryId,
                  version.versionId,
                  "takes",
                  question.revision,
                  fields,
                  signal,
                )
              }
              focusOnArrival={afresh.from === "takes"}
              onReadAfresh={readAfresh("takes")}
            />
            <DeclarationBuilder
              title={say("question.gives")}
              heading="h3"
              demands={GIVES}
              fields={question.gives}
              lists={question.lists}
              editable={editable}
              waiting={instructing.running || taking.running || read.loading}
              saveLabel={say("question.saveGives")}
              underway={say("question.underway")}
              rule={VERSION_ACT_RULES.write}
              action={giving}
              save={(fields, signal) =>
                declare(
                  groupId,
                  entryId,
                  version.versionId,
                  "gives",
                  question.revision,
                  fields,
                  signal,
                )
              }
              focusOnArrival={afresh.from === "gives"}
              onReadAfresh={readAfresh("gives")}
            />
            <WhatIsAdded added={question.added} />
          </Fragment>
        )
      }
    </Async>
  );
}
