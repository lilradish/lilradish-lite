-- The directory, which this system reads and never writes. No key joins it to subjects either way:
-- somebody leaves the directory and stays the subject every act of theirs is recorded against.
create table people (
    user_id       text not null,
    display_name  text not null,
    display_name_folded text generated always as (search_fold(display_name)) stored,

    constraint people_pk primary key (user_id),
    -- The floors subjects puts on the same two values, for the reasons given there.
    constraint people_user_id_visible check (
        length(user_id) > 0 and user_id !~ '[\u0000-\u001f\u007f-\u009f]'),
    constraint people_user_id_bounded check (length(user_id) <= 256),
    constraint people_display_name_visible check (
        length(display_name) > 0 and display_name !~ '[\u0000-\u001f\u007f-\u009f]'),
    constraint people_display_name_bounded check (length(display_name) <= 256)
);
