package com.lilradish.lite.app.groupregister

import com.lilradish.lite.app.change.ChangeTransactions
import com.lilradish.lite.app.estate.EstateRoleGrants
import com.lilradish.lite.app.pool.PoolChanges
import com.lilradish.lite.app.pool.PoolPeople
import com.lilradish.lite.app.pool.PoolStays
import com.lilradish.lite.domain.failure.RefusalCode
import com.lilradish.lite.domain.identity.GroupId
import com.lilradish.lite.domain.identity.GroupKey
import com.lilradish.lite.domain.identity.GroupName
import com.lilradish.lite.domain.identity.SubjectId
import com.lilradish.lite.domain.identity.SystemPrincipal
import com.lilradish.lite.domain.identity.UserId
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.Baseline
import java.sql.Connection
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import org.libprunus.core.error.ApiErrorException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/** Changes must use Read Committed so reads after a lock wait observe the preceding change. */
class GroupChangesIntegrationSpec extends Specification {

    static final String SEEDER = "00000000-0000-4000-8000-000000000000"

    static final String SYSTEM_ACTOR = SystemPrincipal.WORKFLOW_RUNNER.subject().value().toString()


    static final String FIRST_STEWARD = "00000001-0000-4000-8000-000000000001"

    static final UserId STEWARD = new UserId("000001")

    static final String PERSON = "00000002-0000-4000-8000-000000000801"

    static final String OTHER = "00000002-0000-4000-8000-000000000802"

    static final String GROUP = "00000003-0000-4000-8000-000000000801"

    static final String OTHER_GROUP = "00000003-0000-4000-8000-000000000802"

    static final List<String> WRITTEN_TO = ["subjects", "pool_members", "estate_role_grants", "groups", "group_members"]

    @Shared
    @AutoCleanup
    TestPostgres server = TestPostgres.worker()

    @Shared
    int databasesMade = 0

    DataSource database

    JdbcClient session

    GroupChanges changes

    @AutoCleanup("shutdownNow")
    ExecutorService racing = Executors.newFixedThreadPool(2)

    def setupSpec() {
        server.aliasWorker("baseline_applied")
        Baseline.appliedTo(server, "baseline_applied")
        server.rememberBaseline()
    }

    def setup() {
        def name = "groups_" + (++databasesMade)
        server.beginLibraryCase(name, specificationContext.currentFeature.testTags*.value.contains("postgres-schema-changing"))
        database = Baseline.appliedTo(server, name)
        session = JdbcClient.create(database)
        changes = new GroupChanges(session, transactions(), new GroupRegister(session), new PoolStays(session),
                new EstateRoleGrants(session))
        pooled(PERSON, "000801")
    }

    def "creates a group whose one member is the person named, holding the one role that may change its membership, as the caller's act"() {
        when:
        def created = changes.create(new GroupName("Payroll"), new GroupKey("PAYROLL"), subjectId(PERSON), STEWARD)

        then:
        created.key().value() == "PAYROLL"
        created.name().value() == "Payroll"
        created.canBeAdministered()
        created.memberCount() == 1L

        and: "the group made by the caller, under the key and the name given, and changed by nobody since"
        storedGroups() == [storedAs(created.groupId(), "PAYROLL", "Payroll")]

        and: "one membership, the owner's, given by the caller and open"
        // DB-SPECIFIC: PostgreSQL :: casts to text, uuid; || converts UUID and enum operands to text.
        texts("""
                select subject_id || ' ' || role || ' ' || created_by || ' ' || coalesce(removed_at::text, '-')
                  from group_members where group_id = ?::uuid
                """, created.groupId().value().toString()) == ["${PERSON} owner ${FIRST_STEWARD} -" as String]

        and: "and nobody else put anywhere"
        count("select count(*) from group_members") == 1
        count("select count(*) from estate_role_grants") == 1
    }


    def "creates a group under a key typed in any case, held in capitals"() {
        when:
        def created = changes.create(new GroupName("Payroll"), GroupKey.typed("pAyRoLl"), subjectId(PERSON), STEWARD)

        then:
        storedGroups() == [storedAs(created.groupId(), "PAYROLL", "Payroll")]
    }

    def "refuses a key another group holds, whatever case either was typed in, writing nothing"() {
        given:
        existing(GROUP, "PAYROLL", "Payroll")
        def before = contents()

        when:
        changes.create(new GroupName("Salaries"), GroupKey.typed(typed), subjectId(PERSON), STEWARD)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.GROUP_KEY_TAKEN
        refused.message == "Another group already holds that key."

        and:
        contents() == before

        where:
        typed << ["PAYROLL", "payroll", "PayRoll"]
    }


    def "refuses a name another group has, and a key and a name both held as the key, writing nothing"() {
        given:
        existing(GROUP, "PAYROLL", "Payroll")
        def before = contents()

        when:
        changes.create(new GroupName(name), new GroupKey(key), subjectId(PERSON), STEWARD)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == code
        refused.message == message

        and:
        contents() == before

        where:
        name      | key       || code                         | message
        "Payroll" | "SALARY"  || RefusalCode.GROUP_NAME_TAKEN | "Another group already has that name."
        "Payroll" | "PAYROLL" || RefusalCode.GROUP_KEY_TAKEN  | "Another group already holds that key."
    }


    def "refuses a name another group holds in another case, writing nothing"() {
        given:
        existing(GROUP, "TRIAGE", "Triage")
        def before = contents()

        when:
        changes.create(new GroupName(typed), new GroupKey("SALARY"), subjectId(PERSON), STEWARD)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.GROUP_NAME_TAKEN
        refused.message == "Another group already has that name."

        and:
        contents() == before

        where:
        typed << ["TRIAGE", "triage", "tRiAgE"]
    }


    def "creates a group whose name differs from another's by a format character, which is never folded away"() {
        given:
        existing(GROUP, "TRIAGE", "Triage")

        when:
        changes.create(new GroupName("Tri" + Character.toString(0x200C) + "age"), new GroupKey("SALARY"),
                subjectId(PERSON), STEWARD)

        then:
        count("select count(*) from groups") == 2
    }


    def "refuses to make a group's first member of anybody not in the pool, writing nothing"() {
        given:
        pooled(OTHER, "000802")
        endedStay(OTHER)
        person("00000002-0000-4000-8000-000000000803", "000803")
        def before = contents()

        when:
        changes.create(new GroupName("Payroll"), new GroupKey("PAYROLL"), subjectId(named), STEWARD)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.PERSON_NOT_IN_POOL
        refused.message == "That person is not in the pool."

        and:
        contents() == before

        where:
        named << ["00000009-0000-4000-8000-000000000009", OTHER, "00000002-0000-4000-8000-000000000803",
                  SYSTEM_ACTOR, SEEDER]
    }

    /** Uniqueness decides a competing insert only after the first transaction ends. */
    @spock.lang.Tag("postgres-schema-changing")
    def "a creation racing another for the same key is decided by the key's uniqueness"() {
        given:
        repeatableReadByDefault()
        def other = holding("insert into groups (group_id, key, name, created_by)" +
                " values ('${OTHER_GROUP}', 'PAYROLL', 'Salaries', '${FIRST_STEWARD}')")

        when:
        def creating = attempting {
            changes.create(new GroupName("Payroll"), GroupKey.typed("payroll"), subjectId(PERSON), STEWARD)
        }
        untilWaiting(1)
        otherLands ? other.commit() : other.rollback()
        other.close()
        def outcome = creating.get(10, TimeUnit.SECONDS)

        then:
        outcome.getClass() == (refusal == null ? GroupRegister.GroupRow : ApiErrorException)
        refusal == null || outcome.errorCode() == refusal

        and: "one group holding the key whichever way it went, and a member only where this one was made"
        count("select count(*) from groups where key = 'PAYROLL'") == 1
        count("select count(*) from group_members") == (refusal == null ? 1 : 0)

        where:
        otherLands || refusal
        true       || RefusalCode.GROUP_KEY_TAKEN
        false      || null
    }


    @spock.lang.Tag("postgres-schema-changing")
    def "a creation racing another for the same name in another case is refused for the name once the other lands"() {
        given:
        repeatableReadByDefault()
        def other = holding("insert into groups (group_id, key, name, created_by)" +
                " values ('${OTHER_GROUP}', 'SALARY', 'Payroll', '${FIRST_STEWARD}')")

        when:
        def creating = attempting {
            changes.create(new GroupName("PAYROLL"), new GroupKey("PAYROLL"), subjectId(PERSON), STEWARD)
        }
        untilWaiting(1)
        other.commit()
        other.close()
        def outcome = creating.get(10, TimeUnit.SECONDS)

        then:
        outcome instanceof ApiErrorException
        outcome.errorCode() == RefusalCode.GROUP_NAME_TAKEN

        and:
        texts("select key from groups") == ["SALARY"]
        count("select count(*) from group_members") == 0
    }


    @spock.lang.Tag("postgres-schema-changing")
    def "a creation waiting behind somebody leaving the pool reads that they left, and is refused"() {
        given:
        repeatableReadByDefault()
        // DB-SPECIFIC: PostgreSQL SELECT FOR UPDATE.
        def leaving = holding("select 1 from pool_members where subject_id = '${PERSON}' and removed_at is null for update")

        when:
        def creating = attempting {
            changes.create(new GroupName("Payroll"), new GroupKey("PAYROLL"), subjectId(PERSON), STEWARD)
        }
        untilWaiting(1)
        leaving.createStatement().withCloseable {
            // DB-SPECIFIC: PostgreSQL now().
            it.execute("update pool_members set removed_at = now(), removed_by = '${FIRST_STEWARD}'" +
                    " where subject_id = '${PERSON}' and removed_at is null")
        }
        leaving.commit()
        leaving.close()
        def outcome = creating.get(10, TimeUnit.SECONDS)

        then:
        outcome instanceof ApiErrorException
        outcome.errorCode() == RefusalCode.PERSON_NOT_IN_POOL

        and: "no group made, and so nobody outside the pool in one"
        count("select count(*) from groups") == 0
        count("select count(*) from group_members") == 0
    }

    /** Holding the person's pool stay makes removal wait until group creation commits. */
    @spock.lang.Tag("postgres-schema-changing")
    def "somebody taken out of the pool while a group is being made with them waits, and is refused for the group"() {
        given:
        repeatableReadByDefault()
        def pool = new PoolChanges(session, transactions(), new PoolPeople(session), new EstateRoleGrants(session))
        def other = holding("insert into groups (group_id, key, name, created_by)" +
                " values ('${OTHER_GROUP}', 'PAYROLL', 'Salaries', '${FIRST_STEWARD}')")

        when:
        def creating = attempting {
            changes.create(new GroupName("Payroll"), new GroupKey("PAYROLL"), subjectId(PERSON), STEWARD)
        }
        untilWaiting(1)
        def removing = attempting { pool.remove(subjectId(PERSON), STEWARD) }
        untilWaiting(2)
        other.rollback()
        other.close()
        def created = creating.get(10, TimeUnit.SECONDS)
        def removed = removing.get(10, TimeUnit.SECONDS)

        then:
        created instanceof GroupRegister.GroupRow
        removed instanceof ApiErrorException
        removed.errorCode() == RefusalCode.PERSON_IN_GROUPS

        and: "nobody outside the pool is in any group, which is what the lock is for"
        count("""
                select count(*) from group_members membership
                 where membership.removed_at is null
                   and not exists (select 1 from pool_members stay
                                    where stay.subject_id = membership.subject_id and stay.removed_at is null)
                """) == 0
    }


    def "renames a group as the caller's act, leaving its key and its members as they were"() {
        given:
        existing(GROUP, "PAYROLL", "Payroll")
        member(GROUP, PERSON, "owner")
        def members = digestOf("group_members")

        when:
        def renamed = changes.rename(groupId(GROUP), new GroupName(name), STEWARD)

        then:
        renamed.name().value() == name
        renamed.key().value() == "PAYROLL"
        renamed.canBeAdministered()
        renamed.memberCount() == 1L

        and: "changed by the caller, and the key as it was given"
        // DB-SPECIFIC: PostgreSQL || converts the UUID author to text.
        texts("select key || ' ' || name || ' ' || updated_by from groups where updated_at is not null") ==
                ["PAYROLL ${name} ${FIRST_STEWARD}" as String]

        and:
        digestOf("group_members") == members

        where:
        name << ["Salaries and wages", "payroll", "PAYROLL"]
    }


    def "a group is renamed when the change's transaction began, or when it was made if that was later"() {
        given:
        existing(GROUP, "PAYROLL", "Payroll")
        // DB-SPECIFIC: PostgreSQL :: casts to interval, uuid; now() and verbose signed hour input.
        session.sql("update groups set created_at = now() + ?::interval where group_id = ?::uuid")
                .params(offset, GROUP).update()
        def others = (WRITTEN_TO - "groups").collectEntries { [it, digestOf(it)] }

        when:
        changes.rename(groupId(GROUP), new GroupName("Salaries and wages"), STEWARD)

        then:
        // DB-SPECIFIC: PostgreSQL :: casts to uuid; || converts the UUID author to text.
        texts("""
                select name || ' ' || updated_by || ' ' || case when updated_at = created_at then 'when made'
                                                                when updated_at > created_at then 'after made' end
                  from groups where group_id = ?::uuid
                """, GROUP) == ["Salaries and wages ${FIRST_STEWARD} ${changed}" as String]

        and: "nothing but the group written"
        others.every { table, digest -> digestOf(table) == digest }

        where:
        offset    || changed
        "-1 hour" || "after made"
        "+1 hour" || "when made"
    }


    def "a rename never dates the group earlier than the rename before it"() {
        given:
        existing(GROUP, "PAYROLL", "Payroll")
        // DB-SPECIFIC: PostgreSQL :: casts to uuid, now() and verbose interval quantity/unit input.
        session.sql("""
                update groups set created_at = now() - interval '1 hour', updated_at = now() + interval '1 hour',
                                  updated_by = ?::uuid
                 where group_id = ?::uuid
                """).params(FIRST_STEWARD, GROUP).update()
        // DB-SPECIFIC: PostgreSQL :: casts to text, uuid.
        def renamedBefore = texts("select updated_at::text from groups where group_id = ?::uuid", GROUP)

        when:
        changes.rename(groupId(GROUP), new GroupName("Salaries and wages"), STEWARD)

        then:
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        texts("select name from groups where group_id = ?::uuid", GROUP) == ["Salaries and wages"]

        and: "dated when the rename before it was, not moved back to when this one's transaction began"
        // DB-SPECIFIC: PostgreSQL :: casts to text, uuid.
        texts("select updated_at::text from groups where group_id = ?::uuid", GROUP) == renamedBefore
    }

    def "renaming a group to the name it has records nothing and answers alike"() {
        given:
        existing(GROUP, "PAYROLL", "Payroll")
        def before = contents()

        when:
        def renamed = changes.rename(groupId(GROUP), new GroupName("Payroll"), STEWARD)

        then:
        renamed.name().value() == "Payroll"

        and:
        contents() == before
    }

    def "refuses a name another group has, whatever case either was typed in, changing nothing"() {
        given:
        existing(GROUP, "PAYROLL", "Payroll")
        existing(OTHER_GROUP, "TRIAGE", "Triage")
        def before = contents()

        when:
        changes.rename(groupId(GROUP), new GroupName(typed), STEWARD)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.GROUP_NAME_TAKEN
        refused.message == "Another group already has that name."

        and:
        contents() == before

        where:
        typed << ["Triage", "TRIAGE"]
    }

    def "renaming a group nobody holds is refused as no group, changing nothing"() {
        given:
        existing(GROUP, "PAYROLL", "Payroll")
        def before = contents()

        when:
        changes.rename(groupId(addressed), new GroupName("Salaries"), STEWARD)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.GROUP_NOT_IN_VIEW
        refused.message == "That group is not in the register."

        and:
        contents() == before

        where:
        addressed << ["00000009-0000-4000-8000-000000000009", PERSON, FIRST_STEWARD]
    }


    @spock.lang.Tag("postgres-schema-changing")
    def "a rename racing a creation for the same name is decided by the name's uniqueness"() {
        given:
        existing(GROUP, "PAYROLL", "Payroll")
        repeatableReadByDefault()
        def other = holding("insert into groups (group_id, key, name, created_by)" +
                " values ('${OTHER_GROUP}', 'SALARY', 'Salaries', '${FIRST_STEWARD}')")

        when:
        def renaming = attempting { changes.rename(groupId(GROUP), new GroupName("Salaries"), STEWARD) }
        untilWaiting(1)
        otherLands ? other.commit() : other.rollback()
        other.close()
        def outcome = renaming.get(10, TimeUnit.SECONDS)

        then:
        outcome.getClass() == (refusal == null ? GroupRegister.GroupRow : ApiErrorException)
        refusal == null || outcome.errorCode() == refusal

        and:
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        texts("select name from groups where group_id = ?::uuid", GROUP) == [named]

        where:
        otherLands || refusal                      | named
        true       || RefusalCode.GROUP_NAME_TAKEN | "Payroll"
        false      || null                         | "Salaries"
    }

    /** Authorization must be read again after waiting for the group lock. */
    @spock.lang.Tag("postgres-schema-changing")
    def "a change whose caller stops holding its act while it waits on its lock is refused, changing nothing"() {
        given:
        pooled(OTHER, "000802")
        grant(OTHER, "steward")
        existing(GROUP, "PAYROLL", "Payroll")
        repeatableReadByDefault()
        def held = holding(change == "create"
                // DB-SPECIFIC: PostgreSQL SELECT FOR UPDATE.
                ? "select 1 from pool_members where subject_id = '${PERSON}' and removed_at is null for update"
                // DB-SPECIFIC: PostgreSQL SELECT FOR UPDATE.
                : "select 1 from groups where group_id = '${GROUP}' for update")

        when:
        def changing = attempting { attemptAs(change, new UserId("000802")) }
        untilWaiting(1)
        // DB-SPECIFIC: PostgreSQL :: casts to uuid; now().
        session.sql("update estate_role_grants set removed_at = now(), removed_by = ?::uuid where subject_id = ?::uuid")
                .params(FIRST_STEWARD, OTHER).update()
        def before = contents()
        held.commit()
        held.close()
        def outcome = changing.get(10, TimeUnit.SECONDS)

        then:
        outcome instanceof ApiErrorException
        outcome.errorCode() == RefusalCode.ACT_NOT_PERMITTED

        and:
        contents() == before

        where:
        change << ["create", "rename"]
    }

    /** Asked before anything else, so a caller without the act learns nothing of who or what is there. */
    def "a caller not holding the act is refused for that, whatever the change names"() {
        given:
        pooled(OTHER, "000802")
        existing(GROUP, "PAYROLL", "Payroll")
        def before = contents()

        when:
        change == "create"
                ? changes.create(new GroupName("Salaries"), new GroupKey("SALARY"), subjectId(named), new UserId("000802"))
                : changes.rename(groupId(named), new GroupName("Salaries"), new UserId("000802"))

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.ACT_NOT_PERMITTED

        and:
        contents() == before

        where:
        change   | named
        "create" | PERSON
        "create" | "00000009-0000-4000-8000-000000000009"
        "rename" | GROUP
        "rename" | "00000009-0000-4000-8000-000000000009"
    }

    private Object attemptAs(String change, UserId caller) {
        change == "create"
                ? changes.create(new GroupName("Salaries"), new GroupKey("SALARY"), subjectId(PERSON), caller)
                : changes.rename(groupId(GROUP), new GroupName("Salaries"), caller)
    }

    private def transactions() {
        new ChangeTransactions().readCommitted(new DataSourceTransactionManager(database))
    }


    private List<List<Object>> storedGroups() {
        // DB-SPECIFIC: PostgreSQL :: casts to text; now().
        session.sql("""
                select group_id::text, key, name, name_folded, created_at <= now(), created_by::text,
                       updated_at, updated_by::text, updated_by_kind::text
                  from groups order by key
                """).query { row, number -> (1..9).collect { row.getObject(it) } }.list()
    }


    private static List<Object> storedAs(GroupId group, String key, String name) {
        [group.value().toString(), key, name, name.toLowerCase(Locale.ROOT), true, FIRST_STEWARD, null, null, "person"]
    }


    private Future<Object> attempting(Closure change) {
        racing.submit({
            try {
                change()
            } catch (Throwable thrown) {
                thrown
            }
        } as Callable<Object>)
    }


    private Connection holding(String statement) {
        def connection = database.connection
        connection.autoCommit = false
        connection.createStatement().withCloseable { it.execute(statement) }
        connection
    }

    private void untilWaiting(int sessions) {
        def deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        // DB-SPECIFIC: PostgreSQL pg_stat_activity; current_database().
        while (count("""
                select count(*) from pg_stat_activity
                 where datname = current_database() and wait_event_type = 'Lock'
                """) < sessions) {
            assert System.nanoTime() < deadline: "fewer than ${sessions} sessions ever waited on a lock"
            Thread.sleep(10)
        }
    }

    private void repeatableReadByDefault() {
        // DB-SPECIFIC: PostgreSQL current_database().
        session.sql("select current_database()").query(String).single().with { name ->
            // DB-SPECIFIC: PostgreSQL ALTER DATABASE settings.
            session.sql("alter database \"${name}\" set default_transaction_isolation = 'repeatable read'").update()
        }
        // DB-SPECIFIC: PostgreSQL SHOW default_transaction_isolation.
        assert text("show default_transaction_isolation") == "repeatable read"
    }

    private String contents() {
        WRITTEN_TO.collect { digestOf(it) }.join(" ")
    }

    private String digestOf(String table) {
        // DB-SPECIFIC: PostgreSQL :: casts to text; md5(), string_agg().
        text("select coalesce(md5(string_agg(t::text, '|' order by t::text)), 'empty') from ${table} t")
    }

    private String text(String query) {
        session.sql(query).query(String).single()
    }

    private List<String> texts(String query, String parameter = null) {
        (parameter == null ? session.sql(query) : session.sql(query).param(parameter)).query(String).list()
    }

    private long count(String query) {
        session.sql(query).query(Long).single()
    }

    private static SubjectId subjectId(String spelled) {
        new SubjectId(UUID.fromString(spelled))
    }

    private static GroupId groupId(String spelled) {
        new GroupId(UUID.fromString(spelled))
    }

    private void person(String subject, String user) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        session.sql("""
                insert into subjects (subject_id, kind, user_id, display_name, created_by)
                values (?::uuid, 'person', ?, 'Somebody', ?::uuid)
                """).params(subject, user, SEEDER).update()
    }

    private void pooled(String subject, String user) {
        person(subject, user)
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        session.sql("insert into pool_members (subject_id, created_by) values (?::uuid, ?::uuid)")
                .params(subject, FIRST_STEWARD).update()
    }

    private void endedStay(String subject) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid; now().
        session.sql("update pool_members set removed_at = now(), removed_by = ?::uuid where subject_id = ?::uuid")
                .params(FIRST_STEWARD, subject).update()
    }

    private void grant(String subject, String role) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid, estate_role.
        session.sql("insert into estate_role_grants (subject_id, role, created_by) values (?::uuid, ?::estate_role, ?::uuid)")
                .params(subject, role, FIRST_STEWARD).update()
    }

    private void existing(String group, String key, String name) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        session.sql("insert into groups (group_id, key, name, created_by) values (?::uuid, ?, ?, ?::uuid)")
                .params(group, key, name, FIRST_STEWARD).update()
    }

    private void member(String group, String subject, String role) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid, group_role.
        session.sql("""
                insert into group_members (group_id, subject_id, role, created_by)
                values (?::uuid, ?::uuid, ?::group_role, ?::uuid)
                """).params(group, subject, role, FIRST_STEWARD).update()
    }

}
