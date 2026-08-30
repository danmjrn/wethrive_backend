-- Durable, synchronized acknowledgements for reminder review periods.
-- Multiple devices may acknowledge the same day independently; generation only needs existence.

CREATE TABLE reminder_period_reviews (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES app_users(id),
    space_id uuid NOT NULL REFERENCES spaces(id),
    period_date date NOT NULL,
    reviewed_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    deleted_at timestamptz
);

CREATE INDEX ix_reminder_period_review_lookup
    ON reminder_period_reviews(user_id, space_id, period_date)
    WHERE deleted_at IS NULL;

CREATE INDEX ix_reminder_period_review_user_date
    ON reminder_period_reviews(user_id, period_date)
    WHERE deleted_at IS NULL;
