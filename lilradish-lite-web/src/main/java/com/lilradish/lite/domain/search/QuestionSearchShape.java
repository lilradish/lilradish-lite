package com.lilradish.lite.domain.search;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.declaration.Field;
import com.lilradish.lite.domain.declaration.FieldName;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class QuestionSearchShape {

    private QuestionSearchShape() {}

    public static boolean matches(SearchWay way, List<Field> answers) {
        requireNonNull(way, "Search way must not be null");
        requireNonNull(answers, "Question answers must not be null");
        if (answers.size() != way.outputs().size()) {
            return false;
        }
        Map<FieldName, Field> named = new HashMap<>();
        for (Field answer : answers) {
            if (named.putIfAbsent(answer.name(), answer) != null) {
                return false;
            }
        }
        for (Field output : way.outputs()) {
            Field answer = named.get(output.name());
            if (answer == null
                    || !answer.shape().equals(output.shape())
                    || !answer.howMany().equals(output.howMany())
                    || answer.demand().mustBe() != output.demand().mustBe()) {
                return false;
            }
        }
        return true;
    }
}
