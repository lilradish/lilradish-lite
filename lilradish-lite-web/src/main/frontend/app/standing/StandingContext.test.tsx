import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { answered, stillReading } from "../../testutil/standingRead";
import {
  StandingProvider,
  useStanding,
  useStandingRead,
} from "./StandingContext";

/**
 * Renders the standing itself rather than a verdict about it: what is asserted
 * is then what was handed down, not what one call to `holds` made of it.
 */
function Reading() {
  const standing = useStanding();
  return (
    <ul aria-label="standing">
      {[...standing.acts].map((act) => (
        <li key={act}>{act}</li>
      ))}
    </ul>
  );
}

function held(): string[] {
  return screen
    .getAllByRole("listitem")
    .map((entry) => entry.textContent ?? "");
}

describe("StandingProvider", () => {
  it("hands down what the server said, including a word this build cannot name", () => {
    render(
      <StandingProvider
        read={answered(["keep_pool", "commission_a_satellite"])}
      >
        <Reading />
      </StandingProvider>,
    );

    expect(held()).toEqual(["keep_pool", "commission_a_satellite"]);
  });

  /**
   * The distinction the whole context exists to carry. Both render nothing
   * held, and only one of them is an answer — so a reader waiting on the server
   * and a reader the server granted nothing are the same picture to anybody who
   * asks for the standing alone, and different to anybody who asks for the read.
   */
  it("hands down a read still out as an answer of nothing that is not one", () => {
    function Waiting() {
      return <p>{useStandingRead().loading ? "not yet" : "answered"}</p>;
    }

    render(
      <StandingProvider read={stillReading()}>
        <Reading />
        <Waiting />
      </StandingProvider>,
    );

    expect(screen.queryAllByRole("listitem")).toEqual([]);
    expect(screen.getByText("not yet")).toBeInTheDocument();
  });

  /**
   * A reader the server granted nothing is a described reader, and describing
   * them is not the same as failing to. Only this tells the sentinel from the
   * value it stands for.
   */
  it("carries an empty standing as an answer rather than as an absence", () => {
    render(
      <StandingProvider read={answered([])}>
        <Reading />
      </StandingProvider>,
    );

    expect(screen.getByRole("list", { name: "standing" })).toBeInTheDocument();
    expect(screen.queryAllByRole("listitem")).toEqual([]);
  });
});

describe("useStanding", () => {
  /**
   * The one place on this side where being wrong is silent. An empty standing
   * returned for a missing provider renders an application with no
   * destinations and a refusal on every screen — which is a truthful-looking
   * picture of a reader with no permissions, and nothing would ever say
   * otherwise.
   */
  it("throws where nothing provided a standing, rather than inventing one", () => {
    expect(() => render(<Reading />)).toThrow(/StandingProvider/);
    expect(screen.queryByRole("list", { name: "standing" })).toBeNull();
  });
});
