package org.lilradish.lite.domain.observability;

/**
 * The ordinary axis of incident classification, declared in order of impact so a threshold can be
 * expressed as a grade. Kept separate from the AI incident classes because those can fire while the
 * service never stopped serving — {@link #NONE} is that case, not a missing value.
 */
public enum ServiceSeverity {
    NONE,
    LOW,
    MAJOR,
    CRITICAL
}
