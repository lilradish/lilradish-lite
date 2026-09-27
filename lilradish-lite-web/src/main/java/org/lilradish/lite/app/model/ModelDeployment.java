package org.lilradish.lite.app.model;

import java.util.List;
import org.lilradish.lite.domain.model.DeployedModel;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * A misspelt optional key read from a file or the command line stops the start; one from an
 * environment variable or a system property is not checked and goes unread.
 *
 * @param models a list rather than a map keyed by name: a map key loses its underscores, and one
 *     from the environment is lowercased, so a name would be rewritten instead of refused
 */
@ConfigurationProperties(prefix = "lilradish.deployed", ignoreUnknownFields = false)
record ModelDeployment(List<DeployedModel> models) {

    ModelDeployment {
        models = models == null ? List.of() : models;
    }
}
