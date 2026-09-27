import Box from "@mui/material/Box";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { useEffect, useId, useRef, useState } from "react";

import type { ListTerm } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}";
import type { Worded } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}/terms";
import type { TermWay } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}/terms/{termId}/{way}";
import type { GroupRule } from "../../app/standing/actRules";
import { say, type MessageId } from "../../i18n/app";
import { ActButton } from "../../lib/action/ActButton";
import { Press } from "../../lib/action/Press";
import { isolatedInText } from "../../lib/direction/isolated";
import { ACTS_SX, QUIET_SX } from "../../lib/layout/parts";
import { Notice } from "../../lib/notice/Notice";
import { StatusLine, type Line } from "../../lib/notice/StatusLine";
import type { Action } from "../../lib/request/useAction";
import {
  meaningRefused,
  termRefused,
  type LineRefusal,
  type TermRefusal,
} from "../../lib/text/legibility";
import REFERENCE_LIST_LIMITS from "./referenceListLimits.json";
import { usePartFocus } from "./usePartFocus";
import { WriteRefused } from "./WriteRefused";

const PART_SX = { mb: 3 };

const HEADING_SX = { mb: 1 };

const TABLE_SX = { mb: 2, "& td": { verticalAlign: "top" } };

const ADDING_SX = {
  display: "flex",
  flexWrap: "wrap",
  gap: 2,
  alignItems: "flex-start",
  mb: 1,
};

/** Each reason the server refuses a term, said as the refusal it would answer with. */
const TERM_REFUSALS = {
  direction_control: "refusal.PROSE_DIRECTION_CONTROL",
  invisible: "refusal.PROSE_INVISIBLE_CHARACTER",
  tag: "refusal.PROSE_TAG_CHARACTER",
  unusable: "refusal.TERM_UNUSABLE",
} as const satisfies Record<TermRefusal, MessageId>;

const MEANING_REFUSALS = {
  direction_control: "refusal.PROSE_DIRECTION_CONTROL",
  tag: "refusal.PROSE_TAG_CHARACTER",
  unusable: "refusal.TERM_MEANING_UNUSABLE",
} as const satisfies Record<LineRefusal, MessageId>;

const NOTHING_TYPED: Worded = { term: "", meaning: "" };

// Level with the server's Term.MOST_IN_A_LIST, which a spec holds this table to.
const MOST_TERMS = REFERENCE_LIST_LIMITS.mostTerms;

interface TermChanges<T> {
  readonly add: (worded: Worded, signal: AbortSignal) => Promise<T>;
  readonly reword: (
    termId: string,
    worded: Worded,
    signal: AbortSignal,
  ) => Promise<T>;
  readonly remove: (termId: string, signal: AbortSignal) => Promise<T>;
  readonly move: (
    termId: string,
    way: TermWay,
    signal: AbortSignal,
  ) => Promise<T>;
}

interface Typing {
  readonly worded: Worded;
  readonly over: ListTerm;
}

/** None for a move: React puts the keyboard back on a moved row's control itself (restoreSelection). */
type Focus = { readonly termId: string } | { readonly at: "heading" };

/** In the order the version gives them and never another; what is typed over a term stays until it reads otherwise. */
export function TermsTable<T>({
  terms,
  editable,
  waiting,
  rule,
  action,
  changes,
  focusOnArrival,
  onReadAfresh,
}: {
  /** As the server last read them. */
  readonly terms: readonly ListTerm[];
  /** Whether the host may write them now, as the server said. */
  readonly editable: boolean;
  /** Whether another write of the host's is out, which any of these would be refused beside. */
  readonly waiting: boolean;
  /** What writing asks, which a refusal of it is said as. */
  readonly rule: GroupRule;
  readonly action: Action<T>;
  readonly changes: TermChanges<T>;
  /** Where the table was drawn afresh by a press inside it, which is gone: the keyboard starts again here. */
  readonly focusOnArrival: boolean;
  readonly onReadAfresh: () => void;
}) {
  const headingId = useId();
  const { section, heading } = usePartFocus(focusOnArrival, editable);
  const rows = useRef(new Map<string, HTMLElement>());
  const pending = useRef<Focus | null>(null);
  const [asked, setAsked] = useState(false);
  const [landed, setLanded] = useState<Line | null>(null);
  const [typing, setTyping] = useState<ReadonlyMap<string, Typing>>(new Map());
  const [adding, setAdding] = useState(NOTHING_TYPED);
  const [readAs, setReadAs] = useState(terms);
  if (terms !== readAs) {
    setReadAs(terms);
    setTyping((now) => stillOver(now, terms));
  }
  // Keyed on what a landing says, not on the terms: an answer older than those shown redraws nothing, and a
  // focus left waiting on it would take the keyboard at some later, unrelated read.
  useEffect(() => {
    const focus = pending.current;
    pending.current = null;
    if (focus === null) {
      return;
    }
    if ("at" in focus) {
      heading.current?.focus();
      return;
    }
    rows.current
      .get(focus.termId)
      ?.querySelector<HTMLElement>('[data-part="term"]')
      ?.focus();
  }, [landed, heading]);

  const running = action.running;
  const shown = terms.map(
    (term) => typing.get(term.termId)?.worded ?? wordedOf(term),
  );
  // Where the keyboard goes, and what is said, is set only once the write has landed: a refusal redraws nothing.
  const press = (
    act: (signal: AbortSignal) => Promise<T>,
    focus: Focus | null,
    said: string,
  ) =>
    action.run((signal) => {
      setAsked(true);
      setLanded(null);
      return act(signal).then((answer) => {
        pending.current = focus;
        setLanded({ severity: "info", words: said });
        return answer;
      });
    });
  const type = (term: ListTerm, worded: Worded) => {
    if (!running) {
      setTyping((now) =>
        new Map(now).set(term.termId, {
          worded,
          over: now.get(term.termId)?.over ?? term,
        }),
      );
    }
  };
  const typedRefused = refusalOf(adding);
  const addRefused =
    terms.length >= MOST_TERMS
      ? say("referenceList.full", { most: MOST_TERMS })
      : adding.term === "" || adding.meaning === ""
        ? say("referenceList.addFirst")
        : typedRefused === null
          ? null
          : say(typedRefused);

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
        {say("referenceList.terms")}
      </Typography>
      {terms.length === 0 ? (
        <Typography variant="body2" sx={QUIET_SX}>
          {say("referenceList.noTerms")}
        </Typography>
      ) : (
        <Table size="small" aria-labelledby={headingId} sx={TABLE_SX}>
          <TableHead>
            <TableRow>
              <TableCell>{say("referenceList.term")}</TableCell>
              <TableCell>{say("referenceList.meaning")}</TableCell>
              {editable ? (
                <TableCell>{say("referenceList.changes")}</TableCell>
              ) : null}
            </TableRow>
          </TableHead>
          <TableBody>
            {terms.map((term, index) =>
              editable ? (
                <EditedRow
                  key={term.termId}
                  term={term}
                  worded={shown[index]!}
                  position={index + 1}
                  last={index === terms.length - 1}
                  running={running}
                  waiting={waiting}
                  register={(row) => {
                    if (row === null) {
                      rows.current.delete(term.termId);
                    } else {
                      rows.current.set(term.termId, row);
                    }
                  }}
                  onType={(worded) => type(term, worded)}
                  onSave={(worded) =>
                    press(
                      (signal) => changes.reword(term.termId, worded, signal),
                      { termId: term.termId },
                      say("referenceList.saved", {
                        term: isolatedInText(worded.term),
                      }),
                    )
                  }
                  // A write names the revision read, so one landing moved exactly one place.
                  onMove={(way) =>
                    press(
                      (signal) => changes.move(term.termId, way, signal),
                      null,
                      say("referenceList.moved", {
                        term: isolatedInText(term.term),
                        position: way === "up" ? index : index + 2,
                      }),
                    )
                  }
                  onRemove={() =>
                    press(
                      (signal) => changes.remove(term.termId, signal),
                      { at: "heading" },
                      say("referenceList.removed", {
                        term: isolatedInText(term.term),
                      }),
                    )
                  }
                />
              ) : (
                <TableRow key={term.termId}>
                  <TableCell>
                    <bdi>{term.term}</bdi>
                  </TableCell>
                  <TableCell>
                    <bdi>{term.meaning}</bdi>
                  </TableCell>
                </TableRow>
              ),
            )}
          </TableBody>
        </Table>
      )}
      {editable ? (
        <>
          <Box sx={ADDING_SX}>
            <TextField
              label={say("referenceList.newTerm")}
              value={adding.term}
              onChange={(edit) => {
                if (!running) {
                  setAdding({ ...adding, term: edit.target.value });
                }
              }}
              {...termSaid(adding.term, false)}
              slotProps={{ htmlInput: { dir: "auto", readOnly: running } }}
              size="small"
            />
            <TextField
              label={say("referenceList.newMeaning")}
              value={adding.meaning}
              onChange={(edit) => {
                if (!running) {
                  setAdding({ ...adding, meaning: edit.target.value });
                }
              }}
              {...meaningSaid(adding.meaning)}
              slotProps={{ htmlInput: { dir: "auto", readOnly: running } }}
              size="small"
              sx={{ flexGrow: 1 }}
            />
          </Box>
          <Box sx={ACTS_SX}>
            <ActButton
              action={action}
              waiting={waiting}
              reason={
                addRefused === null
                  ? undefined
                  : { severity: "info", words: addRefused }
              }
              act={(signal) => {
                setAsked(true);
                setLanded(null);
                return changes.add(adding, signal).then((answer) => {
                  setAdding(NOTHING_TYPED);
                  setLanded({
                    severity: "info",
                    words: say("referenceList.added", {
                      term: isolatedInText(adding.term),
                    }),
                  });
                  return answer;
                });
              }}
            >
              {say("referenceList.add")}
            </ActButton>
          </Box>
          {running ? (
            <Notice severity="info">{say("referenceList.underway")}</Notice>
          ) : null}
          <StatusLine said={landed} />
        </>
      ) : null}
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

/** One term as it is written: its two text boxes, and each change to it, named with the term as it was read. */
function EditedRow({
  term,
  worded,
  position,
  last,
  running,
  waiting,
  register,
  onType,
  onSave,
  onMove,
  onRemove,
}: {
  readonly term: ListTerm;
  readonly worded: Worded;
  /** Counted from one. */
  readonly position: number;
  readonly last: boolean;
  readonly running: boolean;
  readonly waiting: boolean;
  readonly register: (row: HTMLElement | null) => void;
  readonly onType: (worded: Worded) => void;
  readonly onSave: (worded: Worded) => void;
  readonly onMove: (way: TermWay) => void;
  readonly onRemove: () => void;
}) {
  const spoken = isolatedInText(term.term);
  const named = (act: MessageId) =>
    say("referenceList.onTerm", { act: say(act), term: spoken });
  const held = running || waiting;
  const changed = worded.term !== term.term || worded.meaning !== term.meaning;
  const refused = refusalOf(worded);
  return (
    <TableRow ref={register}>
      <TableCell>
        <TextField
          value={worded.term}
          onChange={(edit) => onType({ ...worded, term: edit.target.value })}
          {...termSaid(worded.term, term.alikeEarlier === true)}
          slotProps={{
            htmlInput: {
              "aria-label": say("referenceList.termAt", { position }),
              "data-part": "term",
              dir: "auto",
              readOnly: running,
            },
          }}
          size="small"
          fullWidth
        />
      </TableCell>
      <TableCell>
        <TextField
          value={worded.meaning}
          onChange={(edit) => onType({ ...worded, meaning: edit.target.value })}
          {...meaningSaid(worded.meaning)}
          slotProps={{
            htmlInput: {
              "aria-label": say("referenceList.meaningAt", { position }),
              dir: "auto",
              readOnly: running,
            },
          }}
          size="small"
          fullWidth
        />
      </TableCell>
      <TableCell>
        <Box sx={ACTS_SX}>
          {changed ? (
            <Press
              label={named("referenceList.save")}
              unavailable={held}
              reason={
                refused === null
                  ? undefined
                  : { severity: "info", words: say(refused) }
              }
              onPress={() => onSave(worded)}
            >
              {say("referenceList.save")}
            </Press>
          ) : null}
          <Press
            label={named("referenceList.up")}
            unavailable={held || position === 1}
            onPress={() => onMove("up")}
          >
            {say("referenceList.up")}
          </Press>
          <Press
            label={named("referenceList.down")}
            unavailable={held || last}
            onPress={() => onMove("down")}
          >
            {say("referenceList.down")}
          </Press>
          <Press
            label={named("referenceList.remove")}
            unavailable={held}
            onPress={onRemove}
          >
            {say("referenceList.remove")}
          </Press>
        </Box>
      </TableCell>
    </TableRow>
  );
}

/** Alike as the server judged the term saved, which follows each save rather than each keystroke. */
function termSaid(typed: string, alikeEarlier: boolean) {
  const refused = typed === "" ? null : termRefused(typed);
  if (refused !== null) {
    return { error: true, helperText: say(TERM_REFUSALS[refused]) };
  }
  return alikeEarlier
    ? { error: true, helperText: say("referenceList.alike") }
    : {};
}

function meaningSaid(typed: string) {
  const refused = typed === "" ? null : meaningRefused(typed);
  return refused === null
    ? {}
    : { error: true, helperText: say(MEANING_REFUSALS[refused]) };
}

function refusalOf(worded: Worded): MessageId | null {
  const term = termRefused(worded.term);
  if (term !== null) {
    return TERM_REFUSALS[term];
  }
  const meaning = meaningRefused(worded.meaning);
  return meaning === null ? null : MEANING_REFUSALS[meaning];
}

function stillOver(
  typing: ReadonlyMap<string, Typing>,
  terms: readonly ListTerm[],
): ReadonlyMap<string, Typing> {
  const read = new Map(terms.map((term) => [term.termId, term]));
  return new Map(
    [...typing].filter(([termId, { over }]) => {
      const now = read.get(termId);
      return (
        now !== undefined &&
        now.term === over.term &&
        now.meaning === over.meaning
      );
    }),
  );
}

function wordedOf(term: ListTerm): Worded {
  return { term: term.term, meaning: term.meaning };
}
