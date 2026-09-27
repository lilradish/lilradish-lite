import {
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type ChangeEvent,
  type CompositionEvent,
} from "react";

const QUIET_MS = 300;

/**
 * What is typed into one box, shown at once and handed on only once typing
 * pauses. Nothing is handed on mid-composition, where the text is not yet what
 * the reader means to type.
 *
 * `field` goes onto the box whole: the value, and both events that can end a
 * stretch of typing. A single-line box: the handlers read an input's value.
 */
export function useTypingPause(
  initial: string,
  onPause: (typed: string) => void,
) {
  const [text, setText] = useState(initial);

  // Read when the pause ends rather than when it began: a caller's handler
  // closes over its own render, and one from before the pause writes old state.
  const latestOnPause = useRef(onPause);
  const latestText = useRef(text);
  useLayoutEffect(() => {
    latestOnPause.current = onPause;
    latestText.current = text;
  });

  const timer = useRef<ReturnType<typeof setTimeout>>(undefined);
  useEffect(() => () => clearTimeout(timer.current), []);

  function handOnWhenQuiet(typed: string) {
    clearTimeout(timer.current);
    timer.current = setTimeout(() => {
      // Superseded while it waited: by text set from outside, or by a
      // composition begun since.
      if (latestText.current !== typed) {
        return;
      }
      latestOnPause.current(typed);
    }, QUIET_MS);
  }

  return {
    setText,
    field: {
      value: text,
      onChange(event: ChangeEvent<HTMLInputElement>) {
        setText(event.target.value);
        if (!(event.nativeEvent as InputEvent).isComposing) {
          handOnWhenQuiet(event.target.value);
        }
      },
      // Handed on here as well, so it holds in whichever order a composition's
      // last input and its end arrive.
      onCompositionEnd(event: CompositionEvent) {
        if (event.target instanceof HTMLInputElement) {
          handOnWhenQuiet(event.target.value);
        }
      },
    },
  };
}
