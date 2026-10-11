package com.lilradish.lite.app.inference

import com.lilradish.lite.domain.inference.CallRequest
import com.lilradish.lite.domain.inference.ModelCallPurpose
import com.lilradish.lite.domain.model.DeployedModel
import com.lilradish.lite.domain.model.DeployedTool
import com.lilradish.lite.domain.model.ModelName
import com.lilradish.lite.domain.model.ModelToolKind
import com.lilradish.lite.domain.model.ModelToolName
import com.lilradish.lite.domain.model.SentText
import spock.lang.Specification

class ToolCallRequestSpec extends Specification {

    static final ModelToolName SEARCH = new ModelToolName("web_search")
    static final DeployedModel MODEL = new DeployedModel(new ModelName("sample_model"), [], 1000,
            BigDecimal.ONE, 1000, [], [new DeployedTool(SEARCH, ModelToolKind.WEB_SEARCH, 3, [])])
    static final CallRequest CALL = new CallRequest(MODEL, null, ModelCallPurpose.PRODUCE,
            SentText.measure("system", "question"))

    def "one offered tool may be requested no more often than the deployment permits"() {
        when:
        def request = new ToolCallRequest(CALL, [new ToolUseLimit(SEARCH, 2)])

        then:
        request.tools()*.maximum() == [2L]
        request.call().model().name().value() == "sample_model"
    }

    def "unoffered repeated and over-limit tools are refused before sending"() {
        when:
        new ToolCallRequest(CALL, uses)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message.contains(reason)

        where:
        uses                                                       || reason
        [new ToolUseLimit(new ModelToolName("other_tool"), 1)]     || "does not offer tool other_tool"
        [new ToolUseLimit(SEARCH, 4)]                              || "more than sample_model offers"
        [new ToolUseLimit(SEARCH, 1), new ToolUseLimit(SEARCH, 2)] || "repeats tool web_search"
        []                                                         || "at least one tool"
    }


    def "review and helper calls cannot use a model tool"() {
        given:
        def call = new CallRequest(MODEL, null, purpose, SentText.measure("system", "question"))

        when:
        new ToolCallRequest(call, [new ToolUseLimit(SEARCH, 1)])

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "Only a production call may use tools"

        where:
        purpose << [ModelCallPurpose.REVIEW, ModelCallPurpose.HELP]
    }
}
