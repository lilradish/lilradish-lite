-- DB-SPECIFIC: length(), text, !~, ~, uuid and four-digit Unicode escapes in regular expressions use PostgreSQL's syntax and semantics.
create table stored_files (
    file_id       uuid   not null,
    group_id      uuid   not null,
    original_name text   not null,
    byte_size     bigint not null,
    constraint stored_files_pk primary key (file_id),
    constraint stored_files_group_fk foreign key (group_id) references groups (group_id),
    constraint stored_files_group_unique unique (file_id, group_id),
    constraint stored_files_name_bounded check (length(original_name) between 1 and 255),
    constraint stored_files_name_one_line check (original_name !~ '[\u0000-\u001f\u007f-\u009f]'),
    constraint stored_files_byte_size_nonnegative check (byte_size >= 0)
);

create index stored_files_by_group on stored_files (group_id);

-- DB-SPECIFIC: bytea, deferrable and uuid use PostgreSQL's syntax and semantics.
create table file_contents (
    file_id uuid  not null,
    content bytea not null,
    constraint file_contents_pk primary key (file_id),
    constraint file_contents_metadata_fk foreign key (file_id) references stored_files (file_id)
        deferrable initially deferred
);

-- DB-SPECIFIC: deferrable uses PostgreSQL's syntax and semantics.
alter table stored_files add constraint stored_files_content_fk
    foreign key (file_id) references file_contents (file_id) deferrable initially deferred;

-- DB-SPECIFIC: deferrable and uuid use PostgreSQL's syntax and semantics.
create table run_file_holders (
    run_id   uuid not null,
    file_id  uuid not null,
    group_id uuid not null,
    constraint run_file_holders_pk primary key (run_id, file_id),
    constraint run_file_holders_run_fk foreign key (run_id, group_id) references runs (run_id, group_id)
        deferrable initially deferred,
    constraint run_file_holders_file_fk foreign key (file_id, group_id)
        references stored_files (file_id, group_id)
);

create index run_file_holders_by_file on run_file_holders (file_id, group_id);

-- DB-SPECIFIC: length(), text, ~ and uuid use PostgreSQL's syntax and semantics.
create table errored_code_returned_files (
    production_id uuid not null,
    run_id uuid not null,
    file_id uuid not null,
    field_path text not null,
    producer step_producer not null default 'code',
    lost_reason try_lost_reason not null default 'errored',
    constraint errored_code_returned_files_pk primary key (production_id, field_path),
    constraint errored_code_returned_files_code_only check (producer = 'code' and lost_reason = 'errored'),
    constraint errored_code_returned_files_path_bounded check (length(field_path) between 1 and 2048),
    constraint errored_code_returned_files_path_shape check (field_path ~
        '^[a-z][a-z0-9_]{0,62}(\[(0|[1-9][0-9]*)\])?(\.[a-z][a-z0-9_]{0,62}(\[(0|[1-9][0-9]*)\])?)*$'),
    constraint errored_code_returned_files_try_fk foreign key (production_id, run_id, producer, lost_reason)
        references productions (production_id, run_id, producer, lost_reason),
    constraint errored_code_returned_files_holder_fk foreign key (run_id, file_id)
        references run_file_holders (run_id, file_id)
);

create index errored_code_returned_files_by_run on errored_code_returned_files (run_id, production_id);
