import TextField from "@mui/material/TextField";
import { useState } from "react";

import { useTypingPause } from "../search/useTypingPause";

const FIELD_SX = { mb: 2 };

/**
 * One box whose words the server matches. What is typed shows at once and is
 * handed on only once typing pauses: every change starts the read again from
 * an empty page, so each keystroke sent would blank the rows it was filtering.
 *
 * The caller writes the value into the address by a functional update, which
 * keeps what else the address holds, and with `replace`: a pause is no step back.
 */
export function FilterField({
  label,
  value,
  onChange,
}: {
  /** What can be typed to narrow the rows, which only the caller knows. */
  readonly label: string;
  readonly value: string;
  readonly onChange: (value: string) => void;
}) {
  const [known, setKnown] = useState(value);
  // What this box handed on and has not yet seen come back. Arriving as the
  // value it is this box's own, not news, and the reader may have typed on.
  const [sent, setSent] = useState<string | null>(null);
  const typing = useTypingPause(value, (typed) => {
    setSent(typed);
    onChange(typed);
  });
  if (value !== known) {
    setKnown(value);
    setSent(null);
    if (value !== sent) {
      typing.setText(value);
    }
  }

  return (
    <TextField
      type="search"
      label={label}
      fullWidth
      sx={FIELD_SX}
      {...typing.field}
    />
  );
}
