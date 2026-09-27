package org.lilradish.lite.app.inference

import java.time.Duration
import org.lilradish.lite.app.inference.ModelEndpoint.VendorMode
import org.lilradish.lite.app.inference.ModelEndpoint.VendorModel
import org.lilradish.lite.domain.model.DeployedModel
import org.lilradish.lite.domain.model.ModelCatalog
import org.lilradish.lite.domain.model.ModelMode
import org.lilradish.lite.domain.model.ModelName
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
        def listed = [vendor(SAMPLE, [])]

        when:
        def omitted = new ModelEndpoint(BASE, TEN_SECONDS, TEN_SECONDS, null)
        def supplied = new ModelEndpoint(BASE, TEN_SECONDS, TEN_SECONDS, listed)
        listed << vendor(OTHER, [])

        then:
        omitted.models() == []
        supplied.models() == [vendor(SAMPLE, [])]
    }

    def "refuses an endpoint it could not call, naming the key"() {
        when:
        new ModelEndpoint(baseUrl, connectTimeout, readTimeout, models)

        then:
        def refused = thrown(expected)
        refused.message == message

        where:
        baseUrl                    | connectTimeout | readTimeout   | models || expected                 | message
        null                       | TEN_SECONDS    | TEN_SECONDS   | []     || NullPointerException     | "lilradish.model-calls.endpoint.base-url must be set"
        URI.create("models/v1")    | TEN_SECONDS    | TEN_SECONDS   | []     || IllegalArgumentException | "lilradish.model-calls.endpoint.base-url must be an http or https address"
        URI.create("ftp://models.example/v1") | TEN_SECONDS | TEN_SECONDS | [] || IllegalArgumentException | "lilradish.model-calls.endpoint.base-url must be an http or https address"
        URI.create("https:/v1")    | TEN_SECONDS    | TEN_SECONDS   | []     || IllegalArgumentException | "lilradish.model-calls.endpoint.base-url must name a host"
        URI.create("https://models.example/v1?key=secret") | TEN_SECONDS | TEN_SECONDS | [] || IllegalArgumentException | "lilradish.model-calls.endpoint.base-url must carry no query and no fragment"
        URI.create("https://models.example/v1#secret") | TEN_SECONDS | TEN_SECONDS | [] || IllegalArgumentException | "lilradish.model-calls.endpoint.base-url must carry no query and no fragment"
        BASE                       | null           | TEN_SECONDS   | []     || NullPointerException     | "lilradish.model-calls.endpoint.connect-timeout must be set"
        BASE                       | Duration.ZERO  | TEN_SECONDS   | []     || IllegalArgumentException | "lilradish.model-calls.endpoint.connect-timeout must be positive: PT0S"
        BASE                       | TEN_SECONDS    | null          | []     || NullPointerException     | "lilradish.model-calls.endpoint.read-timeout must be set"
        BASE                       | TEN_SECONDS    | Duration.ZERO | []     || IllegalArgumentException | "lilradish.model-calls.endpoint.read-timeout must be positive: PT0S"
        BASE                       | TEN_SECONDS    | TEN_SECONDS   | [null] || NullPointerException     | "lilradish.model-calls.endpoint.models holds a null model"
        BASE                       | TEN_SECONDS    | TEN_SECONDS   | [vendor(SAMPLE, []), new VendorModel(null, null, [null])] || NullPointerException | "lilradish.model-calls.endpoint.models[1] has no name"
    }

    def "calls chat completions below the base address, whether or not it ends in a slash"() {
        expect:
        endpoint(URI.create(base)).completions() == URI.create("https://models.example/v1/chat/completions")

        where:
        base << ["https://models.example/v1", "https://models.example/v1/"]
    }

    def "pairs each model the catalog holds with the one the endpoint lists under its name"() {
        given:
        def sample = new VendorModel(SAMPLE, "vendor-sample", [new VendorMode(RESEARCH, ReasoningEffort.HIGH)])
        def other = new VendorModel(OTHER, "vendor-other", [])
        def catalog = new ModelCatalog([deployed(SAMPLE, [RESEARCH]), deployed(OTHER, [])])

        when:
        def paired = endpoint(BASE, [other, sample]).pairedWith(catalog)

        then:
        paired == [(SAMPLE): sample, (OTHER): other]
    }

    def "refuses to pair a list that does not match the catalog, naming the first mismatch"() {
        given:
        def catalog = new ModelCatalog([deployed(SAMPLE, [RESEARCH])])

        when:
        endpoint(BASE, listed).pairedWith(catalog)

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
        def omitted = new VendorModel(SAMPLE, "vendor-sample", null)
        def supplied = new VendorModel(SAMPLE, "vendor-sample", listed)
        listed.clear()

        then:
        omitted.modes() == []
        supplied.modes() == [new VendorMode(DEEP, ReasoningEffort.MAX), new VendorMode(RESEARCH, ReasoningEffort.LOW)]
    }

    def "refuses a vendor model the endpoint could not be asked for"() {
        when:
        new VendorModel(name, id, modes)

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
        def model = new VendorModel(SAMPLE, "v",
                [new VendorMode(RESEARCH, ReasoningEffort.XHIGH), new VendorMode(DEEP, ReasoningEffort.MINIMAL)])

        expect:
        model.effortOf(RESEARCH) == ReasoningEffort.XHIGH
        model.effortOf(DEEP) == ReasoningEffort.MINIMAL
    }

    def "refuses the effort of a mode it was not given"() {
        given:
        def model = new VendorModel(SAMPLE, "v", [new VendorMode(RESEARCH, ReasoningEffort.XHIGH)])

        when:
        model.effortOf(new ModelMode("quick"))

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "sample_model is given no mode quick"
    }

    private static ModelEndpoint endpoint(URI base, List<VendorModel> models = []) {
        new ModelEndpoint(base, TEN_SECONDS, TEN_SECONDS, models)
    }

    private static VendorModel vendor(ModelName name, List<ModelMode> modes) {
        new VendorModel(name, "vendor-" + name.value(), modes.collect { new VendorMode(it, ReasoningEffort.MEDIUM) })
    }

    private static DeployedModel deployed(ModelName name, List<ModelMode> modes) {
        new DeployedModel(name, modes, 200000, new BigDecimal("4"), 8192, [])
    }
}
