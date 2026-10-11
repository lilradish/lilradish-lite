import { useLayoutEffect, useMemo, useRef, useState } from "react";

import { groupsLoader, type RegisteredGroup } from "../../api/groups";
import { ProblemView } from "../../app/ProblemView";
import { refusesTheAct } from "../../app/refusal";
import { useStandingRead } from "../../app/standing/StandingContext";
import { say, type MessageId } from "../../i18n/app";
import { InlineEditableValue } from "../../lib/action/InlineEditableValue";
import { FilterField } from "../../lib/collection/FilterField";
import { SortableTable } from "../../lib/collection/SortableTable";
import { useListAddress } from "../../lib/collection/useListAddress";
import { isolatedInText } from "../../lib/direction/isolated";
import { PageHeading } from "../../lib/heading/PageHeading";
import { StatusLine, type Line } from "../../lib/notice/StatusLine";
import { useAction } from "../../lib/request/useAction";
import { usePagedResource } from "../../lib/request/usePagedResource";
import type { Outcome } from "./changes";
import { CreateAGroup } from "./CreateAGroup";
import { BY_NAME, GROUP_SORTABLE, groupColumns } from "./groupColumns";
import { RenameGroup } from "./RenameGroup";

/** Where the page is. No address names a group on it: the estate cannot enter one. */
export { GROUPS_PAGE } from "../../app/addresses";

const SAID_SX = { mb: 1 };

interface Renaming {
  readonly group: RegisteredGroup;
  readonly opening: number;
}

// The page owns changes so they outlive an opening; settlement rereads the list and standing.
export function GroupsPage({ title }: { readonly title: MessageId }) {
  const reloadStanding = useStandingRead().reload;
  const { filter, order, sort, onFilter, onOrder } = useListAddress(
    GROUP_SORTABLE,
    BY_NAME,
  );
  const [renaming, setRenaming] = useState<Renaming | null>(null);
  const [renameOpen, setRenameOpen] = useState(false);
  const readControls = useRef(new Map<string, HTMLButtonElement>());
  const returnFocus = useRef<string | null>(null);

  const load = useMemo(
    () => groupsLoader({ filter, order: sort }),
    [filter, sort],
  );
  const page = usePagedResource(load);
  const reloadList = page.reload;
  const [said, setSaid] = useState<Line | null>(null);

  const changing = useAction<Outcome>(
    (outcome) => {
      reloadList();
      // The reader may be in the group made or renamed, and the sidebar names it.
      reloadStanding();
      setSaid(
        "created" in outcome
          ? {
              severity: "info",
              words: say("group.created", {
                name: isolatedInText(outcome.created.name),
              }),
            }
          : {
              severity: "info",
              words: say("group.renamed", {
                name: isolatedInText(outcome.renamed.name),
              }),
            },
      );
    },
    (problem) => {
      if (refusesTheAct(problem)) {
        reloadStanding();
      }
    },
  );

  const running = changing.running;
  useLayoutEffect(() => {
    if (!renameOpen && !running && returnFocus.current !== null) {
      if (document.activeElement === document.body)
        readControls.current.get(returnFocus.current)?.focus();
      returnFocus.current = null;
    }
  }, [renameOpen, running]);
  const columns = groupColumns((group) => (
    <InlineEditableValue
      ref={(control) => {
        if (control === null) readControls.current.delete(group.groupId);
        else readControls.current.set(group.groupId, control);
      }}
      value={group.name}
      disabled={running}
      onEdit={() => {
        setRenaming((last) => ({
          group,
          opening: (last?.opening ?? 0) + 1,
        }));
        setRenameOpen(true);
      }}
    />
  )).map((column) =>
    "sortKey" in column && column.sortKey === "name"
      ? {
          ...column,
          cell: (group: RegisteredGroup) =>
            renaming?.group.groupId === group.groupId && renameOpen ? (
              <RenameGroup
                key={renaming.opening}
                group={group}
                open
                changing={changing}
                onShut={(restoring = false) => {
                  returnFocus.current = restoring ? group.groupId : null;
                  setRenameOpen(false);
                }}
              />
            ) : (
              column.cell(group)
            ),
        }
      : column,
  );

  return (
    <>
      <PageHeading
        title={say(title)}
        // Drawn for every reader: the route opens this page only to one who keeps the register.
        actions={<CreateAGroup changing={changing} />}
      />
      <FilterField
        label={say("register.filter")}
        value={filter}
        onChange={onFilter}
      />
      <StatusLine said={said} sx={SAID_SX} />
      {page.problem === null ? null : (
        <ProblemView
          problem={page.problem}
          rule={{ act: "keep_group_register" }}
        />
      )}
      <SortableTable
        label={say("register.label")}
        columns={columns}
        order={order}
        onOrder={onOrder}
        page={page}
        keyOf={groupIdOf}
        empty={say(filter === "" ? "register.empty" : "register.noMatch")}
      />
    </>
  );
}

function groupIdOf(group: RegisteredGroup): string {
  return group.groupId;
}
