package org.lilradish.lite.web;

import static java.util.Objects.requireNonNullElse;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import org.apache.catalina.connector.ClientAbortException;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.error.ApiErrorException;
import org.libprunus.spring.error.ApiErrorHandler;
import org.lilradish.lite.domain.failure.RefusalCode;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.util.DisconnectedClientHelper;

/**
 * The library's outlet, with the answers below rewritten. Replaced rather than stood in front of, because the
 * library's is what stamps a code, and a second would stamp again.
 *
 * <p>A method an address does not take is refused without repeating the method, and with the methods
 * listed as a request asking for them is answered: HEAD wherever GET is taken, and OPTIONS everywhere.
 *
 * <p>A missing file carries no sentence: the framework's names its own machinery and the path it
 * looked for, whichever address that was, and whether or not a resolver was asked at all.
 *
 * <p>A client gone away, too slow sending its body or gone while an answer is written, is no fault of this server's:
 * nothing is written, the container having answered or closed already, and it is logged as one line at debug.
 *
 * <p>A refusal as busy is load rather than a fault: it is logged as one line at warn, and says to send again once
 * the longest wait for the large body turn has passed.
 */
@RestControllerAdvice
final class RefusalOutlet extends ApiErrorHandler {

    private static final String METHOD_REFUSED = "This address does not take that method.";

    private static final DisconnectedClientHelper CLIENT_GONE =
            new DisconnectedClientHelper(RefusalOutlet.class.getName());

    /* The library's own property and sentence keys, which it keeps to itself; rendered as it renders them. */
    private static final String CODE = "code";

    private static final String SENTENCE_KEY = "problemDetail.";

    private static final String SEND_AGAIN_AFTER_SECONDS = Long.toString(LargeBodyAdmission.LONGEST_WAIT.toSeconds());

    /* The library logs every refusal answered 5xx at error with its trace, so busy is rendered here instead. */
    @Override
    public @Nullable ResponseEntity<Object> handleApiError(ApiErrorException refused, WebRequest request) {
        if (refused.errorCode() != RefusalCode.SERVICE_BUSY) {
            return super.handleApiError(refused, request);
        }
        String code = refused.errorCode().code();
        logger.warn("ApiErrorException [" + code + "] mapped to " + HttpStatus.SERVICE_UNAVAILABLE);
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        problem.setDetail(sentence(code, refused.getMessage()));
        problem.setProperty(CODE, code);
        HttpHeaders sendAgain = new HttpHeaders();
        sendAgain.set(HttpHeaders.RETRY_AFTER, SEND_AGAIN_AFTER_SECONDS);
        return handleExceptionInternal(refused, problem, sendAgain, HttpStatus.SERVICE_UNAVAILABLE, request);
    }

    private @Nullable String sentence(String code, @Nullable String fallback) {
        MessageSource sentences = getMessageSource();
        return sentences != null
                ? sentences.getMessage(SENTENCE_KEY + code, null, fallback, LocaleContextHolder.getLocale())
                : fallback;
    }

    @Override
    public @Nullable ResponseEntity<Object> handleUnexpected(Exception unexpected, WebRequest request) {
        if (raisedForTheClient(unexpected) && CLIENT_GONE.checkAndLogClientDisconnectedException(unexpected)) {
            return null;
        }
        return super.handleUnexpected(unexpected, request);
    }

    /* The helper alone matches a cause's simple name or message, which a dead database connection carries too;
     * only the container's own wrapping, or the framework's for a response gone, says it was the client's. */
    private static boolean raisedForTheClient(Throwable unexpected) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = unexpected; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof ClientAbortException || cause instanceof AsyncRequestNotUsableException) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleHttpMessageNotWritable(
            HttpMessageNotWritableException unwritable,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        if (raisedForTheClient(unwritable) && CLIENT_GONE.checkAndLogClientDisconnectedException(unwritable)) {
            return null;
        }
        return super.handleHttpMessageNotWritable(unwritable, headers, status, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleHttpRequestMethodNotSupported(
            HttpRequestMethodNotSupportedException refused,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        HttpHeaders allowing = new HttpHeaders();
        allowing.putAll(headers);
        allowing.setAllow(takenAlongside(requireNonNullElse(refused.getSupportedHttpMethods(), Set.of())));
        return handleExceptionInternal(
                refused, ProblemDetail.forStatusAndDetail(status, METHOD_REFUSED), allowing, status, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleNoResourceFoundException(
            NoResourceFoundException missing, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return handleExceptionInternal(missing, ProblemDetail.forStatus(status), headers, status, request);
    }

    private static Set<HttpMethod> takenAlongside(Set<HttpMethod> declared) {
        Set<HttpMethod> taken = new LinkedHashSet<>(declared);
        if (declared.contains(HttpMethod.GET)) {
            taken.add(HttpMethod.HEAD);
        }
        taken.add(HttpMethod.OPTIONS);
        return taken;
    }
}
