package com.lilradish.lite.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a read-only POST whose request body is too large for a query string, alongside
 * {@link GroupMembershipRequired}. This is not another authorization and does not prove that the
 * handler's implementation performs no writes; tests and review must establish that separately.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface MembershipReadOnlyPost {}
