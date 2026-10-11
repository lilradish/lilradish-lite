package com.lilradish.lite.app.inference.fixture

import static java.nio.charset.StandardCharsets.UTF_8

import groovy.transform.CompileStatic

/** Finite padding lets a test detect a response reader that ignores its bound. */
@CompileStatic
final class OverlongBody extends InputStream {

    final byte[] opening

    final long length

    long served

    OverlongBody(String opening, long length) {
        this.opening = opening.getBytes(UTF_8)
        this.length = length
    }

    @Override
    int read() {
        if (served >= length) {
            return -1
        }
        int next = served < opening.length ? opening[(int) served] : (int) (' ' as char)
        served++
        next
    }

    @Override
    int read(byte[] into, int offset, int asked) {
        if (served < opening.length || served >= length) {
            int next = read()
            if (next < 0) {
                return -1
            }
            into[offset] = (byte) next
            return 1
        }
        int given = (int) Math.min((long) asked, length - served)
        Arrays.fill(into, offset, offset + given, (byte) 32)
        served += given
        given
    }
}
