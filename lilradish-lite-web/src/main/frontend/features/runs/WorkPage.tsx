import AccountTreeIcon from "@mui/icons-material/AccountTree";
import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import CardActionArea from "@mui/material/CardActionArea";
import CardContent from "@mui/material/CardContent";
import Stack from "@mui/material/Stack";
import Tab from "@mui/material/Tab";
import Tabs from "@mui/material/Tabs";
import Typography from "@mui/material/Typography";
import {
  useCallback,
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
  type ReactNode,
  type RefObject,
} from "react";
import {
  useLocation,
  useNavigate,
  useParams,
  useSearchParams,
} from "react-router";

import {
  offeredLoader,
  type OfferedWorkflow,
} from "../../api/groups/{groupId}/offered-workflows";
import {
  runsLoader,
  type RunRow,
  type StartedRun,
} from "../../api/groups/{groupId}/runs";
import {
  asProblem,
  BROKEN_HERE,
  RequestFailed,
  type Problem,
} from "../../api/problem";
import { mayIn, type GroupStanding } from "../../api/standing";
import { ProblemView } from "../../app/ProblemView";
import { movesTheReader } from "../../app/refusal";
import { useGroup } from "../../app/standing/RequireGroup";
import { useStandingRead } from "../../app/standing/StandingContext";
import { say, type MessageId } from "../../i18n/app";
import { FilterField } from "../../lib/collection/FilterField";
import { SortableTable } from "../../lib/collection/SortableTable";
import { useListAddress } from "../../lib/collection/useListAddress";
import { useKeptChoice } from "../../lib/device/useKeptChoice";
import { Press } from "../../lib/action/Press";
import { isolatedInText } from "../../lib/direction/isolated";
import { PageHeading } from "../../lib/heading/PageHeading";
import { QUIET_SX } from "../../lib/layout/parts";
import { Notice } from "../../lib/notice/Notice";
import { reportCaught } from "../../lib/report/reportCaught";
import { useAccumulatedResource } from "../../lib/request/useAccumulatedResource";
import { usePagedResource } from "../../lib/request/usePagedResource";
import {
  listQuery,
  NAMED_TAB,
  runHref,
  tabNamed,
  WORK_TABS,
  type WorkTab,
} from "./runAddress";
import { NEWEST_FIRST, RUNS_SORTABLE, runColumns } from "./runColumns";
import { RunPage } from "./RunPage";
import { StartHere, type StartSettled } from "./StartRun";
import { StepPage } from "./steps/StepPage";

const KEPT_TAB_AS = "lilradish.work.tab";

const TAB_WORDS = {
  newRun: "work.newRun",
  history: "work.history",
} as const satisfies Record<WorkTab, MessageId>;

const TABS_SX = { mb: 3 };

const CARDS_SX = {
  display: "grid",
  gridTemplateColumns: "repeat(auto-fill, minmax(min(100%, 16rem), 1fr))",
  gap: 2,
};

const CARD_SX = { borderColor: "text.secondary", bgcolor: "background.paper" };

// Drawn apart by more than colour: its border is thicker as well.
const PICKED_SX = { ...CARD_SX, borderColor: "primary.main", borderWidth: 2 };

const CARD_LINE_SX = {
  display: "flex",
  alignItems: "flex-start",
  gap: 1,
  minWidth: 0,
};

const CARD_WORDS_SX = { minWidth: 0, overflowWrap: "anywhere" };

const CARD_ICON_SX = { width: 20, height: 20, flexShrink: 0, mt: 0.25 };

const CARD_NAME_SX = { display: "block" };

const FILTER_STYLE = { width: "100%", maxWidth: "22rem" };

/** The group the page is under, and what a refusal saying the reader's standing moved is handed to. */
interface Reach {
  readonly group: GroupStanding;
  readonly onProblem: (problem: Problem | null) => void;
}

export function WorkPage() {
  const group = useGroup();
  const standing = useStandingRead();
  const reloadStanding = standing.reload;
  const [address, setAddress] = useSearchParams();
  const [kept, keep] = useKeptChoice(KEPT_TAB_AS, WORK_TABS);
  const tabsId = useId();
  const newRunPanel = useRef<HTMLDivElement>(null);
  const onProblem = useCallback(
    (problem: Problem | null) => {
      if (problem !== null && movesTheReader(problem)) {
        reloadStanding();
      }
    },
    [reloadStanding],
  );
  const named = tabNamed(address.toString());
  const mayStart = mayIn(group, "start_run");
  const tab: WorkTab = mayStart ? (named ?? kept) : "history";
  // In place of the entry it was on: Back leaves Work rather than going back through its tabs.
  const naming = useCallback(
    (drawn: WorkTab) =>
      setAddress(
        (previous) => {
          const withTab = new URLSearchParams(previous);
          withTab.set(NAMED_TAB, drawn);
          return withTab;
        },
        { replace: true },
      ),
    [setAddress],
  );
  useEffect(() => {
    if (named !== tab) {
      naming(tab);
    }
  }, [named, tab, naming]);
  const heading = workHeading(group);
  const reach: Reach = { group, onProblem };
  if (!mayStart) {
    return (
      <>
        <PageHeading title={heading} />
        <History reach={reach} />
      </>
    );
  }
  return (
    <>
      <PageHeading title={heading} />
      <Tabs
        value={tab}
        onChange={(_event, next: WorkTab) => {
          keep(next);
          naming(next);
        }}
        aria-label={heading}
        sx={TABS_SX}
      >
        {WORK_TABS.map((each) => (
          <Tab
            key={each}
            value={each}
            label={say(TAB_WORDS[each])}
            id={`${tabsId}-${each}`}
            aria-controls={`${tabsId}-${each}-panel`}
          />
        ))}
      </Tabs>
      <TabPanel
        ref={newRunPanel}
        id={`${tabsId}-newRun`}
        shown={tab === "newRun"}
      >
        <NewRun
          key={`${group.groupId}/${standing.value.userId ?? ""}`}
          reach={reach}
          panel={newRunPanel}
        />
      </TabPanel>
      <TabPanel id={`${tabsId}-history`} shown={tab === "history"}>
        <History reach={reach} />
      </TabPanel>
    </>
  );
}

/** The way back to History is the run's own. */
export function OpenedRun() {
  const { runId, stepId } = useParams();
  return stepId === undefined ? (
    <RunPage key={runId} />
  ) : (
    <StepPage key={`${runId}/${stepId}`} />
  );
}

function workHeading(group: GroupStanding): string {
  return say("work.heading", { group: isolatedInText(group.name) });
}

function TabPanel({
  ref,
  id,
  shown,
  children,
}: {
  readonly ref?: RefObject<HTMLDivElement | null>;
  readonly id: string;
  readonly shown: boolean;
  readonly children: ReactNode;
}) {
  return (
    <div
      ref={ref}
      role="tabpanel"
      id={`${id}-panel`}
      aria-labelledby={id}
      tabIndex={-1}
      hidden={!shown}
    >
      {shown ? children : null}
    </div>
  );
}

async function findOffered(
  groupId: string,
  name: string,
  entryId: string,
  signal: AbortSignal,
): Promise<OfferedWorkflow | null> {
  const search = async (filter: string): Promise<OfferedWorkflow | null> => {
    const load = offeredLoader(groupId, filter);
    const seen = new Set<string>();
    let cursor: string | null = null;
    for (;;) {
      const page = await load(cursor, signal);
      const found = page.items.find((each) => each.entryId === entryId);
      if (found !== undefined) {
        return found;
      }
      const next = page.nextCursor;
      if (next === undefined) {
        return null;
      }
      if (seen.has(next)) {
        throw new RequestFailed({ code: BROKEN_HERE });
      }
      seen.add(next);
      cursor = next;
    }
  };
  const named = await search(name);
  return named ?? (name === "" ? null : search(""));
}

// Changing the read source abandons a pending card request after verification closes the page.
const closedOffers = async () => ({ items: [] });

/**
 * One card per workflow on offer, by name, each saying what it is for, and nothing of a run already started. Picking
 * one asks what its newest version takes; a run begun there opens its own page for a reader who
 * may read it. Where the form goes with the control pressed in it, the keyboard starts again at the panel.
 */
function NewRun({
  reach,
  panel,
}: {
  readonly reach: Reach;
  readonly panel: RefObject<HTMLDivElement | null>;
}) {
  const { group, onProblem } = reach;
  const { groupId, key: groupKey } = group;
  const search = listQuery(useLocation().search);
  const navigate = useNavigate();
  const nameInput = useRef<HTMLInputElement>(null);
  const [filter, setFilter] = useState("");
  const [checkProblem, setCheckProblem] = useState<Problem | null>(null);
  const checkClosed = checkProblem !== null && movesTheReader(checkProblem);
  const offered = useMemo(
    () => offeredLoader(groupId, filter),
    [groupId, filter],
  );
  const load = checkClosed ? closedOffers : offered;
  const read = useAccumulatedResource(load, movesTheReader);
  const reloadOffered = useRef(read.reload);
  const [picked, setPicked] = useState<OfferedWorkflow | null>(null);
  const [begun, setBegun] = useState<number | null>(null);
  const [readAgain, setReadAgain] = useState(false);
  const [checking, setChecking] = useState<{
    readonly entryId: string;
    readonly name: string;
    readonly attempt: number;
  } | null>(null);
  const closed = read.terminal || checkClosed;
  const workflow = closed ? null : picked;
  const returnToPanel = useRef(false);
  useEffect(() => {
    if (workflow === null && returnToPanel.current) {
      returnToPanel.current = false;
      panel.current?.focus();
    }
  }, [workflow, panel]);
  const pickedId = picked?.entryId;
  const [terminalNoted, setTerminalNoted] = useState(false);
  const problem = checkClosed ? checkProblem : (read.problem ?? checkProblem);
  if (closed && !terminalNoted) {
    setTerminalNoted(true);
    setPicked(null);
    setChecking(null);
  }

  useEffect(() => {
    if (pickedId !== undefined) {
      nameInput.current?.focus();
    }
  }, [pickedId]);

  useEffect(() => {
    onProblem(problem);
  }, [problem, onProblem]);

  useEffect(() => {
    reloadOffered.current = read.reload;
  }, [read.reload]);

  useEffect(() => {
    if (checking === null) {
      return;
    }
    const controller = new AbortController();
    void findOffered(
      groupId,
      checking.name,
      checking.entryId,
      controller.signal,
    )
      .then((found) => {
        if (controller.signal.aborted) {
          return;
        }
        setChecking(null);
        reloadOffered.current();
        if (found === null) {
          returnToPanel.current = true;
          setPicked(null);
        } else {
          setPicked(found);
        }
      })
      .catch((failure: unknown) => {
        if (controller.signal.aborted) {
          return;
        }
        if (!(failure instanceof RequestFailed)) {
          reportCaught(failure);
        }
        const problem = asProblem(failure, controller.signal);
        setCheckProblem(problem);
        if (movesTheReader(problem)) {
          setChecking(null);
        }
      });
    return () => controller.abort();
  }, [checking, groupId, panel]);

  const putAway = () => {
    returnToPanel.current = true;
    setPicked(null);
    setChecking(null);
    setCheckProblem(null);
    setReadAgain(false);
  };
  const settled: StartSettled = {
    onStarted: (started: StartedRun) => {
      if (mayIn(group, "read_all_runs") || mayIn(group, "read_own_runs")) {
        void navigate(`${runHref(group, started.runId)}${search}`);
      } else {
        putAway();
        setBegun(started.number);
      }
    },
    onNotOffered: () => {
      setReadAgain(true);
      setCheckProblem(null);
      if (picked !== null) {
        setChecking({ entryId: picked.entryId, name: picked.name, attempt: 0 });
      }
    },
    onProblem,
  };

  const verification = (
    <>
      {!readAgain ? null : (
        <Notice severity="warning">{say("work.noLongerOffered")}</Notice>
      )}
      {checkProblem === null ? null : (
        <>
          <ProblemView problem={checkProblem} />
          {closed ? null : (
            <Press
              onPress={() => {
                setCheckProblem(null);
                setChecking((prior) =>
                  prior === null
                    ? null
                    : { ...prior, attempt: prior.attempt + 1 },
                );
              }}
            >
              {say("read.retry")}
            </Press>
          )}
        </>
      )}
    </>
  );

  return (
    <>
      <div role="status">
        {begun === null ? null : (
          <Notice severity="info">
            {say("work.startedUnreadable", {
              run: say("work.runKey", { key: groupKey, number: begun }),
            })}
          </Notice>
        )}
        {workflow === null ? verification : null}
      </div>
      {closed ? null : (
        <Box style={FILTER_STYLE}>
          <FilterField
            label={say("library.filter")}
            value={filter}
            onChange={setFilter}
          />
        </Box>
      )}
      {checkClosed || read.problem === null ? null : (
        <>
          <ProblemView problem={read.problem} rule={{ inGroup: "start_run" }} />
          {closed ? null : (
            <Press onPress={read.retry}>{say("read.retry")}</Press>
          )}
        </>
      )}
      {!closed && read.loading ? (
        <Typography role="status">{say("read.pending")}</Typography>
      ) : null}
      {!closed &&
      read.problem === null &&
      !read.loading &&
      read.items.length === 0 &&
      !read.hasNext ? (
        <Notice severity="info">
          {filter === "" ? (
            <Box component="details">
              <summary>{say("work.nothingOfferedShort")}</summary>
              <Typography sx={{ typography: "systemHint", mt: 1 }}>
                {say("work.nothingOffered")}
              </Typography>
            </Box>
          ) : (
            say("newRun.noMatch")
          )}
        </Notice>
      ) : null}
      {closed || read.items.length === 0 ? null : (
        <Box sx={CARDS_SX}>
          {read.items.map((each) => (
            <Card
              key={each.entryId}
              variant="outlined"
              sx={each.entryId === picked?.entryId ? PICKED_SX : CARD_SX}
            >
              <CardActionArea
                aria-pressed={each.entryId === picked?.entryId}
                onClick={() => {
                  if (each.entryId === picked?.entryId) {
                    nameInput.current?.focus();
                  }
                  setPicked(each);
                  setBegun(null);
                  setReadAgain(false);
                  setChecking(null);
                  setCheckProblem(null);
                }}
              >
                <CardContent>
                  <Box sx={CARD_LINE_SX}>
                    <AccountTreeIcon aria-hidden="true" sx={CARD_ICON_SX} />
                    <Box sx={CARD_WORDS_SX}>
                      <Typography
                        variant="subtitle1"
                        component="span"
                        sx={CARD_NAME_SX}
                      >
                        {isolatedInText(each.name)}
                      </Typography>
                      {each.purpose === undefined ? null : (
                        <Typography
                          variant="body2"
                          component="span"
                          sx={{ ...QUIET_SX, display: "block", mb: 0 }}
                        >
                          {isolatedInText(each.purpose)}
                        </Typography>
                      )}
                    </Box>
                  </Box>
                </CardContent>
              </CardActionArea>
            </Card>
          ))}
        </Box>
      )}
      {!closed && read.hasNext ? (
        <Stack direction="row" sx={{ my: 2 }}>
          <Press
            tier="quiet"
            unavailable={read.loading || read.problem !== null}
            onPress={read.next}
          >
            {say("newRun.loadMore")}
          </Press>
        </Stack>
      ) : null}
      {workflow === null ? null : (
        <StartHere
          key={workflow.entryId}
          groupId={groupId}
          workflow={workflow}
          settled={settled}
          nameRef={nameInput}
          onAbandon={putAway}
          verification={verification}
        />
      )}
    </>
  );
}

/** A row opens its run's page, whose address keeps the list's own query. */
function History({ reach }: { readonly reach: Reach }) {
  const { group, onProblem } = reach;
  const { groupId, key: groupKey } = group;
  const search = listQuery(useLocation().search);
  const { filter, order, sort, onFilter, onOrder } = useListAddress(
    RUNS_SORTABLE,
    NEWEST_FIRST,
  );
  const load = useMemo(
    () => runsLoader(groupId, { filter, order: sort }),
    [groupId, filter, sort],
  );
  const page = usePagedResource(load);
  const columns = useMemo(() => runColumns(groupKey), [groupKey]);

  useEffect(() => onProblem(page.problem), [page.problem, onProblem]);

  return (
    <>
      <Box style={FILTER_STYLE}>
        <FilterField
          label={say("work.filter")}
          value={filter}
          onChange={onFilter}
        />
      </Box>
      {page.problem === null ? null : <ProblemView problem={page.problem} />}
      <SortableTable
        label={say("work.whatIsRunning")}
        columns={columns}
        order={order}
        onOrder={onOrder}
        page={page}
        keyOf={runIdOf}
        picked={null}
        hrefOf={(row) => `${runHref(group, row.runId)}${search}`}
        empty={emptySaid(group, filter)}
      />
    </>
  );
}

function emptySaid(group: GroupStanding, filter: string): ReactNode {
  if (!mayIn(group, "read_all_runs") && !mayIn(group, "read_own_runs")) {
    return (
      <Box component="details">
        <summary>{say("work.noneReadableShort")}</summary>
        <Typography sx={{ typography: "systemHint", mt: 1 }}>
          {say("work.noneReadable")}
        </Typography>
      </Box>
    );
  }
  if (!mayIn(group, "read_all_runs") && mayIn(group, "read_own_runs")) {
    return say(filter === "" ? "work.noOwnRuns" : "work.noOwnMatch");
  }
  return say(filter === "" ? "work.noRuns" : "work.noMatch");
}

function runIdOf(row: RunRow): string {
  return row.runId;
}
