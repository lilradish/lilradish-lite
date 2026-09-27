package org.lilradish.lite.app.groupregister

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.sql.Connection
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import org.libprunus.core.error.ApiErrorException
import org.lilradish.lite.app.change.ChangeTransactions
import org.lilradish.lite.app.estate.EstateRoleGrants
import org.lilradish.lite.app.pool.PoolChanges
import org.lilradish.lite.app.pool.PoolPeople
import org.lilradish.lite.app.pool.PoolStays
import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.identity.GroupId
import org.lilradish.lite.domain.identity.GroupKey
import org.lilradish.lite.domain.identity.GroupName
import org.lilradish.lite.domain.identity.SubjectId
import org.lilradish.lite.domain.identity.SystemPrincipal
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.testutil.Baseline
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * Creating and renaming a group as the store makes each, on a real server running the real baseline
 * and under real transactions: whether a change lands whole or not at all, and what two changes
 * arriving at once leave behind, exist nowhere but in a database.
 *
 * <p>Each feature has a database of its own, copied from one the baseline was applied to, because
 * what is under test commits. Where two changes race, the database defaults to repeatable read, so
 * only a change naming Read Committed reads what it waited on; which change waits first is arranged by
 * holding what both need until both are seen waiting for it.
 */
class GroupChangesIntegrationSpec extends Specification {

    static final String SEEDER = "00000000-0000-4000-8000-000000000000"

    static final String SYSTEM_ACTOR = SystemPrincipal.WORKFLOW_RUNNER.subject().value().toString()

    /** Seeded by the baseline: in the pool, holding the steward role, and the one making changes here. */
    static final String FIRST_STEWARD = "00000001-0000-4000-8000-000000000001"

    static final UserId STEWARD = new UserId("000001")

    static final String PERSON = "00000002-0000-4000-8000-000000000801"

    static final String OTHER = "00000002-0000-4000-8000-000000000802"

    static final String GROUP = "00000003-0000-4000-8000-000000000801"

    static final String OTHER_GROUP = "00000003-0000-4000-8000-000000000802"

    static final List<String> WRITTEN_TO = ["subjects", "pool_members", "estate_role_grants", "groups", "group_members"]

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    DataSource database

    JdbcClient session

    GroupChanges changes

    @AutoCleanup("shutdownNow")
    ExecutorService racing = Executors.newFixedThreadPool(2)

    def setupSpec() {
        administer("create database baseline_applied")
        Baseline.appliedTo(server, "baseline_applied")
    }

    def setup() {
        def name = "groups_" + (++databasesMade)
        administer("create database ${name} template baseline_applied")
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
        texts("""
                select subject_id || ' ' || role || ' ' || created_by || ' ' || coalesce(removed_at::text, '-')
                  from group_members where group_id = ?::uuid
                """, created.groupId().value().toString()) == ["${PERSON} owner ${FIRST_STEWARD} -" as String]

        and: "and nobody else put anywhere"
        count("select count(*) from group_members") == 1
        count("select count(*) from estate_role_grants") == 1
    }

    /** Held in capitals whatever case it was typed in, so a key typed otherwise meets the one held. */
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

    /** Asked about the key first, so a group whose key and name are both held is told of the key. */
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

    /** A name another group holds is refused whatever case either was typed in, as a key is. */
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

    /** Unequal only as written: a format character is kept, and a name holding one is another name. */
    def "creates a group whose name differs from another's by a format character, which is never folded away"() {
        given:
        existing(GROUP, "TRIAGE", "Triage")

        when:
        changes.create(new GroupName("Tri" + Character.toString(0x200C) + "age"), new GroupKey("SALARY"),
                subjectId(PERSON), STEWARD)

        then:
        count("select count(*) from groups") == 2
    }

    /**
     * An identifier nobody holds, somebody whose stay ended, somebody never brought in, an actor that
     * is no person, and the act of seeding: none is in the pool, and none may be told from another.
     */
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

    /**
     * Another creation holds the key and has not committed, so nothing either could read first would
     * decide between them. The index does: this one waits, and is refused if the other landed and
     * made if it did not.
     */
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

    /** The other creation holds the name in another case, not the key; once it lands, what clashed was the name. */
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

    /** The person's stay is what both wait on, and it ends before this creation reads it. */
    def "a creation waiting behind somebody leaving the pool reads that they left, and is refused"() {
        given:
        repeatableReadByDefault()
        def leaving = holding("select 1 from pool_members where subject_id = '${PERSON}' and removed_at is null for update")

        when:
        def creating = attempting {
            changes.create(new GroupName("Payroll"), new GroupKey("PAYROLL"), subjectId(PERSON), STEWARD)
        }
        untilWaiting(1)
        leaving.createStatement().withCloseable {
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

    /**
     * The creation holds the person's stay while it waits on a key another has not committed; the
     * removal waits on that stay, and once the creation lands it reads the membership and is refused.
     */
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

    /** Its own name in another case is no other group's, so a group may be renamed to it. */
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
        texts("select key || ' ' || name || ' ' || updated_by from groups where updated_at is not null") ==
                ["PAYROLL ${name} ${FIRST_STEWARD}" as String]

        and:
        digestOf("group_members") == members

        where:
        name << ["Salaries and wages", "payroll", "PAYROLL"]
    }

    /** The transaction begins before the group is first read, so a group committed in between is the one renamed. */
    def "a group is renamed when the change's transaction began, or when it was made if that was later"() {
        given:
        existing(GROUP, "PAYROLL", "Payroll")
        session.sql("update groups set created_at = now() + ?::interval where group_id = ?::uuid")
                .params(offset, GROUP).update()
        def others = (WRITTEN_TO - "groups").collectEntries { [it, digestOf(it)] }

        when:
        changes.rename(groupId(GROUP), new GroupName("Salaries and wages"), STEWARD)

        then:
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

    /** A rename waits on the group's lock, so the one before it can have landed after this one's transaction began. */
    def "a rename never dates the group earlier than the rename before it"() {
        given:
        existing(GROUP, "PAYROLL", "Payroll")
        session.sql("""
                update groups set created_at = now() - interval '1 hour', updated_at = now() + interval '1 hour',
                                  updated_by = ?::uuid
                 where group_id = ?::uuid
                """).params(FIRST_STEWARD, GROUP).update()
        def renamedBefore = texts("select updated_at::text from groups where group_id = ?::uuid", GROUP)

        when:
        changes.rename(groupId(GROUP), new GroupName("Salaries and wages"), STEWARD)

        then:
        texts("select name from groups where group_id = ?::uuid", GROUP) == ["Salaries and wages"]

        and: "dated when the rename before it was, not moved back to when this one's transaction began"
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

    /** Only the store can see a name another has not committed, and the rename waits on it and then decides. */
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
        texts("select name from groups where group_id = ?::uuid", GROUP) == [named]

        where:
        otherLands || refusal                      | named
        true       || RefusalCode.GROUP_NAME_TAKEN | "Payroll"
        false      || null                         | "Salaries"
    }

    /**
     * The caller holds the act when the change starts and loses it while the change waits on its
     * lock; what the gate asked no longer holds, and under this database's default only a read made
     * after the wait can say so.
     */
    def "a change whose caller stops holding its act while it waits on its lock is refused, changing nothing"() {
        given:
        pooled(OTHER, "000802")
        grant(OTHER, "steward")
        existing(GROUP, "PAYROLL", "Payroll")
        repeatableReadByDefault()
        def held = holding(change == "create"
                ? "select 1 from pool_members where subject_id = '${PERSON}' and removed_at is null for update"
                : "select 1 from groups where group_id = '${GROUP}' for update")

        when:
        def changing = attempting { attemptAs(change, new UserId("000802")) }
        untilWaiting(1)
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

    /** Every column of every group stored, when it was made read only as having been made by now. */
    private List<List<Object>> storedGroups() {
        session.sql("""
                select group_id::text, key, name, name_folded, created_at <= now(), created_by::text,
                       updated_at, updated_by::text, updated_by_kind::text
                  from groups order by key
                """).query { row, number -> (1..9).collect { row.getObject(it) } }.list()
    }

    /** A group as a creation by the first steward stores it, changed by nobody since. */
    private static List<Object> storedAs(GroupId group, String key, String name) {
        [group.value().toString(), key, name, name.toLowerCase(Locale.ROOT), true, FIRST_STEWARD, null, null, "person"]
    }

    /** What the change answered, or what it threw, taken on a thread of its own. */
    private Future<Object> attempting(Closure change) {
        racing.submit({
            try {
                change()
            } catch (Throwable thrown) {
                thrown
            }
        } as Callable<Object>)
    }

    /** A transaction of the test's own, left open holding whatever the statement took. */
    private Connection holding(String statement) {
        def connection = database.connection
        connection.autoCommit = false
        connection.createStatement().withCloseable { it.execute(statement) }
        connection
    }

    private void untilWaiting(int sessions) {
        def deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (count("""
                select count(*) from pg_stat_activity
                 where datname = current_database() and wait_event_type = 'Lock'
                """) < sessions) {
            assert System.nanoTime() < deadline: "fewer than ${sessions} sessions ever waited on a lock"
            Thread.sleep(10)
        }
    }

    private void repeatableReadByDefault() {
        session.sql("select current_database()").query(String).single().with { name ->
            session.sql("alter database \"${name}\" set default_transaction_isolation = 'repeatable read'").update()
        }
        assert text("show default_transaction_isolation") == "repeatable read"
    }

    private String contents() {
        WRITTEN_TO.collect { digestOf(it) }.join(" ")
    }

    private String digestOf(String table) {
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
        session.sql("""
                insert into subjects (subject_id, kind, user_id, display_name, created_by)
                values (?::uuid, 'person', ?, 'Somebody', ?::uuid)
                """).params(subject, user, SEEDER).update()
    }

    private void pooled(String subject, String user) {
        person(subject, user)
        session.sql("insert into pool_members (subject_id, created_by) values (?::uuid, ?::uuid)")
                .params(subject, FIRST_STEWARD).update()
    }

    private void endedStay(String subject) {
        session.sql("update pool_members set removed_at = now(), removed_by = ?::uuid where subject_id = ?::uuid")
                .params(FIRST_STEWARD, subject).update()
    }

    private void grant(String subject, String role) {
        session.sql("insert into estate_role_grants (subject_id, role, created_by) values (?::uuid, ?::estate_role, ?::uuid)")
                .params(subject, role, FIRST_STEWARD).update()
    }

    private void existing(String group, String key, String name) {
        session.sql("insert into groups (group_id, key, name, created_by) values (?::uuid, ?, ?, ?::uuid)")
                .params(group, key, name, FIRST_STEWARD).update()
    }

    private void member(String group, String subject, String role) {
        session.sql("""
                insert into group_members (group_id, subject_id, role, created_by)
                values (?::uuid, ?::uuid, ?::group_role, ?::uuid)
                """).params(group, subject, role, FIRST_STEWARD).update()
    }

    private void administer(String statement) {
        server.postgresDatabase.connection.withCloseable { connection ->
            connection.createStatement().withCloseable { it.execute(statement) }
        }
    }
}
