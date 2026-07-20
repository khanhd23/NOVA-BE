CREATE TABLE IF NOT EXISTS accounts (
    user_id VARCHAR(64) PRIMARY KEY,
    public_id VARCHAR(16) UNIQUE,
    provider_key VARCHAR(160) NOT NULL UNIQUE,
    display_name VARCHAR(120) NOT NULL,
    username VARCHAR(120) NOT NULL,
    bio TEXT NOT NULL,
    avatar_url TEXT NOT NULL,
    featured_photos_json TEXT NOT NULL,
    interests_json TEXT NOT NULL,
    linked_provider_keys_json TEXT NOT NULL,
    age INT NOT NULL,
    city VARCHAR(120) NOT NULL,
    gender VARCHAR(32) NOT NULL DEFAULT 'Not specified',
    verified BOOLEAN NOT NULL,
    online BOOLEAN NOT NULL,
    premium BOOLEAN NOT NULL,
    vip_tier_id VARCHAR(64),
    vip_tier_name VARCHAR(120),
    vip_expires_at TIMESTAMP NULL,
    diamond_balance BIGINT NOT NULL,
    onboarding_complete BOOLEAN NOT NULL,
    profile_complete BOOLEAN NOT NULL,
    distance_km INT NULL,
    settings_json TEXT NOT NULL,
    stats_json TEXT NOT NULL,
    badges_json TEXT NOT NULL,
    wallet_json TEXT NOT NULL,
    entitlements_json TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE accounts ADD COLUMN IF NOT EXISTS public_id VARCHAR(16);
ALTER TABLE accounts ADD COLUMN IF NOT EXISTS gender VARCHAR(32) NOT NULL DEFAULT 'Not specified';

CREATE INDEX IF NOT EXISTS idx_accounts_provider_key ON accounts(provider_key);
CREATE INDEX IF NOT EXISTS idx_accounts_username ON accounts(username);
CREATE INDEX IF NOT EXISTS idx_accounts_public_id ON accounts(public_id);

CREATE TABLE IF NOT EXISTS auth_sessions (
    session_id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    provider VARCHAR(40) NOT NULL,
    issued_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    refresh_expires_at TIMESTAMP NOT NULL,
    access_token VARCHAR(128) NOT NULL UNIQUE,
    refresh_token VARCHAR(128) NOT NULL UNIQUE,
    roles_json TEXT NOT NULL,
    active BOOLEAN NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_auth_sessions_user_id ON auth_sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_auth_sessions_access_token ON auth_sessions(access_token);
CREATE INDEX IF NOT EXISTS idx_auth_sessions_refresh_token ON auth_sessions(refresh_token);

CREATE TABLE IF NOT EXISTS device_tokens (
    token VARCHAR(512) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    platform VARCHAR(32) NOT NULL,
    device_id VARCHAR(128) NOT NULL,
    app_version VARCHAR(32) NOT NULL,
    registered_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_device_tokens_user_id ON device_tokens(user_id);

CREATE TABLE IF NOT EXISTS module_state (
    state_key VARCHAR(120) PRIMARY KEY,
    state_json TEXT NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS profile_follows (
    follower_user_id VARCHAR(64) NOT NULL,
    followee_user_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (follower_user_id, followee_user_id)
);

CREATE INDEX IF NOT EXISTS idx_profile_follows_follower ON profile_follows(follower_user_id);
CREATE INDEX IF NOT EXISTS idx_profile_follows_followee ON profile_follows(followee_user_id);
