import { ThemeProvider } from "@mui/material/styles";
import { render, screen, waitFor } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { useState, type ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";

import type { WorkflowVersion } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}";
import type { SentChoice } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}/steps";
import { useAction } from "../../lib/request/useAction";
import { theme } from "../../lib/theme/theme";
import { CeilingPart, HelpPart } from "./WorkflowSettings";

const WORKFLOW: WorkflowVersion = {
  revision: 2,
  takes: [],
  gives: [],
  steps: [],
  outputs: [],
  keepsOwnCeiling: false,
  raiseNeedsApproval: true,
  mayBeHelped: false,
  problems: [{ code: "helper_missing", part: "helper" }],
  sendsPast: [],
  offered: {
    lists: [],
    questions: [],
    workflows: [],
    codeSteps: [],
    models: [
      { name: "general", modes: ["research"] },
      { name: "small", modes: [] },
    ],
  },
  terms: new Map(),
};

function themed({ children }: { readonly children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

function ceilingAt(workflow: WorkflowVersion, editable: boolean) {
  const saved = vi.fn(
    (ceiling: string | null, keeps: boolean, raise: boolean) =>
      Promise.resolve({
        ...workflow,
        ...(ceiling === null ? {} : { ceiling }),
        keepsOwnCeiling: keeps,
        raiseNeedsApproval: raise,
      }),
  );
  function Host() {
    const [shown, setShown] = useState(workflow);
    const action = useAction<WorkflowVersion>(setShown, () => undefined);
    return (
      <CeilingPart
        workflow={shown}
        editable={editable}
        action={action}
        waiting={false}
        save={(ceiling, keeps, raise) => saved(ceiling, keeps, raise)}
      />
    );
  }
  render(<Host />, { wrapper: themed });
  return saved;
}

function helpAt(workflow: WorkflowVersion, editable: boolean) {
  const saved = vi.fn((may: boolean, helper: SentChoice | null) =>
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
    const action = useAction<WorkflowVersion>(setShown, () => undefined);
    return (
      <HelpPart
        workflow={shown}
        editable={editable}
        action={action}
        waiting={false}
        save={(may, helper) => saved(may, helper)}
      />
    );
  }
  render(<Host />, { wrapper: themed });
  return saved;
}

const SAVE_CEILING = "Save what a run may spend";

const SAVE_HELP = "Save whether a run may be helped";

const HELPED: WorkflowVersion = {
  ...WORKFLOW,
  mayBeHelped: true,
  helper: { model: "general", mode: "research" },
};

describe("CeilingPart", () => {
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
    expect(saved).not.toHaveBeenCalled();
  });

  it("sends no ceiling as none, with both settings as switched", async () => {
    const saved = ceilingAt({ ...WORKFLOW, ceiling: "100" }, true);

    await userEvent.clear(screen.getByRole("textbox", { name: "Ceiling" }));
    await userEvent.click(
      screen.getByRole("switch", {
        name: "A run beneath another is held to this ceiling as well",
      }),
    );
    await userEvent.click(screen.getByRole("button", { name: SAVE_CEILING }));

    await waitFor(() => expect(saved).toHaveBeenCalledTimes(1));
    expect(saved).toHaveBeenCalledWith(null, true, true);
  });
});

describe("HelpPart", () => {
  it("reads that runs may be helped by a model not chosen yet where the draft may not be written", () => {
    helpAt({ ...WORKFLOW, mayBeHelped: true }, false);

    expect(
      screen.getByText("Runs may be helped, by a model not chosen yet."),
    ).toBeInTheDocument();
    expect(screen.queryByText("Runs may not be helped.")).toBeNull();
  });

  it("says once what submitting refuses of the helper", () => {
    helpAt({ ...WORKFLOW, mayBeHelped: true }, false);

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
