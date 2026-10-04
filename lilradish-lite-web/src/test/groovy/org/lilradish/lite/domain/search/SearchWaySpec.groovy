package org.lilradish.lite.domain.search

import org.lilradish.lite.domain.declaration.Declaration
import org.lilradish.lite.domain.declaration.DeclarationSide
import org.lilradish.lite.domain.declaration.Demand
import org.lilradish.lite.domain.declaration.Demands
import org.lilradish.lite.domain.declaration.Field
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.declaration.FieldShape
import org.lilradish.lite.domain.declaration.HowMany
import org.lilradish.lite.domain.model.ModelToolName
import spock.lang.Specification

class SearchWaySpec extends Specification {

    /* Past what none written out runs to: a shorter text counts as long as none, and growing it grows nothing. */
    private static final int SHORT = 10

    def 'release way holds exactly four ordered text outputs held once, the title and body to be given and the rest not'() {
        given:
        def way = new SearchWay('web_search', new ModelToolName('search'), 120, 5000, 2000, 300)

        expect:
        way.name() == 'web_search'
        way.requiredTool() == new ModelToolName('search')
        way.outputs() == [
                new Field(SearchWay.TITLE, null, null, new FieldShape.Text(120), new HowMany.One(), new Demand.Given(true)),
                new Field(SearchWay.BODY, null, null, new FieldShape.Text(5000), new HowMany.One(), new Demand.Given(true)),
                new Field(SearchWay.REFERENCES, null, null, new FieldShape.Text(2000), new HowMany.One(),
                        new Demand.Given(false)),
                new Field(SearchWay.OTHER, null, null, new FieldShape.Text(300), new HowMany.One(), new Demand.Given(false))]
        SearchWay.TITLE.value() == 'title'
        SearchWay.BODY.value() == 'body'
        SearchWay.REFERENCES.value() == 'references'
        SearchWay.OTHER.value() == 'other'
    }

    def 'every field a way gives back is named once, in the order its outputs hold them'() {
        given:
        def way = new SearchWay('web_search', new ModelToolName('search'), 120, 5000, 2000, 300)

        expect:
        SearchWay.FIELDS == way.outputs()*.name()
    }

    def 'release way refuses a name outside the identifier rule'() {
        when:
        new SearchWay(name, new ModelToolName('search'), 1, 1, 1, 1)

        then:
        def refusal = thrown(IllegalArgumentException)
        refusal.message.contains('SearchWay name')

        where:
        name << ['', 'Search', 'search-way', 'a' * 64]
    }

    def 'release way refuses an absent required tool'() {
        when:
        new SearchWay('web_search', null, 1, 1, 1, 1)

        then:
        def refusal = thrown(NullPointerException)
        refusal.message.contains('web_search')
        refusal.message.contains('requiredTool')
    }

    def 'each output needs a positive bound without affecting the others'() {
        when:
        new SearchWay('web_search', new ModelToolName('search'), title, body, references, other)

        then:
        def refusal = thrown(IllegalArgumentException)
        refusal.message.contains(expectedField)
        refusal.message.contains('web_search')

        where:
        title | body | references | other || expectedField
        0     | 1    | 1          | 1     || 'title'
        1     | -1   | 1          | 1     || 'body'
        1     | 1    | 0          | 1     || 'references'
        1     | 1    | 1          | -1    || 'other'
    }

    def 'one output beyond the largest holdable text bound is refused by declaration rules'() {
        when:
        new SearchWay('web_search', new ModelToolName('search'), title, body, references, other)

        then:
        def refusal = thrown(IllegalArgumentException)
        refusal.message.contains('web_search')
        refusal.message.contains(expectedField)
        refusal.message.contains('LIMIT_PAST_LARGEST')

        where:
        title     | body      | references | other     || expectedField
        8_388_607 | 1         | 1          | 1         || 'title'
        1         | 8_388_607 | 1          | 1         || 'body'
        1         | 1         | 8_388_607  | 1         || 'references'
        1         | 1         | 1          | 8_388_607 || 'other'
    }

    def 'outputs written out together at their limits to exactly the longest one value may be are held'() {
        given:
        def limits = [SHORT] * 4
        limits[grown] += room()

        when:
        def way = new SearchWay('web_search', new ModelToolName('search'), *limits)

        then:
        writtenOut(way) == Declaration.MOST_SENT
        way.outputs()*.shape()*.longest() == limits

        where:
        grown << (0..3)
    }

    def 'outputs written out together at their limits past the longest one value may be are refused, naming the way'() {
        given:
        def limits = [SHORT] * 4
        limits[grown] += room() + 1

        when:
        new SearchWay('web_search', new ModelToolName('search'), *limits)

        then:
        def refusal = thrown(IllegalArgumentException)
        refusal.message.contains('web_search')
        refusal.message.contains('together')
        !refusal.message.contains('LIMIT_PAST_LARGEST')

        where:
        grown << (0..3)
    }

    def 'standard output demand is the same with a held way or without one'() {
        given:
        def way = new SearchWay('web_search', new ModelToolName('search'), 120, 5000, 2000, 300)

        expect:
        SearchWay.demand(field) == new Demand.Given(required)
        way.outputs().find { it.name() == field }.demand().is(SearchWay.demand(field))

        where:
        field                || required
        SearchWay.TITLE      || true
        SearchWay.BODY       || true
        SearchWay.REFERENCES || false
        SearchWay.OTHER      || false
    }

    def 'standard output demand refuses an absent field or one that no way declares'() {
        when:
        SearchWay.demand(field)

        then:
        def refused = thrown(exception)
        refused.message == message

        where:
        field                   || exception                | message
        null                    || NullPointerException     | 'SearchWay demand field must not be null'
        new FieldName('summary') || IllegalArgumentException | 'SearchWay declares no output summary'
    }

    private static int room() {
        Math.toIntExact(Declaration.MOST_SENT
                - writtenOut(new SearchWay('web_search', new ModelToolName('search'), SHORT, SHORT, SHORT, SHORT)))
    }

    private static long writtenOut(SearchWay way) {
        new Declaration(DeclarationSide.GIVES, Demands.ofWorkflow(DeclarationSide.GIVES), way.outputs())
                .longestWritten([:])
    }
}
