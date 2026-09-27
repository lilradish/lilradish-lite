package org.lilradish.lite.testutil

import jakarta.servlet.DispatcherType
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.util.WebUtils

/**
 * The second pass the container makes once a failed request has unwound. A filter consults the
 * dispatch type and the failed address, so both are set, and the request line becomes the error page's.
 */
final class FailureDispatch {

    private FailureDispatch() {}

    static MockHttpServletRequest failedOver(MockHttpServletRequest request) {
        request.setAttribute(WebUtils.ERROR_REQUEST_URI_ATTRIBUTE, request.requestURI)
        request.setDispatcherType(DispatcherType.ERROR)
        request.setRequestURI("/error")
        request
    }
}
