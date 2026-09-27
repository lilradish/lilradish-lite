import { useState, type ReactNode } from "react";

import { holds, type Standing, type SurfaceAct } from "../../api/standing";
import { Notice } from "../../lib/notice/Notice";
import { Async } from "../Async";
import { ruleOf } from "./actRules";
import { useStandingRead } from "./StandingContext";

/**
 * A screen, shown only to a reader who may be on it.
 *
 * Refused where it stands rather than redirected: moving someone elsewhere
 * without a word leaves them to work out what became of the address they
 * followed. Not answered as missing either — a screen that exists and is
 * withheld is a different thing from one that is not there, and saying the
 * second is a lie the reader has no way to catch.
 *
 * The frame and its destinations are left standing underneath, so the way out
 * is the way the reader came in.
 */
export function RequireStanding({
  act,
  children,
}: {
  readonly act: SurfaceAct;
  readonly children: ReactNode;
}) {
  return (
    <OnceAnswered>
      {(standing) =>
        holds(standing, act) ? <>{children}</> : <Refused rule={ruleOf(act)} />
      }
    </OnceAnswered>
  );
}

/**
 * The standing a gate decides on: waited for until first answered, so an empty
 * "not yet" is never a wall, then the last answer while it is read again.
 */
export function OnceAnswered({
  children,
}: {
  readonly children: (standing: Standing) => ReactNode;
}) {
  const read = useStandingRead();
  const [answered, setAnswered] = useState<{
    readonly standing: Standing;
  } | null>(null);
  if (
    !read.loading &&
    read.problem === null &&
    answered?.standing !== read.value
  ) {
    setAnswered({ standing: read.value });
  }
  const decided =
    read.loading && answered !== null
      ? { ...read, value: answered.standing, loading: false }
      : read;

  return (
    <Async read={decided} empty={() => null}>
      {children}
    </Async>
  );
}

/**
 * A screen refused in the words of the rule, this side's own sentence: never a
 * minted problem document, which is the shape a served refusal arrives in.
 */
export function Refused({ rule }: { readonly rule: string }) {
  // Error though nothing failed: it replaces the screen asked for, and there
  // is nothing to retry.
  return (
    <Notice severity="error" alert>
      {rule}
    </Notice>
  );
}
