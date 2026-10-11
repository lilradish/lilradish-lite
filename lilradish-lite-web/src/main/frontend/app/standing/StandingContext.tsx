import { createContext, useContext, type ReactNode } from "react";

import type { Standing } from "../../api/standing";
import type { Resource } from "../../lib/request/useResource";

/**
 * Null distinguishes a missing provider from a reader granted nothing;
 * an empty standing would silently answer both alike.
 */
const StandingContext = createContext<Resource<Standing> | null>(null);

/**
 * Shares the read, including its status. The caller issues it; this file has
 * no endpoint or request. Everything below shares that single source of identity.
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
 * Throws outside a provider: answering empty would hide a malformed tree
 * behind missing destinations and refusals that look like missing permissions.
 */
export function useStandingRead(): Resource<Standing> {
  const read = useContext(StandingContext);
  if (read === null) {
    throw new Error("useStandingRead was called outside a StandingProvider");
  }
  return read;
}

/**
 * Empty until answered; use it to hide unreachable controls. To refuse access,
 * take the read instead, or loading's emptiness would flash a permission wall.
 */
export function useStanding(): Standing {
  return useStandingRead().value;
}
