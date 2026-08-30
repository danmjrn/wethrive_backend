package solutions.shapeit.wethrive.common.domain;

public final class DomainEnums {
    private DomainEnums() {}

    public enum SpaceType { PERSONAL, HOUSEHOLD }
    public enum Role { OWNER, ADMIN, FINANCE_EDITOR, MEMBER, VIEWER }
    public enum MembershipStatus { ACTIVE, LEFT, REMOVED }
    public enum Theme { LIGHT, DARK, SYSTEM }
    /**
     * Selects the persisted brand colour family independently of brightness mode.
     *
     * @author Daniel Jr Nkulu
     */
    public enum Palette { ORIGINAL, MONOCHROME }
    public enum BudgetItemView { CARDS, ROWS }
    public enum BudgetStatus { DRAFT, ACTIVE, CLOSED }
    public enum BudgetItemType { PLANNED, UNBUDGETED }
    public enum TransactionType { EXPENSE, REFUND }
    public enum IncomeStatus { SCHEDULED, DUE, LATE, PARTIALLY_RECEIVED, RECEIVED, CANCELLED }
    public enum DeductionType { PERCENTAGE, FIXED }
    public enum FundingSourceType { INCOME_ENTRY, UNASSIGNED_FUNDS, EXTERNAL_FUNDS, ROLLOVER_FUNDS }
    public enum FundingStatus { NOT_APPLICABLE, NOT_FUNDED, FUNDING_SCHEDULED, PARTIALLY_FUNDED, FULLY_FUNDED, OVERFUNDED }
    public enum AccountTokenType { EMAIL_VERIFICATION, PASSWORD_RESET }
    public enum ReminderType { SPENDING_CHECK_IN, MONTHLY_BUDGET_SETUP, BUDGET_THRESHOLD, HOUSEHOLD_ACTIVITY, INVITATION, SYNC_CONFLICT }
    public enum FrequencyType { DAILY, WEEKLY, SELECTED_DAYS, INTERVAL_DAYS, MONTHLY, CONDITIONAL }
    public enum ReminderStatus { PENDING, DELIVERED, ACKNOWLEDGED, SKIPPED, FAILED }
    public enum ReminderChannel { PUSH, IN_APP, BOTH }
    public enum SyncOperationType { CREATE, UPDATE, DELETE, RESTORE }
    public enum SyncStatus { APPLIED, DUPLICATE, CONFLICT, REJECTED }
    public enum AuditResult { SUCCESS, DENIED, FAILURE }
}
