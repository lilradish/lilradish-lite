package org.lilradish.lite.app.measurements;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.identity.EstateAct;
import org.lilradish.lite.domain.model.ModelMode;
import org.lilradish.lite.web.ActRequired;
import org.lilradish.lite.web.CallerAdmission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The measurements whole, answered only to a caller holding the act of reading them. Every row is sent
 * at once and in one fixed order, and a reader sorts them itself.
 */
@RestController
final class MeasurementsController {

    private final Measurements measurements;

    MeasurementsController(Measurements measurements) {
        this.measurements = measurements;
    }

    @GetMapping(CallerAdmission.THIS_APPLICATION_ANSWERS + "/measurements")
    @ActRequired(EstateAct.READ_MEASUREMENTS)
    MeasurementsAnswer measurements() {
        Measurements.Measured measured = measurements.read();
        return new MeasurementsAnswer(
                measured.models().stream().map(MeasurementsController::answer).toList(),
                measured.system().stream().map(MeasurementsController::answer).toList());
    }

    private static ModelAnswer answer(Measurements.ModelFigures figures) {
        ModelMode mode = figures.mode();
        return new ModelAnswer(
                figures.model().value(),
                mode == null ? null : mode.value(),
                figures.productions(),
                figures.refusedOnReview(),
                figures.reviews(),
                figures.refusing(),
                figures.didNotFit());
    }

    private static SystemAnswer answer(Measurements.SystemFigures figures) {
        return new SystemAnswer(
                figures.model().value(), figures.wentWrong(), figures.neverCameBack(), figures.turnedAway());
    }

    /** Each model's figures in each mode it was asked in, and apart from them what calls to it came to. */
    record MeasurementsAnswer(List<ModelAnswer> models, List<SystemAnswer> system) {}

    /**
     * One model in one mode, or run as it is.
     *
     * @param mode null where the model was run as it is, and sent as null rather than left out
     */
    record ModelAnswer(
            String model,
            @Nullable String mode,
            long productions,
            long refusedOnReview,
            long reviews,
            long refusing,
            long didNotFit) {}

    record SystemAnswer(String model, long wentWrong, long neverCameBack, long turnedAway) {}
}
