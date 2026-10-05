-- SMTP used to run inside the user-creation transaction, so a slow provider held a pooled DB
-- connection for the whole handshake and requests failed as soon as several admins worked at once.
-- Requests now only write a row here; a background dispatcher owns the network.
CREATE TABLE IF NOT EXISTS email_outbox (
    id BIGSERIAL PRIMARY KEY,
    to_email VARCHAR(255) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    body_text TEXT NOT NULL,
    purpose VARCHAR(40) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'pending',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    claimed_at TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at TIMESTAMPTZ,
    CONSTRAINT email_outbox_status CHECK (status IN ('pending','sending','sent','failed'))
);

-- The dispatcher polls due rows only, so the partial index keeps that read cheap as history grows.
CREATE INDEX IF NOT EXISTS idx_email_outbox_due
    ON email_outbox(next_attempt_at)
    WHERE status IN ('pending','sending');
