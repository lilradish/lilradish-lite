package com.lilradish.lite.web

import jakarta.servlet.http.HttpServletRequest

/** The connection completion supplied by a real container, for MockMvc requests. */
class ReadyFileConnections {

    static void install(HttpServletRequest request) {
        FileTransfers.connection(request, new FileTransfers.ConnectionEnd() {
            @Override void abort(IOException failure) { request.asyncContext.complete() }
            @Override void close() {}
        })
    }
}
