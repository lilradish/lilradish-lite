import { render, screen } from "@testing-library/react";
import { useRef } from "react";
import { describe, expect, it, vi } from "vitest";

import { laidOutAt } from "../../testutil/layout";
import { useNarrowerThan } from "./useNarrowerThan";

function Frame({ threshold = 560 }: { readonly threshold?: number }) {
  const ref = useRef<HTMLDivElement>(null);
  const narrow = useNarrowerThan(ref, threshold);
  return <div ref={ref}>{narrow ? "Narrow" : "Wide"}</div>;
}

describe("useNarrowerThan", () => {
  it("uses the container width and responds to both sides of the threshold", async () => {
    const resize = laidOutAt(559, 1200);
    render(<Frame />);
    expect(screen.getByText("Narrow")).toBeInTheDocument();
    await resize(560);
    expect(screen.getByText("Wide")).toBeInTheDocument();
    await resize(402);
    expect(screen.getByText("Narrow")).toBeInTheDocument();
    await resize(0);
    expect(screen.getByText("Wide")).toBeInTheDocument();
  });

  it("rechecks a changed threshold and disconnects after unmount", async () => {
    const resize = laidOutAt(600, 1200);
    const disconnected = vi.spyOn(ResizeObserver.prototype, "disconnect");
    const { rerender, unmount } = render(<Frame />);
    expect(screen.getByText("Wide")).toBeInTheDocument();
    rerender(<Frame threshold={700} />);
    expect(screen.getByText("Narrow")).toBeInTheDocument();
    expect(disconnected).toHaveBeenCalledOnce();
    unmount();
    expect(disconnected).toHaveBeenCalledTimes(2);
    expect(disconnected.mock.contexts[1]).not.toBe(
      disconnected.mock.contexts[0],
    );
    await resize(800);
    expect(screen.queryByText(/Wide|Narrow/)).toBeNull();
  });

  it("stays wide when ResizeObserver is absent, even if the frame could be measured", () => {
    laidOutAt(402, 1200);
    vi.stubGlobal("ResizeObserver", undefined);
    render(<Frame />);
    expect(screen.getByText("Wide")).toBeInTheDocument();
    expect(screen.queryByText("Narrow")).toBeNull();
  });
});
