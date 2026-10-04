package org.lilradish.lite.app.inference.development;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.lilradish.lite.app.inference.EndpointJson.stringIn;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.app.inference.ConvertedCallAnswer;
import org.lilradish.lite.app.inference.EndpointJson;
import org.lilradish.lite.app.inference.ModelCallInbound;
import org.lilradish.lite.app.inference.ModelCallOutbound;
import org.lilradish.lite.app.inference.SearchObservation;
import org.lilradish.lite.app.inference.ToolUseLimit;
import org.lilradish.lite.development.DevelopmentOnly;
import org.lilradish.lite.domain.inference.CallRequest;
import org.lilradish.lite.domain.model.ModelToolKind;
import org.lilradish.lite.domain.model.ModelToolName;
import org.lilradish.lite.domain.wire.CanonicalJson;
import org.lilradish.lite.domain.wire.JsonValue;
import org.lilradish.lite.domain.wire.JsonValue.JsonArray;
import org.lilradish.lite.domain.wire.JsonValue.JsonMember;
import org.lilradish.lite.domain.wire.JsonValue.JsonNumber;
import org.lilradish.lite.domain.wire.JsonValue.JsonObject;
import org.lilradish.lite.domain.wire.JsonValue.JsonString;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/** The stand-in's request and response shapes, exercised through the same converter contracts. */
@DevelopmentOnly
@Component
public final class DevelopmentSearchConversion implements ModelCallOutbound, ModelCallInbound {

    static final String ID = "development_search";

    private static final String ANSWER =
            "{\"title\":\"Damaged parcels: refund and claim rules\",\"body\":\"## Summary\\n\\nMost c"
                    + "arriers treat a parcel that arrives **damaged** as a claim the sender makes, but the buy"
                    + "er still has to *report it quickly*.\\n\\n### What to check first\\n\\n1. Photograph the"
                    + " outer box and the item before unpacking further.\\n2. Keep all packing material until t"
                    + "he claim is settled.\\n3. Note the tracking number and the delivery date.\\n\\n> Claims "
                    + "filed late usually need proof that the damage could not be seen at delivery.\\n\\n### Ty"
                    + "pical time limits\\n\\n- Visible damage: report within **48 hours**\\n- Hidden damage: r"
                    + "eport within **14 days**\\n- Missing contents: report within **7 days**\\n\\n---\\n\\nA "
                    + "reply to the customer can open like this:\\n\\n```text\\nWe are sorry your order arrived"
                    + " damaged. Please send two photos of the box and the item.\\n```\\n\\nThe [claim guide](h"
                    + "ttps://example.com/claims) lists the full form, and `claim_id` is printed on the receipt"
                    + ".\",\"references\":\"1. [Carrier claims guide](https://example.com/claims)\\n2. [Consume"
                    + "r returns overview](https://example.org/returns)\\n3. https://example.net/damaged-parcel"
                    + "s\",\"other\":\"\"}";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Set<ModelToolKind> supportedToolKinds() {
        return Set.of(ModelToolKind.WEB_SEARCH);
    }

    @Override
    public byte[] convert(CallRequest request, List<ToolUseLimit> tools, String vendorModelId, @Nullable String mode) {
        List<JsonValue> offered = tools.stream()
                .map(tool -> (JsonValue) new JsonObject(List.of(
                        new JsonMember("name", new JsonString(tool.tool().value())),
                        new JsonMember("maximum", new JsonNumber(java.math.BigDecimal.valueOf(tool.maximum()))))))
                .toList();
        return CanonicalJson.write(new JsonObject(List.of(
                        new JsonMember("model", new JsonString(vendorModelId)),
                        new JsonMember("question", new JsonString(request.sent().user())),
                        new JsonMember("tools", new JsonArray(offered)))))
                .getBytes(UTF_8);
    }

    static String replyTo(byte[] sent) {
        JsonNode asked;
        try {
            asked = EndpointJson.READER.readTree(sent);
        } catch (JacksonException unreadable) {
            throw new IllegalArgumentException("The development call request is unreadable", unreadable);
        }
        String question = stringIn(asked, "question");
        if (question == null) {
            throw new IllegalArgumentException("The development call request has no question");
        }
        JsonNode tools = asked.get("tools");
        JsonNode first = tools != null && tools.isArray() ? tools.get(0) : null;
        String tool = first == null ? null : stringIn(first, "name");
        JsonNode maximum = first == null ? null : first.get("maximum");
        if (tool == null
                || maximum == null
                || !maximum.isIntegralNumber()
                || !maximum.canConvertToLong()
                || maximum.longValue() <= 0) {
            throw new IllegalArgumentException("The development call request has no bounded tool");
        }
        String generated = CanonicalJson.write(new JsonObject(List.of(
                new JsonMember("tool", new JsonString(tool)), new JsonMember("query", new JsonString(question)))));
        return CanonicalJson.write(new JsonObject(List.of(
                new JsonMember("answer", new JsonString(ANSWER)),
                new JsonMember("sent_count", new JsonNumber(java.math.BigDecimal.valueOf(7))),
                new JsonMember("came_back_count", new JsonNumber(java.math.BigDecimal.valueOf(9))),
                new JsonMember("uses", new JsonNumber(java.math.BigDecimal.valueOf(Math.min(2, maximum.longValue())))),
                new JsonMember("tool", new JsonString(tool)),
                new JsonMember("generated_tool_calls", new JsonString(generated)),
                new JsonMember("query", new JsonString(question)),
                new JsonMember("found", new JsonString("Demo Sample page")))));
    }

    @Override
    public @Nullable ConvertedCallAnswer convert(JsonNode response) {
        String answer = stringIn(response, "answer");
        String tool = stringIn(response, "tool");
        String query = stringIn(response, "query");
        String found = stringIn(response, "found");
        String generated = stringIn(response, "generated_tool_calls");
        JsonNode uses = response.get("uses");
        JsonNode sent = response.get("sent_count");
        JsonNode cameBack = response.get("came_back_count");
        if (answer == null
                || tool == null
                || query == null
                || found == null
                || generated == null
                || uses == null
                || !uses.isIntegralNumber()
                || !uses.canConvertToLong()
                || uses.longValue() < 0) {
            return null;
        }
        ModelToolName name;
        try {
            name = new ModelToolName(tool);
        } catch (IllegalArgumentException invalidToolName) {
            return null;
        }
        return new ConvertedCallAnswer(
                answer,
                sent != null && sent.isIntegralNumber() && sent.canConvertToLong() && sent.longValue() >= 1
                        ? sent.longValue()
                        : null,
                cameBack != null
                                && cameBack.isIntegralNumber()
                                && cameBack.canConvertToLong()
                                && cameBack.longValue() >= 0
                        ? cameBack.longValue()
                        : null,
                false,
                Map.of(name, uses.longValue()),
                List.of(new SearchObservation(name, query, found)),
                null,
                null,
                generated.codePointCount(0, generated.length()));
    }
}
