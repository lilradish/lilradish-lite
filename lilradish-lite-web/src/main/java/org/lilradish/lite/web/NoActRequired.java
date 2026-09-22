package org.lilradish.lite.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that being identified is the whole of what a handler asks. Written out rather than left
 * to the absence of {@link ActRequired}, so that a handler nobody gated and a handler somebody
 * decided needs no gate do not look alike.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface NoActRequired {}
