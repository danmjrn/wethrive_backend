-- WeThrive PostgreSQL baseline. All money uses fixed scale; all server instants are UTC timestamptz.
CREATE TABLE app_users (
    id uuid PRIMARY KEY,
    email varchar(320) NOT NULL,
    normalized_email varchar(320) NOT NULL UNIQUE,
    password_hash varchar(100) NOT NULL,
    display_name varchar(120) NOT NULL,
    avatar_reference varchar(500),
    enabled boolean NOT NULL DEFAULT true,
    email_verified boolean NOT NULL DEFAULT false,
    password_changed_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    deleted_at timestamptz
);

CREATE TABLE devices (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES app_users(id),
    display_name varchar(120) NOT NULL,
    platform varchar(80),
    browser varchar(80),
    last_seen_at timestamptz,
    offline_access_enabled boolean NOT NULL DEFAULT false,
    offline_grant_expires_at timestamptz,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    deleted_at timestamptz
);

CREATE TABLE user_sessions (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES app_users(id),
    device_id uuid NOT NULL REFERENCES devices(id),
    access_token_hash varchar(64) NOT NULL UNIQUE,
    refresh_token_hash varchar(64) NOT NULL UNIQUE,
    previous_refresh_token_hash varchar(64),
    token_family_id uuid NOT NULL,
    issued_at timestamptz NOT NULL,
    access_expires_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    last_used_at timestamptz,
    reuse_detected_at timestamptz,
    user_agent_metadata varchar(255),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    deleted_at timestamptz
);

CREATE TABLE used_refresh_tokens (
    id uuid PRIMARY KEY,
    token_hash varchar(64) NOT NULL UNIQUE,
    session_id uuid NOT NULL REFERENCES user_sessions(id),
    token_family_id uuid NOT NULL,
    user_id uuid NOT NULL REFERENCES app_users(id),
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    deleted_at timestamptz
);

CREATE TABLE account_tokens (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES app_users(id),
    type varchar(30) NOT NULL CHECK (type IN ('EMAIL_VERIFICATION','PASSWORD_RESET')),
    token_hash varchar(64) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    deleted_at timestamptz
);

CREATE TABLE spaces (
    id uuid PRIMARY KEY,
    type varchar(20) NOT NULL CHECK (type IN ('PERSONAL','HOUSEHOLD')),
    name varchar(120) NOT NULL,
    slug varchar(160) NOT NULL UNIQUE,
    owner_user_id uuid NOT NULL REFERENCES app_users(id),
    currency_code varchar(3) NOT NULL,
    locale varchar(35) NOT NULL,
    time_zone varchar(60) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    deleted_at timestamptz
);

CREATE TABLE space_memberships (
    id uuid PRIMARY KEY,
    space_id uuid NOT NULL REFERENCES spaces(id),
    user_id uuid NOT NULL REFERENCES app_users(id),
    role varchar(30) NOT NULL CHECK (role IN ('OWNER','ADMIN','FINANCE_EDITOR','MEMBER','VIEWER')),
    status varchar(20) NOT NULL CHECK (status IN ('ACTIVE','LEFT','REMOVED')),
    joined_at timestamptz,
    invited_by_user_id uuid REFERENCES app_users(id),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    deleted_at timestamptz
);
CREATE UNIQUE INDEX ux_space_memberships_active_user ON space_memberships(space_id, user_id) WHERE deleted_at IS NULL;

CREATE TABLE invitations (
    id uuid PRIMARY KEY,
    space_id uuid NOT NULL REFERENCES spaces(id),
    email varchar(320) NOT NULL,
    normalized_email varchar(320) NOT NULL,
    role varchar(30) NOT NULL CHECK (role IN ('ADMIN','FINANCE_EDITOR','MEMBER','VIEWER')),
    token_hash varchar(64) NOT NULL UNIQUE,
    invited_by_user_id uuid NOT NULL REFERENCES app_users(id),
    expires_at timestamptz NOT NULL,
    accepted_at timestamptz,
    declined_at timestamptz,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    deleted_at timestamptz
);

CREATE TABLE user_settings (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL UNIQUE REFERENCES app_users(id),
    currency_code varchar(3) NOT NULL,
    locale varchar(35) NOT NULL,
    time_zone varchar(60) NOT NULL,
    week_start_day integer NOT NULL DEFAULT 1 CHECK (week_start_day BETWEEN 1 AND 7),
    default_tithe_enabled boolean NOT NULL DEFAULT false,
    default_tithe_rate numeric(7,4) NOT NULL DEFAULT 0.1000 CHECK (default_tithe_rate BETWEEN 0 AND 1),
    warning_threshold numeric(7,4) NOT NULL DEFAULT 0.7500 CHECK (warning_threshold >= 0),
    critical_threshold numeric(7,4) NOT NULL DEFAULT 0.9000 CHECK (critical_threshold > warning_threshold),
    theme varchar(20) NOT NULL DEFAULT 'SYSTEM' CHECK (theme IN ('LIGHT','DARK','SYSTEM')),
    notifications_enabled boolean NOT NULL DEFAULT false,
    detailed_notifications_enabled boolean NOT NULL DEFAULT false,
    default_space_id uuid REFERENCES spaces(id),
    auto_lock_minutes integer NOT NULL DEFAULT 5 CHECK (auto_lock_minutes BETWEEN 1 AND 1440),
    onboarding_complete boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    deleted_at timestamptz
);

CREATE TABLE categories (
    id uuid PRIMARY KEY,
    space_id uuid NOT NULL REFERENCES spaces(id),
    created_by_user_id uuid NOT NULL REFERENCES app_users(id),
    updated_by_user_id uuid NOT NULL REFERENCES app_users(id),
    name varchar(100) NOT NULL,
    icon varchar(60),
    sort_order integer NOT NULL,
    archived boolean NOT NULL DEFAULT false,
    system_default boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    deleted_at timestamptz
);
CREATE UNIQUE INDEX ux_categories_space_name ON categories(space_id, lower(name)) WHERE deleted_at IS NULL;

CREATE TABLE income_types (
    id uuid PRIMARY KEY,
    space_id uuid NOT NULL REFERENCES spaces(id),
    created_by_user_id uuid NOT NULL REFERENCES app_users(id),
    updated_by_user_id uuid NOT NULL REFERENCES app_users(id),
    name varchar(100) NOT NULL,
    archived boolean NOT NULL DEFAULT false,
    system_default boolean NOT NULL DEFAULT false,
    sort_order integer NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    deleted_at timestamptz
);
CREATE UNIQUE INDEX ux_income_types_space_name ON income_types(space_id, lower(name)) WHERE deleted_at IS NULL;

CREATE TABLE budget_months (
    id uuid PRIMARY KEY,
    space_id uuid NOT NULL REFERENCES spaces(id),
    created_by_user_id uuid NOT NULL REFERENCES app_users(id),
    updated_by_user_id uuid NOT NULL REFERENCES app_users(id),
    year integer NOT NULL CHECK (year BETWEEN 2000 AND 2200),
    month integer NOT NULL CHECK (month BETWEEN 1 AND 12),
    name varchar(120) NOT NULL,
    status varchar(20) NOT NULL CHECK (status IN ('DRAFT','ACTIVE','CLOSED')),
    notes varchar(2000),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    deleted_at timestamptz
);
CREATE UNIQUE INDEX ux_budget_months_space_period ON budget_months(space_id, year, month) WHERE deleted_at IS NULL;

CREATE TABLE income_entries (
    id uuid PRIMARY KEY,
    space_id uuid NOT NULL REFERENCES spaces(id),
    created_by_user_id uuid NOT NULL REFERENCES app_users(id),
    updated_by_user_id uuid NOT NULL REFERENCES app_users(id),
    budget_month_id uuid NOT NULL REFERENCES budget_months(id),
    source_name varchar(160) NOT NULL,
    income_type_id uuid NOT NULL REFERENCES income_types(id),
    expected_amount numeric(19,2) CHECK (expected_amount >= 0),
    actual_amount numeric(19,2) CHECK (actual_amount >= 0),
    tithe_enabled boolean NOT NULL DEFAULT false,
    tithe_rate numeric(7,4) NOT NULL CHECK (tithe_rate BETWEEN 0 AND 1),
    tithe_amount_override numeric(19,2) CHECK (tithe_amount_override >= 0),
    recurring boolean NOT NULL DEFAULT false,
    notes varchar(2000),
    sort_order integer NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    deleted_at timestamptz
);

CREATE TABLE budget_items (
    id uuid PRIMARY KEY,
    space_id uuid NOT NULL REFERENCES spaces(id),
    created_by_user_id uuid NOT NULL REFERENCES app_users(id),
    updated_by_user_id uuid NOT NULL REFERENCES app_users(id),
    budget_month_id uuid NOT NULL REFERENCES budget_months(id),
    name varchar(160) NOT NULL,
    category_id uuid NOT NULL REFERENCES categories(id),
    planned_amount numeric(19,2) NOT NULL CHECK (planned_amount >= 0),
    tracked boolean NOT NULL DEFAULT true,
    item_type varchar(20) NOT NULL CHECK (item_type IN ('PLANNED','UNBUDGETED')),
    recurring boolean NOT NULL DEFAULT false,
    rollover_enabled boolean NOT NULL DEFAULT false,
    notes varchar(2000),
    sort_order integer NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    deleted_at timestamptz
);

CREATE TABLE spending_entries (
    id uuid PRIMARY KEY,
    space_id uuid NOT NULL REFERENCES spaces(id),
    created_by_user_id uuid NOT NULL REFERENCES app_users(id),
    updated_by_user_id uuid NOT NULL REFERENCES app_users(id),
    budget_item_id uuid NOT NULL REFERENCES budget_items(id),
    transaction_type varchar(20) NOT NULL CHECK (transaction_type IN ('EXPENSE','REFUND')),
    title varchar(180) NOT NULL,
    amount numeric(19,2) NOT NULL CHECK (amount > 0),
    spent_at timestamptz NOT NULL,
    user_selected_date date NOT NULL,
    user_selected_time time NOT NULL,
    time_zone varchar(60) NOT NULL,
    payment_method varchar(80),
    merchant varchar(160),
    notes varchar(2000),
    spent_by_user_id uuid REFERENCES app_users(id),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    deleted_at timestamptz
);

CREATE OR REPLACE FUNCTION wethrive_validate_finance_space() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME = 'income_entries' AND (
        (SELECT space_id FROM budget_months WHERE id = NEW.budget_month_id) <> NEW.space_id OR
        (SELECT space_id FROM income_types WHERE id = NEW.income_type_id) <> NEW.space_id) THEN
        RAISE EXCEPTION 'income references must belong to the same space';
    ELSIF TG_TABLE_NAME = 'budget_items' AND (
        (SELECT space_id FROM budget_months WHERE id = NEW.budget_month_id) <> NEW.space_id OR
        (SELECT space_id FROM categories WHERE id = NEW.category_id) <> NEW.space_id) THEN
        RAISE EXCEPTION 'budget-item references must belong to the same space';
    ELSIF TG_TABLE_NAME = 'spending_entries' AND
        (SELECT space_id FROM budget_items WHERE id = NEW.budget_item_id) <> NEW.space_id THEN
        RAISE EXCEPTION 'spending and budget item must belong to the same space';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_income_space BEFORE INSERT OR UPDATE ON income_entries FOR EACH ROW EXECUTE FUNCTION wethrive_validate_finance_space();
CREATE TRIGGER trg_budget_item_space BEFORE INSERT OR UPDATE ON budget_items FOR EACH ROW EXECUTE FUNCTION wethrive_validate_finance_space();
CREATE TRIGGER trg_spending_space BEFORE INSERT OR UPDATE ON spending_entries FOR EACH ROW EXECUTE FUNCTION wethrive_validate_finance_space();

CREATE TABLE reminder_preferences (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES app_users(id),
    space_id uuid REFERENCES spaces(id),
    reminder_type varchar(40) NOT NULL CHECK (reminder_type IN ('SPENDING_CHECK_IN','MONTHLY_BUDGET_SETUP','BUDGET_THRESHOLD','HOUSEHOLD_ACTIVITY','INVITATION','SYNC_CONFLICT')),
    enabled boolean NOT NULL DEFAULT true,
    frequency_type varchar(30) NOT NULL CHECK (frequency_type IN ('DAILY','WEEKLY','SELECTED_DAYS','INTERVAL_DAYS','MONTHLY','CONDITIONAL')),
    interval_value integer,
    selected_weekdays varchar(80),
    local_time time NOT NULL,
    time_zone varchar(60) NOT NULL,
    day_of_month integer,
    days_before_month_end integer,
    days_after_month_start integer,
    repeat_until_completed boolean NOT NULL DEFAULT false,
    quiet_hours_start time,
    quiet_hours_end time,
    push_enabled boolean NOT NULL DEFAULT false,
    in_app_enabled boolean NOT NULL DEFAULT true,
    detailed_content_enabled boolean NOT NULL DEFAULT false,
    suppress_after_spending boolean NOT NULL DEFAULT true,
    pause_until timestamptz,
    next_run_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    deleted_at timestamptz,
    CHECK (interval_value IS NULL OR interval_value BETWEEN 1 AND 365),
    CHECK (day_of_month IS NULL OR day_of_month BETWEEN 1 AND 31)
);

CREATE TABLE reminder_occurrences (
    id uuid PRIMARY KEY,
    reminder_preference_id uuid NOT NULL REFERENCES reminder_preferences(id),
    user_id uuid NOT NULL REFERENCES app_users(id),
    space_id uuid REFERENCES spaces(id),
    target_period varchar(30) NOT NULL,
    scheduled_for timestamptz NOT NULL,
    delivered_at timestamptz,
    acknowledged_at timestamptz,
    skipped_at timestamptz,
    status varchar(20) NOT NULL CHECK (status IN ('PENDING','DELIVERED','ACKNOWLEDGED','SKIPPED','FAILED')),
    idempotency_key varchar(180) NOT NULL UNIQUE,
    failure_reason varchar(300),
    title varchar(160) NOT NULL,
    body varchar(300) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    deleted_at timestamptz
);

CREATE TABLE push_subscriptions (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES app_users(id),
    device_id uuid NOT NULL REFERENCES devices(id),
    endpoint_encrypted varchar(4096) NOT NULL,
    endpoint_hash varchar(64) NOT NULL UNIQUE,
    public_key_encrypted varchar(2048) NOT NULL,
    authentication_secret_encrypted varchar(2048) NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    expiration_time timestamptz,
    last_success_at timestamptz,
    last_failure_at timestamptz,
    failure_count integer NOT NULL DEFAULT 0,
    revoked_at timestamptz,
    detailed_content_enabled boolean NOT NULL DEFAULT false,
    hide_amounts boolean NOT NULL DEFAULT true,
    hide_space_names boolean NOT NULL DEFAULT true,
    hide_budget_item_names boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    deleted_at timestamptz
);

CREATE TABLE push_delivery_attempts (
    id uuid PRIMARY KEY,
    reminder_occurrence_id uuid REFERENCES reminder_occurrences(id),
    user_id uuid NOT NULL REFERENCES app_users(id),
    subscription_id uuid NOT NULL REFERENCES push_subscriptions(id),
    attempt_number integer NOT NULL CHECK (attempt_number > 0),
    idempotency_key varchar(180) NOT NULL UNIQUE,
    status varchar(30) NOT NULL,
    next_attempt_at timestamptz NOT NULL,
    attempted_at timestamptz,
    response_status integer,
    failure_reason varchar(100),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    deleted_at timestamptz
);

CREATE TABLE sync_operations (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES app_users(id),
    device_id uuid NOT NULL REFERENCES devices(id),
    space_id uuid NOT NULL REFERENCES spaces(id),
    module_key varchar(60) NOT NULL,
    entity_type varchar(60) NOT NULL,
    entity_id uuid NOT NULL,
    operation_type varchar(20) NOT NULL CHECK (operation_type IN ('CREATE','UPDATE','DELETE','RESTORE')),
    payload text NOT NULL,
    base_version bigint,
    client_timestamp timestamptz NOT NULL,
    retry_count integer NOT NULL DEFAULT 0,
    last_error varchar(120),
    status varchar(20) NOT NULL CHECK (status IN ('APPLIED','DUPLICATE','CONFLICT','REJECTED')),
    result_version bigint,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    deleted_at timestamptz
);

CREATE TABLE server_change_log (
    sequence_id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    space_id uuid NOT NULL REFERENCES spaces(id),
    entity_type varchar(60) NOT NULL,
    entity_id uuid NOT NULL,
    operation_type varchar(20) NOT NULL CHECK (operation_type IN ('CREATE','UPDATE','DELETE','RESTORE')),
    entity_version bigint NOT NULL,
    changed_by_user_id uuid NOT NULL REFERENCES app_users(id),
    changed_at timestamptz NOT NULL,
    payload text,
    tombstone boolean NOT NULL DEFAULT false
);

CREATE TABLE audit_events (
    id uuid PRIMARY KEY,
    actor_user_id uuid REFERENCES app_users(id),
    space_id uuid REFERENCES spaces(id),
    action varchar(100) NOT NULL,
    entity_type varchar(60),
    entity_id uuid,
    result varchar(20) NOT NULL CHECK (result IN ('SUCCESS','DENIED','FAILURE')),
    metadata varchar(500),
    created_at timestamptz NOT NULL
);

CREATE TABLE reference_category_definitions (
    id smallint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    name varchar(100) NOT NULL UNIQUE,
    sort_order integer NOT NULL UNIQUE
);
CREATE TABLE reference_income_type_definitions (
    id smallint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    name varchar(100) NOT NULL UNIQUE,
    sort_order integer NOT NULL UNIQUE
);

INSERT INTO reference_category_definitions(name, sort_order) VALUES
('Bank Charges',1),('Clothing & Accessories',2),('Contracts',3),('Contribution',4),('Debt Payoff',5),
('Education',6),('Emergency Funds',7),('Entertainment',8),('Financial Aid',9),('Groceries',10),
('Home',11),('Insurance',12),('Investments',13),('Medical & Health',14),('Miscellaneous',15),
('Personal Development',16),('Savings',17),('Self Care',18),('Stokvel Contribution',19),
('Tithe & Offering',20),('Transportation',21);
INSERT INTO reference_income_type_definitions(name, sort_order) VALUES
('Salary',1),('Gift',2),('Side Hustle',3),('Interest',4),('Borrowed Funds',5),('Debt Recovery',6),('Stokvel Payment',7);

CREATE INDEX ix_devices_user_active ON devices(user_id, revoked_at);
CREATE INDEX ix_sessions_user_active ON user_sessions(user_id, revoked_at, last_used_at DESC);
CREATE INDEX ix_sessions_family ON user_sessions(token_family_id);
CREATE INDEX ix_used_refresh_expiry ON used_refresh_tokens(expires_at);
CREATE INDEX ix_memberships_user_status ON space_memberships(user_id, status) WHERE deleted_at IS NULL;
CREATE INDEX ix_invitations_space ON invitations(space_id, created_at DESC);
CREATE INDEX ix_budget_items_budget ON budget_items(budget_month_id, sort_order) WHERE deleted_at IS NULL;
CREATE INDEX ix_income_entries_budget ON income_entries(budget_month_id, sort_order) WHERE deleted_at IS NULL;
CREATE INDEX ix_spending_space_date ON spending_entries(space_id, spent_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX ix_spending_item_date ON spending_entries(budget_item_id, spent_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX ix_reminder_due ON reminder_preferences(next_run_at) WHERE enabled = true AND deleted_at IS NULL;
CREATE INDEX ix_push_attempt_retry ON push_delivery_attempts(next_attempt_at) WHERE status = 'RETRY_PENDING';
CREATE INDEX ix_change_pull ON server_change_log(space_id, sequence_id);
CREATE INDEX ix_change_entity ON server_change_log(entity_type, entity_id, sequence_id DESC);
CREATE INDEX ix_audit_created ON audit_events(created_at DESC);
