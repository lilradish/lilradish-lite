import TextField from "@mui/material/TextField";
import { useState } from "react";

import { useTypingPause } from "../search/useTypingPause";

const FIELD_SX = { mb: 2 };

/**
 * Shows typing immediately but sends only after a pause, since each sent change
 * restarts from an empty page; sending every keystroke would blank the rows.
 *
 * The caller writes the value into the address by a functional update, which
 * keeps what else the address holds, and with `replace`: a pause is no step back.
 */
export function FilterField({
  label,
  value,
  onChange,
}: {
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
