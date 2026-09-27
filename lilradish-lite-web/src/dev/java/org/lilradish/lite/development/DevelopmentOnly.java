package org.lilradish.lite.development;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks what is compiled from the development source set, every class and package of it, this one included.
 * The build refuses an archive any class of which names this, and a class in that source set without it.
 */
@DevelopmentOnly
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.PACKAGE})
public @interface DevelopmentOnly {}
