-- Public-release domain hardening. V1-V6 remain immutable.

-- 1. Keep PostgreSQL reference definitions aligned with the canonical backend/frontend list,
-- then add any missing system default to every existing space without changing an existing row.
UPDATE reference_category_definitions
SET sort_order = sort_order + 1000000;

CREATE OR REPLACE FUNCTION wethrive_backfill_standard_categories() RETURNS void LANGUAGE sql AS $backfill$
WITH desired(name, sort_order) AS (VALUES
    ('Bank Charges', 1), ('Childcare', 2), ('Clothing & Accessories', 3), ('Contracts', 4),
    ('Contribution', 5), ('Debt Payoff', 6), ('Dining Out & Takeaways', 7), ('Education', 8),
    ('Electricity', 9), ('Emergency Funds', 10), ('Entertainment', 11), ('Financial Aid', 12),
    ('Fuel', 13), ('Gifts', 14), ('Groceries', 15), ('Home', 16), ('Household Maintenance', 17),
    ('Insurance', 18), ('Internet & Mobile', 19), ('Investments', 20), ('Medical & Health', 21),
    ('Miscellaneous', 22), ('Mortgage / Bond', 23), ('Personal Development', 24), ('Pet Care', 25),
    ('Public Transport', 26), ('Rent', 27), ('Savings', 28), ('Self Care', 29),
    ('Stokvel Contribution', 30), ('Subscriptions', 31), ('Taxes', 32), ('Tithe & Offering', 33),
    ('Transportation', 34), ('Travel', 35), ('Utilities', 36), ('Vehicle Maintenance', 37),
    ('Water', 38)
)
INSERT INTO reference_category_definitions(name, sort_order)
SELECT desired.name, desired.sort_order
FROM desired
WHERE NOT EXISTS (
    SELECT 1
    FROM reference_category_definitions existing
    WHERE lower(existing.name) = lower(desired.name)
);

WITH desired(name, sort_order) AS (VALUES
    ('Bank Charges', 1), ('Childcare', 2), ('Clothing & Accessories', 3), ('Contracts', 4),
    ('Contribution', 5), ('Debt Payoff', 6), ('Dining Out & Takeaways', 7), ('Education', 8),
    ('Electricity', 9), ('Emergency Funds', 10), ('Entertainment', 11), ('Financial Aid', 12),
    ('Fuel', 13), ('Gifts', 14), ('Groceries', 15), ('Home', 16), ('Household Maintenance', 17),
    ('Insurance', 18), ('Internet & Mobile', 19), ('Investments', 20), ('Medical & Health', 21),
    ('Miscellaneous', 22), ('Mortgage / Bond', 23), ('Personal Development', 24), ('Pet Care', 25),
    ('Public Transport', 26), ('Rent', 27), ('Savings', 28), ('Self Care', 29),
    ('Stokvel Contribution', 30), ('Subscriptions', 31), ('Taxes', 32), ('Tithe & Offering', 33),
    ('Transportation', 34), ('Travel', 35), ('Utilities', 36), ('Vehicle Maintenance', 37),
    ('Water', 38)
)
UPDATE reference_category_definitions existing
SET sort_order = desired.sort_order
FROM desired
WHERE lower(existing.name) = lower(desired.name);

WITH desired(name, sort_order) AS (VALUES
    ('Bank Charges', 1), ('Childcare', 2), ('Clothing & Accessories', 3), ('Contracts', 4),
    ('Contribution', 5), ('Debt Payoff', 6), ('Dining Out & Takeaways', 7), ('Education', 8),
    ('Electricity', 9), ('Emergency Funds', 10), ('Entertainment', 11), ('Financial Aid', 12),
    ('Fuel', 13), ('Gifts', 14), ('Groceries', 15), ('Home', 16), ('Household Maintenance', 17),
    ('Insurance', 18), ('Internet & Mobile', 19), ('Investments', 20), ('Medical & Health', 21),
    ('Miscellaneous', 22), ('Mortgage / Bond', 23), ('Personal Development', 24), ('Pet Care', 25),
    ('Public Transport', 26), ('Rent', 27), ('Savings', 28), ('Self Care', 29),
    ('Stokvel Contribution', 30), ('Subscriptions', 31), ('Taxes', 32), ('Tithe & Offering', 33),
    ('Transportation', 34), ('Travel', 35), ('Utilities', 36), ('Vehicle Maintenance', 37),
    ('Water', 38)
)
INSERT INTO categories(
    id, space_id, created_by_user_id, updated_by_user_id, name, icon, sort_order,
    archived, system_default, created_at, updated_at, version, deleted_at
)
SELECT md5(space.id::text || ':wethrive-system-category:' || lower(desired.name))::uuid,
       space.id, space.owner_user_id, space.owner_user_id, desired.name, NULL, desired.sort_order,
       false, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, NULL
FROM spaces space
CROSS JOIN desired
WHERE space.deleted_at IS NULL
  AND NOT EXISTS (
      SELECT 1
      FROM categories existing
      WHERE existing.space_id = space.id
        AND lower(existing.name) = lower(desired.name)
  );
$backfill$;

SELECT wethrive_backfill_standard_categories();

-- 2. Brightness and colour family are independent preferences. Existing and new accounts start
-- on the original WeThrive palette; later API updates may explicitly choose MONOCHROME.
ALTER TABLE user_settings
    ADD COLUMN palette varchar(20) NOT NULL DEFAULT 'ORIGINAL';

ALTER TABLE user_settings
    ADD CONSTRAINT ck_user_settings_palette
    CHECK (palette IN ('ORIGINAL', 'MONOCHROME'));

-- 3. Legacy refunds remain valid with a null link. Newly linked refunds identify their original
-- expense so service and database layers can enforce tenant, item, and cumulative-value integrity.
ALTER TABLE spending_entries
    ADD COLUMN refund_for_spending_entry_id uuid;

ALTER TABLE spending_entries
    ADD CONSTRAINT fk_spending_refund_original
        FOREIGN KEY (refund_for_spending_entry_id) REFERENCES spending_entries(id),
    ADD CONSTRAINT ck_spending_refund_not_self
        CHECK (refund_for_spending_entry_id IS NULL OR refund_for_spending_entry_id <> id),
    ADD CONSTRAINT ck_spending_refund_type
        CHECK (refund_for_spending_entry_id IS NULL OR transaction_type = 'REFUND');

CREATE INDEX ix_spending_refund_original
    ON spending_entries(refund_for_spending_entry_id, spent_at)
    WHERE refund_for_spending_entry_id IS NOT NULL;

-- Extend the polymorphic tenant guard in place; existing triggers call this function dynamically.
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
        ELSIF NEW.refund_for_spending_entry_id IS NOT NULL AND (
            (SELECT transaction_type FROM spending_entries WHERE id = NEW.refund_for_spending_entry_id)
                IS DISTINCT FROM 'EXPENSE' OR
            (SELECT space_id FROM spending_entries WHERE id = NEW.refund_for_spending_entry_id)
                IS DISTINCT FROM NEW.space_id OR
            (SELECT budget_item_id FROM spending_entries WHERE id = NEW.refund_for_spending_entry_id)
                IS DISTINCT FROM NEW.budget_item_id) THEN
            RAISE EXCEPTION 'a linked refund must reference an expense in the same space and budget item';
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

CREATE OR REPLACE FUNCTION wethrive_validate_spending_refund() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    original_amount numeric(19,2);
    original_type varchar(20);
    original_deleted_at timestamptz;
    active_refund_total numeric;
    active_refund_count bigint;
BEGIN
    IF TG_OP = 'UPDATE' AND OLD.transaction_type <> NEW.transaction_type THEN
        RAISE EXCEPTION 'spending transaction type is immutable';
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.refund_for_spending_entry_id IS DISTINCT FROM NEW.refund_for_spending_entry_id THEN
        RAISE EXCEPTION 'refund linkage is immutable';
    END IF;

    IF NEW.refund_for_spending_entry_id IS NOT NULL THEN
        SELECT amount, transaction_type, deleted_at
          INTO original_amount, original_type, original_deleted_at
          FROM spending_entries
         WHERE id = NEW.refund_for_spending_entry_id
         FOR UPDATE;
        IF NOT FOUND OR original_type <> 'EXPENSE' OR original_deleted_at IS NOT NULL THEN
            RAISE EXCEPTION 'a linked refund requires an active original expense';
        END IF;
        SELECT COALESCE(SUM(amount), 0)
          INTO active_refund_total
          FROM spending_entries
         WHERE refund_for_spending_entry_id = NEW.refund_for_spending_entry_id
           AND deleted_at IS NULL
           AND id <> NEW.id;
        IF NEW.deleted_at IS NULL THEN
            active_refund_total := active_refund_total + NEW.amount;
        END IF;
        IF active_refund_total > original_amount THEN
            RAISE EXCEPTION 'active refunds cannot exceed the original expense amount';
        END IF;
    ELSIF NEW.transaction_type = 'EXPENSE' THEN
        SELECT COUNT(*), COALESCE(SUM(amount), 0)
          INTO active_refund_count, active_refund_total
          FROM spending_entries
         WHERE refund_for_spending_entry_id = NEW.id
           AND deleted_at IS NULL;
        IF active_refund_count > 0 AND NEW.deleted_at IS NOT NULL THEN
            RAISE EXCEPTION 'an expense with active linked refunds cannot be deleted';
        END IF;
        IF active_refund_total > NEW.amount THEN
            RAISE EXCEPTION 'expense amount cannot be lower than its active refunds';
        END IF;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_spending_refund_integrity
BEFORE INSERT OR UPDATE ON spending_entries
FOR EACH ROW EXECUTE FUNCTION wethrive_validate_spending_refund();
