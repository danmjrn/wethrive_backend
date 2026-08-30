package solutions.shapeit.wethrive.finance.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import solutions.shapeit.wethrive.common.domain.DomainEnums.BudgetItemType;
import solutions.shapeit.wethrive.common.domain.DomainEnums.DeductionType;
import solutions.shapeit.wethrive.common.domain.DomainEnums.FundingSourceType;
import solutions.shapeit.wethrive.common.domain.DomainEnums.FundingStatus;
import solutions.shapeit.wethrive.common.domain.DomainEnums.IncomeStatus;
import solutions.shapeit.wethrive.common.domain.DomainEnums.TransactionType;
import solutions.shapeit.wethrive.finance.entity.BudgetFundingAllocation;
import solutions.shapeit.wethrive.finance.entity.BudgetItem;
import solutions.shapeit.wethrive.finance.entity.IncomeEntry;
import solutions.shapeit.wethrive.finance.entity.IncomeDeduction;
import solutions.shapeit.wethrive.finance.entity.IncomeReceipt;
import solutions.shapeit.wethrive.finance.entity.IncomeReceiptDeduction;
import solutions.shapeit.wethrive.finance.entity.SpendingEntry;

class FinanceCalculationServiceTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 15);
    private final FinanceCalculationService service = new FinanceCalculationService();

    @Test
    void scheduledIncomeIsProjectedButNotAvailableAndFundingPlanStaysScheduled() {
        IncomeEntry salary = income("10000.00", LocalDate.of(2026, 7, 25));
        BudgetItem transport = item("2000.00");
        BudgetFundingAllocation plan = allocation(transport.getId(), salary.getId(), "2000.00", "0.00");
        var incomeResult = service.income(salary, List.of(), List.of(plan), TODAY);
        var itemResult = service.item(transport, List.of(), List.of(plan));

        assertThat(incomeResult.availability().projectedNetIncome()).isEqualByComparingTo("10000.00");
        assertThat(incomeResult.availability().receivedNetIncome()).isEqualByComparingTo("0.00");
        assertThat(incomeResult.availability().unallocatedReceivedIncome()).isEqualByComparingTo("0.00");
        assertThat(incomeResult.availability().status()).isEqualTo(IncomeStatus.SCHEDULED);
        assertThat(itemResult.funding().status()).isEqualTo(FundingStatus.FUNDING_SCHEDULED);
        assertThat(itemResult.funding().confirmedAllocatedAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void receiptAndAllocationsKeepIncomeAvailabilitySeparateFromSpending() {
        IncomeEntry salary = income("10000.00", LocalDate.of(2026, 7, 15));
        IncomeReceipt receipt = receipt(salary.getId(), "10000.00");
        BudgetItem transport = item("2000.00");
        BudgetItem rent = item("5000.00");
        BudgetFundingAllocation transportAllocation = allocation(transport.getId(), salary.getId(), "2000.00", "2000.00");
        BudgetFundingAllocation rentAllocation = allocation(rent.getId(), salary.getId(), "5000.00", "5000.00");
        var result = service.income(salary, List.of(receipt), List.of(transportAllocation, rentAllocation), TODAY);

        assertThat(result.availability().receivedNetIncome()).isEqualByComparingTo("10000.00");
        assertThat(result.availability().confirmedAllocatedAmount()).isEqualByComparingTo("7000.00");
        assertThat(result.availability().unallocatedReceivedIncome()).isEqualByComparingTo("3000.00");
        assertThat(result.availability().status()).isEqualTo(IncomeStatus.RECEIVED);
    }

    @Test
    void partialReceiptAndDeductionAreAppliedOnceToReceivedCash() {
        IncomeEntry salary = income("10000.00", LocalDate.of(2026, 7, 15));
        salary.setTitheEnabled(true); salary.setTitheRate(new BigDecimal("0.1000"));
        var result = service.income(salary, List.of(receipt(salary.getId(), "5000.00")), List.of(), TODAY);

        assertThat(result.availability().projectedDeductions()).isEqualByComparingTo("1000.00");
        assertThat(result.availability().realizedDeductions()).isEqualByComparingTo("500.00");
        assertThat(result.availability().receivedNetIncome()).isEqualByComparingTo("4500.00");
        assertThat(result.availability().remainingExpectedIncome()).isEqualByComparingTo("4500.00");
        assertThat(result.availability().status()).isEqualTo(IncomeStatus.PARTIALLY_RECEIVED);
    }

    @Test
    void cancellingIncomeDoesNotRecalculateARealizedFixedDeduction() {
        IncomeEntry salary = income("10000.00", LocalDate.of(2026, 7, 25));
        salary.setTitheEnabled(true); salary.setTitheAmountOverride(new BigDecimal("1000.00"));
        IncomeReceipt partial = receipt(salary.getId(), "5000.00");
        var before = service.income(salary, List.of(partial), List.of(), TODAY);

        salary.setCancelledAt(Instant.parse("2026-07-16T08:00:00Z"));
        var cancelled = service.income(salary, List.of(partial), List.of(), TODAY);

        assertThat(before.availability().realizedDeductions()).isEqualByComparingTo("500.00");
        assertThat(cancelled.availability().projectedGrossIncome()).isEqualByComparingTo("0.00");
        assertThat(cancelled.availability().projectedDeductions()).isEqualByComparingTo("0.00");
        assertThat(cancelled.availability().realizedDeductions()).isEqualByComparingTo("500.00");
        assertThat(cancelled.availability().receivedNetIncome()).isEqualByComparingTo("4500.00");
        assertThat(cancelled.availability().status()).isEqualTo(IncomeStatus.CANCELLED);
    }

    @Test
    void genericDeductionsRemainExactAcrossPartialReceiptsAndAReversal() {
        IncomeEntry salary = income("10000.00", LocalDate.of(2026, 7, 15));
        IncomeDeduction tithe = planned(salary.getId(), "Tithe", DeductionType.PERCENTAGE,
                "0.1000", null);
        IncomeDeduction medical = planned(salary.getId(), "Medical aid", DeductionType.FIXED,
                null, "1000.00");
        IncomeReceipt first = receipt(salary.getId(), "4000.00");
        IncomeReceipt second = receipt(salary.getId(), "6000.00");
        second.setReceivedAt(Instant.parse("2026-07-20T08:00:00Z"));
        List<IncomeReceiptDeduction> actuals = List.of(
                actual(first, tithe, "400.00"), actual(first, medical, "400.00"),
                actual(second, tithe, "600.00"), actual(second, medical, "600.00"));

        var received = service.income(salary, List.of(first, second), List.of(tithe, medical),
                actuals, List.of(), TODAY);

        assertThat(received.availability().projectedGrossIncome()).isEqualByComparingTo("10000.00");
        assertThat(received.availability().projectedDeductions()).isEqualByComparingTo("2000.00");
        assertThat(received.availability().projectedNetIncome()).isEqualByComparingTo("8000.00");
        assertThat(received.availability().receivedGrossIncome()).isEqualByComparingTo("10000.00");
        assertThat(received.availability().realizedDeductions()).isEqualByComparingTo("2000.00");
        assertThat(received.availability().receivedNetIncome()).isEqualByComparingTo("8000.00");

        Instant reversedAt = Instant.parse("2026-07-21T08:00:00Z");
        second.setDeletedAt(reversedAt);
        actuals.stream().filter(value -> value.getIncomeReceiptId().equals(second.getId()))
                .forEach(value -> value.setDeletedAt(reversedAt));
        var reversed = service.income(salary, List.of(first, second), List.of(tithe, medical),
                actuals, List.of(), TODAY);

        assertThat(reversed.availability().receivedGrossIncome()).isEqualByComparingTo("4000.00");
        assertThat(reversed.availability().realizedDeductions()).isEqualByComparingTo("800.00");
        assertThat(reversed.availability().receivedNetIncome()).isEqualByComparingTo("3200.00");
        assertThat(reversed.availability().remainingExpectedIncome()).isEqualByComparingTo("4800.00");
    }

    @Test
    void splitFundingFullyFundsOneBudgetItem() {
        BudgetItem transport = item("2000.00");
        var salary = allocation(transport.getId(), UUID.randomUUID(), "1200.00", "1200.00");
        var sideHustle = allocation(transport.getId(), UUID.randomUUID(), "800.00", "800.00");
        var result = service.item(transport, List.of(), List.of(salary, sideHustle));
        assertThat(result.funding().confirmedAllocatedAmount()).isEqualByComparingTo("2000.00");
        assertThat(result.funding().fundingGap()).isEqualByComparingTo("0.00");
        assertThat(result.funding().status()).isEqualTo(FundingStatus.FULLY_FUNDED);
    }

    @Test
    void allocationIsIndependentFromSpendingAndRefunds() {
        BudgetItem transport = item("2000.00");
        BudgetFundingAllocation funded = allocation(transport.getId(), UUID.randomUUID(), "2000.00", "2000.00");
        var beforeRefund = service.item(transport, List.of(spending("50.00", TransactionType.EXPENSE),
                spending("60.00", TransactionType.EXPENSE)), List.of(funded));
        assertThat(beforeRefund.calculation().netSpending()).isEqualByComparingTo("110.00");
        assertThat(beforeRefund.calculation().remaining()).isEqualByComparingTo("1890.00");
        assertThat(beforeRefund.funding().unspentAllocatedFunds()).isEqualByComparingTo("1890.00");
        assertThat(beforeRefund.calculation().usagePercentage()).isEqualByComparingTo("5.50");
        assertThat(beforeRefund.funding().status()).isEqualTo(FundingStatus.FULLY_FUNDED);

        var afterRefund = service.item(transport, List.of(spending("50", TransactionType.EXPENSE),
                spending("60", TransactionType.EXPENSE), spending("20", TransactionType.REFUND)), List.of(funded));
        assertThat(afterRefund.calculation().grossSpending()).isEqualByComparingTo("110.00");
        assertThat(afterRefund.calculation().refunds()).isEqualByComparingTo("20.00");
        assertThat(afterRefund.calculation().netSpending()).isEqualByComparingTo("90.00");
        assertThat(afterRefund.calculation().remaining()).isEqualByComparingTo("1910.00");
        assertThat(afterRefund.funding().unspentAllocatedFunds()).isEqualByComparingTo("1910.00");
        assertThat(afterRefund.funding().confirmedAllocatedAmount()).isEqualByComparingTo("2000.00");
    }

    @Test
    void budgetReportSeparatesProjectedReceivedAllocatedAndSpentTotals() {
        IncomeEntry salary = income("10000.00", LocalDate.of(2026, 7, 25));
        BudgetItem transport = item("2000.00");
        BudgetFundingAllocation allocation = allocation(transport.getId(), salary.getId(), "2000.00", "2000.00");
        SpendingEntry expense = spending("110.00", TransactionType.EXPENSE); expense.setBudgetItemId(transport.getId());
        var result = service.budget(List.of(salary), List.of(transport), List.of(expense),
                List.of(receipt(salary.getId(), "5000.00")), List.of(allocation), YearMonth.of(2026, 7), TODAY);
        assertThat(result.projectedGrossIncome()).isEqualByComparingTo("10000.00");
        assertThat(result.receivedNetIncome()).isEqualByComparingTo("5000.00");
        assertThat(result.confirmedAllocatedIncome()).isEqualByComparingTo("2000.00");
        assertThat(result.unallocatedReceivedIncome()).isEqualByComparingTo("3000.00");
        assertThat(result.netSpending()).isEqualByComparingTo("110.00");
    }

    @Test
    void safeToSpendExcludesIncomeThatHasBeenReservedByConfirmedAllocations() {
        IncomeEntry salary = income("10000.00", LocalDate.of(2026, 7, 15));
        BudgetItem savings = item("10000.00");
        BudgetFundingAllocation reserved = allocation(savings.getId(), salary.getId(), "10000.00", "10000.00");

        var result = service.budget(List.of(salary), List.of(savings), List.of(),
                List.of(receipt(salary.getId(), "10000.00")), List.of(reserved),
                YearMonth.of(2026, 7), TODAY);

        assertThat(result.remainingIncome()).isEqualByComparingTo("10000.00");
        assertThat(result.unallocatedReceivedIncome()).isEqualByComparingTo("0.00");
        assertThat(result.safeToSpend()).isEqualByComparingTo("0.00");
    }

    @Test
    void zeroPlannedAmountNeverDividesByZeroAndThresholdsAreStable() {
        assertThat(service.item(item("0"), List.of(spending("10", TransactionType.EXPENSE)))
                .calculation().usagePercentage()).isEqualByComparingTo("0.00");
        assertThat(service.status(new BigDecimal("74.99"))).isEqualTo("On track");
        assertThat(service.status(new BigDecimal("75.00"))).isEqualTo("Watch closely");
        assertThat(service.status(new BigDecimal("90.00"))).isEqualTo("Almost reached");
        assertThat(service.status(new BigDecimal("100.00"))).isEqualTo("Fully used");
        assertThat(service.status(new BigDecimal("100.01"))).isEqualTo("Overspent");
    }

    private IncomeEntry income(String expected, LocalDate date) {
        IncomeEntry value = new IncomeEntry(); value.setId(UUID.randomUUID()); value.setBudgetMonthId(UUID.randomUUID());
        value.setSpaceId(UUID.randomUUID()); value.setIncomeTypeId(UUID.randomUUID()); value.setSourceName("Salary");
        value.setExpectedAmount(new BigDecimal(expected)); value.setExpectedDate(date); value.setTimeZone("Africa/Johannesburg");
        value.setTitheRate(new BigDecimal("0.1000")); return value;
    }

    private IncomeReceipt receipt(UUID incomeId, String amount) {
        IncomeReceipt value = new IncomeReceipt(); value.setId(UUID.randomUUID()); value.setIncomeEntryId(incomeId);
        value.setAmount(new BigDecimal(amount)); value.setReceivedAt(Instant.parse("2026-07-15T08:00:00Z")); return value;
    }

    private IncomeDeduction planned(UUID incomeId, String name, DeductionType type,
                                    String rate, String fixed) {
        IncomeDeduction value = new IncomeDeduction(); value.setId(UUID.randomUUID());
        value.setIncomeEntryId(incomeId); value.setName(name); value.setDeductionType(type);
        value.setPercentageRate(rate == null ? null : new BigDecimal(rate));
        value.setFixedAmount(fixed == null ? null : new BigDecimal(fixed));
        return value;
    }

    private IncomeReceiptDeduction actual(IncomeReceipt receipt, IncomeDeduction planned, String amount) {
        IncomeReceiptDeduction value = new IncomeReceiptDeduction(); value.setId(UUID.randomUUID());
        value.setIncomeReceiptId(receipt.getId()); value.setIncomeDeductionId(planned.getId());
        value.setNameSnapshot(planned.getName()); value.setActualAmount(new BigDecimal(amount));
        return value;
    }

    private BudgetItem item(String amount) {
        BudgetItem value = new BudgetItem(); value.setId(UUID.randomUUID()); value.setBudgetMonthId(UUID.randomUUID());
        value.setSpaceId(UUID.randomUUID()); value.setName("Work Transport"); value.setCategoryId(UUID.randomUUID());
        value.setPlannedAmount(new BigDecimal(amount)); value.setItemType(BudgetItemType.PLANNED); value.setTracked(true); return value;
    }

    private BudgetFundingAllocation allocation(UUID itemId, UUID incomeId, String planned, String confirmed) {
        BudgetFundingAllocation value = new BudgetFundingAllocation(); value.setId(UUID.randomUUID()); value.setBudgetItemId(itemId);
        value.setIncomeEntryId(incomeId); value.setSourceType(FundingSourceType.INCOME_ENTRY);
        value.setPlannedAmount(new BigDecimal(planned)); value.setConfirmedAllocatedAmount(new BigDecimal(confirmed)); return value;
    }

    private SpendingEntry spending(String amount, TransactionType type) {
        SpendingEntry value = new SpendingEntry(); value.setAmount(new BigDecimal(amount)); value.setTransactionType(type);
        value.setSpentAt(Instant.parse("2026-07-15T10:00:00Z")); return value;
    }
}
