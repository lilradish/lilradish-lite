package org.lilradish.lite.app.inference;

/** What a mode asks of the endpoint, and the only thing it may: each constant as the endpoint spells it. */
enum ReasoningEffort {
    NONE("none"),
    MINIMAL("minimal"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    XHIGH("xhigh"),
    MAX("max");

    private final String sent;

    ReasoningEffort(String sent) {
        this.sent = sent;
    }

    String sent() {
        return sent;
    }
}
