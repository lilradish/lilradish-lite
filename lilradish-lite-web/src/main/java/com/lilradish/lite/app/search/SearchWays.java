package com.lilradish.lite.app.search;

import com.lilradish.lite.domain.search.SearchWay;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
public final class SearchWays {

    private final Map<String, SearchWay> held;

    SearchWays(@Nullable List<SearchWay> ways) {
        Map<String, SearchWay> byName = new TreeMap<>();
        if (ways != null) {
            for (SearchWay way : ways) {
                if (way == null) {
                    throw new NullPointerException("SearchWays holds a null way");
                }
                if (byName.putIfAbsent(way.name(), way) != null) {
                    throw new IllegalArgumentException("SearchWays holds " + way.name() + " more than once");
                }
            }
        }
        this.held = Collections.unmodifiableMap(byName);
    }

    public Optional<SearchWay> find(String name) {
        return Optional.ofNullable(held.get(name));
    }

    public Map<String, SearchWay> all() {
        return held;
    }
}
