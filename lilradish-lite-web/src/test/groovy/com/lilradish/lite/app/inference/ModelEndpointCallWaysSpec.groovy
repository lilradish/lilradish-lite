package com.lilradish.lite.app.inference

import com.lilradish.lite.app.inference.ModelEndpoint.CallKey
import com.lilradish.lite.app.inference.ModelEndpoint.VendorCall
import com.lilradish.lite.app.inference.ModelEndpoint.VendorModel
import com.lilradish.lite.domain.model.DeployedModel
import com.lilradish.lite.domain.model.DeployedTool
import com.lilradish.lite.domain.model.ModelCatalog
import com.lilradish.lite.domain.model.ModelName
import com.lilradish.lite.domain.model.ModelToolKind
import com.lilradish.lite.domain.model.ModelToolName
import java.time.Duration
import spock.lang.Specification

class ModelEndpointCallWaysSpec extends Specification {

    static final ModelName MODEL = new ModelName("sample_model")
    static final ModelToolName SEARCH = new ModelToolName("web_search")
    static final ModelToolName OTHER = new ModelToolName("other_tool")
    static final URI BASE = URI.create("https://models.example/v1")
    static final Duration TIMEOUT = Duration.ofSeconds(5)
    static final ModelCatalog CATALOG = new ModelCatalog([
        new DeployedModel(MODEL, [], 1000, BigDecimal.ONE, 1000, [],
                [new DeployedTool(SEARCH, ModelToolKind.WEB_SEARCH, 3, [])])])

    def "a configured tool route pairs the model and tool with its endpoint and converters"() {
        given:
        def call = new VendorCall([SEARCH], "/responses", "sample_outbound", "sample_inbound")
        def endpoint = new ModelEndpoint(true, TIMEOUT, TIMEOUT, [new VendorModel(MODEL, "vendor-sample", BASE.toString(), "SAMPLE_MODEL_KEY", [], [call])])

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
        new ModelEndpoint(true, TIMEOUT, TIMEOUT, [new VendorModel(MODEL, "vendor-sample", BASE.toString(), "SAMPLE_MODEL_KEY", [], [])]).callWays(CATALOG)

        then:
        def refused = thrown(IllegalStateException)
        refused.message.contains("sample_model")
        refused.message.contains("web_search")
    }

    def "a route that asks for a tool the catalog does not offer refuses startup"() {
        given:
        def other = new ModelToolName("other_tool")
        def endpoint = new ModelEndpoint(true, TIMEOUT, TIMEOUT,
                [new VendorModel(MODEL, "vendor-sample", BASE.toString(), "SAMPLE_MODEL_KEY", [],
                        [new VendorCall([other], "/responses", "sample_outbound", "sample_inbound")])])

        when:
        endpoint.callWays(CATALOG)

        then:
        def refused = thrown(IllegalStateException)
        refused.message.contains("other_tool")
        !refused.message.contains("web_search without")
    }

    def "a malformed call path after a valid base is refused without its input or parser cause"() {
        given:
        def endpoint = new ModelEndpoint(true, TIMEOUT, TIMEOUT,
                [new VendorModel(MODEL, "vendor-sample", BASE.toString(), "SAMPLE_MODEL_KEY", [],
                        [new VendorCall([SEARCH], "/imaginary-private path", "sample_outbound", "sample_inbound")])])

        when:
        endpoint.callWays(CATALOG)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message.contains("sample_model")
        refused.message.contains("invalid call path")
        refused.cause == null
        refused.suppressed.length == 0
        def output = new StringWriter()
        refused.printStackTrace(new PrintWriter(output))
        !output.toString().contains("imaginary-private")
        !output.toString().contains("models.example")
    }

    def "every offered tool needs its singleton route even when a combined route includes it"() {
        given:
        def catalog = new ModelCatalog([new DeployedModel(MODEL, [], 1000, BigDecimal.ONE, 1000, [],
                [new DeployedTool(SEARCH, ModelToolKind.WEB_SEARCH, 3, []),
                 new DeployedTool(OTHER, ModelToolKind.WEB_SEARCH, 3, [])])])
        def calls = [new VendorCall([SEARCH, OTHER], "/combined", "sample_outbound", "sample_inbound")]
        if (first) calls.add(new VendorCall([SEARCH], "/first", "sample_outbound", "sample_inbound"))
        if (second) calls.add(new VendorCall([OTHER], "/second", "sample_outbound", "sample_inbound"))
        def endpoint = new ModelEndpoint(true, TIMEOUT, TIMEOUT,
                [new VendorModel(MODEL, "vendor-sample", BASE.toString(), "SAMPLE_MODEL_KEY", [], calls)])

        when:
        endpoint.callWays(catalog)

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "The model catalog offers sample_model the tool ${missing}, but lilradish.model-calls.endpoint.models gives no way to call it with that tool".toString()

        where:
        first | second | missing
        false | false  | "web_search"
        true  | false  | "other_tool"
        false | true   | "web_search"
    }

    def "singleton routes and an additional combination coexist without replacing any endpoint"() {
        given:
        def catalog = new ModelCatalog([new DeployedModel(MODEL, [], 1000, BigDecimal.ONE, 1000, [],
                [new DeployedTool(SEARCH, ModelToolKind.WEB_SEARCH, 3, []),
                 new DeployedTool(OTHER, ModelToolKind.WEB_SEARCH, 3, [])])])
        def endpoint = new ModelEndpoint(true, TIMEOUT, TIMEOUT,
                [new VendorModel(MODEL, "vendor-sample", BASE.toString(), "SAMPLE_MODEL_KEY", [],
                        [new VendorCall([SEARCH], "/first", "first_out", "first_in"),
                         new VendorCall([OTHER], "/second", "second_out", "second_in"),
                         new VendorCall([OTHER, SEARCH], "/combined", "combo_out", "combo_in")])])

        when:
        def ways = endpoint.callWays(catalog)

        then:
        ways.keySet() == [new CallKey(MODEL, []), new CallKey(MODEL, [SEARCH]),
                         new CallKey(MODEL, [OTHER]), new CallKey(MODEL, [SEARCH, OTHER])] as Set
        ways[new CallKey(MODEL, [SEARCH])].endpoint() == URI.create("https://models.example/v1/first")
        ways[new CallKey(MODEL, [OTHER])].endpoint() == URI.create("https://models.example/v1/second")
        ways[new CallKey(MODEL, [SEARCH, OTHER])].endpoint() == URI.create("https://models.example/v1/combined")
        ways[new CallKey(MODEL, [SEARCH, OTHER])].outbound() == "combo_out"
        ways[new CallKey(MODEL, [SEARCH, OTHER])].inbound() == "combo_in"
    }

    def "canonical tool order cannot hide a duplicate configured route"() {
        given:
        def catalog = new ModelCatalog([new DeployedModel(MODEL, [], 1000, BigDecimal.ONE, 1000, [],
                [new DeployedTool(SEARCH, ModelToolKind.WEB_SEARCH, 3, []),
                 new DeployedTool(OTHER, ModelToolKind.WEB_SEARCH, 3, [])])])
        def endpoint = new ModelEndpoint(true, TIMEOUT, TIMEOUT,
                [new VendorModel(MODEL, "vendor-sample", BASE.toString(), "SAMPLE_MODEL_KEY", [],
                        [new VendorCall([SEARCH], "/first", "sample_outbound", "sample_inbound"),
                         new VendorCall([OTHER], "/second", "sample_outbound", "sample_inbound"),
                         new VendorCall([SEARCH, OTHER], "/combined", "sample_outbound", "sample_inbound"),
                         new VendorCall([OTHER, SEARCH], "/duplicate", "sample_outbound", "sample_inbound")])])

        when:
        endpoint.callWays(catalog)

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "lilradish.model-calls.endpoint.models gives sample_model the same tool call more than once: [ModelToolName(value=other_tool), ModelToolName(value=web_search)]"
    }

    def "an explicitly empty tools route replaces the ordinary route and leaves the singleton intact"() {
        given:
        def endpoint = new ModelEndpoint(true, TIMEOUT, TIMEOUT,
                [new VendorModel(MODEL, "vendor-sample", BASE.toString(), "SAMPLE_MODEL_KEY", [],
                        [new VendorCall([SEARCH], "/search", "search_out", "search_in"),
                         new VendorCall([], "/ordinary", "ordinary_out", "ordinary_in")])])

        when:
        def ways = endpoint.callWays(CATALOG)

        then:
        ways.keySet() == [new CallKey(MODEL, []), new CallKey(MODEL, [SEARCH])] as Set
        ways[new CallKey(MODEL, [])].endpoint() == URI.create("https://models.example/v1/ordinary")
        ways[new CallKey(MODEL, [])].outbound() == "ordinary_out"
        ways[new CallKey(MODEL, [])].inbound() == "ordinary_in"
        ways[new CallKey(MODEL, [SEARCH])].endpoint() == URI.create("https://models.example/v1/search")
        ways[new CallKey(MODEL, [SEARCH])].outbound() == "search_out"
        ways[new CallKey(MODEL, [SEARCH])].inbound() == "search_in"
    }

    def "invalid call path shapes refuse the configured route before returning a map"() {
        given:
        def endpoint = new ModelEndpoint(true, TIMEOUT, TIMEOUT,
                [new VendorModel(MODEL, "vendor-sample", BASE.toString(), "SAMPLE_MODEL_KEY", [],
                        [new VendorCall([SEARCH], path, "sample_outbound", "sample_inbound")])])

        when:
        endpoint.callWays(CATALOG)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "lilradish.model-calls.endpoint.models call path must be an absolute path without query or fragment"
        refused.cause == null

        where:
        path << [null, "responses", "//elsewhere.example/responses", "/responses?query=x", "/responses#fragment", "/../responses"]
    }

    def "both converter names must be supplied and nonblank"() {
        when:
        new VendorCall([SEARCH], "/responses", outbound, inbound)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "lilradish.model-calls.endpoint.models call has no converters"

        where:
        outbound          | inbound
        null              | "sample_inbound"
        ""                | "sample_inbound"
        " \t"             | "sample_inbound"
        "sample_outbound" | null
        "sample_outbound" | ""
        "sample_outbound" | " \t"
    }

    def "a whole missing tools list refuses construction of a route key"() {
        when:
        new CallKey(MODEL, null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "A model call has no tools list"
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
