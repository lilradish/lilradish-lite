import { useMemo, useState } from "react";
import { useLocation, useNavigate, useParams } from "react-router";

import type { Problem } from "../../api/problem";
import {
  peopleLoader,
  type PoolPerson,
  type PoolPersonPanel,
} from "../../api/pool/people";
import {
  readPerson,
  refusedForWhatStillHolds,
} from "../../api/pool/people/{subjectId}";
import { holds } from "../../api/standing";
import { Async } from "../../app/Async";
import { ProblemView } from "../../app/ProblemView";
import { refusalSentence, refusesTheAct } from "../../app/refusal";
import { useStandingRead } from "../../app/standing/StandingContext";
import { say, type MessageId } from "../../i18n/app";
import { FilterField } from "../../lib/collection/FilterField";
import { RowPanel } from "../../lib/collection/RowPanel";
import { SortableTable } from "../../lib/collection/SortableTable";
import { useListAddress } from "../../lib/collection/useListAddress";
import { isolatedInText } from "../../lib/direction/isolated";
import { PageHeading } from "../../lib/heading/PageHeading";
import type { Severity } from "../../lib/notice/Notice";
import { StatusLine, type Line } from "../../lib/notice/StatusLine";
import { useAction } from "../../lib/request/useAction";
import { usePagedResource } from "../../lib/request/usePagedResource";
import { nameOrNumber } from "../../lib/text/names";
import { AddSomebody } from "./AddSomebody";
import type { Asked, Outcome } from "./changes";
import { PersonPanel, type Refused } from "./PersonPanel";
import { BY_USER_NUMBER, POOL_SORTABLE, poolColumns } from "./poolColumns";
import { usePickedPerson } from "./usePickedPerson";
import { whatHasToGoFirst } from "./whatHasToGoFirst";

/** Where the page is, and what every address naming somebody on it begins with. */
export const PEOPLE_PAGE = "/system/people";

const SAID_SX = { mb: 1 };

/** What the page says it has done, or what was refused once its person was out of view. */
interface Said extends Line {
  readonly severity: Extract<Severity, "info" | "error">;
}

/** What each change, refused, is said as once its person is out of view. */
const NOT_DONE = {
  removal: "person.notTakenOut",
  roles: "person.rolesUnchanged",
} satisfies Record<Refused["place"], MessageId>;

/**
 * The pool, and the one person in it the address names.
 *
 * Filter and sort live in the query and replace the entry; a cursor never
 * does. The person picked is read on their own, so a filter leaves them picked.
 * Every change runs under one action the page owns, so it outlives the panel
 * and the dialog it was asked from, and the list is read again for each.
 */
export function PeoplePage({ title }: { readonly title: MessageId }) {
  const standingRead = useStandingRead();
  const standing = standingRead.value;
  const reloadStanding = standingRead.reload;
  const { subjectId } = useParams();
  const { search } = useLocation();
  const navigate = useNavigate();
  const { filter, order, sort, onFilter, onOrder } = useListAddress(
    POOL_SORTABLE,
    BY_USER_NUMBER,
  );
  const columns = useMemo(() => poolColumns(), []);

  const load = useMemo(
    () => peopleLoader({ filter, order: sort }),
    [filter, sort],
  );
  const page = usePagedResource(load);
  const reloadList = page.reload;
  const person = usePickedPerson(subjectId);
  const [said, setSaid] = useState<Said | null>(null);
  // The line saying somebody is in, which the panel opening on them answers
  // as well, saying it itself where it covers the page's own: the reader
  // shutting that panel has heard all it had to say.
  const [answeredByPanel, setAnsweredByPanel] = useState<Said | null>(null);
  const [asked, setAsked] = useState<Asked | null>(null);
  // A removal refused for what still holds somebody out of view names it only
  // once they are read again; taken as an action so leaving abandons the read.
  const tellingWhatHolds = useAction<Said>(setSaid);

  const listHref = PEOPLE_PAGE + search;

  const changing = useAction<Outcome>(
    (outcome) => {
      reloadList();
      if ("changed" in outcome) {
        reloadStanding();
        if (outcome.changed.subjectId === subjectId) {
          // A read of them already out began before this answer, and would
          // land over it; one begun now lands after.
          if (person.read.loading) {
            person.reread();
          } else {
            person.answered(outcome.changed);
          }
        }
      } else if ("removed" in outcome) {
        setSaid({
          severity: "info",
          words: say("person.out", { name: nameOf(outcome.removed) }),
        });
        if (outcome.removed.subjectId === subjectId) {
          // Replaced rather than pushed: the way back would lead to somebody gone.
          navigate(listHref, { replace: true });
        }
      } else {
        person.seed(outcome.broughtIn);
        const line: Said = {
          severity: "info",
          words: say("person.in", { name: nameOf(outcome.broughtIn) }),
        };
        setAnsweredByPanel(line);
        setSaid(line);
        if (outcome.broughtIn.subjectId !== subjectId) {
          navigate(personHref(outcome.broughtIn.subjectId, search));
        }
      }
    },
    (problem) => {
      if (refusesTheAct(problem)) {
        reloadStanding();
      }
      if (asked === null) {
        return;
      }
      if (asked.place === "bringIn") {
        return;
      }
      const stillHeld = refusedForWhatStillHolds(problem);
      if (stillHeld) {
        reloadList();
        if (asked.of.subjectId === subjectId) {
          person.reread();
        }
      }
      if (asked.of.subjectId === subjectId) {
        return;
      }
      const { place, of } = asked;
      const name = nameOrNumber(of);
      const plainly = refusedFor(place, name, refusalOf(problem, place));
      if (place === "removal" && stillHeld) {
        tellingWhatHolds.run((signal) =>
          readPerson(of.subjectId, signal).then(
            (now) => {
              const first = whatHasToGoFirst(now);
              return first === undefined
                ? plainly
                : refusedFor(place, name, first);
            },
            () => plainly,
          ),
        );
      } else {
        setSaid(plainly);
      }
    },
  );

  const refused: Refused | null =
    changing.problem !== null &&
    asked !== null &&
    asked.place !== "bringIn" &&
    asked.of.subjectId === subjectId
      ? { place: asked.place, of: asked.of, problem: changing.problem }
      : null;

  return (
    <>
      <PageHeading
        title={say(title)}
        // Drawn for every reader, as Remove from the pool is: the route opens
        // this page only to one who keeps the pool.
        actions={
          <AddSomebody
            changing={changing}
            refused={asked?.place === "bringIn" ? changing.problem : null}
            onAsk={setAsked}
          />
        }
      />
      <FilterField
        label={say("person.numberOrName")}
        value={filter}
        onChange={onFilter}
      />
      <StatusLine said={said} sx={SAID_SX} />
      <RowPanel
        open={subjectId !== undefined}
        onClose={() => {
          setSaid((now) => (now === answeredByPanel ? null : now));
          navigate(listHref);
        }}
        title={titleOf(person.read.value)}
        listLabel={say("pool.label")}
        saidOver={said === answeredByPanel ? said : null}
        list={
          <>
            {page.problem === null ? null : (
              <ProblemView problem={page.problem} rule={{ act: "keep_pool" }} />
            )}
            <SortableTable
              label={say("pool.label")}
              columns={columns}
              order={order}
              onOrder={onOrder}
              page={page}
              keyOf={subjectIdOf}
              picked={subjectId ?? null}
              hrefOf={(row) => personHref(row.subjectId, search)}
              empty={say(filter === "" ? "pool.empty" : "pool.noMatch")}
            />
          </>
        }
      >
        <Async
          read={person.read}
          rule={{ act: "keep_pool" }}
          empty={() => null}
        >
          {(panel) =>
            panel === null ? null : (
              <PersonPanel
                key={panel.subjectId}
                person={panel}
                changing={changing}
                waiting={person.read.loading}
                mayChangeRoles={holds(standing, "grant_estate_role")}
                refused={refused}
                onAsk={setAsked}
              />
            )
          }
        </Async>
      </RowPanel>
    </>
  );
}

function nameOf(person: PoolPersonPanel): string {
  return isolatedInText(nameOrNumber(person));
}

function refusedFor(
  place: Refused["place"],
  name: string,
  refusal: string,
): Said {
  return {
    severity: "error",
    words: say(NOT_DONE[place], { name: isolatedInText(name), refusal }),
  };
}

function refusalOf(problem: Problem, place: Refused["place"]) {
  return refusalSentence(problem, {
    act: place === "roles" ? "grant_estate_role" : "keep_pool",
  });
}

function titleOf(person: PoolPersonPanel | null): string {
  return person === null ? say("person.heading") : nameOrNumber(person);
}

function personHref(subjectId: string, search: string): string {
  return `${PEOPLE_PAGE}/${encodeURIComponent(subjectId)}${search}`;
}

function subjectIdOf(person: PoolPerson): string {
  return person.subjectId;
}
