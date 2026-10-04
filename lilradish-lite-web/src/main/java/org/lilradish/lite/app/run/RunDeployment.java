package org.lilradish.lite.app.run;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Lazy;

/**
 * What the deployment says of runs: how many deep a tree of them may go, the run at the top counted as one. A step
 * that would start a run deeper than that fails instead. Nothing here defaults: the deployment says how deep.
 *
 * @param maximumDepth how many runs deep a tree may go; one lets no run start beneath another
 */
// Never lazy, even where lazy initialisation is switched on: a bound it cannot hold must stop the start.
@Lazy(false)
@ConfigurationProperties(prefix = "lilradish.runs", ignoreUnknownFields = false)
record RunDeployment(Integer maximumDepth) {

    private static final String PREFIX = "lilradish.runs";

    RunDeployment {
        if (maximumDepth == null) {
            throw new NullPointerException(PREFIX + ".maximum-depth must be set");
        }
        if (maximumDepth < 1) {
            throw new IllegalArgumentException(PREFIX + ".maximum-depth must be positive: " + maximumDepth);
        }
    }
}
