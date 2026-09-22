package org.lilradish.lite;

import org.libprunus.core.log.annotation.LogRegistry;
import org.libprunus.core.log.annotation.MaxMessageLength;
import org.libprunus.core.log.annotation.MethodLoggingProfile;
import org.libprunus.core.log.annotation.ToStringProfile;
import org.libprunus.core.log.runtime.LogLevel;

/**
 * Where this application declares what the build's logging rewrite does: which classes have entry
 * and exit logging woven in, which have their {@code toString()} replaced by a structural rendering,
 * and how long one rendered line may run before it is cut.
 *
 * <p>A profile matches by package and class suffix, and both lists below are maintained by hand: a
 * type no suffix names is rewritten by nothing, and renders as a class name and an identity hash
 * that traces nobody.
 *
 * <p>The two method-logging profiles are the wiring a service or a controller added to that package
 * would be woven by, stated once rather than remembered at the time.
 */
@LogRegistry
@MethodLoggingProfile(
        includePackages = {"org.lilradish.lite.web"},
        includeClassSuffixes = {"Service"})
@MethodLoggingProfile(
        includePackages = {"org.lilradish.lite.web"},
        includeClassSuffixes = {"Controller"},
        entryLevel = LogLevel.DEBUG,
        exitLevel = LogLevel.DEBUG)
/*
 * A @DoNotLog on a field only takes effect inside a class a profile matched, so the domain types
 * carrying inference content have to be matched here or their generated toString stays verbatim.
 */
@ToStringProfile(
        includePackages = {"org.lilradish.lite"},
        includeClassSuffixes = {
            "Attempt",
            "Approval",
            "Judgement",
            "Rejection",
            "Confidence",
            "Reference",
            "Candidate",
            "Fields",
            "Id",
            "Principal",
            "Delegation",
            "Registration",
            "Group",
            "Estate",
            "Standing",
            "Rerun",
            "Override",
            "Replacement",
            "Text"
        })
@MaxMessageLength(512)
public final class AppLoggingConvention {

    private AppLoggingConvention() {}
}
