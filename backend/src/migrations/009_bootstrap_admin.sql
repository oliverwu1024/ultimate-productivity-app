-- Admin bootstrap is performed out-of-band via a one-shot SQL command
-- (ECS exec into the task or psql via bastion). The previous version of
-- this migration hard-coded an email address into the source tree, which
-- would become public when the repo is published. Keeping the file as a
-- no-op preserves migration ordering / version numbering across envs.
SELECT 1;
