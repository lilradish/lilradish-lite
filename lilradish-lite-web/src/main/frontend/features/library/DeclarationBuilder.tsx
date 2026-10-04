import AddIcon from "@mui/icons-material/Add";
import ArrowDownwardIcon from "@mui/icons-material/ArrowDownward";
import ArrowUpwardIcon from "@mui/icons-material/ArrowUpward";
import DeleteOutlinedIcon from "@mui/icons-material/DeleteOutlined";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Checkbox from "@mui/material/Checkbox";
import FormControlLabel from "@mui/material/FormControlLabel";
import MenuItem from "@mui/material/MenuItem";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import {
  memo,
  useCallback,
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
} from "react";

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
import { say } from "../../i18n/app";
import { visible } from "../../i18n/visible";
import { ActButton } from "../../lib/action/ActButton";
import { IconPress } from "../../lib/action/IconPress";
import { Press } from "../../lib/action/Press";
import { isolatedInText } from "../../lib/direction/isolated";
import { Part } from "../../lib/layout/Part";
import {
  DEPTH_SX,
  HEADER_ACTS_SX,
  HELD_LIST_SX,
  LIST_SX,
} from "../../lib/layout/parts";
import { Notice } from "../../lib/notice/Notice";
import { joinedInSentence } from "../people/wordLists";
import type { Action } from "../../lib/request/useAction";
import { fieldNameFits, labelFits, lineFits } from "../../lib/text/legibility";
import {
  addedUnder,
  changedAt,
  demandAt,
  draftOf,
  floorFits,
  FILE_SIZE_UNITS,
  fileSizeFits,
  fileTypesFit,
  fileTypesTooMany,
  freshDraft,
  heldFor,
  limitFits,
  movedAt,
  removedAt,
  sentOf,
  type Demands,
  type DraftField,
  type FileSizeUnit,
  type Held,
  type Place,
} from "./declarationDrafts";
import {
  FIELD_KIND_WORDS,
  FILE_SIZE_UNIT_WORDS,
  fileSizeSaid,
  FIELD_STANDING_WORDS,
  partsSaid,
} from "./declarationWords";
import { WriteRefused } from "./WriteRefused";
import { useDraftPartStatus } from "./useDraftStatus";

// A fieldset's own frame is reset and its depth drawn on the item around it: a
// legend sits in a fieldset's border, outside any ground laid inside it.
const ROW_SX = {
  border: 0,
  m: 0,
  p: 0,
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

const ROW_ACTS_SX = { ...HEADER_ACTS_SX, mb: 1 };

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
    readonly heading: "h3" | "h5";
    /** A block of a version's content, whose first-level fields are each a group; or a group, in a step's panel. */
    readonly depth: 2 | 3;
    readonly demands: Demands;
    /** As the server last read them. */
    readonly fields: readonly DeclaredField[];
    /** Every list a field of terms may pin now. */
    readonly lists: readonly ListVersion[];
    /** Whether the host may write this half now, as the server said. */
    readonly editable: boolean;
  } & (Saving<T> | Controlled),
) {
  const { title, heading, depth, demands, fields, lists, editable } = props;
  const focusOnArrival =
    props.controlled === undefined && props.focusOnArrival === true;
  const headingId = useId();
  const section = useRef<HTMLElement>(null);
  const headingAt = useRef<HTMLHeadingElement>(null);
  const arriving = useRef(focusOnArrival);
  const [asked, setAsked] = useState(false);
  const [unsaved, setUnsaved] = useState(false);
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
    <Part
      title={title}
      actions={
        editable && unsaved ? (
          <Typography component="span" variant="body2" color="text.secondary">
            {say("draft.unsaved")}
          </Typography>
        ) : undefined
      }
      level={heading}
      depth={depth}
      headingId={headingId}
      headingRef={headingAt}
      ref={section}
    >
      {editable ? (
        <Editing
          demands={demands}
          fields={fields}
          lists={lists}
          pins={pins}
          firstRows={depth === 2 ? 3 : 4}
          labelledBy={headingId}
          focusHeading={() => headingAt.current?.focus()}
          onUnsaved={setUnsaved}
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
    </Part>
  );
}

function Editing<T>({
  demands,
  fields,
  lists,
  pins,
  firstRows,
  labelledBy,
  focusHeading,
  onUnsaved,
  host,
}: {
  readonly demands: Demands;
  readonly fields: readonly DeclaredField[];
  readonly lists: readonly ListVersion[];
  readonly pins: ReadonlyMap<string, PinnedList>;
  /** How deep a first-level row sits: a group in its own right, or held in the group the half is. */
  readonly firstRows: 3 | 4;
  readonly labelledBy: string;
  readonly focusHeading: () => void;
  readonly onUnsaved: (unsaved: boolean) => void;
  readonly host: Saving<T> | Controlled;
}) {
  const saved = useMemo(() => fields.map(draftOf), [fields]);
  const savedById = useMemo(() => savedFields(fields), [fields]);
  // Pin descriptions may refresh without changing the fields being edited.
  const savedAs = useMemo(() => JSON.stringify(saved), [saved]);
  const [own, setOwn] = useState(saved);
  const [readAs, setReadAs] = useState(savedAs);
  if (savedAs !== readAs) {
    setReadAs(savedAs);
    setOwn(saved);
  }
  const controlled = host.controlled;
  const drafts = controlled === undefined ? own : controlled.drafts;
  const controlledRef = useRef(controlled);
  const draftsRef = useRef(drafts);
  const setDrafts = useCallback(
    (next: (now: readonly DraftField[]) => DraftField[]): void => {
      const current = controlledRef.current;
      if (current === undefined) {
        setOwn(next);
      } else {
        current.change(next(current.drafts));
      }
    },
    [],
  );
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
        ? (row?.querySelector<HTMLElement>(":scope > legend") ??
          row?.querySelector<HTMLElement>(
            ':scope > button[aria-expanded="false"]',
          ))
        : focus.at === "name"
          ? row?.querySelector<HTMLElement>('[data-part="name"]')
          : row?.querySelector<HTMLElement>(`[data-part="${focus.at}"] button`);
    target?.focus();
  }, [drafts, focusHeading]);
  const running = host.controlled === undefined && host.action.running;
  const held = useMemo(() => heldFor(drafts, demands), [drafts, demands]);
  const sent = useMemo(
    () =>
      held === null ? drafts.map((draft) => sentOf(draft, demands)) : null,
    [drafts, demands, held],
  );
  const canSend = sent !== null;
  const savedSentAs = useMemo(
    () =>
      canSend
        ? JSON.stringify(saved.map((draft) => sentOf(draft, demands)))
        : null,
    [saved, demands, canSend],
  );
  const unchanged = useMemo(
    () => sent !== null && JSON.stringify(sent) === savedSentAs,
    [sent, savedSentAs],
  );
  useDraftPartStatus(
    labelledBy,
    !unchanged,
    running || (host.controlled === undefined && host.waiting === true),
  );
  useEffect(() => onUnsaved(!unchanged), [onUnsaved, unchanged]);
  const named = useMemo(() => namedLists(lists, pins), [lists, pins]);
  const runningRef = useRef(running);
  useEffect(() => {
    controlledRef.current = controlled;
    draftsRef.current = drafts;
    runningRef.current = running;
  }, [controlled, drafts, running]);
  const changing = useCallback(
    (next: (now: readonly DraftField[]) => DraftField[], focus: Focus) => {
      if (runningRef.current) {
        return;
      }
      pending.current = focus;
      setDrafts(next);
    },
    [setDrafts],
  );
  const edits = useMemo<Edits>(
    () => ({
      change: (place, change) => {
        if (!runningRef.current) {
          setDrafts((now) => changedAt(now, place, change));
        }
      },
      remove: (place) =>
        changing(
          (now) => removedAt(now, place),
          afterRemoving(draftsRef.current, place),
        ),
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
    }),
    [changing, setDrafts],
  );

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
              position={index}
              last={index === drafts.length - 1}
              demands={demands}
              held={held}
              savedById={savedById}
              running={running}
              named={named}
              lists={lists}
              pins={pins}
              depth={firstRows}
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
            variant={!unchanged ? "contained" : undefined}
            waiting={host.waiting}
            reason={
              held !== null
                ? { severity: "warning", words: say(held.why) }
                : unchanged
                  ? { severity: "info", words: say("declaration.unchanged") }
                  : undefined
            }
            act={(signal) => {
              if (sent === null) {
                throw new Error("A held declaration cannot be saved");
              }
              return host.save(sent, signal);
            }}
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

interface RowProps {
  readonly draft: DraftField;
  readonly place: Place;
  readonly position: number;
  readonly last: boolean;
  readonly demands: Demands;
  readonly held: Held | null;
  readonly savedById: ReadonlyMap<string, DeclaredField>;
  readonly running: boolean;
  readonly named: ReadonlyMap<string, string>;
  readonly lists: readonly ListVersion[];
  readonly pins: ReadonlyMap<string, PinnedList>;
  readonly depth: 3 | 4;
  readonly edits: Edits;
}

const Row = memo(
  function Row({
    draft,
    place,
    position,
    last,
    demands,
    held,
    savedById,
    running,
    named,
    lists,
    pins,
    depth,
    edits,
  }: RowProps) {
    const heldId = useId();
    const savedField =
      draft.fieldId === null ? undefined : savedById.get(draft.fieldId);
    const [expanded, setExpanded] = useState(savedField === undefined);
    const savedAs = useMemo(
      () =>
        JSON.stringify(savedField === undefined ? null : draftOf(savedField)),
      [savedField],
    );
    const wasSaved = useRef(savedAs);
    if (wasSaved.current !== savedAs) {
      wasSaved.current = savedAs;
      setExpanded(savedField === undefined);
    }
    const fieldset = useRef<HTMLElement>(null);
    const focusesName = expanded && savedField !== undefined;
    useEffect(() => {
      if (focusesName) {
        fieldset.current
          ?.querySelector<HTMLElement>('[data-part="name"]')
          ?.focus();
      }
    }, [focusesName]);
    const set = (changed: Partial<DraftField>) =>
      edits.change(place, (now) => ({ ...now, ...changed }));
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
    const up = say("declaration.up", { name: spoken });
    const down = say("declaration.down", { name: spoken });
    const remove = say("declaration.remove", { name: spoken });
    const addInside = say("declaration.addInside", { name: spoken });
    return (
      <Box component="li" sx={DEPTH_SX[depth]}>
        <Box
          component="fieldset"
          ref={(row: HTMLElement | null) => {
            fieldset.current = row;
            edits.register(draft.key, row);
          }}
          aria-label={!expanded ? called : undefined}
          aria-describedby={heldHere === null ? undefined : heldId}
          sx={ROW_SX}
        >
          {expanded ? (
            <legend tabIndex={-1} aria-label={called}>
              {rowVisible(draft.name, position)}
            </legend>
          ) : (
            <Button
              variant="text"
              aria-expanded={false}
              onClick={() => setExpanded(true)}
              sx={{
                display: "block",
                width: "100%",
                overflow: "hidden",
                textOverflow: "ellipsis",
                whiteSpace: "nowrap",
                textAlign: "start",
              }}
            >
              {rowVisible(draft.name, position)} ·{" "}
              {fieldSaid(savedField!, demand === "stands")}
            </Button>
          )}
          {heldHere === null ? null : (
            <Notice id={heldId} severity="warning">
              {say("declaration.held", { why: say(heldHere.why) })}
            </Notice>
          )}
          {expanded ? (
            <>
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
                  onChange={(edit) =>
                    set({ kind: edit.target.value as FieldKind })
                  }
                  helperText={
                    draft.kind === "fields" && draft.fields.length > 0
                      ? say("declaration.kindDropsFields")
                      : undefined
                  }
                  slotProps={chosen}
                  size="small"
                >
                  {(Object.keys(FIELD_KIND_WORDS) as FieldKind[]).map(
                    (kind) => (
                      <MenuItem key={kind} value={kind}>
                        {say(FIELD_KIND_WORDS[kind])}
                      </MenuItem>
                    ),
                  )}
                </TextField>
                {draft.kind === "text" ? (
                  <>
                    <TextField
                      label={say("declaration.longest")}
                      value={draft.longest}
                      onChange={(edit) => set({ longest: edit.target.value })}
                      error={!limitFits(draft.longest)}
                      helperText={
                        limitFits(draft.longest)
                          ? undefined
                          : say("declaration.limit")
                      }
                      slotProps={numeric}
                      size="small"
                    />
                    <TextField
                      select
                      label={say("declaration.lines")}
                      value={draft.lines}
                      onChange={(edit) =>
                        set({ lines: edit.target.value as DraftField["lines"] })
                      }
                      slotProps={chosen}
                      size="small"
                    >
                      <MenuItem value="one">
                        {say("declaration.oneLine")}
                      </MenuItem>
                      <MenuItem value="several">
                        {say("declaration.severalLines")}
                      </MenuItem>
                    </TextField>
                  </>
                ) : null}
                {draft.kind === "file" ? (
                  <FileFieldControls
                    draft={draft}
                    set={set}
                    running={running}
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
                      limitFits(draft.most)
                        ? undefined
                        : say("declaration.limit")
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
                      onChange={(_event, checked) =>
                        set({ mustBeGiven: checked })
                      }
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
                    <MenuItem value="">
                      {say("declaration.standsNone")}
                    </MenuItem>
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
              <Box sx={ROW_ACTS_SX}>
                <Box component="span" data-part="up">
                  <IconPress
                    icon={<ArrowUpwardIcon />}
                    label={up}
                    unavailable={running || position === 0}
                    onPress={() => edits.move(place, -1, draft.key)}
                  >
                    {say("declaration.upTip")}
                  </IconPress>
                </Box>
                <Box component="span" data-part="down">
                  <IconPress
                    icon={<ArrowDownwardIcon />}
                    label={down}
                    unavailable={running || last}
                    onPress={() => edits.move(place, 1, draft.key)}
                  >
                    {say("declaration.downTip")}
                  </IconPress>
                </Box>
                <IconPress
                  icon={<DeleteOutlinedIcon />}
                  warns
                  label={remove}
                  unavailable={running}
                  onPress={() => edits.remove(place)}
                >
                  {say("declaration.removeTip")}
                </IconPress>
                {draft.kind === "fields" ? (
                  <IconPress
                    icon={<AddIcon />}
                    label={addInside}
                    unavailable={running}
                    onPress={() => edits.addInside(place)}
                  >
                    {say("declaration.addInsideTip")}
                  </IconPress>
                ) : null}
              </Box>
              {draft.kind === "fields" && draft.fields.length > 0 ? (
                <Box component="ul" role="list" sx={LIST_SX}>
                  {draft.fields.map((inside, index) => (
                    <Row
                      key={inside.key}
                      draft={inside}
                      place={[...place, index]}
                      position={index}
                      last={index === draft.fields.length - 1}
                      demands={demands}
                      held={held}
                      savedById={savedById}
                      running={running}
                      named={named}
                      lists={lists}
                      pins={pins}
                      depth={4}
                      edits={edits}
                    />
                  ))}
                </Box>
              ) : null}
            </>
          ) : null}
        </Box>
      </Box>
    );
  },
  (before, after) =>
    before.draft === after.draft &&
    before.position === after.position &&
    before.last === after.last &&
    before.demands === after.demands &&
    heldWithin(before.held, before.place) ===
      heldWithin(after.held, after.place) &&
    before.savedById === after.savedById &&
    before.running === after.running &&
    before.named === after.named &&
    before.lists === after.lists &&
    before.pins === after.pins &&
    before.depth === after.depth &&
    before.edits === after.edits &&
    before.place.length === after.place.length &&
    before.place.every((index, at) => index === after.place[at]),
);

function heldWithin(held: Held | null, place: Place): string | null {
  return held !== null && place.every((index, at) => held.place[at] === index)
    ? `${held.why}:${held.place.slice(place.length).join(".")}`
    : null;
}

function savedFields(
  fields: readonly DeclaredField[],
): Map<string, DeclaredField> {
  const saved = new Map<string, DeclaredField>();
  const add = (rows: readonly DeclaredField[]) => {
    for (const field of rows) {
      saved.set(field.fieldId, field);
      if (field.fields !== undefined) {
        add(field.fields);
      }
    }
  };
  add(fields);
  return saved;
}

function FileFieldControls({
  draft,
  set,
  running,
}: {
  readonly draft: DraftField;
  readonly set: (changed: Partial<DraftField>) => void;
  readonly running: boolean;
}) {
  const typesTooMany = fileTypesTooMany(draft.fileTypes);
  const typesValid = !typesTooMany && fileTypesFit(draft.fileTypes);
  const sizeValid = fileSizeFits(draft.fileSize, draft.fileSizeUnit);
  return (
    <>
      <TextField
        label={say("declaration.fileTypes")}
        value={draft.fileTypes}
        onChange={(edit) => set({ fileTypes: edit.target.value })}
        multiline
        minRows={2}
        error={!typesValid}
        helperText={
          typesTooMany
            ? say("refusal.FILE_TYPES_TOO_MANY")
            : typesValid
              ? say("declaration.fileTypesHint")
              : say("declaration.fileTypesInvalid")
        }
        slotProps={{ htmlInput: { readOnly: running } }}
        size="small"
      />
      <TextField
        label={say("declaration.maximumSize")}
        value={draft.fileSize}
        onChange={(edit) => set({ fileSize: edit.target.value })}
        error={!sizeValid}
        helperText={
          sizeValid
            ? say("declaration.sizeUnitsHint")
            : say("refusal.FILE_SIZE_LIMIT_UNUSABLE")
        }
        slotProps={{ htmlInput: { inputMode: "numeric", readOnly: running } }}
        size="small"
      />
      <TextField
        select
        value={draft.fileSizeUnit}
        onChange={(edit) =>
          set({ fileSizeUnit: edit.target.value as FileSizeUnit })
        }
        slotProps={{
          input: { readOnly: running },
          select: { "aria-label": say("declaration.sizeUnitsHint") },
        }}
        size="small"
      >
        {(Object.keys(FILE_SIZE_UNITS) as FileSizeUnit[]).map((unit) => (
          <MenuItem key={unit} value={unit}>
            {say(FILE_SIZE_UNIT_WORDS[unit])}
          </MenuItem>
        ))}
      </TextField>
    </>
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
      sx={first ? LIST_SX : HELD_LIST_SX}
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

function rowVisible(name: string, position: number) {
  return name === "" ? (
    visible("declaration.unnamedVisible", {
      name: (
        <bdi>{say("declaration.defaultName", { position: position + 1 })}</bdi>
      ),
      hint: (
        <Box
          component="span"
          sx={{ typography: "systemHint", color: "text.secondary" }}
        >
          {say("name.notNamed")}
        </Box>
      ),
    })
  ) : (
    <bdi>{name}</bdi>
  );
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
      say(
        field.lines === "one"
          ? "declaration.oneLine"
          : "declaration.severalLines",
      ),
    );
    parts.push(
      field.longest === undefined
        ? say("declaration.longestUnsaid")
        : say("declaration.longestSaid", { longest: field.longest }),
    );
  }
  if (
    field.kind === "file" &&
    field.types !== undefined &&
    field.maximumBytes !== undefined
  ) {
    parts.push(
      say("declaration.fileShown", {
        types: joinedInSentence(field.types),
        size: fileSizeSaid(field.maximumBytes),
      }),
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
