package org.lilradish.lite.app.inference;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.lilradish.lite.app.inference.EndpointJson.stringIn;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.inference.CallRequest;
import org.lilradish.lite.domain.inference.DidNotFitReason;
import org.lilradish.lite.domain.model.ModelToolKind;
import org.lilradish.lite.domain.wire.CanonicalJson;
import org.lilradish.lite.domain.wire.JsonValue;
import org.lilradish.lite.domain.wire.JsonValue.JsonArray;
import org.lilradish.lite.domain.wire.JsonValue.JsonMember;
import org.lilradish.lite.domain.wire.JsonValue.JsonNumber;
import org.lilradish.lite.domain.wire.JsonValue.JsonObject;
import org.lilradish.lite.domain.wire.JsonValue.JsonString;
import tools.jackson.databind.JsonNode;

/** Converts a no-tool chat completion on both sides of a call. */
final class OpenAiCompatibleCallConversion implements ModelCallOutbound, ModelCallInbound {

    static final String ID = "openai_compatible";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Set<ModelToolKind> supportedToolKinds() {
        return Set.of();
    }

    @Override
    public byte[] convert(CallRequest request, List<ToolUseLimit> tools, String vendorModelId, @Nullable String mode) {
        if (!tools.isEmpty()) {
            throw new IllegalArgumentException("The compatible completion shape offers no tools");
        }
        List<JsonMember> members = new ArrayList<>(4);
        members.add(new JsonMember("model", new JsonString(vendorModelId)));
        members.add(new JsonMember(
                "messages",
                new JsonArray(List.of(
                        message("system", request.sent().system()),
                        message("user", request.sent().user())))));
        members.add(new JsonMember(
                "max_completion_tokens",
                new JsonNumber(BigDecimal.valueOf(request.model().cameBackPerCallLimit()))));
        if (mode != null) {
            members.add(new JsonMember("reasoning_effort", new JsonString(mode)));
        }
        return CanonicalJson.write(new JsonObject(members)).getBytes(UTF_8);
    }

    private static JsonValue message(String role, String content) {
        return new JsonObject(List.of(
                new JsonMember("role", new JsonString(role)), new JsonMember("content", new JsonString(content))));
    }

    @Override
    public @Nullable ConvertedCallAnswer convert(JsonNode completion) {
        return from(completion);
    }

    private static @Nullable ConvertedCallAnswer from(@Nullable JsonNode completion) {
        if (completion == null) {
            return null;
        }
        JsonNode choices = completion.get("choices");
        JsonNode choice = choices != null && choices.isArray() ? choices.get(0) : null;
        if (choice == null) {
            return null;
        }
        JsonNode message = choice.get("message");
        if (message == null || !message.isObject()) {
            return null;
        }
        JsonNode content = message.get("content");
        if (content != null && !content.isNull() && !content.isString()) {
            return null;
        }
        String refusal = stringIn(message, "refusal");
        String finishReason = stringIn(choice, "finish_reason");
        boolean refused = (refusal != null && !refusal.isEmpty()) || "content_filter".equals(finishReason);
        String contentText = content != null && !content.isNull() ? content.stringValue() : null;
        String answer = contentText == null ? "" : contentText;
        boolean cutOff = !refused && "length".equals(finishReason);
        JsonNode usage = completion.get("usage");
        long sentCount = usage == null ? -1 : countIn(usage, "prompt_tokens");
        long cameBackCount = usage == null ? -1 : countIn(usage, "completion_tokens");
        return new ConvertedCallAnswer(
                answer,
                sentCount >= 1 ? sentCount : null,
                cameBackCount >= 0 ? cameBackCount : null,
                cutOff,
                Map.of(),
                List.of(),
                refused ? DidNotFitReason.MODEL_DECLINED : null,
                refused ? refusal : null,
                0L);
    }

    private static long countIn(JsonNode usage, String name) {
        JsonNode value = usage.get(name);
        return value != null && value.isIntegralNumber() && value.canConvertToLong() ? value.longValue() : -1;
    }
}
