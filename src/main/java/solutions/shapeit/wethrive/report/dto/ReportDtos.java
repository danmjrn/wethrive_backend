package solutions.shapeit.wethrive.report.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import solutions.shapeit.wethrive.common.web.ApiException;
import solutions.shapeit.wethrive.common.domain.DomainEnums.DeductionType;
import solutions.shapeit.wethrive.common.domain.DomainEnums.FundingSourceType;
import solutions.shapeit.wethrive.common.domain.DomainEnums.IncomeStatus;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.BudgetSummary;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.SpendingResponse;

public final class ReportDtos {
    private ReportDtos() {}

    /**
     * Optional report selection shared by interactive reports and authoritative exports.
     * Budget selection limits assigned plans/income to explicit budget months; date bounds limit
     * event-dated spending and receipt cash-flow only. Category limits budget items and their
     * allocations/spending without moving income assigned to the selected budget months.
     */
    public record ReportFilter(LocalDate from, LocalDate to, String category, List<UUID> budgetIds) {
        public ReportFilter {
            if (from != null && to != null && from.isAfter(to)) {
                throw ApiException.badRequest("Report start date must be on or before the end date");
            }
            category = category == null || category.isBlank() ? null : category.trim();
            budgetIds = budgetIds == null ? List.of() : List.copyOf(new LinkedHashSet<>(budgetIds));
        }

        public static ReportFilter unfiltered() {
            return new ReportFilter(null, null, null, List.of());
        }

        public boolean includesBudget(UUID budgetId) {
            return budgetIds.isEmpty() || budgetIds.contains(budgetId);
        }

        public boolean hasBudgetSelection() {
            return !budgetIds.isEmpty();
        }

        public boolean hasDataFilters() {
            return from != null || to != null || category != null;
        }

        public boolean includesDate(LocalDate date) {
            return date != null && (from == null || !date.isBefore(from)) && (to == null || !date.isAfter(to));
        }
    }

    public record NamedAmount(UUID id, String name, BigDecimal amount) {}
    public record DailyAmount(LocalDate date, BigDecimal amount) {}
    public record DailyCashFlow(LocalDate date, BigDecimal receipts, BigDecimal spending, BigDecimal net) {}
    public record ReportChartData(List<NamedAmount> projectedExpensesByCategory,
                                  List<NamedAmount> actualSpendingByCategory,
                                  List<NamedAmount> plannedFundingAndActual,
                                  List<NamedAmount> incomeAvailability,
                                  List<NamedAmount> fundingStatusDistribution,
                                  List<DailyCashFlow> cashFlow,
                                  List<NamedAmount> budgetVariance) {}

    /** Counts are explicit so clients do not need to infer timing state from monetary totals. */
    public record IncomeStatusCounts(int scheduled, int due, int late, int partiallyReceived,
                                     int received, int cancelled) {}

    /** A reporting projection of one independently scheduled income entry. */
    public record IncomeScheduleRow(UUID id, UUID budgetId, String sourceName, BigDecimal expectedAmount,
                                    LocalDate expectedDate, LocalTime expectedTime, String timeZone,
                                    IncomeStatus status, BigDecimal projectedGrossIncome,
                                    BigDecimal projectedNetIncome, BigDecimal receivedGrossIncome,
                                    BigDecimal receivedNetIncome, BigDecimal outstandingExpectedIncome,
                                    BigDecimal plannedFunding, BigDecimal confirmedAllocation,
                                    BigDecimal unallocatedReceivedIncome, int receiptCount,
                                    boolean recurring, Instant cancelledAt) {}

    /** Active planned deduction detail, calculated from the same canonical gross-income formula as the totals. */
    public record PlannedDeductionRow(UUID id, UUID incomeEntryId, String incomeSource, String name,
                                      DeductionType deductionType, BigDecimal percentageRate,
                                      BigDecimal fixedAmount, BigDecimal projectedAmount,
                                      int sortOrder, boolean legacyTithe) {}

    /** Active receipt-level deduction detail. The receipt timestamp preserves its original time-zone context. */
    public record RealizedDeductionRow(UUID id, UUID incomeReceiptId, UUID incomeDeductionId,
                                       UUID incomeEntryId, String incomeSource, Instant receivedAt,
                                       String timeZone, String name, BigDecimal actualAmount) {}

    /**
     * One many-to-many funding link. Reversed links remain in reports so financial history is not
     * lost; {@code reversedAt == null} identifies a currently active link.
     */
    public record IncomeBudgetMapping(UUID allocationId, UUID incomeEntryId, String incomeSource,
                                      UUID budgetItemId, String budgetItemName,
                                      FundingSourceType sourceType, BigDecimal plannedFunding,
                                      BigDecimal confirmedAllocation, Instant allocatedAt,
                                      String timeZone, Instant createdAt, Instant updatedAt,
                                      Instant reversedAt, String notes) {}

    public record MonthlyReport(UUID spaceId, UUID budgetId, int year, int month, String budgetName,
                                BudgetSummary totals, List<NamedAmount> categories, List<NamedAmount> members,
                                List<DailyAmount> dailyTrend, List<SpendingResponse> largestTransactions,
                                List<UUID> overspentBudgetItemIds, PeriodTotals reportTotals,
                                List<IncomeScheduleRow> incomeSchedule,
                                List<IncomeBudgetMapping> incomeToBudgetMappings,
                                List<PlannedDeductionRow> plannedDeductions,
                                List<RealizedDeductionRow> realizedDeductions,
                                ReportChartData charts) {
        /** Compatibility constructor for export tests and clients constructing the pre-1.1 report shape. */
        public MonthlyReport(UUID spaceId, UUID budgetId, int year, int month, String budgetName,
                             BudgetSummary totals, List<NamedAmount> categories, List<NamedAmount> members,
                             List<DailyAmount> dailyTrend, List<SpendingResponse> largestTransactions,
                             List<UUID> overspentBudgetItemIds, PeriodTotals reportTotals,
                             List<IncomeScheduleRow> incomeSchedule,
                             List<IncomeBudgetMapping> incomeToBudgetMappings,
                             ReportChartData charts) {
            this(spaceId, budgetId, year, month, budgetName, totals, categories, members, dailyTrend,
                    largestTransactions, overspentBudgetItemIds, reportTotals, incomeSchedule,
                    incomeToBudgetMappings, List.of(), List.of(), charts);
        }
    }
    public record PeriodTotals(BigDecimal projectedGrossIncome, BigDecimal projectedNetIncome,
                               BigDecimal receivedGrossIncome, BigDecimal receivedNetIncome,
                               BigDecimal projectedDeductions, BigDecimal realizedDeductions,
                               BigDecimal outstandingExpectedIncome, BigDecimal confirmedAllocatedIncome,
                               BigDecimal unallocatedReceivedIncome, BigDecimal plannedSpending,
                               BigDecimal plannedFunding, BigDecimal confirmedFundedBudget, BigDecimal fundingGap,
                               int fullyFundedItemCount, int partiallyFundedItemCount, int unfundedItemCount,
                               BigDecimal grossSpending, BigDecimal refunds, BigDecimal netSpending,
                               BigDecimal unbudgetedSpending, BigDecimal unspentAllocatedFunds,
                               BigDecimal unfundedSpending, BigDecimal remaining, BigDecimal spendingRate,
                               String status, IncomeStatusCounts incomeStatuses, int receiptCount,
                               int fundingAllocationCount, int reversedFundingAllocationCount) {}
    public record PeriodRow(String label, int year, Integer month, PeriodTotals totals) {}
    public record QuarterlyReport(UUID spaceId, int year, int quarter, List<PeriodRow> months, PeriodTotals totals,
                                  List<NamedAmount> categories, List<NamedAmount> members,
                                  List<IncomeScheduleRow> incomeSchedule,
                                  List<IncomeBudgetMapping> incomeToBudgetMappings,
                                  List<PlannedDeductionRow> plannedDeductions,
                                  List<RealizedDeductionRow> realizedDeductions) {
        public QuarterlyReport(UUID spaceId, int year, int quarter, List<PeriodRow> months, PeriodTotals totals,
                               List<NamedAmount> categories, List<NamedAmount> members,
                               List<IncomeScheduleRow> incomeSchedule,
                               List<IncomeBudgetMapping> incomeToBudgetMappings) {
            this(spaceId, year, quarter, months, totals, categories, members, incomeSchedule,
                    incomeToBudgetMappings, List.of(), List.of());
        }
    }
    public record AnnualReport(UUID spaceId, int year, List<PeriodRow> months, List<PeriodRow> quarters,
                               PeriodTotals totals, List<NamedAmount> categories, List<NamedAmount> members,
                               List<IncomeScheduleRow> incomeSchedule,
                               List<IncomeBudgetMapping> incomeToBudgetMappings,
                               List<PlannedDeductionRow> plannedDeductions,
                               List<RealizedDeductionRow> realizedDeductions) {
        public AnnualReport(UUID spaceId, int year, List<PeriodRow> months, List<PeriodRow> quarters,
                            PeriodTotals totals, List<NamedAmount> categories, List<NamedAmount> members,
                            List<IncomeScheduleRow> incomeSchedule,
                            List<IncomeBudgetMapping> incomeToBudgetMappings) {
            this(spaceId, year, months, quarters, totals, categories, members, incomeSchedule,
                    incomeToBudgetMappings, List.of(), List.of());
        }
    }
}
