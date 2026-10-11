package com.lilradish.lite.domain.search

import com.lilradish.lite.domain.declaration.Demand
import com.lilradish.lite.domain.declaration.Field
import com.lilradish.lite.domain.declaration.FieldHelp
import com.lilradish.lite.domain.declaration.FieldKind
import com.lilradish.lite.domain.declaration.FieldLabel
import com.lilradish.lite.domain.declaration.FieldName
import com.lilradish.lite.domain.declaration.FieldShape
import com.lilradish.lite.domain.declaration.FieldStanding
import com.lilradish.lite.domain.declaration.HowMany
import com.lilradish.lite.domain.declaration.TextLines
import com.lilradish.lite.domain.model.ModelToolName
import spock.lang.Specification

class QuestionSearchShapeSpec extends Specification {

    private static final SearchWay WAY = new SearchWay('web_search', new ModelToolName('search'), 120, 5000, 2000, 300)

    def 'answer shape matches in every order without treating labels, help, standing, or confidence as shape'() {
        given:
        def answers = [
                answer('title', 120, true, FieldStanding.ALWAYS, null),
                answer('body', 5000, true, FieldStanding.NEVER, null),
                answer('references', 2000, false, FieldStanding.ABOVE_CONFIDENCE, 73),
                answer('other', 300, false, null, null)]

        expect:
        answers.permutations().every { QuestionSearchShape.matches(WAY, it) }
    }

    def 'each declared output requires its own name, limit, text lines, multiplicity, and requiredness'() {
        given:
        def answers = matchingAnswers()
        def original = answers[index]
        def shape = original.shape()
        def name = original.name()
        def howMany = original.howMany()
        def mustBe = original.demand().mustBe()
        switch (changed) {
            case 'name':
                name = new FieldName('different')
                break
            case 'limit':
                shape = new FieldShape.Text(shape.longest() + 1)
                break
            case 'kind':
                shape = new FieldShape.Plain(FieldKind.NUMBER)
                break
            case 'lines':
                shape = new FieldShape.Text(shape.longest(), TextLines.ONE)
                break
            case 'many':
                howMany = new HowMany.Many(2)
                break
            case 'required':
                mustBe = !mustBe
                break
        }
        answers[index] = new Field(name, null, null, shape, howMany, new Demand.Stands(mustBe, FieldStanding.NEVER, null))

        expect:
        !QuestionSearchShape.matches(WAY, answers)

        where:
        index | changed
        0     | 'name'
        1     | 'name'
        2     | 'name'
        3     | 'name'
        0     | 'kind'
        1     | 'kind'
        2     | 'kind'
        3     | 'kind'
        0     | 'limit'
        1     | 'limit'
        2     | 'limit'
        3     | 'limit'
        0     | 'lines'
        1     | 'lines'
        2     | 'lines'
        3     | 'lines'
        0     | 'many'
        1     | 'many'
        2     | 'many'
        3     | 'many'
        0     | 'required'
        1     | 'required'
        2     | 'required'
        3     | 'required'
    }

    def 'missing and extra fields cannot match the exact four outputs'() {
        given:
        def answers = matchingAnswers()
        if (extra) {
            answers.add(answer('extra', 120, true, FieldStanding.NEVER, null))
        } else {
            answers.remove(index)
        }

        expect:
        !QuestionSearchShape.matches(WAY, answers)
        !QuestionSearchShape.matches(WAY, [])

        where:
        extra | index
        false | 0
        false | 1
        false | 2
        false | 3
        true  | 0
    }

    def 'duplicate names cannot stand in for a missing output even with four declarations'() {
        given:
        def answers = matchingAnswers()
        answers[replaced] = answers[kept]

        expect:
        !QuestionSearchShape.matches(WAY, answers)

        where:
        kept | replaced
        0    | 1
        1    | 2
        2    | 3
        3    | 0
    }

    private static List<Field> matchingAnswers() {
        [answer('title', 120, true, FieldStanding.NEVER, null),
         answer('body', 5000, true, FieldStanding.NEVER, null),
         answer('references', 2000, false, FieldStanding.NEVER, null),
         answer('other', 300, false, FieldStanding.NEVER, null)]
    }

    private static Field answer(String name, int longest, boolean mustBe, FieldStanding standing, Integer floor) {
        new Field(new FieldName(name), new FieldLabel('Readable ' + name), new FieldHelp('Help with ' + name),
                new FieldShape.Text(longest), new HowMany.One(), new Demand.Stands(mustBe, standing, floor))
    }
}
