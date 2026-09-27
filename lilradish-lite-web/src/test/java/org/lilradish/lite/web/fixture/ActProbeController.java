package org.lilradish.lite.web.fixture;

import org.lilradish.lite.domain.identity.EstateAct;
import org.lilradish.lite.web.ActRequired;
import org.lilradish.lite.web.CallerAdmission;
import org.lilradish.lite.web.NoActRequired;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Two addresses under this application's prefix that exist to be gated: one asking an act, one
 * asking nothing beyond being identified. The gate is asked of these rather than of a shipped
 * handler, so what it decides is asked with no feature's store standing behind the answer.
 *
 * <p>Declared as a test component rather than as a plain one, so that it is registered by the specs
 * that import it and by no other slice: a fixture answering addresses in every slice is a surface
 * every other spec has to know about.
 */
@TestComponent
@RestController
public class ActProbeController {

    public static final String ASKING_AN_ACT = CallerAdmission.THIS_APPLICATION_ANSWERS + "/probe/asking-an-act";

    public static final String ASKING_NOTHING = CallerAdmission.THIS_APPLICATION_ANSWERS + "/probe/asking-nothing";

    /** What only a handler that actually ran can put on the wire. */
    public static final String ANSWERED = "the handler ran";

    @GetMapping(ASKING_AN_ACT)
    @ActRequired(EstateAct.KEEP_POOL)
    String askingAnAct() {
        return ANSWERED;
    }

    @GetMapping(ASKING_NOTHING)
    @NoActRequired
    String askingNothing() {
        return ANSWERED;
    }
}
