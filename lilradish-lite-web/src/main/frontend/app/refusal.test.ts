import { describe, expect, it } from "vitest";

import {
  BROKEN_HERE,
  NOT_A_PROBLEM_DOCUMENT,
  NOT_AN_ADDRESS,
  NOT_REACHED,
  STOPPED_HERE,
} from "../api/problem";
import {
  movesTheReader,
  refusalSentence,
  refusesTheAct,
  refusesTheGroup,
} from "./refusal";

const UNWORDED = "The server refused this request.";

describe("refusalSentence", () => {
  it("tells a request stopped from this screen, one never sent, one that never came back, and this side's own fault apart", () => {
    expect(refusalSentence({ code: STOPPED_HERE })).toBe(
      "The request was stopped from this screen.",
    );
    expect(refusalSentence({ code: NOT_AN_ADDRESS })).toBe(
      "That address does not name anybody here.",
    );
    expect(refusalSentence({ code: NOT_REACHED })).toBe(
      "The server could not be reached.",
    );
    expect(refusalSentence({ code: BROKEN_HERE })).toBe(
      "Something went wrong on this page.",
    );
  });

  /**
   * Only the status says whether anything answered at all — which is the
   * difference between a deployment that is down and one that answered with a
   * gateway's HTML error page.
   */
  it("reads the status before the code, so a response nobody could read is not silence", () => {
    const unreadable = refusalSentence({
      status: 502,
      code: NOT_A_PROBLEM_DOCUMENT,
    });

    expect(unreadable).toBe(
      "The server refused this, in a way this side cannot read.",
    );
    expect(unreadable).not.toBe(
      refusalSentence({ code: NOT_A_PROBLEM_DOCUMENT }),
    );
  });

  /**
   * Every sentence this side owns, named exactly as a server would have to name
   * it to be answered with one. A proxy writes its own error bodies and this
   * code reads them, so "the server cannot be reached" and "this screen stopped
   * it" are both things a 502 could otherwise be made to say.
   *
   * `unworded` is absent because nothing could tell: its sentence is the one a
   * code with no entry already gets.
   */
  it.each([
    "stopped",
    "unreachable",
    "unreadable",
    "disclosure",
    "notAnAddress",
    "broken",
    STOPPED_HERE,
    NOT_AN_ADDRESS,
    NOT_REACHED,
    BROKEN_HERE,
  ])(
    "answers a served code spelt like this side's own %s as served",
    (code) => {
      expect(refusalSentence({ status: 502, code })).toBe(UNWORDED);
    },
  );

  it.each([
    ["NOT_SIGNED_IN", "You are not signed in."],
    ["ACT_NOT_PERMITTED", "This is done only by a role that reaches it."],
    [
      "ORIGIN_UNVERIFIED",
      "A change is only accepted from this application's own pages.",
    ],
    ["LIST_SORT_UNUSABLE", "This list cannot be sorted that way."],
    [
      "LIST_FILTER_UNUSABLE",
      "That filter is too long, or holds a character that cannot be searched for.",
    ],
    [
      "PARAMETER_UNKNOWN",
      "This was asked for in a way it does not understand.",
    ],
    [
      "LIST_CURSOR_UNUSABLE",
      "Your place in this list was lost. Start again from the first page.",
    ],
    ["PERSON_NOT_IN_VIEW", "That person is not in the pool."],
    ["BAD_REQUEST", "Some of what was sent was not accepted."],
    ["INTERNAL", "Something went wrong on the server."],
  ])("says what %s means to the person who met it", (code, sentence) => {
    expect(refusalSentence({ status: 403, code })).toBe(sentence);
  });

  /** The control knows what it asked for, and the code alone cannot say which rule that was. */
  it("says a refusal of the act as the rule of the act that was asked for, where it is known", () => {
    expect(
      refusalSentence(
        { status: 403, code: "ACT_NOT_PERMITTED" },
        { act: "grant_estate_role" },
      ),
    ).toBe(
      "Estate roles are granted and withdrawn only by a role that may grant them.",
    );
  });

  /** Asked inside a group, the rule is the permission the control asked there. */
  it("says a refusal of the act as the rule of the permission asked inside a group, where it is known", () => {
    expect(
      refusalSentence(
        { status: 403, code: "ACT_NOT_PERMITTED" },
        { inGroup: "change_membership" },
      ),
    ).toBe(
      "A group's membership is changed only by a role in it that may change it.",
    );
  });

  it("says any other refusal inside a group in its own words, whatever permission was asked", () => {
    expect(
      refusalSentence(
        { status: 409, code: "LAST_MEMBERSHIP_CHANGER" },
        { inGroup: "change_membership" },
      ),
    ).toBe("Nobody else in this group could change its membership.");
  });

  it("says any other refusal in its own words, whatever act was asked for", () => {
    expect(
      refusalSentence(
        { status: 409, code: "LAST_ESTATE_ROLE_GRANTOR" },
        { act: "grant_estate_role" },
      ),
    ).toBe("Withdrawing this would leave nobody who may grant an estate role.");
  });

  /** Only a served refusal can be one of the act; a request that never arrived was refused by nothing. */
  it("says a refusal of the act with no status as the request that never arrived", () => {
    expect(
      refusalSentence({ code: "ACT_NOT_PERMITTED" }, { act: "keep_pool" }),
    ).toBe("The server could not be reached.");
  });

  /**
   * A code minted after this bundle was built still has to say something. The
   * code itself is not it: it is a machine word, and it is already carried
   * where whoever can act on it will look.
   */
  it("still says something for a code it has never been given words for", () => {
    const said = refusalSentence({ status: 429, code: "QUOTA_EXHAUSTED" });

    expect(said).toBe(UNWORDED);
    expect(said).not.toContain("QUOTA");
  });

  /**
   * An address naming no operation withholds whether anything is there, where
   * every other refusal concedes it; a sentence saying which gives that back.
   */
  it("leaves NOT_FOUND ambiguous between absent and withheld", () => {
    expect(refusalSentence({ status: 404, code: "NOT_FOUND" })).toBe(
      "That is not here, or is not yours to see.",
    );
  });
});

describe("refusesTheAct", () => {
  it("takes a served refusal of the act for one", () => {
    expect(refusesTheAct({ status: 403, code: "ACT_NOT_PERMITTED" })).toBe(
      true,
    );
  });

  /** Only a served refusal can be one of the act; the code alone, minted nowhere here, is not. */
  it.each([
    ["the same code with no status", { code: "ACT_NOT_PERMITTED" }],
    ["another refusal", { status: 409, code: "LAST_ESTATE_ROLE_GRANTOR" }],
  ])("takes %s for something else", (_case, problem) => {
    expect(refusesTheAct(problem)).toBe(false);
  });
});

describe("movesTheReader", () => {
  it.each([
    ["a refusal of the act", { status: 403, code: "ACT_NOT_PERMITTED" }, true],
    [
      "a group none of theirs",
      { status: 404, code: "GROUP_NOT_IN_VIEW" },
      true,
    ],
    ["a member not there", { status: 404, code: "MEMBER_NOT_IN_VIEW" }, false],
    ["a request never sent", { code: "ACT_NOT_PERMITTED" }, false],
  ])("takes %s as moving the reader: %s", (_case, problem, moves) => {
    expect(movesTheReader(problem)).toBe(moves);
  });
});

describe("refusesTheGroup", () => {
  it("takes a served answer that the group is none of the reader's for one", () => {
    expect(refusesTheGroup({ status: 404, code: "GROUP_NOT_IN_VIEW" })).toBe(
      true,
    );
  });

  /** A member the group does not hold is a fact about the member, not about the reader. */
  it.each([
    ["the same code with no status", { code: "GROUP_NOT_IN_VIEW" }],
    ["somebody not in the group", { status: 404, code: "MEMBER_NOT_IN_VIEW" }],
    ["a refusal of the act", { status: 403, code: "ACT_NOT_PERMITTED" }],
  ])("takes %s for something else", (_case, problem) => {
    expect(refusesTheGroup(problem)).toBe(false);
  });
});
