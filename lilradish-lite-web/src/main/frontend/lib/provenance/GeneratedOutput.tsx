import Box from "@mui/material/Box";
import Paper from "@mui/material/Paper";
import { useId } from "react";

import { say } from "../../i18n/lib";

/**
 * What a model wrote, marked as such.
 *
 * Two kinds of text sit side by side on a step: the system's own words and what
 * a model wrote. A reviewer answers for approving the result, so which sentence
 * is whose has to be readable at a glance.
 *
 * The mark belongs to this component rather than to its callers. There is then
 * no arrangement of callers that puts generated text on a screen without it.
 *
 * `output` holds the text because that is what the region is — the result of an
 * act, not prose the page wrote — which is the mark's claim made in markup. It
 * is not here to announce anything; announcing is off, deliberately; and it is
 * form-associated, so a caller must not wrap it in a form it resets.
 */
export function GeneratedOutput({ text }: { readonly text: string }) {
  // A step shows output still arriving beside output already recorded, so this
  // renders twice on one page and a fixed id would label both with the first.
  const markId = useId();

  return (
    <Paper
      component="section"
      variant="outlined"
      aria-labelledby={markId}
      sx={{
        p: 2,
        // Longhands, not `borderLeft: 4`: that shorthand expands to `4px solid`
        // with no colour, resetting the rule to currentColor if it sorts last.
        borderLeftWidth: 4,
        borderLeftStyle: "solid",
        borderLeftColor: "generated.main",
      }}
    >
      <Box
        component="span"
        id={markId}
        // Words, not only a colour: a reader who cannot tell the rule from any
        // other rule still reads who wrote the text underneath it.
        sx={{
          display: "block",
          mb: 1,
          color: "generated.main",
          typography: "tag",
        }}
      >
        {say("output.generated")}
      </Box>
      {/* Not redundant: role=status implies aria-live polite and aria-atomic
          true, so without this every change re-reads the whole output aloud. */}
      <Box
        component="output"
        aria-live="off"
        sx={{
          display: "block",
          whiteSpace: "pre-wrap",
          // A model writes what it writes, including a line longer than the
          // card it lands in with nowhere in it to break.
          overflowWrap: "anywhere",
          minHeight: "1.5rem",
        }}
      >
        {text}
      </Box>
    </Paper>
  );
}
