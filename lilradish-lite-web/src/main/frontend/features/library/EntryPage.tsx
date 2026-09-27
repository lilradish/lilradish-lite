import Box from "@mui/material/Box";
import Link from "@mui/material/Link";
import Typography from "@mui/material/Typography";
import {
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
  renameEntry,
} from "../../api/groups/{groupId}/{kind}/{entryId}";
import {
  letEntryGo,
  stopEntry,
} from "../../api/groups/{groupId}/{kind}/{entryId}/stop";
import {
  contentProblemsIn,
  retiredPinsIn,
  startDraft,
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
import { ActButton } from "../../lib/action/ActButton";
import { Press } from "../../lib/action/Press";
import { NavigableList } from "../../lib/collection/RowList";
import { isolatedInText } from "../../lib/direction/isolated";
import { Field, Fields } from "../../lib/form/Fields";
import { PageHeading } from "../../lib/heading/PageHeading";
import { ACTS_SX, HEADER_ACTS_SX, QUIET_SX } from "../../lib/layout/parts";
import { useResource } from "../../lib/request/useResource";
import { nameOrNumber } from "../../lib/text/names";
import { whenText } from "../../lib/time/When";
import { joinedInSentence } from "../people/wordLists";
import { ENTRY_ACT_RULES, VERSION_ACT_RULES } from "./actRules";
import { ContentProblems, type ReadContent } from "./ContentProblems";
import { DescribeEntry } from "./DescribeEntry";
import { entryHref, justStarted, OPEN_VERSION } from "./entryAddress";
import {
  kindSaid,
  pageOf,
  standingSaid,
  type LibraryKind,
} from "./libraryKinds";
import { VersionContent } from "./VersionContent";

// Narrow beside the version on a wide viewport, and above it on a narrow one.
const BODY_SX = {
  display: "grid",
  gridTemplateColumns: { xs: "1fr", md: "16rem 1fr" },
  gap: 3,
  alignItems: "start",
};

const PART_SX = { mb: 1 };

// Its markers taken away, so it is named a list outright.
const LIST_SX = { listStyleType: "none", m: 0, mb: 2, p: 0 };

/** The words each act on the entry is drawn with, closed over the acts in both directions. */
const ENTRY_ACTS = {
  start_draft: "versions.newDraft",
  rename: "entry.rename",
  stop: "entry.stop",
  let_go: "entry.letGo",
} as const satisfies Record<EntryAct, MessageId>;

type VersionChange = typeof submit;

/**
 * Each act on a version drawn under What can be done, in order, with its words
 * and its change. Writing is the version's editor's, and not drawn here.
 */
const VERSION_ACTS = {
  write: null,
  submit: { words: "version.submit", change: submit },
  withdraw: { words: "version.withdraw", change: withdraw },
  approve: { words: "version.approve", change: approve },
  retire: { words: "version.retire", change: retire },
} as const satisfies Record<
  VersionAct,
  { readonly words: MessageId; readonly change: VersionChange } | null
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
 * One entry, on a page of its own: its name, what it is for, its kind and
 * whether it is stopped; its versions, newest first; what can be done to the
 * version open; and that version, which the address names, the newest where
 * it names none or one the entry does not have.
 *
 * Every control is drawn from what the server says the reader may do now,
 * the entry's acts and the open version's; none is worked out here. Every
 * change runs under one action the page owns and answers with the entry as
 * it then stands, which is what the page shows until it reads it again. A
 * draft started opens, being the newest.
 *
 * A refusal saying the reader's standing moved reads it again, and the entry
 * too. One saying the entry moved under the page reads the entry again, so
 * no control is left offering what was just refused. One naming pins retired
 * since names each beside What can be done, and what would replace it.
 */
export function EntryPage({ of }: { readonly of: LibraryKind }) {
  const { groupId = "", entryId = "" } = useParams();
  const [parameters] = useSearchParams();
  const { state } = useLocation();
  const navigate = useNavigate();
  const versionsId = useId();
  const actsId = useId();
  const openId = useId();
  const pinnedById = useId();
  const versionsHeading = useRef<HTMLHeadingElement>(null);
  const actsHeading = useRef<HTMLHeadingElement>(null);

  const load = useCallback(
    (signal: AbortSignal): Promise<Entry | null> =>
      readEntry(groupId, of.kind, entryId, signal),
    [groupId, of.kind, entryId],
  );
  const read = useResource<Entry | null>(load, null);
  const [asked, setAsked] = useState<Asked | null>(null);
  const [renaming, setRenaming] = useState<number | null>(null);
  const [renameOpen, setRenameOpen] = useState(false);
  const [readCount, setReadCount] = useState(0);
  const [content, setContent] = useState<ReadContent | null>(null);
  const { shown, changing, refused } = useActedOn(read, refusedForWhatMoved, {
    answered: () => {
      if (asked?.place === "entry" && asked.act === "start_draft") {
        // Replaced: the entry before its draft is not somewhere to go back to.
        navigate(entryHref(groupId, of, entryId), { replace: true });
      }
    },
    readAgain: () => setReadCount((count) => count + 1),
  });

  // Re-run for every entry shown, which is when a pressed control can have gone with the act it offered.
  useLayoutEffect(() => {
    if (document.activeElement !== document.body || asked === null) {
      return;
    }
    const heading = asked.place === "version" ? actsHeading : versionsHeading;
    heading.current?.focus();
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
    <Async read={{ ...read, value: shown }} empty={() => null}>
      {(entry) => {
        if (entry === null) {
          return null;
        }
        const open =
          entry.versions.find(
            (version) => version.versionId === parameters.get(OPEN_VERSION),
          ) ?? entry.versions[0];
        const switchedBy = entry.acts.has("stop") ? "stop" : "let_go";
        const switchRefused = refusedAt(["stop", "let_go"]);
        const draftRefused = refusedAt(["start_draft"]);
        const versionRefused =
          asked?.place === "version" && asked.versionId === open?.versionId
            ? changing.problem
            : null;
        return (
          <>
            <PageHeading
              title={isolatedInText(entry.name)}
              focusOnArrival={justStarted(state)}
              actions={
                <Box sx={HEADER_ACTS_SX}>
                  {entry.acts.has("stop") || entry.acts.has("let_go") ? (
                    <ActButton
                      action={changing}
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
                        setRenaming((last) => (last ?? 0) + 1);
                        setRenameOpen(true);
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
            <Fields>
              <Field label={say("entry.purpose")}>
                {entry.purpose === undefined ? (
                  say("entry.saysNothing")
                ) : (
                  <bdi>{entry.purpose}</bdi>
                )}
              </Field>
              <Field label={say("entry.kind")}>{kindSaid(entry.kind)}</Field>
              <Field label={say("entry.stopped")}>
                {entry.stopped === undefined
                  ? say("entry.runs")
                  : say("entry.stoppedBy", {
                      name: isolatedInText(nameOrNumber(entry.stopped.by)),
                      when: whenText(entry.stopped.at),
                    })}
              </Field>
            </Fields>
            <Box sx={BODY_SX}>
              <section aria-labelledby={versionsId}>
                <Typography
                  ref={versionsHeading}
                  id={versionsId}
                  tabIndex={-1}
                  variant="h6"
                  component="h2"
                  sx={PART_SX}
                >
                  {say("versions.label")}
                </Typography>
                {entry.acts.has("start_draft") ? (
                  <ActButton
                    action={changing}
                    act={(signal) => {
                      setAsked({ place: "entry", act: "start_draft" });
                      return startDraft(
                        groupId,
                        of.kind,
                        entry.entryId,
                        signal,
                      );
                    }}
                  >
                    {say(ENTRY_ACTS.start_draft)}
                  </ActButton>
                ) : null}
                {draftRefused === null ? null : (
                  <ProblemView
                    problem={draftRefused}
                    rule={{ inGroup: ENTRY_ACT_RULES.start_draft }}
                  />
                )}
                <NavigableList
                  items={entry.versions}
                  keyOf={versionIdOf}
                  pathOf={(version) =>
                    entryHref(groupId, of, entry.entryId, version.versionId)
                  }
                  isCurrent={(version) => version === open}
                  labelledBy={versionsId}
                  title={(version) =>
                    say("version.number", { number: version.number })
                  }
                  detail={versionSaid}
                />
              </section>
              {open === undefined ? null : (
                <div>
                  <section aria-labelledby={actsId}>
                    <Typography
                      ref={actsHeading}
                      id={actsId}
                      tabIndex={-1}
                      variant="h6"
                      component="h2"
                      sx={PART_SX}
                    >
                      {say("version.acts")}
                    </Typography>
                    <Box sx={ACTS_SX}>
                      {DRAWN_VERSION_ACTS.filter(({ act }) =>
                        open.acts.has(act),
                      ).map(({ act, words, change }) => (
                        <ActButton
                          key={act}
                          action={changing}
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
                      ))}
                    </Box>
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
                        {/* A workflow's content lists what submitting refuses already, beside where it is. */}
                        {content?.kind === "workflow" ? null : (
                          <ContentProblems
                            problems={contentProblemsIn(versionRefused)}
                            content={content}
                          />
                        )}
                      </>
                    )}
                  </section>
                  <section aria-labelledby={openId}>
                    <Typography
                      id={openId}
                      variant="h6"
                      component="h2"
                      sx={PART_SX}
                    >
                      {say("version.number", { number: open.number })}
                    </Typography>
                    {open.standing === "submitted" ? (
                      <Typography variant="body2" sx={QUIET_SX}>
                        {say("version.waiting")}
                      </Typography>
                    ) : null}
                    <Typography id={pinnedById} variant="body2" sx={QUIET_SX}>
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
                            )}
                          </li>
                        ))}
                      </Box>
                    )}
                    <VersionContent
                      kind={of.kind}
                      groupId={groupId}
                      entryId={entry.entryId}
                      version={open}
                      readCount={readCount}
                      onRefused={refused}
                      onWritten={written}
                      onShown={setContent}
                    />
                  </section>
                </div>
              )}
            </Box>
            {renaming === null ? null : (
              <DescribeEntry
                key={renaming}
                open={renameOpen}
                title={say("entry.renameTitle")}
                actLabel={say("entry.rename")}
                underway="entry.renameUnderway"
                rule={ENTRY_ACT_RULES.rename}
                described={entry}
                send={(described, signal) =>
                  renameEntry(
                    groupId,
                    of.kind,
                    entry.entryId,
                    described,
                    signal,
                  )
                }
                action={changing}
                onShut={() => setRenameOpen(false)}
              />
            )}
          </>
        );
      }}
    </Async>
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
      <Typography id={labelId} variant="subtitle2" component="p">
        {say("pins.label")}
      </Typography>
      <Box component="ul" role="list" aria-labelledby={labelId} sx={LIST_SX}>
        {pins.map((pin) => {
          const named = {
            name: isolatedInText(pin.name),
            kind: kindSaid(pin.kind),
            pinned: pin.pinned.number,
          };
          const newest = pin.newestInService;
          return (
            <li key={pin.pinned.versionId}>
              {newest === undefined ? (
                say("pins.unreplaceable", named)
              ) : (
                <>
                  {say("pins.replaceable", { ...named, newest: newest.number })}{" "}
                  {linkedTo(
                    groupId,
                    pin.kind,
                    pin.entryId,
                    newest.versionId,
                    say("pins.openNewest", {
                      name: isolatedInText(pin.name),
                      number: newest.number,
                    }),
                  )}
                </>
              )}
            </li>
          );
        })}
      </Box>
      {open.standing === "draft" || open.standing === "submitted" ? (
        <Typography variant="body2" sx={QUIET_SX}>
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
): ReactNode {
  const page = pageOf(kind);
  return page === undefined ? (
    words
  ) : (
    <Link
      component={RouterLink}
      to={entryHref(groupId, page, entryId, versionId)}
    >
      {words}
    </Link>
  );
}

function versionIdOf(version: Version): string {
  return version.versionId;
}

/** Its standing, everyone who wrote it and, where it has one, who approved it. */
function versionSaid(version: Version): string {
  const written =
    version.writtenByMigration && version.writers.length === 0
      ? say("version.writtenByNobody")
      : say("version.writtenBy", {
          names: joinedInSentence(version.writers.map(nameOrNumber)),
        });
  const standing = standingSaid(version.standing);
  const approval = version.approval;
  if (approval === undefined) {
    return say("version.detail", { standing, written });
  }
  return say("version.detailApproved", {
    standing,
    written,
    approved:
      approval.approver === undefined
        ? say("version.approvedByNobody")
        : say("version.approvedBy", {
            name: isolatedInText(nameOrNumber(approval.approver)),
          }),
  });
}
