package org.lilradish.lite.app.listing;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One page of a list as a reader receives it. Every member with nothing to say is left out rather than
 * written as null, which this application's serialiser would otherwise do: absent is what a reader is
 * promised. That reaches no further than this record, so each item's own answer says the same of itself.
 *
 * @param nextCursor where the next page begins, absent on the last page
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PageAnswer<T>(List<T> items, @Nullable String nextCursor) {}
