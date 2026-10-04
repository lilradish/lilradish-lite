import Box from "@mui/material/Box";
import Chip from "@mui/material/Chip";
import Link from "@mui/material/Link";
import Typography from "@mui/material/Typography";
import {
  Children,
  useCallback,
  useId,
  useLayoutEffect,
  useRef,
  useState,
  type ReactNode,
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
  type RetiredPin,
} from "../../api/groups/{groupId}/{kind}/{entryId}/versions";
import { approve } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}/approval";
import { retire } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}/retirement";
import {
  submit,
  withdraw,
} from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}/submission";
import type { Problem } from "../../api/problem";
import { Async } from "../../app/Async";
import { ProblemView } from "../../app/ProblemView";
import { useActedOn } from "../../app/useActedOn";
import { say, type MessageId } from "../../i18n/app";
import { visible } from "../../i18n/visible";
import { ActButton } from "../../lib/action/ActButton";
import { ActDialog } from "../../lib/action/ActDialog";
import { Press } from "../../lib/action/Press";
import { isolatedInText } from "../../lib/direction/isolated";
import { PageHeading } from "../../lib/heading/PageHeading";
import { Part } from "../../lib/layout/Part";
import { HEADER_ACTS_SX, QUIET_SX } from "../../lib/layout/parts";
import { useResource } from "../../lib/request/useResource";
import { useCalendarNow, whenText } from "../../lib/time/When";
import { ENTRY_ACT_RULES, VERSION_ACT_RULES } from "./actRules";
import { ContentProblems, type ReadContent } from "./ContentProblems";
import { DescribeEntry } from "./DescribeEntry";
import { entryHref, justMade, OPEN_VERSION } from "./entryAddress";
import { kindSaid, pageOf, type LibraryKind } from "./libraryKinds";
import { VersionContent } from "./VersionContent";
import { Versions } from "./Versions";
import { WorkflowSubmissionRefusal } from "./WorkflowContent";
import { DraftStatusContext, useDraftStatus } from "./useDraftStatus";

// Its markers taken away, so it is named a list outright.
const LIST_SX = { listStyleType: "none", m: 0, mb: 2, p: 0 };

/** The words each act on the entry is drawn with, closed over the acts in both directions. */
const ENTRY_ACTS = {
  start_draft: "versions.newDraft",
  rename: "entry.edit",
  stop: "entry.stop",
  let_go: "entry.letGo",
} as const satisfies Record<EntryAct, MessageId>;

type VersionChange = typeof submit;

/**
 * Each act on a version drawn beside its title, in order: whether it moves the version forward, and whether
 * it takes it out of use. Writing is the version's editor's, and not drawn here.
 */
const VERSION_ACTS = {
  write: null,
  submit: {
    words: "version.submit",
    change: submit,
    leads: true,
    warns: false,
  },
  withdraw: {
    words: "version.withdraw",
    change: withdraw,
    leads: false,
    warns: false,
  },
  approve: {
    words: "version.approve",
    change: approve,
    leads: true,
    warns: false,
  },
  retire: {
    words: "version.retire",
    change: retire,
    leads: false,
    warns: true,
  },
} as const satisfies Record<
  VersionAct,
  {
    readonly words: MessageId;
    readonly change: VersionChange;
    readonly leads: boolean;
    readonly warns: boolean;
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

/**
 * Entry and version acts come from the server, never inferred here; refusals
 * trigger a reread so outdated controls can disappear.
 */
export function EntryPage({ of }: { readonly of: LibraryKind }) {
  const { groupId = "", entryId = "" } = useParams();
  return <EntryForIdentity key={`${groupId}:${of.kind}:${entryId}`} of={of} />;
}

function EntryForIdentity({ of }: { readonly of: LibraryKind }) {
  const now = useCalendarNow();
  const { groupId = "", entryId = "" } = useParams();
  const [parameters] = useSearchParams();
  const { state } = useLocation();
  const navigate = useNavigate();
  const pinnedById = useId();
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
  const [retiring, setRetiring] = useState<{
    versionId: string;
    entryId: string;
    selection: string | null;
    seenProblem: Problem | null;
  } | null>(null);
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
          navigate(entryHref(groupId, of, entryId), { replace: true });
        }
      },
      readAgain: () => setReadCount((count) => count + 1),
    },
  );

  const selectedVersion = parameters.get(OPEN_VERSION);
  const selected =
    shown?.versions.find((version) => version.versionId === selectedVersion) ??
    shown?.versions[0];
  const { report, blocked: draftBlocked } = useDraftStatus(
    selected?.versionId ?? null,
    of.kind === "workflow" ? 6 : of.kind === "question" ? 5 : 3,
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
        const switchedBy = entry.acts.has("stop") ? "stop" : "let_go";
        const switchRefused = refusedAt(["stop", "let_go"]);
        const draftRefused = refusedAt(["start_draft"]);
        const versionRefused =
          asked?.place === "version" && asked.versionId === open?.versionId
            ? changing.problem
            : null;
        const versionActs = DRAWN_VERSION_ACTS.filter(
          ({ act }) => open?.acts.has(act) === true,
        );
        return (
          <>
            <PageHeading
              title={isolatedInText(entry.name)}
              besideTitle={
                <Chip
                  label={kindSaid(entry.kind)}
                  size="small"
                  variant="outlined"
                />
              }
              focusOnArrival={justMade(state)}
              actions={
                <Box sx={HEADER_ACTS_SX}>
                  {entry.acts.has("stop") || entry.acts.has("let_go") ? (
                    <ActButton
                      action={changing}
                      warns={switchedBy === "stop"}
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
                  {entry.acts.has("rename") ? (
                    <Press
                      unavailable={changing.running}
                      onPress={() => {
                        setAsked({ place: "entry", act: "rename" });
                        setEditing((last) => (last ?? 0) + 1);
                        setEditOpen(true);
                      }}
                    >
                      {say(ENTRY_ACTS.rename)}
                    </Press>
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
            <Typography variant="body2" sx={QUIET_SX}>
              {entry.purpose === undefined ? (
                say("entry.saysNothing")
              ) : (
                <bdi>{entry.purpose}</bdi>
              )}
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
            <Versions
              key={`${of.kind}:${entry.entryId}`}
              versions={entry.versions}
              open={open}
              hrefOf={(version) =>
                entryHref(groupId, of, entry.entryId, version.versionId)
              }
              headingRef={versionsHeading}
              actions={
                entry.acts.has("start_draft") ? (
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
                        <Part
                          title={say("version.number", { number: open.number })}
                          level="h2"
                          depth={1}
                          headingRef={versionHeading}
                          actions={
                            versionActs.length === 0 ? undefined : (
                              <Box sx={HEADER_ACTS_SX}>
                                {versionActs.map(
                                  ({ act, words, change, leads, warns }) =>
                                    act === "retire" ? (
                                      <Press
                                        key={act}
                                        warns
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
                                        variant={
                                          leads ? "contained" : undefined
                                        }
                                        warns={warns}
                                        waiting={
                                          act === "submit" && draftBlocked
                                        }
                                        reason={
                                          act === "submit" && draftBlocked
                                            ? {
                                                severity: "info",
                                                words: say(
                                                  "version.finishEditing",
                                                ),
                                              }
                                            : undefined
                                        }
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
                            )
                          }
                        >
                          {versionRefused === null ||
                          asked?.place !== "version" ? null : (
                            <>
                              <ProblemView
                                problem={versionRefused}
                                rule={{ inGroup: VERSION_ACT_RULES[asked.act] }}
                              />
                              <RetiredPins
                                groupId={groupId}
                                pins={retiredPinsIn(versionRefused)}
                                open={open}
                              />
                              {content?.kind === "workflow" ? null : (
                                <ContentProblems
                                  problems={contentProblemsIn(versionRefused)}
                                  problemTotal={contentProblemTotalIn(
                                    versionRefused,
                                  )}
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
                          <Typography
                            id={pinnedById}
                            variant="body2"
                            sx={{ ...QUIET_SX, typography: "systemHint" }}
                          >
                            {say("version.pinCount", {
                              count: open.pinnedBy.length,
                            })}
                          </Typography>
                          {open.pinnedBy.length === 0 ? null : (
                            <Box
                              component="ul"
                              role="list"
                              aria-labelledby={pinnedById}
                              sx={LIST_SX}
                            >
                              {open.pinnedBy.map((pin) => (
                                <li key={pin.versionId}>
                                  {linkedTo(
                                    groupId,
                                    pin.kind,
                                    pin.entryId,
                                    pin.versionId,
                                    say("version.pinnedBy", {
                                      name: isolatedInText(pin.name),
                                      kind: kindSaid(pin.kind),
                                      number: pin.number,
                                    }),
                                    pinHint(
                                      visible("version.pinnedBy", {
                                        name: <bdi>{pin.name}</bdi>,
                                        kind: <span>{kindSaid(pin.kind)}</span>,
                                        number: <span>{pin.number}</span>,
                                      }),
                                    ),
                                  )}
                                </li>
                              ))}
                            </Box>
                          )}

                          {document}
                        </Part>
                      )}
                    />
                  </DraftStatusContext.Provider>
                </WorkflowSubmissionRefusal.Provider>
                <ActDialog
                  open={
                    retiring !== null &&
                    retiring.versionId === open.versionId &&
                    retiring.entryId === entryId &&
                    retiring.selection === selectedVersion &&
                    retiring.seenProblem === changing.problem
                  }
                  title={say("version.retireConfirm", { number: open.number })}
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
                    return retire(
                      groupId,
                      of.kind,
                      entry.entryId,
                      open.versionId,
                      signal,
                    );
                  }}
                  actLabel={say("version.retire")}
                  warns
                  abandonLabel={say("confirm.cancel")}
                  underway={say("version.retireUnderway")}
                  refusal={null}
                >
                  {say("version.retireConfirmBody")}
                </ActDialog>
              </>
            )}
            {editing === null ? null : (
              <DescribeEntry
                key={editing}
                open={editOpen}
                title={say("entry.editTitle")}
                actLabel={say("entry.save")}
                underway="entry.editUnderway"
                rule={ENTRY_ACT_RULES.rename}
                described={entry}
                send={(described, signal) =>
                  editEntry(groupId, of.kind, entry.entryId, described, signal)
                }
                action={changing}
                onShut={() => setEditOpen(false)}
              />
            )}
          </>
        );
      }}
    </Async>
  );
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
  groupId,
  pins,
  open,
}: {
  readonly groupId: string;
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
          const visibleNamed = {
            name: <bdi>{pin.name}</bdi>,
            kind: <span>{kindSaid(pin.kind)}</span>,
            pinned: <span>{pin.pinned.number}</span>,
          };
          const newest = pin.newestInService;
          return (
            <li key={pin.pinned.versionId}>
              {newest === undefined ? (
                pinHint(visible("pins.unreplaceable", visibleNamed))
              ) : (
                <>
                  {pinHint(
                    visible("pins.replaceable", {
                      ...visibleNamed,
                      newest: <span>{newest.number}</span>,
                    }),
                  )}{" "}
                  {linkedTo(
                    groupId,
                    pin.kind,
                    pin.entryId,
                    newest.versionId,
                    say("pins.openNewest", {
                      name: isolatedInText(pin.name),
                      number: newest.number,
                    }),
                    <>
                      {visible("pins.openNewest", {
                        name: <bdi>{pin.name}</bdi>,
                        number: newest.number,
                      })}
                    </>,
                  )}
                </>
              )}
            </li>
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

/** A link to a version of an entry of this group's, or the words alone for a kind this build has no page for. */
function linkedTo(
  groupId: string,
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
      to={entryHref(groupId, page, entryId, versionId)}
      aria-label={visibleWords === undefined ? undefined : words}
    >
      {visibleWords ?? words}
    </Link>
  );
}
