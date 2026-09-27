import { createContext, useContext, type ReactNode } from "react";

import type { Standing } from "../../api/standing";
import type { Resource } from "../../lib/request/useResource";

/**
 * Null rather than a settled empty read, because the two have to stay tellable
 * apart from inside a component that only ever asks whether a set holds
 * something: a reader the server granted nothing is a fact, a tree assembled
 * without a provider is a mistake, and an empty set is the answer to both.
 */
const StandingContext = createContext<Resource<Standing> | null>(null);

/**
 * Who may go where, handed down — as the read, not as its answer.
 *
 * The read is the caller's to issue and this file knows of no way to make one —
 * no endpoint, no request. Who the reader is has one place it is learnt, and
 * everything downstream of here is handed that one read rather than issuing its
 * own.
 */
export function StandingProvider({
  read,
  children,
}: {
  readonly read: Resource<Standing>;
  readonly children: ReactNode;
}) {
  return <StandingContext value={read}>{children}</StandingContext>;
}

/**
 * Throws where no provider stands above, rather than answering for one.
 *
 * An empty standing is the safe answer and the wrong one: it renders an
 * application with no destinations and a refusal on every screen, which reads
 * exactly like a permission the reader is missing and gets debugged as one for
 * as long as that takes.
 */
export function useStandingRead(): Resource<Standing> {
  const read = useContext(StandingContext);
  if (read === null) {
    throw new Error("useStanding was called outside a StandingProvider");
  }
  return read;
}

/**
 * What the server has said so far, which is nothing until it has answered.
 *
 * For whoever draws less where less is reachable. Whoever refuses on the same
 * answer must take the read instead: an emptiness that is only "not yet" would
 * be shown as a wall and then replaced by the screen behind it.
 */
export function useStanding(): Standing {
  return useStandingRead().value;
}
