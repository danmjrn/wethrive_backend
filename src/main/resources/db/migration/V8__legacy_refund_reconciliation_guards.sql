-- Forward-only compatibility hardening for refunds inherited before source links existed.
-- V7 remains immutable. Legacy null-link rows stay readable and reversible, while every
-- subsequent mutation must preserve or improve both source and budget-item refund coverage.

CREATE OR REPLACE FUNCTION wethrive_validate_spending_refund() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    original_amount numeric(19,2);
    original_type varchar(20);
    original_deleted_at timestamptz;
    active_refund_total numeric;
    active_refund_count bigint;
    old_budget_id uuid;
    new_budget_id uuid;
    current_expense_total numeric;
    current_refund_total numeric;
    prospective_expense_total numeric;
    prospective_refund_total numeric;
    current_excess numeric;
    prospective_excess numeric;
BEGIN
    IF TG_OP = 'UPDATE' AND OLD.transaction_type <> NEW.transaction_type THEN
        RAISE EXCEPTION 'spending transaction type is immutable';
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.refund_for_spending_entry_id IS DISTINCT FROM NEW.refund_for_spending_entry_id THEN
        RAISE EXCEPTION 'refund linkage is immutable';
    END IF;

    IF TG_OP = 'INSERT' AND NEW.transaction_type = 'REFUND'
       AND NEW.refund_for_spending_entry_id IS NULL THEN
        RAISE EXCEPTION 'new refunds must identify their original expense';
    END IF;

    IF TG_OP = 'UPDATE' AND OLD.transaction_type = 'REFUND'
       AND OLD.refund_for_spending_entry_id IS NULL THEN
        IF NEW.amount > OLD.amount THEN
            RAISE EXCEPTION 'legacy unlinked refund amount cannot be increased before reconciliation';
        END IF;
        IF NEW.budget_item_id IS DISTINCT FROM OLD.budget_item_id THEN
            RAISE EXCEPTION 'legacy unlinked refund cannot be moved before reconciliation';
        END IF;
        IF OLD.deleted_at IS NOT NULL AND NEW.deleted_at IS NULL THEN
            RAISE EXCEPTION 'legacy unlinked refund cannot be restored before reconciliation';
        END IF;
    END IF;

    SELECT budget_month_id INTO new_budget_id FROM budget_items WHERE id = NEW.budget_item_id;
    IF TG_OP = 'UPDATE' THEN
        SELECT budget_month_id INTO old_budget_id FROM budget_items WHERE id = OLD.budget_item_id;
    ELSE
        old_budget_id := new_budget_id;
    END IF;
    -- The same lifecycle row is used by service mutations, so aggregate checks serialize with
    -- budget closure/deletion and with other spending mutations in a deterministic UUID order.
    PERFORM 1
      FROM budget_months
     WHERE id IN (old_budget_id, new_budget_id)
     ORDER BY id
     FOR UPDATE;

    IF NEW.refund_for_spending_entry_id IS NOT NULL THEN
        SELECT amount, transaction_type, deleted_at
          INTO original_amount, original_type, original_deleted_at
          FROM spending_entries
         WHERE id = NEW.refund_for_spending_entry_id
         FOR UPDATE;
        IF NOT FOUND OR original_type <> 'EXPENSE'
           OR (NEW.deleted_at IS NULL AND original_deleted_at IS NOT NULL) THEN
            RAISE EXCEPTION 'an active linked refund requires an active original expense';
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
            RAISE EXCEPTION 'expense amount cannot be lower than its active linked refunds';
        END IF;
    END IF;

    -- If a row moves, first ensure removing it does not worsen the source item's inherited
    -- aggregate position. Existing invalid legacy data is grandfathered only when unchanged or
    -- improved; no operation may increase its refund excess.
    IF TG_OP = 'UPDATE' AND OLD.budget_item_id IS DISTINCT FROM NEW.budget_item_id THEN
        SELECT COALESCE(SUM(amount) FILTER (WHERE transaction_type = 'EXPENSE'), 0),
               COALESCE(SUM(amount) FILTER (WHERE transaction_type = 'REFUND'), 0)
          INTO current_expense_total, current_refund_total
          FROM spending_entries
         WHERE budget_item_id = OLD.budget_item_id
           AND deleted_at IS NULL;
        SELECT COALESCE(SUM(amount) FILTER (WHERE transaction_type = 'EXPENSE'), 0),
               COALESCE(SUM(amount) FILTER (WHERE transaction_type = 'REFUND'), 0)
          INTO prospective_expense_total, prospective_refund_total
          FROM spending_entries
         WHERE budget_item_id = OLD.budget_item_id
           AND deleted_at IS NULL
           AND id <> NEW.id;
        current_excess := GREATEST(current_refund_total - current_expense_total, 0);
        prospective_excess := GREATEST(prospective_refund_total - prospective_expense_total, 0);
        IF prospective_excess > current_excess THEN
            RAISE EXCEPTION 'spending move would leave active refunds uncovered on the source budget item';
        END IF;
    END IF;

    SELECT COALESCE(SUM(amount) FILTER (WHERE transaction_type = 'EXPENSE'), 0),
           COALESCE(SUM(amount) FILTER (WHERE transaction_type = 'REFUND'), 0)
      INTO current_expense_total, current_refund_total
      FROM spending_entries
     WHERE budget_item_id = NEW.budget_item_id
       AND deleted_at IS NULL;
    SELECT COALESCE(SUM(amount) FILTER (WHERE transaction_type = 'EXPENSE'), 0),
           COALESCE(SUM(amount) FILTER (WHERE transaction_type = 'REFUND'), 0)
      INTO prospective_expense_total, prospective_refund_total
      FROM spending_entries
     WHERE budget_item_id = NEW.budget_item_id
       AND deleted_at IS NULL
       AND id <> NEW.id;
    IF NEW.deleted_at IS NULL THEN
        IF NEW.transaction_type = 'EXPENSE' THEN
            prospective_expense_total := prospective_expense_total + NEW.amount;
        ELSE
            prospective_refund_total := prospective_refund_total + NEW.amount;
        END IF;
    END IF;
    current_excess := GREATEST(current_refund_total - current_expense_total, 0);
    prospective_excess := GREATEST(prospective_refund_total - prospective_expense_total, 0);
    IF prospective_excess > current_excess THEN
        RAISE EXCEPTION 'active refunds cannot exceed active expenses for a budget item';
    END IF;

    RETURN NEW;
END $$;
