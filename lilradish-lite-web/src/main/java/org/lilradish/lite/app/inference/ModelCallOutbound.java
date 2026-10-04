package org.lilradish.lite.app.inference;

import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.inference.CallRequest;
import org.lilradish.lite.domain.model.ModelToolKind;

/** Turns one system request into a provider's HTTP request body. */
public interface ModelCallOutbound {

    String id();

    Set<ModelToolKind> supportedToolKinds();

    byte[] convert(CallRequest request, List<ToolUseLimit> tools, String vendorModelId, @Nullable String mode);
}
