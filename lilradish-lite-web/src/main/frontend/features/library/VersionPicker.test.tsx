import { ThemeProvider } from "@mui/material/styles";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";

import { answering } from "../../testutil/answering";
import { theme } from "../../lib/theme/theme";
import { VersionPicker, type VersionChoice } from "./VersionPicker";

const GROUP = "00000003-0000-4000-8000-000000000a71";
const FIRST = "00000007-0000-4000-8000-000000000a71";
const SECOND = "00000007-0000-4000-8000-000000000a72";
const THIRD = "00000007-0000-4000-8000-000000000a73";
const FOREIGN = "00000007-0000-4000-8000-000000000a74";
const CHOICES: readonly VersionChoice[] = [
  { versionId: FIRST, label: "Version 1 · Same", shown: "First" },
  { versionId: SECOND, label: "Version 2 · Same", shown: "Second" },
  {
    versionId: THIRD,
    label: "Version 3 · Retired",
    shown: "Third",
    disabled: true,
  },
];

function Picker({
  current = "",
  choices = CHOICES,
  readOnly = false,
}: {
  readonly current?: string;
  readonly choices?: readonly VersionChoice[];
  readonly readOnly?: boolean;
}) {
  const [value, changed] = useState(current);
  return (
    <ThemeProvider theme={theme}>
      <VersionPicker
        groupId={GROUP}
        kind="question"
        choices={choices}
        value={value}
        onChange={changed}
        label="Question version"
        noneLabel="None"
        readOnly={readOnly}
      />
      <output aria-label="Chosen version">{value}</output>
    </ThemeProvider>
  );
}

describe("loaded version matching", () => {
  it("debounces typing and resets the query with the visible value on blur", async () => {
    const sent = answering([JSON.stringify({ versions: [SECOND] }), 200]);
    render(<Picker current={FIRST} />);
    const input = screen.getByRole("combobox", { name: "Question version" });
    fireEvent.mouseDown(input);
    for (const value of ["S", "Sa", "Same"])
      fireEvent.change(input, { target: { value } });
    expect(sent).not.toHaveBeenCalled();
    await waitFor(() => expect(sent).toHaveBeenCalledTimes(1));
    expect(JSON.parse(String(sent.mock.calls[0]![1]?.body)).text).toBe("Same");
    fireEvent.blur(input);
    await waitFor(() => expect(input).toHaveValue("Version 1 · Same"));
    fireEvent.mouseDown(input);
    expect(
      screen.getAllByRole("option").map((item) => item.textContent),
    ).toEqual(["None", "First", "Second", "Third"]);
    expect(sent).toHaveBeenCalledTimes(1);
  });

  it("keeps server matching, the loaded ID intersection and the original order, with no second text filter", async () => {
    const sent = answering([
      JSON.stringify({ versions: [SECOND, FOREIGN, FIRST] }),
      200,
    ]);
    render(<Picker />);
    const input = screen.getByRole("combobox", { name: "Question version" });
    fireEvent.mouseDown(input);
    expect(sent).not.toHaveBeenCalled();
    fireEvent.change(input, { target: { value: "STRASSE" } });
    await waitFor(() => expect(sent).toHaveBeenCalledTimes(1));
    const [address, init] = sent.mock.calls[0]!;
    expect(address).toBe(`/api/groups/${GROUP}/questions/version-name-matches`);
    expect(init?.method).toBe("POST");
    expect(JSON.parse(String(init?.body))).toEqual({
      text: "STRASSE",
      versions: [FIRST, SECOND, THIRD],
    });
    await waitFor(() =>
      expect(
        screen.getAllByRole("option").map((option) => option.textContent),
      ).toEqual(["None", "First", "Second"]),
    );
    await act(async () => {
      fireEvent.click(screen.getByRole("option", { name: "Second" }));
    });
    expect(
      screen.getByRole("status", { name: "Chosen version" }),
    ).toHaveTextContent(SECOND);
  });

  it("keeps the current choice and the exact empty-match explanation without adding an unloaded option", async () => {
    answering([JSON.stringify({ versions: [FOREIGN] }), 200]);
    render(<Picker current={FIRST} />);
    const input = screen.getByRole("combobox", { name: "Question version" });
    fireEvent.mouseDown(input);
    fireEvent.change(input, { target: { value: "absent" } });
    await screen.findByText(
      "No loaded version matches. More versions may exist; narrow by the entry's name first.",
    );
    expect(
      screen.getAllByRole("option").map((option) => option.textContent),
    ).toEqual(["None", "First"]);
    expect(
      screen.getByRole("status", { name: "Chosen version" }),
    ).toHaveTextContent(FIRST);
    await act(async () => {
      fireEvent.click(screen.getByRole("option", { name: "None" }));
    });
    expect(
      screen.getByRole("status", { name: "Chosen version" }),
    ).toBeEmptyDOMElement();
  });

  it("does not accept an older response after the fragment has changed even if the transport settles it", async () => {
    let settleOld: (response: Response) => void = () => {
      throw new Error("old request was not sent");
    };
    const old = new Promise<Response>((settle) => {
      settleOld = settle;
    });
    const sent = vi.fn<typeof fetch>().mockImplementation((_address, init) =>
      JSON.parse(String(init?.body)).text === "old"
        ? old
        : Promise.resolve(
            new Response(JSON.stringify({ versions: [SECOND] }), {
              status: 200,
            }),
          ),
    );
    vi.stubGlobal("fetch", sent);
    render(<Picker />);
    const input = screen.getByRole("combobox", { name: "Question version" });
    fireEvent.mouseDown(input);
    fireEvent.change(input, { target: { value: "old" } });
    await waitFor(() => expect(sent).toHaveBeenCalledTimes(1));
    fireEvent.change(input, { target: { value: "new" } });
    await waitFor(() =>
      expect(
        screen.getAllByRole("option").map((option) => option.textContent),
      ).toEqual(["None", "Second"]),
    );
    await act(async () =>
      settleOld(
        new Response(JSON.stringify({ versions: [FIRST, FOREIGN] }), {
          status: 200,
        }),
      ),
    );
    expect(
      screen.getAllByRole("option").map((option) => option.textContent),
    ).toEqual(["None", "Second"]);
    expect(sent.mock.calls[0]![1]?.signal?.aborted).toBe(true);
  });

  it("keeps duplicate labels as distinct IDs and refuses a disabled selection", async () => {
    render(
      <Picker
        choices={CHOICES.map((choice) => ({ ...choice, label: "Same" }))}
      />,
    );
    fireEvent.mouseDown(
      screen.getByRole("combobox", { name: "Question version" }),
    );
    const options = screen.getAllByRole("option");
    expect(options.map((option) => option.textContent)).toEqual([
      "None",
      "First",
      "Second",
      "Third",
    ]);
    expect(
      within(screen.getByRole("listbox")).getByRole("option", {
        name: "Third",
      }),
    ).toHaveAttribute("aria-disabled", "true");
    fireEvent.click(screen.getByRole("option", { name: "Third" }));
    expect(
      screen.getByRole("status", { name: "Chosen version" }),
    ).toBeEmptyDOMElement();
    fireEvent.mouseDown(
      screen.getByRole("combobox", { name: "Question version" }),
    );
    await act(async () => {
      fireEvent.click(screen.getByRole("option", { name: "Second" }));
    });
    expect(
      screen.getByRole("status", { name: "Chosen version" }),
    ).toHaveTextContent(SECOND);
  });

  it("keeps a running declaration read-only", async () => {
    render(<Picker current={FIRST} readOnly />);
    expect(
      screen.getByRole("combobox", { name: "Question version" }),
    ).toHaveAttribute("readonly");
    await act(async () => {
      fireEvent.mouseDown(
        screen.getByRole("combobox", { name: "Question version" }),
      );
    });
    expect(screen.queryByRole("listbox")).toBeNull();
    expect(
      screen.getByRole("status", { name: "Chosen version" }),
    ).toHaveTextContent(FIRST);
  });
});
