package com.lilradish.lite.domain.workflow;

import static java.util.Objects.requireNonNull;

public sealed interface Producer permits Producer.Model, Producer.Person, Producer.Code {

    StepProducer kind();

    /** @param toldWhatHappened whether a model asked again is given the refusal and the words it came with */
    record Model(ModelChoice choice, boolean toldWhatHappened) implements Producer {

        public Model {
            requireNonNull(choice, "Producer.Model choice must not be null");
        }

        @Override
        public StepProducer kind() {
            return StepProducer.MODEL;
        }
    }

    record Person() implements Producer {

        @Override
        public StepProducer kind() {
            return StepProducer.PERSON;
        }
    }

    record Code() implements Producer {

        @Override
        public StepProducer kind() {
            return StepProducer.CODE;
        }
    }
}
