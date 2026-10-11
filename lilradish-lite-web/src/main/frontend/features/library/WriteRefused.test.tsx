import { ThemeProvider } from "@mui/material/styles";
import { render, screen } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import type { Problem } from "../../api/problem";
import { theme } from "../../lib/theme/theme";
import { WriteRefused } from "./WriteRefused";

const AFRESH_HINT =
  "What you typed is kept until you load the saved draft. Loading it shows the draft as it is saved now and drops anything here that was not saved.";

const LOAD = "Load the saved draft";

const ASKING_AGAIN = [
  ["sent too slowly", "BODY_SENT_TOO_SLOWLY", 408],
  ["turned away while busy", "SERVICE_BUSY", 503],
] as const;

function refusing(problem: Problem, waiting = false, settled = false) {
  const readAfresh = vi.fn<() => void>();
  render(
    <ThemeProvider theme={theme}>
      <WriteRefused
        problem={problem}
        rule="author_entry"
        waiting={waiting}
        onReadAfresh={readAfresh}
        settled={settled}
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

    await userEvent.click(screen.getByRole("button", { name: LOAD }));

    expect(readAfresh).toHaveBeenCalledTimes(1);
  });

  /** Drawing the parts anew would lose the answer of the write still out. */
  it("holds loading the saved draft while another write of the host's is out, and reads nothing afresh", async () => {
    const readAfresh = refusing(
      { status: 409, code: "DRAFT_WRITTEN_SINCE_READ" },
      true,
    );
    const reload = screen.getByRole("button", { name: LOAD });

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
      "A group's entries are made, written and submitted only by a role in it that may write one.",
    ],
  ])("says %s and offers nothing to read afresh", (_case, problem, said) => {
    refusing(problem);

    expect(screen.getByRole("alert")).toHaveTextContent(said);
    expect(screen.queryByRole("button", { name: LOAD })).toBeNull();
    expect(screen.queryByText(AFRESH_HINT)).toBeNull();
  });

  it("says what happened to a draft written since it was read once nothing is left to save, offering no load and no way on to saving", () => {
    refusing({ status: 409, code: "DRAFT_WRITTEN_SINCE_READ" }, false, true);

    expect(screen.getByRole("alert")).toHaveTextContent(
      "Somebody changed this draft since it was read; nothing was saved.",
    );
    expect(screen.queryByRole("button", { name: LOAD })).toBeNull();
    expect(screen.queryByText(AFRESH_HINT)).toBeNull();
  });

  it.each(ASKING_AGAIN)(
    "says nothing of a write %s, whose words ask for it again, once nothing is left to save",
    (_case, code, status) => {
      refusing({ status, code }, false, true);

      expect(screen.queryByRole("alert")).toBeNull();
      expect(screen.queryByRole("button", { name: LOAD })).toBeNull();
    },
  );

  it.each(ASKING_AGAIN)(
    "says a write %s, whose words ask for it again, while something is left to save",
    (_case, code, status) => {
      refusing({ status, code });

      expect(screen.getByRole("alert")).toHaveTextContent(/again/);
      expect(screen.queryByRole("button", { name: LOAD })).toBeNull();
    },
  );

  it("offers nothing to read afresh of a draft written since it was read where the page offers that itself", () => {
    render(
      <ThemeProvider theme={theme}>
        <WriteRefused
          problem={{ status: 409, code: "DRAFT_WRITTEN_SINCE_READ" }}
          rule="author_entry"
          waiting={false}
        />
      </ThemeProvider>,
    );

    expect(screen.getByRole("alert")).toHaveTextContent(
      "Somebody changed this draft since it was read; nothing was saved.",
    );
    expect(screen.queryByRole("button", { name: LOAD })).toBeNull();
    expect(screen.queryByText(AFRESH_HINT)).toBeNull();
  });
});
