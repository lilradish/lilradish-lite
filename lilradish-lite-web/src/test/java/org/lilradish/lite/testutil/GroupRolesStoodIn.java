package org.lilradish.lite.testutil;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.lilradish.lite.app.group.GroupRoles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * For a slice asking nothing inside any group: the gate every slice loads needs somebody's roles in a group
 * to be readable, and here they are read as none, for everybody, without the spec naming them.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@MockitoBean(types = GroupRoles.class)
public @interface GroupRolesStoodIn {}
