package solutions.shapeit.wethrive.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import solutions.shapeit.wethrive.common.domain.DomainEnums.BudgetItemType;
import solutions.shapeit.wethrive.common.domain.DomainEnums.BudgetStatus;
import solutions.shapeit.wethrive.common.domain.DomainEnums.DeductionType;
import solutions.shapeit.wethrive.common.domain.DomainEnums.FundingSourceType;
import solutions.shapeit.wethrive.common.domain.DomainEnums.IncomeStatus;
import solutions.shapeit.wethrive.common.domain.DomainEnums.TransactionType;
import solutions.shapeit.wethrive.finance.entity.BudgetFundingAllocation;
import solutions.shapeit.wethrive.finance.entity.BudgetItem;
import solutions.shapeit.wethrive.finance.entity.BudgetMonth;
import solutions.shapeit.wethrive.finance.entity.Category;
import solutions.shapeit.wethrive.finance.entity.IncomeEntry;
import solutions.shapeit.wethrive.finance.entity.IncomeDeduction;
import solutions.shapeit.wethrive.finance.entity.IncomeReceipt;
import solutions.shapeit.wethrive.finance.entity.IncomeReceiptDeduction;
import solutions.shapeit.wethrive.finance.entity.SpendingEntry;
import solutions.shapeit.wethrive.finance.repository.BudgetFundingAllocationRepository;
import solutions.shapeit.wethrive.finance.repository.BudgetItemRepository;
import solutions.shapeit.wethrive.finance.repository.BudgetMonthRepository;
import solutions.shapeit.wethrive.finance.repository.CategoryRepository;
import solutions.shapeit.wethrive.finance.repository.IncomeEntryRepository;
import solutions.shapeit.wethrive.finance.repository.IncomeDeductionRepository;
import solutions.shapeit.wethrive.finance.repository.IncomeReceiptRepository;
import solutions.shapeit.wethrive.finance.repository.IncomeReceiptDeductionRepository;
import solutions.shapeit.wethrive.finance.repository.SpendingEntryRepository;
import solutions.shapeit.wethrive.finance.service.FinanceCalculationService;
import solutions.shapeit.wethrive.identity.repository.AppUserRepository;
import solutions.shapeit.wethrive.identity.entity.AppUser;
import solutions.shapeit.wethrive.space.entity.Space;
import solutions.shapeit.wethrive.space.repository.SpaceRepository;
import solutions.shapeit.wethrive.space.service.SpaceAccessService;
import solutions.shapeit.wethrive.report.dto.ReportDtos.ReportFilter;

class ReportServiceFinanceDetailTest {
    private static final UUID SPACE = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID BUDGET = UUID.randomUUID();
    private static final UUID INCOME = UUID.randomUUID();
    private static final UUID ITEM = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-07-15T10:00:00Z");

    private final BudgetMonthRepository budgets = mock(BudgetMonthRepository.class);
    private final IncomeEntryRepository income = mock(IncomeEntryRepository.class);
    private final IncomeReceiptRepository receipts = mock(IncomeReceiptRepository.class);
    private final IncomeDeductionRepository deductions = mock(IncomeDeductionRepository.class);
    private final IncomeReceiptDeductionRepository receiptDeductions = mock(IncomeReceiptDeductionRepository.class);
    private final BudgetItemRepository items = mock(BudgetItemRepository.class);
    private final BudgetFundingAllocationRepository allocations = mock(BudgetFundingAllocationRepository.class);
    private final SpendingEntryRepository spending = mock(SpendingEntryRepository.class);
    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final SpaceAccessService access = mock(SpaceAccessService.class);
    private final SpaceRepository spaces = mock(SpaceRepository.class);
    private final FinanceCalculationService calculations = new FinanceCalculationService();
    private final ReportService reports = new ReportService(budgets, income, receipts, deductions, receiptDeductions,
            items, allocations,
            spending, categories, users, calculations, access, spaces,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private IncomeEntry salary;
    private IncomeReceipt partialReceipt;
    private BudgetItem transport;
    private BudgetFundingAllocation activeFunding;
    private BudgetFundingAllocation reversedFunding;
    private SpendingEntry expense;

    @BeforeEach
    void setUp() {
        BudgetMonth budget = new BudgetMonth(); budget.setId(BUDGET); budget.setSpaceId(SPACE);
        budget.setYear(2026); budget.setMonth(7); budget.setName("July"); budget.setStatus(BudgetStatus.ACTIVE);

        salary = new IncomeEntry(); salary.setId(INCOME); salary.setSpaceId(SPACE); salary.setBudgetMonthId(BUDGET);
        salary.setIncomeTypeId(UUID.randomUUID()); salary.setSourceName("Salary");
        salary.setExpectedAmount(new BigDecimal("1000.00")); salary.setExpectedDate(LocalDate.of(2026, 7, 20));
        salary.setExpectedTime(LocalTime.of(9, 0)); salary.setTimeZone("UTC");
        salary.setTitheRate(new BigDecimal("0.1000"));

        partialReceipt = new IncomeReceipt(); partialReceipt.setId(UUID.randomUUID()); partialReceipt.setSpaceId(SPACE);
        partialReceipt.setIncomeEntryId(INCOME); partialReceipt.setAmount(new BigDecimal("400.00"));
        partialReceipt.setReceivedAt(NOW.minusSeconds(3600)); partialReceipt.setTimeZone("UTC");

        transport = new BudgetItem(); transport.setId(ITEM); transport.setSpaceId(SPACE); transport.setBudgetMonthId(BUDGET);
        transport.setName("Transport"); transport.setCategoryId(UUID.randomUUID());
        transport.setPlannedAmount(new BigDecimal("800.00")); transport.setItemType(BudgetItemType.PLANNED);

        activeFunding = funding("800.00", "300.00", null);
        reversedFunding = funding("100.00", "100.00", NOW.minusSeconds(60));

        expense = new SpendingEntry(); expense.setId(UUID.randomUUID()); expense.setSpaceId(SPACE);
        expense.setBudgetItemId(ITEM); expense.setTransactionType(TransactionType.EXPENSE);
        expense.setTitle("Taxi"); expense.setAmount(new BigDecimal("350.00")); expense.setSpentAt(NOW);
        expense.setUserSelectedDate(LocalDate.of(2026, 7, 15)); expense.setUserSelectedTime(LocalTime.of(12, 0));
        expense.setTimeZone("UTC"); expense.setCreatedByUserId(ACTOR);

        Space space = new Space(); space.setId(SPACE); space.setTimeZone("UTC");
        when(spaces.findByIdAndDeletedAtIsNull(SPACE)).thenReturn(Optional.of(space));
        when(categories.findAllBySpaceIdAndDeletedAtIsNullOrderBySortOrderAscNameAsc(SPACE)).thenReturn(List.of());
        when(budgets.findBySpaceIdAndYearAndMonthAndDeletedAtIsNull(SPACE, 2026, 7)).thenReturn(Optional.of(budget));
        when(budgets.findBySpaceIdAndYearAndMonthAndDeletedAtIsNull(SPACE, 2026, 8)).thenReturn(Optional.empty());
        when(budgets.findBySpaceIdAndYearAndMonthAndDeletedAtIsNull(SPACE, 2026, 9)).thenReturn(Optional.empty());
        when(income.findAllByBudgetMonthIdAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(BUDGET)).thenReturn(List.of(salary));
        when(items.findAllByBudgetMonthIdAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(BUDGET)).thenReturn(List.of(transport));
        when(spending.findAllByBudgetItemIdInAndDeletedAtIsNull(List.of(ITEM))).thenReturn(List.of(expense));
        when(receipts.findAllByIncomeEntryIdInAndDeletedAtIsNull(List.of(INCOME))).thenReturn(List.of(partialReceipt));
        when(allocations.findAllByBudgetItemIdInAndDeletedAtIsNull(List.of(ITEM))).thenReturn(List.of(activeFunding));
        when(allocations.findAllByBudgetItemIdInOrderByCreatedAtAsc(List.of(ITEM)))
                .thenReturn(List.of(activeFunding, reversedFunding));
    }

    @Test
    void monthlyReportExposesIncomeTimingFundingMathAndReversalHistory() {
        var report = reports.monthly(SPACE, 2026, 7, ACTOR);

        assertThat(report.reportTotals().outstandingExpectedIncome()).isEqualByComparingTo("600.00");
        assertThat(report.reportTotals().plannedFunding()).isEqualByComparingTo("800.00");
        assertThat(report.reportTotals().confirmedFundedBudget()).isEqualByComparingTo("300.00");
        assertThat(report.reportTotals().unallocatedReceivedIncome()).isEqualByComparingTo("100.00");
        assertThat(report.reportTotals().unspentAllocatedFunds()).isEqualByComparingTo("0.00");
        assertThat(report.reportTotals().unfundedSpending()).isEqualByComparingTo("50.00");
        assertThat(report.reportTotals().partiallyFundedItemCount()).isEqualTo(1);
        assertThat(report.reportTotals().incomeStatuses().partiallyReceived()).isEqualTo(1);
        assertThat(report.reportTotals().receiptCount()).isEqualTo(1);
        assertThat(report.reportTotals().fundingAllocationCount()).isEqualTo(1);
        assertThat(report.reportTotals().reversedFundingAllocationCount()).isEqualTo(1);

        assertThat(report.incomeSchedule()).singleElement().satisfies(row -> {
            assertThat(row.expectedDate()).isEqualTo(LocalDate.of(2026, 7, 20));
            assertThat(row.status()).isEqualTo(IncomeStatus.PARTIALLY_RECEIVED);
            assertThat(row.plannedFunding()).isEqualByComparingTo("800.00");
            assertThat(row.confirmedAllocation()).isEqualByComparingTo("300.00");
        });
        assertThat(report.incomeToBudgetMappings()).hasSize(2);
        assertThat(report.incomeToBudgetMappings()).filteredOn(row -> row.reversedAt() != null).hasSize(1);
        assertThat(report.charts().projectedExpensesByCategory()).singleElement().satisfies(value ->
                assertThat(value.amount()).isEqualByComparingTo("800.00"));
        assertThat(report.charts().actualSpendingByCategory()).singleElement().satisfies(value ->
                assertThat(value.amount()).isEqualByComparingTo("350.00"));
        BigDecimal projectedCategoryTotal = report.charts().projectedExpensesByCategory().stream()
                .map(value -> value.amount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(projectedCategoryTotal).isEqualByComparingTo(report.totals().plannedSpending());
        assertThat(report.charts().cashFlow()).singleElement().satisfies(value -> {
            assertThat(value.receipts()).isEqualByComparingTo("400.00");
            assertThat(value.spending()).isEqualByComparingTo("350.00");
            assertThat(value.net()).isEqualByComparingTo("50.00");
        });
        assertThat(report.charts().budgetVariance()).singleElement().satisfies(value ->
                assertThat(value.amount()).isEqualByComparingTo("450.00"));
    }

    @Test
    void memberNamesAreResolvedWithOneBulkLookupInsteadOfPerRowQueries() {
        UUID memberA = UUID.randomUUID();
        UUID memberB = UUID.randomUUID();
        SpendingEntry secondExpense = new SpendingEntry();
        secondExpense.setId(UUID.randomUUID()); secondExpense.setSpaceId(SPACE); secondExpense.setBudgetItemId(ITEM);
        secondExpense.setTransactionType(TransactionType.EXPENSE); secondExpense.setTitle("Bus");
        secondExpense.setAmount(new BigDecimal("50.00")); secondExpense.setSpentAt(NOW);
        secondExpense.setUserSelectedDate(LocalDate.of(2026, 7, 15)); secondExpense.setUserSelectedTime(LocalTime.NOON);
        secondExpense.setTimeZone("UTC"); secondExpense.setSpentByUserId(memberB);
        expense.setSpentByUserId(memberA);
        AppUser first = new AppUser(); first.setId(memberA); first.setDisplayName("First member");
        AppUser second = new AppUser(); second.setId(memberB); second.setDisplayName("Second member");
        when(spending.findAllByBudgetItemIdInAndDeletedAtIsNull(List.of(ITEM)))
                .thenReturn(List.of(expense, secondExpense));
        when(users.findAllById(List.of(memberA, memberB))).thenReturn(List.of(first, second));

        var report = reports.monthly(SPACE, 2026, 7, ACTOR);

        assertThat(report.members()).extracting(value -> value.name())
                .containsExactlyInAnyOrder("First member", "Second member");
        verify(users).findAllById(List.of(memberA, memberB));
        verify(users, never()).findById(memberA);
        verify(users, never()).findById(memberB);
    }

    @Test
    void reportIncomeStatusUsesTheEntryTimeZoneAcrossTheMidnightBoundary() {
        salary.setExpectedDate(LocalDate.of(2026, 7, 16));
        salary.setTimeZone("Pacific/Kiritimati");
        when(receipts.findAllByIncomeEntryIdInAndDeletedAtIsNull(List.of(INCOME))).thenReturn(List.of());

        var report = reports.monthly(SPACE, 2026, 7, ACTOR);

        assertThat(report.incomeSchedule()).singleElement().satisfies(row ->
                assertThat(row.status()).isEqualTo(IncomeStatus.DUE));
        assertThat(report.reportTotals().incomeStatuses().due()).isEqualTo(1);
        assertThat(report.reportTotals().incomeStatuses().scheduled()).isZero();
    }

    @Test
    void reportTotalsAndCashFlowUseGenericPlannedAndReceiptDeductions() {
        IncomeDeduction medical = new IncomeDeduction(); medical.setId(UUID.randomUUID()); medical.setSpaceId(SPACE);
        medical.setIncomeEntryId(INCOME); medical.setName("Medical aid"); medical.setDeductionType(DeductionType.FIXED);
        medical.setFixedAmount(new BigDecimal("100.00")); medical.setSortOrder(0);
        IncomeReceiptDeduction actual = new IncomeReceiptDeduction(); actual.setId(UUID.randomUUID()); actual.setSpaceId(SPACE);
        actual.setIncomeReceiptId(partialReceipt.getId()); actual.setIncomeDeductionId(medical.getId());
        actual.setNameSnapshot("Medical aid"); actual.setActualAmount(new BigDecimal("40.00"));
        when(deductions.findAllByIncomeEntryIdInAndDeletedAtIsNull(List.of(INCOME))).thenReturn(List.of(medical));
        when(receiptDeductions.findAllByIncomeReceiptIdInAndDeletedAtIsNull(List.of(partialReceipt.getId())))
                .thenReturn(List.of(actual));

        var report = reports.monthly(SPACE, 2026, 7, ACTOR);

        assertThat(report.reportTotals().projectedDeductions()).isEqualByComparingTo("100.00");
        assertThat(report.reportTotals().projectedNetIncome()).isEqualByComparingTo("900.00");
        assertThat(report.reportTotals().realizedDeductions()).isEqualByComparingTo("40.00");
        assertThat(report.reportTotals().receivedNetIncome()).isEqualByComparingTo("360.00");
        assertThat(report.reportTotals().unallocatedReceivedIncome()).isEqualByComparingTo("60.00");
        assertThat(report.plannedDeductions()).singleElement().satisfies(value -> {
            assertThat(value.incomeSource()).isEqualTo("Salary");
            assertThat(value.name()).isEqualTo("Medical aid");
            assertThat(value.deductionType()).isEqualTo(DeductionType.FIXED);
            assertThat(value.projectedAmount()).isEqualByComparingTo("100.00");
        });
        assertThat(report.realizedDeductions()).singleElement().satisfies(value -> {
            assertThat(value.incomeSource()).isEqualTo("Salary");
            assertThat(value.name()).isEqualTo("Medical aid");
            assertThat(value.actualAmount()).isEqualByComparingTo("40.00");
            assertThat(value.receivedAt()).isEqualTo(partialReceipt.getReceivedAt());
        });
        assertThat(report.charts().cashFlow()).singleElement().satisfies(value -> {
            assertThat(value.receipts()).isEqualByComparingTo("360.00");
            assertThat(value.spending()).isEqualByComparingTo("350.00");
            assertThat(value.net()).isEqualByComparingTo("10.00");
        });
    }

    @Test
    void sharedFilterLimitsCategoryAndEventDatesWithoutMovingBudgetIncome() {
        Category category = new Category(); category.setId(transport.getCategoryId());
        category.setSpaceId(SPACE); category.setName("Transport");
        when(categories.findAllBySpaceIdAndDeletedAtIsNullOrderBySortOrderAscNameAsc(SPACE))
                .thenReturn(List.of(category));

        var report = reports.monthly(SPACE, 2026, 7, ACTOR,
                new ReportFilter(LocalDate.of(2026, 7, 16), LocalDate.of(2026, 7, 31),
                        "Transport", List.of(BUDGET)));

        assertThat(report.budgetId()).isEqualTo(BUDGET);
        assertThat(report.reportTotals().projectedGrossIncome()).isEqualByComparingTo("1000.00");
        assertThat(report.reportTotals().receivedGrossIncome()).isEqualByComparingTo("400.00");
        assertThat(report.reportTotals().plannedSpending()).isEqualByComparingTo("800.00");
        assertThat(report.reportTotals().confirmedFundedBudget()).isEqualByComparingTo("300.00");
        assertThat(report.reportTotals().netSpending()).isEqualByComparingTo("0.00");
        assertThat(report.charts().budgetVariance()).singleElement().satisfies(value ->
                assertThat(value.amount()).isEqualByComparingTo("800.00"));
        assertThat(report.charts().cashFlow()).isEmpty();
    }

    @Test
    void categoryFilterKeepsReservationsMadeToItemsOutsideTheVisibleCategory() {
        Category transportCategory = new Category(); transportCategory.setId(transport.getCategoryId());
        transportCategory.setSpaceId(SPACE); transportCategory.setName("Transport");
        Category housingCategory = new Category(); housingCategory.setId(UUID.randomUUID());
        housingCategory.setSpaceId(SPACE); housingCategory.setName("Housing");
        BudgetItem rent = new BudgetItem(); rent.setId(UUID.randomUUID()); rent.setSpaceId(SPACE);
        rent.setBudgetMonthId(BUDGET); rent.setName("Rent"); rent.setCategoryId(housingCategory.getId());
        rent.setPlannedAmount(new BigDecimal("500.00")); rent.setItemType(BudgetItemType.PLANNED);
        BudgetFundingAllocation rentFunding = new BudgetFundingAllocation(); rentFunding.setId(UUID.randomUUID());
        rentFunding.setSpaceId(SPACE); rentFunding.setBudgetItemId(rent.getId()); rentFunding.setIncomeEntryId(INCOME);
        rentFunding.setSourceType(FundingSourceType.INCOME_ENTRY); rentFunding.setPlannedAmount(new BigDecimal("50.00"));
        rentFunding.setConfirmedAllocatedAmount(new BigDecimal("50.00")); rentFunding.setAllocatedAt(NOW);
        rentFunding.setTimeZone("UTC");
        when(categories.findAllBySpaceIdAndDeletedAtIsNullOrderBySortOrderAscNameAsc(SPACE))
                .thenReturn(List.of(transportCategory, housingCategory));
        when(items.findAllByBudgetMonthIdAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(BUDGET))
                .thenReturn(List.of(transport, rent));
        when(allocations.findAllByBudgetItemIdInAndDeletedAtIsNull(List.of(ITEM, rent.getId())))
                .thenReturn(List.of(activeFunding, rentFunding));
        when(allocations.findAllByBudgetItemIdInOrderByCreatedAtAsc(List.of(ITEM)))
                .thenReturn(List.of(activeFunding, reversedFunding));

        var report = reports.monthly(SPACE, 2026, 7, ACTOR,
                new ReportFilter(null, null, "Transport", List.of(BUDGET)));

        assertThat(report.totals().items()).extracting(value -> value.name()).containsExactly("Transport");
        assertThat(report.reportTotals().confirmedAllocatedIncome()).isEqualByComparingTo("350.00");
        assertThat(report.reportTotals().unallocatedReceivedIncome()).isEqualByComparingTo("50.00");
        assertThat(report.reportTotals().confirmedFundedBudget()).isEqualByComparingTo("300.00");
    }

    @Test
    void sharedFilterRejectsUnselectedBudgetAndUnknownCategoryProducesNoItemTotals() {
        var unselected = reports.monthly(SPACE, 2026, 7, ACTOR,
                new ReportFilter(null, null, null, List.of(UUID.randomUUID())));
        assertThat(unselected.budgetId()).isNull();
        assertThat(unselected.reportTotals().projectedGrossIncome()).isEqualByComparingTo("0.00");

        var unknownCategory = reports.monthly(SPACE, 2026, 7, ACTOR,
                new ReportFilter(null, null, "Not present", List.of()));
        assertThat(unknownCategory.reportTotals().projectedGrossIncome()).isEqualByComparingTo("1000.00");
        assertThat(unknownCategory.reportTotals().plannedSpending()).isEqualByComparingTo("0.00");
        assertThat(unknownCategory.reportTotals().confirmedFundedBudget()).isEqualByComparingTo("0.00");
        assertThat(unknownCategory.charts().budgetVariance()).isEmpty();
        assertThat(unknownCategory.charts().fundingStatusDistribution()).extracting(value -> value.amount())
                .allMatch(value -> value.signum() == 0);
    }

    @Test
    void quarterlyReportAggregatesNewMetricsAndCarriesDetailedMappings() {
        var report = reports.quarterly(SPACE, 2026, 3, ACTOR);

        assertThat(report.months()).hasSize(3);
        assertThat(report.totals().plannedFunding()).isEqualByComparingTo("800.00");
        assertThat(report.totals().unfundedSpending()).isEqualByComparingTo("50.00");
        assertThat(report.totals().incomeStatuses().partiallyReceived()).isEqualTo(1);
        assertThat(report.incomeSchedule()).hasSize(1);
        assertThat(report.incomeToBudgetMappings()).hasSize(2);
    }

    private BudgetFundingAllocation funding(String planned, String confirmed, Instant deletedAt) {
        BudgetFundingAllocation value = new BudgetFundingAllocation(); value.setId(UUID.randomUUID()); value.setSpaceId(SPACE);
        value.setBudgetItemId(ITEM); value.setIncomeEntryId(INCOME); value.setSourceType(FundingSourceType.INCOME_ENTRY);
        value.setPlannedAmount(new BigDecimal(planned)); value.setConfirmedAllocatedAmount(new BigDecimal(confirmed));
        value.setAllocatedAt(NOW.minusSeconds(120)); value.setTimeZone("UTC"); value.setCreatedAt(NOW.minusSeconds(300));
        value.setUpdatedAt(NOW.minusSeconds(60)); value.setDeletedAt(deletedAt); return value;
    }
}
