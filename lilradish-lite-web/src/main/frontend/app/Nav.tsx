import List from "@mui/material/List";
import ListItem from "@mui/material/ListItem";
import ListItemButton from "@mui/material/ListItemButton";
import ListItemIcon from "@mui/material/ListItemIcon";
import ListItemText from "@mui/material/ListItemText";
import ListSubheader from "@mui/material/ListSubheader";
import type SvgIcon from "@mui/material/SvgIcon";
import Tooltip from "@mui/material/Tooltip";
import { Fragment } from "react";
import { NavLink } from "react-router";

import { holds } from "../api/standing";
import { say, type MessageId } from "../i18n/app";
import { TOKENS } from "../lib/theme/tokens";
import {
  DESTINATION_GROUPS,
  GROUP_ITEMS,
  groupPage,
  MY_WORK,
  opensIn,
} from "./destinations";
import { GroupPicker } from "./GroupPicker";
import { useStanding } from "./standing/StandingContext";
import type { GroupInForce } from "./useGroupInForce";

const DESTINATION_SX = {
  "&.active": {
    bgcolor: "action.selected",
    "& .MuiListItemText-primary": { typography: "term" },
    "& .MuiListItemIcon-root": { color: "primary.main" },
  },
  "[data-navigation-collapsed=true] &": {
    width: "40px",
    height: "36px",
    minHeight: 0,
    px: 0,
    justifyContent: "center",
    borderRadius: TOKENS.shape.controlRadius,
    "& .MuiListItemIcon-root": { minWidth: 0 },
  },
};

const LIST_SX = {
  "[data-navigation-collapsed=true] &": { py: "3px" },
};

/** A place out of reach is not drawn, and `NavLink` alone decides which entry is current. */
export function Nav({ inForce }: { readonly inForce: GroupInForce }) {
  const standing = useStanding();
  const group = inForce.group;

  return (
    <>
      {group === null ? null : (
        <>
          <List dense sx={LIST_SX}>
            <Entry to={MY_WORK.to} label={MY_WORK.label} icon={MY_WORK.icon} />
          </List>
          <div data-navigation-label>
            <GroupPicker
              groups={standing.groups}
              group={group}
              onPick={inForce.choose}
            />
          </div>
          <List dense aria-label={group.name} sx={LIST_SX}>
            {GROUP_ITEMS.filter((item) => opensIn(group, item)).map((item) => (
              <Entry
                key={item.segment}
                to={groupPage(group, item.segment)}
                label={item.label}
                icon={item.icon}
              />
            ))}
          </List>
        </>
      )}
      {DESTINATION_GROUPS.map((section) => {
        const items = section.items.filter((destination) =>
          holds(standing, destination.act),
        );
        // The heading goes with the last of them: left behind it names nothing,
        // and the list `aria-labelledby` points back at it holds nothing.
        if (items.length === 0) {
          return null;
        }
        // Every dot, not the first: an IDREF takes one happily, but an `#id`
        // selector reads it as a class and a deeper id would carry two.
        const headingId = section.label.replaceAll(".", "-");
        return (
          <Fragment key={section.label}>
            <ListSubheader
              component="div"
              disableSticky
              id={headingId}
              data-navigation-label
            >
              {say(section.label)}
            </ListSubheader>
            {/* Named by the heading beside it, not a label of its own: the
                `subheader` slot renders that heading inside, as an extra item. */}
            <List dense aria-labelledby={headingId} sx={LIST_SX}>
              {items.map((destination) => (
                <Entry
                  key={destination.to}
                  to={destination.to}
                  label={destination.label}
                  icon={destination.icon}
                />
              ))}
            </List>
          </Fragment>
        );
      })}
    </>
  );
}

function Entry({
  to,
  label,
  icon: Icon,
}: {
  readonly to: string;
  readonly label: MessageId;
  readonly icon: typeof SvgIcon;
}) {
  return (
    <ListItem disablePadding>
      <Tooltip title={say(label)} describeChild>
        <ListItemButton
          component={NavLink}
          to={to}
          aria-label={say(label)}
          sx={DESTINATION_SX}
        >
          <ListItemIcon sx={{ minWidth: 36 }}>
            <Icon sx={{ width: 20, height: 20 }} />
          </ListItemIcon>
          <ListItemText primary={say(label)} data-navigation-label />
        </ListItemButton>
      </Tooltip>
    </ListItem>
  );
}
