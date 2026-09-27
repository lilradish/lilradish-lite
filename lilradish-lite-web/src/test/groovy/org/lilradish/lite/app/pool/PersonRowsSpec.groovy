package org.lilradish.lite.app.pool

import java.sql.Array
import java.sql.ResultSet
import org.lilradish.lite.domain.identity.EstateRole
import org.lilradish.lite.domain.identity.GroupRole
import org.lilradish.lite.domain.identity.SubjectId
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.domain.people.PersonName
import spock.lang.Specification

/**
 * A row of somebody in the pool read column by column. The row is stood in for, being what a driver
 * hands back; what a real store puts in one is asked of the readings that use this.
 */
class PersonRowsSpec extends Specification {

    static final SubjectId SUBJECT = new SubjectId(UUID.fromString("00000002-0000-4000-8000-000000000f91"))

    private ResultSet rowHolding(Map<String, Object> columns) {
        Stub(ResultSet) {
            getObject("subject_id", UUID) >> columns.subject_id
            getString(_ as String) >> { String column -> columns[column] }
        }
    }

    private Array labels(String... held) {
        Stub(Array) { getArray() >> (held as String[]) }
    }

    def "a row is somebody by the subject it holds"() {
        expect:
        PersonRows.subjectOf(rowHolding(subject_id: SUBJECT.value())) == SUBJECT
    }

    def "a row's user number is read as the user it names"() {
        expect:
        PersonRows.userIdOf(rowHolding(user_id: "000f91"), SUBJECT) == new UserId("000f91")
    }

    /** Shown or left out, a list would not be the list; failed, it names whose row it was. */
    def "a user number this system will not show fails the read, naming whose row it was"() {
        when:
        PersonRows.userIdOf(rowHolding(user_id: " "), SUBJECT)

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "Subject ${SUBJECT.value()} holds a user id this system will not show" as String
        failed.cause instanceof IllegalArgumentException
    }

    def "a name is read where one is held, and nothing stands in where none is"() {
        expect:
        PersonRows.nameOf(rowHolding(display_name: held), SUBJECT) == name

        where:
        held           || name
        "Ada Lovelace" || new PersonName("Ada Lovelace")
        null           || null
    }

    def "a name this system will not show fails the read, naming whose row it was"() {
        when:
        PersonRows.nameOf(rowHolding(display_name: " Ada"), SUBJECT)

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "Subject ${SUBJECT.value()} holds a name this system will not show" as String
    }

    def "a row is read whole as somebody: their subject, their user number, and their name where one is held"() {
        expect:
        PersonRows.person(rowHolding(subject_id: SUBJECT.value(), user_id: "000f91", display_name: held)) ==
                new PersonRows.Person(SUBJECT, new UserId("000f91"), name)

        where:
        held           || name
        "Ada Lovelace" || new PersonName("Ada Lovelace")
        null           || null
    }

    def "stored labels are read as the constants of the type they label, each once"() {
        expect:
        PersonRows.labelled(type, labels(*stored)) == held

        where:
        type       | stored                         || held
        GroupRole  | ["owner", "operator"]          || EnumSet.of(GroupRole.OPERATOR, GroupRole.OWNER)
        EstateRole | ["watcher"]                    || EnumSet.of(EstateRole.WATCHER)
        GroupRole  | []                             || EnumSet.noneOf(GroupRole)
    }

    def "a label no constant carries fails the read rather than being dropped"() {
        when:
        PersonRows.labelled(GroupRole, labels("captain"))

        then:
        thrown(IllegalArgumentException)
    }

    def "labels read cannot be widened through the set they came back in"() {
        when:
        PersonRows.labelled(GroupRole, labels("owner")).add(GroupRole.OPERATOR)

        then:
        thrown(UnsupportedOperationException)
    }
}
