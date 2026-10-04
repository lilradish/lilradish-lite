package org.lilradish.lite.app.files

import org.libprunus.core.error.ApiErrorException
import org.lilradish.lite.app.filling.ValueProblemsRefusal
import org.lilradish.lite.app.library.ConstantJson
import org.lilradish.lite.domain.declaration.FieldKind
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.file.MediaTypeName
import org.lilradish.lite.domain.filling.FillField
import spock.lang.Specification

class FileFillingsSpec extends Specification {

    private static List<FillField> fields() {
        [new FillField(new FieldName('photo'), null, null, FieldKind.FILE, null, null, true,
                null, [], [new MediaTypeName('image/png')], 100L),
         new FillField(new FieldName('title'), null, null, FieldKind.TEXT, 2, null, true, null, [])]
    }

    def "a valid ordinary value and upload marker pass before file streams"() {
        given:
        def values = ConstantJson.sent('{"photo":{"upload":0,"name":"actual.png"},"title":"ok"}')

        when:
        FileFillings.beforeFileStreams(fields(), values)

        then:
        noExceptionThrown()
    }

    def "an ordinary value known to be invalid is refused before file streams: #caseName"() {
        given:
        def values = ConstantJson.sent('{"photo":{"upload":0,"name":"actual.png"},"title":' + titleValue + '}')

        when:
        FileFillings.beforeFileStreams(fields(), values)

        then:
        def error = thrown(ValueProblemsRefusal)
        error.errorCode() == RefusalCode.VALUE_DOES_NOT_FIT

        where:
        caseName        | titleValue
        'required null' | 'null'
        'too long'      | '"long"'
    }

    def "a required file left empty keeps the field refusal"() {
        given:
        def values = ConstantJson.sent('{"photo":null,"title":"ok"}')

        when:
        FileFillings.beforeFileStreams(fields(), values)

        then:
        def error = thrown(ValueProblemsRefusal)
        error.errorCode() == RefusalCode.VALUE_DOES_NOT_FIT
    }

    def "an untrusted file object is refused as an unusable filling"() {
        given:
        def values = ConstantJson.sent('{"photo":{"id":"00000000-0000-4000-8000-000000000001",' +
                '"name":"actual.png","type":"image/png","size":1},"title":"ok"}')

        when:
        FileFillings.beforeFileStreams(fields(), values)

        then:
        def error = thrown(ApiErrorException)
        error.errorCode() == RefusalCode.BODY_UNUSABLE
    }
}
