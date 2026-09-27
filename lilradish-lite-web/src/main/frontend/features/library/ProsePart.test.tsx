import { ThemeProvider } from "@mui/material/styles";
import { render, screen, waitFor, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { useState, type ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";

import { RequestFailed } from "../../api/problem";
import type { MessageId } from "../../i18n/app";
import { useAction } from "../../lib/request/useAction";
import { theme } from "../../lib/theme/theme";
import { ProsePart, type ProseWords } from "./ProsePart";

const WORDS: ProseWords = {
  title: "referenceList.note",
  hint: "referenceList.noteHint",
  saysNothing: "referenceList.saysNothing",
  save: "referenceList.saveNote",
  unchanged: "referenceList.noteUnchanged",
  underway: "referenceList.underway",
};

const SAID = "Pick the narrowest.\n\tNever two.";

const OVERRIDE = String.fromCodePoint(0x202e);

function themed({ children }: { readonly children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

/**
 * The part as a host draws it, over the real action it is handed: what a save answers is drawn as what the
 * part says, and a refusal can take writing away, as the page reading the version again does.
 */
function writing(
  said: string | undefined,
  host: {
    readonly editable?: boolean;
    readonly waiting?: boolean;
    readonly focusOnArrival?: boolean;
    readonly answer?: (prose: string | null) => Promise<string | undefined>;
    readonly closeOnRefusal?: boolean;
  } = {},
) {
  const saved = vi.fn(
    host.answer ??
      ((prose: string | null) => Promise.resolve(prose ?? undefined)),
  );
  const readAfresh = vi.fn<() => void>();
  function Host() {
    const [held, setHeld] = useState(said);
    const [open, setOpen] = useState(host.editable ?? true);
    const saving = useAction<string | undefined>(setHeld, () =>
      setOpen(host.closeOnRefusal !== true && open),
    );
    return (
      <ProsePart
        words={WORDS}
        said={held}
        editable={open}
        waiting={host.waiting ?? false}
        refusalOf={(typed): MessageId | null =>
          typed.includes(OVERRIDE) ? "refusal.PROSE_DIRECTION_CONTROL" : null
        }
        rule="author_entry"
        action={saving}
        save={(prose) => saved(prose)}
        focusOnArrival={host.focusOnArrival ?? false}
        onReadAfresh={readAfresh}
      />
    );
  }
  render(<Host />, { wrapper: themed });
  return { saved, readAfresh };
}

function part(): HTMLElement {
  return screen.getByRole("region", { name: "The note" });
}

function box(): HTMLElement {
  return within(part()).getByRole("textbox", { name: "The note" });
}

function save(): HTMLElement {
  return within(part()).getByRole("button", { name: "Save the note" });
}

function heading(): HTMLElement {
  return screen.getByRole("heading", { level: 3, name: "The note" });
}

describe("ProsePart", () => {
  it("reads what it says, its lines kept, where it may not be written, and offers nothing to change", () => {
    writing(SAID, { editable: false });

    expect(within(part()).getByText(/^Pick the narrowest\./).textContent).toBe(
      SAID,
    );
    expect(part().querySelectorAll("input, textarea, button")).toHaveLength(0);
  });

  it("says it says nothing where it holds nothing and may not be written", () => {
    writing(undefined, { editable: false });

    expect(part()).toHaveTextContent(
      "It says nothing on choosing among its terms.",
    );
    expect(part().querySelectorAll("p[dir]")).toHaveLength(0);
  });

  it("saves what is typed, lines and all, and draws what the save answered", async () => {
    const { saved } = writing(SAID);

    await userEvent.clear(box());
    await userEvent.type(box(), "Pick one.{Enter}Never two.");
    await userEvent.click(save());

    expect(saved.mock.calls).toEqual([["Pick one.\nNever two."]]);
    await waitFor(() => expect(box()).toHaveValue("Pick one.\nNever two."));
  });

  it("saves what is emptied as none, rather than as empty", async () => {
    const { saved } = writing(SAID);

    await userEvent.clear(box());
    await userEvent.click(save());

    expect(saved.mock.calls).toEqual([[null]]);
    await waitFor(() =>
      expect(save()).toHaveAccessibleDescription("Change the note first."),
    );
  });

  it.each([
    ["unchanged", "", "Change the note first."],
    [
      "one the server would refuse",
      ` ${OVERRIDE}`,
      "What was written holds a direction control, which text sent to a model may not hold.",
    ],
  ])(
    "holds a save %s, saying why beside it, and sends nothing",
    async (_case, typed, why) => {
      const { saved } = writing(SAID);
      await userEvent.click(box());
      await userEvent.paste(typed);

      save().focus();
      await userEvent.keyboard("{Enter}");

      expect(save()).toHaveAttribute("aria-disabled", "true");
      expect(save()).toHaveAccessibleDescription(why);
      expect(saved).not.toHaveBeenCalled();
    },
  );

  it("says the hint under the box while what is typed would be taken, and the refusal in its place where not", async () => {
    writing(SAID);
    expect(box()).toHaveAccessibleDescription(
      "How to go about choosing among the terms at all. What is true of one term goes in what it means.",
    );

    await userEvent.click(box());
    await userEvent.paste(OVERRIDE);

    expect(box()).toHaveAccessibleDescription(
      "What was written holds a direction control, which text sent to a model may not hold.",
    );
  });

  it("holds the save while another write of the host's is out, which it would be refused beside, and sends nothing", async () => {
    const { saved } = writing(SAID, { waiting: true });
    await userEvent.type(box(), " Now.");

    save().focus();
    await userEvent.keyboard("{Enter}");

    expect(save()).toHaveAttribute("aria-disabled", "true");
    expect(saved).not.toHaveBeenCalled();
  });

  it("offers to read afresh where the draft was written since it was read, keeping what was typed until that is pressed", async () => {
    const { readAfresh } = writing(SAID, {
      answer: () =>
        Promise.reject(
          new RequestFailed({ status: 409, code: "DRAFT_WRITTEN_SINCE_READ" }),
        ),
    });
    await userEvent.type(box(), " Now.");
    await userEvent.click(save());
    expect(await within(part()).findByRole("alert")).toHaveTextContent(
      "Somebody changed this draft since it was read; nothing was saved.",
    );
    expect(box()).toHaveValue(`${SAID} Now.`);
    expect(readAfresh).not.toHaveBeenCalled();

    await userEvent.click(
      within(part()).getByRole("button", { name: "Reload" }),
    );

    expect(readAfresh).toHaveBeenCalledTimes(1);
  });

  it.each([
    [true, "is on its heading"],
    [false, "is left where it was"],
  ])(
    "where the part is drawn afresh by a press inside it (%s), the keyboard %s",
    (arriving) => {
      writing(SAID, { focusOnArrival: arriving });

      expect(heading() === document.activeElement).toBe(arriving);
    },
  );

  /** The page may read the version again after a refusal and find it no longer written here. */
  it("keeps a refused save said once the part may no longer be written, and puts the keyboard on its heading", async () => {
    writing(SAID, {
      answer: () =>
        Promise.reject(
          new RequestFailed({ status: 409, code: "VERSION_STANDING_REFUSES" }),
        ),
      closeOnRefusal: true,
    });
    await userEvent.type(box(), " Now.");

    await userEvent.click(save());

    expect(await within(part()).findByRole("alert")).toHaveTextContent(
      "That version's standing does not admit this.",
    );
    expect(within(part()).queryByRole("textbox")).toBeNull();
    expect(heading()).toHaveFocus();
  });
});
