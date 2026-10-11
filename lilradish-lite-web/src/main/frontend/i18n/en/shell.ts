export const SHELL_EN = {
  /**
   * Names the landmark holding every destination; never drawn. Not "Site
   * navigation", which a screen reader reads out as "Site navigation
   * navigation" — the role is announced already and is not part of the name.
   */
  "navigation.site": "Site",
  "navigation.skip": "Skip to content",
  "navigation.open": "Open navigation",
  /**
   * Whether the docked destinations are out is said beside the name, never in it.
   */
  "navigation.sidebar": "Sidebar",

  /**
   * Grouping and order stay outside the catalogue so translating words
   * cannot change the navigation's structure.
   */
  "destination.people": "People",
  "destination.groups": "Groups",
  /** How much of what each group owns still holds together, as of the last check. */
  "destination.soundness": "Soundness",
  "destination.measurements": "Measurements",
  "destinationGroup.system": "System management",
  /** What waits on the reader in every group they are in, and in none of the others. */
  "destination.myWork": "My work",
  /** A group's own pages, each read inside the group named above them. */
  "destination.work": "Work",
  "destination.workflows": "Workflows",
  "destination.questions": "Questions",
  "destination.referenceLists": "Reference lists",
  "destination.members": "Members",

  /** The one group whose pages are drawn, picked from the reader's own by name or key. */
  "groupInForce.label": "Group",
  "groupInForce.noMatch": "None of your groups has a name or key holding that.",

  /**
   * The start page names no next destination: some readers have none to be told of.
   * Its heading is the product name, not a sentence, so it stays outside the catalogue.
   */
  "landing.detail": "You are at the start.",
  "page.notBuilt": "Nothing is built here yet.",

  /** An address that names nothing, which no amount of retrying will cure. */
  "routeError.missing": "No such screen",
  "routeError.missingDetail": "The address does not name anything here.",
  "routeError.missingWayBack": "Back to the start",
  /** What went wrong is never shown to the reader. */
  "routeError.broken": "Something went wrong on this page.",
  "routeError.brokenDetail":
    "Nothing you saved is lost. Go to another page from the menu, or reload this one.",
  "routeError.brokenReload": "Reload this page",
  /** The frame broke, and the menu with it, so nothing here points at one. */
  "routeError.frameBroken": "Something went wrong.",
  "routeError.frameBrokenDetail":
    "Nothing you saved is lost. Reload to carry on.",
  "routeError.frameBrokenReload": "Reload",
};
