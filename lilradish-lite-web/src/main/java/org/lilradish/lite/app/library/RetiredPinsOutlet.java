package org.lilradish.lite.app.library;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.libprunus.spring.error.ApiErrorHandler;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

/**
 * A version refused for pinning versions retired since, answered by whichever of the library's handlers
 * met it as the outlet answers any refusal, with each pin beside it: the entry by its identifier, kind and
 * name, the version pinned, and the newest of that entry in service where one is.
 *
 * <p>Every pin is one of the owning group's, whose entries its members read already, so nothing here is
 * more than the caller may see. Ordered first, as the outlet is ordered last, so this is asked before it.
 */
@RestControllerAdvice(basePackageClasses = Library.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
final class RetiredPinsOutlet {

    private static final String PINS = "pins";

    private final ApiErrorHandler outlet;

    RetiredPinsOutlet(ApiErrorHandler outlet) {
        this.outlet = outlet;
    }

    /* The outlet decides the status, the code and the sentence; null is a response already sent. */
    @ExceptionHandler(RetiredPinsRefusal.class)
    @Nullable
    ResponseEntity<Object> pinsRetired(RetiredPinsRefusal refused, WebRequest request) {
        ResponseEntity<Object> answered = outlet.handleApiError(refused, request);
        if (answered == null) {
            return null;
        }
        ProblemDetail problem = (ProblemDetail) requireNonNull(answered.getBody());
        problem.setProperty(
                PINS, refused.pins().stream().map(RetiredPinsOutlet::pinAnswer).toList());
        return answered;
    }

    static RetiredPinAnswer pinAnswer(RetiredPinsRefusal.RetiredPin pin) {
        RetiredPinsRefusal.NumberedVersion newest = pin.newestInService();
        return new RetiredPinAnswer(
                pin.entry().value(),
                pin.kind().published(),
                pin.name().value(),
                versionAnswer(pin.pinned()),
                newest == null ? null : versionAnswer(newest));
    }

    private static VersionAnswer versionAnswer(RetiredPinsRefusal.NumberedVersion version) {
        return new VersionAnswer(version.version().value(), version.number());
    }

    /** @param newestInService absent where none of that entry is in service */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record RetiredPinAnswer(
            UUID entryId,
            String kind,
            String name,
            VersionAnswer pinned,
            @Nullable VersionAnswer newestInService) {}

    record VersionAnswer(UUID versionId, int number) {}
}
