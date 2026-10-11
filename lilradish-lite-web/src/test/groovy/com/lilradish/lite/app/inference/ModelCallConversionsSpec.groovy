package com.lilradish.lite.app.inference

import com.lilradish.lite.app.inference.development.DevelopmentSearchConversion
import com.lilradish.lite.domain.inference.CallRequest
import com.lilradish.lite.domain.model.ModelToolKind
import spock.lang.Specification
import tools.jackson.databind.JsonNode

class ModelCallConversionsSpec extends Specification {

    def "registration rejects a #direction converter whose id is #description without converting"() {
        given:
        def outgoing = new NamedOutbound(direction == 'outbound' ? invalidId : 'valid_outbound')
        def incoming = new NamedInbound(direction == 'inbound' ? invalidId : 'valid_inbound')

        when:
        new ModelCallConversions([outgoing], [incoming])

        then:
        def failure = thrown(IllegalStateException)
        failure.message == "An ${direction} model call converter has no id"
        outgoing.converted == 0
        incoming.converted == 0

        where:
        direction  | invalidId | description
        'outbound' | null      | 'null'
        'outbound' | ''        | 'empty'
        'outbound' | ' \t '    | 'blank'
        'inbound'  | null      | 'null'
        'inbound'  | ''        | 'empty'
        'inbound'  | ' \t '    | 'blank'
    }

    def "registration rejects distinct #direction instances with the same id without converting"() {
        given:
        def firstOutgoing = new NamedOutbound('repeated')
        def secondOutgoing = new NamedOutbound('repeated')
        def firstIncoming = new NamedInbound('repeated')
        def secondIncoming = new NamedInbound('repeated')
        def outgoing = direction == 'outbound' ? [firstOutgoing, secondOutgoing] : [firstOutgoing]
        def incoming = direction == 'inbound' ? [firstIncoming, secondIncoming] : [firstIncoming]

        when:
        new ModelCallConversions(outgoing, incoming)

        then:
        def failure = thrown(IllegalStateException)
        failure.message == "More than one ${direction} model call converter is named repeated"
        [firstOutgoing, secondOutgoing, firstIncoming, secondIncoming].every { it.converted == 0 }

        where:
        direction << ['outbound', 'inbound']
    }

    def "distinct registered ids preserve each instance and allow the same id in opposite directions"() {
        given:
        def search = new DevelopmentSearchConversion()
        def ordinary = new OpenAiCompatibleCallConversion()
        def outgoing = new NamedOutbound(search.id(), search)
        def otherOutgoing = new NamedOutbound(ordinary.id(), ordinary)
        def incoming = new NamedInbound(search.id(), search)
        def otherIncoming = new NamedInbound(ordinary.id(), ordinary)

        when:
        def conversions = new ModelCallConversions([outgoing, otherOutgoing], [incoming, otherIncoming])
        conversions.requireKind('development_search', 'development_search', ModelToolKind.WEB_SEARCH,
                'sample_model', 'web_search')

        then:
        conversions.outbound('development_search').is(outgoing)
        conversions.outbound('openai_compatible').is(otherOutgoing)
        conversions.inbound('development_search').is(incoming)
        conversions.inbound('openai_compatible').is(otherIncoming)
        [outgoing, otherOutgoing, incoming, otherIncoming].every { it.converted == 0 }
    }

    private static final class NamedOutbound implements ModelCallOutbound {
        final String name
        final ModelCallOutbound delegate
        int converted

        NamedOutbound(String name, ModelCallOutbound delegate = null) {
            this.name = name
            this.delegate = delegate
        }

        @Override
        String id() { name }

        @Override
        Set<ModelToolKind> supportedToolKinds() {
            delegate == null ? ([ModelToolKind.WEB_SEARCH] as Set) : delegate.supportedToolKinds()
        }

        @Override
        byte[] convert(CallRequest request, List<ToolUseLimit> tools, String vendorModelId, String mode) {
            converted++
            if (delegate != null) return delegate.convert(request, tools, vendorModelId, mode)
            throw new AssertionError('Registration must not convert an outbound request')
        }
    }

    private static final class NamedInbound implements ModelCallInbound {
        final String name
        final ModelCallInbound delegate
        int converted

        NamedInbound(String name, ModelCallInbound delegate = null) {
            this.name = name
            this.delegate = delegate
        }

        @Override
        String id() { name }

        @Override
        Set<ModelToolKind> supportedToolKinds() {
            delegate == null ? ([ModelToolKind.WEB_SEARCH] as Set) : delegate.supportedToolKinds()
        }

        @Override
        ConvertedCallAnswer convert(JsonNode response) {
            converted++
            if (delegate != null) return delegate.convert(response)
            throw new AssertionError('Registration must not convert an inbound response')
        }
    }
}
