export const PEOPLE_EN = {
  /** The estate's roles, in the words a reader is shown wherever one is named. */
  "estateRole.steward": "Steward",
  "estateRole.watcher": "Watcher",

  /** Everybody this system is used by, as against everybody in the directory. */
  "pool.label": "People in the pool",
  "pool.empty": "Nobody is in the pool.",
  "pool.noMatch": "Nobody in the pool matches that.",
  "pool.add": "Add somebody",

  /**
   * Facts share headings in the list and panel, so each is said once.
   */
  "person.heading": "Person",
  "person.userId": "User number",
  "person.name": "Name",
  "person.estateRoles": "Estate roles",
  "person.groups": "Groups",
  "person.who": "Who they are",
  "person.nameless": "No name is held for them.",
  "person.holds": "Held",
  "person.holdsNot": "Not held",
  "person.grant": "Grant {role}",
  "person.withdraw": "Withdraw {role}",
  "person.inNoGroup": "In no group.",
  "confirm.remove": "Remove",
  "confirm.cancel": "Cancel",
  "person.remove": "Remove from the pool",
  "person.removeConfirm": "Remove {name} from the pool?",
  "person.removeConfirmBody":
    "Bringing them in again later keeps them the same person, with their past acts.",
  "person.removeUnderway":
    "They are being removed from the pool, and that can no longer be called back.",
  "person.out": "{name} is out of the pool.",
  "person.in": "{name} is in the pool.",
  /** A change to somebody already in the pool, refused once the reader had moved on from them; each says in words what was not done. */
  "person.notTakenOut": "{name} is still in the pool: {refusal}",
  "person.rolesUnchanged": "{name}'s estate roles were not changed: {refusal}",
  "person.withdrawFirst":
    "{count, plural, one {Their estate role has to be withdrawn first: {roles}.} other {Their estate roles have to be withdrawn first: {roles}.}}",
  "person.leaveFirst":
    "{count, plural, one {They have to leave this group first: {groups}.} other {They have to leave these groups first: {groups}.}}",

  /** One search label serves the pool and the directory alike. */
  "person.numberOrName": "User number or name",

  "directory.noMatch": "Nobody in the directory matches that.",
  "directory.inPool": "Already in the pool.",
  "directory.bringIn": "Bring into the pool",
  "directory.pickFirst": "Pick somebody first.",
  "directory.abandon": "Cancel",
  "directory.underway":
    "They are being brought in, and that can no longer be called back.",
};
