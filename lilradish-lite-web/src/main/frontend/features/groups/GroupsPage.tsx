import { useMemo, useState } from "react";

import { groupsLoader, type RegisteredGroup } from "../../api/groups";
import { ProblemView } from "../../app/ProblemView";
import { refusesTheAct } from "../../app/refusal";
import { useStandingRead } from "../../app/standing/StandingContext";
import { say, type MessageId } from "../../i18n/app";
import { Press } from "../../lib/action/Press";
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
export const GROUPS_PAGE = "/system/groups";

const SAID_SX = { mb: 1 };

/** Which group a rename was opened on, and which opening it is. */
interface Renaming {
  readonly group: RegisteredGroup;
  readonly opening: number;
}

/**
 * The register of groups. Filter and sort live in the address; a cursor never
 * does. A row opens nothing, and its Rename is the one thing on it that acts.
 *
 * Creating and renaming run under one action the page owns, so each outlives
 * the dialog it was asked from, and the list and the reader's standing are
 * read again for each. Each dialog says its own refusal; one refusing the act
 * itself reads the reader's standing again, which has moved.
 */
export function GroupsPage({ title }: { readonly title: MessageId }) {
  const reloadStanding = useStandingRead().reload;
  const { filter, order, sort, onFilter, onOrder } = useListAddress(
    GROUP_SORTABLE,
    BY_NAME,
  );
  const [renaming, setRenaming] = useState<Renaming | null>(null);
  const [renameOpen, setRenameOpen] = useState(false);

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
  const columns = useMemo(
    () =>
      groupColumns((group) => (
        <Press
          label={say("group.renameOne", { name: isolatedInText(group.name) })}
          unavailable={running}
          onPress={() => {
            setRenaming((last) => ({
              group,
              opening: (last?.opening ?? 0) + 1,
            }));
            setRenameOpen(true);
          }}
        >
          {say("group.rename")}
        </Press>
      )),
    [running],
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
      {renaming === null ? null : (
        <RenameGroup
          key={renaming.opening}
          group={renaming.group}
          open={renameOpen}
          changing={changing}
          onShut={() => setRenameOpen(false)}
        />
      )}
    </>
  );
}

function groupIdOf(group: RegisteredGroup): string {
  return group.groupId;
}
