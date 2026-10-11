import FormControlLabel from "@mui/material/FormControlLabel";
import MenuItem from "@mui/material/MenuItem";
import Switch from "@mui/material/Switch";
import Typography from "@mui/material/Typography";
import {
  Fragment,
  useCallback,
  useLayoutEffect,
  useRef,
  useState,
} from "react";

import type { DeclaredField, SentField } from "../../api/declaration";
import type { Version } from "../../api/groups/{groupId}/{kind}";
import {
  readQuestion,
  type QuestionVersion,
} from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}";
import { draw } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}/drawing";
import { instruct } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}/instruction";
import { searching } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}/searching";
import { declare } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}/{side}";
import type { Problem } from "../../api/problem";
import { Async } from "../../app/Async";
import { say, type MessageId } from "../../i18n/app";
import { LabelledTextField } from "../../lib/form/LabelledTextField";
import { useAction } from "../../lib/request/useAction";
import { drawingTooDeep } from "../../lib/text/drawingDepth";
import {
  markInstruction,
  markPlaceholders,
  renamedInstruction,
  renamedPlaceholders,
} from "../../lib/text/placeholders";
import { readersIntl } from "../../i18n/intl";
import { Part as ContentPart } from "../../lib/layout/Part";
import { Notice } from "../../lib/notice/Notice";
import { isolatedInText } from "../../lib/direction/isolated";
import { showsInProse } from "../../lib/text/legibility";
import { VERSION_ACT_RULES } from "./actRules";
import { contentOf, type ReadContent } from "./ContentProblems";
import { DeclarationBuilder } from "./DeclarationBuilder";
import { InstructionPart, instructionRefused } from "./InstructionPart";
import type { Demands } from "./declarationDrafts";
import { ProsePart, type ProseWords } from "./ProsePart";
import QUESTION_DEMANDS from "./questionDemands.json";
import { useVersionContent } from "./useVersionContent";
import { useDraftPartStatus } from "./useDraftStatus";
import { WhatIsAdded } from "./WhatIsAdded";
import { WriteRefused } from "./WriteRefused";

const TAKES = QUESTION_DEMANDS.takes as Demands;

const GIVES = QUESTION_DEMANDS.gives as Demands;

const DRAWING_REFUSALS = {
  crlf: "refusal.PROSE_LINE_BREAK_CRLF",
  unusable: "refusal.DRAWING_UNUSABLE",
  too_deep: "refusal.DRAWING_TOO_DEEP",
} as const;

const NOT_DRAWING = /[^\t\n\P{Cc}]|[\p{Zl}\p{Zp}\p{Cs}]/u;

function drawingRefused(typed: string): MessageId | null {
  if (typed.includes("\r\n")) {
    return DRAWING_REFUSALS.crlf;
  }
  let length = 0;
  for (const _character of typed) {
    length += 1;
    if (length > 8192) {
      return DRAWING_REFUSALS.unusable;
    }
  }
  if (NOT_DRAWING.test(typed) || !showsInProse(typed)) {
    return DRAWING_REFUSALS.unusable;
  }
  return drawingTooDeep(typed) ? DRAWING_REFUSALS.too_deep : null;
}

const DRAWING_WORDS: ProseWords = {
  title: "question.drawing",
  hint: "question.drawingHint",
  saysNothing: "question.drawingEmpty",
  emptyExplanation: "question.drawingMissing",
  explained: {
    about: "question.drawingAbout",
    hint: "question.drawingExplained",
  },
  save: "question.saveDrawing",
  underway: "question.underway",
  edit: "prose.editDrawing",
};

/** Which part a press to read the version afresh came from, which is where the keyboard goes back to. */
type Part = "instruction" | "drawing" | "takes" | "gives" | "searching";

/** Each part is written on its own over the revision last shown; while one is out, or a read, nothing else is sent. */
export function QuestionContent({
  groupId,
  entryId,
  version,
  editable,
  readCount,
  onRefused,
  onWritten,
  onShown,
}: {
  readonly groupId: string;
  readonly entryId: string;
  readonly version: Version;
  readonly editable: boolean;
  /** Changed each time the page reads the entry again, which is when this reads the question again too. */
  readonly readCount: number;
  readonly onRefused: (problem: Problem) => void;
  /** Somebody wrote a part, here or elsewhere, so whatever the page holds of the version before it is stale. */
  readonly onWritten: () => void;
  readonly onShown: (content: ReadContent | null) => void;
}) {
  const [refreshedWay, setRefreshedWay] = useState<string | null>(null);
  const [pickedWay, setPickedWay] = useState("");
  const [at, setAt] = useState({
    groupId,
    entryId,
    versionId: version.versionId,
  });
  const mayWrite = useRef(editable);
  useLayoutEffect(() => {
    mayWrite.current = editable;
  }, [editable]);
  if (
    at.groupId !== groupId ||
    at.entryId !== entryId ||
    at.versionId !== version.versionId
  ) {
    setAt({ groupId, entryId, versionId: version.versionId });
    setRefreshedWay(null);
    setPickedWay("");
  }
  const load = useCallback(
    async (signal: AbortSignal): Promise<QuestionVersion | null> => {
      const question = await readQuestion(
        groupId,
        entryId,
        version.versionId,
        signal,
      );
      if (
        !mayWrite.current ||
        !question.searchShapeNeedsRefresh ||
        question.searchWay === undefined
      ) {
        return question;
      }
      const updated = await searching(
        groupId,
        entryId,
        version.versionId,
        question.revision,
        question.searchWay,
        signal,
      );
      if (!signal.aborted && updated.revision > question.revision) {
        setRefreshedWay(question.searchWay);
      }
      return updated;
    },
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
  const drawing = useAction<QuestionVersion>(written, onRefused);
  const [rewritten, setRewritten] = useState<{
    readonly revision: number;
    readonly versionId: string;
    readonly instruction: (text: string) => string;
    readonly drawing: (text: string) => string;
  } | null>(null);
  const taking = useAction<{
    readonly question: QuestionVersion;
    readonly groupId: string;
    readonly entryId: string;
    readonly versionId: string;
    readonly names: ReadonlyMap<string, string>;
  }>((saved) => {
    if (
      saved.groupId !== groupId ||
      saved.entryId !== entryId ||
      saved.versionId !== version.versionId ||
      (shown !== null && saved.question.revision <= shown.revision)
    )
      return;
    if (saved.names.size !== 0) {
      setRewritten({
        revision: saved.question.revision,
        versionId: saved.versionId,
        instruction: (text) => renamedInstruction(text, saved.names),
        drawing: (text) => renamedPlaceholders(text, saved.names),
      });
    }
    written(saved.question);
  }, onRefused);
  const giving = useAction<QuestionVersion>(written, onRefused);
  const searchingAction = useAction<QuestionVersion>(written, onRefused);
  const searchingSection = useRef<HTMLElement>(null);
  useDraftPartStatus(
    "read",
    false,
    read.loading ||
      instructing.running ||
      drawing.running ||
      taking.running ||
      giving.running,
  );
  useDraftPartStatus("search", false, searchingAction.running, {
    saving: searchingAction.running,
    node: searchingSection,
  });
  const question = shown;
  const selectedWay =
    question?.searchWay ??
    (question?.searchWays.some((way) => way.name === pickedWay)
      ? pickedWay
      : question?.searchWays.length === 1
        ? (question.searchWays[0]?.name ?? "")
        : "");
  const waiting =
    read.loading ||
    instructing.running ||
    drawing.running ||
    taking.running ||
    giving.running ||
    searchingAction.running;

  return (
    // Drawn from what is shown rather than what was read: a write landing mid-read neither waits on it nor goes.
    <Async read={read} empty={() => null}>
      {() =>
        question === null ? null : (
          <Fragment key={afresh.count}>
            <InstructionPart
              said={question.instruction}
              rewritten={
                rewritten?.versionId === version.versionId &&
                rewritten.revision === question.revision
                  ? rewritten.instruction
                  : undefined
              }
              takes={question.takes}
              editable={editable}
              waiting={
                searchingAction.running ||
                drawing.running ||
                taking.running ||
                giving.running ||
                read.loading
              }
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
            <ProsePart
              words={DRAWING_WORDS}
              said={question.drawing}
              rewritten={
                rewritten?.versionId === version.versionId &&
                rewritten.revision === question.revision
                  ? rewritten.drawing
                  : undefined
              }
              editable={editable}
              waiting={
                searchingAction.running ||
                instructing.running ||
                taking.running ||
                giving.running ||
                read.loading
              }
              refusalOf={drawingRefused}
              rule={VERSION_ACT_RULES.write}
              action={drawing}
              save={(drawingText, signal) =>
                draw(
                  groupId,
                  entryId,
                  version.versionId,
                  question.revision,
                  drawingText,
                  signal,
                )
              }
              focusOnArrival={afresh.from === "drawing"}
              onReadAfresh={readAfresh("drawing")}
            />
            <DeclarationBuilder
              title={say("question.takes")}
              heading="h3"
              depth={2}
              demands={TAKES}
              fields={question.takes}
              refusalOf={(fields) =>
                takesRefused(
                  question.takes,
                  fields,
                  question.instruction,
                  question.drawing,
                )
              }
              scope={`${groupId}:${entryId}:${version.versionId}:takes`}
              editable={editable}
              waiting={
                searchingAction.running ||
                instructing.running ||
                drawing.running ||
                giving.running ||
                read.loading
              }
              saveLabel={say("question.saveTakes")}
              underway={say("question.underway")}
              rule={VERSION_ACT_RULES.write}
              action={taking}
              save={async (fields, signal) => ({
                names: renamedInputs(question.takes, fields),
                groupId,
                entryId,
                versionId: version.versionId,
                question: await declare(
                  groupId,
                  entryId,
                  version.versionId,
                  "takes",
                  question.revision,
                  fields,
                  signal,
                ),
              })}
              focusOnArrival={afresh.from === "takes"}
              onReadAfresh={readAfresh("takes")}
            />
            {editable ? (
              <ContentPart
                title={say("question.forSearching")}
                ref={searchingSection}
                level="h3"
                depth={2}
              >
                <Typography variant="body2">
                  {say("question.forSearchingHint")}
                </Typography>
                <FormControlLabel
                  label={say("question.forSearching")}
                  control={
                    <Switch
                      checked={question.searchWay !== undefined}
                      disabled={
                        !editable ||
                        waiting ||
                        (question.searchWay === undefined && selectedWay === "")
                      }
                      onChange={(_event, checked) => {
                        if (
                          editable &&
                          !waiting &&
                          (!checked || selectedWay !== "")
                        ) {
                          searchingAction.run((signal) =>
                            searching(
                              groupId,
                              entryId,
                              version.versionId,
                              question.revision,
                              checked ? selectedWay : null,
                              signal,
                            ),
                          );
                        }
                      }}
                    />
                  }
                />
                <LabelledTextField
                  select
                  label={say("question.searchWith")}
                  value={selectedWay}
                  disabled={
                    !editable || waiting || question.searchWays.length === 0
                  }
                  onChange={(event) => {
                    if (!editable || waiting) return;
                    const way = event.target.value;
                    setPickedWay(way);
                    if (
                      question.searchWay !== undefined &&
                      way !== question.searchWay
                    ) {
                      searchingAction.run((signal) =>
                        searching(
                          groupId,
                          entryId,
                          version.versionId,
                          question.revision,
                          way,
                          signal,
                        ),
                      );
                    }
                  }}
                >
                  <MenuItem value="">{say("workflow.searchWayNone")}</MenuItem>
                  {question.searchWays.map((way) => (
                    <MenuItem key={way.name} value={way.name}>
                      {way.name}
                    </MenuItem>
                  ))}
                  {question.searchWay !== undefined &&
                  !question.searchWays.some(
                    (way) => way.name === question.searchWay,
                  ) ? (
                    <MenuItem value={question.searchWay}>
                      {question.searchWay}
                    </MenuItem>
                  ) : null}
                </LabelledTextField>
                {searchingAction.problem === null ? null : (
                  <WriteRefused
                    searchWay={selectedWay}
                    problem={searchingAction.problem}
                    rule={VERSION_ACT_RULES.write}
                    waiting={waiting}
                    onReadAfresh={readAfresh("searching")}
                  />
                )}
                {refreshedWay === null ? null : (
                  <Notice severity="info">
                    {say("question.searchShapeUpdated", {
                      way: isolatedInText(refreshedWay),
                    })}
                  </Notice>
                )}
              </ContentPart>
            ) : question.searchWay === undefined ? null : (
              <ContentPart
                title={say("question.forSearching")}
                level="h3"
                depth={2}
              >
                <Typography>
                  {say("question.searchUsed", {
                    way: isolatedInText(question.searchWay),
                  })}
                </Typography>
              </ContentPart>
            )}
            {question.searchWay === undefined ? null : (
              <Notice severity="info">
                {say("question.searchShapeLocked", {
                  way: isolatedInText(question.searchWay),
                })}{" "}
                {say("question.searchStandingHint", {
                  way: isolatedInText(question.searchWay),
                })}
              </Notice>
            )}
            <DeclarationBuilder
              title={say("question.gives")}
              acceptedWhen
              shapeLocked={question.searchWay !== undefined}
              searchWay={question.searchWay}
              heading="h3"
              depth={2}
              demands={GIVES}
              fields={question.gives}
              scope={`${groupId}:${entryId}:${version.versionId}:gives`}
              editable={editable}
              waiting={
                searchingAction.running ||
                instructing.running ||
                drawing.running ||
                taking.running ||
                read.loading
              }
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

function renamedInputs(
  before: readonly DeclaredField[],
  after: readonly SentField[],
): ReadonlyMap<string, string> {
  return new Map(
    before.flatMap((field) => {
      const next = after.find(
        (candidate) => candidate.fieldId === field.fieldId,
      );
      return next !== undefined && next.name !== field.name
        ? [[field.name, next.name] as const]
        : [];
    }),
  );
}

function takesRefused(
  before: readonly DeclaredField[],
  after: readonly SentField[],
  instruction: string | undefined,
  drawing: string | undefined,
): string | null {
  const removed = removedMentions(before, after, instruction, drawing);
  if (removed !== null) return removed;
  const names = renamedInputs(before, after);
  if (names.size === 0) return null;
  const instructionProblem = instructionRefused(
    renamedInstruction(instruction ?? "", names),
  );
  if (instructionProblem !== null) return say(instructionProblem);
  const drawingProblem =
    drawing === undefined
      ? null
      : drawingRefused(renamedPlaceholders(drawing, names));
  return drawingProblem === null ? null : say(drawingProblem);
}

function removedMentions(
  before: readonly DeclaredField[],
  after: readonly SentField[],
  instruction: string | undefined,
  drawing: string | undefined,
): string | null {
  const mentioned = new Set(
    [
      ...markInstruction(instruction ?? "").segments,
      ...markPlaceholders(drawing ?? "").segments,
    ].flatMap((segment) => ("name" in segment ? [segment.name] : [])),
  );
  const removed = before
    .filter(
      (field) =>
        mentioned.has(field.name) &&
        !after.some((next) => next.fieldId === field.fieldId),
    )
    .map((field) => `{{${field.name}}}`)
    .sort();
  return removed.length === 0
    ? null
    : say("refusal.INPUT_STILL_MENTIONED", {
        count: removed.length,
        names: new Intl.ListFormat(readersIntl.locale, {
          type: "conjunction",
        }).format(removed),
      });
}
