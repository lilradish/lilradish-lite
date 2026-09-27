package org.lilradish.lite.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Controller;
import org.springframework.util.DigestUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.HttpResource;
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
 * <p>Nothing under the prefix this application answers is ever a client route either: the document in
 * its place is a 200 carrying a page, which a caller reads as an operation that ran. Nothing is resolved
 * there, so the address is answered as missing, as {@link RefusalOutlet} answers every missing file.
 * This judges the prefix a second time beside the door, so it may only ever widen what is refused.
 *
 * <p>Only the hashed files are held in the chain's own map. That map has no bound and no eviction,
 * and the answer below is a resolution like any other, so a catch-all that kept its answers would
 * let anybody who can reach an unauthenticated address grow it without end by asking for names
 * nothing has a file for. What the pattern claiming the hashed files keeps instead is bounded by
 * what the build emitted, a name outside that set resolving to nothing and being kept as nothing.
 * The navigation path pays a lookup in the archive per request for that, which is the same lookup
 * it already pays the first time any address is asked for.
 *
 * <p>The only static handling there is. The framework's own claims the same catch-all pattern and is
 * switched off in the configuration rather than outlasted by registering after it, which would leave
 * whatever is set for it there read by nothing.
 *
 * <p>The root is a handler method rather than the framework's welcome page, whose refusals never reach
 * {@link RefusalOutlet}. The document is read once, at startup, and wherever it answers it is revalidated
 * by a tag of its content alone: the archive's timestamps say when it was written, not what it holds.
 */
@Controller
class ClientRouteFallback implements WebMvcConfigurer {

    private static final String BUNDLE = "classpath:/static/";

    private static final String DOCUMENT_NAME = "index.html";

    private static final Resource DOCUMENT = new ClassPathResource("static/" + DOCUMENT_NAME);

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

    /* Set rather than negotiated, so what the asker says it accepts decides nothing, as at every route. */
    private static final MediaType DOCUMENT_TYPE = new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8);

    private final byte[] document;

    private final String documentTag;

    private final LoadedDocument loaded;

    ClientRouteFallback() throws IOException {
        this.document = DOCUMENT.getContentAsByteArray();
        this.documentTag = "\"" + DigestUtils.md5DigestAsHex(document) + "\"";
        this.loaded = new LoadedDocument(document);
    }

    /* The framework has a generator answer null for what it cannot tag, though its signature says otherwise. */
    @SuppressWarnings("NullAway")
    private static Function<Resource, String> taggingOnly(Resource tagged, String tag) {
        return served -> served == tagged ? tag : null;
    }

    @GetMapping("/")
    ResponseEntity<byte[]> root() {
        return ResponseEntity.ok()
                .cacheControl(REVALIDATED)
                .eTag(documentTag)
                .contentType(DOCUMENT_TYPE)
                .body(document);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/" + HASHED_FILES + "**")
                .addResourceLocations(BUNDLE + HASHED_FILES)
                .setCacheControl(UNTIL_THE_NAME_CHANGES)
                .resourceChain(true);

        registry.addResourceHandler("/**")
                .addResourceLocations(BUNDLE)
                .setCacheControl(REVALIDATED)
                .setUseLastModified(false)
                // Any other file at the root carries no validator, and is sent whole every time it is asked for.
                .setEtagGenerator(taggingOnly(loaded, documentTag))
                .resourceChain(false)
                .addResolver(new PathResourceResolver() {

                    /* Asked of the chain first rather than resolved here, so that what a served path
                     * is checked against stays the framework's business and not a second copy of it. */
                    @Override
                    protected @Nullable Resource getResource(String resourcePath, Resource location)
                            throws IOException {
                        Resource asked = super.getResource(resourcePath, location);
                        if (asked != null) {
                            return asked;
                        }
                        PathContainer resolved = PathContainer.parsePath("/" + resourcePath);
                        return CallerAdmission.answersThisApplication(resolved) ? null : loaded;
                    }
                });
    }

    /* The type is set outright, as the root sends it. Still named, because the handler hands the name to
     * the container's type lookup unguarded, and that lookup's contract admits no null. */
    private static final class LoadedDocument extends ByteArrayResource implements HttpResource {

        private final HttpHeaders headers = new HttpHeaders();

        LoadedDocument(byte[] document) {
            super(document);
            headers.setContentType(DOCUMENT_TYPE);
        }

        @Override
        public String getFilename() {
            return DOCUMENT_NAME;
        }

        @Override
        public HttpHeaders getResponseHeaders() {
            return headers;
        }
    }
}
