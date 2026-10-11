package com.lilradish.lite.app.inference

import static java.nio.charset.StandardCharsets.UTF_8

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import com.lilradish.lite.app.inference.ModelEndpoint.CallKey
import com.lilradish.lite.app.inference.development.DevelopmentSearchConversion
import com.lilradish.lite.domain.inference.CallOutcome
import com.lilradish.lite.domain.inference.CallProgress
import com.lilradish.lite.domain.inference.CallRequest
import com.lilradish.lite.domain.inference.ModelCallPurpose
import com.lilradish.lite.domain.inference.ModelCalls
import com.lilradish.lite.domain.model.DeployedModel
import com.lilradish.lite.domain.model.DeployedTool
import com.lilradish.lite.domain.model.ModelCatalog
import com.lilradish.lite.domain.model.ModelName
import com.lilradish.lite.domain.model.ModelToolKind
import com.lilradish.lite.domain.model.ModelToolName
import com.lilradish.lite.domain.model.SentText
import com.lilradish.lite.testutil.SnapshottingAppender
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.libprunus.core.log.runtime.Loggable
import org.slf4j.LoggerFactory
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import spock.lang.Specification
import spock.lang.Timeout

@Timeout(20)
class ModelCallsConfigurationRoutingIntegrationSpec extends Specification {

    static final ModelToolName SEARCH = new ModelToolName("web_search")
    static final List<DeployedModel> MODELS = ["cedar_model", "birch_model"].collect {
        new DeployedModel(new ModelName(it), [], 1000, BigDecimal.ONE, 1000, [],
                [new DeployedTool(SEARCH, ModelToolKind.WEB_SEARCH, 3, [])])
    }
    static final Map<String, String> KEYS = [CEDAR_MODEL_KEY: "imaginary-cedar.key", BIRCH_MODEL_KEY: "imaginary-birch.key"]

    def "ordinary and tool calls keep the selected model's address and key when sharing is #sharedAddress and resend is #resending"() {
        given:
        def workers = Executors.newFixedThreadPool(2)
        def first = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
        def second = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
        def received = new CopyOnWriteArrayList<Map>()
        [first, second].each { server ->
            server.executor = workers
            server.createContext("/") { HttpExchange exchange ->
                exchange.withCloseable {
                    def body = EndpointJson.READER.readTree(exchange.requestBody.readAllBytes())
                    received.add([address: "http://" + exchange.requestHeaders.getFirst("Host") + exchange.requestURI,
                                  hosts: new ArrayList(exchange.requestHeaders.get("Host") ?: []),
                                  port: server.address.port, path: exchange.requestURI.toString(), method: exchange.requestMethod,
                                  authorization: new ArrayList(exchange.requestHeaders.get("Authorization") ?: []),
                                  accept: exchange.requestHeaders.getFirst("Accept"),
                                  contentType: exchange.requestHeaders.getFirst("Content-Type"), body: body])
                    def tool = exchange.requestURI.path.endsWith("/responses")
                    def turnedAway = resending && body.get("model").textValue() == "vendor-birch_model" &&
                            received.count { it.body.get("model").textValue() == "vendor-birch_model" && it.path == exchange.requestURI.toString() } == 1
                    def reply = turnedAway
                            ? '{"error":{"message":"The server is overloaded","type":"service_unavailable_error","code":"server_is_overloaded"}}'
                            : tool
                            ? '{"answer":"Imaginary search answer","tool":"web_search","query":"question","found":"Imaginary source","uses":1,"generated_tool_calls":"search","observations":[{"query":"question","found":"Imaginary source"}]}'
                            : '{"choices":[{"message":{"content":"Imaginary answer"},"finish_reason":"stop"}]}'
                    byte[] bytes = reply.getBytes(UTF_8)
                    exchange.responseHeaders.set("Content-Type", "application/json")
                    exchange.sendResponseHeaders(turnedAway ? 503 : 200, bytes.length)
                    exchange.responseBody.write(bytes)
                }
            }
            server.start()
        }
        def addresses = ["http://127.0.0.1:${first.address.port}/cedar/v1",
                         "http://127.0.0.1:${sharedAddress ? first.address.port : second.address.port}/${sharedAddress ? 'cedar' : 'birch'}/v1"]
        def birchPort = sharedAddress ? first.address.port : second.address.port
        def birchBase = addresses[1].toString()
        def toolPath = "/routing/search/responses"
        def progress = Mock(CallProgress)
        List<CallOutcome> outcomes = []
        Throwable failure = null

        when:
        runner(KEYS).withPropertyValues((settings(addresses, toolPath) + ["lilradish.model-calls.resend.times=" + (resending ? 1 : 0)]) as String[]).run { context ->
            failure = context.startupFailure
            if (failure == null) {
                def ordinary = context.getBean(ModelCalls)
                def withTools = context.getBean(ToolModelCalls)
                [0, 1, 0].each { index ->
                    outcomes << ordinary.call(request(MODELS[index]), progress)
                    outcomes << withTools.call(new ToolCallRequest(request(MODELS[index]),
                            [new ToolUseLimit(SEARCH, 3)]), progress).outcome()
                }
            }
        }

        then:
        failure == null
        outcomes.size() == 6
        outcomes.every { it instanceof CallOutcome.CameBack }
        outcomes.collect { it.answer() } == ["Imaginary answer", "Imaginary search answer"] * 3
        received.size() == (resending ? 8 : 6)
        received.collect { it.port } == [first.address.port] * 2 + [birchPort] * (resending ? 4 : 2) + [first.address.port] * 2
        received.collect { it.hosts } == [["127.0.0.1:" + first.address.port]] * 2 +
                [["127.0.0.1:" + birchPort]] * (resending ? 4 : 2) + [["127.0.0.1:" + first.address.port]] * 2
        received.collect { it.address } == [addresses[0] + "/chat/completions", addresses[0] + toolPath] +
                (resending ? [birchBase + "/chat/completions"] * 2 + [birchBase + toolPath] * 2 :
                [birchBase + "/chat/completions", birchBase + toolPath]) +
                [addresses[0] + "/chat/completions", addresses[0] + toolPath]
        received.collect { it.path } == ["/cedar/v1/chat/completions", "/cedar/v1" + toolPath] +
                (resending ? ["/${sharedAddress ? 'cedar' : 'birch'}/v1/chat/completions".toString()] * 2 +
                ["/${sharedAddress ? 'cedar' : 'birch'}/v1".toString() + toolPath] * 2 :
                ["/${sharedAddress ? 'cedar' : 'birch'}/v1/chat/completions".toString(),
                 "/${sharedAddress ? 'cedar' : 'birch'}/v1".toString() + toolPath]) +
                ["/cedar/v1/chat/completions", "/cedar/v1" + toolPath]
        received.collect { it.authorization } == [["Bearer " + KEYS.CEDAR_MODEL_KEY]] * 2 +
                [["Bearer " + KEYS.BIRCH_MODEL_KEY]] * (resending ? 4 : 2) + [["Bearer " + KEYS.CEDAR_MODEL_KEY]] * 2
        received.every { it.method == "POST" && it.accept == "application/json" && it.contentType.startsWith("application/json") }
        received.collect { it.body.get("model").textValue() } ==
                ["vendor-cedar_model"] * 2 + ["vendor-birch_model"] * (resending ? 4 : 2) + ["vendor-cedar_model"] * 2
        received.every {
            if (it.path.endsWith(toolPath)) {
                assert it.body.get("question").textValue() == "question"
                assert it.body.get("tools").size() == 1
                assert it.body.get("tools").get(0).get("maximum").longValue() == 3
            } else {
                assert it.body.get("messages").get(0).get("content").textValue() == "system"
                assert it.body.get("messages").get(1).get("content").textValue() == "question"
            }
            true
        }
        6 * progress.aboutToSend()
        (resending ? 2 : 0) * progress.turnedAway(_)
        (resending ? 2 : 0) * progress.mayResend() >> true
        0 * _

        cleanup:
        first.stop(0)
        second.stop(0)
        workers.shutdownNow()
        assert workers.awaitTermination(3, TimeUnit.SECONDS)

        where:
        sharedAddress | resending
        false         | false
        false         | true
        true          | false
        true          | true
    }

    def "the second model's missing configuration stops even a fully lazy context without exposing values"() {
        given:
        def properties = settings(["https://models.example/cedar", "https://models.example/birch"])
        properties = properties.findAll { !it.startsWith("lilradish.model-calls.endpoint.models[1]." + missing + "=") }
        def environment = new HashMap(KEYS)
        if (missing == "process-key") {
            environment.remove("BIRCH_MODEL_KEY")
        }
        Throwable failure = null

        when:
        runner(environment).withInitializer { ConfigurableApplicationContext context ->
            context.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor())
        }.withPropertyValues(properties as String[]).run { context -> failure = context.startupFailure }
        def messages = messagesOf(failure)

        then:
        failure != null
        messages.any { it.contains("birch_model") && it.contains(expected) }
        messages.every { !it.contains("https://models.example") && !it.contains("imaginary-") }

        where:
        missing            || expected
        "base-url"         || "base-url"
        "api-key-variable" || "api-key-variable"
        "process-key"      || "holds no key"
    }

    def "a variable name is resolved exactly in process environment without relaxed spelling or file values"() {
        given:
        def workers = Executors.newFixedThreadPool(1)
        def received = new CopyOnWriteArrayList<Map>()
        def server = wire(workers, received)
        def properties = settings(["https://models.example/cedar", "http://127.0.0.1:${server.address.port}/birch/v1"])
        properties.removeAll { it.startsWith("lilradish.model-calls.endpoint.models[1].api-key-variable=") }
        properties << "lilradish.model-calls.endpoint.models[1].api-key-variable=birch_key"
        properties << "birch_key=imaginary-file-secret"
        def environment = [CEDAR_MODEL_KEY: KEYS.CEDAR_MODEL_KEY, BIRCH_KEY: "imaginary-upper-secret"]
        if (exact) {
            environment.birch_key = KEYS.BIRCH_MODEL_KEY
        }
        Throwable failure = null
        def progress = Mock(CallProgress)
        List<CallOutcome> outcomes = []

        when:
        runner(environment).withPropertyValues(properties as String[]).run { context ->
            failure = context.startupFailure
            if (failure == null) {
                outcomes << context.getBean(ModelCalls).call(request(MODELS[1]), progress)
                outcomes << context.getBean(ToolModelCalls).call(new ToolCallRequest(request(MODELS[1]),
                        [new ToolUseLimit(SEARCH, 3)]), progress).outcome()
            }
        }

        then:
        (failure == null) == exact
        received.size() == (exact ? 2 : 0)
        outcomes.collect { it.answer() } == (exact ? ["Imaginary answer", "Imaginary search answer"] : [])
        outcomes.every { it instanceof CallOutcome.CameBack }
        received.collect { it.authorization } == (exact ? [["Bearer " + KEYS.BIRCH_MODEL_KEY]] * 2 : [])
        received.collect { it.address } == (exact ? ["http://127.0.0.1:${server.address.port}/birch/v1/chat/completions".toString(),
                                                     "http://127.0.0.1:${server.address.port}/birch/v1/responses".toString()] : [])
        received.every { it.body.get("model").textValue() == "vendor-birch_model" }
        if (!exact) {
            assert messagesOf(failure).any { it.contains("birch_model") && it.contains("holds no key") }
            assert messagesOf(failure).every { !it.contains("imaginary-file-secret") && !it.contains("imaginary-upper-secret") }
        }
        (exact ? 2 : 0) * progress.aboutToSend()
        0 * _

        cleanup:
        server.stop(0)
        workers.shutdownNow()
        assert workers.awaitTermination(3, TimeUnit.SECONDS)

        where:
        exact << [false, true]
    }

    def "transport checks the complete client and route model sets before any HTTP call: #shape"() {
        given:
        def workers = Executors.newFixedThreadPool(1)
        def received = new CopyOnWriteArrayList<Map>()
        def server = wire(workers, received)
        def properties = settings(["http://127.0.0.1:${server.address.port}/cedar/v1",
                                   "http://127.0.0.1:${server.address.port}/birch/v1"])
        def progress = Mock(CallProgress)
        Throwable startupFailure = null
        Throwable constructionFailure = null
        List<CallOutcome> outcomes = []

        when:
        runner(KEYS).withPropertyValues(properties as String[]).run { context ->
            startupFailure = context.startupFailure
            if (startupFailure == null) {
                def configured = context.getBean(ModelCallTransport)
                def clients = new HashMap(configured.clients)
                if (shape == "miss") {
                    clients.remove(MODELS[1].name())
                } else if (shape == "exceed") {
                    clients[new ModelName("maple_model")] = clients[MODELS[0].name()]
                }
                try {
                    def transport = new ModelCallTransport(clients, configured.ways, context.getBean(ModelCallConversions))
                    outcomes << new OpenAiCompatibleModelCalls(transport).call(request(MODELS[1]), progress)
                } catch (Throwable refused) {
                    constructionFailure = refused
                }
            }
        }

        then:
        startupFailure == null
        if (valid) {
            assert constructionFailure == null
            assert outcomes.size() == 1
            assert outcomes[0] instanceof CallOutcome.CameBack
            assert outcomes[0].answer() == "Imaginary answer"
            assert received.size() == 1
            assert received[0].address == "http://127.0.0.1:${server.address.port}/birch/v1/chat/completions".toString()
            assert received[0].authorization == ["Bearer " + KEYS.BIRCH_MODEL_KEY]
            assert received[0].body.get("model").textValue() == "vendor-birch_model"
        } else {
            assert constructionFailure instanceof IllegalStateException
            assert constructionFailure.message == "HTTP clients and call ways must cover the same models"
            assert outcomes.isEmpty()
            assert received.isEmpty()
        }
        (valid ? 1 : 0) * progress.aboutToSend()
        0 * _

        cleanup:
        server.stop(0)
        workers.shutdownNow()
        assert workers.awaitTermination(3, TimeUnit.SECONDS)

        where:
        shape    || valid
        "miss"   || false
        "exceed" || false
        "equal"  || true
    }

    def "invalid address or key configuration stays out of the full startup failure and captured logs"() {
        given:
        def properties = settings(["https://models.example/cedar", "https://models.example/birch"])
        def environment = new HashMap(KEYS)
        if (setting == "process-key") {
            environment.BIRCH_MODEL_KEY = value
        } else {
            properties.removeAll { it.startsWith("lilradish.model-calls.endpoint.models[1]." + setting + "=") }
            properties << "lilradish.model-calls.endpoint.models[1].${setting}=${value}"
        }
        def logged = new SnapshottingAppender()
        def root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        logged.start()
        root.addAppender(logged)
        Throwable failure = null

        when:
        runner(environment).withPropertyValues(properties as String[]).run { context -> failure = context.startupFailure }
        def messages = messagesOf(failure)
        def output = new StringWriter()
        failure.printStackTrace(new PrintWriter(output))
        def loggedMessages = logged.list.collect {
            it.formattedMessage + Arrays.toString(it.argumentArray) +
                    (it.throwableProxy == null ? "" : ThrowableProxyUtil.asString(it.throwableProxy))
        }

        then:
        failure != null
        messages.any { it.contains("birch_model") && it.contains(property) }
        !output.toString().contains("imaginary-private")
        !loggedMessages.isEmpty()
        loggedMessages.every { !it.contains("imaginary-private") }

        cleanup:
        root.detachAppender(logged)
        logged.stop()

        where:
        setting            | value                                                  || property
        "base-url"         | "https://imaginary-private@models.example/v1"          || "base-url"
        "base-url"         | "https://models.example/v1?token=imaginary-private"     || "base-url"
        "base-url"         | "https://models.example/imaginary-private space"        || "base-url"
        "api-key-variable" | "imaginary-private"                                    || "api-key-variable"
        "api-key-variable" | "imaginary_private"                                    || "api-key-variable"
        "process-key"      | "imaginary-private token"                              || "api-key-variable"
    }

    def "a malformed call path with a valid base refuses startup without exposing the path in causes or logs"() {
        given:
        def workers = Executors.newFixedThreadPool(1)
        def received = new CopyOnWriteArrayList<Map>()
        def server = wire(workers, received)
        def properties = settings(["http://127.0.0.1:${server.address.port}/cedar/v1",
                                   "http://127.0.0.1:${server.address.port}/birch/v1"])
        properties.removeAll { it.startsWith("lilradish.model-calls.endpoint.models[1].calls[0].path=") }
        properties << "lilradish.model-calls.endpoint.models[1].calls[0].path=/imaginary-private path"
        def logged = new SnapshottingAppender()
        def root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        logged.start()
        root.addAppender(logged)
        Throwable failure = null

        when:
        runner(KEYS).withPropertyValues(properties as String[]).run { context -> failure = context.startupFailure }
        def output = new StringWriter()
        failure.printStackTrace(new PrintWriter(output))
        def causes = []
        for (Throwable cause = failure; cause != null; cause = cause.cause) {
            causes << cause
        }
        def loggedMessages = logged.list.collect {
            it.formattedMessage + Arrays.toString(it.argumentArray) +
                    (it.throwableProxy == null ? "" : ThrowableProxyUtil.asString(it.throwableProxy))
        }

        then:
        failure != null
        causes.last() instanceof IllegalArgumentException
        causes.last().message.contains("birch_model")
        causes.last().message.contains("invalid call path")
        causes.last().cause == null
        causes.last().suppressed.length == 0
        !output.toString().contains("imaginary-private")
        !loggedMessages.isEmpty()
        loggedMessages.every { !it.contains("imaginary-private") }
        received.isEmpty()

        cleanup:
        root.detachAppender(logged)
        logged.stop()
        server.stop(0)
        workers.shutdownNow()
        assert workers.awaitTermination(3, TimeUnit.SECONDS)
    }

    def "woven direct and nested configuration routes hide private slots while preserving model and converter identities"() {
        given:
        def properties = settings(["https://models.example/imaginary-private-cedar", "https://models.example/imaginary-private-birch"])
        properties.removeAll { it.endsWith("api-key-variable=BIRCH_MODEL_KEY") }
        properties << "lilradish.model-calls.endpoint.models[1].api-key-variable=imaginary_private"
        properties.removeAll { it.startsWith("lilradish.model-calls.endpoint.models[1].calls[0].path=") }
        properties << "lilradish.model-calls.endpoint.models[1].calls[0].path=/imaginary_private_tool_route"
        def environment = new HashMap(KEYS)
        environment.imaginary_private = "imaginary-private-key"
        List<String> rendered = []
        List<Boolean> woven = []
        Throwable failure = null
        def logged = new SnapshottingAppender()
        def logger = LoggerFactory.getLogger(ModelCallsConfiguration) as Logger
        logged.start()
        logger.addAppender(logged)

        when:
        runner(environment).withPropertyValues(properties as String[]).run { context ->
            failure = context.startupFailure
            if (failure == null) {
                def endpoint = context.getBean(ModelEndpoint)
                def ways = endpoint.callWays(new ModelCatalog(MODELS))
                def values = [endpoint, endpoint.models()[1], ways[new CallKey(MODELS[1].name(), [])],
                              ways[new CallKey(MODELS[1].name(), [SEARCH])], endpoint.models()[1].calls()[0]]
                woven = values.collect { it instanceof Loggable }
                rendered = values.collect { it.toString() }
                logger.warn("Configured model: {}", values[0])
                logger.warn("Configured call: {}", values[3])
                logger.warn("Configured tool route: {}", values[4])
            }
        }

        then:
        failure == null
        woven == [true] * 5
        rendered.take(4).every { it.contains("birch_model") && it.contains("vendor-birch_model") }
        rendered[0].contains("enabled=true")
        rendered.every { it.contains("development_search") && it.contains("web_search") }
        rendered[4].contains("outbound=development_search")
        rendered[4].contains("inbound=development_search")
        rendered.every { !it.contains("imaginary-private") && !it.contains("imaginary_private") && !it.contains("https://") }
        rendered.every { text ->
            ["path=", "endpoint=", "baseUrl=", "apiKeyVariable=", "connectTimeout=", "readTimeout="].every { !text.contains(it) }
        }
        logged.list.size() == 3
        logged.list.take(2).every { it.formattedMessage.contains("birch_model") && it.formattedMessage.contains("vendor-birch_model") }
        logged.list.every {
            def text = it.formattedMessage + Arrays.toString(it.argumentArray) +
                    (it.throwableProxy == null ? "" : ThrowableProxyUtil.asString(it.throwableProxy))
            assert text.contains("development_search") && text.contains("web_search")
            assert !text.contains("imaginary-private") && !text.contains("imaginary_private") && !text.contains("https://")
            assert ["path=", "endpoint=", "baseUrl=", "apiKeyVariable=", "connectTimeout=", "readTimeout="].every { !text.contains(it) }
            true
        }

        cleanup:
        logger.detachAppender(logged)
        logged.stop()
    }

    def "an explicit empty tool route overrides only its model's ordinary HTTP address and conversions"() {
        given:
        def workers = Executors.newFixedThreadPool(2)
        def received = new CopyOnWriteArrayList<Map>()
        def cedar = wire(workers, received)
        def birch = wire(workers, received)
        def addresses = ["http://127.0.0.1:${cedar.address.port}/cedar/v1".toString(),
                         "http://127.0.0.1:${birch.address.port}/birch/v1".toString()]
        def properties = settings(addresses) + [
            "lilradish.model-calls.endpoint.models[0].calls[1].path=/ordinary/responses",
            "lilradish.model-calls.endpoint.models[0].calls[1].outbound=development_search",
            "lilradish.model-calls.endpoint.models[0].calls[1].inbound=development_search",
        ]
        def progress = Mock(CallProgress)
        List<CallOutcome> outcomes = []
        List<ModelToolName> boundTools = null
        Throwable failure = null

        when:
        runner(KEYS).withPropertyValues(properties as String[]).run { context ->
            failure = context.startupFailure
            if (failure == null) {
                boundTools = context.getBean(ModelEndpoint).models()[0].calls()[1].tools()
                def ordinary = context.getBean(ModelCalls)
                def withTools = context.getBean(ToolModelCalls)
                outcomes << ordinary.call(request(MODELS[0]), progress)
                outcomes << withTools.call(new ToolCallRequest(request(MODELS[0]), [new ToolUseLimit(SEARCH, 3)]), progress).outcome()
                outcomes << ordinary.call(request(MODELS[1]), progress)
                outcomes << withTools.call(new ToolCallRequest(request(MODELS[1]), [new ToolUseLimit(SEARCH, 3)]), progress).outcome()
            }
        }
        assert failure == null : messagesOf(failure)

        then:
        failure == null
        boundTools == []
        outcomes.size() == 4
        outcomes.every { it instanceof CallOutcome.CameBack }
        outcomes.collect { it.answer() } == ["Imaginary search answer", "Imaginary search answer", "Imaginary answer", "Imaginary search answer"]
        received.collect { it.address } == [addresses[0] + "/ordinary/responses", addresses[0] + "/responses",
                                           addresses[1] + "/chat/completions", addresses[1] + "/responses"]
        received.collect { it.authorization } == [["Bearer imaginary-cedar.key"]] * 2 + [["Bearer imaginary-birch.key"]] * 2
        received[0].body == EndpointJson.READER.readTree('{"model":"vendor-cedar_model","question":"question","tools":[]}')
        received[1].body == EndpointJson.READER.readTree('{"model":"vendor-cedar_model","question":"question","tools":[{"name":"web_search","maximum":3}]}')
        received[2].body.get("model").textValue() == "vendor-birch_model"
        received[2].body.get("messages").get(0).get("content").textValue() == "system"
        received[2].body.get("messages").get(1).get("content").textValue() == "question"
        !received[2].body.has("question")
        !received[2].body.has("tools")
        received[3].body == EndpointJson.READER.readTree('{"model":"vendor-birch_model","question":"question","tools":[{"name":"web_search","maximum":3}]}')
        4 * progress.aboutToSend()
        0 * progress._

        cleanup:
        cedar.stop(0)
        birch.stop(0)
        workers.shutdownNow()
        assert workers.awaitTermination(3, TimeUnit.SECONDS)
    }

    private static ApplicationContextRunner runner(Map environment) {
        new ApplicationContextRunner().withUserConfiguration(ModelCallsConfiguration, ModelCallsRequired)
                .withBean(ModelCatalog, { -> new ModelCatalog(MODELS) })
                .withBean(DevelopmentSearchConversion)
                .withInitializer { ConfigurableApplicationContext context ->
                    def sources = context.environment.propertySources
                    def name = StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME
                    sources.remove(name)
                    sources.addLast(new SystemEnvironmentPropertySource(name, environment))
                }
    }

    private static HttpServer wire(def workers, List<Map> received) {
        def server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = workers
        server.createContext("/") { HttpExchange exchange ->
            exchange.withCloseable {
                def body = EndpointJson.READER.readTree(exchange.requestBody.readAllBytes())
                received.add([address: "http://" + exchange.requestHeaders.getFirst("Host") + exchange.requestURI,
                              authorization: new ArrayList(exchange.requestHeaders.get("Authorization") ?: []), body: body])
                def reply = exchange.requestURI.path.endsWith("/responses")
                        ? '{"answer":"Imaginary search answer","tool":"web_search","query":"question","found":"Imaginary source","uses":1,"generated_tool_calls":"search","observations":[{"query":"question","found":"Imaginary source"}]}'
                        : '{"choices":[{"message":{"content":"Imaginary answer"},"finish_reason":"stop"}]}'
                byte[] bytes = reply.getBytes(UTF_8)
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.length)
                exchange.responseBody.write(bytes)
            }
        }
        server.start()
        server
    }

    private static List<String> settings(List<String> addresses, String toolPath = "/responses") {
        def properties = ["lilradish.model-calls.endpoint.enabled=true",
                          "lilradish.model-calls.endpoint.connect-timeout=1s",
                          "lilradish.model-calls.endpoint.read-timeout=2s",
                          "lilradish.model-calls.resend.times=0",
                          "lilradish.model-calls.resend.first-wait=1ms",
                          "lilradish.model-calls.resend.longest-wait=1ms"]
        MODELS.eachWithIndex { model, index ->
            def prefix = "lilradish.model-calls.endpoint.models[${index}]."
            properties.addAll([prefix + "name=" + model.name().value(), prefix + "id=vendor-" + model.name().value(),
                               prefix + "base-url=" + addresses[index],
                               prefix + "api-key-variable=" + (index == 0 ? "CEDAR_MODEL_KEY" : "BIRCH_MODEL_KEY"),
                               prefix + "calls[0].tools[0]=web_search", prefix + "calls[0].path=" + toolPath,
                               prefix + "calls[0].outbound=development_search", prefix + "calls[0].inbound=development_search"])
        }
        properties.collect { it.toString() }
    }

    private static CallRequest request(DeployedModel model) {
        new CallRequest(model, null, ModelCallPurpose.PRODUCE, SentText.measure("system", "question"))
    }

    private static List<String> messagesOf(Throwable failure) {
        def messages = []
        for (Throwable cause = failure; cause != null; cause = cause.cause) {
            messages << String.valueOf(cause.message)
        }
        messages
    }
}
