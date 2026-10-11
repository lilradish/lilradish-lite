package com.lilradish.lite.app.library;

import com.lilradish.lite.domain.failure.RefusalCode;
import java.util.List;
import org.libprunus.core.error.ApiErrorException;

/** Unknown references can hold empty or invalid field names; their raw contents must reach the caller. */
final class InputReferenceRefusal extends ApiErrorException {

    private final transient List<String> names;

    InputReferenceRefusal(RefusalCode code, String sentence, List<String> names) {
        super(code, sentence);
        this.names = List.copyOf(names);
    }

    List<String> names() {
        return names;
    }
}
