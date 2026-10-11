package com.lilradish.lite.app.codestep

import static java.nio.charset.StandardCharsets.UTF_8

import com.lilradish.lite.domain.codestep.CodeStep
import com.lilradish.lite.domain.declaration.Demand
import com.lilradish.lite.domain.declaration.FieldName
import com.lilradish.lite.domain.declaration.FieldShape
import com.lilradish.lite.domain.declaration.FieldStanding
import com.lilradish.lite.domain.declaration.HowMany
import com.lilradish.lite.domain.filling.FillPath
import com.lilradish.lite.domain.wire.CanonicalJson
import com.lilradish.lite.testutil.inference.Json
import spock.lang.Specification

class ReadTextFileSpec extends Specification {

    def "declares bounded ordered text files with standing texts and permits another run"() {
        when:
        def declared = new ReadTextFile().declaration()

        then:
        declared.takes().fields()*.name()*.value() == ['file']
        declared.takes().fields()[0].shape() instanceof FieldShape.File
        declared.takes().fields()[0].shape().types()*.value() == ['.txt', '.md', '.csv', '.json']
        declared.takes().fields()[0].shape().maximumBytes() == 2097152L
        declared.takes().fields()[0].howMany() == new HowMany.Many(3)
        declared.takes().fields()[0].demand() == new Demand.Given(true)
        declared.gives().fields()*.name()*.value() == ['text']
        declared.gives().fields()[0].shape().longest() == 262144
        declared.gives().fields()[0].help().value() == 'The first 262,144 characters of each file, exactly as written. Anything after that is left out.'
        declared.gives().fields()[0].howMany() == new HowMany.Many(3)
        declared.gives().fields()[0].demand() == new Demand.Stands(true, FieldStanding.ALWAYS, null)
        declared.mayRunAgain()
    }

    def "reads #contents in order exactly, closing each stream before opening another"() {
        given:
        boolean open = false
        int closed = 0
        def handles = contents.withIndex().collectEntries { text, index ->
            [(path(index)): { ->
                assert !open
                open = true
                new ByteArrayInputStream(text.getBytes(UTF_8)) {
                    @Override
                    void close() { open = false; closed++; super.close() }
                }
            } as CodeStep.FileInput]
        }

        when:
        def output = new ReadTextFile().run(new CodeStep.Input(metadata(contents.collect { it.getBytes(UTF_8) }), handles))

        then:
        CanonicalJson.write(output.values()) == CanonicalJson.write(Json.of([text: contents]))
        output.files().isEmpty()
        !open
        closed == contents.size()

        where:
        contents << [['Invented claim.\r\nKeep the box.\n', 'Invented packing note.\nRecord the dispatch date.\n'],
                     ['# Invented\nÉvidence 📦', 'a,b\n1,2\n', '{"fictional":true}\n']]
    }

    def "one invalid UTF-8 file fails the whole step and names that file without replacing its bytes"() {
        given:
        def bytes = ['Valid invented note'.getBytes(UTF_8), [0xC3, 0x28] as byte[]]
        boolean invalidClosed = false
        def handles = [(path(0)): { -> new ByteArrayInputStream(bytes[0]) } as CodeStep.FileInput,
                       (path(1)): { -> new ByteArrayInputStream(bytes[1]) {
                           @Override
                           void close() { invalidClosed = true; super.close() }
                       } } as CodeStep.FileInput]

        when:
        new ReadTextFile().run(new CodeStep.Input(metadata(bytes), handles))

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "Demo Sample 1.txt isn't plain text, so it can't be read. Use .txt, .md, .csv or .json files saved as plain text (UTF-8)."
        refused.cause instanceof java.nio.charset.CharacterCodingException
        invalidClosed
    }

    def "keeps the first 262144 code points of each longer file without splitting a surrogate pair or changing order"() {
        given:
        def beginnings = ['a'.repeat(262143) + '📦', 'Short invented note.\r\n', '📦'.repeat(262144)]
        def contents = [beginnings[0] + 'left out', beginnings[1], beginnings[2] + 'also left out']
        def bytes = contents.collect { it.getBytes(UTF_8) }
        def handles = bytes.withIndex().collectEntries { value, index ->
            [(path(index)): { -> new ByteArrayInputStream(value) } as CodeStep.FileInput]
        }

        when:
        def output = new ReadTextFile().run(new CodeStep.Input(metadata(bytes), handles))

        then:
        CanonicalJson.write(output.values()) == CanonicalJson.write(Json.of([text: beginnings]))
        beginnings[0].codePointCount(0, beginnings[0].length()) == 262144
        beginnings[2].codePointCount(0, beginnings[2].length()) == 262144
    }

    def "invalid UTF-8 after the part that would be kept still refuses the whole file"() {
        given:
        def prefix = 'a'.repeat(262144).getBytes(UTF_8)
        def bytes = Arrays.copyOf(prefix, prefix.length + 2)
        bytes[prefix.length] = (byte) 0xC3
        bytes[prefix.length + 1] = (byte) 0x28
        boolean closed = false
        def handles = [(path(0)): { -> new ByteArrayInputStream(bytes) {
            @Override
            void close() { closed = true; super.close() }
        } } as CodeStep.FileInput]

        when:
        new ReadTextFile().run(new CodeStep.Input(metadata([bytes]), handles))

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "Demo Sample 0.txt isn't plain text, so it can't be read. Use .txt, .md, .csv or .json files saved as plain text (UTF-8)."
        refused.cause instanceof java.nio.charset.CharacterCodingException
        closed
    }

    private static FillPath path(int index) {
        new FillPath([new FillPath.Named(new FieldName('file')), new FillPath.Place(index)])
    }

    private static def metadata(List<byte[]> bytes) {
        Json.of([file: bytes.withIndex().collect { content, index ->
            [id: new UUID(0, index + 1).toString(), name: "Demo Sample ${index}.txt" as String, size: content.length]
        }])
    }
}
