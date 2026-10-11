import ArrowDownwardIcon from "@mui/icons-material/ArrowDownward";
import ArrowUpwardIcon from "@mui/icons-material/ArrowUpward";
import DeleteOutlinedIcon from "@mui/icons-material/DeleteOutlined";
import Box from "@mui/material/Box";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableContainer from "@mui/material/TableContainer";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import Typography from "@mui/material/Typography";
import { memo, useCallback, useEffect, useId, useRef, useState } from "react";

import type { ListTerm } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}";
import type { Worded } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}/terms";
import type { TermWay } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}/terms/{termId}/{way}";
import type { GroupRule } from "../../app/standing/actRules";
import { say, type MessageId } from "../../i18n/app";
import { ActButton } from "../../lib/action/ActButton";
import { IconPress } from "../../lib/action/IconPress";
import { Press, type Reason } from "../../lib/action/Press";
import { isolatedInText } from "../../lib/direction/isolated";
import { LabelledTextField } from "../../lib/form/LabelledTextField";
import { Part } from "../../lib/layout/Part";
import { ACTS_SX, HEADER_ACTS_SX, QUIET_SX } from "../../lib/layout/parts";
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
import { useDraftPartStatus } from "./useDraftStatus";
import { WriteRefused } from "./WriteRefused";

const TABLE_SX = { "& td": { verticalAlign: "top" } };

const TABLE_HOLDER_SX = { mb: 2 };

const ROW_ACTS_SX = { ...HEADER_ACTS_SX, alignItems: "flex-start" };

const ADDING_SX = {
  display: "flex",
  flexWrap: "wrap",
  gap: 2,
  alignItems: "flex-start",
  mb: 1,
};

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
  readonly terms: readonly ListTerm[];
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
  const run = action.run;
  // Where the keyboard goes, and what is said, is set only once the write has landed: a refusal redraws nothing.
  const press = useCallback(
    (
      act: (signal: AbortSignal) => Promise<T>,
      focus: Focus | null,
      said: string,
    ) =>
      run((signal) => {
        setAsked(true);
        setLanded(null);
        return act(signal).then((answer) => {
          pending.current = focus;
          setLanded({ severity: "info", words: said });
          return answer;
        });
      }),
    [run],
  );
  // Each row is handed these rather than its own, so a keystroke in one row redraws that row alone.
  const register = useCallback((termId: string, row: HTMLElement | null) => {
    if (row === null) {
      rows.current.delete(termId);
    } else {
      rows.current.set(termId, row);
    }
  }, []);
  const type = useCallback(
    (term: ListTerm, worded: Worded) => {
      if (!running) {
        setTyping((now) =>
          new Map(now).set(term.termId, {
            worded,
            over: now.get(term.termId)?.over ?? term,
          }),
        );
      }
    },
    [running],
  );
  const save = useCallback(
    (term: ListTerm, worded: Worded) =>
      press(
        (signal) => changes.reword(term.termId, worded, signal),
        { termId: term.termId },
        say("referenceList.saved", { term: isolatedInText(worded.term) }),
      ),
    [press, changes],
  );
  // A write names the revision read, so one landing moved exactly one place.
  const move = useCallback(
    (term: ListTerm, way: TermWay, position: number) =>
      press(
        (signal) => changes.move(term.termId, way, signal),
        null,
        say("referenceList.moved", {
          term: isolatedInText(term.term),
          position: way === "up" ? position - 1 : position + 1,
        }),
      ),
    [press, changes],
  );
  const remove = useCallback(
    (term: ListTerm) =>
      press(
        (signal) => changes.remove(term.termId, signal),
        { at: "heading" },
        say("referenceList.removed", { term: isolatedInText(term.term) }),
      ),
    [press, changes],
  );
  const typedRefused = refusalOf(adding);
  const unsaved =
    terms.some((term) => {
      const worded = typing.get(term.termId)?.worded;
      return (
        worded !== undefined &&
        (worded.term !== term.term || worded.meaning !== term.meaning)
      );
    }) ||
    adding.term !== "" ||
    adding.meaning !== "";
  useDraftPartStatus("terms", unsaved, action.running || waiting, {
    saving: action.running,
    node: section,
  });
  const addHeld: Reason | undefined =
    terms.length >= MOST_TERMS
      ? {
          severity: "warning",
          words: say("referenceList.full", { most: MOST_TERMS }),
        }
      : adding.term === "" || adding.meaning === ""
        ? { severity: "info", words: say("referenceList.addFirst") }
        : typedRefused === null
          ? undefined
          : { severity: "warning", words: say(typedRefused) };

  return (
    <Part
      title={say("referenceList.terms")}
      unsaved={editable && unsaved}
      level="h3"
      depth={2}
      headingId={headingId}
      headingRef={heading}
      ref={section}
    >
      {terms.length === 0 ? (
        <Typography variant="body2" sx={QUIET_SX}>
          {say("referenceList.noTerms")}
        </Typography>
      ) : (
        <TableContainer sx={TABLE_HOLDER_SX}>
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
                    typed={typing.get(term.termId)?.worded}
                    position={index + 1}
                    last={index === terms.length - 1}
                    running={running}
                    waiting={waiting}
                    onRegister={register}
                    onType={type}
                    onSave={save}
                    onMove={move}
                    onRemove={remove}
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
        </TableContainer>
      )}
      {editable ? (
        <>
          <Box sx={ADDING_SX}>
            <LabelledTextField
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
            <LabelledTextField
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
              tier={
                adding.term !== "" || adding.meaning !== ""
                  ? "primary"
                  : "secondary"
              }
              waiting={waiting}
              reason={addHeld}
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
    </Part>
  );
}

/** One term as it is written: its two text boxes, and each change to it, named with the term as it was read. */
const EditedRow = memo(function EditedRow({
  term,
  typed,
  position,
  last,
  running,
  waiting,
  onRegister,
  onType,
  onSave,
  onMove,
  onRemove,
}: {
  readonly term: ListTerm;
  /** What is typed over it, where anything is. */
  readonly typed: Worded | undefined;
  /** Counted from one. */
  readonly position: number;
  readonly last: boolean;
  readonly running: boolean;
  readonly waiting: boolean;
  readonly onRegister: (termId: string, row: HTMLElement | null) => void;
  readonly onType: (term: ListTerm, worded: Worded) => void;
  readonly onSave: (term: ListTerm, worded: Worded) => void;
  readonly onMove: (term: ListTerm, way: TermWay, position: number) => void;
  readonly onRemove: (term: ListTerm) => void;
}) {
  const worded = typed ?? wordedOf(term);
  const spoken = isolatedInText(term.term);
  const named = (act: MessageId) =>
    say("referenceList.onTerm", { act: say(act), term: spoken });
  const held = running || waiting;
  const changed = worded.term !== term.term || worded.meaning !== term.meaning;
  const refused = refusalOf(worded);
  return (
    <TableRow ref={(row) => onRegister(term.termId, row)}>
      <TableCell>
        <LabelledTextField
          value={worded.term}
          onChange={(edit) =>
            onType(term, { ...worded, term: edit.target.value })
          }
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
        <LabelledTextField
          value={worded.meaning}
          onChange={(edit) =>
            onType(term, { ...worded, meaning: edit.target.value })
          }
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
        <Box sx={ROW_ACTS_SX}>
          {changed ? (
            <Press
              label={named("referenceList.save")}
              tier="primary"
              unavailable={held}
              reason={
                refused === null
                  ? undefined
                  : { severity: "warning", words: say(refused) }
              }
              onPress={() => onSave(term, worded)}
            >
              {say("referenceList.save")}
            </Press>
          ) : null}
          <IconPress
            icon={<ArrowUpwardIcon />}
            label={named("referenceList.up")}
            unavailable={held || position === 1}
            onPress={() => onMove(term, "up", position)}
          >
            {say("referenceList.up")}
          </IconPress>
          <IconPress
            icon={<ArrowDownwardIcon />}
            label={named("referenceList.down")}
            unavailable={held || last}
            onPress={() => onMove(term, "down", position)}
          >
            {say("referenceList.down")}
          </IconPress>
          <IconPress
            icon={<DeleteOutlinedIcon />}
            label={named("referenceList.remove")}
            unavailable={held}
            onPress={() => onRemove(term)}
          >
            {say("referenceList.remove")}
          </IconPress>
        </Box>
      </TableCell>
    </TableRow>
  );
});

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
