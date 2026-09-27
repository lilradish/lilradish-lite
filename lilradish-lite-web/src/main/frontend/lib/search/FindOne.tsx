import Box from "@mui/material/Box";
import FormControl from "@mui/material/FormControl";
import FormControlLabel from "@mui/material/FormControlLabel";
import FormLabel from "@mui/material/FormLabel";
import Radio from "@mui/material/Radio";
import RadioGroup from "@mui/material/RadioGroup";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { useCallback, useId, useState, type ReactNode } from "react";

import type { Problem } from "../../api/problem";
import { say } from "../../i18n/lib";
import { isolated } from "../direction/isolated";
import { Notice } from "../notice/Notice";
import { useResource } from "../request/useResource";
import { useTypingPause } from "./useTypingPause";

/** What one search sent back: the first of the matches, and whether there are others. */
export interface Found<T> {
  readonly items: readonly T[];
  readonly more: boolean;
}

const NOTHING: Found<never> = { items: [], more: false };

// The server's own test for nothing typed. Not `trim`, which takes away more
// than space separators: the two sides would judge one text two ways.
const NOTHING_TYPED = /^\p{Zs}*$/u;

const STATUS_REGION_SX = { my: 1 };

const STATUS_SX = { color: "text.secondary" };

const WITHHELD_SX = { listStyleType: "none", m: 0, p: 0 };

/**
 * A search over a set the page is not about, and one of what it finds picked.
 * Nothing is offered until something is typed, and what is typed is sent as it
 * stands: which part of it matches what is the server's to say.
 *
 * The matches are radios rather than a combobox. The pick has to stay in view
 * while the reader goes on to other fields, and a combobox's value is the text
 * in its box, which here is the search. A match the caller will not offer is no
 * option at all, so is said in words beside the choice rather than disabled in it.
 *
 * There is no paging: the server sends the first of the matches and says
 * whether there are more, and more is answered by typing more.
 *
 * A pick outlives a refusal of whatever it was picked for, and goes as soon as
 * a different search is sent. The same search sent again after it was refused
 * is asked again.
 */
export function FindOne<T>({
  label,
  search,
  keyOf,
  title,
  whyNot,
  picked,
  onPick,
  empty,
  refusal,
  autoFocus,
}: {
  readonly label: string;
  /**
   * Must be stable — a module constant, or the caller's own `useCallback` —
   * for the reason `useResource` gives.
   */
  readonly search: (typed: string, signal: AbortSignal) => Promise<Found<T>>;
  readonly keyOf: (row: T) => string;
  /** Drawn inside a label: phrasing content, nothing interactive. */
  readonly title: (row: T) => ReactNode;
  /**
   * Why a match is not offered, or null where it is. Never empty: that is a
   * match drawn, dead and silent. Shown as given, so a name in it arrives
   * isolated by `isolatedInText`.
   */
  readonly whyNot?: (row: T) => string | null;
  readonly picked: T | null;
  readonly onPick: (row: T | null) => void;
  /** Said where a settled, unrefused search found nothing. */
  readonly empty: string;
  /** A refused search, said in the caller's words; there are none for it here. */
  readonly refusal: (problem: Problem) => ReactNode;
  /** Where the box is the first field of what has just opened. */
  readonly autoFocus?: boolean;
}) {
  const matchesId = useId();
  const [searched, setSearched] = useState<string | null>(null);

  const load = useCallback(
    (signal: AbortSignal): Promise<Found<T>> =>
      searched === null ? Promise.resolve(NOTHING) : search(searched, signal),
    [searched, search],
  );
  const found = useResource<Found<T>>(load, NOTHING);

  function handOn(typed: string) {
    const next = NOTHING_TYPED.test(typed) ? null : typed;
    if (next === searched) {
      if (found.problem !== null) {
        found.reload();
      }
      return;
    }
    // Cleared here rather than while rendering, where it would be this
    // component updating its caller's state.
    if (picked !== null) {
      onPick(null);
    }
    setSearched(next);
  }

  const offered: T[] = [];
  const withheld: { row: T; reason: string }[] = [];
  for (const row of found.value.items) {
    const reason = whyNot?.(row) ?? null;
    if (reason === null) {
      offered.push(row);
    } else {
      withheld.push({ row, reason });
    }
  }

  const count = found.value.items.length;
  // A count is a reading; nothing found, or more than shown, asks the reader
  // to type something else, which is a hint.
  const status =
    searched === null || found.problem !== null ? null : found.loading ? (
      <Typography variant="body2" sx={STATUS_SX}>
        {say("read.pending")}
      </Typography>
    ) : count === 0 || found.value.more ? (
      <Notice severity="info">
        {count === 0 ? empty : say("search.more", { count })}
      </Notice>
    ) : (
      <Typography variant="body2" sx={STATUS_SX}>
        {say("search.found", { count })}
      </Typography>
    );

  return (
    <div>
      <SearchBox label={label} autoFocus={autoFocus} onPause={handOn} />
      <Box role="status" sx={STATUS_REGION_SX}>
        {status}
      </Box>
      {found.problem === null ? null : refusal(found.problem)}
      {offered.length === 0 ? null : (
        <FormControl>
          <FormLabel id={matchesId}>{say("search.matches")}</FormLabel>
          <RadioGroup
            aria-labelledby={matchesId}
            value={picked === null ? null : keyOf(picked)}
            onChange={(_event, key) =>
              onPick(offered.find((row) => keyOf(row) === key) ?? null)
            }
          >
            {offered.map((row) => (
              <FormControlLabel
                key={keyOf(row)}
                value={keyOf(row)}
                control={<Radio />}
                label={isolated(title(row))}
              />
            ))}
          </RadioGroup>
        </FormControl>
      )}
      {withheld.length === 0 ? null : (
        // Named a list outright, its markers being taken away.
        <Box component="ul" role="list" sx={WITHHELD_SX}>
          {withheld.map(({ row, reason }) => (
            <li key={keyOf(row)}>
              {isolated(title(row))}
              <Notice severity="info">{reason}</Notice>
            </li>
          ))}
        </Box>
      )}
    </div>
  );
}

/**
 * The box alone, so a keystroke renders the box and not every match below it;
 * the search above hears of the text only when a pause hands it over.
 */
function SearchBox({
  label,
  autoFocus,
  onPause,
}: {
  readonly label: string;
  readonly autoFocus: boolean | undefined;
  readonly onPause: (typed: string) => void;
}) {
  const typing = useTypingPause("", onPause);
  return (
    <TextField
      type="search"
      label={label}
      fullWidth
      autoFocus={autoFocus}
      {...typing.field}
    />
  );
}
