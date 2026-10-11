package com.lilradish.lite;

import org.libprunus.core.log.annotation.LogRegistry;
import org.libprunus.core.log.annotation.MaxMessageLength;
import org.libprunus.core.log.annotation.MethodLoggingProfile;
import org.libprunus.core.log.annotation.ToStringProfile;
import org.libprunus.core.log.runtime.LogLevel;

/**
 * The two method-logging profiles are the wiring a service or a controller added to that package
 * would be woven by, stated once rather than remembered at the time.
 */
@LogRegistry
@MethodLoggingProfile(
        includePackages = {"com.lilradish.lite.web"},
        includeClassSuffixes = {"Service"})
@MethodLoggingProfile(
        includePackages = {"com.lilradish.lite.web"},
        includeClassSuffixes = {"Controller"},
        entryLevel = LogLevel.DEBUG,
        exitLevel = LogLevel.DEBUG)
/* @DoNotLog only takes effect inside a matched class, so classes with fields to hide must match here
 * or their generated toString exposes the fields verbatim. */
@ToStringProfile(
        includePackages = {"com.lilradish.lite"},
        includeClassSuffixes = {
            "ModelEndpoint",
            "VendorModel",
            "CallWay",
            "VendorMode",
            "VendorCall",
            "ModelName",
            "ModelMode",
            "Confidence",
            "Fields",
            "Id",
            "Principal",
            "Delegation",
            "Registration",
            "Group",
            "Estate",
            "Standing",
            "Record",
            "Snapshot",
            "Inputs",
            "SentText",
            "CameBack",
            "Errored",
            "TurnAway",
            "ForeignProse",
            "KeptAnswer",
            "Produced",
            "Fits",
            "Misfit",
            "Refused",
            "CodeCall",
            "CodeStepName",
            "Gave",
            "Given",
            "FieldName",
            "Fault",
            "Said",
            "Reads",
            "Part",
            "ConvertedCallAnswer",
            "ToolCallOutcome",
            "SearchObservation",
            "ListFilter",
            "ReviewPayload$Built",
            "ModelToolName",
            "CallOutcome$TurnedAway",
            "CallOutcome$NotResent"
        })
@MaxMessageLength(512)
public final class AppLoggingConvention {

    private AppLoggingConvention() {}
}
