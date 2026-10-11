import { useLayoutEffect, useState, type RefObject } from "react";

export function useNarrowerThan(
  ref: RefObject<HTMLElement | null>,
  width: number,
): boolean {
  const [narrow, setNarrow] = useState(false);
  useLayoutEffect(() => {
    const element = ref.current;
    if (element === null || typeof ResizeObserver === "undefined") return;
    const measure = () => {
      const measured = element.getBoundingClientRect().width;
      setNarrow(measured > 0 && measured < width);
    };
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(element);
    return () => observer.disconnect();
  }, [ref, width]);
  return narrow;
}
