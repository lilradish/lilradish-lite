package org.lilradish.lite.domain.identity;

import java.util.Set;

/**
 * A principal that stands somewhere — a person, or a delegation exercised in a person's name.
 * Membership filters which rows exist to be acted on; a role filters which acts are open on them.
 * They are separate axes and neither substitutes for the other, which is why {@link #scopes()} is
 * asked on its own and everything a role decides takes the group it is decided in.
 *
 * <p>The two axes answer in two types on purpose. A resource binds to a {@link Scope}, so that is
 * what says where rows may be seen; but a role is held inside a group, so everything a role decides
 * takes a {@link Scope.Group} and the estate cannot be handed to it at all. An estate role would add
 * to the first axis and would bring a vocabulary of its own for the second.
 *
 * <p>Split out of {@link Principal} rather than left on it so that an actor existing in code cannot
 * be asked any of this. Nothing it does varies by scope, so an emptiness returned there would read
 * as a standing rather than as the absence of the axis.
 *
 * <p>Roles are carried beside permissions and never folded back into them. Both were answered from
 * the same claims, so both are asked of the principal: a query narrowed by roles read from anywhere
 * else is a query narrowed by somebody else's. And a delegation carries its owner's roles across
 * whole while being granted less than they bundle, so a permission recomputed from these hands back
 * exactly what the narrowing withheld — the two agreeing for every person is what hides it. What may
 * be done is {@link #permissions} and nothing else.
 */
public sealed interface ScopedPrincipal extends Principal permits HumanPrincipal, Delegation {

    /** Where this caller may see rows at all, before any role narrows what may be done with them. */
    Set<Scope> scopes();

    Set<GroupPermission> permissions(Scope.Group group);

    /** The roles this call carries in one group, beside the permissions they granted. */
    Set<GroupRole> roles(Scope.Group group);

    default boolean may(GroupPermission permission, Scope.Group group) {
        return permissions(group).contains(permission);
    }
}
