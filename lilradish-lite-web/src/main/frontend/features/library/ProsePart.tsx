import Box from "@mui/material/Box";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { useId, useState } from "react";

import type { GroupRule } from "../../app/standing/actRules";
import { say, type MessageId } from "../../i18n/app";
import { ActButton } from "../../lib/action/ActButton";
import { QUIET_SX } from "../../lib/layout/parts";
import { Notice } from "../../lib/notice/Notice";
import type { Action } from "../../lib/request/useAction";
import { usePartFocus } from "./usePartFocus";
import { WriteRefused } from "./WriteRefused";

const PART_SX = { mb: 3 };

const HEADING_SX = { mb: 1 };

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

/** Prose sent to a model: emptied, it is saved as none rather than as an empty one. */
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
  const headingId = useId();
  const { section, heading } = usePartFocus(focusOnArrival, editable);
  const [typed, setTyped] = useState(said ?? "");
  const [readAs, setReadAs] = useState(said);
  if (said !== readAs) {
    setReadAs(said);
    setTyped(said ?? "");
  }
  const [asked, setAsked] = useState(false);
  const refused = typed === "" ? null : refusalOf(typed);
  return (
    <Box
      component="section"
      ref={section}
      aria-labelledby={headingId}
      sx={PART_SX}
    >
      <Typography
        id={headingId}
        ref={heading}
        tabIndex={-1}
        variant="h6"
        component="h3"
        sx={HEADING_SX}
      >
        {say(words.title)}
      </Typography>
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
              waiting={waiting}
              reason={
                refused !== null
                  ? { severity: "info", words: say(refused) }
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
    </Box>
  );
}
