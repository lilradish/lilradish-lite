import Box from "@mui/material/Box";
import Checkbox from "@mui/material/Checkbox";
import FormControlLabel from "@mui/material/FormControlLabel";
import MenuItem from "@mui/material/MenuItem";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { useEffect, useId, useMemo, useRef, useState } from "react";

import {
  type DeclaredField,
  type FieldKind,
  type FieldStanding,
  type ListVersion,
  type PinnedList,
  type SentField,
} from "../../api/declaration";
import { ProblemView } from "../../app/ProblemView";
import type { GroupRule } from "../../app/standing/actRules";
import { say, type MessageId } from "../../i18n/app";
import { ActButton } from "../../lib/action/ActButton";
import { Press } from "../../lib/action/Press";
import { isolatedInText } from "../../lib/direction/isolated";
import { Notice } from "../../lib/notice/Notice";
import type { Action } from "../../lib/request/useAction";
import { fieldNameFits, labelFits, lineFits } from "../../lib/text/legibility";
import {
  addedUnder,
  changedAt,
  demandAt,
  draftOf,
  floorFits,
  freshDraft,
  heldFor,
  limitFits,
  movedAt,
  removedAt,
  sentOf,
  type Demands,
  type DraftField,
  type Place,
} from "./declarationDrafts";
import {
  FIELD_KIND_WORDS,
  FIELD_STANDING_WORDS,
  partsSaid,
} from "./declarationWords";
import { WriteRefused } from "./WriteRefused";

const PART_SX = { mb: 3 };

const HEADING_SX = { mb: 1 };

// Its markers taken away, so it is named a list outright.
const LIST_SX = { listStyleType: "none", m: 0, p: 0 };

// A field another holds sits under it, set in by one step for every level.
const HELD_SX = { listStyleType: "none", m: 0, pl: 3 };

const ROW_SX = {
  border: 1,
  borderColor: "divider",
  borderRadius: 1,
  p: 2,
  mb: 1,
  "& legend": { typography: "subtitle2", px: 0.5 },
};

const CONTROLS_SX = {
  display: "flex",
  flexWrap: "wrap",
  gap: 2,
  alignItems: "center",
  mb: 1,
};

const QUIET_SX = { color: "text.secondary" };

/** Where focus goes once the rows a press changed are drawn: a row's part, or the half's heading. */
type Focus =
  | { readonly key: string; readonly at: "name" | "legend" | "up" | "down" }
  | { readonly at: "heading" };

/** A half the builder sends whole itself, under a control of its own. */
interface Saving<T> {
  readonly saveLabel: string;
  readonly underway: string;
  /** What writing asks, which a refusal of it is said as. */
  readonly rule: GroupRule;
  readonly action: Action<T>;
  readonly save: (
    fields: readonly SentField[],
    signal: AbortSignal,
  ) => Promise<T>;
  /** Whether another write of the host's is out, which saving this half would be refused beside. */
  readonly waiting?: boolean;
  /** Where the half was drawn afresh by a press inside it, which is gone: the keyboard starts again here. */
  readonly focusOnArrival?: boolean;
  /** Offered beside a refusal for somebody saving since; a host that offers it for every part leaves it out. */
  readonly onReadAfresh?: () => void;
  readonly controlled?: undefined;
}

/** A half the host holds as typed and sends with what else it writes, so no control of the builder's sends it. */
interface Controlled {
  readonly controlled: {
    readonly drafts: readonly DraftField[];
    readonly change: (drafts: DraftField[]) => void;
  };
}

/**
 * One half of a declaration, a row per field, edited where the host may write it; what each depth is asked is
 * the host's `demands`. Sent whole by its own control, or held by the host and sent with what else it writes.
 */
export function DeclarationBuilder<T>(
  props: {
    readonly title: string;
    readonly heading: "h3" | "h4" | "h5";
    readonly demands: Demands;
    /** As the server last read them. */
    readonly fields: readonly DeclaredField[];
    /** Every list a field of terms may pin now. */
    readonly lists: readonly ListVersion[];
    /** Whether the host may write this half now, as the server said. */
    readonly editable: boolean;
  } & (Saving<T> | Controlled),
) {
  const { title, heading, demands, fields, lists, editable } = props;
  const focusOnArrival =
    props.controlled === undefined && props.focusOnArrival === true;
  const headingId = useId();
  const section = useRef<HTMLElement>(null);
  const headingAt = useRef<HTMLHeadingElement>(null);
  const arriving = useRef(focusOnArrival);
  const [asked, setAsked] = useState(false);
  const pins = useMemo(() => pinsIn(fields), [fields]);
  const wasEditable = useRef(editable);
  useEffect(() => {
    if (arriving.current) {
      headingAt.current?.focus();
    }
  }, []);
  useEffect(() => {
    const within = section.current?.contains(document.activeElement) ?? false;
    if (
      wasEditable.current &&
      !editable &&
      (within || document.activeElement === document.body)
    ) {
      headingAt.current?.focus();
    }
    wasEditable.current = editable;
  }, [editable]);
  return (
    <Box
      component="section"
      ref={section}
      aria-labelledby={headingId}
      sx={PART_SX}
    >
      <Typography
        id={headingId}
        ref={headingAt}
        tabIndex={-1}
        variant="h6"
        component={heading}
        sx={HEADING_SX}
      >
        {title}
      </Typography>
      {editable ? (
        <Editing
          demands={demands}
          fields={fields}
          lists={lists}
          pins={pins}
          labelledBy={headingId}
          focusHeading={() => headingAt.current?.focus()}
          host={
            props.controlled === undefined
              ? {
                  ...props,
                  save: (sent, signal) => {
                    setAsked(true);
                    return props.save(sent, signal);
                  },
                }
              : { controlled: props.controlled }
          }
        />
      ) : (
        <Reading
          fields={fields}
          demands={demands}
          first
          labelledBy={headingId}
        />
      )}
      {props.controlled !== undefined ||
      !asked ||
      props.action.problem === null ? null : props.onReadAfresh ===
        undefined ? (
        <ProblemView
          problem={props.action.problem}
          rule={{ inGroup: props.rule }}
        />
      ) : (
        <WriteRefused
          problem={props.action.problem}
          rule={props.rule}
          waiting={props.waiting === true}
          onReadAfresh={props.onReadAfresh}
        />
      )}
    </Box>
  );
}

function Editing<T>({
  demands,
  fields,
  lists,
  pins,
  labelledBy,
  focusHeading,
  host,
}: {
  readonly demands: Demands;
  readonly fields: readonly DeclaredField[];
  readonly lists: readonly ListVersion[];
  readonly pins: ReadonlyMap<string, PinnedList>;
  readonly labelledBy: string;
  readonly focusHeading: () => void;
  readonly host: Saving<T> | Controlled;
}) {
  const saved = useMemo(() => fields.map(draftOf), [fields]);
  // What is written here is kept until the half the server holds changes; the
  // other half being written leaves it as typed.
  const savedAs = useMemo(() => JSON.stringify(fields), [fields]);
  const [own, setOwn] = useState(saved);
  const [readAs, setReadAs] = useState(savedAs);
  if (savedAs !== readAs) {
    setReadAs(savedAs);
    setOwn(saved);
  }
  const controlled = host.controlled;
  const drafts = controlled === undefined ? own : controlled.drafts;
  const setDrafts = (
    next: (now: readonly DraftField[]) => DraftField[],
  ): void => {
    if (controlled === undefined) {
      setOwn(next);
    } else {
      controlled.change(next(controlled.drafts));
    }
  };
  const made = useRef(0);
  const rows = useRef(new Map<string, HTMLElement>());
  const pending = useRef<Focus | null>(null);
  useEffect(() => {
    const focus = pending.current;
    pending.current = null;
    if (focus === null) {
      return;
    }
    if (focus.at === "heading") {
      focusHeading();
      return;
    }
    const row = rows.current.get(focus.key);
    const target =
      focus.at === "legend"
        ? row?.querySelector<HTMLElement>(":scope > legend")
        : focus.at === "name"
          ? row?.querySelector<HTMLElement>('[data-part="name"]')
          : row?.querySelector<HTMLElement>(`[data-part="${focus.at}"] button`);
    target?.focus();
  }, [drafts, focusHeading]);
  const running = host.controlled === undefined && host.action.running;
  const held = useMemo(() => heldFor(drafts, demands), [drafts, demands]);
  const sent = useMemo(
    () => drafts.map((draft) => sentOf(draft, demands)),
    [drafts, demands],
  );
  const unchanged = useMemo(
    () =>
      JSON.stringify(sent) ===
      JSON.stringify(saved.map((draft) => sentOf(draft, demands))),
    [sent, saved, demands],
  );
  const named = useMemo(() => namedLists(lists, pins), [lists, pins]);
  const changing = (
    next: (now: readonly DraftField[]) => DraftField[],
    focus: Focus,
  ) => {
    if (running) {
      return;
    }
    pending.current = focus;
    setDrafts(next);
  };
  const edits: Edits = {
    change: (place, change) => {
      if (!running) {
        setDrafts((now) => changedAt(now, place, change));
      }
    },
    remove: (place) =>
      changing((now) => removedAt(now, place), afterRemoving(drafts, place)),
    move: (place, by, key) =>
      changing((now) => movedAt(now, place, by), {
        key,
        at: by < 0 ? "up" : "down",
      }),
    addInside: (place) => {
      made.current += 1;
      const added = freshDraft(`new-${made.current}`);
      changing((now) => addedUnder(now, place, added), {
        key: added.key,
        at: "name",
      });
    },
    register: (key, row) => {
      if (row === null) {
        rows.current.delete(key);
      } else {
        rows.current.set(key, row);
      }
    },
  };

  return (
    <>
      {drafts.length === 0 ? (
        <Typography variant="body2" sx={QUIET_SX}>
          {say("declaration.none")}
        </Typography>
      ) : (
        <Box
          component="ul"
          role="list"
          aria-labelledby={labelledBy}
          sx={LIST_SX}
        >
          {drafts.map((draft, index) => (
            <Row
              key={draft.key}
              draft={draft}
              place={[index]}
              fellows={drafts}
              demands={demands}
              held={held}
              running={running}
              named={named}
              lists={lists}
              pins={pins}
              edits={edits}
            />
          ))}
        </Box>
      )}
      <Box sx={CONTROLS_SX}>
        <Press unavailable={running} onPress={() => edits.addInside(null)}>
          {say("declaration.add")}
        </Press>
        {host.controlled !== undefined ? null : (
          <ActButton
            action={host.action}
            waiting={host.waiting}
            reason={
              held !== null
                ? { severity: "info", words: say(held.why) }
                : unchanged
                  ? { severity: "info", words: say("declaration.unchanged") }
                  : undefined
            }
            act={(signal) => host.save(sent, signal)}
          >
            {host.saveLabel}
          </ActButton>
        )}
      </Box>
      {host.controlled === undefined && running ? (
        <Notice severity="info">{host.underway}</Notice>
      ) : null}
    </>
  );
}

/** What a row may do to the half; each is ignored while a save is out. */
interface Edits {
  readonly change: (
    place: Place,
    change: (draft: DraftField) => DraftField,
  ) => void;
  readonly remove: (place: Place) => void;
  readonly move: (place: Place, by: -1 | 1, key: string) => void;
  readonly addInside: (place: Place | null) => void;
  readonly register: (key: string, row: HTMLElement | null) => void;
}

function Row({
  draft,
  place,
  fellows,
  demands,
  held,
  running,
  named,
  lists,
  pins,
  edits,
}: {
  readonly draft: DraftField;
  readonly place: Place;
  /** Every row on its level, itself among them. */
  readonly fellows: readonly DraftField[];
  readonly demands: Demands;
  readonly held: { readonly why: MessageId; readonly place: Place } | null;
  readonly running: boolean;
  readonly named: ReadonlyMap<string, string>;
  readonly lists: readonly ListVersion[];
  readonly pins: ReadonlyMap<string, PinnedList>;
  readonly edits: Edits;
}) {
  const heldId = useId();
  const set = (changed: Partial<DraftField>) =>
    edits.change(place, (now) => ({ ...now, ...changed }));
  const position = place[place.length - 1]!;
  const called = rowCalled(draft, position);
  const demand = demandAt(demands, place.length === 1);
  const heldHere =
    held !== null && held.place.join(".") === place.join(".") ? held : null;
  const pinned = draft.kind === "term" ? pins.get(draft.list) : undefined;
  const typed = { htmlInput: { dir: "auto", readOnly: running } };
  const numeric = {
    htmlInput: { inputMode: "numeric" as const, readOnly: running },
  };
  const chosen = { input: { readOnly: running } };
  // A check box ignores readonly, so what it says is carried by aria-readonly and the edit ignored in `set`.
  const ticked = { input: { "aria-readonly": running } };
  const spoken = isolatedInText(called);
  return (
    <li>
      <Box
        component="fieldset"
        ref={(row: HTMLElement | null) => edits.register(draft.key, row)}
        aria-describedby={heldHere === null ? undefined : heldId}
        sx={ROW_SX}
      >
        <legend tabIndex={-1}>
          <bdi>{called}</bdi>
        </legend>
        {heldHere === null ? null : (
          <Notice id={heldId} severity="info">
            {say("declaration.held", { why: say(heldHere.why) })}
          </Notice>
        )}
        <Box sx={CONTROLS_SX}>
          <TextField
            label={say("declaration.name")}
            value={draft.name}
            onChange={(edit) => set({ name: edit.target.value })}
            error={draft.name !== "" && !fieldNameFits(draft.name)}
            helperText={
              draft.name !== "" && !fieldNameFits(draft.name)
                ? say("refusal.FIELD_NAME_UNUSABLE")
                : undefined
            }
            slotProps={{
              htmlInput: { "data-part": "name", readOnly: running },
            }}
            size="small"
          />
          <TextField
            label={say("declaration.label")}
            value={draft.label}
            onChange={(edit) => set({ label: edit.target.value })}
            error={draft.label !== "" && !labelFits(draft.label)}
            helperText={
              draft.label !== "" && !labelFits(draft.label)
                ? say("declaration.labelLimit")
                : undefined
            }
            slotProps={typed}
            size="small"
          />
          <TextField
            select
            label={say("declaration.kind")}
            value={draft.kind}
            onChange={(edit) => set({ kind: edit.target.value as FieldKind })}
            helperText={
              draft.kind === "fields" && draft.fields.length > 0
                ? say("declaration.kindDropsFields")
                : undefined
            }
            slotProps={chosen}
            size="small"
          >
            {(Object.keys(FIELD_KIND_WORDS) as FieldKind[]).map((kind) => (
              <MenuItem key={kind} value={kind}>
                {say(FIELD_KIND_WORDS[kind])}
              </MenuItem>
            ))}
          </TextField>
          {draft.kind === "text" ? (
            <TextField
              label={say("declaration.longest")}
              value={draft.longest}
              onChange={(edit) => set({ longest: edit.target.value })}
              error={!limitFits(draft.longest)}
              helperText={
                limitFits(draft.longest) ? undefined : say("declaration.limit")
              }
              slotProps={numeric}
              size="small"
            />
          ) : null}
          {draft.kind === "term" ? (
            <TextField
              select
              label={say("declaration.list")}
              value={draft.list}
              onChange={(edit) => set({ list: edit.target.value })}
              slotProps={chosen}
              size="small"
            >
              <MenuItem value="">{say("declaration.listNone")}</MenuItem>
              {listOptions(lists, draft.list, named).map(
                ([versionId, words]) => (
                  <MenuItem key={versionId} value={versionId}>
                    {words}
                  </MenuItem>
                ),
              )}
            </TextField>
          ) : null}
          <FormControlLabel
            control={
              <Checkbox
                checked={draft.many}
                readOnly={running}
                slotProps={ticked}
                onChange={(_event, checked) => set({ many: checked })}
              />
            }
            label={say("declaration.many")}
          />
          {draft.many ? (
            <TextField
              label={say("declaration.most")}
              value={draft.most}
              onChange={(edit) => set({ most: edit.target.value })}
              error={!limitFits(draft.most)}
              helperText={
                limitFits(draft.most) ? undefined : say("declaration.limit")
              }
              slotProps={numeric}
              size="small"
            />
          ) : null}
          <FormControlLabel
            control={
              <Checkbox
                checked={draft.mustBeGiven}
                readOnly={running}
                slotProps={ticked}
                onChange={(_event, checked) => set({ mustBeGiven: checked })}
              />
            }
            label={say("declaration.mustBeGiven")}
          />
          {demand === "stands" ? (
            <TextField
              select
              label={say("declaration.stands")}
              value={draft.stands}
              onChange={(edit) =>
                set({ stands: edit.target.value as FieldStanding | "" })
              }
              slotProps={chosen}
              size="small"
            >
              <MenuItem value="">{say("declaration.standsNone")}</MenuItem>
              {(Object.keys(FIELD_STANDING_WORDS) as FieldStanding[]).map(
                (standing) => (
                  <MenuItem key={standing} value={standing}>
                    {say(FIELD_STANDING_WORDS[standing])}
                  </MenuItem>
                ),
              )}
            </TextField>
          ) : null}
          {demand === "stands" && draft.stands === "above_confidence" ? (
            <TextField
              label={say("declaration.floor")}
              value={draft.floor}
              onChange={(edit) => set({ floor: edit.target.value })}
              error={!floorFits(draft.floor)}
              helperText={
                floorFits(draft.floor)
                  ? undefined
                  : say("declaration.floorLimit")
              }
              slotProps={numeric}
              size="small"
            />
          ) : null}
        </Box>
        <TextField
          label={say("declaration.help")}
          value={draft.help}
          onChange={(edit) => set({ help: edit.target.value })}
          error={draft.help !== "" && !lineFits(draft.help)}
          helperText={
            draft.help !== "" && !lineFits(draft.help)
              ? say("declaration.helpLimit")
              : undefined
          }
          slotProps={typed}
          size="small"
          fullWidth
        />
        {pinned === undefined ? null : <PinSaid pinned={pinned} />}
        <Box sx={CONTROLS_SX}>
          <Box component="span" data-part="up">
            <Press
              unavailable={running || position === 0}
              onPress={() => edits.move(place, -1, draft.key)}
            >
              {say("declaration.up", { name: spoken })}
            </Press>
          </Box>
          <Box component="span" data-part="down">
            <Press
              unavailable={running || position === fellows.length - 1}
              onPress={() => edits.move(place, 1, draft.key)}
            >
              {say("declaration.down", { name: spoken })}
            </Press>
          </Box>
          <Press unavailable={running} onPress={() => edits.remove(place)}>
            {say("declaration.remove", { name: spoken })}
          </Press>
          {draft.kind === "fields" ? (
            <Press unavailable={running} onPress={() => edits.addInside(place)}>
              {say("declaration.addInside", { name: spoken })}
            </Press>
          ) : null}
        </Box>
        {draft.kind === "fields" && draft.fields.length > 0 ? (
          <Box component="ul" role="list" sx={HELD_SX}>
            {draft.fields.map((inside, index) => (
              <Row
                key={inside.key}
                draft={inside}
                place={[...place, index]}
                fellows={draft.fields}
                demands={demands}
                held={held}
                running={running}
                named={named}
                lists={lists}
                pins={pins}
                edits={edits}
              />
            ))}
          </Box>
        ) : null}
      </Box>
    </li>
  );
}

/** A pin retired since is kept while the draft is one; a newer version in service is offered either way. */
function PinSaid({ pinned }: { readonly pinned: PinnedList }) {
  const retired = pinned.standing === "retired";
  if (!retired && pinned.newer === undefined) {
    return null;
  }
  return (
    <Typography variant="body2" sx={QUIET_SX}>
      {!retired
        ? say("declaration.listNewer", { number: pinned.newer!.number })
        : pinned.newer === undefined
          ? say("declaration.listRetired")
          : say("declaration.listRetiredNewest", {
              number: pinned.newer.number,
            })}
    </Typography>
  );
}

/** Every field, only read: what it is called, then what it is and what it asks. */
function Reading({
  fields,
  demands,
  first,
  labelledBy,
}: {
  readonly fields: readonly DeclaredField[];
  readonly demands: Demands;
  readonly first: boolean;
  /** The heading naming the first level; none below it. */
  readonly labelledBy?: string;
}) {
  if (fields.length === 0) {
    return (
      <Typography variant="body2" sx={QUIET_SX}>
        {say("declaration.none")}
      </Typography>
    );
  }
  return (
    <Box
      component="ul"
      role="list"
      aria-labelledby={labelledBy}
      sx={first ? LIST_SX : HELD_SX}
    >
      {fields.map((field) => (
        <li key={field.fieldId}>
          <Typography variant="body1">
            <bdi>{field.label ?? field.name.replaceAll("_", " ")}</bdi>{" "}
            <Box component="code" sx={QUIET_SX}>
              {field.name}
            </Box>
          </Typography>
          <Typography variant="body2" sx={QUIET_SX}>
            {fieldSaid(field, demandAt(demands, first) === "stands")}
          </Typography>
          {field.help === undefined ? null : (
            <Typography variant="body2" sx={QUIET_SX}>
              <bdi>{field.help}</bdi>
            </Typography>
          )}
          {field.fields === undefined ? null : (
            <Reading fields={field.fields} demands={demands} first={false} />
          )}
        </li>
      ))}
    </Box>
  );
}

/** A row by its name, or by its place among its fellows where it has none yet. */
function rowCalled(draft: DraftField, position: number): string {
  return draft.name === ""
    ? say("declaration.unnamed", { position: position + 1 })
    : draft.name;
}

/** The row after the one removed, else the one before, else the row holding it, else the half's heading. */
function afterRemoving(drafts: readonly DraftField[], place: Place): Focus {
  const above = place.slice(0, -1);
  let level: readonly DraftField[] = drafts;
  let holder: DraftField | undefined;
  for (const index of above) {
    holder = level[index];
    level = holder?.fields ?? [];
  }
  const here = place[place.length - 1]!;
  const next = level[here + 1] ?? level[here - 1] ?? holder;
  return next === undefined
    ? { at: "heading" }
    : { key: next.key, at: "legend" };
}

/**
 * What a field is and what it asks, each part in its own words, in the order a
 * reader asks them; `standsAsked` where its depth is asked what it takes to stand.
 */
function fieldSaid(field: DeclaredField, standsAsked: boolean): string {
  const parts = [say(FIELD_KIND_WORDS[field.kind])];
  if (field.kind === "text") {
    parts.push(
      field.longest === undefined
        ? say("declaration.longestUnsaid")
        : say("declaration.longestSaid", { longest: field.longest }),
    );
  }
  if (field.kind === "term") {
    parts.push(
      field.list === undefined
        ? say("declaration.fromUnsaid")
        : say("declaration.fromSaid", {
            name: isolatedInText(field.list.name),
            number: field.list.number,
          }),
    );
  }
  parts.push(
    !field.many
      ? say("declaration.one")
      : field.most === undefined
        ? say("declaration.manyUnsaid")
        : say("declaration.manyUpTo", { most: field.most }),
  );
  parts.push(
    say(field.mustBeGiven ? "declaration.given" : "declaration.mayBeEmpty"),
  );
  if (field.stands !== undefined) {
    parts.push(
      field.stands !== "above_confidence"
        ? say(FIELD_STANDING_WORDS[field.stands])
        : field.floor === undefined
          ? say("declaration.standsAboveUnsaid")
          : say("declaration.standsAbove", { floor: field.floor }),
    );
  } else if (standsAsked) {
    parts.push(say("declaration.standsUnsaid"));
  }
  return partsSaid(parts);
}

/** Every list pinned by a field as it was read, by the version pinned. */
function pinsIn(fields: readonly DeclaredField[]): Map<string, PinnedList> {
  const pins = new Map<string, PinnedList>();
  const walk = (level: readonly DeclaredField[]) => {
    for (const field of level) {
      if (field.list !== undefined) {
        pins.set(field.list.versionId, field.list);
      }
      walk(field.fields ?? []);
    }
  };
  walk(fields);
  return pins;
}

/** The words each list version is offered by, those that may be pinned now and those already pinned. */
function namedLists(
  lists: readonly ListVersion[],
  pins: ReadonlyMap<string, PinnedList>,
): Map<string, string> {
  const named = new Map<string, string>();
  for (const list of [...pins.values(), ...lists]) {
    named.set(
      list.versionId,
      say("declaration.listVersion", {
        name: isolatedInText(list.name),
        number: list.number,
      }),
    );
  }
  return named;
}

/** What may be pinned now, and the one already pinned where it no longer may be, so the field still reads it. */
function listOptions(
  lists: readonly ListVersion[],
  chosen: string,
  named: ReadonlyMap<string, string>,
): [string, string][] {
  const offered = lists.map((list): [string, string] => [
    list.versionId,
    named.get(list.versionId) ?? list.versionId,
  ]);
  return chosen === "" || lists.some((list) => list.versionId === chosen)
    ? offered
    : [[chosen, named.get(chosen) ?? chosen], ...offered];
}
