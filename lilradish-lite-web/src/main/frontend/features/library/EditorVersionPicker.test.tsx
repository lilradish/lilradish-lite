import { ThemeProvider } from "@mui/material/styles";
import {
  act,
  fireEvent,
  render as renderNow,
  screen,
  waitFor,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { memo, useState } from "react";
import { describe, expect, it, vi } from "vitest";

import type { Standing } from "../../api/standing";
import type { VersionDetail } from "../../api/groups/{groupId}/versions/{versionId}";
import { StandingProvider } from "../../app/standing/StandingContext";
import { theme } from "../../lib/theme/theme";
import { EditorVersionPicker, useVersionDetail } from "./EditorVersionPicker";
import { PinNote } from "./PinNote";

const GROUP = "group";
const FIRST = "first";
const READER: Standing = { userId: "reader", acts: new Set(), groups: [] };
const OTHER: Standing = { ...READER, userId: "other" };
const reload = vi.fn();
vi.mock("../../app/standing/RequireGroup", () => ({
  useGroup: () => ({ groupId: GROUP }),
}));

const candidate = {
  versionId: FIRST,
  name: "Categories",
  number: 2,
  versionName: "Current",
};
const detail = {
  kind: "reference_list",
  version: { ...candidate, standing: "in_service" },
  terms: { [FIRST]: ["urgent", "later"] },
};
const response = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });

function pending() {
  let settle!: (value: Response) => void;
  const promise = new Promise<Response>((resolve) => {
    settle = resolve;
  });
  return { promise, settle };
}

function Host({
  scope = "draft",
  reader = READER,
  disabled = false,
  loading = false,
  searchWay,
}: {
  readonly scope?: string;
  readonly reader?: Standing;
  readonly disabled?: boolean;
  readonly loading?: boolean;
  readonly searchWay?: string;
}) {
  const [chosen, choose] = useState<VersionDetail | null>(null);
  const [commits, count] = useState(0);
  return (
    <ThemeProvider theme={theme}>
      <StandingProvider
        read={{ value: reader, loading, problem: null, reload }}
      >
        <EditorVersionPicker
          kind={searchWay === undefined ? "reference_list" : "question"}
          value={chosen?.version.versionId ?? ""}
          selected={chosen?.version}
          label="Version"
          noneLabel="None"
          scope={scope}
          disabled={disabled}
          searchWay={searchWay}
          onPick={(value) => {
            choose(value);
            count((before) => before + 1);
          }}
        />
        <output aria-label="Selection">
          {chosen === null
            ? "none"
            : JSON.stringify({
                id: chosen.version.versionId,
                terms: [...chosen.terms],
                ...("takes" in chosen
                  ? { takes: chosen.takes, gives: chosen.gives }
                  : {}),
              })}
        </output>
        <output aria-label="Commits">{commits}</output>
      </StandingProvider>
    </ThemeProvider>
  );
}

async function render(...parameters: Parameters<typeof renderNow>) {
  let view!: ReturnType<typeof renderNow>;
  await act(async () => {
    view = renderNow(...parameters);
  });
  return view;
}

async function select() {
  const input = screen.getByRole("combobox", { name: "Version" });
  await act(async () => fireEvent.mouseDown(input));
  await act(async () =>
    fireEvent.change(input, { target: { value: "Categories" } }),
  );
  const option = await screen.findByRole("option", {
    name: /Categories.*Version 2/,
  });
  await act(async () => fireEvent.click(option));
}

function serve(point: () => Promise<Response>, items: unknown[] = [candidate]) {
  const sent = vi
    .fn<typeof fetch>()
    .mockImplementation((address) =>
      String(address).includes("/reference-lists/versions?") ||
      String(address).includes("/questions/versions?")
        ? Promise.resolve(response({ items, more: false }))
        : point(),
    );
  vi.stubGlobal("fetch", sent);
  return sent;
}

describe("editor version detail admission", () => {
  it("keeps the detail reader stable on an unrelated owner edit and uses the current receiver", async () => {
    const renders = vi.fn();
    const received = vi.fn();
    const held = pending();
    serve(() => held.promise);
    const Row = memo(function Row({
      read,
    }: {
      readonly read: (id: string) => void;
    }) {
      renders();
      return (
        <button onClick={() => read(FIRST)}>Take the current version</button>
      );
    });
    function Owner() {
      const [label, setLabel] = useState("Before");
      const { read } = useVersionDetail({
        kind: "reference_list",
        scope: "same-owner",
        disabled: false,
        onRead: (detail) => received(label, detail?.version.versionId),
      });
      return (
        <>
          <input
            aria-label="Another field"
            value={label}
            onChange={(event) => setLabel(event.target.value)}
          />
          <Row read={read} />
        </>
      );
    }
    await render(
      <StandingProvider
        read={{ value: READER, loading: false, problem: null, reload }}
      >
        <Owner />
      </StandingProvider>,
    );
    await act(async () =>
      fireEvent.click(
        screen.getByRole("button", { name: "Take the current version" }),
      ),
    );
    const before = renders.mock.calls.length;
    await act(async () =>
      fireEvent.change(screen.getByRole("textbox", { name: "Another field" }), {
        target: { value: "After" },
      }),
    );
    expect(renders).toHaveBeenCalledTimes(before);
    await act(async () => held.settle(response(detail)));
    expect(received).toHaveBeenCalledWith("After", FIRST);
  });

  it("shows the bounded continuation and disabled reason on the actual twenty-row response", async () => {
    const items = Array.from({ length: 20 }, (_, index) => ({
      ...candidate,
      versionId: `question-${index}`,
      number: index + 1,
      searchWay: "web",
      searchMatches: index !== 0,
    }));
    const sent = vi
      .fn<typeof fetch>()
      .mockResolvedValue(response({ items, more: true }));
    vi.stubGlobal("fetch", sent);
    await render(<Host searchWay="web" />);
    const input = screen.getByRole("combobox", { name: "Version" });
    await act(async () => fireEvent.mouseDown(input));
    await act(async () =>
      fireEvent.change(input, { target: { value: "Categories" } }),
    );
    expect(
      await screen.findByText(
        "More match than are shown; type more to narrow them.",
      ),
    ).toBeVisible();
    const options = screen.getAllByRole("option");
    expect(options).toHaveLength(21);
    expect(
      options
        .slice(1)
        .map((option) => option.textContent?.match(/Version (\d+)/)?.[1]),
    ).toEqual([
      "1",
      "2",
      "3",
      "4",
      "5",
      "6",
      "7",
      "8",
      "9",
      "10",
      "11",
      "12",
      "13",
      "14",
      "15",
      "16",
      "17",
      "18",
      "19",
      "20",
    ]);
    expect(options[1]).toHaveAttribute("aria-disabled", "true");
    await act(async () => fireEvent.click(options[1]!));
    expect(sent).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("status", { name: "Commits" })).toHaveTextContent(
      "0",
    );
  });
  it("debounces successive typing and abandons an issued collection when cleared", async () => {
    vi.useFakeTimers();
    try {
      const deferred = pending();
      const sent = vi
        .fn<typeof fetch>()
        .mockImplementation(() => deferred.promise);
      vi.stubGlobal("fetch", sent);
      const view = await render(<Host />);
      const input = screen.getByRole("combobox", { name: "Version" });
      await act(async () => fireEvent.mouseDown(input));
      for (const value of ["C", "Ca", "Categories"]) {
        await act(async () => fireEvent.change(input, { target: { value } }));
        await act(async () => vi.advanceTimersByTimeAsync(149));
        expect(sent).not.toHaveBeenCalled();
      }
      await act(async () => vi.advanceTimersByTimeAsync(1));
      expect(sent).toHaveBeenCalledTimes(1);
      expect(String(sent.mock.calls[0]![0])).toContain("filter=Categories");
      await act(async () => fireEvent.change(input, { target: { value: "" } }));
      await act(async () =>
        deferred.settle(response({ items: [candidate], more: true })),
      );
      await act(async () => vi.advanceTimersByTimeAsync(150));
      expect(sent.mock.calls[0]![1]?.signal?.aborted).toBe(true);
      expect(sent).toHaveBeenCalledTimes(1);
      expect(screen.queryByRole("option")).toBeNull();
      expect(screen.getByRole("status", { name: "Commits" })).toHaveTextContent(
        "1",
      );
      expect(
        screen.getByRole("status", { name: "Selection" }),
      ).toHaveTextContent("none");
      await act(async () => view.unmount());
    } finally {
      vi.useRealTimers();
    }
  });

  it("stands its label above the box it names, none floating inside it, and a press on the label puts the keyboard in the box", async () => {
    serve(() => Promise.resolve(response(detail)), []);
    await render(<Host />);
    const input = screen.getByRole("combobox", { name: "Version" });
    const box = input.closest(".MuiInputBase-root")!;
    const label = box.previousElementSibling!;

    expect(label.tagName).toBe("LABEL");
    expect(label.textContent).toBe("Version");
    expect(
      input
        .closest(".MuiFormControl-root")!
        .querySelector(".MuiInputLabel-root"),
    ).toBeNull();

    await userEvent.click(label);

    expect(input).toHaveFocus();
  });

  it("holds the complete chosen label in the box, with its name quieter and no Runs line beneath it", async () => {
    serve(() => Promise.resolve(response(detail)));
    await render(<Host />);
    const input = screen.getByRole("combobox", { name: "Version" });
    const field = input.closest(".MuiInputBase-root")!;
    const picker = field.closest(".MuiAutocomplete-root")!;
    expect(picker.nextElementSibling?.tagName).not.toBe("P");

    await select();

    await waitFor(() =>
      expect(screen.getByRole("status", { name: "Commits" })).toHaveTextContent(
        "1",
      ),
    );
    expect(input).toHaveValue(
      "\u2068Categories\u2069 \u00b7 Version 2 \u00b7 \u2068Current\u2069",
    );
    expect(picker.nextElementSibling?.tagName).not.toBe("P");
    const chosen = field.querySelector('[aria-hidden="true"]')!;
    expect(chosen).toHaveTextContent(
      "Categories \u00b7 Version 2 \u00b7 Current",
    );
    expect(chosen.querySelectorAll("bdi")[1]).toHaveStyle({
      color: "rgb(88, 102, 108)",
    });
    expect(input).not.toHaveAttribute("title");
    expect(field.previousElementSibling!.tagName).toBe("LABEL");
    expect(field.previousElementSibling!.previousElementSibling).toBeNull();
  });

  it.each(["pointer", "keyboard"] as const)(
    "shows the complete chosen label in a real Tooltip on %s",
    async (way) => {
      serve(() => Promise.resolve(response(detail)));
      await render(<Host />);
      await select();
      const input = screen.getByRole("combobox", { name: "Version" });
      await waitFor(() =>
        expect(input).toHaveValue(
          "\u2068Categories\u2069 \u00b7 Version 2 \u00b7 \u2068Current\u2069",
        ),
      );
      const user = userEvent.setup();
      if (way === "pointer") await user.hover(input);
      else {
        await user.tab();
        await user.tab({ shift: true });
      }
      const tip = await screen.findByRole("tooltip");
      expect(tip).toHaveTextContent("Categories");
      expect(tip).toHaveTextContent("Version 2");
      expect(tip).toHaveTextContent("Current");
      expect(input).not.toHaveAttribute("title");
    },
  );

  it.each([undefined, "None"])(
    "keeps a long chosen label complete with keyboard help and wrapping options, None label %s",
    async (noneLabel) => {
      const long = {
        ...candidate,
        name: "Complaint classification for exceptional evidence and regional review",
        versionName:
          "Evidence review for exceptionally detailed regional complaints",
      };
      serve(
        () =>
          Promise.resolve(
            response({
              ...detail,
              version: { ...long, standing: "in_service" },
            }),
          ),
        [long],
      );
      await render(
        <ThemeProvider theme={theme}>
          <StandingProvider
            read={{ value: READER, loading: false, problem: null, reload }}
          >
            <EditorVersionPicker
              kind="reference_list"
              value={FIRST}
              selected={long}
              label="Version"
              noneLabel={noneLabel}
              scope="long-chosen"
              onPick={() => undefined}
            />
          </StandingProvider>
        </ThemeProvider>,
      );
      const input = screen.getByRole("combobox", { name: "Version" });
      expect(input).toHaveValue(
        `\u2068${long.name}\u2069 \u00b7 Version 2 \u00b7 \u2068${long.versionName}\u2069`,
      );
      const field = input.closest(".MuiInputBase-root")!;
      const chosen = field.querySelector('[aria-hidden="true"]')!;
      expect(chosen).toHaveTextContent(
        `${long.name} \u00b7 Version 2 \u00b7 ${long.versionName}`,
      );
      expect(chosen).toHaveStyle({
        position: "absolute",
        pointerEvents: "none",
        whiteSpace: "nowrap",
        overflow: "hidden",
        textOverflow: "ellipsis",
        insetInlineEnd: noneLabel === undefined ? "36px" : "62px",
      });
      expect(chosen).not.toContainElement(
        screen.getByRole("button", { name: "Open" }),
      );
      expect(chosen.querySelectorAll("bdi")[1]).toHaveStyle({
        color: "rgb(88, 102, 108)",
      });
      const user = userEvent.setup();
      await user.tab();
      expect(input).toHaveFocus();
      expect(await screen.findByRole("tooltip")).toHaveTextContent(
        `\u2068${long.name}\u2069 \u00b7 Version 2 \u00b7 \u2068${long.versionName}\u2069`,
      );
      expect(input).not.toHaveAttribute("title");
      await act(async () => fireEvent.mouseDown(input));
      await act(async () =>
        fireEvent.change(input, { target: { value: "Complaint" } }),
      );
      const option = await screen.findByRole("option", {
        name: /Complaint classification.*Version 2.*Evidence review/,
      });
      expect(option).toHaveTextContent(
        `${long.name} \u00b7 Version 2 \u00b7 ${long.versionName}`,
      );
      expect(option).toHaveStyle({
        whiteSpace: "normal",
        overflowWrap: "anywhere",
      });
    },
  );

  it("keeps the newer note and Take label complete with wrapping declarations, and takes that version", async () => {
    const newer = {
      versionId: "newer",
      number: 3,
      versionName:
        "Evidence review for exceptionally detailed regional complaints",
    };
    const take = vi.fn();
    await render(
      <ThemeProvider theme={theme}>
        <PinNote
          pinned={{ ...candidate, standing: "in_service", newer }}
          take={take}
        />
      </ThemeProvider>,
    );
    const note = screen.getByText(/A newer version is in service/);
    expect(note).toHaveTextContent(
      `Categories \u00b7 Version 3 \u00b7 ${newer.versionName}`,
    );
    expect(note).toHaveStyle({ overflowWrap: "anywhere" });
    const button = screen.getByRole("button", {
      name: /Take Version 3.*Evidence review/,
    });
    expect(button).toHaveTextContent(
      `Take Version 3 \u00b7 ${newer.versionName}`,
    );
    expect(button).toHaveStyle({
      maxWidth: "100%",
      whiteSpace: "normal",
      overflowWrap: "anywhere",
      textAlign: "start",
    });
    expect(button.querySelector("span")).toHaveStyle({ minWidth: "0" });
    await userEvent.click(button);
    expect(take).toHaveBeenCalledExactlyOnceWith("newer");
  });

  it("uses the approved no-match and continuation wording without manufacturing candidates", async () => {
    const sent = serve(() => Promise.resolve(response(detail)), []);
    await render(<Host />);
    const input = screen.getByRole("combobox", { name: "Version" });
    await act(async () => fireEvent.mouseDown(input));
    await act(async () =>
      fireEvent.change(input, { target: { value: "Absent" } }),
    );
    await screen.findByText("No version in service matches.");
    expect(
      screen.getAllByRole("option").map((item) => item.textContent),
    ).toEqual(["None"]);
    expect(sent).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("status", { name: "Commits" })).toHaveTextContent(
      "0",
    );
  });

  it("does not fetch an empty fragment and commits one complete ordered detail only after its response", async () => {
    const deferred = pending();
    const sent = serve(() => deferred.promise);
    await render(<Host />);
    await act(async () =>
      fireEvent.mouseDown(screen.getByRole("combobox", { name: "Version" })),
    );
    expect(sent).not.toHaveBeenCalled();
    expect(screen.queryByRole("option")).toBeNull();
    await select();
    expect(screen.getByRole("status", { name: "Selection" })).toHaveTextContent(
      "none",
    );
    expect(screen.getByRole("status", { name: "Commits" })).toHaveTextContent(
      "0",
    );
    expect(sent.mock.calls.map(([address]) => String(address))).toEqual([
      "/api/groups/group/reference-lists/versions?filter=Categories",
      "/api/groups/group/versions/first",
    ]);
    expect(
      sent.mock.calls.every(([, init]) => (init?.method ?? "GET") === "GET"),
    ).toBe(true);
    await act(async () => deferred.settle(response(detail)));
    await waitFor(() =>
      expect(screen.getByRole("status", { name: "Commits" })).toHaveTextContent(
        "1",
      ),
    );
    expect(
      JSON.parse(
        screen.getByRole("status", { name: "Selection" }).textContent!,
      ),
    ).toEqual({ id: FIRST, terms: [[FIRST, ["urgent", "later"]]] });
  });

  it.each([
    [
      "wrong ID",
      { ...detail, version: { ...detail.version, versionId: "other" } },
    ],
    [
      "retired",
      { ...detail, version: { ...detail.version, standing: "retired" } },
    ],
    ["wrong kind", { ...detail, kind: "workflow", takes: [], gives: [] }],
    ["missing terms", { ...detail, terms: {} }],
    [
      "missing nullable name",
      { ...detail, version: { ...detail.version, versionName: undefined } },
    ],
  ])(
    "preserves the owner when the independent detail is %s",
    async (_why, body) => {
      serve(() => Promise.resolve(response(body)));
      await render(<Host />);
      await select();
      await screen.findByText("not_a_problem_document");
      expect(screen.getByRole("alert")).not.toBeEmptyDOMElement();
      expect(
        screen.getByRole("status", { name: "Selection" }),
      ).toHaveTextContent("none");
      expect(screen.getByRole("status", { name: "Commits" })).toHaveTextContent(
        "0",
      );
    },
  );

  it("preserves the owner and reports an actual point refusal", async () => {
    serve(() =>
      Promise.resolve(response({ code: "VERSION_NOT_IN_VIEW" }, 404)),
    );
    await render(<Host />);
    await select();
    await screen.findByText("That version is not in this group's library.");
    expect(screen.getByRole("status", { name: "Commits" })).toHaveTextContent(
      "0",
    );
  });

  it.each(["scope", "identity", "permission", "loading"])(
    "never revives a pending detail after a %s round trip",
    async (changed) => {
      const deferred = pending();
      const sent = serve(() => deferred.promise);
      const view = await render(<Host />);
      await select();
      const changedProps =
        changed === "scope"
          ? { scope: "recreated" }
          : changed === "identity"
            ? { reader: OTHER }
            : changed === "permission"
              ? { disabled: true }
              : { loading: true };
      view.rerender(<Host {...changedProps} />);
      view.rerender(<Host />);
      await act(async () => deferred.settle(response(detail)));
      expect(
        sent.mock.calls.find(([address]) =>
          String(address).endsWith("/versions/first"),
        )![1]?.signal?.aborted,
      ).toBe(true);
      expect(screen.getByRole("status", { name: "Commits" })).toHaveTextContent(
        "0",
      );
      expect(
        screen.getByRole("status", { name: "Selection" }),
      ).toHaveTextContent("none");
    },
  );

  it("ignores a late refusal as well as success after the owner is replaced", async () => {
    const deferred = pending();
    serve(() => deferred.promise);
    const view = await render(<Host />);
    await select();
    view.rerender(<Host scope="replacement" />);
    await act(async () =>
      deferred.settle(response({ code: "VERSION_NOT_IN_VIEW" }, 404)),
    );
    expect(screen.getByRole("status", { name: "Commits" })).toHaveTextContent(
      "0",
    );
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("aborts a detail on unmount without handing it to a replacement owner", async () => {
    const deferred = pending();
    const sent = serve(() => deferred.promise);
    const view = await render(<Host />);
    await select();
    view.unmount();
    await render(<Host />);
    await act(async () => deferred.settle(response(detail)));
    expect(
      sent.mock.calls.find(([address]) =>
        String(address).endsWith("/versions/first"),
      )![1]?.signal?.aborted,
    ).toBe(true);
    expect(screen.getByRole("status", { name: "Commits" })).toHaveTextContent(
      "0",
    );
  });

  it("clears through None without a point request", async () => {
    const sent = serve(() => Promise.resolve(response(detail)));
    await render(<Host />);
    await select();
    await waitFor(() =>
      expect(screen.getByRole("status", { name: "Commits" })).toHaveTextContent(
        "1",
      ),
    );
    const pointCalls = sent.mock.calls.filter(([address]) =>
      String(address).endsWith("/versions/first"),
    ).length;
    const input = screen.getByRole("combobox", { name: "Version" });
    await act(async () => fireEvent.mouseDown(input));
    await act(async () =>
      fireEvent.change(input, { target: { value: "Categories" } }),
    );
    await act(async () =>
      fireEvent.click(await screen.findByRole("option", { name: "None" })),
    );
    expect(
      sent.mock.calls.filter(([address]) =>
        String(address).endsWith("/versions/first"),
      ),
    ).toHaveLength(pointCalls);
    expect(screen.getByRole("status", { name: "Commits" })).toHaveTextContent(
      "2",
    );
  });

  it("keeps an unqualified search candidate visible and keyboard discoverable while refusing selection", async () => {
    const sent = serve(
      () => Promise.resolve(response(detail)),
      [{ ...candidate, searchMatches: false }],
    );
    await render(<Host searchWay="web" />);
    const input = screen.getByRole("combobox", { name: "Version" });
    await act(async () => fireEvent.mouseDown(input));
    await act(async () =>
      fireEvent.change(input, { target: { value: "Categories" } }),
    );
    const option = await screen.findByRole("option", {
      name: /Categories.*Version 2/,
    });
    expect(option).toHaveAttribute("aria-disabled", "true");
    await act(async () => fireEvent.click(option));
    await act(async () => fireEvent.keyDown(input, { key: "ArrowDown" }));
    await act(async () => fireEvent.keyDown(input, { key: "ArrowDown" }));
    expect(input).toHaveAttribute("aria-activedescendant", option.id);
    await act(async () => fireEvent.keyDown(input, { key: "Enter" }));
    expect(screen.getByRole("status", { name: "Commits" })).toHaveTextContent(
      "0",
    );
    expect(
      sent.mock.calls.every(
        ([address]) => !String(address).endsWith("/versions/first"),
      ),
    ).toBe(true);
  });
});
