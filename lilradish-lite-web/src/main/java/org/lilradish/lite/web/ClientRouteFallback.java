package org.lilradish.lite.web;

import java.io.IOException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.log.LogFormatUtils;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Every address the reader's side routes for itself, answered with the one document that routes it.
 * Those addresses exist inside a loaded page and nowhere else, so typed in, refreshed or opened from
 * a bookmark each arrives here as a request for a path this archive has no file for — and what the
 * reader would otherwise be told is that the screen they were just on does not exist.
 *
 * <p>The bundle's own files are claimed by a pattern of their own, which is what keeps them out of
 * that answer. Their names carry a hash of what is in them, so one this archive has no file for is a
 * name from a build that is gone, and the honest reply is that it is missing; handed the document
 * instead, a browser asking for a module is given markup.
 *
 * <p>That same property is what earns them the freshness below: a name that resolves can never later
 * mean different bytes, so a request asking whether it has changed could not come back saying so.
 * Nothing outside that pattern may borrow it. A name kept that way while the bytes behind it can
 * still change is one no later deploy can reach, and the reader is the only one who can let go of it.
 *
 * <p>Nothing under the prefix this application answers is ever a client route either. An address
 * there that names no operation is refused instead: the document in its place is a 200 carrying a
 * page, which a caller reads as an operation that ran. Whether an address is under it is asked of
 * {@link CallerAdmission} rather than judged again here — two judgements, unable to see each other,
 * would drift apart and leave this reachable for addresses nothing guards. What is handed over is
 * the path the framework resolved this request to rather than the request, that path being the one
 * thing out here the resolution already decoded and collapsed the doubled separators out of.
 *
 * <p>That a resolver refuses rather than resolving to nothing is the one thing here a reader would
 * not expect, and it is deliberate. Resolving to nothing is exactly what makes the framework answer
 * that it has no static resource of that name — a sentence naming its own machinery and handing the
 * caller back the path they sent — and that sentence is composed where the resolution ended, so
 * there is no later place to take it off. Refusing here is also the only way the doubled-separator
 * spellings are covered: anything deciding earlier has only the request line, where the separators
 * are still doubled and the judgement reads false, while the path this is handed has had them
 * collapsed out. The refusal is raised as the transport's own missing-address signal carrying no
 * reason, so the outlet that renders every refusal of this application writes no sentence on it and
 * stamps it with the code a response declaring none of its own carries.
 *
 * <p>Only the hashed files are held in the chain's own map. That map has no bound and no eviction,
 * and the answer below is a resolution like any other, so a catch-all that kept its answers would
 * let anybody who can reach an unauthenticated address grow it without end by asking for names
 * nothing has a file for. What the pattern claiming the hashed files keeps instead is bounded by
 * what the build emitted, a name outside that set resolving to nothing and being kept as nothing.
 * The navigation path pays a lookup in the archive per request for that, which is the same lookup
 * it already pays the first time any address is asked for.
 *
 * <p>Registered last on purpose. The catch-all pattern is the one the framework's own static
 * handling claims, and a registry keyed by pattern keeps whichever registration came last, so the
 * ordering is what decides which of the two answers.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class ClientRouteFallback implements WebMvcConfigurer {

    private static final Logger logger = LoggerFactory.getLogger(ClientRouteFallback.class);

    private static final String BUNDLE = "classpath:/static/";

    private static final Resource DOCUMENT = new ClassPathResource("static/index.html");

    /* Named once and read twice below: the pattern claiming these and the directory they are read
     * out of have to be one word, and a test holds that word level with the one the build emits. */
    static final String HASHED_FILES = "assets/";

    /* Bounded because the extension holds only while the response is fresh, so this is also how long
     * a name wrongly kept this way could outlive the build that emitted it. */
    private static final CacheControl UNTIL_THE_NAME_CHANGES =
            CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable();

    /* The map from those names to the build that emitted them, so it is the one thing here that has
     * to be re-read before it is trusted. Stored rather than refetched: unchanged, it answers 304. */
    private static final CacheControl REVALIDATED = CacheControl.noCache();

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/" + HASHED_FILES + "**")
                .addResourceLocations(BUNDLE + HASHED_FILES)
                .setCacheControl(UNTIL_THE_NAME_CHANGES)
                .resourceChain(true);

        registry.addResourceHandler("/**")
                .addResourceLocations(BUNDLE)
                .setCacheControl(REVALIDATED)
                .resourceChain(false)
                .addResolver(new PathResourceResolver() {

                    /* Asked of the chain first rather than resolved here, so that what a served path
                     * is checked against stays the framework's business and not a second copy of it. */
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource asked = super.getResource(resourcePath, location);
                        if (asked != null) {
                            return asked;
                        }
                        if (!CallerAdmission.answersThisApplication(PathContainer.parsePath("/" + resourcePath))) {
                            return DOCUMENT;
                        }
                        // Decoded by the time it is handed over, so a newline in it would forge a line.
                        logger.warn(LogFormatUtils.formatValue(
                                "Nothing answers " + resourcePath + " under the prefix this application answers",
                                -1,
                                true));
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
                    }
                });
    }
}
