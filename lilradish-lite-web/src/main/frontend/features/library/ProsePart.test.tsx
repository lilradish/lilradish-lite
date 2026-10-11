import { ThemeProvider } from "@mui/material/styles";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { useState, type ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";

import { RequestFailed } from "../../api/problem";
import type { MessageId } from "../../i18n/app";
import { useAction } from "../../lib/request/useAction";
import { theme } from "../../lib/theme/theme";
import { drawnAtDepth, drawnOf } from "../../testutil/depths";
import { wordsBeside } from "../../testutil/editable";
import { noticesIn } from "../../testutil/notices";
import { ProsePart, type ProseWords } from "./ProsePart";

const WORDS: ProseWords = {
  title: "referenceList.note",
  hint: "referenceList.noteHint",
  saysNothing: "referenceList.saysNothing",
  save: "referenceList.saveNote",
  underway: "referenceList.underway",
  edit: "prose.editNote",
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
    /** What the version says once read afresh, where reading it afresh draws that. */
    readonly readsAs?: string;
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
        onReadAfresh={() => {
          readAfresh();
          if (host.readsAs !== undefined) setHeld(host.readsAs);
        }}
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

function saveOrNone(): HTMLElement | null {
  return within(part()).queryByRole("button", { name: "Save the note" });
}

function heading(): HTMLElement {
  return screen.getByRole("heading", { level: 3, name: "The note" });
}

async function open() {
  await userEvent.click(
    within(part()).getByRole("button", { name: "Edit the note" }),
  );
}

describe("ProsePart", () => {
  it("starts reading, opens by keyboard, retains raw text on blur and Escape, and cancels without a write", async () => {
    const { saved } = writing(SAID);
    expect(within(part()).queryByRole("textbox")).toBeNull();
    const reading = within(part()).getByRole("button", {
      name: "Edit the note",
    });
    await act(async () => {
      reading.focus();
    });
    await userEvent.keyboard("{Enter}");
    expect(box()).toHaveFocus();
    await userEvent.type(box(), " Keep this draft.");
    await act(async () => {
      heading().focus();
    });
    await userEvent.keyboard("{Escape}");
    expect(box()).toHaveValue(`${SAID} Keep this draft.`);
    expect(saved).not.toHaveBeenCalled();
    await userEvent.click(
      within(part()).getByRole("button", { name: "Cancel" }),
    );
    expect(within(part()).queryByRole("textbox")).toBeNull();
    expect(
      within(part()).getByRole("button", { name: "Edit the note" }),
    ).toHaveFocus();
    expect(
      wordsBeside(within(part()).getByRole("button", { name: "Edit the note" }))
        .textContent,
    ).toBe(SAID);
    expect(saved).not.toHaveBeenCalled();
    await open();
    expect(box()).toHaveValue(SAID);
  });

  it.each([
    ["what it says", SAID, SAID],
    [
      "the line saying it says nothing",
      undefined,
      "It says nothing on choosing among its terms.",
    ],
  ] as const)(
    "offers %s as the one press into its editor, with no Edit button beside its title",
    async (_case, said, read) => {
      writing(said);

      const buttons = within(part()).getAllByRole("button");
      expect(
        buttons.map((button) => button.getAttribute("aria-label")),
      ).toEqual(["Edit the note"]);
      expect(within(part()).queryByRole("button", { name: "Edit" })).toBeNull();
      expect(wordsBeside(buttons[0]!).textContent).toBe(read);

      await userEvent.click(wordsBeside(buttons[0]!));

      expect(box()).toHaveFocus();
      expect(box()).toHaveValue(said ?? "");
      expect(
        within(part()).queryByRole("button", { name: "Edit the note" }),
      ).toBeNull();
    },
  );

  it("names its box by the part's own title, with no label of its own above or inside the box and no placeholder", async () => {
    writing(SAID);
    await open();

    expect(box()).toHaveAttribute("aria-labelledby", heading().id);
    expect(box()).not.toHaveAttribute("placeholder");
    expect(part().querySelector("label, .MuiInputLabel-root")).toBeNull();
    expect(within(part()).getAllByText("The note")).toEqual([heading()]);
  });

  it.each([true, false])(
    "preserves dirty words only when the host supplies a related rewrite (%s)",
    async (rewrite) => {
      const saved = vi.fn((text: string | null) => Promise.resolve(text));
      function Host({
        said,
        rewritten,
      }: {
        readonly said: string;
        readonly rewritten?: (text: string) => string;
      }) {
        const action = useAction<string | null>(() => {});
        return (
          <ProsePart
            words={WORDS}
            said={said}
            rewritten={rewritten}
            editable
            waiting={false}
            refusalOf={() => null}
            rule="author_entry"
            action={action}
            save={saved}
            focusOnArrival={false}
            onReadAfresh={() => {}}
          />
        );
      }
      const { rerender } = render(<Host said="Read {{a}}." />, {
        wrapper: themed,
      });
      await open();
      await userEvent.type(box(), " Author words.");
      const changed = (text: string) => text.replace("{{a}}", "{{b}}");
      rerender(
        <Host said="Read {{b}}." rewritten={rewrite ? changed : undefined} />,
      );
      expect(box()).toHaveValue(
        rewrite ? "Read {{b}}. Author words." : "Read {{b}}.",
      );
      rerender(
        <Host said="Read {{b}}." rewritten={rewrite ? changed : undefined} />,
      );
      expect(box()).toHaveValue(
        rewrite ? "Read {{b}}. Author words." : "Read {{b}}.",
      );
      if (rewrite) {
        await userEvent.click(save());
        expect(saved).toHaveBeenCalledExactlyOnceWith(
          "Read {{b}}. Author words.",
          expect.any(AbortSignal),
        );
      }
    },
  );

  it("stands as a block of the version, framed apart from the next", () => {
    writing(SAID, { editable: false });

    expect(drawnOf(part())).toEqual(drawnAtDepth(2));
  });

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
    await open();

    expect(within(part()).queryByText("Unsaved")).toBeNull();
    expect(saveOrNone()).toBeNull();
    await userEvent.clear(box());
    await userEvent.type(box(), "Pick one.{Enter}Never two.");
    expect(within(part()).getByText("Unsaved")).toBeVisible();
    expect(save()).toHaveClass("MuiButton-contained");
    await userEvent.click(save());

    expect(saved.mock.calls).toEqual([["Pick one.\nNever two."]]);
    await within(part()).findByRole("button", { name: "Edit the note" });
    expect(within(part()).queryByRole("textbox")).toBeNull();
    await open();
    expect(box()).toHaveValue("Pick one.\nNever two.");
    expect(within(part()).queryByText("Unsaved")).toBeNull();
    expect(saveOrNone()).toBeNull();
  });

  it("saves what is emptied as none, rather than as empty", async () => {
    const { saved } = writing(SAID);
    await open();

    await userEvent.clear(box());
    await userEvent.click(save());

    expect(saved.mock.calls).toEqual([[null]]);
    await within(part()).findByRole("button", { name: "Edit the note" });
    await open();
    expect(box()).toHaveValue("");
    await waitFor(() => expect(saveOrNone()).toBeNull());
  });

  it("draws no Save while what is typed is as it was saved, only Cancel, and says nothing unsaved", async () => {
    const { saved } = writing(SAID);
    await open();

    await userEvent.type(box(), "x");
    expect(save()).toHaveClass("MuiButton-contained");
    await userEvent.keyboard("{Backspace}");

    expect(saveOrNone()).toBeNull();
    expect(
      within(part()).getByRole("button", { name: "Cancel" }),
    ).toBeInTheDocument();
    expect(within(part()).queryByText("Unsaved")).toBeNull();
    expect(saved).not.toHaveBeenCalled();
  });

  it("keeps its Save drawn, the leading button, while the write is out", async () => {
    let settle!: (prose: string | undefined) => void;
    const { saved } = writing(SAID, {
      answer: () =>
        new Promise<string | undefined>((resolve) => {
          settle = resolve;
        }),
    });
    await open();
    await userEvent.type(box(), " Now.");

    await userEvent.click(save());

    expect(save()).toHaveClass("MuiButton-contained");
    expect(saved).toHaveBeenCalledTimes(1);
    await act(async () => settle(`${SAID} Now.`));
    await waitFor(() =>
      expect(within(part()).queryByRole("textbox")).toBeNull(),
    );
    expect(saveOrNone()).toBeNull();
  });

  it("sends a Save pressed again while its write is out nothing more, and saves once", async () => {
    let settle!: (prose: string | undefined) => void;
    const { saved } = writing(SAID, {
      answer: () =>
        new Promise<string | undefined>((resolve) => {
          settle = resolve;
        }),
    });
    await open();
    await userEvent.type(box(), " Now.");
    await userEvent.click(save());

    await userEvent.click(save());

    expect(saved.mock.calls).toEqual([[`${SAID} Now.`]]);
    expect(save()).toHaveAttribute("aria-disabled", "true");
    await act(async () => settle(`${SAID} Now.`));
    await waitFor(() =>
      expect(within(part()).queryByRole("textbox")).toBeNull(),
    );
    expect(saved).toHaveBeenCalledTimes(1);
  });

  it("puts the keyboard on its edit control once a Save pressed by keyboard is answered", async () => {
    writing(SAID);
    await open();
    await userEvent.type(box(), " Now.");
    await act(async () => {
      save().focus();
    });

    await userEvent.keyboard("{Enter}");

    const edit = await within(part()).findByRole("button", {
      name: "Edit the note",
    });
    await waitFor(() => expect(document.activeElement).toBe(edit));
    expect(within(part()).queryByRole("textbox")).toBeNull();
    expect(wordsBeside(edit).textContent).toBe(`${SAID} Now.`);
  });

  it("takes the keyboard from a refused Save pressed by keyboard to the refusal, the Save and what is typed kept", async () => {
    writing(SAID, {
      answer: () =>
        Promise.reject(
          new RequestFailed({ status: 409, code: "DRAFT_WRITTEN_SINCE_READ" }),
        ),
    });
    await open();
    await userEvent.type(box(), " Now.");
    await act(async () => {
      save().focus();
    });

    await userEvent.keyboard("{Enter}");

    const alert = await within(part()).findByRole("alert");
    await waitFor(() => expect(document.activeElement).toContainElement(alert));
    expect(save()).not.toHaveFocus();
    expect(box()).toHaveValue(`${SAID} Now.`);
  });

  it("holds a save the server would refuse, saying why beside it as a warning, and sends nothing", async () => {
    const why =
      "What you wrote contains a text-direction character, which can't be sent to a model.";
    const { saved } = writing(SAID);
    await open();
    await userEvent.click(box());
    await userEvent.paste(` ${OVERRIDE}`);

    await act(async () => {
      save().focus();
    });
    await userEvent.keyboard("{Enter}");

    expect(save()).toHaveAttribute("aria-disabled", "true");
    expect(save()).toHaveAccessibleDescription(why);
    expect(noticesIn(save().parentElement!)).toEqual([
      { severity: "warning", words: why },
    ]);
    expect(saved).not.toHaveBeenCalled();
  });

  it("says the hint under the box while what is typed would be taken, and the refusal in its place where not", async () => {
    writing(SAID);
    await open();
    expect(box()).toHaveAccessibleDescription(
      "How to go about choosing among the terms at all. What is true of one term goes in what it means.",
    );

    await userEvent.click(box());
    await userEvent.paste(OVERRIDE);

    expect(box()).toHaveAccessibleDescription(
      "What you wrote contains a text-direction character, which can't be sent to a model.",
    );
  });

  it("holds the save while another write of the host's is out, which it would be refused beside, and sends nothing", async () => {
    const { saved } = writing(SAID, { waiting: true });
    await open();
    await userEvent.type(box(), " Now.");

    expect(within(part()).getByText("Unsaved")).toBeVisible();
    expect(save()).toHaveClass("MuiButton-contained");
    await act(async () => {
      save().focus();
    });
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
    await open();
    await userEvent.type(box(), " Now.");
    await userEvent.click(save());
    expect(await within(part()).findByRole("alert")).toHaveTextContent(
      "Somebody changed this draft since it was read; nothing was saved.",
    );
    expect(within(part()).getByText("Unsaved")).toBeVisible();
    expect(save()).toHaveClass("MuiButton-contained");
    expect(box()).toHaveValue(`${SAID} Now.`);
    expect(readAfresh).not.toHaveBeenCalled();

    await userEvent.click(
      within(part()).getByRole("button", { name: "Load the saved draft" }),
    );

    expect(readAfresh).toHaveBeenCalledTimes(1);
  });

  it.each([
    ["the next change", false],
    ["Cancel", true],
  ] as const)(
    "keeps saying a refused save once reading afresh leaves nothing to save, with no Save and no way on to saving, until %s",
    async (_case, cancelling) => {
      writing(SAID, {
        answer: () =>
          Promise.reject(
            new RequestFailed({
              status: 409,
              code: "DRAFT_WRITTEN_SINCE_READ",
            }),
          ),
        readsAs: "Theirs now.",
      });
      await open();
      await userEvent.type(box(), " Now.");
      await userEvent.click(save());
      await within(part()).findByRole("alert");

      await userEvent.click(
        within(part()).getByRole("button", { name: "Load the saved draft" }),
      );

      expect(box()).toHaveValue("Theirs now.");
      expect(saveOrNone()).toBeNull();
      const alert = within(part()).getByRole("alert");
      expect(alert).toHaveTextContent(
        "Somebody changed this draft since it was read; nothing was saved.",
      );
      expect(part().textContent).not.toMatch(/again|load the saved draft/i);
      expect(
        within(part()).queryByRole("button", { name: "Load the saved draft" }),
      ).toBeNull();

      if (cancelling) {
        await userEvent.click(
          within(part()).getByRole("button", { name: "Cancel" }),
        );
        expect(within(part()).queryByRole("alert")).toBeNull();
        expect(saveOrNone()).toBeNull();
      } else {
        await userEvent.type(box(), "!");
        expect(within(part()).queryByRole("alert")).toBeNull();
        expect(save()).toBeInTheDocument();
      }
    },
  );

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
    await open();
    await userEvent.type(box(), " Now.");

    await userEvent.click(save());

    expect(await within(part()).findByRole("alert")).toHaveTextContent(
      "That version's standing does not admit this.",
    );
    expect(within(part()).queryByRole("textbox")).toBeNull();
    expect(heading()).toHaveFocus();
  });
});
