package com.lilradish.lite.web;

import com.lilradish.lite.domain.identity.GroupPermission;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * {@link ActAdmission} enforces the permission within the group under {@link ActAdmission#IN_A_GROUP}.
 * Handlers use {@link ActAdmission#admittedGroup}, never a second reading of the address.
 *
 * <p>Separate from {@link ActRequired} and {@link NoActRequired} to keep group and estate vocabularies
 * distinct, so neither can be used in the other scope.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface GroupPermissionRequired {

    GroupPermission value();
}
