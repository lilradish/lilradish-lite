package org.lilradish.lite.app.currency;

import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.error.ApiErrorException;
import org.lilradish.lite.app.change.Author;
import org.lilradish.lite.app.change.ChangeTransactions;
import org.lilradish.lite.app.group.GroupReach;
import org.lilradish.lite.app.group.GroupRoles;
import org.lilradish.lite.domain.failure.RefusalCode;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.identity.GroupPermission;
import org.lilradish.lite.domain.identity.GroupRole;
import org.lilradish.lite.domain.identity.UserId;
import org.lilradish.lite.domain.model.ModelCatalog;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * The one currency a group reads its costs in, and the currencies it may be changed to, which are the ones
 * some model is priced in now. A currency chosen stays chosen once no model is priced in it any longer, and
 * choosing it again is choosing what is chosen already.
 *
 * <p>Only a member who may change the group's membership chooses one, and is the only one offered any.
 * A choice is never deleted and never moved to another group: the first is recorded as its author's act,
 * and every later one as its editor's. A stored code no currency has fails the whole read.
 *
 * <p>A choice locks the group's row first. It is made in {@link ChangeTransactions}' transaction, asks
 * {@link GroupReach#requireStillReached} after the lock, and records its {@link Author}.
 */
@Component
final class GroupCurrencies {

    // DB-SPECIFIC: now(), greatest and for no key update are PostgreSQL's.
    private static final String AUTHOR = Author.OF_CALLER;

    /* Conflicting with itself, it puts every change to this group's choice and to its membership one after the
     * other; only after it can the caller's roles no longer move, so they are asked again there. */
    private static final String GROUP_LOCKED = """
            select 1 from groups circle where circle.group_id = :group for no key update
            """;

    private static final String CHOSEN = """
            select chosen.currency from group_currencies chosen where chosen.group_id = :group
            """;

    private static final String FIRST_CHOICE = """
            insert into group_currencies (group_id, currency, created_by)
            values (:group, :currency, %s)
            """.formatted(AUTHOR);

    /* now() is when this transaction began, which can be before the choice, or its last change, was made;
     * greatest passes over an updated_at still null. */
    private static final String CHANGE = """
            update group_currencies
               set currency = :currency, updated_at = greatest(now(), created_at, updated_at), updated_by = %s
             where group_id = :group
            """.formatted(AUTHOR);

    private static final String NOT_PRICED = "No model is priced in that currency.";

    private final JdbcClient database;

    private final TransactionOperations transactions;

    private final GroupRoles roles;

    private final List<Currency> priced;

    private final Map<String, Currency> pricedByCode;

    GroupCurrencies(JdbcClient database, TransactionOperations transactions, GroupRoles roles, ModelCatalog catalog) {
        this.database = database;
        this.transactions = transactions;
        this.roles = roles;
        this.priced = catalog.currencies();
        Map<String, Currency> pricedByCode = HashMap.newHashMap(priced.size());
        for (Currency currency : priced) {
            pricedByCode.put(currency.getCurrencyCode(), currency);
        }
        this.pricedByCode = Map.copyOf(pricedByCode);
    }

    /** Asked again of the permission the reading was admitted on, which the roles read here must still reach. */
    Choice read(GroupId group, UserId caller) {
        Set<GroupRole> held = roles.heldBy(caller, group);
        GroupReach.requireReached(held, group, GroupPermission.READ_MEMBERSHIP);
        boolean offering = GroupReach.reachedBy(held).contains(GroupPermission.CHANGE_MEMBERSHIP);
        return new Choice(chosenIn(group).orElse(null), offering ? priced : null);
    }

    /**
     * Chosen so already, it is chosen still and nothing is recorded, whether or not any model is priced in it
     * now. Any other currency is taken only where some model is priced in it.
     */
    Choice choose(GroupId group, String code, UserId caller) {
        return transactions.execute(status -> {
            database.sql(GROUP_LOCKED)
                    .param("group", group.value())
                    .query(Integer.class)
                    .optional();
            GroupReach.requireStillReached(roles, caller, group, GroupPermission.CHANGE_MEMBERSHIP);
            Optional<Currency> chosen = chosenIn(group);
            if (chosen.isPresent() && chosen.get().getCurrencyCode().equals(code)) {
                return new Choice(chosen.get(), priced);
            }
            Currency taken = pricedByCode.get(code);
            if (taken == null) {
                throw new ApiErrorException(RefusalCode.CURRENCY_NOT_PRICED, NOT_PRICED);
            }
            database.sql(chosen.isEmpty() ? FIRST_CHOICE : CHANGE)
                    .param("group", group.value())
                    .param("currency", code)
                    .param("caller", caller.value())
                    .update();
            return new Choice(taken, priced);
        });
    }

    private Optional<Currency> chosenIn(GroupId group) {
        return database.sql(CHOSEN)
                .param("group", group.value())
                .query((result, number) -> heldAs(group, result.getString(1)))
                .optional();
    }

    private static Currency heldAs(GroupId group, String code) {
        try {
            return Currency.getInstance(code);
        } catch (IllegalArgumentException refused) {
            throw new IllegalStateException(
                    "Group " + group.value() + " holds a currency this system will not show", refused);
        }
    }

    /**
     * @param chosen absent where none has been chosen
     * @param offered ordered by code, and absent where the caller may not change the currency
     */
    record Choice(@Nullable Currency chosen, @Nullable List<Currency> offered) {}
}
