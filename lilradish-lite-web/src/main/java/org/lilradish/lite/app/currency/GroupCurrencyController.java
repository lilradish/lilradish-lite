package org.lilradish.lite.app.currency;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Currency;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.error.ApiErrorException;
import org.lilradish.lite.domain.failure.RefusalCode;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.identity.GroupPermission;
import org.lilradish.lite.web.ActAdmission;
import org.lilradish.lite.web.CallerAdmission;
import org.lilradish.lite.web.GroupPermissionRequired;
import org.lilradish.lite.web.JsonBody;
import org.lilradish.lite.web.QueryParameters;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * The currency a group reads its costs in, read by a member who may see the members and chosen by one who
 * may change the membership, in the group the gate admitted them into. A choice is answered as a read is.
 *
 * <p>A choice takes no parameter, and its body is read as {@link JsonBody} reads one rather than bound. It
 * holds one currency code in three capital English letters and nothing else, and anything else is refused
 * as the body before the store is asked; whether any model is priced in it is the store's to say.
 */
@RestController
final class GroupCurrencyController {

    private static final String CURRENCY = ActAdmission.IN_A_GROUP + "/currency";

    private static final String CURRENCY_MEMBER = "currency";

    private static final int CODE_LENGTH = 3;

    /** Room for the one member and its code with every character escaped, and the object around them. */
    private static final int LARGEST_BODY = 256;

    private static final String BODY_REFUSED =
            "This takes a JSON object holding one currency code in three capital English letters, and nothing else.";

    private static final String PARAMETER_REFUSED = "This change takes no parameter.";

    private final GroupCurrencies currencies;

    GroupCurrencyController(GroupCurrencies currencies) {
        this.currencies = currencies;
    }

    @GetMapping(CURRENCY)
    @GroupPermissionRequired(GroupPermission.READ_MEMBERSHIP)
    CurrencyAnswer currency(HttpServletRequest request) {
        return answer(currencies.read(ActAdmission.admittedGroup(request), CallerAdmission.callerOf(request)));
    }

    @PutMapping(CURRENCY)
    @GroupPermissionRequired(GroupPermission.CHANGE_MEMBERSHIP)
    CurrencyAnswer choose(HttpServletRequest request) throws IOException {
        GroupId group = ActAdmission.admittedGroup(request);
        QueryParameters.requireNone(request, PARAMETER_REFUSED);
        JsonNode body = JsonBody.read(request, LARGEST_BODY, BODY_REFUSED);
        JsonNode named = body.path(CURRENCY_MEMBER);
        if (!body.isObject() || body.size() != 1 || !named.isString() || !spelledAsCode(named.asString())) {
            throw new ApiErrorException(RefusalCode.BODY_UNUSABLE, BODY_REFUSED);
        }
        return answer(currencies.choose(group, named.asString(), CallerAdmission.callerOf(request)));
    }

    /* Capitals only, as the store holds a code, so no other spelling of one currency is taken for another. */
    private static boolean spelledAsCode(String spelled) {
        if (spelled.length() != CODE_LENGTH) {
            return false;
        }
        for (int at = 0; at < CODE_LENGTH; at++) {
            char letter = spelled.charAt(at);
            if (letter < 'A' || letter > 'Z') {
                return false;
            }
        }
        return true;
    }

    private static CurrencyAnswer answer(GroupCurrencies.Choice choice) {
        Currency chosen = choice.chosen();
        List<Currency> offered = choice.offered();
        return new CurrencyAnswer(
                chosen == null ? null : chosen.getCurrencyCode(),
                offered == null
                        ? null
                        : offered.stream().map(Currency::getCurrencyCode).toList());
    }

    /**
     * @param chosen absent where none has been chosen, and kept once no model is priced in it any longer
     * @param offered absent where the caller may not change the currency, and empty where no model is priced
     *     in any
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record CurrencyAnswer(@Nullable String chosen, @Nullable List<String> offered) {}
}
