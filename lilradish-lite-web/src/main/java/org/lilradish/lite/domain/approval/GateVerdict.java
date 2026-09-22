package org.lilradish.lite.domain.approval;

/**
 * What an automated judge said at a gate — never what a person said there, whose decision is an
 * approval or a rejection. Its reject blocks; only its approve is powerless, because stopping a
 * step is something a reviewer may do and passing one is a person's to do.
 */
public enum GateVerdict {
    APPROVE,
    REJECT
}
