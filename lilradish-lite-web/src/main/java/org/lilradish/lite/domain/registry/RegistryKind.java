package org.lilradish.lite.domain.registry;

/**
 * What kind of thing a registry entry is. An entry keeps one kind for life — only its content
 * versions — so a reference may carry the kind it expects and be refused when the target is another.
 */
public enum RegistryKind {
    WORKFLOW,
    PROMPT,
    MODEL,
    TOOL,
    CORPUS,
    EVALUATION_SET,
    REFERENCE_LIST,
    LESSON
}
