package com.lilradish.lite.app.inference.development;

import com.lilradish.lite.app.inference.ConvertedCallAnswer;
import com.lilradish.lite.app.inference.EndpointJson;
import com.lilradish.lite.app.inference.ModelCallConversions;
import com.lilradish.lite.app.inference.ModelCallInbound;
import com.lilradish.lite.app.inference.ModelCallOutbound;
import com.lilradish.lite.app.inference.ModelEndpoint.CallKey;
import com.lilradish.lite.app.inference.ToolCallOutcome;
import com.lilradish.lite.app.inference.ToolCallRequest;
import com.lilradish.lite.app.inference.ToolModelCalls;
import com.lilradish.lite.app.inference.ToolUseLimit;
import com.lilradish.lite.development.DevelopmentOnly;
import com.lilradish.lite.domain.inference.CallOutcome;
import com.lilradish.lite.domain.inference.CallProgress;
import com.lilradish.lite.domain.inference.KeptAnswer;
import com.lilradish.lite.domain.inference.UnreadableResponseRecord;
import com.lilradish.lite.domain.model.DeployedModel;
import com.lilradish.lite.domain.model.ModelCatalog;
import com.lilradish.lite.domain.model.ModelToolName;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;

@DevelopmentOnly
@Component
@Lazy(false)
final class DevelopmentToolModelCalls implements ToolModelCalls {

    private final Map<CallKey, DevelopmentCallRoutes.Route> routes;

    private final ModelCallConversions conversions;

    DevelopmentToolModelCalls(
            ModelCatalog catalog,
            DevelopmentCallRoutes configured,
            List<ModelCallOutbound> outbound,
            List<ModelCallInbound> inbound) {
        conversions = new ModelCallConversions(outbound, inbound);
        Map<CallKey, DevelopmentCallRoutes.Route> paired = new HashMap<>();
        for (DevelopmentCallRoutes.Route route : configured.routes()) {
            DeployedModel model = catalog.find(route.model())
                    .orElseThrow(() -> new IllegalStateException("Development call route names model "
                            + route.model().value() + ", which the catalog does not hold"));
            CallKey key = new CallKey(route.model(), route.tools());
            if (paired.putIfAbsent(key, route) != null) {
                throw new IllegalStateException(
                        "Development call route repeats model " + route.model().value() + " and tools " + key.tools());
            }
            for (ModelToolName tool : key.tools()) {
                if (model.tools().stream().noneMatch(offered -> offered.name().equals(tool))) {
                    throw new IllegalStateException("Development call route gives model "
                            + route.model().value() + " tool " + tool.value() + ", which it does not offer");
                }
            }
            conversions.outbound(route.outbound());
            conversions.inbound(route.inbound());
            for (ModelToolName tool : key.tools()) {
                var offered = model.tools().stream()
                        .filter(found -> found.name().equals(tool))
                        .findFirst()
                        .orElseThrow();
                conversions.requireKind(
                        route.outbound(),
                        route.inbound(),
                        offered.kind(),
                        model.name().value(),
                        tool.value());
            }
        }
        for (DeployedModel model : catalog.all()) {
            for (var tool : model.tools()) {
                if (!paired.containsKey(new CallKey(model.name(), List.of(tool.name())))) {
                    throw new IllegalStateException("Development catalog offers model "
                            + model.name().value() + " tool " + tool.name().value() + " without a call route");
                }
            }
        }
        routes = Map.copyOf(paired);
    }

    @Override
    public ToolCallOutcome call(ToolCallRequest request, CallProgress progress) {
        var call = request.call();
        CallKey key = new CallKey(
                call.model().name(),
                request.tools().stream().map(ToolUseLimit::tool).toList());
        DevelopmentCallRoutes.Route route = routes.get(key);
        if (route == null) {
            throw new IllegalArgumentException(
                    "No development call route for model " + key.model().value() + " and tools " + key.tools());
        }
        progress.aboutToSend();
        byte[] sent = conversions
                .outbound(route.outbound())
                .convert(call, request.tools(), call.model().name().value(), null);
        String raw = DevelopmentSearchConversion.replyTo(sent);
        ConvertedCallAnswer converted;
        try {
            converted = conversions.inbound(route.inbound()).convert(EndpointJson.READER.readTree(raw));
        } catch (JacksonException unreadable) {
            converted = null;
        }
        if (converted == null) {
            return new ToolCallOutcome(
                    new CallOutcome.Errored(
                            "The model service returned a response this system could not read.",
                            new UnreadableResponseRecord(KeptAnswer.of(raw), false)),
                    Map.of(),
                    List.of());
        }
        CallOutcome.CameBack answered = converted.sentCount() != null && converted.cameBackCount() != null
                ? new CallOutcome.CameBack(
                        converted.answer(),
                        converted.sentCount(),
                        converted.cameBackCount(),
                        true,
                        converted.cutOff(),
                        converted.didNotFitReason(),
                        converted.declinedWords())
                : CallOutcome.CameBack.measuredHere(
                        call.model(),
                        call.sent(),
                        converted.answer(),
                        converted.cutOff(),
                        converted.didNotFitReason(),
                        converted.declinedWords(),
                        converted.generatedToolCallCharacters());
        return new ToolCallOutcome(answered, converted.reportedUses(), converted.observations());
    }
}
