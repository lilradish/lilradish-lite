import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import { useState, type ReactNode } from "react";

import { Press, type Reason } from "../action/Press";

export interface NoteAct {
  readonly label: string;
  readonly act: (note: string) => void;
  /** Why it cannot be pressed, beyond the form being busy; absent where it can. */
  readonly reason?: Reason;
}

/**
 * A note, and the acts it accompanies.
 *
 * Every decision in this system is recorded with who made it and why, so every
 * screen that decides something needs the same row: a field, a busy flag, and
 * one or more buttons.
 *
 * The note stays put when an act is refused — it is the reviewer's reasoning,
 * and losing it to a failed submit means writing it twice. Clearing it is the
 * caller's: a form that stays mounted carries the note to the next decision, so
 * a caller that reuses it for a second subject must re-key this component.
 *
 * No `form` element: with more than one act there is nothing single for Enter
 * to submit, so the acts are plain buttons and a required note is checked here
 * rather than by the browser. A lone act takes the same path rather than
 * growing a second shape, which is why Enter submits nothing either way.
 */
export function NoteForm({
  note,
  acts,
  busy,
  leading,
}: {
  /**
   * What the note is called and what it is for, or null where the act takes no
   * note at all.
   */
  readonly note: {
    readonly label: string;
    readonly hint: string;
    readonly required?: boolean;
  } | null;
  readonly acts: readonly NoteAct[];
  readonly busy: boolean;
  /** Controls the act needs beside the note: which variant, which fields. */
  readonly leading?: ReactNode;
}) {
  const [written, setWritten] = useState("");
  const missing = note?.required === true && written.trim() === "";

  return (
    <Stack
      direction="row"
      spacing={1.5}
      // Without this the spacing is a left margin on every child but the first
      // in DOM order — so a wrapped row keeps that margin, its left edge lands
      // off the one above, and nothing separates the two rows vertically.
      useFlexGap
      sx={{ my: 2, alignItems: "flex-start", flexWrap: "wrap" }}
    >
      {leading}
      {note === null ? null : (
        <TextField
          // Named for the act: one screen can carry a decision note, a rerun
          // note and an override note at once, and "Note" three times names
          // none of them.
          label={note.label}
          value={written}
          onChange={(edit) => setWritten(edit.target.value)}
          placeholder={note.hint}
          required={note.required}
          // Ergonomics, not the rule — the cap that decides is the server's.
          slotProps={{ htmlInput: { maxLength: 2000 } }}
          // Takes the room that is there, down to a width a phone still has.
          sx={{ flex: 1, minWidth: 240 }}
        />
      )}
      {acts.map((choice) => (
        <Press
          key={choice.label}
          // A missing note says nothing here: the field is marked required.
          unavailable={busy || missing}
          reason={choice.reason}
          // Not cleared here: the act may be refused, and the note is the
          // reviewer's reasoning. A decision that lands unmounts this form.
          onPress={() => choice.act(note === null ? "" : written)}
        >
          {choice.label}
        </Press>
      ))}
    </Stack>
  );
}
