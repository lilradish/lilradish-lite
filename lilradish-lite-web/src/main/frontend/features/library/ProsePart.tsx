import Box from "@mui/material/Box";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { useState } from "react";

import type { GroupRule } from "../../app/standing/actRules";
import { say, type MessageId } from "../../i18n/app";
import { ActButton } from "../../lib/action/ActButton";
import { Part } from "../../lib/layout/Part";
import { QUIET_SX } from "../../lib/layout/parts";
import { Notice } from "../../lib/notice/Notice";
import type { Action } from "../../lib/request/useAction";
import { usePartFocus } from "./usePartFocus";
import { useDraftPartStatus } from "./useDraftStatus";
import { WriteRefused } from "./WriteRefused";

// Prose keeps its lines, and takes its direction from what is in it.
const PROSE_SX = { whiteSpace: "pre-wrap", overflowWrap: "anywhere", m: 0 };

/** The words a prose part is drawn with, each said where the reader meets it. */
export interface ProseWords {
  /** Heads the part and names its text box. */
  readonly title: MessageId;
  readonly hint: MessageId;
  /** Said where it is read and holds nothing. */
  readonly saysNothing: MessageId;
  readonly save: MessageId;
  readonly unchanged: MessageId;
  readonly underway: MessageId;
}

/** Optional prose is saved as none when emptied. */
export function ProsePart<T>({
  words,
  said,
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
  const [typed, setTyped] = useState(said ?? "");
  const [readAs, setReadAs] = useState(said);
  if (said !== readAs) {
    setReadAs(said);
    setTyped(said ?? "");
  }
  const [asked, setAsked] = useState(false);
  const refused = typed === "" ? null : refusalOf(typed);
  const unsaved = typed !== (said ?? "");
  useDraftPartStatus(words.title, unsaved, action.running || waiting);
  return (
    <Part
      title={say(words.title)}
      actions={
        editable && unsaved ? (
          <Typography component="span" variant="body2" color="text.secondary">
            {say("draft.unsaved")}
          </Typography>
        ) : undefined
      }
      level="h3"
      depth={2}
      headingRef={heading}
      ref={section}
    >
      {!editable ? (
        said === undefined ? (
          <Typography variant="body2" sx={QUIET_SX}>
            {say(words.saysNothing)}
          </Typography>
        ) : (
          <Typography component="p" variant="body1" dir="auto" sx={PROSE_SX}>
            {said}
          </Typography>
        )
      ) : (
        <>
          <TextField
            label={say(words.title)}
            value={typed}
            onChange={(edit) => {
              if (!action.running) {
                setTyped(edit.target.value);
              }
            }}
            error={refused !== null}
            helperText={say(refused ?? words.hint)}
            slotProps={{
              htmlInput: { dir: "auto", readOnly: action.running },
            }}
            multiline
            minRows={4}
            fullWidth
          />
          <Box sx={{ mt: 1 }}>
            <ActButton
              action={action}
              variant={unsaved ? "contained" : undefined}
              waiting={waiting}
              reason={
                refused !== null
                  ? { severity: "warning", words: say(refused) }
                  : typed === (said ?? "")
                    ? { severity: "info", words: say(words.unchanged) }
                    : undefined
              }
              act={(signal) => {
                setAsked(true);
                return save(typed === "" ? null : typed, signal);
              }}
            >
              {say(words.save)}
            </ActButton>
          </Box>
          {action.running ? (
            <Notice severity="info">{say(words.underway)}</Notice>
          ) : null}
        </>
      )}
      {!asked || action.problem === null ? null : (
        <WriteRefused
          problem={action.problem}
          rule={rule}
          waiting={waiting}
          onReadAfresh={onReadAfresh}
        />
      )}
    </Part>
  );
}
