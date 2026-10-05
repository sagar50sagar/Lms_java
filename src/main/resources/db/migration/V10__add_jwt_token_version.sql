-- Stateless JWTs stayed valid after a password change or reset. The filter now compares this
-- counter with the token's claim, so bumping it invalidates every previously issued token.
ALTER TABLE users ADD COLUMN IF NOT EXISTS token_version INT NOT NULL DEFAULT 0;
