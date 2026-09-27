create type soundness_check_outcome as enum ('finished', 'cut_short');

-- ---------------------------------------------------------------- checks

-- Inserted running, and ended only by an update guarded on outcome is null, run under read committed; once
-- ended, nothing updates it again.
create table soundness_checks (
    soundness_check_id uuid                    not null default uuidv7(),
    -- An end whose guarded update meets no row finds the check already cut short, and writes none of its counts.
    outcome            soundness_check_outcome,
    created_at         timestamptz             not null default now(),
    created_by         uuid                    not null,
    created_by_kind    subject_kind            not null default 'person',

    constraint soundness_checks_pk primary key (soundness_check_id),
    constraint soundness_checks_author_person_fk foreign key (created_by, created_by_kind)
        references subjects (subject_id, kind),
    constraint soundness_checks_author_is_person check (created_by_kind = 'person'),
    -- A foreign key target, at the cost subjects_kind_unique gives.
    constraint soundness_checks_outcome_unique unique (soundness_check_id, outcome)
);

-- A process, on starting and before it serves, cuts short any check left running; so only for a single instance. A
-- failed worker cuts its own short in a finally, retried until it lands, and times out, or no check starts again.
create unique index soundness_checks_one_running
    -- Nulls not distinct, or every running check would differ from every other and all of them be admitted.
    on soundness_checks (outcome) nulls not distinct where outcome is null;

-- ---------------------------------------------------------------- counts

-- A row for every group there is when the check runs, 0 and 0 for one owning no workflow: a group with no
-- row in a check was not yet there to be checked, which is never a count of none.
create table soundness_counts (
    soundness_check_id uuid                    not null,
    -- Always 'finished', only to match soundness_checks.outcome through soundness_counts_check_fk.
    check_outcome      soundness_check_outcome not null default 'finished',
    group_id           uuid                    not null,
    workflows          integer                 not null,
    unsound            integer                 not null,

    constraint soundness_counts_pk primary key (soundness_check_id, group_id),
    -- A check is ended and then counted in one transaction, the only one to write its counts: a count written
    -- later mixes into the reading a group that was not there when it was taken.
    constraint soundness_counts_check_fk foreign key (soundness_check_id, check_outcome)
        references soundness_checks (soundness_check_id, outcome),
    constraint soundness_counts_check_is_finished check (check_outcome = 'finished'),
    constraint soundness_counts_group_fk foreign key (group_id) references groups (group_id),
    -- With soundness_counts_unsound_not_negative this holds workflows to zero or more as well.
    constraint soundness_counts_unsound_at_most_workflows check (unsound <= workflows),
    constraint soundness_counts_unsound_not_negative check (unsound >= 0)
);
