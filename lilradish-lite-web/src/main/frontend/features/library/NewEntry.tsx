import { useState } from "react";

import { makeEntry, type Entry } from "../../api/groups/{groupId}/{kind}";
import { say } from "../../i18n/app";
import { Press } from "../../lib/action/Press";
import type { Action } from "../../lib/request/useAction";
import { DescribeEntry } from "./DescribeEntry";
import type { LibraryKind } from "./libraryKinds";

const NOTHING_YET = { name: "" };

/**
 * The control that makes an entry of the page's kind, named for that kind, and the dialog it opens: a name, and
 * what it is for. What it makes is the entry and its first version, a draft. While that is out, the control opens
 * nothing.
 */
export function NewEntry({
  groupId,
  of,
  making,
}: {
  readonly groupId: string;
  readonly of: LibraryKind;
  readonly making: Action<Entry>;
}) {
  const [open, setOpen] = useState(false);
  // Each opening starts from nothing typed, even one begun while the last is still leaving.
  const [opening, setOpening] = useState(0);

  return (
    <>
      <Press
        variant="contained"
        unavailable={making.running}
        onPress={() => {
          setOpening((count) => count + 1);
          setOpen(true);
        }}
      >
        {say(of.newButton)}
      </Press>
      <DescribeEntry
        key={opening}
        open={open}
        title={say(of.newButton)}
        actLabel={say("entry.create")}
        underway="entry.createUnderway"
        rule="author_entry"
        described={NOTHING_YET}
        send={(described, signal) =>
          makeEntry(groupId, of.kind, described, signal)
        }
        action={making}
        onShut={() => setOpen(false)}
      />
    </>
  );
}
