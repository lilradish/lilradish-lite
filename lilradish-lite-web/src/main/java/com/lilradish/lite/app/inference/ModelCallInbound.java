package com.lilradish.lite.app.inference;

import com.lilradish.lite.domain.model.ModelToolKind;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

public interface ModelCallInbound {

    String id();

    Set<ModelToolKind> supportedToolKinds();

    /** Returns null for malformed provider data; conversion implementation failures propagate. */
    @Nullable
    ConvertedCallAnswer convert(JsonNode response);
}
