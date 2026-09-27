package org.lilradish.lite.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.lilradish.lite.domain.identity.GroupPermission;

/**
 * The permission a handler asks of whoever calls it inside the group its address names, which is the
 * group under {@link ActAdmission#IN_A_GROUP}. What enforces it is {@link ActAdmission}, and what a
 * handler reads the group from is {@link ActAdmission#admittedGroup}: the one the gate admitted, never
 * the address read a second time.
 *
 * <p>A declaration beside {@link ActRequired} and {@link NoActRequired} rather than a second value of
 * either, because what it is asked within is a group and not the estate, and the two
 * vocabularies are kept apart so that neither can be spelt in the other.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface GroupPermissionRequired {

    GroupPermission value();
}
