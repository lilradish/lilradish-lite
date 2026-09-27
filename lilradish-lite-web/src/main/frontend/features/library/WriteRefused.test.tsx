import { ThemeProvider } from "@mui/material/styles";
import { render, screen } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import type { Problem } from "../../api/problem";
import { theme } from "../../lib/theme/theme";
import { WriteRefused } from "./WriteRefused";

const AFRESH_HINT =
  "What is typed here is kept until you reload. Reloading shows the draft as it is saved now, and drops whatever here was not saved.";

function refusing(problem: Problem, waiting = false) {
  const readAfresh = vi.fn<() => void>();
  render(
    <ThemeProvider theme={theme}>
      <WriteRefused
        problem={problem}
        rule="author_entry"
        waiting={waiting}
        onReadAfresh={readAfresh}
      />
    </ThemeProvider>,
  );
  return readAfresh;
}

describe("WriteRefused", () => {
  it("says a draft written since it was read so, keeps what is typed, and reads it afresh only when that is pressed", async () => {
    const readAfresh = refusing({
      status: 409,
      code: "DRAFT_WRITTEN_SINCE_READ",
    });

    expect(screen.getByRole("alert")).toHaveTextContent(
      "Somebody changed this draft since it was read; nothing was saved.",
    );
    expect(screen.getByText(AFRESH_HINT)).toBeInTheDocument();
    expect(readAfresh).not.toHaveBeenCalled();

    await userEvent.click(screen.getByRole("button", { name: "Reload" }));

    expect(readAfresh).toHaveBeenCalledTimes(1);
  });

  /** Drawing the parts anew would lose the answer of the write still out. */
  it("holds Reload while another write of the host's is out, and reads nothing afresh", async () => {
    const readAfresh = refusing(
      { status: 409, code: "DRAFT_WRITTEN_SINCE_READ" },
      true,
    );
    const reload = screen.getByRole("button", { name: "Reload" });

    reload.focus();
    await userEvent.keyboard("{Enter}");

    expect(reload).toHaveAttribute("aria-disabled", "true");
    expect(reload).toHaveFocus();
    expect(readAfresh).not.toHaveBeenCalled();
  });

  it.each([
    [
      "another refusal of the server's",
      { status: 409, code: "VERSION_STANDING_REFUSES" },
      "That version's standing does not admit this.",
    ],
    [
      "the same words minted here, where no server answered",
      { code: "DRAFT_WRITTEN_SINCE_READ" },
      "The server could not be reached.",
    ],
    [
      "the act refused, as the rule writing asks",
      { status: 403, code: "ACT_NOT_PERMITTED" },
      "A group's entries are started, written and submitted only by a role in it that may write one.",
    ],
  ])("says %s and offers nothing to read afresh", (_case, problem, said) => {
    refusing(problem);

    expect(screen.getByRole("alert")).toHaveTextContent(said);
    expect(screen.queryByRole("button", { name: "Reload" })).toBeNull();
    expect(screen.queryByText(AFRESH_HINT)).toBeNull();
  });
});
