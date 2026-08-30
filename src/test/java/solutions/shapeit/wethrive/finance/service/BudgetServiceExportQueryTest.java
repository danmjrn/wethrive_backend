package solutions.shapeit.wethrive.finance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import solutions.shapeit.wethrive.audit.service.AuditService;
import solutions.shapeit.wethrive.common.domain.DomainChangeRecorder;
import solutions.shapeit.wethrive.common.domain.DomainEnums.BudgetItemType;
import solutions.shapeit.wethrive.common.domain.DomainEnums.BudgetStatus;
import solutions.shapeit.wethrive.finance.entity.BudgetItem;
import solutions.shapeit.wethrive.finance.entity.BudgetMonth;
import solutions.shapeit.wethrive.finance.entity.IncomeEntry;
import solutions.shapeit.wethrive.finance.entity.IncomeReceipt;
import solutions.shapeit.wethrive.finance.repository.BudgetFundingAllocationRepository;
import solutions.shapeit.wethrive.finance.repository.BudgetItemRepository;
import solutions.shapeit.wethrive.finance.repository.BudgetMonthRepository;
import solutions.shapeit.wethrive.finance.repository.IncomeDeductionRepository;
import solutions.shapeit.wethrive.finance.repository.IncomeEntryRepository;
import solutions.shapeit.wethrive.finance.repository.IncomeReceiptDeductionRepository;
import solutions.shapeit.wethrive.finance.repository.IncomeReceiptRepository;
import solutions.shapeit.wethrive.finance.repository.SpendingEntryRepository;
import solutions.shapeit.wethrive.space.entity.Space;
import solutions.shapeit.wethrive.space.service.SpaceAccessService;
import solutions.shapeit.wethrive.space.service.SpaceAccessService.Capability;
import solutions.shapeit.wethrive.space.service.SpaceService;

class BudgetServiceExportQueryTest {
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SPACE = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant NOW = Instant.parse("2026-07-15T10:00:00Z");

    private final BudgetMonthRepository budgets = mock(BudgetMonthRepository.class);
    private final IncomeEntryRepository income = mock(IncomeEntryRepository.class);
    private final IncomeReceiptRepository receipts = mock(IncomeReceiptRepository.class);
    private final IncomeDeductionRepository deductions = mock(IncomeDeductionRepository.class);
    private final IncomeReceiptDeductionRepository actuals = mock(IncomeReceiptDeductionRepository.class);
    private final BudgetItemRepository items = mock(BudgetItemRepository.class);
    private final BudgetFundingAllocationRepository allocations = mock(BudgetFundingAllocationRepository.class);
    private final SpendingEntryRepository spending = mock(SpendingEntryRepository.class);
    private final SpaceAccessService access = mock(SpaceAccessService.class);
    private final SpaceService spaces = mock(SpaceService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<DomainChangeRecorder> changes = mock(ObjectProvider.class);
    private final BudgetService service = new BudgetService(budgets, income, receipts, deductions, actuals,
            items, allocations, spending, mock(ReferenceFinanceService.class), new FinanceCalculationService(),
            access, spaces, mock(AuditService.class), changes, Clock.fixed(NOW, ZoneOffset.UTC),
            mock(ApplicationEventPublisher.class));

    @Test
    void periodExportLoadsAllBudgetGraphsWithOneBulkQueryPerRepository() {
        BudgetMonth january = budget(1);
        BudgetMonth february = budget(2);
        List<BudgetMonth> budgetValues = List.of(february, january);
        List<UUID> budgetIds = List.of(february.getId(), january.getId());
        IncomeEntry februaryIncome = income(february, "2200.00");
        IncomeEntry januaryIncome = income(january, "1100.00");
        List<IncomeEntry> incomeValues = List.of(februaryIncome, januaryIncome);
        List<UUID> incomeIds = List.of(februaryIncome.getId(), januaryIncome.getId());
        BudgetItem februaryItem = item(february, "1200.00");
        BudgetItem januaryItem = item(january, "600.00");
        List<BudgetItem> itemValues = List.of(februaryItem, januaryItem);
        List<UUID> itemIds = List.of(februaryItem.getId(), januaryItem.getId());
        IncomeReceipt februaryReceipt = receipt(februaryIncome, "1000.00");
        IncomeReceipt januaryReceipt = receipt(januaryIncome, "500.00");
        List<IncomeReceipt> receiptValues = List.of(februaryReceipt, januaryReceipt);
        List<UUID> receiptIds = List.of(februaryReceipt.getId(), januaryReceipt.getId());
        when(budgets.findAllBySpaceIdAndYearAndMonthBetweenAndDeletedAtIsNullOrderByYearDescMonthDesc(
                SPACE, 2026, 1, 12)).thenReturn(budgetValues);
        when(income.findAllByBudgetMonthIdInAndDeletedAtIsNull(budgetIds)).thenReturn(incomeValues);
        when(items.findAllByBudgetMonthIdInAndDeletedAtIsNull(budgetIds)).thenReturn(itemValues);
        when(spending.findAllByBudgetItemIdInAndDeletedAtIsNull(itemIds)).thenReturn(List.of());
        when(receipts.findAllByIncomeEntryIdInAndDeletedAtIsNull(incomeIds)).thenReturn(receiptValues);
        when(deductions.findAllByIncomeEntryIdInAndDeletedAtIsNull(incomeIds)).thenReturn(List.of());
        when(actuals.findAllByIncomeReceiptIdInAndDeletedAtIsNull(receiptIds)).thenReturn(List.of());
        when(allocations.findAllByBudgetItemIdInAndDeletedAtIsNull(itemIds)).thenReturn(List.of());
        Space space = new Space();
        space.setId(SPACE);
        space.setTimeZone("Africa/Johannesburg");
        when(spaces.requireSpace(SPACE)).thenReturn(space);

        var result = service.listForExport(SPACE, 2026, 1, 12, ACTOR);

        assertThat(result).extracting(value -> value.id()).containsExactly(february.getId(), january.getId());
        assertThat(result).extracting(value -> value.summary().projectedGrossIncome())
                .containsExactly(new BigDecimal("2200.00"), new BigDecimal("1100.00"));
        assertThat(result).extracting(value -> value.summary().receivedGrossIncome())
                .containsExactly(new BigDecimal("1000.00"), new BigDecimal("500.00"));
        verify(access).require(SPACE, ACTOR, Capability.EXPORT);
        verify(income, times(1)).findAllByBudgetMonthIdInAndDeletedAtIsNull(budgetIds);
        verify(items, times(1)).findAllByBudgetMonthIdInAndDeletedAtIsNull(budgetIds);
        verify(spending, times(1)).findAllByBudgetItemIdInAndDeletedAtIsNull(itemIds);
        verify(receipts, times(1)).findAllByIncomeEntryIdInAndDeletedAtIsNull(incomeIds);
        verify(deductions, times(1)).findAllByIncomeEntryIdInAndDeletedAtIsNull(incomeIds);
        verify(actuals, times(1)).findAllByIncomeReceiptIdInAndDeletedAtIsNull(receiptIds);
        verify(allocations, times(1)).findAllByBudgetItemIdInAndDeletedAtIsNull(itemIds);
        verify(spaces, times(1)).requireSpace(SPACE);
        verify(income, never()).findAllByBudgetMonthIdAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(
                january.getId());
        verify(income, never()).findAllByBudgetMonthIdAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(
                february.getId());
        verify(items, never()).findAllByBudgetMonthIdAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(
                january.getId());
        verify(items, never()).findAllByBudgetMonthIdAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(
                february.getId());
        verify(budgets, never()).findAllBySpaceIdAndDeletedAtIsNullOrderByYearDescMonthDesc(SPACE);
    }

    private static BudgetMonth budget(int month) {
        BudgetMonth value = new BudgetMonth();
        value.setId(UUID.randomUUID());
        value.setSpaceId(SPACE);
        value.setYear(2026);
        value.setMonth(month);
        value.setName("Month " + month);
        value.setStatus(BudgetStatus.ACTIVE);
        value.setCreatedAt(NOW);
        return value;
    }

    private static IncomeEntry income(BudgetMonth budget, String amount) {
        IncomeEntry value = new IncomeEntry();
        value.setId(UUID.randomUUID());
        value.setSpaceId(SPACE);
        value.setBudgetMonthId(budget.getId());
        value.setSourceName("Salary " + budget.getMonth());
        value.setIncomeTypeId(UUID.randomUUID());
        value.setExpectedAmount(new BigDecimal(amount));
        value.setExpectedDate(LocalDate.of(2026, budget.getMonth(), 20));
        value.setTimeZone("Africa/Johannesburg");
        value.setTitheRate(new BigDecimal("0.1000"));
        value.setCreatedAt(NOW);
        return value;
    }

    private static BudgetItem item(BudgetMonth budget, String amount) {
        BudgetItem value = new BudgetItem();
        value.setId(UUID.randomUUID());
        value.setSpaceId(SPACE);
        value.setBudgetMonthId(budget.getId());
        value.setName("Housing " + budget.getMonth());
        value.setCategoryId(UUID.randomUUID());
        value.setPlannedAmount(new BigDecimal(amount));
        value.setItemType(BudgetItemType.PLANNED);
        value.setCreatedAt(NOW);
        return value;
    }

    private static IncomeReceipt receipt(IncomeEntry income, String amount) {
        IncomeReceipt value = new IncomeReceipt();
        value.setId(UUID.randomUUID());
        value.setSpaceId(SPACE);
        value.setIncomeEntryId(income.getId());
        value.setAmount(new BigDecimal(amount));
        value.setReceivedAt(NOW);
        value.setTimeZone("Africa/Johannesburg");
        value.setRecordedByUserId(ACTOR);
        value.setCreatedAt(NOW);
        return value;
    }
}
