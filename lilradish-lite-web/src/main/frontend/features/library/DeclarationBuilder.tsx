import AddIcon from "@mui/icons-material/Add";
import ArrowDownwardIcon from "@mui/icons-material/ArrowDownward";
import ArrowUpwardIcon from "@mui/icons-material/ArrowUpward";
import ErrorOutlineIcon from "@mui/icons-material/ErrorOutlineOutlined";
import EditOutlinedIcon from "@mui/icons-material/EditOutlined";
import Box from "@mui/material/Box";
import Checkbox from "@mui/material/Checkbox";
import FormControlLabel from "@mui/material/FormControlLabel";
import IconButton from "@mui/material/IconButton";
import MenuItem from "@mui/material/MenuItem";
import TableRow from "@mui/material/TableRow";
import Typography from "@mui/material/Typography";
import Tooltip from "@mui/material/Tooltip";
import {
  createContext,
  memo,
  Fragment,
  useContext,
  type ReactNode,
  type ReactElement,
  type RefObject,
  useCallback,
  useEffect,
  useId,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from "react";

import { createPortal } from "react-dom";

import {
  type DeclaredField,
  type FieldKind,
  type FieldStanding,
  type PinnedList,
  type SentField,
} from "../../api/declaration";
import { ProblemView } from "../../app/ProblemView";
import type { GroupRule } from "../../app/standing/actRules";
import { say, type MessageId } from "../../i18n/app";
import { visible } from "../../i18n/visible";
import { ActButton } from "../../lib/action/ActButton";
import { IconPress } from "../../lib/action/IconPress";
import { InfoMark } from "../../lib/action/InfoMark";
import { Press, heldOr } from "../../lib/action/Press";
import { NATIVE_TABLE_SX, TABLE_GEOMETRY } from "../../lib/collection/table";
import { isolatedInText } from "../../lib/direction/isolated";
import { Part } from "../../lib/layout/Part";
import { SidePanelHost } from "../../lib/layout/SidePanelHost";
import { HEADER_ACTS_SX } from "../../lib/layout/parts";
import { UnsavedMark } from "../../lib/layout/UnsavedMark";
import { useNarrowerThan } from "../../lib/layout/useNarrowerThan";
import { Notice } from "../../lib/notice/Notice";
import { TOKENS } from "../../lib/theme/tokens";
import { joinedInSentence } from "../people/wordLists";
import { FieldName } from "../../lib/form/FieldName";
import { LabelledTextField } from "../../lib/form/LabelledTextField";
import type { Action } from "../../lib/request/useAction";
import { fieldNameFits, labelFits, lineFits } from "../../lib/text/legibility";
import {
  addedUnder,
  allFields,
  candidateFields,
  rebaseCandidates,
  changedAt,
  changedKey,
  demandAt,
  draftOf,
  fieldAtKey,
  fieldKeysAfter,
  fieldAtPlace,
  fieldPlace,
  floorFits,
  FILE_SIZE_UNITS,
  fileSizeFits,
  fileTypesFit,
  fileTypesTooMany,
  freshDraft,
  heldFor,
  limitFits,
  LARGEST_LIMIT,
  movedAt,
  sentOf,
  withoutField,
  type Demands,
  type DraftField,
  type FileSizeUnit,
  type Held,
  type Place,
} from "./declarationDrafts";
import {
  FIELD_KIND_WORDS,
  FILE_SIZE_UNIT_WORDS,
  FIELD_STANDING_WORDS,
} from "./declarationWords";
import { sizeSaid } from "../../lib/text/sizeSaid";
import { useHeardRefusal } from "./useHeardRefusal";
import { WriteRefused } from "./WriteRefused";
import { useDraftPartStatus } from "./useDraftStatus";
import type { VersionDetail } from "../../api/groups/{groupId}/versions/{versionId}";
import type { WorkflowVersion } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}";
import type { Reason } from "../../lib/action/Press";
import { EditorVersionPicker, useVersionDetail } from "./EditorVersionPicker";
import { referencedFieldDetails } from "./workflowDrafts";
import { VersionLabel, versionLabelText } from "../../lib/text/VersionLabel";
import { FactTag } from "../../lib/text/FactTag";
import { StatusLine, type Line } from "../../lib/notice/StatusLine";

function heldWords(why: MessageId): string {
  return why === "declaration.limit"
    ? say(why, { minimum: 1, maximum: LARGEST_LIMIT })
    : say(why);
}

const ROW_SX = {
  border: 0,
  m: 0,
  p: 0,
  mb: 1,
  "& legend": { typography: "fieldName", px: 0.5 },
};

const CONTROLS_SX = {
  display: "grid",
  gridTemplateColumns: "repeat(3, minmax(0, 1fr))",
  gap: "16px 20px",
  maxWidth: 820,
  alignItems: "flex-start",
  mb: 1,
};

const NOTHING_DECLARED_SX = { color: "ink.secondary" };

/** A held declaration's change not yet taken into what holds it, kept while its editor is shut away. */
export interface KeptCandidate {
  readonly basis: string;
  readonly drafts: readonly DraftField[];
  readonly details: Map<string, VersionDetail>;
  readonly removed: ReadonlySet<string>;
  readonly picked: string | null;
  /** Asked to open again on its first change, the field shut since included. */
  readonly opening?: boolean;
}

/** Lent by what holds declarations whose editors it may shut before their changes are taken in. */
export const KeptCandidates = createContext<{
  readonly kept: (scope: string) => KeptCandidate | undefined;
  readonly keep: (scope: string, candidate: KeptCandidate | null) => void;
} | null>(null);

const FIELD_DRAWER_WIDTH = Number.parseFloat(TOKENS.drawer.field);

const FieldSaveReason = createContext<{
  readonly reason?: SaveReason;
  readonly id: string;
} | null>(null);

const NOTHING_READ_SX = {
  ...NOTHING_DECLARED_SX,
  typography: "tableCell",
  py: `${TABLE_GEOMETRY.bodyPadding}px`,
};

const HIDDEN_SX = {
  position: "absolute",
  width: "1px",
  height: "1px",
  overflow: "hidden",
  clipPath: "inset(50%)",
  whiteSpace: "nowrap",
};

const QUIET_SX = { color: "text.secondary" };

const ROW_ACTS_SX = { ...HEADER_ACTS_SX, mb: 1 };

const FieldMoves = createContext<{
  readonly available: boolean;
  readonly waiting: boolean;
  readonly reason?: Reason;
  readonly reasonId?: string;
  readonly failed: string | null;
  readonly refusal: ReactNode;
  readonly refusalAt: RefObject<HTMLDivElement | null>;
  readonly move: (key: string, by: -1 | 1) => void;
} | null>(null);

const MOVE_SX = {
  width: TOKENS.size.controlSmall,
  height: TOKENS.size.controlSmall,
  color: "ink.secondary",
  borderRadius: TOKENS.shape.controlRadius,
  "& .MuiSvgIcon-root": {
    width: TOKENS.table.moveIcon,
    height: TOKENS.table.moveIcon,
  },
  "&:hover, &:active": { bgcolor: "act.hover", color: "ink.secondary" },
};

const MOVE_SLOT_SX = {
  width: TOKENS.size.controlSmall,
  height: TOKENS.size.controlSmall,
  flexShrink: 0,
};

const NARROW_FIELD_ROW_SX = {
  display: "grid",
  gridTemplateColumns: "minmax(0, 1fr) auto",
  "& > td": { width: "auto !important" },
};

/** Where focus goes once the rows a press changed are drawn: a row's part, or the half's heading. */
type Focus =
  | { readonly key: string; readonly at: "legend" | "up" | "down" }
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
  /** A host-specific reason this whole half cannot replace the one read, beyond the declaration's own checks. */
  readonly refusalOf?: (fields: readonly SentField[]) => string | null;
  /** Whether another write of the host's is out, which saving this half would be refused beside. */
  readonly waiting?: boolean;
  /** Where the half was drawn afresh by a press inside it, which is gone: the keyboard starts again here. */
  readonly focusOnArrival?: boolean;
  /** Offered beside a refusal for somebody saving since, which is said in the editor that asked while it is open. */
  readonly onReadAfresh: () => void;
  readonly controlled?: undefined;
}

/** A half the host holds as typed and sends with what else it writes, so no control of the builder's sends it. */
interface Controlled {
  readonly controlled: {
    readonly drafts: readonly DraftField[];
    readonly details: ReadonlyMap<string, VersionDetail>;
    readonly change: (drafts: DraftField[]) => void;
    /** The unsaved part it counts as, which it is sent with. */
    readonly unit: string;
    readonly move?: {
      readonly action: Action<WorkflowVersion>;
      readonly waiting: boolean;
      readonly reason?: Reason;
      readonly save: (
        drafts: readonly DraftField[],
        signal: AbortSignal,
        focus: { readonly key: string; readonly by: -1 | 1 },
      ) => Promise<WorkflowVersion>;
    };
    readonly onLocate?: () => void;
    readonly resolve: (
      detail: VersionDetail | null,
      change: (drafts: readonly DraftField[]) => DraftField[],
    ) => void;
  };
}

/**
 * Edited where the host may write it; what each depth is asked is the host's `demands`.
 * Sent whole by its own control, or held by the host and sent with what else it writes.
 */
export function DeclarationBuilder<T>(
  props: {
    readonly title: string;
    readonly hint?: string;
    readonly heading: "h3" | "h5";
    /** A block of a version's content, whose first-level fields are each a group; or a group, in a step's panel. */
    readonly depth: 2 | 3;
    readonly demands: Demands;
    /** As the server last read them. */
    readonly fields: readonly DeclaredField[];
    readonly scope: string;
    /** Whether the host may write this half now, as the server said. */
    readonly editable: boolean;
    readonly shapeLocked?: boolean;
    readonly acceptedWhen?: boolean;
    readonly searchWay?: string;
  } & (Saving<T> | Controlled),
) {
  const { title, hint, heading, depth, demands, fields, scope, editable } =
    props;
  const focusOnArrival =
    props.controlled === undefined && props.focusOnArrival === true;
  const headingId = useId();
  const section = useRef<HTMLElement>(null);
  const narrow = useNarrowerThan(
    section,
    Number.parseFloat(TOKENS.table.rowsBelow),
  );
  const headingAt = useRef<HTMLHeadingElement>(null);
  const arriving = useRef(focusOnArrival);
  const [unsaved, setUnsaved] = useState(false);
  const heard = useHeardRefusal(
    props.controlled === undefined ? props.action : undefined,
    unsaved,
  );
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
  const refusedBy = heard.problem;
  const refusal =
    props.controlled !== undefined || refusedBy === null ? null : (
      <WriteRefused
        settled={heard.settled}
        searchWay={props.searchWay}
        problem={refusedBy}
        rule={props.rule}
        waiting={props.waiting === true}
        onReadAfresh={props.onReadAfresh}
      />
    );
  return (
    <Part
      title={title}
      marker={
        hint === undefined ? undefined : (
          <InfoMark about={say("workflow.givesAbout")} hint={hint} />
        )
      }
      unsaved={editable && unsaved}
      level={heading}
      depth={depth}
      headingId={headingId}
      headingRef={headingAt}
      afterTitle={null}
      ref={section}
    >
      {editable ? (
        <Editing
          demands={demands}
          narrow={narrow}
          acceptedWhen={props.acceptedWhen === true}
          shapeLocked={props.shapeLocked === true}
          fields={fields}
          scope={scope}
          pins={pins}
          labelledBy={headingId}
          title={title}
          section={section}
          focusHeading={() => headingAt.current?.focus()}
          onUnsaved={setUnsaved}
          onLetGo={heard.letGo}
          refusal={refusal}
          refusedBy={refusedBy}
          host={
            props.controlled === undefined
              ? {
                  ...props,
                  save: (sent, signal) => {
                    heard.sent();
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
          narrow={narrow}
          acceptedWhen={props.acceptedWhen === true}
          labelledBy={headingId}
        />
      )}
      {editable ? null : refusal}
    </Part>
  );
}

function Editing<T>({
  demands,
  narrow,
  acceptedWhen,
  shapeLocked,
  fields,
  scope,
  pins,
  labelledBy,
  title,
  section,
  focusHeading,
  onUnsaved,
  onLetGo,
  refusal,
  refusedBy,
  host,
}: {
  readonly demands: Demands;
  readonly narrow: boolean;
  readonly acceptedWhen: boolean;
  readonly shapeLocked: boolean;
  readonly fields: readonly DeclaredField[];
  readonly scope: string;
  readonly pins: ReadonlyMap<string, PinnedList>;
  readonly labelledBy: string;
  readonly title: string;
  readonly section: RefObject<HTMLElement | null>;
  readonly focusHeading: () => void;
  readonly onUnsaved: (unsaved: boolean) => void;
  /** Said by Abandon, which lets go of a refusal heard. */
  readonly onLetGo: () => void;
  readonly refusal: ReactNode;
  /** What `refusal` says, a new one for each refusal. */
  readonly refusedBy: object | null;
  readonly host: Saving<T> | Controlled;
}) {
  const saved = useMemo(() => fields.map(draftOf), [fields]);
  const savedById = useMemo(() => savedFields(fields), [fields]);
  const controlled = host.controlled;
  const stored = controlled?.drafts ?? saved;
  const saveContext = useRef({ scope, stored });
  useLayoutEffect(() => {
    saveContext.current = { scope, stored };
  }, [scope, stored]);
  const storedByKey = useMemo(
    () => new Map(allFields(stored).map((field) => [field.key, field])),
    [stored],
  );
  const savedAs = useMemo(() => JSON.stringify(stored), [stored]);
  const keeping = useContext(KeptCandidates);
  const holding = controlled !== undefined && keeping !== null;
  const [restored] = useState(() => {
    const kept = holding ? keeping.kept(scope) : undefined;
    if (kept === undefined) return undefined;
    // Kept over a save, it is laid again on what the save answered.
    const drafts =
      kept.basis === savedAs
        ? kept.drafts
        : rebaseCandidates(
            JSON.parse(kept.basis) as DraftField[],
            stored,
            kept.drafts,
            "",
          );
    const held = new Set(allFields(drafts).map((field) => field.key));
    const keys = fieldKeysAfter(JSON.parse(kept.basis) as DraftField[], stored);
    const removed = new Set(
      [...kept.removed]
        .map((key) => keys.get(key) ?? key)
        .filter((key) => held.has(key)),
    );
    if (JSON.stringify(drafts) === savedAs && removed.size === 0)
      return undefined;
    const dirty = firstChanged(drafts, removed, storedByKey);
    const pickedKey =
      kept.picked === null ? null : (keys.get(kept.picked) ?? kept.picked);
    const picked = pickedKey !== null && held.has(pickedKey) ? pickedKey : null;
    return {
      ...kept,
      drafts,
      removed,
      picked: picked ?? (kept.opening ? (dirty?.key ?? null) : null),
    };
  });
  const [own, setOwn] = useState({
    drafts: restored?.drafts ?? stored,
    details: restored?.details ?? new Map<string, VersionDetail>(),
  });
  const [picked, setPicked] = useState<string | null>(restored?.picked ?? null);
  const [removed, setRemoved] = useState<ReadonlySet<string>>(
    restored?.removed ?? new Set(),
  );
  useLayoutEffect(() => {
    if (!holding) return;
    const dirty = JSON.stringify(own.drafts) !== savedAs || removed.size > 0;
    keeping.keep(
      scope,
      dirty
        ? {
            basis: savedAs,
            drafts: own.drafts,
            details: own.details,
            removed,
            picked,
          }
        : null,
    );
  }, [holding, keeping, scope, savedAs, own, removed, picked]);
  const [applied, setApplied] = useState<{
    key: string;
    before: readonly DraftField[];
    drafts: readonly DraftField[];
  } | null>(null);
  const [readAs, setReadAs] = useState({ scope, savedAs, stored });
  if (
    readAs.scope !== scope ||
    readAs.savedAs !== savedAs ||
    (applied !== null && readAs.stored !== stored)
  ) {
    const keeping =
      applied !== null && (readAs.scope === scope || applied.key === "");
    setOwn({
      drafts: keeping
        ? rebaseCandidates(applied.before, stored, applied.drafts, applied.key)
        : stored,
      details: keeping ? own.details : new Map(),
    });
    setReadAs({ scope, savedAs, stored });
    setApplied(null);
    setRemoved(
      keeping
        ? new Set([...removed].filter((key) => key !== applied.key))
        : new Set(),
    );
    if (!keeping) setPicked(null);
  }
  const drafts = own.drafts;
  const heldPins = useMemo(() => {
    const held = new Map(pins);
    for (const detail of [
      ...(controlled?.details.values() ?? []),
      ...own.details.values(),
    ]) {
      if (detail.kind === "reference_list")
        held.set(detail.version.versionId, detail.version);
    }
    return held;
  }, [pins, controlled?.details, own.details]);
  const draftsRef = useRef(drafts);
  const running =
    controlled === undefined
      ? host.action.running
      : controlled.move?.action.running === true;
  const runningRef = useRef(running);
  useEffect(() => {
    draftsRef.current = drafts;
    runningRef.current = running;
  }, [drafts, running]);
  const setDrafts = useCallback(
    (next: (now: readonly DraftField[]) => DraftField[]) => {
      setOwn((now) => {
        const drafts = next(now.drafts);
        return { drafts, details: referencedFieldDetails(drafts, now.details) };
      });
    },
    [],
  );
  const made = useRef(0);
  const rows = useRef(new Map<string, HTMLElement>());
  const pending = useRef<Focus | null>(null);
  const pendingStored = useRef<readonly DraftField[] | null>(null);
  useEffect(() => {
    if (pendingStored.current === stored) return;
    pendingStored.current = null;
    const focus = pending.current;
    pending.current = null;
    if (focus === null) return;
    if (focus.at === "heading") {
      focusHeading();
      return;
    }
    const row = rows.current.get(focus.key);
    const target =
      focus.at === "legend"
        ? row?.querySelector<HTMLElement>(
            ':scope > legend, button[aria-expanded="false"]',
          )
        : (section.current?.querySelector<HTMLElement>(
            `[data-field-key="${CSS.escape(focus.key)}"] [data-part="${focus.at}"] button`,
          ) ??
          section.current?.querySelector<HTMLElement>(
            `[data-field-key="${CSS.escape(focus.key)}"] [data-part] button`,
          ));
    target?.focus();
  }, [drafts, stored, focusHeading, section]);
  const reveal = useMemo(
    () =>
      picked === null
        ? undefined
        : {
            focus: () =>
              rows.current
                .get(picked)
                ?.querySelector<HTMLElement>('[data-part="name"]')
                ?.focus(),
          },
    [picked],
  );
  const { refusalAt, insist } = useRefusalFocus(refusedBy);
  const [editorHost] = useState(() => document.createElement("div"));
  const held = useMemo(() => heldFor(drafts, demands), [drafts, demands]);
  useUnsavedReport({
    labelledBy,
    drafts,
    savedAs,
    removed,
    running,
    host,
    section,
    storedByKey,
    setPicked,
    focusHeading,
    onUnsaved,
  });
  const edits = useMemo<Edits>(
    () => ({
      pick: setPicked,
      choose: (key, detail) => {
        if (
          runningRef.current ||
          shapeLocked ||
          (detail !== null && detail.kind !== "reference_list") ||
          fieldAtKey(draftsRef.current, key)?.kind !== "term"
        )
          return;
        setOwn((now) => {
          if (fieldAtKey(now.drafts, key)?.kind !== "term") return now;
          const details = new Map(now.details);
          if (detail !== null) details.set(detail.version.versionId, detail);
          const drafts = changedKey(now.drafts, key, (draft) => ({
            ...draft,
            list: detail?.version.versionId ?? "",
          }));
          return { drafts, details: referencedFieldDetails(drafts, details) };
        });
      },
      change: (place, change) => {
        if (!runningRef.current)
          setDrafts((now) => changedAt(now, place, change));
      },
      remove: (place) => {
        if (runningRef.current || shapeLocked) return;
        const field = fieldAtPlace(draftsRef.current, place);
        if (field !== undefined)
          setRemoved((now) => new Set([...now, field.key]));
      },
      addInside: (place) => {
        if (runningRef.current || shapeLocked) return;
        const among =
          place === null
            ? draftsRef.current
            : (fieldAtPlace(draftsRef.current, place)?.fields ?? []);
        const waiting = among.find(
          (field) =>
            fieldAtKey(saveContext.current.stored, field.key) === undefined,
        );
        if (waiting !== undefined) {
          setPicked(waiting.key);
          return;
        }
        made.current += 1;
        const added = freshDraft("new-" + made.current);
        setDrafts((now) => addedUnder(now, place, added));
        setPicked(added.key);
      },
      register: (key, row) => {
        if (row === null) rows.current.delete(key);
        else rows.current.set(key, row);
      },
    }),
    [setDrafts, shapeLocked],
  );
  const {
    proposed,
    sent,
    selected,
    ordered,
    position,
    drawerTitle,
    navigationOnly,
    reason,
    after,
  } = pickedFieldFacts({
    picked,
    removed,
    drafts,
    stored,
    demands,
    host,
    title,
    savedAs,
    storedByKey,
  });
  const cancel = () => {
    if (running || picked === null) return;
    onLetGo();
    const key = picked;
    setDrafts((now) => candidateFields(now, stored, key));
    setRemoved(
      (now) => new Set([...now].filter((candidate) => candidate !== key)),
    );
    setPicked(null);
  };
  const done = (next: string | null) => {
    if (
      controlled === undefined ||
      picked === null ||
      sent === null ||
      running ||
      reason !== undefined
    )
      return;
    const key = picked;
    setApplied({ key, before: stored, drafts });
    const detail =
      selected?.kind === "term"
        ? (own.details.get(selected.list) ?? null)
        : null;
    controlled.resolve(detail, () => [...proposed]);
    setPicked(next);
  };
  const save = (signal: AbortSignal, next: string | null) => {
    if (controlled !== undefined || picked === null || sent === null)
      throw new Error("A held declaration cannot be saved");
    const key = picked;
    setMoveFailed(null);
    return host.save(sent, signal).then((answer) => {
      if (
        !signal.aborted &&
        saveContext.current.scope === scope &&
        saveContext.current.stored === stored
      ) {
        if (next === null) {
          const place = fieldPlace(drafts, key);
          pending.current =
            removed.has(key) && place !== null
              ? afterRemoving(drafts, place)
              : { key, at: "legend" };
          pendingStored.current = stored;
        }
        setApplied({ key, before: stored, drafts });
        setPicked(next);
      }
      return answer;
    });
  };
  const [movedSaid, setMovedSaid] = useState<Line | null>(null);
  const saveReasonId = useId();
  const [moveFailed, setMoveFailed] = useState<string | null>(null);
  useEffect(() => {
    if (moveFailed !== null && refusedBy !== null) refusalAt.current?.focus();
  }, [moveFailed, refusedBy, refusalAt]);
  const move = (key: string, by: -1 | 1) => {
    const place = fieldPlace(stored, key);
    const field = fieldAtKey(stored, key);
    if (place === null || field === undefined || running || shapeLocked) return;
    const siblings =
      place.length === 1
        ? stored
        : (fieldAtPlace(stored, place.slice(0, -1))?.fields ?? []);
    const index = place.at(-1)!;
    if (index + by < 0 || index + by >= siblings.length) return;
    const reordered = movedAt(stored, place, by);
    const refused = heldFor(reordered, demands);
    if (refused !== null) return;
    const sent = reordered.map((draft) => sentOf(draft, demands));
    if (
      controlled === undefined &&
      (host.waiting || host.refusalOf?.(sent) != null)
    )
      return;
    if (
      controlled !== undefined &&
      (controlled.move === undefined ||
        controlled.move.waiting ||
        controlled.move.reason !== undefined)
    )
      return;
    setMoveFailed(null);
    setMovedSaid(null);
    onLetGo();
    const focusedAt = document.activeElement;
    const answered = <A,>(saving: Promise<A>, signal: AbortSignal) => {
      return saving.then(
        (answer) => {
          if (
            !signal.aborted &&
            saveContext.current.scope === scope &&
            saveContext.current.stored === stored
          ) {
            if (document.activeElement === focusedAt) {
              pending.current = { key, at: by < 0 ? "up" : "down" };
              pendingStored.current = stored;
            }
            setApplied({ key: "", before: stored, drafts });
            setMovedSaid({
              severity: "info",
              words: say("declaration.moved", {
                label: isolatedInText(field.label || field.name),
                position: index + by + 1,
                count: siblings.length,
              }),
            });
          }
          return answer;
        },
        (failure: unknown) => {
          if (!signal.aborted) setMoveFailed(key);
          throw failure;
        },
      );
    };
    if (controlled === undefined)
      host.action.run((signal) => answered(host.save(sent, signal), signal));
    else
      controlled.move!.action.run((signal) =>
        answered(controlled.move!.save(reordered, signal, { key, by }), signal),
      );
  };
  const moveNow = useRef(move);
  useLayoutEffect(() => {
    moveNow.current = move;
  });
  const moveField = useCallback((key: string, by: -1 | 1) => {
    moveNow.current(key, by);
  }, []);
  const storedHeld = useMemo(() => heldFor(stored, demands), [stored, demands]);
  const storedRefusal =
    controlled === undefined && storedHeld === null
      ? host.refusalOf?.(stored.map((draft) => sentOf(draft, demands)))
      : undefined;
  const independentMove = controlled === undefined;
  const ownerMoveReason = controlled?.move?.reason;
  const moveReason = useMemo(
    () =>
      independentMove
        ? storedHeld !== null
          ? { severity: "warning" as const, words: heldWords(storedHeld.why) }
          : storedRefusal == null
            ? undefined
            : { severity: "warning" as const, words: storedRefusal }
        : ownerMoveReason,
    [independentMove, storedHeld, storedRefusal, ownerMoveReason],
  );
  const ownMoveReasonId = useId();
  const sharesReason =
    selected !== undefined &&
    reason !== undefined &&
    reason.words === moveReason?.words;
  const moveReasonId = sharesReason ? saveReasonId : ownMoveReasonId;
  const movesAvailable =
    controlled === undefined || controlled.move !== undefined;
  const movesWaiting =
    running ||
    (controlled === undefined
      ? host.waiting === true
      : controlled.move?.waiting === true);
  const moves = useMemo(
    () => ({
      available: movesAvailable,
      waiting: movesWaiting,
      reason: moveReason,
      reasonId: moveReason === undefined ? undefined : moveReasonId,
      failed: moveFailed,
      refusal,
      refusalAt,
      move: moveField,
    }),
    [
      movesAvailable,
      movesWaiting,
      moveReason,
      moveReasonId,
      moveFailed,
      refusal,
      refusalAt,
      moveField,
    ],
  );
  const table = (
    <FieldMoves.Provider value={moves}>
      <StatusLine said={movedSaid} />
      {moveReason === undefined || sharesReason ? null : (
        <Notice id={moveReasonId} severity={moveReason.severity}>
          {moveReason.visible ?? moveReason.words}
        </Notice>
      )}
      <EditingTable
        selected={selected}
        refusal={moveFailed === null ? refusal : null}
        labelledBy={labelledBy}
        narrow={narrow}
        acceptedWhen={acceptedWhen}
        drafts={drafts}
        demands={demands}
        shapeLocked={shapeLocked}
        held={held}
        savedById={savedById}
        storedByKey={storedByKey}
        running={running}
        scope={scope}
        heldPins={heldPins}
        edits={edits}
        picked={picked}
        editorHost={editorHost}
        removed={removed}
        stored={stored}
      />
    </FieldMoves.Provider>
  );
  return (
    <FieldSaveReason.Provider value={{ reason, id: saveReasonId }}>
      <SidePanelHost
        asksRoom
        width={FIELD_DRAWER_WIDTH}
        leastListWidth={560}
        open={picked !== null}
        reveal={reveal}
        insist={selected === undefined ? null : insist}
        keptAs="lilradish.panel.field"
        closeBeside
        onClose={() => setPicked(null)}
        title={drawerTitle}
        listLabel={title}
        listRole="group"
        list={table}
        headingLevel="h4"
        foot={
          selected === undefined ? undefined : (
            <FieldPanelFoot
              selected={selected}
              running={running}
              shapeLocked={shapeLocked}
              removed={removed}
              drafts={drafts}
              edits={edits}
              cancel={cancel}
              host={host}
              reason={reason}
              reasonDescribedBy={saveReasonId}
              save={save}
              done={done}
              navigationOnly={navigationOnly}
              setPicked={setPicked}
              previous={position <= 0 ? null : ordered[position - 1]!.key}
              next={
                position < 0 || after >= ordered.length
                  ? null
                  : ordered[after]!.key
              }
            />
          )
        }
      >
        <div
          ref={(slot) => {
            if (slot !== null && editorHost.parentElement !== slot)
              slot.append(editorHost);
          }}
        />
        {controlled === undefined && running ? (
          <Notice severity="info">{host.underway}</Notice>
        ) : null}
        {selected === undefined ||
        refusal === null ||
        moveFailed !== null ? null : (
          <div ref={refusalAt} tabIndex={-1}>
            {refusal}
          </div>
        )}
      </SidePanelHost>
    </FieldSaveReason.Provider>
  );
}

function useUnsavedReport<T>({
  labelledBy,
  drafts,
  savedAs,
  removed,
  running,
  host,
  section,
  storedByKey,
  setPicked,
  focusHeading,
  onUnsaved,
}: {
  readonly labelledBy: string;
  readonly drafts: readonly DraftField[];
  readonly savedAs: string;
  readonly removed: ReadonlySet<string>;
  readonly running: boolean;
  readonly host: Saving<T> | Controlled;
  readonly section: RefObject<HTMLElement | null>;
  readonly storedByKey: ReadonlyMap<string, DraftField>;
  readonly setPicked: (picked: string) => void;
  readonly focusHeading: () => void;
  readonly onUnsaved: (unsaved: boolean) => void;
}) {
  const controlled = host.controlled;
  const unchanged = JSON.stringify(drafts) === savedAs && removed.size === 0;
  useDraftPartStatus(
    labelledBy,
    !unchanged,
    running || (controlled === undefined && host.waiting === true),
    {
      saving: running,
      node: section,
      unit: controlled?.unit ?? labelledBy,
      locate: () => {
        controlled?.onLocate?.();
        section.current?.scrollIntoView?.({ block: "nearest" });
        const dirty = firstChanged(drafts, removed, storedByKey);
        if (dirty !== undefined) setPicked(dirty.key);
        else focusHeading();
      },
    },
  );
  useEffect(() => onUnsaved(!unchanged), [onUnsaved, unchanged]);
}

/** The first field, in the order they are drawn, taken out or no longer as stored. */
function firstChanged(
  drafts: readonly DraftField[],
  removed: ReadonlySet<string>,
  storedByKey: ReadonlyMap<string, DraftField>,
): DraftField | undefined {
  return allFields(drafts).find(
    (field) =>
      removed.has(field.key) ||
      JSON.stringify(field) !== JSON.stringify(storedByKey.get(field.key)),
  );
}

function useRefusalFocus(refusedBy: object | null) {
  const refusalAt = useRef<HTMLDivElement>(null);
  const insist = useMemo(
    () =>
      refusedBy === null ? null : { focus: () => refusalAt.current?.focus() },
    [refusedBy],
  );
  return { refusalAt, insist };
}

function pickedFieldFacts<T>({
  picked,
  removed,
  drafts,
  stored,
  demands,
  host,
  title,
  savedAs,
  storedByKey,
}: {
  readonly picked: string | null;
  readonly removed: ReadonlySet<string>;
  readonly drafts: readonly DraftField[];
  readonly stored: readonly DraftField[];
  readonly demands: Demands;
  readonly host: Saving<T> | Controlled;
  readonly title: string;
  readonly savedAs: string;
  readonly storedByKey: ReadonlyMap<string, DraftField>;
}) {
  const controlled = host.controlled;
  const candidates =
    picked !== null && removed.has(picked)
      ? withoutField(drafts, picked)
      : drafts;
  const proposed =
    picked === null ? stored : candidateFields(stored, candidates, picked);
  const refusing = heldFor(proposed, demands);
  const sent =
    refusing === null ? proposed.map((draft) => sentOf(draft, demands)) : null;
  const refused =
    sent === null || controlled !== undefined
      ? null
      : (host.refusalOf?.(sent) ?? null);
  const selected = picked === null ? undefined : fieldAtKey(drafts, picked);
  const ordered = allFields(stored);
  const position = ordered.findIndex((field) => field.key === picked);
  const drawerTitle = say("declaration.fieldPosition", {
    half: title,
    position: position < 0 ? ordered.length + 1 : position + 1,
    count: ordered.length + (position < 0 ? 1 : 0),
  });
  const navigationOnly =
    refusing === null &&
    refused === null &&
    JSON.stringify(proposed) === savedAs;
  const reason =
    refusing !== null
      ? { severity: "warning" as const, words: heldWords(refusing.why) }
      : refused !== null
        ? { severity: "warning" as const, words: refused }
        : undefined;
  const pickedStored = picked === null ? undefined : storedByKey.get(picked);
  const after =
    position +
    1 +
    (pickedStored !== undefined && removed.has(pickedStored.key)
      ? allFields(pickedStored.fields).length
      : 0);
  return {
    proposed,
    sent,
    selected,
    ordered,
    position,
    drawerTitle,
    navigationOnly,
    reason,
    after,
  };
}

function EditingTable({
  selected,
  refusal,
  labelledBy,
  narrow,
  acceptedWhen,
  drafts,
  demands,
  shapeLocked,
  held,
  savedById,
  storedByKey,
  running,
  scope,
  heldPins,
  edits,
  picked,
  editorHost,
  removed,
  stored,
}: {
  readonly selected: DraftField | undefined;
  readonly refusal: ReactNode;
  readonly labelledBy: string;
  readonly narrow: boolean;
  readonly acceptedWhen: boolean;
  readonly drafts: readonly DraftField[];
  readonly demands: Demands;
  readonly shapeLocked: boolean;
  readonly held: Held | null;
  readonly savedById: ReadonlyMap<string, DeclaredField>;
  readonly storedByKey: ReadonlyMap<string, DraftField>;
  readonly running: boolean;
  readonly scope: string;
  readonly heldPins: ReadonlyMap<string, PinnedList>;
  readonly edits: Edits;
  readonly picked: string | null;
  readonly editorHost: HTMLElement;
  readonly removed: ReadonlySet<string>;
  readonly stored: readonly DraftField[];
}) {
  const spanned = fieldColumns(narrow, acceptedWhen, true).length;
  return (
    <>
      {selected === undefined ? refusal : null}
      <Box component="table" aria-labelledby={labelledBy} sx={NATIVE_TABLE_SX}>
        <DeclarationColumns
          narrow={narrow}
          acceptedWhen={acceptedWhen}
          editable
        />
        {drafts.map((draft, index) => (
          <Row
            key={draft.key}
            draft={draft}
            standingField={draft}
            place={[index]}
            position={index}
            siblings={stored}
            demands={demands}
            shapeLocked={shapeLocked}
            held={held}
            savedById={savedById}
            storedByKey={storedByKey}
            running={running}
            scope={scope}
            pins={heldPins}
            narrow={narrow}
            acceptedWhen={acceptedWhen}
            edits={edits}
            picked={picked}
            editorHost={editorHost}
            removed={removed}
          />
        ))}
        <tbody>
          {stored.length === 0 ? (
            <tr>
              <Box component="td" colSpan={spanned} sx={NOTHING_DECLARED_SX}>
                {say("declaration.none")}
              </Box>
            </tr>
          ) : null}
          <tr>
            <td colSpan={spanned}>
              <Press
                tier="quiet"
                unavailable={running || shapeLocked}
                onPress={() => edits.addInside(null)}
              >
                {say("declaration.add")}
              </Press>
            </td>
          </tr>
        </tbody>
      </Box>
    </>
  );
}

interface SaveReason {
  readonly severity: "warning";
  readonly words: string;
}

function FieldPanelFoot<T>({
  selected,
  running,
  shapeLocked,
  removed,
  drafts,
  edits,
  cancel,
  host,
  reason,
  reasonDescribedBy,
  save,
  done,
  navigationOnly,
  setPicked,
  previous,
  next,
}: {
  readonly selected: DraftField;
  readonly running: boolean;
  readonly shapeLocked: boolean;
  readonly removed: ReadonlySet<string>;
  readonly drafts: readonly DraftField[];
  readonly edits: Edits;
  readonly cancel: () => void;
  readonly host: Saving<T> | Controlled;
  readonly reason?: SaveReason;
  readonly reasonDescribedBy: string;
  readonly save: (signal: AbortSignal, next: string | null) => Promise<T>;
  readonly done: (next: string | null) => void;
  readonly navigationOnly: boolean;
  readonly setPicked: (next: string) => void;
  readonly previous: string | null;
  readonly next: string | null;
}) {
  return (
    <Box
      sx={{
        display: "flex",
        flexWrap: "wrap",
        gap: 1,
        width: "100%",
        alignItems: "center",
      }}
    >
      <DeclarationSaveActions
        host={host}
        reason={reason}
        reasonDescribedBy={reasonDescribedBy}
        running={running}
        onSave={save}
        onDone={done}
        onCancel={cancel}
        navigationOnly={navigationOnly}
        onNavigate={setPicked}
        previous={previous}
        next={next}
      />
      <Box sx={{ marginInlineStart: "auto" }}>
        <Press
          tier="quiet"
          unavailable={running || shapeLocked || removed.has(selected.key)}
          onPress={() => {
            const place = fieldPlace(drafts, selected.key);
            if (place !== null) edits.remove(place);
          }}
        >
          {say("declaration.removeThis")}
        </Press>
      </Box>
    </Box>
  );
}

function DeclarationSaveActions<T>({
  host,
  reason,
  reasonDescribedBy,
  running,
  onSave,
  onDone,
  onCancel,
  navigationOnly,
  onNavigate,
  previous,
  next,
}: {
  readonly host: Saving<T> | Controlled;
  readonly reason?: SaveReason;
  readonly reasonDescribedBy: string;
  readonly running: boolean;
  readonly onSave: (signal: AbortSignal, next: string | null) => Promise<T>;
  readonly onDone: (next: string | null) => void;
  readonly onCancel: () => void;
  readonly navigationOnly: boolean;
  readonly onNavigate: (next: string) => void;
  readonly previous: string | null;
  readonly next: string | null;
}) {
  const actions = [
    ...(navigationOnly
      ? []
      : [
          {
            key: null,
            label: say(
              host.controlled === undefined
                ? "declaration.save"
                : "declaration.done",
            ),
          },
        ]),
    ...(previous === null
      ? []
      : [{ key: previous, label: say("declaration.previousField") }]),
    ...(next === null
      ? []
      : [{ key: next, label: say("declaration.nextField") }]),
  ];
  const buttons = actions.map(({ key, label }) =>
    key !== null && navigationOnly ? (
      <Press
        key={label}
        tier="primary"
        unavailable={
          running || (host.controlled === undefined && host.waiting === true)
        }
        onPress={() => onNavigate(key)}
      >
        {label}
      </Press>
    ) : host.controlled === undefined ? (
      <ActButton
        key={label}
        action={host.action}
        tier="primary"
        waiting={host.waiting}
        reason={reason}
        reasonDescribedBy={reasonDescribedBy}
        act={(signal) => onSave(signal, key)}
      >
        {label}
      </ActButton>
    ) : (
      <Press
        key={label}
        tier="primary"
        unavailable={running}
        reason={reason}
        reasonDescribedBy={reasonDescribedBy}
        onPress={() => onDone(key)}
      >
        {label}
      </Press>
    ),
  );
  const cancel = (
    <Press tier="neutral" unavailable={running} onPress={onCancel}>
      {say("entry.abandon")}
    </Press>
  );
  return navigationOnly ? (
    <>
      {buttons}
      {cancel}
    </>
  ) : (
    <>
      {buttons[0]}
      {cancel}
      {buttons.slice(1)}
    </>
  );
}

/** Edits that change the half are ignored while a save is out. */
interface Edits {
  readonly pick: (key: string | null) => void;
  readonly choose: (key: string, detail: VersionDetail | null) => void;
  readonly change: (
    place: Place,
    change: (draft: DraftField) => DraftField,
  ) => void;
  readonly remove: (place: Place) => void;
  readonly addInside: (place: Place | null) => void;
  readonly register: (key: string, row: HTMLElement | null) => void;
}

interface RowProps {
  readonly picked: string | null;
  readonly editorHost: HTMLElement;
  readonly removed: ReadonlySet<string>;
  readonly storedByKey: ReadonlyMap<string, DraftField>;
  readonly draft: DraftField;
  readonly standingField: Pick<DraftField, "name" | "label">;
  readonly place: Place;
  readonly position: number;
  readonly siblings: readonly DraftField[];
  readonly demands: Demands;
  readonly held: Held | null;
  readonly savedById: ReadonlyMap<string, DeclaredField>;
  readonly running: boolean;
  readonly shapeLocked: boolean;
  readonly scope: string;
  readonly pins: ReadonlyMap<string, PinnedList>;
  readonly narrow: boolean;
  readonly acceptedWhen: boolean;
  readonly edits: Edits;
}

function sameAt<K extends keyof RowProps>(key: K) {
  return (before: RowProps, after: RowProps) => before[key] === after[key];
}

// One entry per prop, neither more nor fewer, so a prop added to a row is
// compared or the build fails.
const ROW_ALIKE = Object.values({
  picked: (before, after) =>
    before.draft.kind === "fields"
      ? before.picked === after.picked
      : (before.picked === before.draft.key) ===
        (after.picked === after.draft.key),
  editorHost: sameAt("editorHost"),
  removed: sameAt("removed"),
  storedByKey: (before, after) =>
    before.storedByKey.get(before.draft.key) ===
    after.storedByKey.get(after.draft.key),
  shapeLocked: sameAt("shapeLocked"),
  draft: sameAt("draft"),
  standingField: (before, after) =>
    before.standingField.name === after.standingField.name &&
    before.standingField.label === after.standingField.label,
  position: sameAt("position"),
  siblings: sameAt("siblings"),
  demands: sameAt("demands"),
  held: (before, after) =>
    heldWithin(before.held, before.place) ===
    heldWithin(after.held, after.place),
  savedById: sameAt("savedById"),
  running: sameAt("running"),
  scope: sameAt("scope"),
  pins: sameAt("pins"),
  narrow: sameAt("narrow"),
  acceptedWhen: sameAt("acceptedWhen"),
  edits: sameAt("edits"),
  place: (before, after) =>
    before.place.length === after.place.length &&
    before.place.every((index, at) => index === after.place[at]),
} satisfies {
  readonly [K in keyof RowProps]-?: (
    before: RowProps,
    after: RowProps,
  ) => boolean;
});

const Row = memo(
  function Row({
    draft,
    standingField,
    place,
    position,
    siblings,
    demands,
    held,
    savedById,
    running,
    shapeLocked,
    scope,
    pins,
    narrow,
    acceptedWhen,
    edits,
    picked,
    editorHost,
    removed,
    storedByKey,
  }: RowProps) {
    const heldId = useId();
    const expanded = picked === draft.key;
    const storedField = storedByKey.get(draft.key);
    const savedAs = JSON.stringify(storedField ?? null);
    const fieldset = useRef<HTMLElement>(null);
    const body = useRef<HTMLTableSectionElement>(null);
    const summary = useRef<HTMLButtonElement>(null);
    useEffect(() => {
      edits.register(draft.key, expanded ? fieldset.current : body.current);
      return () => edits.register(draft.key, null);
    }, [draft.key, edits, expanded]);
    const set = (changed: Partial<DraftField>) => {
      if (
        shapeLocked &&
        Object.keys(changed).some(
          (key) => !["label", "help", "stands", "floor"].includes(key),
        )
      ) {
        return;
      }
      edits.change(place, (now) => ({ ...now, ...changed }));
    };
    const called = rowCalled(draft, position);
    const demand = demandAt(demands, place.length === 1);
    const heldHere =
      held !== null && held.place.join(".") === place.join(".") ? held : null;
    const pinned = draft.kind === "term" ? pins.get(draft.list) : undefined;
    const detailScope = `${scope}:${draft.key}:${draft.kind}:${draft.list}:${place.join(".")}`;
    const detailEpoch = useMemo(
      () => ({ owner: detailScope, savedById }),
      [detailScope, savedById],
    );
    const newer = useVersionDetail({
      kind: "reference_list",
      scope: detailEpoch,
      disabled: running || shapeLocked || draft.kind !== "term",
      onRead: (detail) => edits.choose(draft.key, detail),
    });
    const typed = { htmlInput: { dir: "auto", readOnly: running } };
    const numeric = {
      htmlInput: {
        inputMode: "numeric" as const,
        readOnly: running || shapeLocked,
      },
    };
    const chosen = { input: { readOnly: running || shapeLocked } };
    const ticked = { input: { "aria-readonly": running || shapeLocked } };
    const spoken = isolatedInText(called);
    const addInside = say("declaration.addInside", { name: spoken });
    const summaryDraft = storedField ?? draft;
    const shown = factsOf(
      summaryDraft,
      summaryDraft.kind === "term" ? pins.get(summaryDraft.list) : undefined,
    );
    const changed =
      storedField === undefined ||
      JSON.stringify(draft) !== savedAs ||
      removed.has(draft.key);
    return (
      <>
        <tbody
          ref={body}
          data-field-key={draft.key}
          hidden={storedField === undefined}
          aria-label={called}
          aria-describedby={heldHere === null ? undefined : heldId}
        >
          <TableRow
            selected={expanded}
            sx={narrow ? { "&&": NARROW_FIELD_ROW_SX } : undefined}
          >
            <Box
              component="td"
              sx={{
                pl: `${TABLE_GEOMETRY.inset + (place.length - 1) * (narrow ? 16 : 24)}px !important`,
                ...(narrow
                  ? { gridColumn: "1 / -1", borderBottom: 0, pb: 0 }
                  : {}),
              }}
            >
              <FieldCell
                field={shown}
                position={position}
                unsaved={changed && !expanded}
                held={heldHere !== null}
                summary={
                  narrow ? (
                    <FieldSummary
                      field={shown}
                      acceptedWhen={acceptedWhen}
                      standsAsked={demand === "stands"}
                      standingField={standingField}
                    />
                  ) : undefined
                }
              />
              {heldHere === null || expanded ? null : (
                <Notice id={heldId} severity="warning">
                  {say("declaration.held", { why: heldWords(heldHere.why) })}
                </Notice>
              )}
            </Box>
            {narrow ? null : (
              <PropertyCells
                field={shown}
                acceptedWhen={acceptedWhen}
                standsAsked={demand === "stands"}
                standingField={standingField}
              />
            )}
            <td>
              <IconButton
                type="button"
                ref={summary}
                aria-expanded={expanded}
                aria-controls={`${heldId}-editor`}
                aria-label={say(
                  expanded ? "declaration.close" : "declaration.open",
                  { name: isolatedInText(draft.label || called) },
                )}
                onClick={() => {
                  edits.pick(expanded ? null : draft.key);
                }}
              >
                <EditOutlinedIcon />
              </IconButton>
            </td>
            <FieldRowMoves
              storedField={storedField}
              siblings={siblings}
              shapeLocked={shapeLocked}
              called={called}
            />
          </TableRow>
        </tbody>
        {expanded
          ? createPortal(
              <FieldSaveReason.Consumer>
                {(saving) => {
                  const shownReason = saving?.reason;
                  const warningId =
                    shownReason === undefined ? heldId : saving!.id;
                  const hint = (words: string | undefined) =>
                    shownReason?.words === words ? undefined : words;
                  return (
                    <Box
                      id={`${heldId}-editor`}
                      component="fieldset"
                      ref={(row: HTMLElement | null) => {
                        fieldset.current = row;
                      }}
                      aria-describedby={
                        shownReason !== undefined
                          ? warningId
                          : heldHere === null
                            ? undefined
                            : heldId
                      }
                      sx={ROW_SX}
                    >
                      <legend tabIndex={-1} aria-label={called}>
                        {rowVisible(draft.name, position)}
                      </legend>
                      {shownReason === undefined && heldHere === null ? null : (
                        <Notice id={warningId} severity="warning">
                          {heldHere === null
                            ? shownReason!.words
                            : say("declaration.held", {
                                why: heldWords(heldHere.why),
                              })}
                        </Notice>
                      )}
                      <Box
                        sx={{
                          ...CONTROLS_SX,
                          gridTemplateColumns: "repeat(2, minmax(0, 1fr))",
                          mt:
                            shownReason === undefined && heldHere === null
                              ? 0
                              : 2,
                        }}
                      >
                        <LabelledTextField
                          helperBefore
                          describedBy={
                            shownReason === undefined ? undefined : warningId
                          }
                          fullWidth
                          label={say("declaration.label")}
                          value={draft.label}
                          onChange={(edit) => set({ label: edit.target.value })}
                          error={draft.label !== "" && !labelFits(draft.label)}
                          helperText={
                            draft.label !== "" && !labelFits(draft.label)
                              ? hint(say("declaration.labelLimit"))
                              : undefined
                          }
                          slotProps={typed}
                          size="small"
                        />
                        <>
                          <LabelledTextField
                            helperBefore
                            describedBy={
                              shownReason === undefined ? undefined : warningId
                            }
                            fullWidth
                            label={say("declaration.name")}
                            value={draft.name}
                            onChange={(edit) =>
                              set({ name: edit.target.value })
                            }
                            error={
                              draft.name !== "" && !fieldNameFits(draft.name)
                            }
                            helperText={
                              draft.name !== "" && !fieldNameFits(draft.name)
                                ? hint(say("refusal.FIELD_NAME_UNUSABLE"))
                                : undefined
                            }
                            slotProps={{
                              htmlInput: {
                                "data-part": "name",
                                readOnly: running || shapeLocked,
                              },
                            }}
                            size="small"
                          />
                          <LabelledTextField
                            helperBefore
                            describedBy={
                              shownReason === undefined ? undefined : warningId
                            }
                            fullWidth
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
                          </LabelledTextField>
                          {draft.kind === "text" ? (
                            <>
                              <LabelledTextField
                                helperBefore
                                describedBy={
                                  shownReason === undefined
                                    ? undefined
                                    : warningId
                                }
                                fullWidth
                                select
                                label={say("declaration.lines")}
                                value={draft.lines}
                                onChange={(edit) =>
                                  set({
                                    lines: edit.target
                                      .value as DraftField["lines"],
                                  })
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
                              </LabelledTextField>
                              <LabelledTextField
                                helperBefore
                                describedBy={
                                  shownReason === undefined
                                    ? undefined
                                    : warningId
                                }
                                fullWidth
                                label={say("declaration.longest")}
                                value={draft.longest}
                                onChange={(edit) =>
                                  set({ longest: edit.target.value })
                                }
                                error={!limitFits(draft.longest)}
                                helperText={
                                  limitFits(draft.longest)
                                    ? undefined
                                    : hint(heldWords("declaration.limit"))
                                }
                                slotProps={numeric}
                                size="small"
                              />
                            </>
                          ) : null}
                          {draft.kind === "file" ? (
                            <FileFieldControls
                              draft={draft}
                              set={set}
                              running={running || shapeLocked}
                            />
                          ) : null}
                          {draft.kind === "term" ? (
                            <Box
                              sx={{
                                "& .MuiInputLabel-root": {
                                  position: "static",
                                  transform: "none",
                                  maxWidth: "100%",
                                  typography: "controlLabel",
                                  color: "text.secondary",
                                  mb: 0.5,
                                },
                                "& .MuiOutlinedInput-notchedOutline > legend": {
                                  display: "none",
                                },
                              }}
                            >
                              <EditorVersionPicker
                                kind="reference_list"
                                label={say("declaration.list")}
                                scope={detailEpoch}
                                readOnly={running || shapeLocked}
                                value={draft.list}
                                selected={pinned}
                                onPick={(detail) =>
                                  edits.choose(draft.key, detail)
                                }
                                noneLabel={say("declaration.listNone")}
                              />
                            </Box>
                          ) : null}
                          <Box
                            sx={{
                              display: "flex",
                              flexDirection: "column",
                              gap: 0.5,
                            }}
                          >
                            <FormControlLabel
                              control={
                                <Checkbox
                                  checked={draft.many}
                                  readOnly={running || shapeLocked}
                                  slotProps={ticked}
                                  onChange={(_event, checked) =>
                                    set({ many: checked })
                                  }
                                />
                              }
                              label={say("declaration.many")}
                            />
                            {draft.many ? (
                              <LabelledTextField
                                helperBefore
                                describedBy={
                                  shownReason === undefined
                                    ? undefined
                                    : warningId
                                }
                                fullWidth
                                label={say("declaration.most")}
                                value={draft.most}
                                onChange={(edit) =>
                                  set({ most: edit.target.value })
                                }
                                error={!limitFits(draft.most)}
                                helperText={
                                  limitFits(draft.most)
                                    ? undefined
                                    : hint(heldWords("declaration.limit"))
                                }
                                slotProps={numeric}
                                size="small"
                              />
                            ) : null}
                            <FormControlLabel
                              control={
                                <Checkbox
                                  checked={draft.mustBeGiven}
                                  readOnly={running || shapeLocked}
                                  slotProps={ticked}
                                  onChange={(_event, checked) =>
                                    set({ mustBeGiven: checked })
                                  }
                                />
                              }
                              label={say("declaration.mustBeGiven")}
                            />
                          </Box>
                        </>
                        {demand === "stands" ? (
                          <LabelledTextField
                            helperBefore
                            describedBy={
                              shownReason === undefined ? undefined : warningId
                            }
                            fullWidth
                            select
                            label={say("declaration.acceptedWhen")}
                            value={draft.stands}
                            onChange={(edit) =>
                              set({
                                stands: edit.target.value as FieldStanding | "",
                              })
                            }
                            slotProps={{ input: { readOnly: running } }}
                            size="small"
                          >
                            <MenuItem value="">
                              {say("declaration.standsNone")}
                            </MenuItem>
                            {(
                              Object.keys(
                                FIELD_STANDING_WORDS,
                              ) as FieldStanding[]
                            ).map((standing) => (
                              <MenuItem key={standing} value={standing}>
                                {say(FIELD_STANDING_WORDS[standing])}
                              </MenuItem>
                            ))}
                          </LabelledTextField>
                        ) : null}
                        {demand === "stands" &&
                        draft.stands === "above_confidence" ? (
                          <LabelledTextField
                            helperBefore
                            describedBy={
                              shownReason === undefined ? undefined : warningId
                            }
                            fullWidth
                            label={say("declaration.floor")}
                            value={draft.floor}
                            onChange={(edit) =>
                              set({ floor: edit.target.value })
                            }
                            error={!floorFits(draft.floor)}
                            helperText={
                              floorFits(draft.floor)
                                ? undefined
                                : hint(say("declaration.floorLimit"))
                            }
                            slotProps={{
                              htmlInput: {
                                inputMode: "numeric",
                                readOnly: running,
                              },
                            }}
                            size="small"
                          />
                        ) : null}
                        <Box sx={{ gridColumn: "1 / -1" }}>
                          <LabelledTextField
                            helperBefore
                            describedBy={
                              shownReason === undefined ? undefined : warningId
                            }
                            label={say("declaration.help")}
                            value={draft.help}
                            onChange={(edit) =>
                              set({ help: edit.target.value })
                            }
                            error={draft.help !== "" && !lineFits(draft.help)}
                            helperText={
                              draft.help !== "" && !lineFits(draft.help)
                                ? hint(say("declaration.helpLimit"))
                                : undefined
                            }
                            slotProps={typed}
                            size="small"
                            fullWidth
                          />
                        </Box>
                      </Box>
                      {pinned === undefined ? null : (
                        <PinSaid pinned={pinned} />
                      )}
                      {pinned?.newer === undefined ? null : (
                        <Press
                          unavailable={running || shapeLocked || newer.running}
                          label={say("workflow.takeNewerFor", {
                            label: versionLabelText({
                              number: pinned.newer.number,
                              name: pinned.newer.versionName,
                            }),
                            name: spoken,
                          })}
                          onPress={() => newer.read(pinned.newer!.versionId)}
                        >
                          {visible("workflow.takeNewer", {
                            label: (
                              <VersionLabel
                                number={pinned.newer.number}
                                name={pinned.newer.versionName}
                              />
                            ),
                          })}
                        </Press>
                      )}
                      {newer.problem === null ? null : (
                        <ProblemView problem={newer.problem} />
                      )}
                      <Box sx={ROW_ACTS_SX}>
                        {draft.kind === "fields" ? (
                          <IconPress
                            icon={<AddIcon />}
                            label={addInside}
                            unavailable={running || shapeLocked}
                            onPress={() => edits.addInside(place)}
                          >
                            {say("declaration.addInsideTip")}
                          </IconPress>
                        ) : null}
                      </Box>
                    </Box>
                  );
                }}
              </FieldSaveReason.Consumer>,
              editorHost,
            )
          : null}
        {draft.kind === "fields" && draft.fields.length > 0 ? (
          <>
            {draft.fields.map((inside, index) => (
              <Row
                key={inside.key}
                draft={inside}
                standingField={standingField}
                place={[...place, index]}
                position={index}
                siblings={storedField?.fields ?? NO_FIELDS}
                demands={demands}
                held={held}
                savedById={savedById}
                running={running}
                shapeLocked={shapeLocked}
                scope={scope}
                pins={pins}
                narrow={narrow}
                acceptedWhen={acceptedWhen}
                edits={edits}
                picked={picked}
                editorHost={editorHost}
                removed={removed}
                storedByKey={storedByKey}
              />
            ))}
          </>
        ) : null}
      </>
    );
  },
  (before, after) => ROW_ALIKE.every((alike) => alike(before, after)),
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

const NO_FIELDS: readonly DraftField[] = [];

function FieldRowMoves({
  storedField,
  siblings,
  shapeLocked,
  called,
}: {
  readonly storedField: DraftField | undefined;
  readonly siblings: readonly DraftField[];
  readonly shapeLocked: boolean;
  readonly called: string;
}) {
  const moving = useContext(FieldMoves);
  const { refusalAt, refusal } = moving ?? {};
  const position = siblings.findIndex(
    (field) => field.key === storedField?.key,
  );
  return (
    <td data-compact="true">
      <Box
        sx={{
          display: "flex",
          justifyContent: "flex-end",
          alignItems: "center",
          gap: TOKENS.table.moveGap,
          whiteSpace: "nowrap",
        }}
      >
        {([-1, 1] as const).map((by) => (
          <Box
            key={by}
            component="span"
            data-part={by < 0 ? "up" : "down"}
            sx={MOVE_SLOT_SX}
          >
            {storedField === undefined ||
            shapeLocked ||
            moving?.available !== true ||
            (by < 0
              ? position <= 0
              : position === siblings.length - 1) ? null : (
              <Tooltip
                title={say(
                  by < 0 ? "declaration.upTip" : "declaration.downTip",
                )}
                describeChild
              >
                <IconButton
                  size="small"
                  sx={MOVE_SX}
                  aria-label={say(
                    by < 0 ? "declaration.up" : "declaration.down",
                    {
                      name: isolatedInText(
                        storedField?.label || storedField?.name || called,
                      ),
                    },
                  )}
                  aria-describedby={moving.reasonId}
                  {...heldOr(
                    moving.waiting || moving.reason !== undefined,
                    () => moving.move(storedField!.key, by),
                  )}
                >
                  {by < 0 ? <ArrowUpwardIcon /> : <ArrowDownwardIcon />}
                </IconButton>
              </Tooltip>
            )}
          </Box>
        ))}
      </Box>
      {storedField !== undefined &&
      moving?.failed === storedField.key &&
      refusal !== null ? (
        <div ref={refusalAt} tabIndex={-1}>
          {refusal}
        </div>
      ) : null}
    </td>
  );
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
  const hintId = useId();
  const saving = useContext(FieldSaveReason);
  const typesTooMany = fileTypesTooMany(draft.fileTypes);
  const typesValid = !typesTooMany && fileTypesFit(draft.fileTypes);
  const sizeValid = fileSizeFits(draft.fileSize, draft.fileSizeUnit);
  const typesError = typesTooMany
    ? say("refusal.FILE_TYPES_TOO_MANY")
    : typesValid
      ? undefined
      : say("declaration.fileTypesInvalid");
  const sizeError = sizeValid
    ? undefined
    : say("refusal.FILE_SIZE_LIMIT_UNUSABLE");
  const typesHeld =
    typesError !== undefined && saving?.reason?.words === typesError;
  const sizeHeld =
    sizeError !== undefined && saving?.reason?.words === sizeError;
  return (
    <>
      <Box sx={{ display: "flex", flexDirection: "column", gap: 1 }}>
        <LabelledTextField
          helperBefore
          describedBy={typesHeld ? saving!.id : undefined}
          fullWidth
          id={`${hintId}-types-input`}
          label={say("declaration.fileTypes")}
          value={draft.fileTypes}
          onChange={(edit) => set({ fileTypes: edit.target.value })}
          multiline
          minRows={2}
          error={!typesValid}
          helperText={
            <>
              {say("declaration.fileTypesHint")}
              {typesError === undefined || typesHeld ? null : (
                <> {typesError}</>
              )}
            </>
          }
          slotProps={{
            htmlInput: {
              readOnly: running,
            },
          }}
          size="small"
        />
      </Box>
      <Box sx={{ display: "flex", flexDirection: "column", gap: 1 }}>
        <Box sx={{ display: "flex", gap: 1 }}>
          <LabelledTextField
            helperBefore
            describedBy={sizeHeld ? saving!.id : undefined}
            fullWidth
            id={`${hintId}-size-input`}
            label={say("declaration.maximumSize")}
            value={draft.fileSize}
            onChange={(edit) => set({ fileSize: edit.target.value })}
            error={!sizeValid}
            helperText={
              <>
                {say("declaration.sizeUnitsHint")}
                {sizeError === undefined || sizeHeld ? null : <> {sizeError}</>}
              </>
            }
            slotProps={{
              htmlInput: {
                inputMode: "numeric",
                readOnly: running,
              },
            }}
            size="small"
          />
          <LabelledTextField
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
          </LabelledTextField>
        </Box>
      </Box>
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
        ? visible("declaration.listNewer", {
            label: (
              <VersionLabel
                number={pinned.newer!.number}
                name={pinned.newer!.versionName}
              />
            ),
          })
        : pinned.newer === undefined
          ? say("declaration.listRetired")
          : visible("declaration.listRetiredNewest", {
              label: (
                <VersionLabel
                  number={pinned.newer.number}
                  name={pinned.newer.versionName}
                />
              ),
            })}
    </Typography>
  );
}

function Reading({
  fields,
  demands,
  narrow,
  acceptedWhen,
  labelledBy,
}: {
  readonly fields: readonly DeclaredField[];
  readonly demands: Demands;
  readonly narrow: boolean;
  readonly acceptedWhen: boolean;
  readonly labelledBy?: string;
}) {
  if (fields.length === 0) {
    return (
      <Typography variant="body2" sx={NOTHING_READ_SX}>
        {say("declaration.none")}
      </Typography>
    );
  }
  return (
    <Box component="table" aria-labelledby={labelledBy} sx={NATIVE_TABLE_SX}>
      <DeclarationColumns
        narrow={narrow}
        acceptedWhen={acceptedWhen}
        editable={false}
      />
      <tbody>
        <ReadRows
          fields={fields}
          demands={demands}
          narrow={narrow}
          acceptedWhen={acceptedWhen}
          depth={0}
        />
      </tbody>
    </Box>
  );
}

function ReadRows({
  fields,
  demands,
  narrow,
  acceptedWhen,
  depth,
  standingField,
}: {
  readonly fields: readonly DeclaredField[];
  readonly demands: Demands;
  readonly narrow: boolean;
  readonly acceptedWhen: boolean;
  readonly depth: number;
  readonly standingField?: Pick<DeclaredField, "name" | "label">;
}) {
  return fields.map((field, position) => (
    <Fragment key={field.fieldId}>
      <tr>
        <Box
          component="td"
          sx={{
            pl: `${TABLE_GEOMETRY.inset + depth * (narrow ? 16 : 24)}px !important`,
          }}
        >
          <FieldCell
            field={field}
            position={position}
            summary={
              narrow ? (
                <FieldSummary
                  field={field}
                  acceptedWhen={acceptedWhen}
                  standsAsked={demandAt(demands, depth === 0) === "stands"}
                  standingField={standingField ?? field}
                />
              ) : undefined
            }
          />
        </Box>
        {narrow ? null : (
          <PropertyCells
            field={field}
            acceptedWhen={acceptedWhen}
            standsAsked={demandAt(demands, depth === 0) === "stands"}
            standingField={standingField ?? field}
          />
        )}
      </tr>
      {field.fields === undefined ? null : (
        <ReadRows
          fields={field.fields}
          demands={demands}
          narrow={narrow}
          acceptedWhen={acceptedWhen}
          depth={depth + 1}
          standingField={standingField ?? field}
        />
      )}
    </Fragment>
  ));
}

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

type ShownField = Omit<
  DeclaredField,
  "longest" | "most" | "floor" | "maximumBytes"
> & {
  readonly longest?: number | string;
  readonly most?: number | string;
  readonly floor?: number | string;
  readonly maximumBytes?: number | string;
};

function factsOf(
  draft: DraftField,
  pinned: PinnedList | undefined,
): ShownField {
  const numberOrWords = (
    value: string,
    fits: boolean,
  ): number | string | undefined =>
    value === "" ? undefined : fits ? Number(value) : value;
  return {
    fieldId: draft.fieldId ?? draft.key,
    name: draft.name,
    label: draft.label || undefined,
    help: draft.help || undefined,
    kind: draft.kind,
    lines: draft.lines,
    longest: numberOrWords(draft.longest, limitFits(draft.longest)),
    many: draft.many,
    most: numberOrWords(draft.most, limitFits(draft.most)),
    mustBeGiven: draft.mustBeGiven,
    stands: draft.stands || undefined,
    floor: numberOrWords(draft.floor, floorFits(draft.floor)),
    list: pinned,
    types: draft.fileTypes.split(/\r?\n/).filter((type) => type !== ""),
    maximumBytes: fileSizeFits(draft.fileSize, draft.fileSizeUnit)
      ? Number(draft.fileSize) * FILE_SIZE_UNITS[draft.fileSizeUnit]
      : draft.fileSize === ""
        ? undefined
        : `${draft.fileSize} ${draft.fileSizeUnit}`,
  };
}

function DeclarationColumns({
  narrow,
  acceptedWhen,
  editable,
}: {
  readonly narrow: boolean;
  readonly acceptedWhen: boolean;
  readonly editable: boolean;
}) {
  if (narrow) return null;
  return (
    <thead>
      <tr>
        {fieldColumns(narrow, acceptedWhen, editable).map((key) => (
          <th
            key={key}
            scope="col"
            data-compact={
              key === "declaration.howMany" ||
              key === "declaration.required" ||
              key === "declaration.openColumn" ||
              key === "declaration.movesColumn"
                ? "true"
                : undefined
            }
          >
            {key === "declaration.movesColumn" ? null : key ===
              "declaration.openColumn" ? (
              <Box component="span" sx={HIDDEN_SX}>
                {say(key)}
              </Box>
            ) : (
              say(key)
            )}
          </th>
        ))}
      </tr>
    </thead>
  );
}

/** Where it is narrow, the field's cell holds what the others would. */
function fieldColumns(
  narrow: boolean,
  acceptedWhen: boolean,
  editable: boolean,
): MessageId[] {
  const columns: MessageId[] = ["declaration.field"];
  if (!narrow)
    columns.push(
      "declaration.kind",
      "declaration.howMany",
      "declaration.required",
    );
  if (!narrow && acceptedWhen) columns.push("declaration.acceptedWhen");
  if (editable)
    columns.push("declaration.openColumn", "declaration.movesColumn");
  return columns;
}

function FieldCell({
  field,
  position,
  unsaved = false,
  held = false,
  summary,
}: {
  readonly field: ShownField;
  readonly position: number;
  readonly unsaved?: boolean;
  readonly held?: boolean;
  readonly summary?: ReactNode;
}) {
  return (
    <>
      <Box
        sx={{
          display: "flex",
          flexWrap: "wrap",
          alignItems: "center",
          gap: 0.75,
          typography: "fieldName",
        }}
      >
        {field.name === "" ? (
          rowVisible(field.name, position)
        ) : (
          <FieldName context="author" name={field.name} label={field.label} />
        )}
        {unsaved ? <UnsavedMark compact /> : null}
        {held ? (
          <ErrorOutlineIcon aria-hidden sx={{ color: "error.main" }} />
        ) : null}
        {summary}
      </Box>
      {field.help === undefined ? null : (
        <Typography
          component="div"
          sx={{ typography: "supportingProse", color: "text.secondary" }}
        >
          <bdi>{field.help}</bdi>
        </Typography>
      )}
    </>
  );
}

function whatItIs(field: ShownField, inline = false): string | ReactElement {
  const kind = say(FIELD_KIND_WORDS[field.kind]);
  if (!inline) {
    return (
      <>
        <Box component="span" sx={{ typography: "fieldFactKind" }}>
          {kind}
        </Box>
        {field.kind === "text" ? (
          <>
            <FactTag>
              {say(
                field.lines === "one"
                  ? "declaration.oneLine"
                  : "declaration.severalLines",
              )}
            </FactTag>
            {field.longest === undefined ? (
              <FactTag>{say("declaration.longestUnsaid")}</FactTag>
            ) : (
              <FactTag>
                {typeof field.longest === "number"
                  ? say("declaration.longestSaid", { longest: field.longest })
                  : say("declaration.longestWritten", {
                      longest: field.longest,
                    })}
              </FactTag>
            )}
          </>
        ) : field.kind === "file" ? (
          <>
            <FactTag>{joinedInSentence(field.types ?? [])}</FactTag>
            <FactTag>
              {field.maximumBytes === undefined
                ? say("declaration.fileSizeUnsaid")
                : say(
                    field.many
                      ? "declaration.fileUpToEach"
                      : "declaration.fileUpTo",
                    {
                      size:
                        typeof field.maximumBytes === "number"
                          ? sizeSaid(field.maximumBytes)
                          : field.maximumBytes,
                    },
                  )}
            </FactTag>
          </>
        ) : field.kind === "term" ? (
          <FactTag>
            {field.list === undefined ? (
              say("declaration.fromUnsaid")
            ) : (
              <VersionLabel
                entry={field.list.name}
                number={field.list.number}
                name={field.list.versionName}
              />
            )}
          </FactTag>
        ) : null}
      </>
    );
  }
  const longest =
    field.longest === undefined
      ? say("declaration.longestUnsaid")
      : typeof field.longest === "number"
        ? say("declaration.longestInline", { longest: field.longest })
        : say("declaration.longestWrittenInline", { longest: field.longest });
  if (field.kind === "text")
    return (
      <>
        {visible("declaration.whatItIsInline", {
          kind,
          lines: say(
            field.lines === "one"
              ? "declaration.oneLineInline"
              : "declaration.severalLinesInline",
          ),
          longest,
        })}
      </>
    );
  if (field.kind === "file")
    return (
      <>
        {kind}
        {" · "}
        {joinedInSentence(field.types ?? [])}
        {" · "}
        {field.maximumBytes === undefined
          ? say("declaration.fileSizeUnsaid")
          : typeof field.maximumBytes === "number"
            ? sizeSaid(field.maximumBytes)
            : field.maximumBytes}
      </>
    );
  if (field.kind === "term")
    return (
      <>
        {kind}
        {" · "}
        {field.list === undefined
          ? say("declaration.fromUnsaid")
          : visible("declaration.fromSaid", {
              label: (
                <VersionLabel
                  entry={field.list.name}
                  number={field.list.number}
                  name={field.list.versionName}
                />
              ),
            })}
      </>
    );
  return kind;
}

function howMany(field: ShownField): string {
  return !field.many
    ? say("declaration.readingOne")
    : field.most === undefined
      ? say("declaration.readingMany")
      : typeof field.most === "number"
        ? say("declaration.readingManyUpTo", { most: field.most })
        : say("declaration.readingManyWritten", { most: field.most });
}

function accepted(
  field: ShownField,
  standsAsked: boolean,
  standingField: Pick<ShownField, "name" | "label">,
): string | ReactElement {
  if (!standsAsked)
    return (
      <>
        {visible("declaration.inherited", {
          field: (
            <FieldName
              context="author"
              name={standingField.name}
              label={standingField.label}
            />
          ),
        })}
      </>
    );
  if (field.stands === undefined) return say("declaration.standsUnsaid");
  if (field.stands !== "above_confidence")
    return say(FIELD_STANDING_WORDS[field.stands]);
  return field.floor === undefined
    ? say("declaration.standsAboveUnsaid")
    : typeof field.floor === "number"
      ? say("declaration.standsAbove", { floor: field.floor })
      : say("declaration.floorNotConfidence", {
          floor: isolatedInText(field.floor),
        });
}

function PropertyCells({
  field,
  acceptedWhen,
  standsAsked,
  standingField,
}: {
  readonly field: ShownField;
  readonly acceptedWhen: boolean;
  readonly standsAsked: boolean;
  readonly standingField: Pick<ShownField, "name" | "label">;
}) {
  return (
    <>
      <td>{whatItIs(field)}</td>
      <td data-compact="true">{howMany(field)}</td>
      <td data-compact="true">
        {say(field.mustBeGiven ? "workflow.yes" : "workflow.no")}
      </td>
      {acceptedWhen ? (
        <td>{accepted(field, standsAsked, standingField)}</td>
      ) : null}
    </>
  );
}

function FieldSummary({
  field,
  acceptedWhen,
  standsAsked,
  standingField,
}: {
  readonly field: ShownField;
  readonly acceptedWhen: boolean;
  readonly standsAsked: boolean;
  readonly standingField: Pick<ShownField, "name" | "label">;
}) {
  return (
    <Box component="span" sx={{ color: "text.secondary", typography: "body1" }}>
      {visible("declaration.summaryLine", {
        name: "",
        whatItIs: whatItIs(field, true),
        howMany: howMany(field),
        required: say(
          field.mustBeGiven
            ? "declaration.requiredSaid"
            : "declaration.optional",
        ),
      })}
      {acceptedWhen
        ? visible("declaration.acceptedSummary", {
            when: accepted(field, standsAsked, standingField),
          })
        : null}
    </Box>
  );
}

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
