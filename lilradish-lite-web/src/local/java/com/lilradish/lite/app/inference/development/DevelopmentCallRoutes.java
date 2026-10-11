package com.lilradish.lite.app.inference.development;

import com.lilradish.lite.development.DevelopmentOnly;
import com.lilradish.lite.domain.model.ModelName;
import com.lilradish.lite.domain.model.ModelToolName;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** The development deployment's configured ways to call its stand-in with tools. */
@DevelopmentOnly
@ConfigurationProperties(prefix = "lilradish.development-model-calls", ignoreUnknownFields = false)
record DevelopmentCallRoutes(List<Route> routes) {

    DevelopmentCallRoutes {
        routes = routes == null ? List.of() : List.copyOf(routes);
    }

    record Route(ModelName model, List<ModelToolName> tools, String outbound, String inbound) {
        Route {
            if (model == null || tools == null || tools.isEmpty() || outbound == null || inbound == null) {
                throw new IllegalArgumentException("A development model call route is incomplete");
            }
            tools = List.copyOf(tools);
        }
    }
}
