-- Bootstrap the developer's account as the first admin. RDS sits in a private subnet
-- with no public access, so an ad-hoc UPDATE would need ECS exec or a bastion —
-- doing it inside a migration is simpler and runs once on the next task boot.
-- No-op if the row doesn't exist yet (e.g. fresh dev DB before register).
UPDATE users SET is_admin = TRUE WHERE email = '[scrubbed-email]';
