package com.lilradish.lite.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Identification alone suffices. An explicit declaration distinguishes that decision from forgetting
 * {@link ActRequired}, {@link GroupPermissionRequired} or {@link GroupMembershipRequired}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface NoActRequired {}
