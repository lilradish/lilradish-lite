package com.lilradish.lite.app.run

/**
 * What the deployment says of runs where a spec has nothing of its own to say. Here beside the engine because only
 * its own package may name that setting.
 */
final class Deployments {

    /** Deep enough that no tree a spec builds reaches it. */
    static final RunDeployment DEEP_ENOUGH = new RunDeployment(32)

    private Deployments() {}

    static RunDeployment deep(int maximumDepth) {
        new RunDeployment(maximumDepth)
    }
}
