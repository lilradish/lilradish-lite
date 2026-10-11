import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { useId, useRef } from "react";

import type { GroupRule } from "../../app/standing/actRules";
import { say, type MessageId } from "../../i18n/app";
import { EditableText } from "../../lib/action/EditableText";
import { InfoMark } from "../../lib/action/InfoMark";
import { heldOr, QUIET } from "../../lib/action/Press";
import { SaveButton } from "../../lib/action/SaveButton";
import { Part } from "../../lib/layout/Part";
import { QUIET_SX } from "../../lib/layout/parts";
import { Notice } from "../../lib/notice/Notice";
import type { Action } from "../../lib/request/useAction";
import { usePartFocus } from "./usePartFocus";
import { useDraftPartStatus } from "./useDraftStatus";
import { useHeardRefusal } from "./useHeardRefusal";
import { useProseEdit } from "./useProseEdit";
import { WriteRefused } from "./WriteRefused";

const PROSE_SX = { whiteSpace: "pre-wrap", overflowWrap: "anywhere", m: 0 };

export interface ProseWords {
  readonly title: MessageId;
  readonly hint: MessageId;
  readonly saysNothing: MessageId;
  readonly emptyExplanation?: MessageId;
  /** Said behind an information mark beside the title wherever it may be written. */
  readonly explained?: { readonly about: MessageId; readonly hint: MessageId };
  readonly save: MessageId;
  readonly underway: MessageId;
  readonly edit: MessageId;
}

/** Optional prose is saved as none when emptied. */
export function ProsePart<T>({
  words,
  said,
  rewritten,
  editable,
  waiting,
  refusalOf,
  rule,
  action,
  save,
  focusOnArrival,
  onReadAfresh,
}: {
  readonly words: ProseWords;
  /** As the server last read it; absent where it says nothing. */
  readonly said: string | undefined;
  /** A successful related change rewrites only its references in any unsaved text. */
  readonly rewritten?: (text: string) => string;
  /** Whether the host may write it now, as the server said. */
  readonly editable: boolean;
  /** Whether another write of the version's is out, which this one would be refused beside. */
  readonly waiting: boolean;
  /** Why the server would refuse what is typed, in the refusal's own words, or null where it takes it. */
  readonly refusalOf: (typed: string) => MessageId | null;
  /** What writing asks, which a refusal of it is said as. */
  readonly rule: GroupRule;
  readonly action: Action<T>;
  readonly save: (prose: string | null, signal: AbortSignal) => Promise<T>;
  /** Where the part was drawn afresh by a press inside it, which is gone: the keyboard starts again here. */
  readonly focusOnArrival: boolean;
  readonly onReadAfresh: () => void;
}) {
  const { section, heading } = usePartFocus(focusOnArrival, editable);
  const titleId = useId();
  const { typed, editing, trigger, begin, cancel, saved, change } =
    useProseEdit(said, rewritten, editable, action.running, section);
  const refused = typed === "" ? null : refusalOf(typed);
  const unsaved = typed !== (said ?? "");
  const refusalAt = useRef<HTMLDivElement>(null);
  const heard = useHeardRefusal(action, unsaved, refusalAt);
  useDraftPartStatus(words.title, unsaved, action.running || waiting, {
    saving: action.running,
    node: section,
    locate: begin,
  });
  return (
    <Part
      title={say(words.title)}
      unsaved={editable && unsaved}
      level="h3"
      depth={2}
      headingId={titleId}
      headingRef={heading}
      ref={section}
      marker={
        editable && words.explained !== undefined ? (
          <InfoMark
            about={say(words.explained.about)}
            hint={say(words.explained.hint)}
          />
        ) : undefined
      }
    >
      {!editable ? (
        said === undefined ? (
          words.emptyExplanation === undefined ? (
            <Typography
              variant="body2"
              sx={{ ...QUIET_SX, typography: "systemHint" }}
            >
              {say(words.saysNothing)}
            </Typography>
          ) : (
            <Box component="details" sx={QUIET_SX}>
              <summary>{say(words.saysNothing)}</summary>
              <Typography
                variant="body2"
                sx={{ typography: "systemHint", mt: 1 }}
              >
                {say(words.emptyExplanation)}
              </Typography>
            </Box>
          )
        ) : (
          <Typography component="p" variant="body1" dir="auto" sx={PROSE_SX}>
            {said}
          </Typography>
        )
      ) : !editing ? (
        <EditableText ref={trigger} label={say(words.edit)} onEdit={begin}>
          {said === undefined ? (
            <Typography
              variant="body2"
              sx={{ ...QUIET_SX, mb: 0, typography: "systemHint" }}
            >
              {say(words.saysNothing)}
            </Typography>
          ) : (
            <Typography component="p" variant="body1" dir="auto" sx={PROSE_SX}>
              {said}
            </Typography>
          )}
        </EditableText>
      ) : (
        <>
          <TextField
            value={typed}
            onChange={(event) => {
              if (!action.running) {
                change(event.target.value);
              }
            }}
            error={refused !== null}
            helperText={say(refused ?? words.hint)}
            slotProps={{
              htmlInput: {
                dir: "auto",
                readOnly: action.running,
                "aria-labelledby": titleId,
              },
            }}
            multiline
            minRows={4}
            fullWidth
          />
          <Box sx={{ mt: 1 }}>
            <SaveButton
              unsaved={unsaved}
              action={action}
              waiting={waiting}
              reason={
                refused === null
                  ? undefined
                  : { severity: "warning", words: say(refused) }
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
              {say(words.save)}
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
            <Notice severity="info">{say(words.underway)}</Notice>
          ) : null}
        </>
      )}
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
