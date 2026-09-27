package org.lilradish.lite.web.fixture;

import java.util.concurrent.atomic.AtomicInteger;
import org.lilradish.lite.web.CallerAdmission;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.HttpRequestHandler;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

/** Handlers under the prefix with no handler method to say what they ask on, registered only where imported. */
@TestConfiguration(proxyBeanMethods = false)
public class NoMethodProbes implements WebMvcConfigurer {

    public static final String ROUTED = CallerAdmission.THIS_APPLICATION_ANSWERS + "/probe/routed";

    public static final String BEAN_NAMED = CallerAdmission.THIS_APPLICATION_ANSWERS + "/probe/bean-named";

    /** A file the static handling below holds, where a file here would be served were nothing asked of it. */
    public static final String SERVED_FILE = CallerAdmission.THIS_APPLICATION_ANSWERS + "/probe/files/probe.txt";

    /** Not under static/, where the bundle's spec holds the bundle to what the build emits and failed on it. */
    public static final String SERVED_FILE_SOURCE = "probe-files/probe.txt";

    @Bean
    AtomicInteger noMethodProbeRuns() {
        return new AtomicInteger();
    }

    @Bean
    RouterFunction<ServerResponse> routedProbe(AtomicInteger noMethodProbeRuns) {
        return RouterFunctions.route()
                .GET(ROUTED, request -> {
                    noMethodProbeRuns.incrementAndGet();
                    return ServerResponse.ok().build();
                })
                .build();
    }

    @Bean(BEAN_NAMED)
    HttpRequestHandler beanNamedProbe(AtomicInteger noMethodProbeRuns) {
        return (request, response) -> noMethodProbeRuns.incrementAndGet();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler(CallerAdmission.THIS_APPLICATION_ANSWERS + "/probe/files/**")
                .addResourceLocations("classpath:/probe-files/");
    }
}
