import { ThemeProvider } from "@mui/material/styles";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { useState, type ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";

import type { WorkflowVersion } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}";
import type { SentChoice } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}/steps";
import { RequestFailed } from "../../api/problem";
import { useAction } from "../../lib/request/useAction";
import { theme } from "../../lib/theme/theme";
import { drawnAtDepth, drawnOf } from "../../testutil/depths";
import { noticesIn } from "../../testutil/notices";
import { RunSettings } from "./WorkflowSettings";

const WORKFLOW: WorkflowVersion = {
  revision: 2,
  takes: [],
  gives: [],
  steps: [],
  outputs: [],
  keepsOwnCeiling: false,
  raiseNeedsApproval: true,
  mayBeHelped: false,
  problems: [],
  sendsPast: [],
  offered: {
    codeSteps: [],
    searchWays: [],
    models: [
      { name: "general", modes: ["research", "deep_research"], tools: [] },
      { name: "small", modes: [], tools: [] },
    ],
  },
  terms: new Map(),
};

function themed({ children }: { readonly children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

function settingsAt(workflow: WorkflowVersion, editable: boolean) {
  const savedCeiling = vi.fn(
    (ceiling: string | null, keeps: boolean, raise: boolean) => {
      const { ceiling: _was, ...withoutCeiling } = workflow;
      return Promise.resolve({
        ...withoutCeiling,
        ...(ceiling === null ? {} : { ceiling }),
        keepsOwnCeiling: keeps,
        raiseNeedsApproval: raise,
      });
    },
  );
  const savedHelp = vi.fn((may: boolean, helper: SentChoice | null) =>
    Promise.resolve({
      ...workflow,
      mayBeHelped: may,
      problems: [],
      ...(helper === null
        ? {}
        : {
            helper: {
              model: helper.model,
              ...(helper.mode === null ? {} : { mode: helper.mode }),
            },
          }),
    }),
  );
  function Host() {
    const [shown, setShown] = useState(workflow);
    const spending = useAction<WorkflowVersion>(setShown, () => undefined);
    const helping = useAction<WorkflowVersion>(setShown, () => undefined);
    return (
      <RunSettings
        workflow={shown}
        editable={editable}
        spending={spending}
        helping={helping}
        waiting={() => false}
        saveCeiling={(ceiling, keeps, raise) =>
          savedCeiling(ceiling, keeps, raise)
        }
        saveHelp={(may, helper) => savedHelp(may, helper)}
      />
    );
  }
  render(<Host />, { wrapper: themed });
  fireEvent.click(screen.getByRole("button", { name: "Run settings" }));
  return { savedCeiling, savedHelp };
}

function ceilingAt(workflow: WorkflowVersion, editable: boolean) {
  return settingsAt(workflow, editable).savedCeiling;
}

function helpAt(workflow: WorkflowVersion, editable: boolean) {
  return settingsAt(workflow, editable).savedHelp;
}

const SAVE_CEILING = "Save what a run may spend";

const SAVE_HELP = "Save whether a run may be helped";

const HELPED: WorkflowVersion = {
  ...WORKFLOW,
  mayBeHelped: true,
  helper: { model: "general", mode: "research" },
};

describe("CeilingPart", () => {
  it("keeps usage-limit explanations above an empty input, with a stable description and a separately named information mark", () => {
    ceilingAt(WORKFLOW, true);
    const input = screen.getByRole("textbox", { name: "Usage limit" });
    expect(input).toHaveValue("");
    expect(input).not.toHaveAttribute("placeholder");
    const hint = screen.getByText(
      "What is sent and what comes back, added together. Leave it empty for no usage limit.",
    );
    expect(
      hint.compareDocumentPosition(input) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(input).toHaveAccessibleDescription(hint.textContent!);
    expect(
      screen.getByRole("button", { name: "About run settings" }),
    ).toHaveAccessibleDescription(
      "The usage limit for each run of this version, and whether people following a run can ask a model for help.",
    );
    expect(screen.queryByRole("tooltip")).toBeNull();
    expect(
      input.closest(".MuiTextField-root")!.querySelector(".MuiInputLabel-root"),
    ).toBeNull();
  });

  it("stands as a block of the version, framed apart from the next", () => {
    ceilingAt(WORKFLOW, false);

    expect(
      drawnOf(screen.getByRole("region", { name: "What a run may spend" })),
    ).toEqual(drawnAtDepth(3));
  });

  it("reads the ceiling and both settings where the draft may not be written", () => {
    ceilingAt({ ...WORKFLOW, ceiling: "5000" }, false);

    expect(
      screen.getByText(
        "Runs of it may spend at most 5,000, what is sent and what comes back added together.",
      ),
    ).toBeInTheDocument();
    expect(
      screen.queryByText(
        /A run beneath another is held|A sub-run can only be changed/,
      ),
    ).toBeNull();
    expect(screen.queryByRole("textbox")).toBeNull();
    expect(screen.queryByRole("switch")).toBeNull();
    expect(screen.queryByRole("button", { name: SAVE_CEILING })).toBeNull();
  });

  it.each([
    ["a leading zero", "0100"],
    ["past the largest", "9007199254740992"],
    ["no digits", "ten"],
  ])("holds back a ceiling with %s, sending nothing", async (_case, typed) => {
    const saved = ceilingAt(WORKFLOW, true);

    await userEvent.type(
      screen.getByRole("textbox", { name: "Usage limit" }),
      typed,
    );

    expect(screen.getByRole("button", { name: SAVE_CEILING })).toHaveAttribute(
      "aria-disabled",
      "true",
    );
    expect(
      screen.getByRole("textbox", { name: "Usage limit" }),
    ).toHaveAccessibleDescription(
      "What is sent and what comes back, added together. Leave it empty for no usage limit. A usage limit is a whole number from 1 to 9,007,199,254,740,991, or empty for no limit.",
    );
    expect(
      noticesIn(
        screen.getByRole("button", { name: SAVE_CEILING }).parentElement!,
      ),
    ).toEqual([
      {
        severity: "warning",
        words:
          "A usage limit is a whole number from 1 to 9,007,199,254,740,991, or empty for no limit.",
      },
    ]);
    expect(saved).not.toHaveBeenCalled();
  });

  it("draws no Save of a ceiling as it was read, and draws it as the leading button once something is changed", async () => {
    ceilingAt(WORKFLOW, true);

    expect(screen.queryByText("Unsaved")).toBeNull();
    expect(screen.queryByRole("button", { name: SAVE_CEILING })).toBeNull();
    expect(screen.queryByText("Change something first.")).toBeNull();

    await userEvent.type(
      screen.getByRole("textbox", { name: "Usage limit" }),
      "5",
    );

    const save = screen.getByRole("button", { name: SAVE_CEILING });
    expect(save).toHaveClass("MuiButton-contained");
    expect(save).not.toHaveAttribute("aria-disabled", "true");
    expect(noticesIn(save.parentElement!)).toEqual([]);
    expect(screen.getByText("Unsaved")).toBeVisible();
  });

  it("sends no ceiling as none, with both settings as switched", async () => {
    const saved = ceilingAt({ ...WORKFLOW, ceiling: "100" }, true);
    const before = "Usage limit: 100 · May not be helped";
    expect(screen.getByText(before)).toBeVisible();

    await userEvent.clear(screen.getByRole("textbox", { name: "Usage limit" }));
    await userEvent.click(
      screen.getByRole("switch", {
        name: "When run as a sub-run, it is also held to this usage limit",
      }),
    );
    expect(screen.getByText("Unsaved")).toBeVisible();
    expect(screen.getByRole("button", { name: SAVE_CEILING })).toHaveClass(
      "MuiButton-contained",
    );
    await userEvent.click(screen.getByRole("button", { name: SAVE_CEILING }));

    await waitFor(() => expect(saved).toHaveBeenCalledTimes(1));
    expect(saved).toHaveBeenCalledWith(null, true, true);
    expect(
      await screen.findByText("No usage limit · May not be helped"),
    ).toBeVisible();
    expect(screen.queryByText(before)).toBeNull();
    expect(screen.queryByText("Unsaved")).toBeNull();
    expect(screen.queryByRole("button", { name: SAVE_CEILING })).toBeNull();
  });

  it("puts the keyboard on the part's title once a Save of a ceiling pressed by keyboard is answered", async () => {
    const saved = ceilingAt(WORKFLOW, true);
    await userEvent.type(
      screen.getByRole("textbox", { name: "Usage limit" }),
      "5",
    );
    await act(async () => {
      screen.getByRole("button", { name: SAVE_CEILING }).focus();
    });

    await userEvent.keyboard("{Enter}");

    await waitFor(() =>
      expect(document.activeElement).toBe(
        screen.getByRole("heading", { level: 4, name: "What a run may spend" }),
      ),
    );
    expect(saved).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole("button", { name: SAVE_CEILING })).toBeNull();
  });

  it("takes the keyboard from a refused Save of a ceiling to the refusal, the Save kept drawn", async () => {
    const saved = ceilingAt(WORKFLOW, true);
    saved.mockRejectedValueOnce(
      new RequestFailed({ status: 409, code: "DRAFT_WRITTEN_SINCE_READ" }),
    );
    await userEvent.type(
      screen.getByRole("textbox", { name: "Usage limit" }),
      "5",
    );
    await act(async () => {
      screen.getByRole("button", { name: SAVE_CEILING }).focus();
    });

    await userEvent.keyboard("{Enter}");

    const alert = await screen.findByRole("alert");
    await waitFor(() => expect(document.activeElement).toContainElement(alert));
    expect(document.activeElement).toHaveAttribute("tabindex", "-1");
    expect(
      screen.getByRole("button", { name: SAVE_CEILING }),
    ).toBeInTheDocument();
  });

  it("keeps the Save of a ceiling changed drawn while its write is out and once it is refused", async () => {
    const saved = ceilingAt(WORKFLOW, true);
    let refuse!: (reason: unknown) => void;
    saved.mockImplementationOnce(
      () =>
        new Promise<WorkflowVersion>((_resolve, rejects) => {
          refuse = rejects;
        }),
    );
    await userEvent.type(
      screen.getByRole("textbox", { name: "Usage limit" }),
      "5",
    );

    await userEvent.click(screen.getByRole("button", { name: SAVE_CEILING }));

    expect(screen.getByRole("button", { name: SAVE_CEILING })).toHaveClass(
      "MuiButton-contained",
    );
    await act(async () =>
      refuse(
        new RequestFailed({ status: 409, code: "DRAFT_WRITTEN_SINCE_READ" }),
      ),
    );
    expect(screen.getByRole("button", { name: SAVE_CEILING })).toHaveClass(
      "MuiButton-contained",
    );
    expect(screen.getByText("Unsaved")).toBeVisible();
  });

  it.each([
    [
      "kept, its words asking nothing",
      { status: 409, code: "DRAFT_WRITTEN_SINCE_READ" },
      "Somebody changed this draft since it was read; nothing was saved.",
    ],
    [
      "unsaid, its words asking for the write again",
      { status: 503, code: "SERVICE_BUSY" },
      null,
    ],
  ])(
    "draws no Save once a refused ceiling is typed back to what is saved, the refusal %s, and lets it go at the next change",
    async (_case, problem, kept) => {
      const saved = ceilingAt(WORKFLOW, true);
      saved.mockRejectedValueOnce(new RequestFailed(problem));
      const limit = screen.getByRole("textbox", { name: "Usage limit" });
      await userEvent.type(limit, "5");
      await userEvent.click(screen.getByRole("button", { name: SAVE_CEILING }));
      expect(await screen.findByRole("alert")).toBeInTheDocument();

      await userEvent.type(limit, "{Backspace}");

      expect(screen.queryByRole("alert")?.textContent ?? null).toBe(kept);
      expect(screen.queryByText(/again/)).toBeNull();
      expect(screen.queryByRole("button", { name: SAVE_CEILING })).toBeNull();

      await userEvent.type(limit, "7");

      expect(screen.queryByRole("alert")).toBeNull();
      expect(
        screen.getByRole("button", { name: SAVE_CEILING }),
      ).toBeInTheDocument();
      expect(saved).toHaveBeenCalledTimes(1);
    },
  );
});

describe("HelpPart", () => {
  it("stands as a block of the version, framed apart from the next", () => {
    helpAt(WORKFLOW, false);

    expect(
      drawnOf(
        screen.getByRole("region", { name: "Whether a run may be helped" }),
      ),
    ).toEqual(drawnAtDepth(3));
  });

  it("reads that runs may be helped by a model not chosen yet where the draft may not be written", () => {
    helpAt({ ...WORKFLOW, mayBeHelped: true }, false);

    expect(
      screen.getByText("Runs may be helped, by a model not chosen yet."),
    ).toBeInTheDocument();
    expect(screen.queryByText("Runs may not be helped.")).toBeNull();
  });

  it("says once what submitting refuses of the helper", () => {
    helpAt(
      {
        ...WORKFLOW,
        mayBeHelped: true,
        problems: [{ code: "helper_missing", part: "helper" }],
      },
      false,
    );

    expect(
      screen.getAllByText(
        "Runs may be helped, and no model is named to help them.",
      ),
    ).toHaveLength(1);
  });

  it("offers no control over help where the draft may not be written", () => {
    helpAt({ ...WORKFLOW, mayBeHelped: true }, false);

    expect(screen.queryByRole("switch")).toBeNull();
    expect(screen.queryByRole("combobox")).toBeNull();
    expect(screen.queryByRole("button", { name: SAVE_HELP })).toBeNull();
  });

  it("sends a helper chosen with its mode", async () => {
    const saved = helpAt(WORKFLOW, true);

    await userEvent.click(
      screen.getByRole("switch", { name: "Runs may be helped" }),
    );
    await userEvent.click(screen.getByRole("combobox", { name: "Model" }));
    await userEvent.click(screen.getByRole("option", { name: "general" }));
    await userEvent.click(screen.getByRole("combobox", { name: "Mode" }));
    expect(
      screen.getAllByRole("option").map((option) => option.textContent),
    ).toEqual(["As it is", "research", "deep_research"]);
    await userEvent.click(screen.getByRole("option", { name: "research" }));
    await userEvent.click(screen.getByRole("button", { name: SAVE_HELP }));

    await waitFor(() => expect(saved).toHaveBeenCalledTimes(1));
    expect(saved).toHaveBeenLastCalledWith(true, {
      model: "general",
      mode: "research",
    });
  });

  it("puts the keyboard on the part's title once a Save of help pressed by keyboard is answered", async () => {
    const saved = helpAt(WORKFLOW, true);
    await userEvent.click(
      screen.getByRole("switch", { name: "Runs may be helped" }),
    );
    await act(async () => {
      screen.getByRole("button", { name: SAVE_HELP }).focus();
    });

    await userEvent.keyboard("{Enter}");

    await waitFor(() =>
      expect(document.activeElement).toBe(
        screen.getByRole("heading", {
          level: 4,
          name: "Whether a run may be helped",
        }),
      ),
    );
    expect(saved).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole("button", { name: SAVE_HELP })).toBeNull();
  });

  it("hides the helper's controls while help is switched off", async () => {
    helpAt(HELPED, true);

    await userEvent.click(
      screen.getByRole("switch", { name: "Runs may be helped" }),
    );

    expect(screen.queryByRole("combobox", { name: "Model" })).toBeNull();
    expect(screen.queryByRole("combobox", { name: "Mode" })).toBeNull();
  });

  it("keeps the helper chosen once help is switched back on, holding the save as nothing changed", async () => {
    const saved = helpAt(HELPED, true);
    const helping = screen.getByRole("switch", { name: "Runs may be helped" });

    await userEvent.click(helping);
    await userEvent.click(helping);

    expect(screen.getByRole("combobox", { name: "Model" })).toHaveTextContent(
      "general",
    );
    expect(screen.getByRole("combobox", { name: "Mode" })).toHaveTextContent(
      "research",
    );
    expect(screen.queryByRole("button", { name: SAVE_HELP })).toBeNull();
    expect(screen.queryByText("Unsaved")).toBeNull();
    expect(saved).not.toHaveBeenCalled();
  });

  it("sends no helper with help switched off, whatever was chosen before", async () => {
    const saved = helpAt(HELPED, true);

    await userEvent.click(
      screen.getByRole("switch", { name: "Runs may be helped" }),
    );
    await userEvent.click(screen.getByRole("button", { name: SAVE_HELP }));

    await waitFor(() => expect(saved).toHaveBeenCalledTimes(1));
    expect(saved).toHaveBeenLastCalledWith(false, null);
  });
});
