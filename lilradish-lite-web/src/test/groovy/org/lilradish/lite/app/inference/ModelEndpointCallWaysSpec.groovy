package org.lilradish.lite.app.inference

import java.time.Duration
import org.lilradish.lite.app.inference.ModelEndpoint.CallKey
import org.lilradish.lite.app.inference.ModelEndpoint.VendorCall
import org.lilradish.lite.app.inference.ModelEndpoint.VendorModel
import org.lilradish.lite.domain.model.DeployedModel
import org.lilradish.lite.domain.model.DeployedTool
import org.lilradish.lite.domain.model.ModelCatalog
import org.lilradish.lite.domain.model.ModelName
import org.lilradish.lite.domain.model.ModelToolKind
import org.lilradish.lite.domain.model.ModelToolName
import spock.lang.Specification

class ModelEndpointCallWaysSpec extends Specification {

    static final ModelName MODEL = new ModelName("sample_model")
    static final ModelToolName SEARCH = new ModelToolName("web_search")
    static final URI BASE = URI.create("https://models.example/v1")
    static final Duration TIMEOUT = Duration.ofSeconds(5)
    static final ModelCatalog CATALOG = new ModelCatalog([
        new DeployedModel(MODEL, [], 1000, BigDecimal.ONE, 1000, [],
                [new DeployedTool(SEARCH, ModelToolKind.WEB_SEARCH, 3, [])])])

    def "a configured tool route pairs the model and tool with its endpoint and converters"() {
        given:
        def call = new VendorCall([SEARCH], "/responses", "sample_outbound", "sample_inbound")
        def endpoint = new ModelEndpoint(BASE, TIMEOUT, TIMEOUT, [new VendorModel(MODEL, "vendor-sample", [], [call])])

        when:
        def ways = endpoint.callWays(CATALOG)

        then:
        ways[new CallKey(MODEL, [SEARCH])].endpoint() == URI.create("https://models.example/v1/responses")
        ways[new CallKey(MODEL, [SEARCH])].outbound() == "sample_outbound"
        ways[new CallKey(MODEL, [SEARCH])].inbound() == "sample_inbound"
        ways[new CallKey(MODEL, [])].endpoint() == URI.create("https://models.example/v1/chat/completions")
        ways.size() == 2
    }

    def "an offered tool without any configured call way refuses startup naming model and tool"() {
        when:
        new ModelEndpoint(BASE, TIMEOUT, TIMEOUT, [new VendorModel(MODEL, "vendor-sample", [])]).callWays(CATALOG)

        then:
        def refused = thrown(IllegalStateException)
        refused.message.contains("sample_model")
        refused.message.contains("web_search")
    }

    def "a route that asks for a tool the catalog does not offer refuses startup"() {
        given:
        def other = new ModelToolName("other_tool")
        def endpoint = new ModelEndpoint(BASE, TIMEOUT, TIMEOUT,
                [new VendorModel(MODEL, "vendor-sample", [],
                        [new VendorCall([other], "/responses", "sample_outbound", "sample_inbound")])])

        when:
        endpoint.callWays(CATALOG)

        then:
        def refused = thrown(IllegalStateException)
        refused.message.contains("other_tool")
        !refused.message.contains("web_search without")
    }

    def "a route key sorts tools but rejects a repeated or null tool"() {
        given:
        def other = new ModelToolName("other_tool")

        expect:
        new CallKey(MODEL, [SEARCH, other]).tools() == [other, SEARCH]
        new CallKey(MODEL, []).tools().isEmpty()

        when:
        new CallKey(MODEL, [SEARCH, SEARCH])

        then:
        def duplicate = thrown(IllegalArgumentException)
        duplicate.message.contains("repeats tool web_search")

        when:
        new CallKey(MODEL, [null])

        then:
        def missing = thrown(NullPointerException)
        missing.message.contains("null tool")
    }
}
