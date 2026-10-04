package org.lilradish.lite.app.inference;

import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.model.ModelToolKind;
import tools.jackson.databind.JsonNode;

/** Turns one provider response into the answer, counts and search evidence it reported. */
public interface ModelCallInbound {

    String id();

    Set<ModelToolKind> supportedToolKinds();

    /** Returns null for malformed provider data; conversion implementation failures propagate. */
    @Nullable
    ConvertedCallAnswer convert(JsonNode response);
}
