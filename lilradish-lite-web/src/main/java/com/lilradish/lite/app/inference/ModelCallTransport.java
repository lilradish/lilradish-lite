package com.lilradish.lite.app.inference;

import com.lilradish.lite.app.inference.ModelEndpoint.CallKey;
import com.lilradish.lite.app.inference.ModelEndpoint.CallWay;
import com.lilradish.lite.domain.inference.CallOutcome;
import com.lilradish.lite.domain.inference.CallProgress;
import com.lilradish.lite.domain.inference.CallRequest;
import com.lilradish.lite.domain.inference.UnreadableResponseRecord;
import com.lilradish.lite.domain.model.ModelName;
import com.lilradish.lite.domain.model.ModelToolName;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.databind.JsonNode;

final class ModelCallTransport {

    private final Map<ModelName, RestClient> clients;

    private final Map<CallKey, CallWay> ways;

    private final ModelCallConversions conversions;

    ModelCallTransport(
            Map<ModelName, RestClient> clients, Map<CallKey, CallWay> ways, ModelCallConversions conversions) {
        this.clients = Map.copyOf(clients);
        this.ways = Map.copyOf(ways);
        this.conversions = conversions;
        var routedModels = ways.keySet().stream().map(CallKey::model).collect(java.util.stream.Collectors.toSet());
        if (!this.clients.keySet().equals(routedModels)) {
            throw new IllegalStateException("HTTP clients and call ways must cover the same models");
        }
        for (var entry : ways.entrySet()) {
            try {
                conversions.outbound(entry.getValue().outbound());
                conversions.inbound(entry.getValue().inbound());
            } catch (IllegalStateException missing) {
                throw new IllegalStateException(
                        "Model " + entry.getKey().model().value() + " with tools "
                                + entry.getKey().tools() + " has no configured conversion: " + missing.getMessage(),
                        missing);
            }
        }
    }

    ToolCallOutcome send(CallRequest call, List<ToolUseLimit> tools, CallProgress progress) {
        List<ModelToolName> named = tools.stream().map(ToolUseLimit::tool).toList();
        CallWay way = ways.get(new CallKey(call.model().name(), named));
        if (way == null) {
            if (named.isEmpty()) {
                throw new IllegalArgumentException("No endpoint model is paired with "
                        + call.model().name().value());
            }
            throw new IllegalArgumentException(
                    "No call way is configured for " + call.model().name().value() + " with tools " + named);
        }
        ModelCallOutbound outbound = conversions.outbound(way.outbound());
        ModelCallInbound inbound = conversions.inbound(way.inbound());
        String mode =
                call.mode() == null ? null : way.vendor().effortOf(call.mode()).sent();
        byte[] sent = outbound.convert(call, tools, way.vendor().id(), mode);
        try {
            return Objects.requireNonNull(clients.get(call.model().name()))
                    .post()
                    .uri(way.endpoint())
                    .contentType(MediaType.APPLICATION_JSON)
                    .attribute(TurnAwayResend.PROGRESS, progress)
                    .body(output -> output.write(sent))
                    .exchange((asked, response) -> outcomeOf(call, response, inbound), true);
        } catch (CallTurnedAway ended) {
            return empty(new CallOutcome.TurnedAway(ended.last()));
        } catch (CallNotResent ended) {
            return empty(new CallOutcome.NotResent());
        } catch (ResourceAccessException failed) {
            Throwable cause = Objects.requireNonNullElse(failed.getCause(), failed);
            return empty(new CallOutcome.Errored(
                    "No answer came back: " + cause.getClass().getName(), null));
        }
    }

    private static ToolCallOutcome outcomeOf(CallRequest request, ClientHttpResponse response, ModelCallInbound inbound)
            throws IOException {
        HttpStatusCode status = response.getStatusCode();
        if (!status.is2xxSuccessful()) {
            String said = ErrorBody.read(response).said();
            return empty(new CallOutcome.Errored("HTTP " + status.value() + (said == null ? "" : ": " + said), null));
        }
        var body = response.getBody();
        try {
            return readAnswer(request, new BoundedResponseCapture(body), inbound);
        } finally {
            try {
                body.close();
            } catch (IOException closeFailed) {
                // Cleanup must not replace the call's result or a failure already being propagated.
            }
        }
    }

    private static ToolCallOutcome readAnswer(
            CallRequest request, BoundedResponseCapture captured, ModelCallInbound inbound) throws IOException {
        JsonNode envelope;
        try {
            var reader = inbound instanceof OpenAiCompatibleCallConversion
                    ? EndpointJson.COMPATIBLE_COMPLETION_READER
                    : EndpointJson.READER;
            try (JsonParser parser = reader.createParser(captured)) {
                envelope = reader.readTree(parser);
                if (parser.nextToken() != null) {
                    return unreadable(captured);
                }
            }
        } catch (JacksonIOException failed) {
            Throwable cause = Objects.requireNonNullElse(failed.getCause(), failed);
            return empty(new CallOutcome.Errored(
                    "No answer came back: " + cause.getClass().getName(), null));
        } catch (JacksonException unreadable) {
            return unreadable(captured);
        }
        if (captured.malformedUtf8()) {
            return unreadable(captured);
        }
        ConvertedCallAnswer converted = envelope == null ? null : inbound.convert(envelope);
        if (converted == null) {
            return unreadable(captured);
        }
        return answered(request, converted);
    }

    private static ToolCallOutcome answered(CallRequest request, ConvertedCallAnswer converted) {
        CallOutcome.CameBack outcome = converted.sentCount() != null && converted.cameBackCount() != null
                ? new CallOutcome.CameBack(
                        converted.answer(),
                        converted.sentCount(),
                        converted.cameBackCount(),
                        true,
                        converted.cutOff(),
                        converted.didNotFitReason(),
                        converted.declinedWords())
                : CallOutcome.CameBack.measuredHere(
                        request.model(),
                        request.sent(),
                        converted.answer(),
                        converted.cutOff(),
                        converted.didNotFitReason(),
                        converted.declinedWords(),
                        converted.generatedToolCallCharacters());
        return new ToolCallOutcome(outcome, converted.reportedUses(), converted.observations());
    }

    private static ToolCallOutcome unreadable(BoundedResponseCapture captured) {
        var evidence = new UnreadableResponseRecord(captured.evidence(), !captured.ended());
        return empty(
                new CallOutcome.Errored("The model service returned a response this system could not read.", evidence));
    }

    private static ToolCallOutcome empty(CallOutcome outcome) {
        return new ToolCallOutcome(outcome, Map.of(), List.of());
    }
}
