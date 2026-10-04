package org.lilradish.lite.app.files

import org.springframework.util.unit.DataSize
import spock.lang.Specification

class FileDeploymentSpec extends Specification {

    def "a deployment holds positive limits and a total no smaller than one file"() {
        when:
        def deployment = new FileDeployment(DataSize.ofBytes(size), count, DataSize.ofBytes(total))

        then:
        deployment.maximumSize().toBytes() == size
        deployment.maximumPerField() == count
        deployment.maximumTotalSize().toBytes() == total

        where:
        size | count | total
        1L   | 1     | 1L
        100L | 2     | 100L
        100L | 2     | 101L
        9_007_199_254_740_991L | 1 | 9_007_199_254_740_991L
    }

    def "a deployment refuses a missing, nonpositive, or smaller total bound"() {
        when:
        new FileDeployment(size == null ? null : DataSize.ofBytes(size), count,
                total == null ? null : DataSize.ofBytes(total))

        then:
        def refusal = thrown(IllegalArgumentException)
        refusal.message == message

        where:
        size | count | total || message
        null | 1     | 1L    || "lilradish.files.maximum-size must be positive"
        0L   | 1     | 1L    || "lilradish.files.maximum-size must be positive"
        1L   | null  | 1L    || "lilradish.files.maximum-per-field must be positive"
        1L   | 0     | 1L    || "lilradish.files.maximum-per-field must be positive"
        1L   | 1     | null  || "lilradish.files.maximum-total-size must be positive"
        1L   | 1     | 0L    || "lilradish.files.maximum-total-size must be positive"
        2L   | 1     | 1L    || "lilradish.files.maximum-total-size must not be less than maximum-size"
        9_007_199_254_740_992L | 1 | 9_007_199_254_740_992L || "lilradish.files.maximum-size must not exceed 9007199254740991 bytes"
        1L | 1 | 9_007_199_254_740_992L || "lilradish.files.maximum-total-size must not exceed 9007199254740991 bytes"
    }
}
