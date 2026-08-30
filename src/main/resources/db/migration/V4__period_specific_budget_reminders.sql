-- Explicit, versioned reminder schedules for individual target budget periods.
-- V1/V2 remain immutable; deletion is soft and no cascade may erase reminder history.

CREATE TABLE budget_reminder_schedules (
    id uuid PRIMARY KEY,
    reminder_preference_id uuid NOT NULL REFERENCES reminder_preferences(id),
    user_id uuid NOT NULL REFERENCES app_users(id),
    space_id uuid NOT NULL REFERENCES spaces(id),
    target_year integer NOT NULL CHECK (target_year BETWEEN 2000 AND 2200),
    target_month integer NOT NULL CHECK (target_month BETWEEN 1 AND 12),
    initial_reminder_date date NOT NULL CHECK (
        initial_reminder_date BETWEEN date '1999-01-01' AND date '2201-12-31'
    ),
    local_time time NOT NULL,
    time_zone varchar(60) NOT NULL CHECK (btrim(time_zone) <> ''),
    repeat_until_budget_exists boolean NOT NULL DEFAULT true,
    repeat_interval_days integer NOT NULL DEFAULT 1 CHECK (repeat_interval_days BETWEEN 1 AND 365),
    enabled boolean NOT NULL DEFAULT true,
    next_run_at timestamptz NOT NULL,
    completed_at timestamptz,
    created_by_user_id uuid NOT NULL REFERENCES app_users(id),
    updated_by_user_id uuid NOT NULL REFERENCES app_users(id),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    deleted_at timestamptz
);

CREATE UNIQUE INDEX ux_budget_reminder_schedule_target
    ON budget_reminder_schedules(user_id, space_id, target_year, target_month)
    WHERE deleted_at IS NULL;
CREATE INDEX ix_budget_reminder_schedule_preference
    ON budget_reminder_schedules(reminder_preference_id, target_year, target_month)
    WHERE deleted_at IS NULL;
CREATE INDEX ix_budget_reminder_schedule_due
    ON budget_reminder_schedules(next_run_at)
    WHERE enabled = true AND deleted_at IS NULL;

CREATE OR REPLACE FUNCTION wethrive_validate_budget_reminder_schedule() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    preference_user uuid;
    preference_space uuid;
    preference_type varchar(40);
BEGIN
    SELECT user_id, space_id, reminder_type
      INTO preference_user, preference_space, preference_type
      FROM reminder_preferences
     WHERE id = NEW.reminder_preference_id AND deleted_at IS NULL;

    IF preference_user IS NULL OR preference_user <> NEW.user_id OR
       preference_space IS DISTINCT FROM NEW.space_id OR
       preference_type <> 'MONTHLY_BUDGET_SETUP' THEN
        RAISE EXCEPTION 'budget reminder schedule must reference the same user, space, and monthly preference';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_budget_reminder_schedule_owner
BEFORE INSERT OR UPDATE ON budget_reminder_schedules
FOR EACH ROW EXECUTE FUNCTION wethrive_validate_budget_reminder_schedule();

ALTER TABLE reminder_occurrences
    ADD COLUMN budget_reminder_schedule_id uuid REFERENCES budget_reminder_schedules(id);
CREATE INDEX ix_reminder_occurrence_budget_schedule
    ON reminder_occurrences(budget_reminder_schedule_id, scheduled_for)
    WHERE budget_reminder_schedule_id IS NOT NULL AND deleted_at IS NULL;
