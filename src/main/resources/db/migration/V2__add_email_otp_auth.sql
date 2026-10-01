CREATE TABLE email_otp_tokens (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(150) NOT NULL,
    purpose VARCHAR(30) NOT NULL CHECK (purpose IN ('login', 'password_setup', 'password_reset')),
    code_hash VARCHAR(255) NOT NULL,
    attempts SMALLINT NOT NULL DEFAULT 0,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_email_otp_tokens_lookup
    ON email_otp_tokens (email, purpose, created_at DESC);
