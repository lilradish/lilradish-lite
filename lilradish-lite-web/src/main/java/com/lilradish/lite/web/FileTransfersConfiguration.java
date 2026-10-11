package com.lilradish.lite.web;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.apache.coyote.ActionCode;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/* Servlet completion waits for queued writes; only a container action can cut those writes off. */
@Configuration(proxyBeanMethods = false)
class FileTransfersConfiguration {

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> fileTransferConnections() {
        return factory -> factory.addContextValves(new TransferConnections());
    }

    private static final class TransferConnections extends ValveBase {

        private TransferConnections() {
            super(true);
        }

        @Override
        public void invoke(Request request, Response response) throws IOException, ServletException {
            boolean downloading = ("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()))
                    && request.getDecodedRequestURI().contains("/runs/")
                    && request.getDecodedRequestURI().contains("/files/");
            if (!downloading && !MultipartFiles.sent(request)) {
                getNext().invoke(request, response);
                return;
            }
            Connection connection = new Connection(request.getCoyoteRequest());
            FileTransfers.connection(request, connection);
            try {
                getNext().invoke(request, response);
            } finally {
                if (request.isAsyncStarted()) {
                    request.getAsyncContext().addListener(connection);
                } else {
                    connection.close();
                }
            }
        }
    }

    private static final class Connection implements FileTransfers.ConnectionEnd, AsyncListener {

        private org.apache.coyote.@Nullable Request request;

        private Connection(org.apache.coyote.Request request) {
            this.request = request;
        }

        @Override
        public synchronized void abort(IOException failure) {
            if (request != null) {
                request.action(ActionCode.CLOSE_NOW, failure);
            }
        }

        @Override
        public synchronized void close() {
            request = null;
        }

        @Override
        public void onComplete(AsyncEvent event) {
            close();
        }

        @Override
        public void onError(AsyncEvent event) {}

        @Override
        public void onTimeout(AsyncEvent event) {}

        @Override
        public void onStartAsync(AsyncEvent event) {
            event.getAsyncContext().addListener(this);
        }
    }
}
