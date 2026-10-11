import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Typography from "@mui/material/Typography";
import { Fragment, useId, useRef } from "react";

import type { DeclaredField } from "../../api/declaration";
import type { GroupRule } from "../../app/standing/actRules";
import { say, type MessageId } from "../../i18n/app";
import { intl } from "../../i18n/intl";
import { visible } from "../../i18n/visible";
import { FieldName } from "../../lib/form/FieldName";
import { EditableText } from "../../lib/action/EditableText";
import { heldOr, QUIET } from "../../lib/action/Press";
import { SaveButton } from "../../lib/action/SaveButton";
import { Part } from "../../lib/layout/Part";
import { QUIET_SX } from "../../lib/layout/parts";
import { Notice } from "../../lib/notice/Notice";
import type { Action } from "../../lib/request/useAction";
import { InstructionText } from "../../lib/text/InstructionText";
import { proseRefused, type ProseRefusal } from "../../lib/text/legibility";
import { instructionNames } from "../../lib/text/placeholders";
import { useDraftPartStatus } from "./useDraftStatus";
import { useHeardRefusal } from "./useHeardRefusal";
import { usePartFocus } from "./usePartFocus";
import { useProseEdit } from "./useProseEdit";
import { WriteRefused } from "./WriteRefused";
import { joinedInSentence } from "../people/wordLists";

const PROSE_REFUSALS = {
  crlf: "refusal.PROSE_LINE_BREAK_CRLF",
  direction_control: "refusal.PROSE_DIRECTION_CONTROL",
  tag: "refusal.PROSE_TAG_CHARACTER",
  unusable: "refusal.INSTRUCTION_UNUSABLE",
} as const satisfies Record<ProseRefusal, MessageId>;

/** Judged on the text that will be kept, including references rewritten by an input's name change. */
export function instructionRefused(text: string): MessageId | null {
  const refusal = text === "" ? null : proseRefused(text);
  return refusal === null ? null : PROSE_REFUSALS[refusal];
}

/** The author's instruction is edited with marked input names, and saved without substituting any input value. */
export function InstructionPart<T>({
  said,
  rewritten,
  takes,
  editable,
  waiting,
  rule,
  action,
  save,
  focusOnArrival,
  onReadAfresh,
}: {
  readonly said: string | undefined;
  readonly rewritten?: (text: string) => string;
  readonly takes: readonly DeclaredField[];
  readonly editable: boolean;
  readonly waiting: boolean;
  readonly rule: GroupRule;
  readonly action: Action<T>;
  readonly save: (
    instruction: string | null,
    signal: AbortSignal,
  ) => Promise<T>;
  readonly focusOnArrival: boolean;
  readonly onReadAfresh: () => void;
}) {
  const { section, heading } = usePartFocus(focusOnArrival, editable);
  const description = useId();
  const { typed, editing, trigger, begin, cancel, saved, change } =
    useProseEdit(said, rewritten, editable, action.running, section);
  const names = instructionNames(typed);
  const unknown = names.filter(
    (name) => !takes.some((field) => field.name === name),
  );
  const unused = takes.filter((field) => !names.includes(field.name));
  const prose = instructionRefused(typed);
  const refused =
    prose !== null
      ? say(prose)
      : unknown.length === 0
        ? null
        : say("refusal.INSTRUCTION_NAMES_UNKNOWN", {
            count: unknown.length,
            names: joinedInSentence(unknown.map((name) => `{{${name}}}`)),
          });
  const unsaved = typed !== (said ?? "");
  const refusalAt = useRef<HTMLDivElement>(null);
  const heard = useHeardRefusal(action, unsaved, refusalAt);
  useDraftPartStatus(
    "question.instruction",
    unsaved,
    action.running || waiting,
    { saving: action.running, node: section, locate: begin },
  );
  return (
    <Part
      title={say("question.instruction")}
      level="h3"
      depth={2}
      headingRef={heading}
      ref={section}
      unsaved={editable && unsaved}
    >
      {editable && editing ? (
        <>
          <Typography
            id={description}
            variant="body2"
            color={refused === null ? "text.secondary" : "error"}
            sx={{ typography: "systemHint", mb: 2 }}
          >
            {refused ?? say("question.instructionHint")}
          </Typography>
          <InstructionText
            text={typed}
            takes={takes}
            editing={{
              label: say("question.instruction"),
              readOnly: action.running,
              invalid: refused !== null,
              describedBy: description,
              change: (value) => {
                change(value);
              },
            }}
          />
        </>
      ) : editable ? (
        <EditableText
          ref={trigger}
          label={say("prose.editInstruction")}
          onEdit={begin}
        >
          {said === undefined ? (
            <Typography variant="body2" sx={{ ...QUIET_SX, mb: 0 }}>
              {say("question.saysNothing")}
            </Typography>
          ) : (
            <InstructionText text={said} takes={takes} />
          )}
        </EditableText>
      ) : said === undefined ? (
        <Typography variant="body2" sx={QUIET_SX}>
          {say("question.saysNothing")}
        </Typography>
      ) : (
        <div>
          <InstructionText text={said} takes={takes} />
        </div>
      )}
      {editable && editing ? (
        <>
          {unused.length === 0 ? null : (
            <Notice severity="warning">
              {visible("question.inputsUnused", {
                count: unused.length,
                labels: (
                  <>
                    {new Intl.ListFormat(intl.defaultLocale, {
                      type: "conjunction",
                    })
                      .formatToParts(unused.map((_, index) => String(index)))
                      .map((part, index) => (
                        <Fragment key={index}>
                          {part.type === "literal" ? (
                            part.value
                          ) : (
                            <FieldName
                              context="author"
                              name={unused[Number(part.value)].name}
                              label={unused[Number(part.value)].label}
                            />
                          )}
                        </Fragment>
                      ))}
                  </>
                ),
              })}
            </Notice>
          )}
          <Box sx={{ mt: 1 }}>
            <SaveButton
              unsaved={unsaved}
              action={action}
              waiting={waiting}
              reason={
                refused === null
                  ? undefined
                  : { severity: "warning", words: refused }
              }
              act={(signal) => {
                heard.sent();
                return save(typed === "" ? null : typed, signal).then(
                  (answer) => {
                    saved();
                    return answer;
                  },
                );
              }}
            >
              {say("question.saveInstruction")}
            </SaveButton>
            <Button
              {...QUIET}
              {...heldOr(action.running, () => {
                heard.letGo();
                cancel();
              })}
            >
              {say("entry.abandon")}
            </Button>
          </Box>
          {action.running ? (
            <Notice severity="info">{say("question.underway")}</Notice>
          ) : null}
        </>
      ) : null}
      {heard.problem === null ? null : (
        <div ref={refusalAt} tabIndex={-1}>
          <WriteRefused
            settled={heard.settled}
            problem={heard.problem}
            rule={rule}
            waiting={waiting}
            onReadAfresh={onReadAfresh}
          />
        </div>
      )}
    </Part>
  );
}
