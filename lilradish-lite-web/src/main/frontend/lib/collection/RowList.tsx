import List from "@mui/material/List";
import ListItem from "@mui/material/ListItem";
import ListItemButton from "@mui/material/ListItemButton";
import ListItemText from "@mui/material/ListItemText";
import type { ReactNode } from "react";
import { Link as RouterLink } from "react-router";

const ROW_SX = {
  display: "block",
  width: "100%",
  textAlign: "start",
  font: "inherit",
  border: 1,
  borderColor: "divider",
  borderRadius: 1,
};

/**
 * Rows that go somewhere. Each is an anchor, so opening one in a new tab,
 * copying its address and reading the destination off the status bar all work
 * without this component doing anything — none of which a button can offer.
 */
export function NavigableList<T>({
  items,
  keyOf,
  pathOf,
  title,
  detail,
}: {
  readonly items: readonly T[];
  readonly keyOf: (item: T) => string;
  readonly pathOf: (item: T) => string;
  readonly title: (item: T) => ReactNode;
  readonly detail: (item: T) => ReactNode;
}) {
  return (
    <List disablePadding sx={{ mb: 2 }}>
      {items.map((item) => (
        <ListItem key={keyOf(item)} disablePadding sx={{ mb: 0.5 }}>
          <ListItemButton component={RouterLink} to={pathOf(item)} sx={ROW_SX}>
            <ListItemText primary={title(item)} secondary={detail(item)} />
          </ListItemButton>
        </ListItem>
      ))}
    </List>
  );
}
