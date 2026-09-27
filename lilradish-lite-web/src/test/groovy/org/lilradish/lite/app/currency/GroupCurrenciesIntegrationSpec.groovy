package org.lilradish.lite.app.currency

import static org.lilradish.lite.testutil.library.LibraryStore.FIRST_STEWARD
import static org.lilradish.lite.testutil.library.LibraryStore.attempting
import static org.lilradish.lite.testutil.library.LibraryStore.groupId

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.libprunus.core.error.ApiErrorException
import org.lilradish.lite.app.estate.EstateRoleGrants
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.groupregister.GroupChanges
import org.lilradish.lite.app.groupregister.GroupRegister
import org.lilradish.lite.app.pool.PoolStays
import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.identity.GroupName
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.domain.model.DeployedModel
import org.lilradish.lite.domain.model.ModelCatalog
import org.lilradish.lite.domain.model.ModelName
import org.lilradish.lite.domain.model.ModelPrice
import org.lilradish.lite.testutil.library.LibraryStore
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * A group's currency as the store reads and chooses it, on a real server running the real baseline and
 * under real transactions: what a choice records and against whom, what it leaves alone, and what a choice
 * racing another change to the group leaves behind.
 *
 * <p>Each feature has a database of its own, copied from one the baseline was applied to, because what
 * is under test commits. Where a choice races, the database defaults to repeatable read, so only a change
 * naming Read Committed reads what it waited on; the choice is made to wait by holding what it needs until
 * it is seen waiting for it.
 */
class GroupCurrenciesIntegrationSpec extends Specification {

    static final String PAYROLL = "00000003-0000-4000-8000-000000000c01"

    static final String TRIAGE = "00000003-0000-4000-8000-000000000c02"

    static final String NO_GROUP = "00000003-0000-4000-8000-000000000c09"

    /** An owner of the payroll group, who may change its membership. */
    static final String ADA = "00000002-0000-4000-8000-000000000c01"

    static final UserId ADA_USER = new UserId("000c01")

    /** An operator and an overseer of the payroll group, who may see its members and not change them. */
    static final String GRACE = "00000002-0000-4000-8000-000000000c02"

    static final UserId GRACE_USER = new UserId("000c02")

    /** An owner of the triage group and in no role of the payroll group. */
    static final String OLIVE = "00000002-0000-4000-8000-000000000c03"

    static final UserId OLIVE_USER = new UserId("000c03")

    /** A second owner of the payroll group. */
    static final String LINUS = "00000002-0000-4000-8000-000000000c04"

    static final UserId LINUS_USER = new UserId("000c04")

    /** The first steward, who keeps the group register and so may rename a group. */
    static final UserId STEWARD_USER = new UserId("000001")

    static final String NOT_IN_VIEW = "That group is not in view."

    static final String NOT_PERMITTED = "This caller may not do that."

    static final String NOT_PRICED = "No model is priced in that currency."

    /** Listed out of order and priced twice over, so the offer is ordered and once each by the catalogue alone. */
    static final ModelCatalog CATALOG = new ModelCatalog([
            new DeployedModel(new ModelName("sample_model"), [], 200000, new BigDecimal("4"), 8192,
                    [priced("USD"), priced("EUR")]),
            new DeployedModel(new ModelName("other_model"), [], 200000, new BigDecimal("4"), 8192,
                    [priced("EUR")]),
    ])

    /** One model, priced in nothing. */
    static final ModelCatalog UNPRICED = new ModelCatalog([
            new DeployedModel(new ModelName("sample_model"), [], 200000, new BigDecimal("4"), 8192, []),
    ])

    static final List<Currency> PRICED = [currency("EUR"), currency("USD")]

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    GroupCurrencies currencies

    @AutoCleanup("shutdownNow")
    ExecutorService racing = Executors.newFixedThreadPool(2)

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        store = LibraryStore.copied(server, "currencies_" + (++databasesMade))
        currencies = over(CATALOG)
        pooled(ADA, "000c01", "Ada Lovelace")
        pooled(GRACE, "000c02", "Grace Hopper")
        pooled(OLIVE, "000c03", "Olive Out")
        pooled(LINUS, "000c04", "Linus Left")
        store.group(PAYROLL, "PAYROLL", "Payroll")
        store.group(TRIAGE, "TRIAGE", "Triage")
        store.member(PAYROLL, ADA, "owner")
        store.member(PAYROLL, GRACE, "operator")
        store.member(PAYROLL, GRACE, "overseer")
        store.member(PAYROLL, LINUS, "owner")
        store.member(TRIAGE, OLIVE, "owner")
    }

    /** What it may be changed to is the catalogue's, ordered by code, and only for whoever may change it. */
    def "reads the chosen currency, offering the priced ones only to a member who may change the membership"() {
        given:
        if (chosen != null) {
            chose(PAYROLL, chosen, FIRST_STEWARD)
        }
        def before = store.contents()

        when:
        def read = currencies.read(groupId(PAYROLL), caller)

        then:
        read.chosen() == expected
        read.offered() == offered

        and: "reading records nothing"
        store.contents() == before

        where:
        [chosen, caller] << [[null, "EUR"], [ADA_USER, GRACE_USER]].combinations()
        expected = chosen == null ? null : currency(chosen)
        offered = caller == ADA_USER ? PRICED : null
    }

    /** The choice is kept as it was made: nothing else stands in for it, and it is not offered back. */
    def "a chosen currency no model is priced in any longer is still read as chosen"() {
        given:
        chose(PAYROLL, "GBP", FIRST_STEWARD)

        when:
        def read = currencies.read(groupId(PAYROLL), ADA_USER)

        then:
        read.chosen() == currency("GBP")
        read.offered() == PRICED
    }

    def "reads a group's own choice and never another group's"() {
        given:
        chose(TRIAGE, "USD", OLIVE)

        when:
        def read = currencies.read(groupId(PAYROLL), ADA_USER)

        then:
        read.chosen() == null
        read.offered() == PRICED
    }

    def "reading a group the caller holds nothing in, or one there is none of, is refused as no group in view"() {
        given:
        chose(PAYROLL, "EUR", FIRST_STEWARD)
        def before = store.contents()

        when:
        currencies.read(groupId(group), OLIVE_USER)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.GROUP_NOT_IN_VIEW
        refused.message == NOT_IN_VIEW

        and:
        store.contents() == before

        where:
        group << [PAYROLL, NO_GROUP]
    }

    /** A read takes no lock, so a change under way neither holds it up nor shows through. */
    def "a read made while a change is under way answers what was committed, without waiting"() {
        given:
        chose(PAYROLL, "EUR", FIRST_STEWARD)
        def changing = store.holding(
                "select 1 from groups where group_id = '${PAYROLL}' for no key update" as String,
                "update group_currencies set currency = 'USD' where group_id = '${PAYROLL}'" as String)

        when:
        def read = attempting(racing) { currencies.read(groupId(PAYROLL), ADA_USER) }.get(10, TimeUnit.SECONDS)

        then:
        read instanceof GroupCurrencies.Choice
        read.chosen() == currency("EUR")

        cleanup:
        changing.rollback()
        changing.close()
    }

    /** Nothing priced is an empty offer to whoever may change it, and still no offer to anybody else. */
    def "with no model priced in anything, a changer is offered nothing and anybody else no offer, the choice kept"() {
        given:
        chose(PAYROLL, "EUR", FIRST_STEWARD)

        when:
        def read = over(UNPRICED).read(groupId(PAYROLL), caller)

        then:
        read.chosen() == currency("EUR")
        read.offered() == offered

        where:
        caller     || offered
        ADA_USER   || []
        GRACE_USER || null
    }

    /** A stored code no currency has is neither shown nor passed over, and the failure never quotes it. */
    def "a stored code no currency has fails the reading or the change, naming the group and not the code"() {
        given:
        chose(PAYROLL, "QQQ", FIRST_STEWARD)
        def before = store.contents()

        when:
        asking == "read"
                ? currencies.read(groupId(PAYROLL), ADA_USER)
                : currencies.choose(groupId(PAYROLL), "USD", ADA_USER)

        then:
        def failed = thrown(IllegalStateException)
        failed.message.contains(PAYROLL)
        !failed.message.contains("QQQ")
        failed.cause instanceof IllegalArgumentException

        and:
        store.contents() == before

        where:
        asking << ["read", "choose"]
    }

    def "the first choice records the currency as the caller's act, and no edit"() {
        when:
        def chosen = currencies.choose(groupId(PAYROLL), "EUR", ADA_USER)

        then:
        chosen.chosen() == currency("EUR")
        chosen.offered() == PRICED

        and: "one row, made by the caller and never edited"
        rowOf(PAYROLL) == "EUR ${ADA} person null null" as String

        and: "no other group given one"
        rowOf(TRIAGE) == null
    }

    /** Who first chose it stays recorded, and the one who changed it is recorded beside them. */
    def "changing the currency records the new one as the caller's edit, and leaves who first chose it"() {
        given:
        chose(PAYROLL, "EUR", FIRST_STEWARD)
        chose(TRIAGE, "EUR", OLIVE)
        def otherGroup = rowOf(TRIAGE)

        when:
        def chosen = currencies.choose(groupId(PAYROLL), "USD", ADA_USER)

        then:
        chosen.chosen() == currency("USD")
        chosen.offered() == PRICED

        and:
        rowOf(PAYROLL) == "USD ${FIRST_STEWARD} person ${ADA} person" as String

        and: "the other group's choice left as it was"
        rowOf(TRIAGE) == otherGroup
    }

    /** A choice waits on the group's lock, so the transaction changing a row can have begun before it was made. */
    def "a change to a choice made after the change's transaction began is dated no earlier than the choice"() {
        given:
        chose(PAYROLL, "EUR", FIRST_STEWARD)
        store.session.sql("update group_currencies set created_at = now() + interval '1 hour'").update()

        when:
        currencies.choose(groupId(PAYROLL), "USD", ADA_USER)

        then:
        store.count("select count(*) from group_currencies where currency = 'USD' and updated_at = created_at") == 1
    }

    /** Likewise a change before it can have landed after this one's transaction began. */
    def "a change never dates the choice earlier than the change before it"() {
        given:
        chose(PAYROLL, "EUR", FIRST_STEWARD)
        store.session.sql("""
                update group_currencies set created_at = now() - interval '1 hour',
                                            updated_at = now() + interval '1 hour', updated_by = ?::uuid
                """).param(FIRST_STEWARD).update()
        def changedBefore = store.texts("select updated_at::text from group_currencies")

        when:
        currencies.choose(groupId(PAYROLL), "USD", ADA_USER)

        then:
        rowOf(PAYROLL) == "USD ${FIRST_STEWARD} person ${ADA} person" as String

        and: "dated when the change before it was, not moved back to when this one's transaction began"
        store.texts("select updated_at::text from group_currencies") == changedBefore
    }

    /** Priced or no longer, the currency chosen is chosen already, and choosing it changes nothing. */
    def "choosing the currency already chosen records nothing and answers alike"() {
        given:
        chose(PAYROLL, earlier, FIRST_STEWARD)
        def before = store.contents()

        when:
        def chosen = currencies.choose(groupId(PAYROLL), earlier, ADA_USER)

        then:
        chosen.chosen() == currency(earlier)
        chosen.offered() == PRICED

        and:
        store.contents() == before

        where:
        earlier << ["EUR", "GBP"]
    }

    /** A code of any currency no model is priced in, however spelt, and whether or not one was chosen before. */
    def "refuses a change to a currency no model is priced in, writing nothing"() {
        given:
        if (earlier != null) {
            chose(PAYROLL, earlier, FIRST_STEWARD)
        }
        def before = store.contents()

        when:
        currencies.choose(groupId(PAYROLL), code, ADA_USER)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.CURRENCY_NOT_PRICED
        refused.message == NOT_PRICED

        and:
        store.contents() == before

        where:
        [code, earlier] << [["GBP", "eur", "XYZ", "GBP "], [null, "EUR", "GBP"]].combinations()
                .findAll { it != ["GBP", "GBP"] }
    }

    def "with no model priced in anything, every other currency is refused, writing nothing"() {
        given:
        if (earlier != null) {
            chose(PAYROLL, earlier, FIRST_STEWARD)
        }
        def before = store.contents()

        when:
        over(UNPRICED).choose(groupId(PAYROLL), code, ADA_USER)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.CURRENCY_NOT_PRICED
        refused.message == NOT_PRICED

        and:
        store.contents() == before

        where:
        [code, earlier] << [["EUR", "USD", "GBP"], [null, "JPY"]].combinations()
    }

    def "with no model priced in anything, choosing the currency already chosen records nothing"() {
        given:
        chose(PAYROLL, "EUR", FIRST_STEWARD)
        def before = store.contents()

        when:
        def chosen = over(UNPRICED).choose(groupId(PAYROLL), "EUR", ADA_USER)

        then:
        chosen.chosen() == currency("EUR")
        chosen.offered() == []

        and:
        store.contents() == before
    }

    /** Asked before the code is judged, so a caller who may not change it learns nothing of what is priced. */
    def "a caller who may not change the membership is refused for that, whatever the currency named"() {
        given:
        def before = store.contents()

        when:
        currencies.choose(groupId(PAYROLL), code, GRACE_USER)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.ACT_NOT_PERMITTED
        refused.message == NOT_PERMITTED

        and:
        store.contents() == before

        where:
        code << ["EUR", "GBP"]
    }

    def "a caller holding nothing in the group, or choosing for a group there is none of, is refused as no group in view"() {
        given:
        def before = store.contents()

        when:
        currencies.choose(groupId(group), "EUR", OLIVE_USER)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.GROUP_NOT_IN_VIEW
        refused.message == NOT_IN_VIEW

        and:
        store.contents() == before

        where:
        group << [PAYROLL, NO_GROUP]
    }

    /**
     * The caller holds the permission when the choice starts and loses it while the choice waits on the
     * group's lock; under this database's default only a read made after the wait can say so.
     */
    def "a choice whose caller stops being able to change the membership while it waits is refused, changing nothing"() {
        given:
        if (earlier != null) {
            chose(PAYROLL, earlier, FIRST_STEWARD)
        }
        store.repeatableReadByDefault()
        def before = store.contents("group_members")
        def taking = store.takingRoles(PAYROLL, ADA, *left)

        when:
        def choosing = attempting(racing) { currencies.choose(groupId(PAYROLL), "USD", ADA_USER) }
        store.untilWaiting(1)
        taking.commit()
        taking.close()
        def outcome = choosing.get(10, TimeUnit.SECONDS)

        then:
        outcome instanceof ApiErrorException
        outcome.errorCode() == refusal
        outcome.message == message

        and: "nothing written but the roles taken while it waited"
        store.contents("group_members") == before

        where:
        [earlier, left] << [[null, "EUR"], [["operator"], []]].combinations()
        refusal = left.isEmpty() ? RefusalCode.GROUP_NOT_IN_VIEW : RefusalCode.ACT_NOT_PERMITTED
        message = left.isEmpty() ? NOT_IN_VIEW : NOT_PERMITTED
    }

    /**
     * Both wait on the group's lock, and whichever goes second reads what the first did: a first choice then
     * finds one made, and changes it.
     */
    def "two choices at once both land, the one written second changing the one written first"() {
        given:
        if (earlier != null) {
            chose(PAYROLL, earlier, FIRST_STEWARD)
        }
        store.repeatableReadByDefault()
        def held = store.holding("select 1 from groups where group_id = '${PAYROLL}' for no key update" as String)

        when:
        def first = attempting(racing) { currencies.choose(groupId(PAYROLL), "EUR", ADA_USER) }
        def second = attempting(racing) { currencies.choose(groupId(PAYROLL), "USD", LINUS_USER) }
        store.untilWaiting(2)
        held.commit()
        held.close()
        def outcomes = [first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)]

        then: "each answered with its own choice"
        outcomes*.chosen() == [currency("EUR"), currency("USD")]

        and: "one row, holding what was written second, its author the one first and its editor the one second"
        def row = store.texts("select currency || ' ' || created_by || ' ' || updated_by from group_currencies")
        row.size() == 1
        def (written, author, editor) = row[0].split(" ")
        editor == (written == "EUR" ? ADA : LINUS)
        author == (earlier != null ? FIRST_STEWARD : written == "EUR" ? LINUS : ADA)

        where:
        earlier << [null, "GBP"]
    }

    /**
     * A rename locks the group's row for update. A first choice's row points at the group and waits on that
     * lock anyway; a change to one points at nothing new, and only the group's lock makes it wait.
     */
    def "a choice waits on a rename of the group under way, and both land"() {
        given:
        if (earlier != null) {
            chose(PAYROLL, earlier, FIRST_STEWARD)
        }
        store.repeatableReadByDefault()
        def renaming = store.holding(
                "select name from groups where group_id = '${PAYROLL}' for update" as String,
                "update groups set name = 'Payroll and Pensions', updated_at = now(), updated_by = '${FIRST_STEWARD}'"
                        + " where group_id = '${PAYROLL}'" as String)

        when:
        def choosing = attempting(racing) { currencies.choose(groupId(PAYROLL), "USD", ADA_USER) }
        store.untilWaiting(1)
        renaming.commit()
        renaming.close()
        def outcome = choosing.get(10, TimeUnit.SECONDS)

        then:
        outcome instanceof GroupCurrencies.Choice
        outcome.chosen() == currency("USD")

        and: "the rename and the choice both recorded"
        store.texts("select name from groups where group_id = ?::uuid", PAYROLL) == ["Payroll and Pensions"]
        rowOf(PAYROLL) == recorded

        where:
        earlier || recorded
        null    || "USD ${ADA} person null null" as String
        "EUR"   || "USD ${FIRST_STEWARD} person ${ADA} person" as String
    }

    /** The register's own rename, waiting on a choice under way, then landing beside it. */
    def "a rename of the group waits on a choice under way, and both land"() {
        given:
        store.repeatableReadByDefault()
        def choosing = store.holding(
                "select 1 from groups where group_id = '${PAYROLL}' for no key update" as String,
                "insert into group_currencies (group_id, currency, created_by)"
                        + " values ('${PAYROLL}', 'EUR', '${ADA}')" as String)
        def changes = new GroupChanges(store.session, store.transactions(), new GroupRegister(store.session),
                new PoolStays(store.session), new EstateRoleGrants(store.session))

        when:
        def renaming = attempting(racing) {
            changes.rename(groupId(PAYROLL), new GroupName("Payroll and Pensions"), STEWARD_USER)
        }
        store.untilWaiting(1)
        choosing.commit()
        choosing.close()
        def outcome = renaming.get(10, TimeUnit.SECONDS)

        then:
        outcome instanceof GroupRegister.GroupRow
        outcome.name() == new GroupName("Payroll and Pensions")

        and: "the choice and the rename both recorded"
        store.texts("select name from groups where group_id = ?::uuid", PAYROLL) == ["Payroll and Pensions"]
        rowOf(PAYROLL) == "EUR ${ADA} person null null" as String
    }

    private GroupCurrencies over(ModelCatalog catalog) {
        new GroupCurrencies(store.session, store.transactions(), new GroupRoles(store.session), catalog)
    }

    private static ModelPrice priced(String code) {
        new ModelPrice(currency(code), 1.0, 2.0)
    }

    private static Currency currency(String code) {
        Currency.getInstance(code)
    }

    /** A group's choice as its currency, its author and their kind, and its editor and theirs. */
    private String rowOf(String group) {
        store.session.sql("""
                select currency || ' ' || created_by || ' ' || created_by_kind || ' '
                       || coalesce(updated_by::text, 'null') || ' '
                       || case when updated_by is null then 'null' else updated_by_kind::text end
                  from group_currencies where group_id = ?::uuid
                """).param(group).query(String).optional().orElse(null)
    }

    private void pooled(String subject, String user, String name) {
        store.person(subject, user, name)
        store.session.sql("insert into pool_members (subject_id, created_by) values (?::uuid, ?::uuid)")
                .params(subject, FIRST_STEWARD).update()
    }

    private void chose(String group, String code, String author) {
        store.session.sql("insert into group_currencies (group_id, currency, created_by) values (?::uuid, ?, ?::uuid)")
                .params(group, code, author).update()
    }
}
