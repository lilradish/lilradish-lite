package org.lilradish.lite.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that holding any role in the group its address names is the whole of what a handler asks,
 * that group being the one under {@link ActAdmission#IN_A_GROUP}. What enforces it is {@link
 * ActAdmission}, and what a handler reads the group from is {@link ActAdmission#admittedGroup}.
 *
 * <p>A declaration of its own rather than {@link GroupPermissionRequired} naming some permission every
 * role happens to hold: what every role holds is today's table, and a permission standing in for
 * membership would close the page the day a role stopped bundling it.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface GroupMembershipRequired {}
