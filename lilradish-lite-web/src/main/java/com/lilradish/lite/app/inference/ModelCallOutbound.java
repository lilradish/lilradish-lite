package com.lilradish.lite.app.inference;

import com.lilradish.lite.domain.inference.CallRequest;
import com.lilradish.lite.domain.model.ModelToolKind;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

public interface ModelCallOutbound {

    String id();

    Set<ModelToolKind> supportedToolKinds();

    byte[] convert(CallRequest request, List<ToolUseLimit> tools, String vendorModelId, @Nullable String mode);
}
