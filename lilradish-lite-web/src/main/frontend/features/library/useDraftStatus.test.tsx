import { act, render, screen } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { StrictMode, useContext, useRef, type ContextType } from "react";
import { describe, expect, it } from "vitest";

import {
  DraftStatusContext,
  useDraftPartStatus,
  useDraftStatus,
} from "./useDraftStatus";

describe("useDraftStatus", () => {
  it("counts one logical save once and locates the first pending part in the current DOM order", async () => {
    function Piece({ name, unit }: { name: string; unit: string }) {
      const node = useRef<HTMLElement>(null);
      useDraftPartStatus(name, true, false, { node, unit });
      return (
        <section ref={node}>
          <input aria-label={name} />
        </section>
      );
    }
    function Host({ reversed }: { reversed: boolean }) {
      const { report, unsavedCount, saving, locateFirst } = useDraftStatus(
        "version",
        3,
      );
      const flow = [
        <Piece key="steps" name="Steps" unit="flow" />,
        <Piece key="outputs" name="Outputs" unit="flow" />,
      ];
      return (
        <DraftStatusContext.Provider value={report}>
          <output>{`${unsavedCount} ${saving}`}</output>
          <button onClick={locateFirst}>Locate</button>
          {reversed ? [...flow].reverse() : flow}
          <Piece name="Note" unit="note" />
        </DraftStatusContext.Provider>
      );
    }
    const page = render(
      <StrictMode>
        <Host reversed={false} />
      </StrictMode>,
    );
    expect(screen.getByText("2 false")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Locate" }));
    expect(screen.getByRole("textbox", { name: "Steps" })).toHaveFocus();
    page.rerender(
      <StrictMode>
        <Host reversed />
      </StrictMode>,
    );
    await userEvent.click(screen.getByRole("button", { name: "Locate" }));
    expect(screen.getByRole("textbox", { name: "Outputs" })).toHaveFocus();
    expect(screen.getByText("2 false")).toBeInTheDocument();
  });

  it("blocks on reading without claiming a save and locates the actual saving unit", async () => {
    function Piece({ saving }: { saving: boolean }) {
      const node = useRef<HTMLElement>(null);
      useDraftPartStatus("content", false, true, { saving, node });
      return (
        <section ref={node}>
          <input aria-label="Content" readOnly />
        </section>
      );
    }
    function Host({ saving }: { saving: boolean }) {
      const status = useDraftStatus("version", 1);
      return (
        <DraftStatusContext.Provider value={status.report}>
          <output>{`${status.blocked} ${status.unsavedCount} ${status.saving}`}</output>
          <button onClick={status.locateFirst}>Locate</button>
          <Piece saving={saving} />
        </DraftStatusContext.Provider>
      );
    }
    const page = render(<Host saving={false} />);
    expect(screen.getByText("true 0 false")).toBeInTheDocument();
    page.rerender(<Host saving />);
    expect(screen.getByText("true 0 true")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Locate" }));
    expect(screen.getByRole("textbox", { name: "Content" })).toHaveFocus();
  });
  it("waits again when a part leaves, then accepts its clean replacement", () => {
    function Part() {
      useDraftPartStatus("content", false, false);
      return null;
    }

    function Host({ show }: { readonly show: boolean }) {
      const { report, blocked } = useDraftStatus("version", 1);
      return (
        <DraftStatusContext.Provider value={report}>
          <output>{blocked ? "Wait" : "Ready"}</output>
          {show ? <Part /> : null}
        </DraftStatusContext.Provider>
      );
    }

    const page = render(<Host show />);
    expect(screen.getByText("Ready")).toBeInTheDocument();
    page.rerender(<Host show={false} />);
    expect(screen.getByText("Wait")).toBeInTheDocument();
    page.rerender(<Host show />);
    expect(screen.getByText("Ready")).toBeInTheDocument();
  });

  it.each([
    [1, "Wait"],
    [2, "Ready"],
    [3, "Wait"],
  ] as const)(
    "holds while the page counts %s units for two clean ones reported, both fewer and more",
    (expected, shown) => {
      function Part({ unit }: { readonly unit: string }) {
        useDraftPartStatus(unit, false, false);
        return null;
      }
      function Host() {
        const { report, blocked } = useDraftStatus("version", expected);
        return (
          <DraftStatusContext.Provider value={report}>
            <output>{blocked ? "Wait" : "Ready"}</output>
            <Part unit="content" />
            <Part unit="read" />
          </DraftStatusContext.Provider>
        );
      }

      render(<Host />);

      expect(screen.getByRole("status")).toHaveTextContent(shown);
    },
  );

  it("keeps a new version separate from a late report by the old one, including StrictMode effect replay", () => {
    let oldReport: ContextType<typeof DraftStatusContext> = null;

    function Part({
      version,
      unsaved,
    }: {
      readonly version: string;
      readonly unsaved: boolean;
    }) {
      const report = useContext(DraftStatusContext);
      if (version === "first") {
        oldReport = report;
      }
      useDraftPartStatus("content", unsaved, false);
      return null;
    }

    function Host({
      version,
      unsaved,
    }: {
      readonly version: string;
      readonly unsaved: boolean;
    }) {
      const { report, blocked } = useDraftStatus(version, 2);
      return (
        <DraftStatusContext.Provider value={report}>
          <output>{blocked ? "Wait" : "Ready"}</output>
          <Part version={version} unsaved={unsaved} />
          <ReadStatus />
        </DraftStatusContext.Provider>
      );
    }

    function ReadStatus() {
      useDraftPartStatus("read", false, false);
      return null;
    }

    const page = render(
      <StrictMode>
        <Host version="first" unsaved={false} />
      </StrictMode>,
    );
    expect(screen.getByText("Ready")).toBeInTheDocument();

    page.rerender(
      <StrictMode>
        <Host version="second" unsaved />
      </StrictMode>,
    );
    expect(screen.getByText("Wait")).toBeInTheDocument();
    page.rerender(
      <StrictMode>
        <Host version="second" unsaved={false} />
      </StrictMode>,
    );
    expect(screen.getByText("Ready")).toBeInTheDocument();

    act(() =>
      oldReport?.("content", {
        unsaved: true,
        busy: false,
        saving: false,
        unit: "content",
        locate: () => {},
      }),
    );
    expect(screen.getByText("Ready")).toBeInTheDocument();
  });
});
