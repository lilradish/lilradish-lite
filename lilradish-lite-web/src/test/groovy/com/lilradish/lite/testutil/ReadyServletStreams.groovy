package com.lilradish.lite.testutil

import jakarta.servlet.Filter
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletResponseWrapper
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

/** Ready container streams for MockMvc, whose default streams reject nonblocking listeners. */
@TestConfiguration(proxyBeanMethods = false)
class ReadyServletStreams {

    @Bean
    Filter readyServletStreams() {
        { asked, answered, onward ->
            onward.doFilter(request(asked as HttpServletRequest), response(answered as HttpServletResponse))
        } as Filter
    }

    static HttpServletRequest request(HttpServletRequest request) {
        new HttpServletRequestWrapper(request) {
            ServletInputStream ready
            @Override ServletInputStream getInputStream() {
                if (ready == null) {
                    ready = new ReadyInput(super.getInputStream())
                }
                ready
            }
        }
    }

    static HttpServletResponse response(HttpServletResponse response) {
        new HttpServletResponseWrapper(response) {
            ServletOutputStream ready
            @Override ServletOutputStream getOutputStream() {
                if (ready == null) {
                    ready = new ReadyOutput(super.getOutputStream())
                }
                ready
            }
        }
    }

    private static class ReadyInput extends ServletInputStream {
        final ServletInputStream source
        boolean finished
        ReadListener listener

        ReadyInput(ServletInputStream source) { this.source = source }

        @Override boolean isFinished() { finished || source.isFinished() }
        @Override boolean isReady() { true }
        @Override void setReadListener(ReadListener listener) {
            this.listener = listener
            listener.onDataAvailable()
        }
        @Override int read() {
            int value = source.read()
            if (value < 0) { finished = true }
            value
        }
        @Override int read(byte[] bytes, int offset, int length) {
            int count = source.read(bytes, offset, length)
            if (count < 0) { finished = true }
            count
        }
        @Override void close() { source.close() }
    }

    private static class ReadyOutput extends ServletOutputStream {
        final ServletOutputStream destination

        ReadyOutput(ServletOutputStream destination) { this.destination = destination }

        @Override boolean isReady() { true }
        @Override void setWriteListener(WriteListener listener) {
            try {
                listener.onWritePossible()
            } catch (IOException failure) {
                listener.onError(failure)
            }
        }
        @Override void write(int value) { destination.write(value) }
        @Override void write(byte[] bytes, int offset, int length) { destination.write(bytes, offset, length) }
        @Override void flush() { destination.flush() }
        @Override void close() { destination.close() }
    }
}
