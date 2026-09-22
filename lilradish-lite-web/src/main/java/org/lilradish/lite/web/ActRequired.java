package org.lilradish.lite.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.lilradish.lite.domain.identity.EstateAct;

/**
 * The act a handler asks of whoever calls it, declared beside the address it answers. What enforces
 * it is {@link ActAdmission}, so no handler carries an authorisation branch of its own.
 *
 * <p>Every handler under this application's prefix carries this or {@link NoActRequired}, and the
 * two are separate annotations rather than one with a standing-in value: a handler that asks nothing
 * beyond being identified is a decision somebody took, and a default would let it be one nobody did.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ActRequired {

    EstateAct value();
}
