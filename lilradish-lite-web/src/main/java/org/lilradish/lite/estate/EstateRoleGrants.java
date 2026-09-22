package org.lilradish.lite.estate;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.lilradish.lite.domain.identity.UserId;
import org.lilradish.lite.domain.identity.EstateRole;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Which estate roles a user holds right now, asked again on every call. Nothing is kept: a
 * grant withdrawn has to take hold on the very next call, and anything that remembered one would be
 * the single thing standing between the withdrawal and its effect.
 *
 * <p>A user this system has never heard of and one it knows holding nothing answer alike, with
 * no roles at all: the two have the same true answer to what may be reached, which is nothing.
 *
 * <p>The tables below are named unqualified and this system pins no schema: which one the baseline
 * was applied into is the deployer's, and a name compiled in here would be this application refusing
 * every deployment that chose another. Which schema the connection reaches is the connection's to
 * settle.
 */
@Component
public final class EstateRoleGrants {

    private static final String CURRENTLY_HELD = """
            select holding.role
              from estate_role_grants holding
              join subjects person on person.subject_id = holding.subject_id
             where person.user_id = ? and holding.removed_at is null
            """;

    private final JdbcClient database;

    EstateRoleGrants(JdbcClient database) {
        this.database = database;
    }

    public Set<EstateRole> heldBy(UserId user) {
        List<EstateRole> held = database.sql(CURRENTLY_HELD)
                .param(user.value())
                .query((row, number) -> EstateRole.valueOf(row.getString(1).toUpperCase(Locale.ROOT)))
                .list();
        EnumSet<EstateRole> roles = EnumSet.noneOf(EstateRole.class);
        roles.addAll(held);
        return Collections.unmodifiableSet(roles);
    }
}
