package org.lilradish.lite.app.library;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import org.lilradish.lite.domain.declaration.Asking;
import org.lilradish.lite.domain.declaration.OfferedTerms;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.registry.ContentProblem;
import org.lilradish.lite.domain.registry.EntryKind;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.lilradish.lite.domain.wire.StoreLabels;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What a model is told of a question version a step of the group's pins: its instruction and both halves,
 * for whatever builds what is sent. It asks nobody's permission, naming the version being the whole question.
 */
@Component
public final class QuestionsAsked {

    // DB-SPECIFIC: an enum cast is PostgreSQL's.
    private static final String QUESTION_IN_GROUP = """
            select 1
              from entry_versions version
              join entries entry on entry.entry_id = version.entry_id
             where version.entry_version_id = :version
               and entry.group_id = :group
               and version.entry_kind = cast(:kind as entry_kind)
               and version.approved_at is not null
            """;

    private final JdbcClient database;

    private final TransactionTemplate snapshot;

    QuestionsAsked(JdbcClient database, PlatformTransactionManager transactionManager) {
        this.database = database;
        this.snapshot = Snapshots.readOnly(transactionManager);
    }

    /**
     * One moment of the store, refused inside a transaction reading more than one. No approved question of the
     * group's fails, as do one pinning another group's list and one holding a problem submitting names before
     * it measures; how much one asking of it could send is not measured here.
     */
    public Asking of(GroupId group, EntryVersionId version) {
        requireNonNull(group, "QuestionsAsked group must not be null");
        requireNonNull(version, "QuestionsAsked version must not be null");
        Snapshots.requireNoWeakerOneInForce("QuestionsAsked");
        Asking asked = snapshot.execute(status -> {
            if (database.sql(QUESTION_IN_GROUP)
                    .param("version", version.value())
                    .param("group", group.value())
                    .param("kind", StoreLabels.label(EntryKind.QUESTION))
                    .query(Integer.class)
                    .optional()
                    .isEmpty()) {
                throw new IllegalStateException(
                        "Group " + group.value() + " holds no approved question version " + version.value());
            }
            StoredQuestion stored = StoredQuestion.read(database, version)
                    .orElseThrow(() ->
                            new IllegalStateException("Version " + version.value() + " holds no question content"));
            Map<EntryVersionId, OfferedTerms> offered = stored.offered(database, group, version);
            List<ContentProblem> problems = QuestionContentCheck.problemsOf(stored, offered);
            if (!problems.isEmpty()) {
                throw new IllegalStateException(
                        "Question version " + version.value() + " holds what submitting would refuse");
            }
            return stored.told(version, offered);
        });
        return requireNonNull(asked);
    }
}
