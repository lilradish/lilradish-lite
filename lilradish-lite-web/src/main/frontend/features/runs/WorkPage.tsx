import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Card from "@mui/material/Card";
import CardActionArea from "@mui/material/CardActionArea";
import CardContent from "@mui/material/CardContent";
import ToggleButton from "@mui/material/ToggleButton";
import ToggleButtonGroup from "@mui/material/ToggleButtonGroup";
import Typography from "@mui/material/Typography";
import {
  useCallback,
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
  type MouseEvent,
  type RefObject,
} from "react";
import {
  Link as RouterLink,
  useLocation,
  useNavigate,
  useParams,
} from "react-router";

import {
  readOffered,
  type OfferedWorkflow,
} from "../../api/groups/{groupId}/offered-workflows";
import {
  runsLoader,
  type RunReading,
  type RunRow,
  type StartedRun,
} from "../../api/groups/{groupId}/runs";
import type { Problem } from "../../api/problem";
import { mayIn } from "../../api/standing";
import { Async } from "../../app/Async";
import { groupPage, WORK_ITEM } from "../../app/destinations";
import { ProblemView } from "../../app/ProblemView";
import { movesTheReader } from "../../app/refusal";
import { useStandingRead } from "../../app/standing/StandingContext";
import { say, type MessageId } from "../../i18n/app";
import { FilterField } from "../../lib/collection/FilterField";
import { orderSpelled } from "../../lib/collection/ordering";
import { PagingControls } from "../../lib/collection/PagingControls";
import { NavigableList } from "../../lib/collection/RowList";
import { SortableTable } from "../../lib/collection/SortableTable";
import { useListAddress } from "../../lib/collection/useListAddress";
import { isolatedInText } from "../../lib/direction/isolated";
import { PageHeading } from "../../lib/heading/PageHeading";
import { HEADER_ACTS_SX, QUIET_SX } from "../../lib/layout/parts";
import { Notice } from "../../lib/notice/Notice";
import {
  usePagedResource,
  type PagedResource,
} from "../../lib/request/usePagedResource";
import { useResource } from "../../lib/request/useResource";
import { listQuery, runHref } from "./runAddress";
import {
  LAST_HAPPENED_FIRST,
  NEWEST_FIRST,
  RUNS_SORTABLE,
  listedWhereSaid,
  runColumns,
} from "./runColumns";
import { byDay, type RunDay } from "./runDays";
import { RunPage } from "./RunPage";
import { StartDialog, StartHere, type StartSettled } from "./StartRun";
import { StepPage } from "./steps/StepPage";
import { useWorkDrawing, type WorkDrawing } from "./useWorkDrawing";

/** The words each day the runs are listed under is said in, closed over the days. */
const DAYS = {
  today: "work.today",
  yesterday: "work.yesterday",
  earlier: "work.earlier",
} as const satisfies Record<RunDay, MessageId>;

const LAST_HAPPENED_ORDER = orderSpelled(LAST_HAPPENED_FIRST);

const NONE_OFFERED: readonly OfferedWorkflow[] = [];

const CONVERSATION_SX = {
  display: "flex",
  flexWrap: "wrap",
  alignItems: "flex-start",
  gap: 3,
};

const LEADING_SX = { flex: "1 1 18rem", maxWidth: "26rem", minWidth: 0 };

const REST_SX = { flex: "999 1 28rem", minWidth: 0 };

const NEW_RUN_SX = { mb: 2 };

const CARDS_SX = {
  display: "grid",
  gridTemplateColumns: "repeat(auto-fill, minmax(16rem, 1fr))",
  gap: 2,
};

// Drawn apart by more than colour: its border is thicker as well.
const PICKED_SX = { borderColor: "primary.main", borderWidth: 2 };

const OPENED_SX = { mt: 3 };

const STATUS_SX = { my: 1 };

/** What the group this page is under lets the reader do here, as the standing says. */
interface Reach {
  readonly groupId: string;
  readonly groupKey: string;
  readonly mayStart: boolean;
  readonly onProblem: (problem: Problem | null) => void;
}

/**
 * One page drawn as a conversation or in detail, as this device last chose; both list the runs the server lets
 * the reader read, and a run opened is the run's own page, beside the runs or under them.
 */
export function WorkPage() {
  const standingRead = useStandingRead();
  const reloadStanding = standingRead.reload;
  const { groupId = "", runId, stepId } = useParams();
  const [drawing, setDrawing] = useWorkDrawing();
  const [starting, setStarting] = useState(false);
  const detailToggle = useRef<HTMLButtonElement>(null);
  const group = standingRead.value.groups.find(
    (each) => each.groupId === groupId,
  );
  const onProblem = useCallback(
    (problem: Problem | null) => {
      if (problem !== null && movesTheReader(problem)) {
        reloadStanding();
      }
    },
    [reloadStanding],
  );

  if (group === undefined) {
    return null;
  }
  const reach: Reach = {
    groupId,
    groupKey: group.key,
    mayStart: mayIn(group, "start_run"),
    onProblem,
  };
  return (
    <>
      <PageHeading
        title={say("work.heading", { group: isolatedInText(group.name) })}
        actions={
          <Box sx={HEADER_ACTS_SX}>
            {drawing === "detail" && reach.mayStart ? (
              <Button variant="contained" onClick={() => setStarting(true)}>
                {say("work.startARun")}
              </Button>
            ) : null}
            <DrawingSwitch
              drawing={drawing}
              detailRef={detailToggle}
              onChange={setDrawing}
            />
          </Box>
        }
      />
      {drawing === "conversation" ? (
        <Conversation
          reach={reach}
          runId={runId}
          stepId={stepId}
          onDetail={() => {
            detailToggle.current?.focus();
            setDrawing("detail");
          }}
        />
      ) : (
        <InDetail
          reach={reach}
          runId={runId}
          stepId={stepId}
          starting={starting && reach.mayStart}
          onStartShut={() => setStarting(false)}
        />
      )}
    </>
  );
}

function DrawingSwitch({
  drawing,
  detailRef,
  onChange,
}: {
  readonly drawing: WorkDrawing;
  /** The switch to the detail, which takes the keyboard where the detail is asked for from a control it takes away. */
  readonly detailRef: RefObject<HTMLButtonElement | null>;
  readonly onChange: (next: WorkDrawing) => void;
}) {
  return (
    <ToggleButtonGroup
      exclusive
      size="small"
      value={drawing}
      aria-label={say("work.drawn")}
      onChange={(_event, next: WorkDrawing | null) => {
        if (next !== null) {
          onChange(next);
        }
      }}
    >
      <ToggleButton value="conversation">
        {say("work.asConversation")}
      </ToggleButton>
      <ToggleButton ref={detailRef} value="detail">
        {say("work.inDetail")}
      </ToggleButton>
    </ToggleButtonGroup>
  );
}

/**
 * The runs down the leading side, the one something last happened in first, each under the day that was. The
 * rest of the page is the run opened, or what may start; New run takes the keyboard there either way.
 */
function Conversation({
  reach,
  runId,
  stepId,
  onDetail,
}: {
  readonly reach: Reach;
  readonly runId: string | undefined;
  readonly stepId: string | undefined;
  /** Draws the page in detail instead, as the switch at its top does. */
  readonly onDetail: () => void;
}) {
  const { groupId, mayStart, onProblem } = reach;
  const search = listQuery(useLocation().search);
  const navigate = useNavigate();
  const { filter, onFilter } = useListAddress(RUNS_SORTABLE, NEWEST_FIRST);
  const load = useMemo(
    () => runsLoader(groupId, { filter, order: LAST_HAPPENED_ORDER }),
    [groupId, filter],
  );
  const page = usePagedResource(load);
  const reloadRuns = page.reload;
  const dayId = useId();
  const newRunHeading = useRef<HTMLHeadingElement>(null);
  const focusNewRun = useRef(false);
  const [newRunAsked, setNewRunAsked] = useState(0);
  const [startedHere, setStartedHere] = useState<string | null>(null);
  const [runShown, setRunShown] = useState(runId);
  // The run begun here takes the keyboard as it arrives, and never again once another is opened.
  if (runShown !== runId) {
    setRunShown(runId);
    if (runShown === startedHere) {
      setStartedHere(null);
    }
  }

  useEffect(() => onProblem(page.problem), [page.problem, onProblem]);

  // Once the run open has made way for what may start, where a run was open.
  useEffect(() => {
    if (focusNewRun.current && newRunHeading.current !== null) {
      focusNewRun.current = false;
      newRunHeading.current.focus();
    }
  }, [runId, newRunAsked]);

  const onReadable = useCallback(
    (started: StartedRun) => {
      reloadRuns();
      setStartedHere(started.runId);
      void navigate(`${runHref(groupId, started.runId)}${search}`);
    },
    [reloadRuns, navigate, groupId, search],
  );

  return (
    <Box sx={CONVERSATION_SX}>
      <Box role="region" aria-label={say("work.theRuns")} sx={LEADING_SX}>
        {mayStart ? (
          <Button
            variant="contained"
            component={RouterLink}
            to={{ pathname: groupPage(groupId, WORK_ITEM.segment), search }}
            onClick={(event: MouseEvent) => {
              if (
                event.button !== 0 ||
                event.metaKey ||
                event.ctrlKey ||
                event.shiftKey ||
                event.altKey
              ) {
                return;
              }
              focusNewRun.current = true;
              if (runId === undefined) {
                event.preventDefault();
                setNewRunAsked((asked) => asked + 1);
              }
            }}
            sx={NEW_RUN_SX}
          >
            {say("work.newRun")}
          </Button>
        ) : null}
        <FilterField
          label={say("work.filter")}
          value={filter}
          onChange={onFilter}
        />
        {page.problem === null ? null : <ProblemView problem={page.problem} />}
        {byDay(page.items, (row) => row.lastHappenedAt, new Date()).map(
          ({ day, rows }) => (
            <div key={day}>
              <Typography
                id={`${dayId}-${day}`}
                variant="overline"
                component="h2"
              >
                {say(DAYS[day])}
              </Typography>
              <NavigableList
                items={rows}
                keyOf={runIdOf}
                pathOf={(row) => `${runHref(groupId, row.runId)}${search}`}
                isCurrent={(row) => row.runId === runId}
                labelledBy={`${dayId}-${day}`}
                title={(row) => isolatedInText(row.name)}
                detail={(row) =>
                  say("work.line", {
                    workflow: isolatedInText(row.workflow.name),
                    where: listedWhereSaid(row),
                  })
                }
              />
            </div>
          ),
        )}
        <ListStatus
          page={page}
          empty={emptySaid(page.answered?.reading, filter)}
        />
        <PagingControls page={page} />
      </Box>
      <Box sx={REST_SX}>
        {runId === undefined ? (
          <NewRun
            reach={reach}
            listsAny={
              page.answered !== null && page.answered.reading !== "none"
            }
            headingRef={newRunHeading}
            onReadable={onReadable}
          />
        ) : stepId === undefined ? (
          <RunPage
            key={runId}
            drawing="conversation"
            onDetail={onDetail}
            focusOnArrival={runId === startedHere}
            onActed={reloadRuns}
          />
        ) : (
          <StepPage key={`${runId}/${stepId}`} onActed={reloadRuns} />
        )}
      </Box>
    </Box>
  );
}

/**
 * Where no run is open: what a run may be started of, for one who may start one, or where to open one, where the
 * server lists the reader any run to open.
 */
function NewRun({
  reach,
  listsAny,
  headingRef,
  onReadable,
}: {
  readonly reach: Reach;
  readonly listsAny: boolean;
  readonly headingRef: RefObject<HTMLHeadingElement | null>;
  readonly onReadable: (started: StartedRun) => void;
}) {
  if (reach.mayStart) {
    return (
      <Offered reach={reach} headingRef={headingRef} onReadable={onReadable} />
    );
  }
  return listsAny ? (
    <Typography variant="body2" sx={QUIET_SX}>
      {say("work.openOne")}
    </Typography>
  ) : null;
}

/**
 * One card per workflow on offer, by name, each saying what it is for. Picking one draws beneath the cards what
 * its newest version takes, and a run begun there becomes the rest of the page for a reader who may read it.
 */
function Offered({
  reach,
  headingRef,
  onReadable,
}: {
  readonly reach: Reach;
  readonly headingRef: RefObject<HTMLHeadingElement | null>;
  readonly onReadable: (started: StartedRun) => void;
}) {
  const { groupId, groupKey, onProblem } = reach;
  const headingId = useId();
  const pickedHeadingId = useId();
  const pickedHeading = useRef<HTMLHeadingElement>(null);
  const load = useCallback(
    (signal: AbortSignal) => readOffered(groupId, signal),
    [groupId],
  );
  const read = useResource(load, NONE_OFFERED);
  const reloadOffered = read.reload;
  const [picked, setPicked] = useState<string | null>(null);
  const [begun, setBegun] = useState<number | null>(null);
  const [readAgain, setReadAgain] = useState(false);
  const workflow = read.value.find((each) => each.entryId === picked);

  useEffect(() => {
    if (picked !== null) {
      pickedHeading.current?.focus();
    }
  }, [picked]);

  // Read again without the workflow picked, whose form goes with the control pressed in it.
  const gone = readAgain && picked !== null && workflow === undefined;
  useEffect(() => {
    if (gone) {
      headingRef.current?.focus();
    }
  }, [gone, headingRef]);

  // The form goes with the control pressed in it, so the keyboard starts again at what may start.
  const putAway = () => {
    setPicked(null);
    headingRef.current?.focus();
  };
  const settled: StartSettled = {
    onStarted: (started) => {
      if (started.readable) {
        setPicked(null);
        onReadable(started);
      } else {
        putAway();
        setBegun(started.number);
      }
    },
    onNotOffered: () => {
      setReadAgain(true);
      reloadOffered();
    },
    onProblem,
  };

  return (
    <Box role="group" aria-labelledby={headingId}>
      <Typography
        ref={headingRef}
        id={headingId}
        tabIndex={-1}
        variant="h6"
        component="h2"
        sx={NEW_RUN_SX}
      >
        {say("work.aNewRun")}
      </Typography>
      <div role="status">
        {begun === null ? null : (
          <Notice severity="info">
            {say("work.startedUnreadable", {
              run: say("work.runKey", { key: groupKey, number: begun }),
            })}
          </Notice>
        )}
        {readAgain ? (
          <Notice severity="warning">{say("work.noLongerOffered")}</Notice>
        ) : null}
      </div>
      <Async
        read={read}
        rule={{ inGroup: "start_run" }}
        empty={(offered) =>
          offered.length === 0 ? say("work.nothingOffered") : null
        }
      >
        {(offered) => (
          <Box sx={CARDS_SX}>
            {offered.map((each) => (
              <Card
                key={each.entryId}
                variant="outlined"
                sx={each.entryId === picked ? PICKED_SX : undefined}
              >
                <CardActionArea
                  aria-pressed={each.entryId === picked}
                  onClick={() => {
                    setPicked(each.entryId);
                    setBegun(null);
                    setReadAgain(false);
                  }}
                >
                  <CardContent>
                    <Typography
                      variant="subtitle1"
                      component="span"
                      sx={{ display: "block" }}
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
                  </CardContent>
                </CardActionArea>
              </Card>
            ))}
          </Box>
        )}
      </Async>
      {workflow === undefined ? null : (
        <Box
          component="section"
          aria-labelledby={pickedHeadingId}
          sx={OPENED_SX}
        >
          <Typography
            ref={pickedHeading}
            id={pickedHeadingId}
            tabIndex={-1}
            variant="subtitle1"
            component="h3"
            sx={NEW_RUN_SX}
          >
            {isolatedInText(workflow.name)}
          </Typography>
          <StartHere
            key={workflow.entryId}
            groupId={groupId}
            workflow={workflow}
            settled={settled}
            onAbandon={putAway}
          />
        </Box>
      )}
    </Box>
  );
}

/**
 * Every run as a table sorted by one column, newest first until another is asked for; a row opens its run under
 * the table, which keeps its page, its order and the row that opened it. A run begun from the dialog opens there
 * too, for a reader who may read it.
 */
function InDetail({
  reach,
  runId,
  stepId,
  starting,
  onStartShut,
}: {
  readonly reach: Reach;
  readonly runId: string | undefined;
  readonly stepId: string | undefined;
  readonly starting: boolean;
  readonly onStartShut: () => void;
}) {
  const { groupId, groupKey, onProblem } = reach;
  const search = listQuery(useLocation().search);
  const navigate = useNavigate();
  const { order, sort, onOrder } = useListAddress(RUNS_SORTABLE, NEWEST_FIRST);
  const load = useMemo(
    () => runsLoader(groupId, { filter: "", order: sort }),
    [groupId, sort],
  );
  const page = usePagedResource(load);
  const columns = useMemo(() => runColumns(groupKey), [groupKey]);
  const [begun, setBegun] = useState<number | null>(null);

  useEffect(() => onProblem(page.problem), [page.problem, onProblem]);

  const settled = {
    onStarted: (started: StartedRun) => {
      onStartShut();
      if (started.readable) {
        setBegun(null);
        page.reload();
        void navigate(`${runHref(groupId, started.runId)}${search}`);
      } else {
        setBegun(started.number);
      }
    },
    onProblem,
  };

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
      </div>
      {page.problem === null ? null : <ProblemView problem={page.problem} />}
      <SortableTable
        label={say("work.whatIsRunning")}
        columns={columns}
        order={order}
        onOrder={onOrder}
        page={page}
        keyOf={runIdOf}
        picked={runId ?? null}
        hrefOf={(row) => `${runHref(groupId, row.runId)}${search}`}
        empty={emptySaid(page.answered?.reading, "")}
        quietWhileAnswered
      />
      {runId === undefined ? null : (
        <Box sx={OPENED_SX}>
          {stepId === undefined ? (
            <RunPage key={runId} drawing="detail" onActed={page.reload} />
          ) : (
            <StepPage key={`${runId}/${stepId}`} onActed={page.reload} />
          )}
        </Box>
      )}
      {starting ? (
        <StartDialog groupId={groupId} onShut={onStartShut} settled={settled} />
      ) : null}
    </>
  );
}

/** What the list says while it reads with nothing answered yet, and where a settled first page found nothing. */
function ListStatus({
  page,
  empty,
}: {
  readonly page: PagedResource<RunRow> & { readonly answered: object | null };
  readonly empty: string;
}) {
  // A read again after an act keeps the rows it replaces, and saying it reads would be news of nothing.
  return (
    <Box role="status" sx={STATUS_SX}>
      {page.loading && page.answered === null ? (
        <Typography variant="body2" sx={QUIET_SX}>
          {say("read.pending")}
        </Typography>
      ) : page.problem === null &&
        page.items.length === 0 &&
        page.onFirstPage ? (
        <Notice severity="info">{empty}</Notice>
      ) : null}
    </Box>
  );
}

/** Said as the server read the runs, so it never contradicts what it read: none it may, none of theirs, or none. */
function emptySaid(reading: RunReading | undefined, filter: string): string {
  if (reading === "none") {
    return say("work.noneReadable");
  }
  if (reading === "own") {
    return say(filter === "" ? "work.noOwnRuns" : "work.noOwnMatch");
  }
  return say(filter === "" ? "work.noRuns" : "work.noMatch");
}

function runIdOf(row: RunRow): string {
  return row.runId;
}
