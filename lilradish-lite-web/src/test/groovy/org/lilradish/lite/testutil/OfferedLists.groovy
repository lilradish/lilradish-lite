package org.lilradish.lite.testutil

import org.lilradish.lite.domain.declaration.OfferedTerms
import org.lilradish.lite.domain.referencelist.Term
import org.lilradish.lite.domain.referencelist.TermMeaning
import org.lilradish.lite.domain.registry.EntryVersionId

/** What each list offers, built from its terms in order, each meaning something of its own and no note beside. */
final class OfferedLists {

    private OfferedLists() {}

    static Map<EntryVersionId, OfferedTerms> offering(Map<EntryVersionId, List<String>> terms) {
        terms.collectEntries { list, held ->
            [(list): new OfferedTerms(held.collect { new OfferedTerms.Offered(new Term(it), new TermMeaning("Said so.")) },
                    null)]
        } as Map<EntryVersionId, OfferedTerms>
    }
}
