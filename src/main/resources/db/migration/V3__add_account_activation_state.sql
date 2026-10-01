ALTER TABLE users
    ADD COLUMN IF NOT EXISTS password_setup_required BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_users_password_setup_required
    ON users (password_setup_required)
    WHERE password_setup_required = TRUE;
