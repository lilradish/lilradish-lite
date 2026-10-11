package com.lilradish.lite.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Any role in the group under {@link ActAdmission#IN_A_GROUP} suffices. {@link ActAdmission} enforces
 * this, and handlers obtain the admitted group from {@link ActAdmission#admittedGroup}.
 *
 * <p>Separate from {@link GroupPermissionRequired}: using a permission shared by all roles would
 * revoke membership-only access when any role stopped bundling that permission.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface GroupMembershipRequired {}
