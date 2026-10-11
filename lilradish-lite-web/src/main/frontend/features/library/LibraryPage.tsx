import { useEffect, useMemo } from "react";
import { useNavigate } from "react-router";

import {
  libraryLoader,
  type Entry,
  type EntryRow,
} from "../../api/groups/{groupId}/{kind}";
import { mayIn, type GroupStanding } from "../../api/standing";
import { ProblemView } from "../../app/ProblemView";
import { movesTheReader } from "../../app/refusal";
import { useGroup } from "../../app/standing/RequireGroup";
import { useStandingRead } from "../../app/standing/StandingContext";
import { say } from "../../i18n/app";
import { FilterField } from "../../lib/collection/FilterField";
import { SortableTable } from "../../lib/collection/SortableTable";
import { useListAddress } from "../../lib/collection/useListAddress";
import { isolatedInText } from "../../lib/direction/isolated";
import { PageHeading } from "../../lib/heading/PageHeading";
import { useAction } from "../../lib/request/useAction";
import { usePagedResource } from "../../lib/request/usePagedResource";
import { entryHref, JUST_MADE } from "./entryAddress";
import { BY_NAME, LIBRARY_SORTABLE, libraryColumns } from "./libraryColumns";
import type { LibraryKind } from "./libraryKinds";
import { NewEntry } from "./NewEntry";

/**
 * A group's entries of one kind. Drawn under any group the reader is in,
 * which the route decides; its New button only for a reader who may write an
 * entry there.
 *
 * Filter and sort live in the address; a cursor never does. A row opens the
 * entry's own page, on its newest version, and so does an entry just made.
 *
 * A refusal saying the reader may no longer do what they asked, or is in the
 * group no longer, reads the standing again, which moves the frame and the
 * page off whatever it no longer reaches.
 */
export function LibraryPage({ of }: { readonly of: LibraryKind }) {
  const group = useGroup();
  return (
    <LibraryForGroup
      key={`${group.groupId}:${of.kind}`}
      group={group}
      of={of}
    />
  );
}

function LibraryForGroup({
  group,
  of,
}: {
  readonly group: GroupStanding;
  readonly of: LibraryKind;
}) {
  const reloadStanding = useStandingRead().reload;
  const { groupId } = group;
  const navigate = useNavigate();
  const mayMake = mayIn(group, "author_entry");
  const { filter, order, sort, onFilter, onOrder } = useListAddress(
    LIBRARY_SORTABLE,
    BY_NAME,
  );
  const columns = useMemo(() => libraryColumns(), []);

  const load = useMemo(
    () => libraryLoader(groupId, of.kind, { filter, order: sort }),
    [groupId, of.kind, filter, sort],
  );
  const page = usePagedResource(load);

  useEffect(() => {
    if (page.problem !== null && movesTheReader(page.problem)) {
      reloadStanding();
    }
  }, [page.problem, reloadStanding]);

  const making = useAction<Entry>(
    (made) =>
      navigate(entryHref(group, of, made.entryId), {
        state: JUST_MADE,
      }),
    (problem) => {
      if (movesTheReader(problem)) {
        reloadStanding();
      }
    },
  );

  return (
    <>
      <PageHeading
        title={say(of.heading, { group: isolatedInText(group.name) })}
        actions={
          mayMake ? (
            <NewEntry groupId={groupId} of={of} making={making} />
          ) : null
        }
      />
      <FilterField
        label={say("library.filter")}
        value={filter}
        onChange={onFilter}
      />
      {page.problem === null ? null : <ProblemView problem={page.problem} />}
      <SortableTable
        label={say(of.label)}
        columns={columns}
        order={order}
        onOrder={onOrder}
        page={page}
        keyOf={entryIdOf}
        picked={null}
        hrefOf={(row) => entryHref(group, of, row.entryId)}
        linkNameOf={(row) => row.name}
        linkDescriptionOf={(row) => row.purpose ?? say("entry.saysNothing")}
        empty={say(filter === "" ? of.empty : "library.noMatch")}
      />
    </>
  );
}

function entryIdOf(row: EntryRow): string {
  return row.entryId;
}
