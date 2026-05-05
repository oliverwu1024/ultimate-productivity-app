-- token_version is bumped any time we want to invalidate every JWT a user
-- holds (password change, password reset, account compromise). The middleware
-- compares this column to the `tv` claim on each request and rejects the token
-- if they differ.
ALTER TABLE users
ADD COLUMN token_version INTEGER NOT NULL DEFAULT 1;
