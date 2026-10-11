package com.lilradish.lite

import com.lilradish.lite.app.identification.UserIdentification
import com.lilradish.lite.app.identification.development.DevelopmentUserIdentification
import com.lilradish.lite.app.inference.ToolModelCalls
import com.lilradish.lite.app.inference.development.DevelopmentModelCalls
import com.lilradish.lite.app.inference.development.DevelopmentSearchConversion
import com.lilradish.lite.app.search.SearchWays
import com.lilradish.lite.domain.identity.UserId
import com.lilradish.lite.domain.inference.ModelCalls
import com.lilradish.lite.domain.model.ModelCatalog
import jakarta.servlet.http.Cookie
import java.util.function.Supplier
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.mock.web.MockHttpServletRequest
import spock.lang.Specification

class DevelopmentProfilesIntegrationSpec extends Specification {

    def '#profile alone assembles the development callers and identity'() {
        given:
        def request = new MockHttpServletRequest()
        request.setCookies(new Cookie('lilradish_user', '000001'))
        def runner = new ApplicationContextRunner({ ->
            def context = new AnnotationConfigApplicationContext()
            context.scan('com.lilradish.lite.app.model',
                    'com.lilradish.lite.app.identification.development',
                    'com.lilradish.lite.app.inference.development',
                    'com.lilradish.lite.app.search.development')
            context
        })
                .withUserConfiguration(SearchWays)
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=${profile}", 'spring.config.location=classpath:/,classpath:/config/')

        when:
        def loaded = [:]
        runner.run { context ->
            loaded.failure = context.startupFailure
            if (context.startupFailure == null) {
                loaded.profiles = context.environment.activeProfiles.toList()
                loaded.sources = context.environment.propertySources*.name
                loaded.identity = context.getBean(UserIdentification)
                loaded.user = loaded.identity.identify(request)
                loaded.calls = context.getBean(ModelCalls)
                loaded.toolCalls = context.getBean(ToolModelCalls)
                loaded.models = context.getBean(ModelCatalog).all()
                loaded.ways = context.getBean(SearchWays).all()
            }
        }

        then:
        loaded.failure == null
        loaded.profiles == [profile]
        loaded.sources.any { it.contains("config/application-${profile}.yaml") }
        loaded.identity instanceof DevelopmentUserIdentification
        loaded.user == Optional.of(new UserId('000001'))
        loaded.calls instanceof DevelopmentModelCalls
        loaded.toolCalls.class.name == 'com.lilradish.lite.app.inference.development.DevelopmentToolModelCalls'
        loaded.models*.name()*.value() == ['sample_model', 'sample_reader']
        loaded.models*.tools().collect { it*.name()*.value() } == [['web_search', 'evidence_search'], ['web_search', 'evidence_search']]
        loaded.ways.keySet() == ['sample_web_search'] as Set

        where:
        profile << ['local', 'dev']
    }

    def 'prod without deployment values fails and names a missing placeholder'() {
        when:
        Throwable failure = null
        productionContext([:]).run { context -> failure = context.startupFailure }

        then:
        failure != null
        causesOf(failure).any { String.valueOf(it.message).contains('LILRADISH_MODEL_NAME') }
    }

    def 'prod binds deployment placeholders and assembles production callers and supplied identity'() {
        given:
        UserIdentification supplied = { request -> Optional.empty() }

        when:
        def loaded = [:]
        productionContext(PRODUCTION, supplied).run { context ->
            loaded.failure = context.startupFailure
            if (context.startupFailure == null) {
                loaded.profiles = context.environment.activeProfiles.toList()
                loaded.sources = context.environment.propertySources*.name
                loaded.maximumFileSize = context.environment.getProperty('lilradish.files.maximum-size')
                loaded.calls = context.getBean(ModelCalls)
                loaded.toolCalls = context.getBean(ToolModelCalls)
                loaded.identity = context.getBean(UserIdentification)
                loaded.models = context.getBean(ModelCatalog).all()
                loaded.endpoint = context.getBean(Class.forName('com.lilradish.lite.app.inference.ModelEndpoint'))
                loaded.resend = context.getBean(Class.forName('com.lilradish.lite.app.inference.ResendPolicy'))
            }
        }

        then:
        loaded.failure == null
        loaded.profiles == ['prod']
        !loaded.sources.any { it.contains('application-local.yaml') || it.contains('application-dev.yaml') }
        loaded.maximumFileSize == '100MB'
        loaded.calls.class.name == 'com.lilradish.lite.app.inference.OpenAiCompatibleModelCalls'
        loaded.toolCalls.class.name == 'com.lilradish.lite.app.inference.ConfiguredToolModelCalls'
        loaded.identity.is(supplied)
        loaded.models.collect { model ->
            [name: model.name().value(), modes: model.modes()*.value(),
             sent: model.sentPerCallLimit(), unit: model.charactersPerUnit(), back: model.cameBackPerCallLimit(),
             prices: model.pricesPerMillion().collect { [it.currency().currencyCode, it.sentPerMillion(), it.cameBackPerMillion()] },
             tools: model.tools().collect { tool ->
                 [tool.name().value(), tool.kind().name(), tool.maxUsesPerCall(),
                  tool.pricesPerUse().collect { [it.currency().currencyCode, it.perUse()] }]
             }]
        } == [
            [name: 'imaginary_model', modes: ['imaginary_mode', 'careful_mode'], sent: 200000, unit: 4G, back: 8192,
             prices: [['USD', 7G, 21G], ['EUR', 6G, 19G]],
             tools: [['imaginary_search', 'WEB_SEARCH', 3, [['USD', 0.01G], ['EUR', 0.02G]]],
                     ['imaginary_evidence', 'WEB_SEARCH', 3, [['USD', 0.03G], ['EUR', 0.04G]]]]],
            [name: 'imaginary_reader', modes: ['imaginary_mode', 'careful_mode'], sent: 200000, unit: 4G, back: 8192,
             prices: [['USD', 8G, 23G], ['EUR', 5G, 17G]],
             tools: [['imaginary_search', 'WEB_SEARCH', 3, [['USD', 0.05G], ['EUR', 0.06G]]],
                     ['imaginary_evidence', 'WEB_SEARCH', 3, [['USD', 0.07G], ['EUR', 0.08G]]]]],
        ]
        loaded.endpoint.enabled()
        loaded.endpoint.connectTimeout() == java.time.Duration.ofSeconds(5)
        loaded.endpoint.readTimeout() == java.time.Duration.ofSeconds(60)
        loaded.endpoint.models().collect { model ->
            [name: model.name().value(), id: model.id(), url: model.baseUrl(), key: model.apiKeyVariable(),
             modes: model.modes().collect { [it.mode().value(), it.reasoningEffort().name()] },
             calls: model.calls().collect { [it.tools()*.value(), it.path(), it.outbound(), it.inbound()] }]
        } == [
            [name: 'imaginary_model', id: 'imaginary-vendor-model', url: 'https://models.example.invalid/v1',
             key: 'IMAGINARY_MODEL_TEST_KEY', modes: [['imaginary_mode', 'HIGH'], ['careful_mode', 'LOW']],
             calls: [[['imaginary_search'], '/chat/completions', 'development_search', 'development_search'],
                     [['imaginary_evidence'], '/chat/completions', 'development_search', 'development_search'],
                     [['imaginary_search', 'imaginary_evidence'], '/chat/completions', 'development_search', 'development_search']]],
            [name: 'imaginary_reader', id: 'imaginary-vendor-reader', url: 'https://reader.example.invalid/v1',
             key: 'IMAGINARY_READER_TEST_KEY', modes: [['imaginary_mode', 'HIGH'], ['careful_mode', 'LOW']],
             calls: [[['imaginary_search'], '/chat/completions', 'development_search', 'development_search'],
                     [['imaginary_evidence'], '/chat/completions', 'development_search', 'development_search'],
                     [['imaginary_search', 'imaginary_evidence'], '/chat/completions', 'development_search', 'development_search']]],
        ]
        loaded.resend.times() == 3
        loaded.resend.firstWait() == java.time.Duration.ofSeconds(1)
        loaded.resend.longestWait() == java.time.Duration.ofSeconds(30)
    }

    def 'prod refuses a single missing deployment value #missing before any call'() {
        given:
        def environment = new LinkedHashMap(PRODUCTION)
        environment.remove(missing)

        when:
        Throwable failure = null
        productionContext(environment).run { context -> failure = context.startupFailure }

        then:
        failure != null
        causesOf(failure).any { String.valueOf(it.message).contains(diagnostic) }

        where:
        missing                           || diagnostic
        'LILRADISH_MODEL_ID'               || 'LILRADISH_MODEL_ID'
        'LILRADISH_MODEL_NAME'             || 'LILRADISH_MODEL_NAME'
        'LILRADISH_MODEL_MODE'             || 'LILRADISH_MODEL_MODE'
        'LILRADISH_MODEL_BASE_URL'         || 'base-url'
        'LILRADISH_MODEL_API_KEY_VARIABLE' || 'api-key-variable'
        'LILRADISH_MODEL_CALL_PATH'        || 'call path'
        'LILRADISH_MODEL_CALL_OUTBOUND'    || 'LILRADISH_MODEL_CALL_OUTBOUND'
        'LILRADISH_MODEL_CALL_INBOUND'     || 'LILRADISH_MODEL_CALL_INBOUND'
    }

    private static final Map<String, Object> PRODUCTION = [
        LILRADISH_MODEL_NAME: 'imaginary_model',
        LILRADISH_MODEL_MODE: 'imaginary_mode',
        LILRADISH_MODEL_SENT_PER_CALL_LIMIT: '200000',
        LILRADISH_MODEL_CHARACTERS_PER_UNIT: '4',
        LILRADISH_MODEL_CAME_BACK_PER_CALL_LIMIT: '8192',
        LILRADISH_MODEL_PRICE_CURRENCY: 'USD',
        LILRADISH_MODEL_SENT_PER_MILLION: '7',
        LILRADISH_MODEL_CAME_BACK_PER_MILLION: '21',
        LILRADISH_MODEL_TOOL_NAME: 'imaginary_search',
        LILRADISH_MODEL_TOOL_KIND: 'web_search',
        LILRADISH_MODEL_TOOL_MAX_USES: '3',
        LILRADISH_MODEL_TOOL_PRICE_CURRENCY: 'USD',
        LILRADISH_MODEL_TOOL_PRICE_PER_USE: '0.01',
        LILRADISH_ENDPOINT_CONNECT_TIMEOUT: '5s',
        LILRADISH_ENDPOINT_READ_TIMEOUT: '60s',
        LILRADISH_MODEL_ID: 'imaginary-vendor-model',
        LILRADISH_MODEL_BASE_URL: 'https://models.example.invalid/v1',
        LILRADISH_MODEL_API_KEY_VARIABLE: 'IMAGINARY_MODEL_TEST_KEY',
        LILRADISH_MODEL_REASONING_EFFORT: 'high',
        LILRADISH_MODEL_CALL_PATH: '/chat/completions',
        LILRADISH_MODEL_CALL_OUTBOUND: 'development_search',
        LILRADISH_MODEL_CALL_INBOUND: 'development_search',
        LILRADISH_RESEND_TIMES: '3',
        LILRADISH_RESEND_FIRST_WAIT: '1s',
        LILRADISH_RESEND_LONGEST_WAIT: '30s',
        IMAGINARY_MODEL_TEST_KEY: 'imaginary-model.key',
        LILRADISH_MODEL_MODE_2: 'careful_mode',
        LILRADISH_MODEL_PRICE_CURRENCY_2: 'EUR',
        LILRADISH_MODEL_SENT_PER_MILLION_2: '6',
        LILRADISH_MODEL_CAME_BACK_PER_MILLION_2: '19',
        LILRADISH_MODEL_TOOL_PRICE_CURRENCY_2: 'EUR',
        LILRADISH_MODEL_TOOL_PRICE_PER_USE_2: '0.02',
        LILRADISH_MODEL_TOOL_2_NAME: 'imaginary_evidence',
        LILRADISH_MODEL_TOOL_2_KIND: 'web_search',
        LILRADISH_MODEL_TOOL_2_MAX_USES: '3',
        LILRADISH_MODEL_TOOL_2_PRICE_CURRENCY: 'USD',
        LILRADISH_MODEL_TOOL_2_PRICE_PER_USE: '0.03',
        LILRADISH_MODEL_TOOL_2_PRICE_CURRENCY_2: 'EUR',
        LILRADISH_MODEL_TOOL_2_PRICE_PER_USE_2: '0.04',
        LILRADISH_MODEL_REASONING_EFFORT_2: 'low',
        LILRADISH_MODEL_CALL_2_PATH: '/chat/completions',
        LILRADISH_MODEL_CALL_2_OUTBOUND: 'development_search',
        LILRADISH_MODEL_CALL_2_INBOUND: 'development_search',
        LILRADISH_MODEL_CALL_PAIR_PATH: '/chat/completions',
        LILRADISH_MODEL_CALL_PAIR_OUTBOUND: 'development_search',
        LILRADISH_MODEL_CALL_PAIR_INBOUND: 'development_search',
        LILRADISH_MODEL_2_MODE_2: 'careful_mode',
        LILRADISH_MODEL_2_PRICE_CURRENCY_2: 'EUR',
        LILRADISH_MODEL_2_SENT_PER_MILLION_2: '5',
        LILRADISH_MODEL_2_CAME_BACK_PER_MILLION_2: '17',
        LILRADISH_MODEL_2_TOOL_PRICE_CURRENCY_2: 'EUR',
        LILRADISH_MODEL_2_TOOL_PRICE_PER_USE_2: '0.06',
        LILRADISH_MODEL_2_TOOL_2_NAME: 'imaginary_evidence',
        LILRADISH_MODEL_2_TOOL_2_KIND: 'web_search',
        LILRADISH_MODEL_2_TOOL_2_MAX_USES: '3',
        LILRADISH_MODEL_2_TOOL_2_PRICE_CURRENCY: 'USD',
        LILRADISH_MODEL_2_TOOL_2_PRICE_PER_USE: '0.07',
        LILRADISH_MODEL_2_TOOL_2_PRICE_CURRENCY_2: 'EUR',
        LILRADISH_MODEL_2_TOOL_2_PRICE_PER_USE_2: '0.08',
        LILRADISH_MODEL_2_REASONING_EFFORT_2: 'low',
        LILRADISH_MODEL_2_CALL_2_PATH: '/chat/completions',
        LILRADISH_MODEL_2_CALL_2_OUTBOUND: 'development_search',
        LILRADISH_MODEL_2_CALL_2_INBOUND: 'development_search',
        LILRADISH_MODEL_2_CALL_PAIR_PATH: '/chat/completions',
        LILRADISH_MODEL_2_CALL_PAIR_OUTBOUND: 'development_search',
        LILRADISH_MODEL_2_CALL_PAIR_INBOUND: 'development_search',
        LILRADISH_MODEL_2_NAME: 'imaginary_reader',
        LILRADISH_MODEL_2_MODE: 'imaginary_mode',
        LILRADISH_MODEL_2_SENT_PER_CALL_LIMIT: '200000',
        LILRADISH_MODEL_2_CHARACTERS_PER_UNIT: '4',
        LILRADISH_MODEL_2_CAME_BACK_PER_CALL_LIMIT: '8192',
        LILRADISH_MODEL_2_PRICE_CURRENCY: 'USD',
        LILRADISH_MODEL_2_SENT_PER_MILLION: '8',
        LILRADISH_MODEL_2_CAME_BACK_PER_MILLION: '23',
        LILRADISH_MODEL_2_TOOL_NAME: 'imaginary_search',
        LILRADISH_MODEL_2_TOOL_KIND: 'web_search',
        LILRADISH_MODEL_2_TOOL_MAX_USES: '3',
        LILRADISH_MODEL_2_TOOL_PRICE_CURRENCY: 'USD',
        LILRADISH_MODEL_2_TOOL_PRICE_PER_USE: '0.05',
        LILRADISH_MODEL_2_ID: 'imaginary-vendor-reader',
        LILRADISH_MODEL_2_BASE_URL: 'https://reader.example.invalid/v1',
        LILRADISH_MODEL_2_API_KEY_VARIABLE: 'IMAGINARY_READER_TEST_KEY',
        LILRADISH_MODEL_2_REASONING_EFFORT: 'high',
        LILRADISH_MODEL_2_CALL_PATH: '/chat/completions',
        LILRADISH_MODEL_2_CALL_OUTBOUND: 'development_search',
        LILRADISH_MODEL_2_CALL_INBOUND: 'development_search',
        IMAGINARY_READER_TEST_KEY: 'imaginary-reader.key',
    ]

    private static ApplicationContextRunner productionContext(Map<String, Object> environment,
                                                               UserIdentification supplied = { request -> Optional.empty() }) {
        new ApplicationContextRunner({ ->
            def context = new AnnotationConfigApplicationContext()
            context.scan('com.lilradish.lite.app.model')
            context
        })
                .withUserConfiguration(Class.forName('com.lilradish.lite.app.inference.ModelCallsConfiguration'),
                        Class.forName('com.lilradish.lite.app.inference.ModelCallsRequired'),
                        Class.forName('com.lilradish.lite.app.identification.UserIdentificationRequired'))
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withInitializer { context ->
                    def name = StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME
                    context.environment.propertySources.replace(name, new SystemEnvironmentPropertySource(name, environment))
                }
                .withBean(UserIdentification, { -> supplied } as Supplier)
                .withBean(DevelopmentSearchConversion)
                .withPropertyValues('spring.profiles.active=prod', 'spring.config.location=classpath:/')
    }

    private static List<Throwable> causesOf(Throwable failure) {
        def causes = []
        for (def cause = failure; cause != null; cause = cause.cause) {
            causes << cause
        }
        causes
    }
}
