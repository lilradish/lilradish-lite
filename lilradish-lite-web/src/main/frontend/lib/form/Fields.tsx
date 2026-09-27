import Box from "@mui/material/Box";
import type { ReactNode } from "react";

/**
 * Label-and-value pairs, as the description list they are. The grid belongs
 * here rather than to each screen that shows one: a label column that is one
 * width on this screen and another on the next reads as two layouts of one
 * kind of thing.
 *
 * One column on a narrow viewport. Two columns of `max-content 1fr` need a
 * label column the label actually fits in, and on a phone that is most of the
 * screen.
 */
export function Fields({ children }: { readonly children: ReactNode }) {
  return (
    <Box
      component="dl"
      sx={{
        display: "grid",
        gridTemplateColumns: { xs: "1fr", sm: "max-content 1fr" },
        // Stacked, a row gap would separate a label from its own value as
        // readily as from the next pair. The margin goes on the value instead,
        // so the space falls between pairs and never inside one.
        gap: { xs: "0 0", sm: "0.25rem 1rem" },
        m: "0 0 1rem",
        "& dt": { typography: "term" },
        // A value is often one unbroken identifier, wider than its column.
        "& dd": { m: 0, mb: { xs: 1, sm: 0 }, overflowWrap: "anywhere" },
      }}
    >
      {children}
    </Box>
  );
}

export function Field({
  label,
  children,
}: {
  readonly label: ReactNode;
  readonly children: ReactNode;
}) {
  // A fragment, not an element: the pair belongs to the grid its parent owns,
  // and a wrapper around it would take both cells as one.
  return (
    <>
      <dt>{label}</dt>
      <dd>{children}</dd>
    </>
  );
}
