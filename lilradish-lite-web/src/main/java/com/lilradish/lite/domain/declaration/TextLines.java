package com.lilradish.lite.domain.declaration;

import static java.util.Objects.requireNonNull;

public enum TextLines {
    ONE("one"),
    SEVERAL("several");

    private final String published;

    TextLines(String published) {
        this.published = published;
    }

    public String published() {
        return published;
    }

    public boolean accepts(String value) {
        requireNonNull(value, "TextLines value must not be null");
        if (this == SEVERAL) {
            return true;
        }
        for (int index = 0; index < value.length(); index++) {
            switch (value.charAt(index)) {
                case 0x0A, 0x0B, 0x0C, 0x0D, 0x85, 0x2028, 0x2029 -> {
                    return false;
                }
                default -> {}
            }
        }
        return true;
    }
}
