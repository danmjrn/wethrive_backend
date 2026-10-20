ALTER TABLE app_users ADD COLUMN application_role varchar(20) NOT NULL DEFAULT 'USER'
    CHECK (application_role IN ('USER', 'SUPER_ADMIN'));

-- A permanent singleton serializes first-run initialization across server instances.
-- Existing users are never silently promoted: the operator must claim the owner
-- using the bootstrap secret and, for an existing account, its current password.
CREATE TABLE application_initialization (
    id integer PRIMARY KEY CHECK (id = 1),
    completed_at timestamptz,
    owner_user_id uuid REFERENCES app_users(id)
);
INSERT INTO application_initialization(id) VALUES (1);

CREATE TABLE application_invitations (
    id uuid PRIMARY KEY,
    email varchar(320) NOT NULL,
    normalized_email varchar(320) NOT NULL,
    token_hash varchar(64) NOT NULL UNIQUE,
    invited_by_user_id uuid NOT NULL REFERENCES app_users(id),
    expires_at timestamptz NOT NULL,
    accepted_at timestamptz,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    deleted_at timestamptz
);
