package com.lilradish.lite.app.library;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.registry.ContentPlace;
import com.lilradish.lite.domain.registry.ContentProblem;
import com.lilradish.lite.domain.registry.ContentProblemCode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Counts every content problem while retaining only the first problems the reader may be shown. */
public final class ContentProblemReport {

    static final int MOST_LISTED = 200;

    static final int MOST_PER_CASE = 20;

    private final List<ContentProblem> listed = new ArrayList<>();

    private final Map<UUID, Integer> listedByCase = new HashMap<>();

    private long total;

    void add(ContentProblemCode code, ContentPlace place) {
        add(code, place, null);
    }

    void add(ContentProblemCode code, ContentPlace place, @Nullable Long excess) {
        add(code, place, excess, null);
    }

    void add(
            ContentProblemCode code,
            ContentPlace place,
            @Nullable Long excess,
            ContentProblem.@Nullable FileLimits fileLimits) {
        requireNonNull(code, "ContentProblemReport code must not be null");
        requireNonNull(place, "ContentProblemReport place must not be null");
        if (reserve(caseId(place))) {
            listed.add(new ContentProblem(code, place, excess, fileLimits));
        }
    }

    /** Count before building a case difference's path or place; append only when this returns true. */
    boolean reserveCase(UUID caseId) {
        return reserve(requireNonNull(caseId, "ContentProblemReport caseId must not be null"));
    }

    void addReserved(ContentProblemCode code, ContentPlace place) {
        listed.add(new ContentProblem(code, place, null, null));
    }

    Result result() {
        return new Result(listed, total);
    }

    private boolean reserve(@Nullable UUID caseId) {
        total++;
        if (listed.size() >= MOST_LISTED) {
            return false;
        }
        if (caseId != null) {
            int count = listedByCase.getOrDefault(caseId, 0);
            if (count >= MOST_PER_CASE) {
                return false;
            }
            listedByCase.put(caseId, count + 1);
        }
        return true;
    }

    private static @Nullable UUID caseId(ContentPlace place) {
        return switch (place) {
            case ContentPlace.AtCase routeCase -> routeCase.caseId();
            case ContentPlace.AtCaseField field -> field.caseId();
            case ContentPlace.AtInput input -> input.caseId();
            default -> null;
        };
    }

    public record Result(List<ContentProblem> problems, long total) {

        public Result {
            problems = List.copyOf(requireNonNull(problems, "ContentProblemReport.Result problems must not be null"));
            if (total < problems.size()) {
                throw new IllegalArgumentException("ContentProblemReport.Result total is below listed count");
            }
        }
    }
}
