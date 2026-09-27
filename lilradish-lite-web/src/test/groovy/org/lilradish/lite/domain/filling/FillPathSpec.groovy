package org.lilradish.lite.domain.filling

import org.lilradish.lite.domain.declaration.FieldName
import spock.lang.Specification

class FillPathSpec extends Specification {

    static final FillPath.Named CONTACT = new FillPath.Named(new FieldName("contact"))

    static final FillPath.Named EMAIL = new FillPath.Named(new FieldName("email"))

    def "a place is a field, then any fields and elements below it, held apart from the list it was built from"() {
        given:
        def built = [CONTACT, new FillPath.Place(0), EMAIL]

        when:
        def path = new FillPath(built)
        built.clear()

        then:
        path.steps() == [CONTACT, new FillPath.Place(0), EMAIL]
    }

    def "a path starting anywhere but at a field is refused, since no value is judged but a field's"() {
        when:
        new FillPath(steps)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "FillPath starts at a field"

        where:
        steps << [[], [new FillPath.Place(0)], [new FillPath.Place(2), EMAIL]]
    }

    def "an element is placed from nought, and a place before the first is refused"() {
        when:
        new FillPath.Place(index)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "FillPath.Place index must not be negative: " + index

        where:
        index << [-1, Integer.MIN_VALUE]
    }
}
