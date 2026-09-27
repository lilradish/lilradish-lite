package org.lilradish.lite.app.inference;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.lilradish.lite.app.inference.EndpointJson.stringIn;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.lilradish.lite.app.inference.ModelEndpoint.VendorModel;
import org.lilradish.lite.domain.inference.CallOutcome;
import org.lilradish.lite.domain.inference.CallProgress;
import org.lilradish.lite.domain.inference.CallRequest;
import org.lilradish.lite.domain.inference.ModelCalls;
import org.lilradish.lite.domain.model.DeployedModel;
import org.lilradish.lite.domain.model.ModelName;
import org.lilradish.lite.domain.wire.CanonicalJson;
import org.lilradish.lite.domain.wire.JsonValue;
import org.lilradish.lite.domain.wire.JsonValue.JsonArray;
import org.lilradish.lite.domain.wire.JsonValue.JsonMember;
import org.lilradish.lite.domain.wire.JsonValue.JsonNumber;
import org.lilradish.lite.domain.wire.JsonValue.JsonObject;
import org.lilradish.lite.domain.wire.JsonValue.JsonString;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.databind.JsonNode;

/**
 * A deployment's models called over an OpenAI-compatible chat completions endpoint, one message of
 * each role, asking for no more back than the model may give. What came back is returned as it is:
 * nothing is cleaned or cut here.
 *
 * <p>A refusal, or content the endpoint's filter withheld, is never an answer: it comes back empty and
 * not cut off, so it goes as an answer that does not fit. A body that is not a readable completion is
 * an error; one whose content is null or absent comes back empty.
 */
final class OpenAiCompatibleModelCalls implements ModelCalls {

    private final RestClient client;

    private final URI completions;

    private final Map<ModelName, VendorModel> vendorModels;

    OpenAiCompatibleModelCalls(RestClient client, URI completions, Map<ModelName, VendorModel> vendorModels) {
        this.client = client;
        this.completions = completions;
        this.vendorModels = vendorModels;
    }

    @Override
    public CallOutcome call(CallRequest request, CallProgress progress) {
        byte[] sent = CanonicalJson.write(completionAsked(request)).getBytes(UTF_8);
        try {
            return client.post()
                    .uri(completions)
                    .contentType(MediaType.APPLICATION_JSON)
                    .attribute(TurnAwayResend.PROGRESS, progress)
                    .body(output -> output.write(sent))
                    .exchange((asked, response) -> outcomeOf(request, response), true);
        } catch (CallTurnedAway ended) {
            return new CallOutcome.TurnedAway(ended.last());
        } catch (CallNotResent ended) {
            return new CallOutcome.NotResent();
        } catch (ResourceAccessException failed) {
            return noAnswer(Objects.requireNonNullElse(failed.getCause(), failed));
        }
    }

    private JsonValue completionAsked(CallRequest request) {
        DeployedModel model = request.model();
        VendorModel vendorModel = vendorModels.get(model.name());
        if (vendorModel == null) {
            throw new IllegalArgumentException(
                    "No endpoint model is paired with " + model.name().value());
        }
        List<JsonMember> members = new ArrayList<>(4);
        members.add(new JsonMember("model", new JsonString(vendorModel.id())));
        members.add(new JsonMember(
                "messages",
                new JsonArray(List.of(
                        message("system", request.sent().system()),
                        message("user", request.sent().user())))));
        members.add(new JsonMember(
                "max_completion_tokens", new JsonNumber(BigDecimal.valueOf(model.cameBackPerCallLimit()))));
        if (request.mode() != null) {
            members.add(new JsonMember(
                    "reasoning_effort",
                    new JsonString(vendorModel.effortOf(request.mode()).sent())));
        }
        return new JsonObject(members);
    }

    private static JsonValue message(String role, String content) {
        return new JsonObject(List.of(
                new JsonMember("role", new JsonString(role)), new JsonMember("content", new JsonString(content))));
    }

    private static CallOutcome outcomeOf(CallRequest request, ClientHttpResponse response) throws IOException {
        HttpStatusCode status = response.getStatusCode();
        if (!status.is2xxSuccessful()) {
            String said = ErrorBody.read(response).said();
            return new CallOutcome.Errored("HTTP " + status.value() + (said == null ? "" : ": " + said));
        }
        JsonNode completion;
        try {
            completion = EndpointJson.READER.readTree(response.getBody());
        } catch (JacksonIOException failed) {
            return noAnswer(Objects.requireNonNullElse(failed.getCause(), failed));
        } catch (JacksonException unreadable) {
            return unreadable(status);
        }
        JsonNode choices = completion.get("choices");
        JsonNode choice = choices != null && choices.isArray() ? choices.get(0) : null;
        if (choice == null) {
            return unreadable(status);
        }
        JsonNode message = choice.get("message");
        if (message == null || !message.isObject()) {
            return unreadable(status);
        }
        JsonNode content = message.get("content");
        if (content != null && !content.isNull() && !content.isString()) {
            return unreadable(status);
        }
        String refusal = stringIn(message, "refusal");
        String finishReason = stringIn(choice, "finish_reason");
        boolean refused = (refusal != null && !refusal.isEmpty()) || "content_filter".equals(finishReason);
        String answer = refused || content == null || content.isNull() ? "" : content.stringValue();
        boolean cutOff = !refused && "length".equals(finishReason);
        JsonNode usage = completion.get("usage");
        long sentCount = usage == null ? -1 : countIn(usage, "prompt_tokens");
        long cameBackCount = usage == null ? -1 : countIn(usage, "completion_tokens");
        if (sentCount >= 1 && cameBackCount >= 0) {
            return new CallOutcome.CameBack(answer, sentCount, cameBackCount, true, cutOff);
        }
        return CallOutcome.CameBack.measuredHere(request.model(), request.sent(), answer, cutOff);
    }

    /** Minus one where the count is not a whole number a long holds. */
    private static long countIn(JsonNode usage, String name) {
        JsonNode value = usage.get(name);
        return value != null && value.isIntegralNumber() && value.canConvertToLong() ? value.longValue() : -1;
    }

    private static CallOutcome unreadable(HttpStatusCode status) {
        return new CallOutcome.Errored("HTTP " + status.value() + " came back without a readable chat completion");
    }

    private static CallOutcome noAnswer(Throwable failure) {
        return new CallOutcome.Errored("No answer came back: " + failure);
    }
}
