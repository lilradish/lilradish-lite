import { act, render, screen } from "@testing-library/react";
import { StrictMode, useContext, type ContextType } from "react";
import { describe, expect, it } from "vitest";

import {
  DraftStatusContext,
  useDraftPartStatus,
  useDraftStatus,
} from "./useDraftStatus";

describe("useDraftStatus", () => {
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

    act(() => oldReport?.("content", { unsaved: true, busy: false }));
    expect(screen.getByText("Ready")).toBeInTheDocument();
  });
});
