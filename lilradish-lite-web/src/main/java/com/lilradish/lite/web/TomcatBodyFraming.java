package com.lilradish.lite.web;

import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

/** Bound chunk framing that is read inside a blocking request-body read. */
@Component
final class TomcatBodyFraming implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        factory.addConnectorCustomizers(connector -> {
            if (!(connector.getProtocolHandler() instanceof AbstractHttp11Protocol<?> protocol)) {
                throw new IllegalStateException("Request body framing requires a Tomcat HTTP/1.1 connector");
            }
            protocol.setMaxExtensionSize(0);
            protocol.setMaxTrailerSize(2);
        });
    }
}
