import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import { useId, type ReactNode } from "react";

const MESSAGE_SX = {
  py: 2,
  borderBottomWidth: 1,
  borderBottomStyle: "solid",
  borderBottomColor: "divider",
};

/** One message of a conversation, named by its title; one found by an anchor may take the keyboard. */
export function Message({
  anchor,
  title,
  titleId: givenTitleId,
  children,
}: {
  readonly anchor?: string;
  /** Shown as given, so a name in it arrives isolated. */
  readonly title: string;
  /** Where the message's own controls are described by its title. */
  readonly titleId?: string;
  readonly children: ReactNode;
}) {
  const ownTitleId = useId();
  const titleId = givenTitleId ?? ownTitleId;
  return (
    <Box
      component="li"
      id={anchor}
      tabIndex={anchor === undefined ? undefined : -1}
      aria-labelledby={titleId}
      sx={MESSAGE_SX}
    >
      <Typography id={titleId} variant="h6" component="h3">
        {title}
      </Typography>
      {children}
    </Box>
  );
}
