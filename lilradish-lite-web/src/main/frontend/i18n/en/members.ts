export const MEMBERS_EN = {
  "groupRole.operator": "Operator",
  "groupRole.overseer": "Overseer",
  "groupRole.owner": "Owner",

  "members.heading": "Members of {group}",
  "members.label": "Members of the group",
  "members.empty": "Nobody is in this group.",
  "members.noMatch": "No member of this group matches that.",
  "members.bringIn": "Bring somebody in",

  "member.heading": "Member",
  "member.roles": "Roles",
  "member.remove": "Remove from the group",
  "member.removeConfirm": "Remove {name} from the group?",
  "member.removeConfirmBody":
    "They will hold no role here. Bringing them back means taking them on again.",
  "member.removeUnderway":
    "They are being removed from the group, and that can no longer be called back.",
  /** Said where granting a role is how they come back, nobody having to be searched for. */
  "member.noLonger":
    "No longer a member of this group. Granting a role here makes them one again.",
  /** Why the last who may change who is in the group can be neither removed nor have that role withdrawn. */
  "member.lastChanger":
    "No one else here can change who is in this group, so they can't be removed, and the role that lets them do it can't be withdrawn.",
  "member.in": "{name} is in this group now.",
  "member.out": "{name} is no longer in this group.",
  /** A change to a member, refused once the reader had moved on from them; each says what was not done. */
  "member.notTakenOut": "{name} is still in this group: {refusal}",
  "member.rolesUnchanged": "{name}'s roles here were not changed: {refusal}",

  /** The one currency a group reads its costs in; once chosen, only ever changed. */
  "currency.label": "Currency",
  /** A currency the reader's language has a name for. */
  "currency.named": "{code} — {name}",
  "currency.noneChosen": "None chosen",
  "currency.read": "Currency: {currency}",
  "currency.change": "Change",
  "currency.notPriced": "No model is priced in it any longer.",
  "currency.chosen": "This group's costs are read in {currency} now.",

  /** Existing members are not offered as candidates. */
  "candidates.noMatch": "Nobody in the pool outside this group matches that.",
  "candidates.roles": "Roles here",
  "candidates.pickFirst": "Pick somebody first.",
  "candidates.pickRole":
    "Pick at least one role: somebody holding nothing here is not a member.",
  "candidates.takeOn": "Take them on",
  "candidates.abandon": "Cancel",
  "candidates.underway":
    "They are being taken on, and that can no longer be called back.",
};
