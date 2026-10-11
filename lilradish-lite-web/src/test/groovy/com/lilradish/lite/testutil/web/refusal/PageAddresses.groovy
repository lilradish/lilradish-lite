package com.lilradish.lite.testutil.web.refusal

import com.lilradish.lite.testutil.EmittedNames
import com.lilradish.lite.web.ClientRouteFallback
import org.springframework.core.io.ClassPathResource

final class PageAddresses {

    private PageAddresses() {}

    private static final String AN_EMITTED_FILE = "/" + ClientRouteFallback.HASHED_FILES +
            new ClassPathResource("static/" + ClientRouteFallback.HASHED_FILES).getFile().listFiles()
                    .find { EmittedNames.HASHED.matcher(it.name).matches() }.name

    static final List<String> ADDRESSES = [
        "/system/people",
        AN_EMITTED_FILE,
        "/index.html",
        "/",
    ]
}
