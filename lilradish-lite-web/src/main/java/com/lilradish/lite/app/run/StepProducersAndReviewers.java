package com.lilradish.lite.app.run;

import com.lilradish.lite.domain.codestep.ReleasedCodeStep;
import com.lilradish.lite.domain.declaration.Declaration;
import com.lilradish.lite.domain.declaration.Demand;
import com.lilradish.lite.domain.declaration.Field;
import com.lilradish.lite.domain.declaration.FieldStanding;
import com.lilradish.lite.domain.model.ModelMode;
import com.lilradish.lite.domain.run.PlannedStep;
import com.lilradish.lite.domain.run.StepRuns;
import com.lilradish.lite.domain.workflow.ModelChoice;
import com.lilradish.lite.domain.workflow.Producer;
import com.lilradish.lite.domain.workflow.StepProducer;
import org.jspecify.annotations.Nullable;

final class StepProducersAndReviewers {

    private StepProducersAndReviewers() {}

    static StepAnswers.@Nullable WhoAnswer producer(PlannedStep planned) {
        Producer producer = planned.producer();
        return switch (producer) {
            case null -> null;
            case Producer.Model model -> model(model.choice());
            case Producer.Person ignored -> new StepAnswers.WhoAnswer("person", null, null, null);
            case Producer.Code ignored -> new StepAnswers.WhoAnswer("code", null, null, null);
        };
    }

    /* Standing above a confidence waits on a person as never standing does: a person's production carries none. */
    static StepAnswers.@Nullable WhoAnswer reviewer(PlannedStep planned) {
        ModelChoice reviewer = planned.reviewer();
        if (reviewer != null) {
            return model(reviewer);
        }
        Declaration gives = null;
        if (planned.runs() instanceof StepRuns.Question question) {
            gives = question.gives();
        } else if (planned.runs() instanceof StepRuns.Code code) {
            ReleasedCodeStep released = code.released();
            gives = released == null ? null : released.declared().gives();
        }
        if (gives == null) {
            return null;
        }
        for (Field field : gives.fields()) {
            if (!(field.demand() instanceof Demand.Stands stands && stands.standing() == FieldStanding.ALWAYS)) {
                return new StepAnswers.WhoAnswer(StepProducer.PERSON.published(), null, null, null);
            }
        }
        return null;
    }

    static StepAnswers.WhoAnswer model(ModelChoice choice) {
        ModelMode mode = choice.mode();
        return new StepAnswers.WhoAnswer("model", null, choice.model().value(), mode == null ? null : mode.value());
    }
}
