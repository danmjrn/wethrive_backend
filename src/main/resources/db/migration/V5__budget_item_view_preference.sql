-- Synchronize the user's Cards | Rows budget-item presentation choice.
-- The default preserves the 1.0 card experience for every existing account.

ALTER TABLE user_settings
    ADD COLUMN budget_item_view varchar(10) NOT NULL DEFAULT 'CARDS';

ALTER TABLE user_settings
    ADD CONSTRAINT ck_user_settings_budget_item_view
    CHECK (budget_item_view IN ('CARDS', 'ROWS'));
