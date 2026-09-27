import List from "@mui/material/List";
import ListItem from "@mui/material/ListItem";
import ListItemButton from "@mui/material/ListItemButton";
import ListItemText from "@mui/material/ListItemText";
import type { ReactNode } from "react";
import { Link as RouterLink } from "react-router";

import type { ReturnState } from "../navigation/returnSearch";

interface Rows<T> {
  readonly items: readonly T[];
  readonly keyOf: (item: T) => string;
  readonly title: (item: T) => ReactNode;
  readonly detail: (item: T) => ReactNode;
}

const ROW_SX = {
  display: "block",
  width: "100%",
  textAlign: "start",
  font: "inherit",
  border: 1,
  borderColor: "divider",
  borderRadius: 1,
  "&.Mui-selected": { borderColor: "primary.main", borderLeftWidth: 4 },
};

/**
 * Rows that go somewhere. Each is an anchor, so opening one in a new tab,
 * copying its address and reading the destination off the status bar all work
 * without this component doing anything — none of which a button can offer.
 *
 * Where the address already names one of them, that one is current, marked
 * as `SelectableList` marks its choice.
 */
export function NavigableList<T>({
  items,
  keyOf,
  pathOf,
  state,
  isCurrent,
  labelledBy,
  title,
  detail,
}: Rows<T> & {
  readonly pathOf: (item: T) => string;
  readonly state?: ReturnState;
  readonly isCurrent?: (item: T) => boolean;
  /** The id of what names the list, where something on the page does. */
  readonly labelledBy?: string;
}) {
  return (
    <List disablePadding sx={{ mb: 2 }} aria-labelledby={labelledBy}>
      {items.map((item) => (
        <ListItem key={keyOf(item)} disablePadding sx={{ mb: 0.5 }}>
          <ListItemButton
            component={RouterLink}
            to={pathOf(item)}
            state={state}
            selected={isCurrent?.(item) === true}
            aria-current={isCurrent?.(item) === true || undefined}
            sx={ROW_SX}
          >
            <RowText title={title(item)} detail={detail(item)} />
          </ListItemButton>
        </ListItem>
      ))}
    </List>
  );
}

/**
 * Rows that are the choice itself, with one of them current.
 *
 * `aria-current` rather than `aria-pressed`: a pressed row never unpresses.
 * MUI's `selected` carries only a class, which says how a row looks rather
 * than what a screen reader announces.
 */
export function SelectableList<T>({
  items,
  keyOf,
  isSelected,
  onSelect,
  title,
  detail,
}: Rows<T> & {
  readonly isSelected: (item: T) => boolean;
  readonly onSelect: (item: T) => void;
}) {
  return (
    <List disablePadding sx={{ mb: 2 }}>
      {items.map((item) => (
        <ListItem key={keyOf(item)} disablePadding sx={{ mb: 0.5 }}>
          <ListItemButton
            // A real button, not MUI's default div[role=button]: Enter and
            // Space become the browser's job rather than a library's.
            component="button"
            selected={isSelected(item)}
            aria-current={isSelected(item) || undefined}
            onClick={() => onSelect(item)}
            sx={ROW_SX}
          >
            <RowText title={title(item)} detail={detail(item)} />
          </ListItemButton>
        </ListItem>
      ))}
    </List>
  );
}

function RowText({
  title,
  detail,
}: {
  readonly title: ReactNode;
  readonly detail: ReactNode;
}) {
  return (
    <ListItemText
      // A button holds phrasing content only, so spans; `slots.root` would drop the
      // root's styles, which stack title and detail, and an inline root its margins.
      primary={title}
      secondary={detail}
      slotProps={{
        root: { component: "span", sx: { display: "block" } },
        primary: { component: "span", sx: { typography: "identifier" } },
        secondary: { component: "span" },
      }}
    />
  );
}
