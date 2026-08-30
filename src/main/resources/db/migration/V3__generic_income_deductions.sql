-- Generic planned deductions and receipt-level reviewed deductions.
-- V1/V2 remain immutable. Legacy Tithe configuration is retained as a compatibility
-- projection on income_entries, while these ledgers become the canonical source of truth.

CREATE TABLE income_deductions (
    id uuid PRIMARY KEY,
    space_id uuid NOT NULL REFERENCES spaces(id),
    income_entry_id uuid NOT NULL REFERENCES income_entries(id),
    name varchar(160) NOT NULL CHECK (btrim(name) <> ''),
    deduction_type varchar(20) NOT NULL CHECK (deduction_type IN ('PERCENTAGE','FIXED')),
    percentage_rate numeric(7,4),
    fixed_amount numeric(19,2),
    notes varchar(2000),
    sort_order integer NOT NULL DEFAULT 0 CHECK (sort_order >= 0),
    legacy_tithe boolean NOT NULL DEFAULT false,
    created_by_user_id uuid NOT NULL REFERENCES app_users(id),
    updated_by_user_id uuid NOT NULL REFERENCES app_users(id),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    deleted_at timestamptz,
    CHECK (
        (deduction_type = 'PERCENTAGE' AND percentage_rate IS NOT NULL
            AND percentage_rate BETWEEN 0 AND 1 AND fixed_amount IS NULL) OR
        (deduction_type = 'FIXED' AND fixed_amount IS NOT NULL
            AND fixed_amount >= 0 AND percentage_rate IS NULL)
    )
);

CREATE TABLE income_receipt_deductions (
    id uuid PRIMARY KEY,
    space_id uuid NOT NULL REFERENCES spaces(id),
    income_receipt_id uuid NOT NULL REFERENCES income_receipts(id),
    income_deduction_id uuid NOT NULL REFERENCES income_deductions(id),
    name_snapshot varchar(160) NOT NULL CHECK (btrim(name_snapshot) <> ''),
    actual_amount numeric(19,2) NOT NULL CHECK (actual_amount >= 0),
    created_by_user_id uuid NOT NULL REFERENCES app_users(id),
    updated_by_user_id uuid NOT NULL REFERENCES app_users(id),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    deleted_at timestamptz
);

CREATE UNIQUE INDEX ux_income_deductions_legacy_tithe
    ON income_deductions(income_entry_id)
    WHERE legacy_tithe = true AND deleted_at IS NULL;
CREATE INDEX ix_income_deductions_income
    ON income_deductions(income_entry_id, sort_order, created_at)
    WHERE deleted_at IS NULL;
CREATE INDEX ix_income_deductions_space_history
    ON income_deductions(space_id, created_at, id);
CREATE UNIQUE INDEX ux_income_receipt_deduction_line
    ON income_receipt_deductions(income_receipt_id, income_deduction_id)
    WHERE deleted_at IS NULL;
CREATE INDEX ix_income_receipt_deductions_receipt
    ON income_receipt_deductions(income_receipt_id, created_at)
    WHERE deleted_at IS NULL;
CREATE INDEX ix_income_receipt_deductions_planned
    ON income_receipt_deductions(income_deduction_id, created_at)
    WHERE deleted_at IS NULL;
CREATE INDEX ix_income_receipt_deductions_space_history
    ON income_receipt_deductions(space_id, created_at, id);

-- Extend the existing polymorphic finance-space guard rather than installing a second
-- divergent source of truth for tenant ownership.
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
    ELSIF TG_TABLE_NAME = 'income_deductions' THEN
        IF (SELECT space_id FROM income_entries WHERE id = NEW.income_entry_id) <> NEW.space_id THEN
            RAISE EXCEPTION 'income deduction and income entry must belong to the same space';
        END IF;
    ELSIF TG_TABLE_NAME = 'income_receipt_deductions' THEN
        IF (SELECT space_id FROM income_receipts WHERE id = NEW.income_receipt_id) <> NEW.space_id OR
           (SELECT space_id FROM income_deductions WHERE id = NEW.income_deduction_id) <> NEW.space_id OR
           (SELECT income_entry_id FROM income_receipts WHERE id = NEW.income_receipt_id) <>
               (SELECT income_entry_id FROM income_deductions WHERE id = NEW.income_deduction_id) THEN
            RAISE EXCEPTION 'receipt deduction references must belong to the same income and space';
        END IF;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_income_deduction_space
BEFORE INSERT OR UPDATE ON income_deductions
FOR EACH ROW EXECUTE FUNCTION wethrive_validate_finance_space();
CREATE TRIGGER trg_income_receipt_deduction_space
BEFORE INSERT OR UPDATE ON income_receipt_deductions
FOR EACH ROW EXECUTE FUNCTION wethrive_validate_finance_space();

-- Database-level aggregate guards complement service locking and protect direct/imported writes.
CREATE OR REPLACE FUNCTION wethrive_validate_income_deduction_total() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    gross numeric(19,2);
    existing_total numeric;
    candidate numeric;
BEGIN
    IF NEW.deleted_at IS NOT NULL THEN RETURN NEW; END IF;
    SELECT expected_amount INTO gross FROM income_entries WHERE id = NEW.income_entry_id;
    SELECT COALESCE(SUM(CASE WHEN deduction_type = 'PERCENTAGE'
                         THEN gross * percentage_rate ELSE fixed_amount END), 0)
      INTO existing_total
      FROM income_deductions
     WHERE income_entry_id = NEW.income_entry_id AND deleted_at IS NULL AND id <> NEW.id;
    candidate := CASE WHEN NEW.deduction_type = 'PERCENTAGE'
                      THEN gross * NEW.percentage_rate ELSE NEW.fixed_amount END;
    IF existing_total + candidate > gross THEN
        RAISE EXCEPTION 'planned deductions cannot exceed expected gross income';
    END IF;
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION wethrive_validate_income_gross_against_deductions() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    deduction_total numeric;
BEGIN
    SELECT COALESCE(SUM(CASE WHEN deduction_type = 'PERCENTAGE'
                         THEN NEW.expected_amount * percentage_rate ELSE fixed_amount END), 0)
      INTO deduction_total
      FROM income_deductions
     WHERE income_entry_id = NEW.id AND deleted_at IS NULL;
    IF deduction_total > NEW.expected_amount THEN
        RAISE EXCEPTION 'expected gross income cannot be below planned deductions';
    END IF;
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION wethrive_validate_receipt_deduction_total() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    gross numeric(19,2);
    existing_total numeric;
BEGIN
    IF NEW.deleted_at IS NOT NULL THEN RETURN NEW; END IF;
    SELECT amount INTO gross FROM income_receipts WHERE id = NEW.income_receipt_id;
    SELECT COALESCE(SUM(actual_amount), 0)
      INTO existing_total
      FROM income_receipt_deductions
     WHERE income_receipt_id = NEW.income_receipt_id AND deleted_at IS NULL AND id <> NEW.id;
    IF existing_total + NEW.actual_amount > gross THEN
        RAISE EXCEPTION 'receipt deductions cannot exceed receipt gross amount';
    END IF;
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION wethrive_validate_receipt_gross_against_deductions() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    deduction_total numeric(19,2);
BEGIN
    SELECT COALESCE(SUM(actual_amount), 0)
      INTO deduction_total
      FROM income_receipt_deductions
     WHERE income_receipt_id = NEW.id AND deleted_at IS NULL;
    IF deduction_total > NEW.amount THEN
        RAISE EXCEPTION 'receipt gross amount cannot be below its actual deductions';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_income_deduction_total
BEFORE INSERT OR UPDATE ON income_deductions
FOR EACH ROW EXECUTE FUNCTION wethrive_validate_income_deduction_total();
CREATE CONSTRAINT TRIGGER trg_income_gross_deduction_total
AFTER UPDATE ON income_entries
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION wethrive_validate_income_gross_against_deductions();
CREATE TRIGGER trg_income_receipt_deduction_total
BEFORE INSERT OR UPDATE ON income_receipt_deductions
FOR EACH ROW EXECUTE FUNCTION wethrive_validate_receipt_deduction_total();
CREATE CONSTRAINT TRIGGER trg_income_receipt_gross_deduction_total
AFTER UPDATE ON income_receipts
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION wethrive_validate_receipt_gross_against_deductions();

-- V2 permitted zero-valued receipts. They do not represent received cash and make deduction
-- apportionment ambiguous, so new writes require a positive gross amount.
ALTER TABLE income_receipts DROP CONSTRAINT IF EXISTS income_receipts_amount_check;
-- NOT VALID preserves any historical zero rows accepted by V2 while enforcing positivity for
-- every new or subsequently updated receipt.
ALTER TABLE income_receipts ADD CONSTRAINT income_receipts_amount_check CHECK (amount > 0) NOT VALID;

-- Deterministic compatibility projection: the planned legacy Tithe row reuses the income UUID.
INSERT INTO income_deductions (
    id, space_id, income_entry_id, name, deduction_type, percentage_rate, fixed_amount,
    notes, sort_order, legacy_tithe, created_by_user_id, updated_by_user_id,
    created_at, updated_at, version, deleted_at
)
SELECT i.id, i.space_id, i.id, 'Tithe',
       CASE WHEN i.tithe_amount_override IS NULL THEN 'PERCENTAGE' ELSE 'FIXED' END,
       CASE WHEN i.tithe_amount_override IS NULL THEN i.tithe_rate ELSE NULL END,
       i.tithe_amount_override,
       'Migrated from legacy Tithe fields', 0, true,
       i.created_by_user_id, i.updated_by_user_id, i.created_at, i.updated_at, i.version, i.deleted_at
  FROM income_entries i
 WHERE i.tithe_enabled = true;

-- PostgreSQL round(numeric, scale) is half-away-from-zero. Finance calculations use HALF_EVEN,
-- so this short-lived migration helper preserves the exact legacy aggregate at cent boundaries.
CREATE FUNCTION wethrive_round_half_even(value numeric, places integer) RETURNS numeric
LANGUAGE plpgsql IMMUTABLE STRICT AS $$
DECLARE
    factor numeric := power(10::numeric, places);
    absolute_scaled numeric := abs(value) * factor;
    lower_units numeric := floor(absolute_scaled);
    fraction numeric := absolute_scaled - lower_units;
    rounded_units numeric;
BEGIN
    IF fraction > 0.5 OR (fraction = 0.5 AND mod(lower_units, 2) <> 0) THEN
        rounded_units := lower_units + 1;
    ELSE
        rounded_units := lower_units;
    END IF;
    RETURN CASE WHEN value < 0 THEN -rounded_units ELSE rounded_units END / factor;
END $$;

-- Actual legacy Tithe rows reuse receipt UUIDs. Cumulative proportional apportionment makes
-- every line deterministic and guarantees that the lines sum to the exact former aggregate,
-- including the final rounding cent across partial receipts.
WITH eligible AS (
    SELECT r.id AS receipt_id, r.space_id, r.income_entry_id, r.amount, r.received_at,
           r.created_by_user_id, r.updated_by_user_id, r.created_at, r.updated_at,
           r.version, r.deleted_at, i.expected_amount, i.tithe_rate, i.tithe_amount_override,
           SUM(r.amount) OVER group_window AS group_gross,
           SUM(r.amount) OVER row_window AS cumulative_gross
      FROM income_receipts r
      JOIN income_entries i ON i.id = r.income_entry_id AND i.tithe_enabled = true
    WINDOW group_window AS (
               PARTITION BY r.income_entry_id, (r.deleted_at IS NULL)
           ),
           row_window AS (
               PARTITION BY r.income_entry_id, (r.deleted_at IS NULL)
               ORDER BY r.received_at, r.created_at, r.id
               ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
           )
), apportioned AS (
    SELECT e.*,
           CASE
               WHEN e.tithe_amount_override IS NULL
                   THEN wethrive_round_half_even(e.group_gross * e.tithe_rate, 2)
               WHEN e.expected_amount > 0
                   THEN wethrive_round_half_even(LEAST(e.tithe_amount_override,
                        e.tithe_amount_override * e.group_gross / e.expected_amount), 2)
               ELSE wethrive_round_half_even(LEAST(e.group_gross, e.tithe_amount_override), 2)
           END AS group_deduction
      FROM eligible e
), lines AS (
    SELECT a.*,
           CASE WHEN a.group_gross = 0 THEN 0::numeric
                ELSE wethrive_round_half_even(a.group_deduction * a.cumulative_gross / a.group_gross, 2)
                   - wethrive_round_half_even(a.group_deduction * (a.cumulative_gross - a.amount) / a.group_gross, 2)
           END AS line_amount
      FROM apportioned a
)
INSERT INTO income_receipt_deductions (
    id, space_id, income_receipt_id, income_deduction_id, name_snapshot, actual_amount,
    created_by_user_id, updated_by_user_id, created_at, updated_at, version, deleted_at
)
SELECT receipt_id, space_id, receipt_id, income_entry_id, 'Tithe', line_amount,
       created_by_user_id, updated_by_user_id, created_at, updated_at, version, deleted_at
  FROM lines;

DROP FUNCTION wethrive_round_half_even(numeric, integer);
