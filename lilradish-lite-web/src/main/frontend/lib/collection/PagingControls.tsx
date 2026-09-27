import Stack from "@mui/material/Stack";
import { useState } from "react";

import { say } from "../../i18n/lib";
import { Press } from "../action/Press";
import type { PagedResource } from "../request/usePagedResource";

/**
 * Forward, or back to the start. No page number and no total: both are offset
 * thinking, and a control that shows one is a control that needs it to exist.
 *
 * Nothing at all where the collection begins and ends on the same page, because
 * what would be drawn there is a pair of controls that can never be pressed.
 * A read still out has not said so yet, so the last answer's word stands until
 * it does: the control just pressed is otherwise removed from under the keyboard.
 */
export function PagingControls({
  page,
}: {
  readonly page: PagedResource<unknown>;
}) {
  const alone = page.onFirstPage && !page.hasNext;
  const [shown, setShown] = useState(!alone);
  if (!page.loading && shown === alone) {
    setShown(!alone);
  }

  if (page.loading ? !shown : alone) {
    return null;
  }
  return (
    <Stack direction="row" spacing={1.5} sx={{ my: 2 }}>
      <Press unavailable={page.onFirstPage} onPress={page.first}>
        {say("paging.first")}
      </Press>
      <Press unavailable={!page.hasNext} onPress={page.next}>
        {say("paging.next")}
      </Press>
    </Stack>
  );
}
