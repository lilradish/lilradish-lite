package org.lilradish.lite.domain.identity;

/**
 * What an actor that exists in code may do, and a vocabulary of its own rather than a corner of a
 * group's. Nothing a person can be granted is stated in these terms, so a role cannot name one and
 * no accumulation of roles can reach one: the guarantee is in the type rather than in the role table
 * being watched for their absence.
 */
public enum SystemPermission {

    /** A system principal reads in bulk; what it may never do is surface what it read. */
    READ_BULK_CONTENT,

    EXPIRE_RETENTION
}
