-- Its own file and first in order: a label added in a transaction cannot be used until it commits, and each
-- repeatable migration commits on its own, so the library seed after it may name the label.
-- DB-SPECIFIC: alter type uses PostgreSQL's syntax and semantics.
alter type code_step add value if not exists 'stamp_reference';
