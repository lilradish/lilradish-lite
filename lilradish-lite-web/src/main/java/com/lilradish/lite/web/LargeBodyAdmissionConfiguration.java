package com.lilradish.lite.web;

import com.lilradish.lite.app.files.FileDeployment;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.tomcat.autoconfigure.TomcatServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.HandlerExceptionResolver;

/* Imported by the door, so the turn is in every chain the door is, a web slice scanning no plain configuration.
 * Its order is read off the filter's class, never off this method. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({FileDeployment.class, TomcatServerProperties.class})
class LargeBodyAdmissionConfiguration {

    @Bean(destroyMethod = "close")
    FileTransfers fileTransfers(
            FileDeployment deployment,
            LongSupplier fileTransferTime,
            @Qualifier("fileUploadExecutor") ThreadPoolTaskExecutor workers) {
        return new FileTransfers(deployment, fileTransferTime, workers);
    }

    @Bean(defaultCandidate = false)
    ThreadPoolTaskExecutor fileUploadExecutor(TomcatServerProperties tomcat) {
        ThreadPoolTaskExecutor workers = new ThreadPoolTaskExecutor();
        workers.setThreadNamePrefix("file-upload-");
        workers.setVirtualThreads(true);
        workers.setCorePoolSize(tomcat.getThreads().getMax());
        workers.setMaxPoolSize(tomcat.getThreads().getMax());
        workers.setQueueCapacity(tomcat.getAcceptCount());
        workers.setStrictEarlyShutdown(true);
        return workers;
    }

    @Bean
    LongSupplier fileTransferTime() {
        return System::nanoTime;
    }

    @Bean
    LargeBodyAdmission largeBodyAdmission(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver refusals, LongSupplier fileTransferTime) {
        return new LargeBodyAdmission(refusals, fileTransferTime);
    }
}
