import Box from "@mui/material/Box";
import Link from "@mui/material/Link";
import Typography from "@mui/material/Typography";
import type { SxProps, Theme } from "@mui/material/styles";
import {
  Children,
  useCallback,
  useId,
  useLayoutEffect,
  useRef,
  useState,
  type ReactNode,
  type RefObject,
} from "react";
import {
  Link as RouterLink,
  useLocation,
  useNavigate,
  useParams,
  useSearchParams,
} from "react-router";

import type {
  Entry,
  EntryAct,
  Version,
  VersionAct,
} from "../../api/groups/{groupId}/{kind}";
import {
  readEntry,
  refusedForWhatMoved,
  editEntry,
} from "../../api/groups/{groupId}/{kind}/{entryId}";
import {
  letEntryGo,
  stopEntry,
} from "../../api/groups/{groupId}/{kind}/{entryId}/stop";
import {
  contentProblemsIn,
  contentProblemTotalIn,
  retiredPinsIn,
  makeDraft,
  type NumberedVersion,
  type RetiredPin,
} from "../../api/groups/{groupId}/{kind}/{entryId}/versions";
import { approve } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}/approval";
import { retire } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}/retirement";
import { nameVersion } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}/name";
import {
  submit,
  withdraw,
} from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}/submission";
import type { Problem } from "../../api/problem";
import type { GroupStanding, Standing } from "../../api/standing";
import { Async } from "../../app/Async";
import { ProblemView } from "../../app/ProblemView";
import { useGroup } from "../../app/standing/RequireGroup";
import { useStanding } from "../../app/standing/StandingContext";
import { useActedOn } from "../../app/useActedOn";
import { say, type MessageId } from "../../i18n/app";
import { VersionLabel, versionLabelText } from "../../lib/text/VersionLabel";
import { visible } from "../../i18n/visible";
import { ActButton } from "../../lib/action/ActButton";
import { InlineEditableValue } from "../../lib/action/InlineEditableValue";
import { ActDialog } from "../../lib/action/ActDialog";
import { Press, type ActionTier } from "../../lib/action/Press";
import { isolatedInText } from "../../lib/direction/isolated";
import { Field, Fields } from "../../lib/form/Fields";
import { PageHeading } from "../../lib/heading/PageHeading";
import { Part } from "../../lib/layout/Part";
import { PANEL_PART_SX, SidePanelHost } from "../../lib/layout/SidePanelHost";
import { DEPTH_SX, HEADER_ACTS_SX, QUIET_SX } from "../../lib/layout/parts";
import type { Action } from "../../lib/request/useAction";
import { useResource } from "../../lib/request/useResource";
import { useCalendarNow, whenText } from "../../lib/time/When";
import { nameOrNumber } from "../../lib/text/names";
import { joinedInCell } from "../people/wordLists";
import {
  ENTRY_ACT_RULES,
  VERSION_ACT_RULES,
  entryOffers,
  versionOffers,
} from "./actRules";
import { ContentProblems, type ReadContent } from "./ContentProblems";
import { DescribeEntry } from "./DescribeEntry";
import { entryHref, justMade, OPEN_VERSION } from "./entryAddress";
import { kindSaid, pageOf, type LibraryKind } from "./libraryKinds";
import { VersionContent } from "./VersionContent";
import { Versions, VersionStanding } from "./Versions";
import { VersionName } from "./VersionName";
import { WorkflowSubmissionRefusal } from "./WorkflowContent";
import { DraftStatusContext, useDraftStatus } from "./useDraftStatus";

// Its markers taken away, so it is named a list outright.
const LIST_SX = { listStyleType: "none", m: 0, mb: 2, p: 0 };

const PIN_LINE_SX = { display: "flex", alignItems: "baseline", gap: "6px" };

const ENTRY_ACTS = {
  start_draft: "versions.newDraft",
  rename: "entry.edit",
  stop: "entry.stop",
  let_go: "entry.letGo",
} as const satisfies Record<EntryAct, MessageId>;

type VersionChange = typeof submit;

/** Each act on a version drawn beside its title, in order. Writing is the version's editor's, and not drawn here. */
const VERSION_ACTS = {
  write: null,
  submit: {
    words: "version.submit",
    change: submit,
    tier: "primary",
  },
  withdraw: {
    words: "version.withdraw",
    change: withdraw,
    tier: "secondary",
  },
  approve: {
    words: "version.approve",
    change: approve,
    tier: "primary",
  },
  retire: {
    words: "version.retire",
    change: retire,
    tier: "danger",
  },
} as const satisfies Record<
  VersionAct,
  {
    readonly words: MessageId;
    readonly change: VersionChange;
    readonly tier: ActionTier;
  } | null
>;

const DRAWN_VERSION_ACTS = (
  Object.entries(VERSION_ACTS) as [
    VersionAct,
    (typeof VERSION_ACTS)[VersionAct],
  ][]
).flatMap(([act, drawn]) => (drawn === null ? [] : [{ act, ...drawn }]));

/** Which control asked the change out, which is where its refusal is said. */
type Asked =
  | { readonly place: "entry"; readonly act: EntryAct }
  | {
      readonly place: "version";
      readonly act: VersionAct;
      readonly versionId: string;
    };

type Retiring = {
  readonly versionId: string;
  readonly entryId: string;
  readonly selection: string | null;
  readonly seenProblem: Problem | null;
};

/**
 * Entry and version acts come from the server, never inferred here; refusals
 * trigger a reread so outdated controls can disappear.
 */
export function EntryPage({ of }: { readonly of: LibraryKind }) {
  const group = useGroup();
  const { entryId = "" } = useParams();
  return (
    <EntryForIdentity
      key={`${group.groupId}:${of.kind}:${entryId}`}
      group={group}
      of={of}
    />
  );
}

function EntryForIdentity({
  group,
  of,
}: {
  readonly group: GroupStanding;
  readonly of: LibraryKind;
}) {
  const now = useCalendarNow();
  const standing = useStanding();
  const { groupId } = group;
  const { entryId = "" } = useParams();
  const [parameters] = useSearchParams();
  const { state } = useLocation();
  const navigate = useNavigate();
  const versionsHeading = useRef<HTMLHeadingElement>(null);
  const versionHeading = useRef<HTMLHeadingElement>(null);

  const load = useCallback(
    (signal: AbortSignal): Promise<Entry | null> =>
      readEntry(groupId, of.kind, entryId, signal),
    [groupId, of.kind, entryId],
  );
  const read = useResource<Entry | null>(load, null);
  const [asked, setAsked] = useState<Asked | null>(null);
  const [editing, setEditing] = useState<number | null>(null);
  const [editOpen, setEditOpen] = useState(false);
  const [editingField, setEditingField] = useState<"name" | "purpose">("name");
  const readControls = useRef(new Map<string, HTMLButtonElement>());
  const returnFocus = useRef<string | null>(null);
  const [retiring, setRetiring] = useState<Retiring | null>(null);
  const [readCount, setReadCount] = useState(0);
  const [content, setContent] = useState<ReadContent | null>(null);
  const { shown, showingAnswerWhileReading, changing, refused } = useActedOn(
    read,
    refusedForWhatMoved,
    {
      answered: () => {
        if (asked?.place === "version" && asked.act === "retire") {
          setRetiring(null);
        }
        if (asked?.place === "entry" && asked.act === "start_draft") {
          // Replaced: the entry before its draft is not somewhere to go back to.
          navigate(entryHref(group, of, entryId), { replace: true });
        }
      },
      readAgain: () => setReadCount((count) => count + 1),
    },
  );

  const selectedVersion = parameters.get(OPEN_VERSION);
  useLayoutEffect(() => {
    if (!editOpen && !changing.running && returnFocus.current !== null) {
      if (document.activeElement === document.body)
        readControls.current.get(returnFocus.current)?.focus();
      returnFocus.current = null;
    }
  }, [editOpen, changing.running]);
  const selected =
    shown?.versions.find((version) => version.versionId === selectedVersion) ??
    shown?.versions[0];
  const {
    report,
    blocked: draftBlocked,
    unsavedCount,
    saving,
    locateFirst,
  } = useDraftStatus(
    selected?.versionId ?? null,
    of.kind === "workflow" || of.kind === "question" ? 6 : 3,
  );

  // Re-run for every entry shown, which is when a pressed control can have gone with the act it offered.
  useLayoutEffect(() => {
    if (document.activeElement !== document.body || asked === null) {
      return;
    }
    const heading =
      asked.place === "version"
        ? versionHeading.current
        : versionsHeading.current;
    heading?.focus();
  }, [shown, asked]);

  // What a refusal named and who wrote the version are both as they were before the write.
  const written = () => {
    setAsked(null);
    read.reload();
  };

  const refusedAt = (acts: readonly EntryAct[]): Problem | null =>
    asked?.place === "entry" && acts.includes(asked.act)
      ? changing.problem
      : null;

  return (
    <Async
      read={{
        ...read,
        value: shown,
        loading: read.loading && !showingAnswerWhileReading,
      }}
      empty={() => null}
    >
      {(entry) => {
        if (entry === null) {
          return null;
        }
        const open =
          entry.versions.find(
            (version) => version.versionId === selectedVersion,
          ) ?? entry.versions[0];
        const switchedBy = entryOffers(entry, "stop", standing, groupId)
          ? "stop"
          : "let_go";
        const switchRefused = refusedAt(["stop", "let_go"]);
        const draftRefused = refusedAt(["start_draft"]);
        const versionRefused =
          asked?.place === "version" && asked.versionId === open?.versionId
            ? changing.problem
            : null;
        const versionActs = DRAWN_VERSION_ACTS.filter(
          ({ act }) =>
            open !== undefined && versionOffers(open, act, standing, groupId),
        );
        const editor =
          editing === null ? null : (
            <DescribeEntry
              key={editing}
              open={editOpen}
              editing={editingField}
              title={say("entry.editTitle")}
              actLabel={say("entry.save")}
              underway="entry.editUnderway"
              rule={ENTRY_ACT_RULES.rename}
              described={entry}
              send={(described, signal) =>
                editEntry(groupId, of.kind, entry.entryId, described, signal)
              }
              action={changing}
              onShut={(restoring = false) => {
                returnFocus.current = restoring ? editingField : null;
                setEditOpen(false);
              }}
            />
          );
        const edit = (field: "name" | "purpose") => {
          setAsked({ place: "entry", act: "rename" });
          setEditing((last) => (last ?? 0) + 1);
          setEditingField(field);
          setEditOpen(true);
        };
        const editable = (field: "name" | "purpose", value: string) =>
          entryOffers(entry, "rename", standing, groupId) ? (
            <InlineEditableValue
              ref={(control) => {
                if (control === null) readControls.current.delete(field);
                else readControls.current.set(field, control);
              }}
              value={value}
              characters={field === "name" ? 128 : 512}
              disabled={changing.running}
              onEdit={() => edit(field)}
            />
          ) : (
            <bdi>{value}</bdi>
          );
        const mayNameVersion = standing.groups.some(
          (group) =>
            group.groupId === groupId &&
            group.permissions.has(ENTRY_ACT_RULES.rename),
        );
        const versionsIn = (sx: SxProps<Theme>) => (
          <Versions
            sx={sx}
            key={`${of.kind}:${entry.entryId}`}
            versions={entry.versions}
            open={open}
            hrefOf={(version) =>
              entryHref(group, of, entry.entryId, version.versionId)
            }
            headingRef={versionsHeading}
            actions={
              entryOffers(entry, "start_draft", standing, groupId) ? (
                <ActButton
                  action={changing}
                  act={(signal) => {
                    setAsked({ place: "entry", act: "start_draft" });
                    return makeDraft(groupId, of.kind, entry.entryId, signal);
                  }}
                >
                  {say(ENTRY_ACTS.start_draft)}
                </ActButton>
              ) : null
            }
          >
            {draftRefused === null ? null : (
              <ProblemView
                problem={draftRefused}
                rule={{ inGroup: ENTRY_ACT_RULES.start_draft }}
              />
            )}
          </Versions>
        );
        return (
          <>
            <EntryHeading
              of={of}
              groupId={groupId}
              standing={standing}
              entry={entry}
              now={now}
              state={state}
              editable={editable}
              editor={editor}
              editingField={editingField}
              editOpen={editOpen}
              switchedBy={switchedBy}
              switchRefused={switchRefused}
              changing={changing}
              setAsked={setAsked}
            />
            {open === undefined ? versionsIn(DEPTH_SX[1]) : null}
            {open === undefined ? null : (
              <>
                <WorkflowSubmissionRefusal.Provider
                  value={of.kind === "workflow" ? versionRefused : null}
                >
                  <DraftStatusContext.Provider value={report}>
                    <VersionContent
                      kind={of.kind}
                      groupId={groupId}
                      entryId={entry.entryId}
                      version={open}
                      readCount={readCount}
                      onRefused={refused}
                      onWritten={written}
                      onShown={setContent}
                      wrapDocument={(document) => (
                        <SidePanelHost
                          open
                          title={say("version.details")}
                          listLabel={say("version.number", {
                            number: open.number,
                          })}
                          listRole="group"
                          keptAs="lilradish.panel.version"
                          leastListWidth={640}
                          list={
                            <OpenVersionPart
                              group={group}
                              of={of}
                              groupId={groupId}
                              entry={entry}
                              open={open}
                              mayNameVersion={mayNameVersion}
                              versionHeading={versionHeading}
                              actions={
                                versionActs.length === 0 ? undefined : (
                                  <OpenVersionActs
                                    of={of}
                                    groupId={groupId}
                                    entry={entry}
                                    entryId={entryId}
                                    open={open}
                                    selectedVersion={selectedVersion}
                                    versionActs={versionActs}
                                    draftBlocked={draftBlocked}
                                    changing={changing}
                                    setAsked={setAsked}
                                    setRetiring={setRetiring}
                                  />
                                )
                              }
                              asked={asked}
                              versionRefused={versionRefused}
                              content={content}
                              unsavedCount={unsavedCount}
                              saving={saving}
                              locateFirst={locateFirst}
                              changing={changing}
                              setAsked={setAsked}
                            >
                              {document}
                            </OpenVersionPart>
                          }
                        >
                          <VersionFacts version={open} />
                          {versionsIn(PANEL_PART_SX)}
                          <Box sx={PANEL_PART_SX}>
                            <PinnedBy group={group} version={open} />
                          </Box>
                        </SidePanelHost>
                      )}
                    />
                  </DraftStatusContext.Provider>
                </WorkflowSubmissionRefusal.Provider>
                <RetireConfirmation
                  of={of}
                  groupId={groupId}
                  entry={entry}
                  entryId={entryId}
                  open={open}
                  selectedVersion={selectedVersion}
                  retiring={retiring}
                  asked={asked}
                  versionHeading={versionHeading}
                  changing={changing}
                  setAsked={setAsked}
                  setRetiring={setRetiring}
                />
              </>
            )}
          </>
        );
      }}
    </Async>
  );
}

function EntryHeading({
  of,
  groupId,
  standing,
  entry,
  now,
  state,
  editable,
  editor,
  editingField,
  editOpen,
  switchedBy,
  switchRefused,
  changing,
  setAsked,
}: {
  readonly of: LibraryKind;
  readonly groupId: string;
  readonly standing: Standing;
  readonly entry: Entry;
  readonly now: Date;
  readonly state: unknown;
  readonly editable: (field: "name" | "purpose", value: string) => ReactNode;
  readonly editor: ReactNode;
  readonly editingField: "name" | "purpose";
  readonly editOpen: boolean;
  readonly switchedBy: "stop" | "let_go";
  readonly switchRefused: Problem | null;
  readonly changing: Action<Entry | null>;
  readonly setAsked: (asked: Asked) => void;
}) {
  return (
    <>
      <PageHeading
        title={isolatedInText(entry.name)}
        titleValue={editable("name", entry.name)}
        editingTitle={editingField === "name" && editOpen ? editor : undefined}
        besideTitle={
          <>
            <KindTag kind={entry.kind} />
          </>
        }
        focusOnArrival={justMade(state)}
        actions={
          <Box sx={HEADER_ACTS_SX}>
            {entryOffers(entry, "stop", standing, groupId) ||
            entryOffers(entry, "let_go", standing, groupId) ? (
              <ActButton
                action={changing}
                tier={switchedBy === "stop" ? "danger" : "secondary"}
                act={(signal) => {
                  setAsked({ place: "entry", act: switchedBy });
                  return (switchedBy === "stop" ? stopEntry : letEntryGo)(
                    groupId,
                    of.kind,
                    entry.entryId,
                    signal,
                  );
                }}
              >
                {say(ENTRY_ACTS[switchedBy])}
              </ActButton>
            ) : null}
          </Box>
        }
      />
      {switchRefused === null ? null : (
        <ProblemView
          problem={switchRefused}
          rule={{ inGroup: ENTRY_ACT_RULES[switchedBy] }}
        />
      )}
      <Typography variant="body2" component="div" sx={QUIET_SX}>
        {editingField === "purpose" && editOpen
          ? editor
          : editable("purpose", entry.purpose ?? say("entry.saysNothing"))}
      </Typography>
      {entry.stopped === undefined ? null : (
        <Typography variant="body2" sx={{ color: "warning.main", mb: 2 }}>
          {entry.stopped.by.displayName === undefined
            ? say("entry.stoppedByGone", {
                when: whenText(entry.stopped.at, now),
              })
            : say("entry.stoppedBy", {
                name: isolatedInText(entry.stopped.by.displayName),
                when: whenText(entry.stopped.at, now),
              })}
        </Typography>
      )}
    </>
  );
}

function OpenVersionPart({
  group,
  of,
  groupId,
  entry,
  open,
  mayNameVersion,
  versionHeading,
  actions,
  asked,
  versionRefused,
  content,
  unsavedCount,
  saving,
  locateFirst,
  changing,
  setAsked,
  children,
}: {
  readonly group: GroupStanding;
  readonly of: LibraryKind;
  readonly groupId: string;
  readonly entry: Entry;
  readonly open: Version;
  readonly mayNameVersion: boolean;
  readonly versionHeading: RefObject<HTMLHeadingElement | null>;
  readonly actions: ReactNode;
  readonly asked: Asked | null;
  readonly versionRefused: Problem | null;
  readonly content: ReadContent | null;
  readonly unsavedCount: number;
  readonly saving: boolean;
  readonly locateFirst: () => void;
  readonly changing: Action<Entry | null>;
  readonly setAsked: (asked: Asked) => void;
  readonly children: ReactNode;
}) {
  return (
    <Part
      title={
        <Box
          component="span"
          sx={{
            "& bdi": { typography: "versionName" },
          }}
        >
          <VersionLabel
            number={open.number}
            name={mayNameVersion ? null : open.versionName}
          />
        </Box>
      }
      afterTitle={
        <>
          {mayNameVersion ? (
            <VersionName
              key={open.versionId}
              number={open.number}
              value={open.versionName}
              action={changing}
              send={(name, signal) => {
                setAsked({
                  place: "entry",
                  act: "rename",
                });
                return nameVersion(
                  groupId,
                  of.kind,
                  entry.entryId,
                  open.versionId,
                  name,
                  signal,
                );
              }}
            />
          ) : null}
          <VersionStanding version={open} />
        </>
      }
      level="h2"
      depth={1}
      headingRef={versionHeading}
      actions={actions}
    >
      {open.standing === "draft" && (unsavedCount > 0 || saving) ? (
        <Box
          sx={{
            display: "flex",
            flexWrap: "wrap",
            gap: 1,
            mb: "18px",
          }}
        >
          <Link component="button" type="button" onClick={locateFirst}>
            {unsavedCount > 0
              ? say("version.partsUnsaved", {
                  count: unsavedCount,
                })
              : say("version.stillSaving")}
          </Link>
          {unsavedCount > 0 && saving ? (
            <Typography variant="body2">
              {say("version.stillSaving")}
            </Typography>
          ) : null}
        </Box>
      ) : null}

      {versionRefused === null || asked?.place !== "version" ? null : (
        <>
          <ProblemView
            problem={versionRefused}
            rule={{
              inGroup: VERSION_ACT_RULES[asked.act],
            }}
          />
          <RetiredPins
            group={group}
            pins={retiredPinsIn(versionRefused)}
            open={open}
          />
          {content?.kind === "workflow" ? null : (
            <ContentProblems
              problems={contentProblemsIn(versionRefused)}
              problemTotal={contentProblemTotalIn(versionRefused)}
              content={content}
            />
          )}
        </>
      )}
      {open.standing === "submitted" ? (
        <Typography
          variant="body2"
          sx={{ ...QUIET_SX, typography: "systemHint" }}
        >
          {say("version.waiting")}
        </Typography>
      ) : null}

      <Box
        sx={
          content?.kind === "workflow"
            ? undefined
            : {
                "& > :not(:empty)": {
                  borderTop: 1,
                  borderColor: "surface.rule",
                  py: "18px",
                  my: 0,
                },
                "& section:has(> h3), & section:has(> div > h3)": { my: 0 },
              }
        }
      >
        {children}
      </Box>
    </Part>
  );
}

function OpenVersionActs({
  of,
  groupId,
  entry,
  entryId,
  open,
  selectedVersion,
  versionActs,
  draftBlocked,
  changing,
  setAsked,
  setRetiring,
}: {
  readonly of: LibraryKind;
  readonly groupId: string;
  readonly entry: Entry;
  readonly entryId: string;
  readonly open: Version;
  readonly selectedVersion: string | null;
  readonly versionActs: readonly (typeof DRAWN_VERSION_ACTS)[number][];
  readonly draftBlocked: boolean;
  readonly changing: Action<Entry | null>;
  readonly setAsked: (asked: Asked) => void;
  readonly setRetiring: (retiring: Retiring | null) => void;
}) {
  return (
    <Box sx={HEADER_ACTS_SX}>
      {versionActs.map(({ act, words, change, tier }) =>
        act === "retire" ? (
          <Press
            key={act}
            tier="danger"
            unavailable={changing.running}
            onPress={() =>
              setRetiring({
                versionId: open.versionId,
                entryId,
                selection: selectedVersion,
                seenProblem: changing.problem,
              })
            }
          >
            {say(words)}
          </Press>
        ) : (
          <ActButton
            key={act}
            action={changing}
            tier={tier}
            waiting={act === "submit" && draftBlocked}
            act={(signal) => {
              setAsked({
                place: "version",
                act,
                versionId: open.versionId,
              });
              return change(
                groupId,
                of.kind,
                entry.entryId,
                open.versionId,
                signal,
              );
            }}
          >
            {say(words)}
          </ActButton>
        ),
      )}
    </Box>
  );
}

function RetireConfirmation({
  of,
  groupId,
  entry,
  entryId,
  open,
  selectedVersion,
  retiring,
  asked,
  versionHeading,
  changing,
  setAsked,
  setRetiring,
}: {
  readonly of: LibraryKind;
  readonly groupId: string;
  readonly entry: Entry;
  readonly entryId: string;
  readonly open: Version;
  readonly selectedVersion: string | null;
  readonly retiring: Retiring | null;
  readonly asked: Asked | null;
  readonly versionHeading: RefObject<HTMLHeadingElement | null>;
  readonly changing: Action<Entry | null>;
  readonly setAsked: (asked: Asked) => void;
  readonly setRetiring: (retiring: Retiring | null) => void;
}) {
  return (
    <ActDialog
      open={
        retiring !== null &&
        retiring.versionId === open.versionId &&
        retiring.entryId === entryId &&
        retiring.selection === selectedVersion &&
        retiring.seenProblem === changing.problem
      }
      title={say("version.retireConfirm", {
        label: versionLabelText({
          number: open.number,
          name: open.versionName,
        }),
      })}
      onShut={() => setRetiring(null)}
      onExited={() => {
        setRetiring(null);
        if (asked?.place === "version" && asked.act === "retire") {
          versionHeading.current?.focus();
        }
      }}
      action={changing}
      act={(signal) => {
        setRetiring({
          versionId: open.versionId,
          entryId,
          selection: selectedVersion,
          seenProblem: null,
        });
        setAsked({
          place: "version",
          act: "retire",
          versionId: open.versionId,
        });
        return retire(groupId, of.kind, entry.entryId, open.versionId, signal);
      }}
      actLabel={say("version.retire")}
      tier="dangerConfirm"
      abandonLabel={say("confirm.cancel")}
      underway={say("version.retireUnderway")}
      refusal={null}
    >
      {say("version.retireConfirmBody")}
    </ActDialog>
  );
}

function VersionFacts({ version }: { readonly version: Version }) {
  return (
    <Fields inPanel>
      <Field label={say("versions.standing")}>
        <VersionStanding version={version} />
      </Field>
      <Field label={say("versions.writtenBy")}>
        {version.writtenByMigration && version.writers.length === 0
          ? say("version.writtenByNobody")
          : joinedInCell(version.writers.map(nameOrNumber))}
      </Field>
      {version.approval?.approver !== undefined ||
      version.writtenByMigration ? (
        <Field label={say("versions.approvedBy")}>
          {version.approval?.approver === undefined ? (
            say("version.writtenByNobody")
          ) : (
            <bdi dir="auto">{nameOrNumber(version.approval.approver)}</bdi>
          )}
        </Field>
      ) : null}
    </Fields>
  );
}

function PinnedBy({
  group,
  version,
}: {
  readonly group: GroupStanding;
  readonly version: Version;
}) {
  const pinnedById = useId();
  const { pinnedBy } = version;
  return (
    <Part
      title={say("version.pinnedByHeading", { number: version.number })}
      headingId={pinnedById}
      level="h2"
      depth={2}
    >
      <Box
        component="div"
        sx={pinnedBy.length > 0 ? { flexBasis: "100%" } : undefined}
      >
        <Box
          component="div"
          sx={{
            display: "flex",
            flexWrap: "wrap",
            alignItems: "center",
            gap: "4px 14px",
          }}
        >
          {pinnedBy.length === 0 ? (
            say("version.pinnedByNothing")
          ) : (
            <>
              {pinnedBy.length < 2 ? null : (
                <span>
                  {say("version.pinnedByCount", { count: pinnedBy.length })}
                </span>
              )}
              <Box
                component="ul"
                role="list"
                aria-label={say("version.pinnedByLabel")}
                sx={{
                  listStyle: "none",
                  m: 0,
                  p: 0,
                  display: "flex",
                  flexDirection: "column",
                  gap: "10px",
                }}
              >
                {pinnedBy.slice(0, 20).map((pin) => (
                  <Box
                    component="li"
                    key={pin.versionId}
                    sx={{
                      display: "inline-flex",
                      alignItems: "center",
                      gap: "6px",
                    }}
                  >
                    <KindTag kind={pin.kind} />
                    {linkedTo(
                      group,
                      pin.kind,
                      pin.entryId,
                      pin.versionId,
                      versionLabelText(pinLabelled(pin.name, pin)),
                      <Box
                        component="span"
                        sx={{ "& bdi ~ bdi": { typography: "body1" } }}
                      >
                        <VersionLabel {...pinLabelled(pin.name, pin)} />
                      </Box>,
                    )}
                  </Box>
                ))}
              </Box>
              {pinnedBy.length <= 20 ? null : (
                <span>
                  {say("version.pinnedByMore", { count: pinnedBy.length - 20 })}
                </span>
              )}
            </>
          )}
        </Box>
      </Box>
    </Part>
  );
}

function KindTag({ kind }: { readonly kind: string }) {
  return (
    <Box
      component="span"
      sx={{
        typography: "tag",
        px: 0.75,
        borderRadius: 1,
        color: (given: Theme) =>
          kind === "workflow" || kind === "question"
            ? given.palette.kind[kind].text
            : given.palette.text.secondary,
        bgcolor: (given: Theme) =>
          kind === "workflow" || kind === "question"
            ? given.palette.kind[kind].ground
            : given.palette.action.hover,
      }}
    >
      {kindSaid(kind)}
    </Box>
  );
}

function pinLabelled(
  entry: string,
  version: Pick<NumberedVersion, "number" | "versionName">,
) {
  return { entry, number: version.number, name: version.versionName };
}

function pinHint(words: ReactNode): ReactNode {
  return Children.map(words, (part) =>
    typeof part === "string" ? (
      <Box component="span" sx={{ typography: "systemHint" }}>
        {part}
      </Box>
    ) : (
      part
    ),
  );
}

/**
 * The pins a refusal named, each beside the newest of its entry in service,
 * and the way to put each right from the standing the version is at.
 */
function RetiredPins({
  group,
  pins,
  open,
}: {
  readonly group: GroupStanding;
  readonly pins: readonly RetiredPin[] | null;
  readonly open: Version;
}) {
  const labelId = useId();
  if (pins === null || pins.length === 0) {
    return null;
  }
  return (
    <>
      <Typography id={labelId} variant="subtitle2">
        {say("pins.label")}
      </Typography>
      <Box component="ul" role="list" aria-labelledby={labelId} sx={LIST_SX}>
        {pins.map((pin) => {
          const label = <VersionLabel {...pinLabelled(pin.name, pin.pinned)} />;
          const newest = pin.newestInService;
          return (
            <Box component="li" key={pin.pinned.versionId} sx={PIN_LINE_SX}>
              <KindTag kind={pin.kind} />
              {newest === undefined ? (
                <span>{pinHint(visible("pins.unreplaceable", { label }))}</span>
              ) : (
                <span>
                  {pinHint(
                    visible("pins.replaceable", {
                      label,
                      newest: newest.number,
                    }),
                  )}{" "}
                  {linkedTo(
                    group,
                    pin.kind,
                    pin.entryId,
                    newest.versionId,
                    say("pins.openNewest", {
                      label: versionLabelText(pinLabelled(pin.name, newest)),
                    }),
                    <>
                      {visible("pins.openNewest", {
                        label: (
                          <VersionLabel {...pinLabelled(pin.name, newest)} />
                        ),
                      })}
                    </>,
                  )}
                </span>
              )}
            </Box>
          );
        })}
      </Box>
      {open.standing === "draft" || open.standing === "submitted" ? (
        <Typography
          variant="body2"
          sx={{ ...QUIET_SX, typography: "systemHint" }}
        >
          {say(open.standing === "draft" ? "pins.onDraft" : "pins.onSubmitted")}
        </Typography>
      ) : null}
    </>
  );
}

/** Words alone, without a link, for a kind this build has no page for. */
function linkedTo(
  group: GroupStanding,
  kind: string,
  entryId: string,
  versionId: string,
  words: string,
  visibleWords?: ReactNode,
): ReactNode {
  const page = pageOf(kind);
  return page === undefined ? (
    (visibleWords ?? words)
  ) : (
    <Link
      component={RouterLink}
      to={entryHref(group, page, entryId, versionId)}
      aria-label={visibleWords === undefined ? undefined : words}
    >
      {visibleWords ?? words}
    </Link>
  );
}
