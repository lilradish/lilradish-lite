package com.lilradish.lite.app.inference.development

import com.lilradish.lite.app.inference.EndpointJson
import com.lilradish.lite.app.inference.SearchObservation
import com.lilradish.lite.app.inference.ToolUseLimit
import com.lilradish.lite.domain.declaration.AskedField
import com.lilradish.lite.domain.declaration.Asking
import com.lilradish.lite.domain.declaration.FieldKind
import com.lilradish.lite.domain.declaration.FieldName
import com.lilradish.lite.domain.declaration.Instruction
import com.lilradish.lite.domain.inference.CallRequest
import com.lilradish.lite.domain.inference.ModelCallPurpose
import com.lilradish.lite.domain.inference.Payload
import com.lilradish.lite.domain.model.DeployedModel
import com.lilradish.lite.domain.model.ModelName
import com.lilradish.lite.domain.model.ModelToolName
import com.lilradish.lite.domain.model.SentText
import com.lilradish.lite.domain.wire.CanonicalJson
import com.lilradish.lite.domain.wire.JsonValue.JsonString
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper

class DevelopmentSearchConversionSpec extends Specification {

    static final JsonMapper JSON = JsonMapper.builder().build()

    def "the stand-in returns two complete observations unless the actual tool permits only one"() {
        given:
        def request = JSON.writeValueAsBytes([question: '{"instruction":"Find invented parcel evidence.","takes":{"topic":"Imaginary parcel claim"}}',
                tools: [[name: 'web_search', maximum: maximum]]])
        def first = new SearchObservation(new ModelToolName('web_search'), 'Imaginary parcel claim',
                '## Invented source notes\nThe practice record concerns: Imaginary parcel claim\n[Imaginary source](https://example.com/practice)\nNo real policy or supplier is represented.')
        def second = new SearchObservation(new ModelToolName('web_search'), 'Imaginary parcel claim / evidence ledger',
                '## Invented evidence ledger\nMira Cloudmere recorded the missing box photograph for: Imaginary parcel claim\n[Imaginary ledger](https://example.org/evidence)\nThe fictional dispatch record remains open.')

        when:
        def response = EndpointJson.READER.readTree(DevelopmentSearchConversion.replyTo(request))
        def converted = new DevelopmentSearchConversion().convert(response)

        then:
        converted.observations() == (maximum == 1 ? [first] : [first, second])
        converted.reportedUses() == [(new ModelToolName('web_search')): maximum == 1 ? 1L : 2L]
        response.get('uses').longValue() == converted.observations().size()
        response.get('generated_tool_calls').stringValue() == (maximum == 1
                ? '[{"tool":"web_search","query":"Imaginary parcel claim"}]'
                : '[{"tool":"web_search","query":"Imaginary parcel claim"},{"tool":"web_search","query":"Imaginary parcel claim / evidence ledger"}]')

        where:
        maximum << [1, 2, 3]
    }

    def "the inbound stand-in refuses malformed observations or a usage count that does not describe them"() {
        given:
        def body = [answer: 'Invented answer', tool: 'web_search', uses: 2,
                generated_tool_calls: '[]', observations: [[query: 'First', found: 'First note'], [query: 'Second', found: 'Second note']]] + changed

        expect:
        new DevelopmentSearchConversion().convert(EndpointJson.READER.readTree(JSON.writeValueAsString(body))) == null

        where:
        changed << [[observations: null], [observations: 'notes'], [observations: [[query: 'First', found: 'First note'], [query: 2, found: 'Second note']]],
                [observations: [[query: 'First', found: 'First note'], [query: 'Second', found: null]]], [uses: 1], [uses: -1], [uses: 2.5]]
    }

    def "the actual search payload answers its invoice question rather than a refused parcel history"() {
        given:
        def field = new AskedField(new FieldName('body'), FieldKind.TEXT, 4000, null, null, [], true, false)
        def refused = new Payload.Refused([(field.name()): new JsonString('Damaged parcel evidence')],
                [(field.name()): 'The parcel damage explanation was refused.'])
        def question = 'Find imaginary invoice correction evidence'
        def payload = CanonicalJson.write(Payload.toProduce(new Asking(new Instruction(question), [], [field]), [:], refused))
        def model = new DeployedModel(new ModelName('imaginary_model'), [], 1000, BigDecimal.ONE, 1000, [], [])
        def call = new CallRequest(model, null, ModelCallPurpose.PRODUCE, SentText.measure('system', payload))
        def conversion = new DevelopmentSearchConversion()

        when:
        def sent = conversion.convert(call, [new ToolUseLimit(new ModelToolName('web_search'), 3)], 'imaginary_vendor_model', null)
        def response = EndpointJson.READER.readTree(DevelopmentSearchConversion.replyTo(sent))
        def answer = EndpointJson.READER.readTree(response.get('answer').stringValue())

        then:
        answer.get('title').stringValue() == 'Imaginary Mooncrate: billing review'
        answer.get('body').stringValue().contains('invoice and payment record')
        answer.get('body').stringValue().contains('Question received: ' + question)
        !answer.get('body').stringValue().contains('box and packing')
        !answer.get('body').stringValue().contains('Damaged parcel evidence')
        response.get('observations').get(0).get('query').stringValue() == question
        conversion.convert(response).observations()[0].searchedFor() == question
        EndpointJson.READER.readTree(sent).get('question').stringValue() == payload
    }

    def "the stand-in answers the requested theme with invented evidence and Markdown"() {
        given:
        def request = JSON.writeValueAsBytes([question: CanonicalJson.write(Payload.toProduce(new Asking(new Instruction(question), [], []), [:], null)),
                tools: [[name: 'web_search', maximum: 3]]])

        when:
        def response = EndpointJson.READER.readTree(DevelopmentSearchConversion.replyTo(request))
        def answer = EndpointJson.READER.readTree(response.get('answer').stringValue())

        then:
        answer.get('title').stringValue() == title
        answer.get('body').stringValue().contains(finding)
        answer.get('body').stringValue().contains('Question received: ' + question)
        answer.get('body').stringValue().contains('| Evidence | Invented allowance |')
        answer.get('body').stringValue().contains('```fictional-policy')
        answer.get('body').stringValue().contains('Evidence&#8203;status')
        answer.get('body').stringValue().contains(Character.toString(0x200B))
        answer.get('references').stringValue().contains('Invented sources only:')
        answer.get('other').stringValue() == ''
        !answer.get('body').stringValue().contains(otherFinding)
        response.get('observations').get(0).get('query').stringValue() == question
        response.get('uses').longValue() == 2L

        where:
        question || title | finding | otherFinding
        'Find damaged parcel evidence' || 'Imaginary Mooncrate: damaged parcel review' | 'box and packing' | 'invoice and payment record'
        'Find invoice correction evidence' || 'Imaginary Mooncrate: billing review' | 'invoice and payment record' | 'box and packing'
        'Find inventory planning evidence' || 'Imaginary Mooncrate: topic review' | 'source notes and unresolved questions' | 'box and packing'
    }

    def "the stand-in reads the actual typed inputs and never uses refused history as its query"() {
        given:
        def payload = '{"envelope_version":6,"instruction":"Find fictional evidence.","takes":{"topic":"Invented invoice correction","amount":12.50},"was_refused":{"body":{"value":"Parcel history","why":"Not this request."}}}'
        def request = JSON.writeValueAsBytes([question: payload, tools: [[name: 'web_search', maximum: 3]]])

        when:
        def response = EndpointJson.READER.readTree(DevelopmentSearchConversion.replyTo(request))

        then:
        response.get('observations').get(0).get('query').stringValue() == 'Invented invoice correction'
        response.get('answer').stringValue().contains('invoice and payment record')
        !response.get('answer').stringValue().contains('Parcel history')
        EndpointJson.READER.readTree(request).get('question').stringValue() == payload
    }

    def "every supplied text contributes to the actual query and body in order under a natural title"() {
        given:
        def texts = ['Invented invoice A: 12 credits.', '# Invented receipt B\nStill unpaid.', 'Invented note C: check the ledger.']
        def payload = JSON.writeValueAsString([envelope_version: 6, instruction: 'Read every text with the topic.',
                takes: [topic: 'Fictional billing audit', text: texts]])
        def request = JSON.writeValueAsBytes([question: payload, tools: [[name: 'web_search', maximum: 3]]])

        when:
        def reply = EndpointJson.READER.readTree(DevelopmentSearchConversion.replyTo(request))
        def answer = EndpointJson.READER.readTree(reply.get('answer').stringValue())

        then:
        reply.get('observations').get(0).get('query').stringValue() == 'Fictional billing audit\nFile 1: ' + texts[0] + '\nFile 2: ' + texts[1] + '\nFile 3: ' + texts[2]
        texts.every { answer.get('body').stringValue().contains(it) }
        answer.get('title').stringValue() == 'Imaginary Mooncrate: billing review'
        answer.get('body').stringValue().contains(reply.get('observations').get(0).get('query').stringValue())
        answer.get('body').stringValue().contains('| Evidence | Invented allowance | Review note |\n| --- | --- | --- |')
    }

    def "the development reply keeps a supplementary character whole at each text boundary"() {
        given:
        def payload = JSON.writeValueAsString([envelope_version: 6, instruction: 'Read the supplied text.',
                takes: [topic: topic, text: [text]]])
        def request = JSON.writeValueAsBytes([question: payload, tools: [[name: 'web_search', maximum: 3]]])

        when:
        def reply = EndpointJson.READER.readTree(DevelopmentSearchConversion.replyTo(request))
        def answer = EndpointJson.READER.readTree(reply.get('answer').stringValue())

        then:
        answer.get('title').stringValue() == 'Imaginary Mooncrate: topic review'
        reply.get('observations').get(0).get('query').stringValue() == topic + '\nFile 1: ' + excerpt
        answer.get('body').stringValue().contains(topic + '\nFile 1: ' + excerpt)
        !reply.get('observations').get(0).get('query').stringValue().contains('\uFFFD')
        !answer.get('body').stringValue().contains('\uFFFD')

        where:
        topic | text || excerpt
        'a'.repeat(47) + '📦x' | 'Evidence' || 'Evidence'
        'Practice' | 'a'.repeat(23) + '📦x' || 'a'.repeat(23) + '📦x'
        'Practice' | 'a'.repeat(119) + '📦x' || 'a'.repeat(119) + '📦'
    }
}
