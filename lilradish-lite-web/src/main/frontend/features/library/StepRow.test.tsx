import { ThemeProvider } from "@mui/material/styles";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import { render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { describe, expect, it, vi } from "vitest";

import type {
  OfferedCodeStep,
  OfferedSearchWay,
} from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}";
import { theme } from "../../lib/theme/theme";
import { isolatedInText } from "../../lib/direction/isolated";
import { StepRow } from "./StepRow";
import { freshStep, type StepDraft } from "./workflowDrafts";

const first = { ...freshStep("first"), name: "first" };
const middle = { ...freshStep("middle"), name: "middle" };
const last = { ...freshStep("last"), name: "last" };
const named = new Map();
const codeSteps: readonly OfferedCodeStep[] = [];
const searchWays: readonly OfferedSearchWay[] = [];
const onRegisterRow = vi.fn();
const onRegisterOpen = vi.fn();
const onMove = vi.fn();
const onTakeOut = vi.fn();
const onTakeVersion = vi.fn();

function rows(steps: readonly StepDraft[], picked: string) {
  return (
    <ThemeProvider theme={theme}>
      <MemoryRouter>
        <Table>
          <TableBody>
            {steps.map((step, position) => (
              <StepRow
                key={step.key}
                step={step}
                position={position}
                last={position === steps.length - 1}
                focusAfterTakingOut={
                  steps[position + 1]?.key ?? steps[position - 1]?.key
                }
                picked={step.key === picked}
                href={`/content?step=${step.key}`}
                named={named}
                codeSteps={codeSteps}
                searchWays={searchWays}
                editable
                running={false}
                onRegisterRow={onRegisterRow}
                onRegisterOpen={onRegisterOpen}
                onMove={onMove}
                onTakeOut={onTakeOut}
                onTakeVersion={onTakeVersion}
              />
            ))}
          </TableBody>
        </Table>
      </MemoryRouter>
    </ThemeProvider>
  );
}

describe("StepRow", () => {
  it("sets an unnamed step's default name apart from its hint without changing the link's complete name", () => {
    render(rows([freshStep("added")], "added"));

    const link = screen.getByRole("link", {
      name: "Open Step 1, not named yet",
    });
    const hint = getComputedStyle(within(link).getByText("not named yet"));

    expect(link.textContent).toBe("Step 1, not named yet");
    expect(within(link).getByText("Step 1").tagName).toBe("BDI");
    expect(hint.fontStyle).toBe("italic");
    expect(hint.fontWeight).toBe("400");
    expect(hint.fontSize).toBe(getComputedStyle(link).fontSize);
    expect(getComputedStyle(link).fontStyle).not.toBe("italic");
    expect(
      screen.getByRole("button", { name: "Take out: Step 1, not named yet" }),
    ).toBeInTheDocument();
  });

  it("isolates a named step as text and draws no unnamed hint", () => {
    const name = 'Demo Sample <img src=x onerror="alert(1)"> مرحبا';
    render(rows([{ ...first, name }], "first"));

    const link = screen.getByRole("link", {
      name: `Open ${isolatedInText(name)}`,
    });

    expect(link.textContent).toBe(name);
    expect(link.firstElementChild?.tagName).toBe("BDI");
    expect(link.querySelector("img")).toBeNull();
    expect(screen.queryByText("not named yet")).toBeNull();
  });
  it("registers only the row edited and the two rows whose selection changes", () => {
    const attached = () =>
      onRegisterRow.mock.calls
        .filter(([, row]) => row !== null)
        .map(([key]) => key);
    const { rerender } = render(rows([first, middle, last], "first"));
    expect(screen.getAllByRole("row")).toHaveLength(3);
    onRegisterRow.mockClear();
    onRegisterOpen.mockClear();

    const renamed = { ...first, name: "first edited" };
    rerender(rows([renamed, middle, last], "first"));
    expect(
      screen.getByRole("link", { name: /first edited/ }),
    ).toBeInTheDocument();
    expect(attached()).toEqual(["first"]);
    expect(
      onRegisterOpen.mock.calls
        .filter(([, open]) => open !== null)
        .map(([key]) => key),
    ).toEqual(["first"]);
    onRegisterRow.mockClear();
    onRegisterOpen.mockClear();

    rerender(rows([renamed, middle, last], "last"));
    expect(screen.getByRole("link", { name: /last/ })).toHaveAttribute(
      "aria-current",
      "true",
    );
    expect(attached()).toEqual(["first", "last"]);
    expect(
      onRegisterOpen.mock.calls
        .filter(([, open]) => open !== null)
        .map(([key]) => key),
    ).toEqual(["first", "last"]);
  });
});
