package com.lilradish.lite.domain.wire;

import static java.util.Objects.requireNonNull;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Store labels derive from lower-case constant names so a second spelling cannot drift. The schema spec
 * checks every stored type's labels against its constants, catching renames before rows become unreadable.
 *
 * <p>Only for store-only spellings. Published spellings are separate contracts, written beside each constant;
 * neither spelling may change because the other does.
 */
public final class StoreLabels {

    /* Folded once per type and kept for as long as the type is, so a label written or read per row is
     * a lookup. Indexed by ordinal, which is read here and never stored. */
    private static final ClassValue<String[]> BY_ORDINAL = new ClassValue<>() {
        @Override
        protected String[] computeValue(Class<?> type) {
            Object[] constants = type.getEnumConstants();
            String[] labels = new String[constants.length];
            for (int ordinal = 0; ordinal < constants.length; ordinal++) {
                labels[ordinal] = ((Enum<?>) constants[ordinal]).name().toLowerCase(Locale.ROOT);
            }
            return labels;
        }
    };

    private static final ClassValue<Map<String, Enum<?>>> BY_LABEL = new ClassValue<>() {
        @Override
        protected Map<String, Enum<?>> computeValue(Class<?> type) {
            String[] labels = BY_ORDINAL.get(type);
            Object[] constants = type.getEnumConstants();
            Map<String, Enum<?>> labelled = new HashMap<>();
            for (int ordinal = 0; ordinal < constants.length; ordinal++) {
                labelled.put(labels[ordinal], (Enum<?>) constants[ordinal]);
            }
            return Map.copyOf(labelled);
        }
    };

    private StoreLabels() {}

    public static String label(Enum<?> constant) {
        requireNonNull(constant, "StoreLabels constant must not be null");
        return BY_ORDINAL.get(constant.getDeclaringClass())[constant.ordinal()];
    }

    public static <E extends Enum<E>> E parse(Class<E> type, String label) {
        requireNonNull(type, "StoreLabels type must not be null");
        requireNonNull(label, "StoreLabels label must not be null");
        Enum<?> found = BY_LABEL.get(type).get(label);
        if (found == null) {
            throw new IllegalArgumentException(
                    "No " + type.getSimpleName() + " is stored under the label '" + label + "'");
        }
        return type.cast(found);
    }
}
