package com.lilradish.lite.app.inference.development;

import static com.lilradish.lite.app.inference.EndpointJson.stringIn;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.lilradish.lite.app.inference.ConvertedCallAnswer;
import com.lilradish.lite.app.inference.EndpointJson;
import com.lilradish.lite.app.inference.ModelCallInbound;
import com.lilradish.lite.app.inference.ModelCallOutbound;
import com.lilradish.lite.app.inference.SearchObservation;
import com.lilradish.lite.app.inference.ToolUseLimit;
import com.lilradish.lite.development.DevelopmentOnly;
import com.lilradish.lite.domain.inference.CallRequest;
import com.lilradish.lite.domain.model.ModelToolKind;
import com.lilradish.lite.domain.model.ModelToolName;
import com.lilradish.lite.domain.wire.CanonicalJson;
import com.lilradish.lite.domain.wire.JsonValue;
import com.lilradish.lite.domain.wire.JsonValue.JsonArray;
import com.lilradish.lite.domain.wire.JsonValue.JsonMember;
import com.lilradish.lite.domain.wire.JsonValue.JsonNumber;
import com.lilradish.lite.domain.wire.JsonValue.JsonObject;
import com.lilradish.lite.domain.wire.JsonValue.JsonString;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/** The stand-in's request and response shapes, exercised through the same converter contracts. */
@DevelopmentOnly
@Component
public final class DevelopmentSearchConversion implements ModelCallOutbound, ModelCallInbound {

    static final String ID = "development_search";

    private static String answerFor(String question) {
        String topic = question.toLowerCase(Locale.ROOT);
        boolean parcels = topic.contains("parcel") || topic.contains("damage") || topic.contains("delivery");
        boolean billing = topic.contains("invoice") || topic.contains("billing") || topic.contains("refund");
        String category = parcels
                ? "Imaginary Mooncrate: damaged parcel review"
                : billing ? "Imaginary Mooncrate: billing review" : "Imaginary Mooncrate: topic review";
        String title = category;
        String finding = parcels
                ? "Keep the **box and packing** until the imaginary claim is reviewed."
                : billing
                        ? "Compare the **invoice and payment record** before proposing an imaginary correction."
                        : "Collect the **source notes and unresolved questions** before proposing an imaginary decision.";
        String body = "## " + title + "\n\nThis is an invented practice case, not an actual policy.\n"
                + "Question received: " + question + "\n\n" + finding + "\n\n"
                + "1. Record `DEMO-CLAIM-17`.\n2. Ask Mira Cloudmere for the missing evidence.\n\n"
                + "- **Keep** the original record.\n- ~~Discard the evidence~~ Keep it for review.\n\n"
                + "> The invented review cannot settle a claim without evidence.\n\n"
                + "| Evidence | Invented allowance | Review note |\n| --- | --- | --- |\n"
                + "| Photograph | 12 pretend credits | Extra note: ask for the box label |\n"
                + "| Packing note | 7 pretend credits | Record the fictional dispatch date |\n\n"
                + "```fictional-policy\nclaim = DEMO-CLAIM-17\n```\n\n"
                + "Read the [invented guide](https://example.com/claims \"Mooncrate guide\") and "
                + "[local note](file:///imaginary/claim).\n"
                + "Address alone: https://example.net/claims\n"
                + "![Imaginary box diagram](https://example.com/box.png)\n\n"
                + "[Terms][fictional-terms]\n\n"
                + "[fictional-terms]: https://example.org/terms \"Invented terms\"\n\n"
                + "<aside>Invented review note, shown exactly as written.</aside>\n\n"
                + "Evidence&#8203;status: pending.\nEvidence" + Character.toString(0x200B)
                + "owner: Mira Cloudmere.\n\n---";
        String references = "Invented sources only:\n[Practice guide](https://example.com/claims)\n"
                + "[Contact](mailto:demo@example.invalid)\nhttps://example.org/terms";
        return CanonicalJson.write(new JsonObject(List.of(
                new JsonMember("title", new JsonString(title)), new JsonMember("body", new JsonString(body)),
                new JsonMember("references", new JsonString(references)),
                        new JsonMember("other", new JsonString("")))));
    }

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
        String payload = stringIn(asked, "question");
        if (payload == null) {
            throw new IllegalArgumentException("The development call request has no question");
        }
        JsonNode content;
        try {
            content = EndpointJson.READER.readTree(payload);
        } catch (JacksonException unreadable) {
            throw new IllegalArgumentException("The development search payload is unreadable", unreadable);
        }
        String instruction = stringIn(content, "instruction");
        JsonNode inputs = content.get("takes");
        if (instruction == null || inputs == null || !inputs.isObject()) {
            throw new IllegalArgumentException("The development search payload has no instruction and inputs");
        }
        String topic = stringIn(inputs, "topic");
        String question = topic != null ? topic : instruction + (inputs.isEmpty() ? "" : " " + inputs);
        JsonNode texts = inputs.get("text");
        if (texts != null && texts.isArray()) {
            StringBuilder evidence = new StringBuilder(question);
            for (int index = 0; index < texts.size(); index++) {
                JsonNode text = texts.get(index);
                if (!text.isString()) {
                    throw new IllegalArgumentException("The development search text is not text");
                }
                String contentText = text.stringValue();
                evidence.append("\nFile ").append(index + 1).append(": ").append(prefix(contentText, 120));
            }
            question = evidence.toString();
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
        int used = (int) Math.min(2, maximum.longValue());
        List<JsonValue> observations = new ArrayList<>();
        List<JsonValue> generatedCalls = new ArrayList<>();
        for (int index = 0; index < used; index++) {
            String query = index == 0 ? question : question + " / evidence ledger";
            String found = index == 0
                    ? "## Invented source notes\nThe practice record concerns: " + question
                            + "\n[Imaginary source](https://example.com/practice)\nNo real policy or supplier is represented."
                    : "## Invented evidence ledger\nMira Cloudmere recorded the missing box photograph for: " + question
                            + "\n[Imaginary ledger](https://example.org/evidence)\nThe fictional dispatch record remains open.";
            generatedCalls.add(new JsonObject(List.of(
                    new JsonMember("tool", new JsonString(tool)), new JsonMember("query", new JsonString(query)))));
            observations.add(new JsonObject(List.of(
                    new JsonMember("query", new JsonString(query)), new JsonMember("found", new JsonString(found)))));
        }
        String generated = CanonicalJson.write(new JsonArray(generatedCalls));
        return CanonicalJson.write(new JsonObject(List.of(
                new JsonMember("answer", new JsonString(answerFor(question))),
                new JsonMember("sent_count", new JsonNumber(java.math.BigDecimal.valueOf(7))),
                new JsonMember("came_back_count", new JsonNumber(java.math.BigDecimal.valueOf(9))),
                new JsonMember("uses", new JsonNumber(java.math.BigDecimal.valueOf(used))),
                new JsonMember("tool", new JsonString(tool)),
                new JsonMember("generated_tool_calls", new JsonString(generated)),
                new JsonMember("observations", new JsonArray(observations)))));
    }

    private static String prefix(String text, int most) {
        return text.substring(0, text.offsetByCodePoints(0, Math.min(most, text.codePointCount(0, text.length()))));
    }

    @Override
    public @Nullable ConvertedCallAnswer convert(JsonNode response) {
        String answer = stringIn(response, "answer");
        String tool = stringIn(response, "tool");
        JsonNode observed = response.get("observations");
        String generated = stringIn(response, "generated_tool_calls");
        JsonNode uses = response.get("uses");
        JsonNode sent = response.get("sent_count");
        JsonNode cameBack = response.get("came_back_count");
        if (answer == null
                || tool == null
                || observed == null
                || !observed.isArray()
                || generated == null
                || uses == null
                || !uses.isIntegralNumber()
                || !uses.canConvertToLong()
                || uses.longValue() < 0
                || uses.longValue() != observed.size()) {
            return null;
        }
        ModelToolName name;
        try {
            name = new ModelToolName(tool);
        } catch (IllegalArgumentException invalidToolName) {
            return null;
        }
        List<SearchObservation> observations = new ArrayList<>();
        for (JsonNode observation : observed) {
            String query = stringIn(observation, "query");
            String found = stringIn(observation, "found");
            if (query == null || found == null) {
                return null;
            }
            observations.add(new SearchObservation(name, query, found));
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
                observations,
                null,
                null,
                generated.codePointCount(0, generated.length()));
    }
}
