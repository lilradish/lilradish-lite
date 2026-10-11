package com.lilradish.lite.web

import jakarta.servlet.http.HttpServletRequest

/** The parser's package-private receiving contract, reached by already-complete business fixtures. */
final class ReadyMultipartFiles {

    static MultipartFiles open(HttpServletRequest request, int documentMaximum) {
        MultipartFiles.open(MultipartFiles.sending(request), request.getInputStream(), documentMaximum,
                new MultipartFiles.Receiving() {
                    @Override void limit(long bytes) {}
                    @Override void finished() {}
                })
    }

    private ReadyMultipartFiles() {}
}
