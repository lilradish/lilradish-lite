package org.lilradish.lite.app.estate;

import java.util.Set;
import org.libprunus.core.error.ApiErrorException;
import org.lilradish.lite.domain.failure.RefusalCode;
import org.lilradish.lite.domain.identity.EstateAct;
import org.lilradish.lite.domain.identity.EstateRole;
import org.lilradish.lite.domain.identity.UserId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Whether the roles somebody holds reach an act, asked in this one place by whoever asks it — the gate
 * in front of a handler, and a change asking again after its locks.
 *
 * <p>An act nobody holds and an act that does not exist are one refusal, whose sentence names the kind
 * of thing refused and never the act: the act is written to the log instead, where it is answered for
 * whoever operates this rather than for whoever called.
 */
public final class EstateReach {

    private static final Logger logger = LoggerFactory.getLogger(EstateReach.class);

    private EstateReach() {}

    public static void requireReached(Set<EstateRole> held, EstateAct act) {
        if (!EstateRole.actsOf(held).contains(act)) {
            logger.warn("Refused a caller holding no role that reaches {}", act);
            throw new ApiErrorException(RefusalCode.ACT_NOT_PERMITTED, "This caller may not do that.");
        }
    }

    /**
     * Asked again inside a change, after its locks: the gate asked before the change waited on them,
     * and a grant withdrawn in between has to hold. The caller's grants are read without a lock, which
     * would deadlock against the ordered exclusive one withdrawing a granting role takes on them.
     */
    public static void requireStillReached(EstateRoleGrants grants, UserId caller, EstateAct act) {
        requireReached(grants.heldBy(caller), act);
    }
}
