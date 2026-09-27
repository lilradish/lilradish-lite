import { useState } from "react";

import { startEntry, type Entry } from "../../api/groups/{groupId}/{kind}";
import { say } from "../../i18n/app";
import { Press } from "../../lib/action/Press";
import type { Action } from "../../lib/request/useAction";
import { DescribeEntry } from "./DescribeEntry";
import type { LibraryKind } from "./libraryKinds";

const NOTHING_YET = { name: "" };

/**
 * The control that starts an entry of the page's kind, and the dialog it
 * opens: a name, and what it is for. What it makes is the entry and its
 * first version, a draft. While the start is out, the control opens nothing.
 */
export function StartOne({
  groupId,
  of,
  starting,
}: {
  readonly groupId: string;
  readonly of: LibraryKind;
  readonly starting: Action<Entry>;
}) {
  const [open, setOpen] = useState(false);
  // Each opening starts from nothing typed, even one begun while the last is still leaving.
  const [opening, setOpening] = useState(0);

  return (
    <>
      <Press
        variant="contained"
        unavailable={starting.running}
        onPress={() => {
          setOpening((count) => count + 1);
          setOpen(true);
        }}
      >
        {say(of.start)}
      </Press>
      <DescribeEntry
        key={opening}
        open={open}
        title={say(of.start)}
        actLabel={say("entry.start")}
        underway="entry.startUnderway"
        rule="author_entry"
        described={NOTHING_YET}
        send={(described, signal) =>
          startEntry(groupId, of.kind, described, signal)
        }
        action={starting}
        onShut={() => setOpen(false)}
      />
    </>
  );
}
