import { ThemeProvider } from "@mui/material/styles";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { useState, type ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";

import type { WorkflowVersion } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}";
import type { SentChoice } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}/steps";
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
    lists: [],
    questions: [],
    workflows: [],
    codeSteps: [],
    searchWays: [],
    models: [
      { name: "general", modes: ["research"], tools: [] },
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
        "Runs of it may spend at most 5000, what is sent and what comes back added together.",
      ),
    ).toBeInTheDocument();
    expect(screen.queryByText(/A run beneath another/)).toBeNull();
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
      screen.getByRole("textbox", { name: "Ceiling" }),
      typed,
    );

    expect(screen.getByRole("button", { name: SAVE_CEILING })).toHaveAttribute(
      "aria-disabled",
      "true",
    );
    expect(
      screen.getByRole("textbox", { name: "Ceiling" }),
    ).toHaveAccessibleDescription(
      "A ceiling is a whole number from one to 9,007,199,254,740,991, written in digits, or none at all.",
    );
    expect(
      noticesIn(
        screen.getByRole("button", { name: SAVE_CEILING }).parentElement!,
      ),
    ).toEqual([
      {
        severity: "warning",
        words:
          "A ceiling is a whole number from one to 9,007,199,254,740,991, written in digits, or none at all.",
      },
    ]);
    expect(saved).not.toHaveBeenCalled();
  });

  it("holds saving a ceiling as it was read in a quiet line, and in no notice", () => {
    ceilingAt(WORKFLOW, true);
    const save = screen.getByRole("button", { name: SAVE_CEILING });

    expect(screen.queryByText("Unsaved")).toBeNull();
    expect(save).not.toHaveClass("MuiButton-contained");
    expect(save).toHaveAccessibleDescription("Change something first.");
    expect(noticesIn(save.parentElement!)).toEqual([]);
  });

  it("sends no ceiling as none, with both settings as switched", async () => {
    const saved = ceilingAt({ ...WORKFLOW, ceiling: "100" }, true);
    const before = "At most 100 sent and came back in all · May not be helped";
    expect(screen.getByText(before)).toBeVisible();

    await userEvent.clear(screen.getByRole("textbox", { name: "Ceiling" }));
    await userEvent.click(
      screen.getByRole("switch", {
        name: "A run beneath another is held to this ceiling as well",
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
      await screen.findByText("No ceiling · May not be helped"),
    ).toBeVisible();
    expect(screen.queryByText(before)).toBeNull();
    expect(screen.queryByText("Unsaved")).toBeNull();
    expect(screen.getByRole("button", { name: SAVE_CEILING })).not.toHaveClass(
      "MuiButton-contained",
    );
  });
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
    await userEvent.click(screen.getByRole("option", { name: "research" }));
    await userEvent.click(screen.getByRole("button", { name: SAVE_HELP }));

    await waitFor(() => expect(saved).toHaveBeenCalledTimes(1));
    expect(saved).toHaveBeenLastCalledWith(true, {
      model: "general",
      mode: "research",
    });
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
    expect(screen.getByRole("button", { name: SAVE_HELP })).toHaveAttribute(
      "aria-disabled",
      "true",
    );
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
