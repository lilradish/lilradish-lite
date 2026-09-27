import { useCallback, useEffect, useMemo, useState } from "react";
import { useLocation, useNavigate, useParams } from "react-router";

import {
  membersLoader,
  type Member,
  type MemberPanel,
} from "../../api/groups/{groupId}/members";
import {
  readMember,
  refusedForWhatMoved,
} from "../../api/groups/{groupId}/members/{subjectId}";
import { mayIn } from "../../api/standing";
import { Async } from "../../app/Async";
import { groupPage, MEMBERS_ITEM } from "../../app/destinations";
import { ProblemView } from "../../app/ProblemView";
import { movesTheReader, refusalSentence } from "../../app/refusal";
import type { Rule } from "../../app/standing/actRules";
import { useStandingRead } from "../../app/standing/StandingContext";
import { say, type MessageId } from "../../i18n/app";
import { FilterField } from "../../lib/collection/FilterField";
import { RowPanel } from "../../lib/collection/RowPanel";
import { SortableTable } from "../../lib/collection/SortableTable";
import { useListAddress } from "../../lib/collection/useListAddress";
import { usePickedRow } from "../../lib/collection/usePickedRow";
import { isolatedInText } from "../../lib/direction/isolated";
import { PageHeading } from "../../lib/heading/PageHeading";
import type { Severity } from "../../lib/notice/Notice";
import { StatusLine, type Line } from "../../lib/notice/StatusLine";
import { useAction } from "../../lib/request/useAction";
import { usePagedResource } from "../../lib/request/usePagedResource";
import { nameOrNumber } from "../../lib/text/names";
import { BringSomebodyIn } from "./BringSomebodyIn";
import type { Asked, Outcome } from "./changes";
import { CurrencyPicker } from "./CurrencyPicker";
import {
  BY_USER_NUMBER,
  MEMBER_SORTABLE,
  memberColumns,
} from "./memberColumns";
import { RolesPanel, type Refused } from "./RolesPanel";

const MEMBERS = MEMBERS_ITEM.segment;

const SAID_SX = { mb: 1 };

/** What reading the members takes. */
const READS: Rule = { inGroup: "read_membership" };

/** What the page says it has done, or what was refused once its member was out of view. */
interface Said extends Line {
  readonly severity: Extract<Severity, "info" | "error">;
}

/** What each change, refused, is said as once its member is out of view. */
const NOT_DONE = {
  removal: "member.notTakenOut",
  roles: "member.rolesUnchanged",
} satisfies Record<Refused["place"], MessageId>;

/**
 * One group's members, and the one of them the address names. Drawn only
 * under a group the reader may see the members of, which the route decides.
 *
 * Filter and sort live in the query and replace the entry; a cursor never
 * does. The member picked is read on their own, so a filter leaves them
 * picked. Every change runs under one action the page owns, so it outlives
 * the panel and the dialog it was asked from, and the list is read again for
 * each, as is the reader's standing, which a change to their own roles moves.
 *
 * A refusal saying the reader may no longer do what they asked, or is in the
 * group no longer, reads the standing again, which moves the frame and the
 * page off whatever it no longer reaches. One saying the group itself moved
 * since it was read reads the list again, and the member refused, so no
 * control is left offering what was just refused.
 */
export function MembersPage() {
  const standingRead = useStandingRead();
  const reloadStanding = standingRead.reload;
  const { groupId = "", subjectId } = useParams();
  const { search } = useLocation();
  const navigate = useNavigate();
  const group = standingRead.value.groups.find(
    (each) => each.groupId === groupId,
  );
  const mayChange = group !== undefined && mayIn(group, "change_membership");
  const { filter, order, sort, onFilter, onOrder } = useListAddress(
    MEMBER_SORTABLE,
    BY_USER_NUMBER,
  );
  const columns = useMemo(() => memberColumns(), []);

  const load = useMemo(
    () => membersLoader(groupId, { filter, order: sort }),
    [groupId, filter, sort],
  );
  const page = usePagedResource(load);
  const reloadList = page.reload;
  const readThere = useCallback(
    (subject: string, signal: AbortSignal) =>
      readMember(groupId, subject, signal),
    [groupId],
  );
  const member = usePickedRow(subjectId, readThere, subjectIdOf);
  const [said, setSaid] = useState<Said | null>(null);
  // The line saying somebody is in, which the panel opening on them answers
  // as well, saying it itself where it covers the page's own: the reader
  // shutting that panel has heard all it had to say.
  const [answeredByPanel, setAnsweredByPanel] = useState<Said | null>(null);
  const [asked, setAsked] = useState<Asked | null>(null);

  const listHref = groupPage(groupId, MEMBERS) + search;

  useEffect(() => {
    for (const problem of [page.problem, member.read.problem]) {
      if (problem !== null && movesTheReader(problem)) {
        reloadStanding();
      }
    }
  }, [page.problem, member.read.problem, reloadStanding]);

  const changing = useAction<Outcome>(
    (outcome) => {
      reloadList();
      reloadStanding();
      if ("changed" in outcome) {
        if (outcome.changed.subjectId === subjectId) {
          // A read of them already out began before this answer, and would
          // land over it; one begun now lands after.
          if (member.read.loading) {
            member.reread();
          } else {
            member.answered(outcome.changed);
          }
        }
      } else if ("removed" in outcome) {
        setSaid({
          severity: "info",
          words: say("member.out", { name: nameOf(outcome.removed) }),
        });
        if (outcome.removed.subjectId === subjectId) {
          // Replaced rather than pushed: the way back would lead to somebody gone.
          navigate(listHref, { replace: true });
        }
      } else {
        member.seed(outcome.broughtIn);
        const line: Said = {
          severity: "info",
          words: say("member.in", { name: nameOf(outcome.broughtIn) }),
        };
        setAnsweredByPanel(line);
        setSaid(line);
        if (outcome.broughtIn.subjectId !== subjectId) {
          navigate(memberHref(groupId, outcome.broughtIn.subjectId, search));
        }
      }
    },
    (problem) => {
      if (movesTheReader(problem)) {
        reloadStanding();
      }
      const inView =
        asked !== null &&
        asked.place !== "bringIn" &&
        asked.of.subjectId === subjectId;
      if (refusedForWhatMoved(problem)) {
        reloadList();
        if (inView) {
          member.reread();
        }
      }
      if (asked === null || asked.place === "bringIn" || inView) {
        return;
      }
      const { place, of } = asked;
      setSaid({
        severity: "error",
        words: say(NOT_DONE[place], {
          name: isolatedInText(nameOrNumber(of)),
          refusal: refusalSentence(problem, { inGroup: "change_membership" }),
        }),
      });
    },
  );

  const refused: Refused | null =
    changing.problem !== null &&
    asked !== null &&
    asked.place !== "bringIn" &&
    asked.of.subjectId === subjectId
      ? { place: asked.place, problem: changing.problem }
      : null;

  return (
    <>
      <PageHeading
        title={
          group === undefined
            ? say(MEMBERS_ITEM.label)
            : say("members.heading", { group: isolatedInText(group.name) })
        }
        actions={
          <>
            <CurrencyPicker
              // Read again as the right to change it moves, which is what the currencies offered follow.
              key={String(mayChange)}
              groupId={groupId}
              onChosen={(currency) =>
                setSaid({
                  severity: "info",
                  words: say("currency.chosen", { currency }),
                })
              }
              onMoved={reloadStanding}
            />
            {mayChange ? (
              <BringSomebodyIn
                groupId={groupId}
                changing={changing}
                refused={asked?.place === "bringIn" ? changing.problem : null}
                onAsk={setAsked}
                onMoved={reloadStanding}
              />
            ) : null}
          </>
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
        title={titleOf(member.read.value)}
        listLabel={say("members.label")}
        saidOver={said === answeredByPanel ? said : null}
        list={
          <>
            {page.problem === null ? null : (
              <ProblemView problem={page.problem} rule={READS} />
            )}
            <SortableTable
              label={say("members.label")}
              columns={columns}
              order={order}
              onOrder={onOrder}
              page={page}
              keyOf={subjectIdOf}
              picked={subjectId ?? null}
              hrefOf={(row) => memberHref(groupId, row.subjectId, search)}
              empty={say(filter === "" ? "members.empty" : "members.noMatch")}
            />
          </>
        }
      >
        <Async read={member.read} rule={READS} empty={() => null}>
          {(panel) =>
            panel === null ? null : (
              <RolesPanel
                key={panel.subjectId}
                groupId={groupId}
                member={panel}
                changing={changing}
                waiting={member.read.loading}
                mayChange={mayChange}
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

function nameOf(member: MemberPanel): string {
  return isolatedInText(nameOrNumber(member));
}

function titleOf(member: MemberPanel | null): string {
  return member === null ? say("member.heading") : nameOrNumber(member);
}

function memberHref(
  groupId: string,
  subjectId: string,
  search: string,
): string {
  return `${groupPage(groupId, MEMBERS)}/${encodeURIComponent(subjectId)}${search}`;
}

function subjectIdOf(member: Member): string {
  return member.subjectId;
}
