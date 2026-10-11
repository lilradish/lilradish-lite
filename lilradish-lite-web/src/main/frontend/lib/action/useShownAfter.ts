import { useEffect, useState } from "react";

/** A new cycle starts its own delay even when the preceding one was still active. */
export function useShownAfter(
  active: boolean,
  delay = 300,
  cycle?: unknown,
): boolean {
  const [timing, setTiming] = useState({ active, delay, cycle, shown: false });
  const same =
    timing.active === active &&
    timing.delay === delay &&
    Object.is(timing.cycle, cycle);
  if (!same) {
    setTiming({ active, delay, cycle, shown: false });
  }
  useEffect(() => {
    if (!active) return;
    const timer = setTimeout(() => {
      setTiming({ active, delay, cycle, shown: true });
    }, delay);
    return () => clearTimeout(timer);
  }, [active, delay, cycle]);
  return active && same && timing.shown;
}
