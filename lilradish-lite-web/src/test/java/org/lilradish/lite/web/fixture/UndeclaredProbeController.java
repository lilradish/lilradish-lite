package org.lilradish.lite.web.fixture;

import org.lilradish.lite.web.CallerAdmission;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The handler somebody forgot to declare anything on: under the prefix, and silent about what it
 * asks of whoever reaches it. It exists because the rule that such a handler must not answer can
 * only be exercised against one, and the surface this application ships has none by construction —
 * a spec of its own holds that, and would go red were this registered anywhere but here.
 *
 * <p>Kept apart from the probe beside it for exactly that reason: the two rules contradict each
 * other on purpose, so the slice holding one must not register the fixture proving the other.
 */
@TestComponent
@RestController
public class UndeclaredProbeController {

    public static final String DECLARING_NOTHING = CallerAdmission.THIS_APPLICATION_ANSWERS + "/probe/undeclared";

    /** What only a handler that actually ran can put on the wire. */
    public static final String ANSWERED = "the undeclared handler ran";

    @GetMapping(DECLARING_NOTHING)
    String declaringNothing() {
        return ANSWERED;
    }
}
