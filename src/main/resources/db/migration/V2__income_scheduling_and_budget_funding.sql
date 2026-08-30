-- Specification B: independently scheduled income, receipt ledger, and many-to-many funding allocations.
-- V1 is intentionally untouched. Legacy actual_amount values are converted once into receipt rows,
-- then cleared so income availability has a single source of truth.

ALTER TABLE income_entries ADD COLUMN expected_date date;
ALTER TABLE income_entries ADD COLUMN expected_time time;
ALTER TABLE income_entries ADD COLUMN time_zone varchar(60);
ALTER TABLE income_entries ADD COLUMN recurrence_rule varchar(300);
ALTER TABLE income_entries ADD COLUMN cancelled_at timestamptz;

-- V1 installed this function on three different row types using compound conditions.
-- Replace it before updating income_entries: PostgreSQL resolves every NEW-field reference
-- in a condition for the current trigger row, even when TG_TABLE_NAME would be false.
CREATE OR REPLACE FUNCTION wethrive_validate_finance_space() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME = 'income_entries' THEN
        IF (SELECT space_id FROM budget_months WHERE id = NEW.budget_month_id) <> NEW.space_id OR
           (SELECT space_id FROM income_types WHERE id = NEW.income_type_id) <> NEW.space_id THEN
            RAISE EXCEPTION 'income references must belong to the same space';
        END IF;
    ELSIF TG_TABLE_NAME = 'budget_items' THEN
        IF (SELECT space_id FROM budget_months WHERE id = NEW.budget_month_id) <> NEW.space_id OR
           (SELECT space_id FROM categories WHERE id = NEW.category_id) <> NEW.space_id THEN
            RAISE EXCEPTION 'budget-item references must belong to the same space';
        END IF;
    ELSIF TG_TABLE_NAME = 'spending_entries' THEN
        IF (SELECT space_id FROM budget_items WHERE id = NEW.budget_item_id) <> NEW.space_id THEN
            RAISE EXCEPTION 'spending and budget item must belong to the same space';
        END IF;
    ELSIF TG_TABLE_NAME = 'income_receipts' THEN
        IF (SELECT space_id FROM income_entries WHERE id = NEW.income_entry_id) <> NEW.space_id THEN
            RAISE EXCEPTION 'income receipt and income entry must belong to the same space';
        END IF;
    ELSIF TG_TABLE_NAME = 'budget_funding_allocations' THEN
        IF (SELECT space_id FROM budget_items WHERE id = NEW.budget_item_id) <> NEW.space_id OR
           (NEW.income_entry_id IS NOT NULL AND (
               (SELECT space_id FROM income_entries WHERE id = NEW.income_entry_id) <> NEW.space_id OR
               (SELECT budget_month_id FROM income_entries WHERE id = NEW.income_entry_id) <>
                   (SELECT budget_month_id FROM budget_items WHERE id = NEW.budget_item_id))) THEN
            RAISE EXCEPTION 'funding allocation references must belong to the same space';
        END IF;
    END IF;
    RETURN NEW;
END $$;

UPDATE income_entries i
SET expected_date = make_date(b.year, b.month, 1),
    time_zone = s.time_zone
FROM budget_months b
JOIN spaces s ON s.id = b.space_id
WHERE i.budget_month_id = b.id
  AND (i.expected_date IS NULL OR i.time_zone IS NULL);

ALTER TABLE income_entries ALTER COLUMN expected_date SET NOT NULL;
ALTER TABLE income_entries ALTER COLUMN time_zone SET NOT NULL;

CREATE TABLE income_receipts (
    id uuid PRIMARY KEY,
    space_id uuid NOT NULL REFERENCES spaces(id),
    income_entry_id uuid NOT NULL REFERENCES income_entries(id),
    amount numeric(19,2) NOT NULL CHECK (amount >= 0),
    received_at timestamptz NOT NULL,
    time_zone varchar(60) NOT NULL,
    notes varchar(2000),
    recorded_by_user_id uuid NOT NULL REFERENCES app_users(id),
    created_by_user_id uuid NOT NULL REFERENCES app_users(id),
    updated_by_user_id uuid NOT NULL REFERENCES app_users(id),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    deleted_at timestamptz
);

-- Reuse the income UUID in this separate table so server and IndexedDB migrations identify the
-- same synthetic receipt. This prevents a queued local migration from double-counting legacy cash.
INSERT INTO income_receipts (
    id, space_id, income_entry_id, amount, received_at, time_zone, notes,
    recorded_by_user_id, created_by_user_id, updated_by_user_id, created_at, updated_at, version
)
SELECT i.id, i.space_id, i.id, i.actual_amount,
       (i.expected_date + COALESCE(i.expected_time, time '12:00')) AT TIME ZONE i.time_zone,
       i.time_zone, 'Migrated from legacy actual amount',
       i.updated_by_user_id, i.created_by_user_id, i.updated_by_user_id, i.created_at, i.updated_at, i.version
FROM income_entries i
WHERE i.actual_amount IS NOT NULL;

UPDATE income_entries SET actual_amount = NULL WHERE actual_amount IS NOT NULL;

CREATE TABLE budget_funding_allocations (
    id uuid PRIMARY KEY,
    space_id uuid NOT NULL REFERENCES spaces(id),
    budget_item_id uuid NOT NULL REFERENCES budget_items(id),
    income_entry_id uuid REFERENCES income_entries(id),
    source_type varchar(30) NOT NULL CHECK (source_type IN ('INCOME_ENTRY','UNASSIGNED_FUNDS','EXTERNAL_FUNDS','ROLLOVER_FUNDS')),
    planned_amount numeric(19,2) NOT NULL DEFAULT 0 CHECK (planned_amount >= 0),
    confirmed_allocated_amount numeric(19,2) NOT NULL DEFAULT 0 CHECK (confirmed_allocated_amount >= 0),
    allocated_at timestamptz,
    time_zone varchar(60),
    notes varchar(2000),
    created_by_user_id uuid NOT NULL REFERENCES app_users(id),
    updated_by_user_id uuid NOT NULL REFERENCES app_users(id),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    deleted_at timestamptz,
    CHECK (planned_amount > 0 OR confirmed_allocated_amount > 0),
    CHECK ((source_type = 'INCOME_ENTRY' AND income_entry_id IS NOT NULL) OR
           (source_type <> 'INCOME_ENTRY' AND income_entry_id IS NULL)),
    CHECK (confirmed_allocated_amount = 0 OR
           (allocated_at IS NOT NULL AND time_zone IS NOT NULL AND btrim(time_zone) <> ''))
);

CREATE TRIGGER trg_income_receipt_space BEFORE INSERT OR UPDATE ON income_receipts
FOR EACH ROW EXECUTE FUNCTION wethrive_validate_finance_space();
CREATE TRIGGER trg_funding_allocation_space BEFORE INSERT OR UPDATE ON budget_funding_allocations
FOR EACH ROW EXECUTE FUNCTION wethrive_validate_finance_space();

CREATE INDEX ix_income_entries_schedule ON income_entries(space_id, expected_date) WHERE deleted_at IS NULL AND cancelled_at IS NULL;
CREATE INDEX ix_income_receipts_income ON income_receipts(income_entry_id, received_at) WHERE deleted_at IS NULL;
CREATE INDEX ix_funding_item ON budget_funding_allocations(budget_item_id, created_at) WHERE deleted_at IS NULL;
CREATE INDEX ix_funding_income ON budget_funding_allocations(income_entry_id, created_at) WHERE deleted_at IS NULL AND income_entry_id IS NOT NULL;
