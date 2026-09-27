import { useEffect, useRef } from "react";

/** Keyboard lost to the body counts as in the part once writing is taken away: the control it was on went with it. */
export function usePartFocus(focusOnArrival: boolean, editable: boolean) {
  const section = useRef<HTMLElement>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  const arriving = useRef(focusOnArrival);
  const wasEditable = useRef(editable);
  useEffect(() => {
    if (arriving.current) {
      heading.current?.focus();
    }
  }, []);
  useEffect(() => {
    const within = section.current?.contains(document.activeElement) ?? false;
    if (
      wasEditable.current &&
      !editable &&
      (within || document.activeElement === document.body)
    ) {
      heading.current?.focus();
    }
    wasEditable.current = editable;
  }, [editable]);
  return { section, heading };
}
