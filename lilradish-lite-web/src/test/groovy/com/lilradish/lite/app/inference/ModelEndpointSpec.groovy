package com.lilradish.lite.app.inference

import com.lilradish.lite.app.inference.ModelEndpoint.VendorMode
import com.lilradish.lite.app.inference.ModelEndpoint.VendorModel
import com.lilradish.lite.domain.model.DeployedModel
import com.lilradish.lite.domain.model.ModelCatalog
import com.lilradish.lite.domain.model.ModelMode
import com.lilradish.lite.domain.model.ModelName
import java.time.Duration
import spock.lang.Specification

class ModelEndpointSpec extends Specification {

    static final URI BASE = URI.create("https://models.example/v1")

    static final Duration TEN_SECONDS = Duration.ofSeconds(10)

    static final ModelName SAMPLE = new ModelName("sample_model")

    static final ModelName OTHER = new ModelName("other_model")

    static final ModelMode RESEARCH = new ModelMode("research")

    static final ModelMode DEEP = new ModelMode("deep")

    def "a models list left out stands for none, and one given is kept as it was when given"() {
        given:
        def listed = [vendor(SAMPLE, []), vendor(OTHER, [])]

        when:
        def omitted = new ModelEndpoint(true, TEN_SECONDS, TEN_SECONDS, null)
        def supplied = new ModelEndpoint(true, TEN_SECONDS, TEN_SECONDS, listed)
        listed.clear()

        then:
        omitted.models() == []
        supplied.models() == [vendor(SAMPLE, []), vendor(OTHER, [])]
    }

    def "refuses an endpoint it could not call, naming the key"() {
        when:
        new ModelEndpoint(true, connectTimeout, readTimeout, models)

        then:
        def refused = thrown(expected)
        refused.message == message

        where:
        connectTimeout | readTimeout   | models || expected                 | message
        null           | TEN_SECONDS   | []     || NullPointerException     | "lilradish.model-calls.endpoint.connect-timeout must be set"
        Duration.ZERO  | TEN_SECONDS   | []     || IllegalArgumentException | "lilradish.model-calls.endpoint.connect-timeout must be positive: PT0S"
        TEN_SECONDS    | null          | []     || NullPointerException     | "lilradish.model-calls.endpoint.read-timeout must be set"
        TEN_SECONDS    | Duration.ZERO | []     || IllegalArgumentException | "lilradish.model-calls.endpoint.read-timeout must be positive: PT0S"
        TEN_SECONDS    | TEN_SECONDS   | [null] || NullPointerException     | "lilradish.model-calls.endpoint.models holds a null model"
        TEN_SECONDS    | TEN_SECONDS   | [vendor(SAMPLE, []), new VendorModel(null, null, BASE.toString(), "SAMPLE_MODEL_KEY", [null], [])] || NullPointerException | "lilradish.model-calls.endpoint.models[1] has no name"
    }


    def "refuses an unusable model address without echoing it or retaining a parser cause"() {
        given:
        def model = new VendorModel(SAMPLE, "vendor-sample", base, "SAMPLE_MODEL_KEY", [], [])

        when:
        new ModelEndpoint(true, TEN_SECONDS, TEN_SECONDS, [model]).callWays(new ModelCatalog([deployed(SAMPLE, [])]))

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "lilradish.model-calls.endpoint.models gives sample_model a base-url that " + refusal
        refused.cause == null
        !refused.message.contains("imaginary-secret")

        where:
        base                                                        || refusal
        null                                                        || "must be set"
        "models/v1"                                                 || "must be an http or https address"
        "ftp://models.example/v1"                                    || "must be an http or https address"
        "https:/v1"                                                 || "must name a host"
        "https://models.example/v1?key=imaginary-secret"              || "must carry no user information, query or fragment"
        "https://models.example/v1#imaginary-secret"                  || "must carry no user information, query or fragment"
        "https://imaginary-secret@models.example/v1"                  || "must carry no user information, query or fragment"
        "https://models.example/imaginary-secret space"               || "must be an http or https address"
    }

    def "calls chat completions below the base address, whether or not it ends in a slash"() {
        expect:
        new VendorModel(SAMPLE, "vendor-sample", base, "SAMPLE_MODEL_KEY", [], []).address("/chat/completions") == URI.create("https://models.example/v1/chat/completions")

        where:
        base << ["https://models.example/v1", "https://models.example/v1/"]
    }

    def "pairs each model the catalog holds with the one the endpoint lists under its name"() {
        given:
        def sample = new VendorModel(SAMPLE, "vendor-sample", BASE.toString(), "SAMPLE_MODEL_KEY",
                [new VendorMode(RESEARCH, ReasoningEffort.HIGH), new VendorMode(DEEP, ReasoningEffort.LOW)], [])
        def other = new VendorModel(OTHER, "vendor-other", BASE.toString(), "SAMPLE_MODEL_KEY", [], [])
        def catalog = new ModelCatalog([deployed(SAMPLE, [RESEARCH, DEEP]), deployed(OTHER, [])])

        when:
        def paired = endpoint([other, sample]).pairedWith(catalog)

        then:
        paired == [(SAMPLE): sample, (OTHER): other]
    }

    def "refuses to pair a list that does not match the catalog, naming the first mismatch"() {
        given:
        def catalog = new ModelCatalog([deployed(SAMPLE, [RESEARCH])])

        when:
        endpoint(listed).pairedWith(catalog)

        then:
        def refused = thrown(IllegalStateException)
        refused.message == message

        where:
        listed                                                                    || message
        []                                                                        || "The model catalog holds sample_model, which lilradish.model-calls.endpoint.models does not list"
        [vendor(SAMPLE, [RESEARCH]), vendor(SAMPLE, [RESEARCH])]                  || "lilradish.model-calls.endpoint.models lists sample_model more than once"
        [vendor(SAMPLE, [RESEARCH]), vendor(OTHER, [])]                           || "lilradish.model-calls.endpoint.models lists other_model, which the model catalog does not hold"
        [vendor(SAMPLE, [RESEARCH, DEEP])]                                        || "lilradish.model-calls.endpoint.models gives sample_model the mode deep, which the model catalog does not offer it"
        [vendor(SAMPLE, [])]                                                      || "The model catalog offers sample_model the mode research, which lilradish.model-calls.endpoint.models does not give it"
    }

    def "a vendor model's modes left out stand for none, and those given are kept in order as they were when given"() {
        given:
        def listed = [new VendorMode(DEEP, ReasoningEffort.MAX), new VendorMode(RESEARCH, ReasoningEffort.LOW)]

        when:
        def omitted = new VendorModel(SAMPLE, "vendor-sample", BASE.toString(), "SAMPLE_MODEL_KEY", null, [])
        def supplied = new VendorModel(SAMPLE, "vendor-sample", BASE.toString(), "SAMPLE_MODEL_KEY", listed, [])
        listed.clear()

        then:
        omitted.modes() == []
        supplied.modes() == [new VendorMode(DEEP, ReasoningEffort.MAX), new VendorMode(RESEARCH, ReasoningEffort.LOW)]
    }

    def "refuses a vendor model the endpoint could not be asked for"() {
        when:
        new VendorModel(name, id, BASE.toString(), "SAMPLE_MODEL_KEY", modes, [])

        then:
        def refused = thrown(expected)
        refused.message == message

        where:
        name   | id    | modes                                                                               || expected                 | message
        SAMPLE | null  | []                                                                               || IllegalArgumentException | "lilradish.model-calls.endpoint.models gives sample_model no id"
        SAMPLE | " "   | []                                                                                  || IllegalArgumentException | "lilradish.model-calls.endpoint.models gives sample_model no id"
        SAMPLE | "v"   | [null]                                                                              || NullPointerException     | "lilradish.model-calls.endpoint.models gives sample_model a null mode"
        SAMPLE | "v"   | [new VendorMode(null, ReasoningEffort.HIGH)]                                        || NullPointerException     | "lilradish.model-calls.endpoint.models gives sample_model a mode with no name"
        SAMPLE | "v"   | [new VendorMode(RESEARCH, null)]                                                    || NullPointerException     | "lilradish.model-calls.endpoint.models gives sample_model the mode research no reasoning-effort"
        SAMPLE | "v"   | [new VendorMode(DEEP, ReasoningEffort.LOW), new VendorMode(DEEP, ReasoningEffort.HIGH)] || IllegalArgumentException | "lilradish.model-calls.endpoint.models gives sample_model the mode deep more than once"
    }

    def "gives each mode the effort listed for it and no other"() {
        given:
        def model = new VendorModel(SAMPLE, "v", BASE.toString(), "SAMPLE_MODEL_KEY",
                [new VendorMode(RESEARCH, ReasoningEffort.XHIGH), new VendorMode(DEEP, ReasoningEffort.MINIMAL)], [])

        expect:
        model.effortOf(RESEARCH) == ReasoningEffort.XHIGH
        model.effortOf(DEEP) == ReasoningEffort.MINIMAL
    }

    def "refuses the effort of a mode it was not given"() {
        given:
        def model = new VendorModel(SAMPLE, "v", BASE.toString(), "SAMPLE_MODEL_KEY", [new VendorMode(RESEARCH, ReasoningEffort.XHIGH)], [])

        when:
        model.effortOf(new ModelMode("quick"))

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "sample_model is given no mode quick"
    }

    private static ModelEndpoint endpoint(List<VendorModel> models) {
        new ModelEndpoint(true, TEN_SECONDS, TEN_SECONDS, models)
    }

    private static VendorModel vendor(ModelName name, List<ModelMode> modes) {
        new VendorModel(name, "vendor-" + name.value(), BASE.toString(), "SAMPLE_MODEL_KEY", modes.collect { new VendorMode(it, ReasoningEffort.MEDIUM) }, [])
    }

    private static DeployedModel deployed(ModelName name, List<ModelMode> modes) {
        new DeployedModel(name, modes, 200000, new BigDecimal("4"), 8192, [], [])
    }
}
