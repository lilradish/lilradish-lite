-- Development only, and repeatable for the reasons the pool seed gives. The code step the development source set
-- holds, by the name the library seed publishes and names it under.
--
-- Its own file and first in order: a label added in a transaction cannot be used until it commits, and each
-- repeatable migration commits on its own, so the library seed after it may name the label.
alter type code_step add value if not exists 'stamp_reference';
