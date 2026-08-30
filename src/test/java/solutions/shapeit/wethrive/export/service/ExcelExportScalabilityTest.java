package solutions.shapeit.wethrive.export.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import solutions.shapeit.wethrive.TestProperties;
import solutions.shapeit.wethrive.common.domain.DomainEnums.Role;
import solutions.shapeit.wethrive.common.domain.DomainEnums.SpaceType;
import solutions.shapeit.wethrive.common.domain.DomainEnums.TransactionType;
import solutions.shapeit.wethrive.common.web.ApiException;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.SpendingResponse;
import solutions.shapeit.wethrive.finance.entity.BudgetItem;
import solutions.shapeit.wethrive.finance.entity.BudgetMonth;
import solutions.shapeit.wethrive.finance.service.BudgetService;
import solutions.shapeit.wethrive.finance.service.FinanceCalculationService;
import solutions.shapeit.wethrive.finance.service.SpendingService;
import solutions.shapeit.wethrive.finance.repository.BudgetFundingAllocationRepository;
import solutions.shapeit.wethrive.finance.repository.BudgetItemRepository;
import solutions.shapeit.wethrive.finance.repository.BudgetMonthRepository;
import solutions.shapeit.wethrive.finance.repository.IncomeDeductionRepository;
import solutions.shapeit.wethrive.finance.repository.IncomeEntryRepository;
import solutions.shapeit.wethrive.finance.repository.IncomeReceiptDeductionRepository;
import solutions.shapeit.wethrive.finance.repository.IncomeReceiptRepository;
import solutions.shapeit.wethrive.finance.repository.SpendingEntryRepository;
import solutions.shapeit.wethrive.identity.repository.AppUserRepository;
import solutions.shapeit.wethrive.report.service.ReportService;
import solutions.shapeit.wethrive.space.dto.SpaceDtos.SpaceResponse;
import solutions.shapeit.wethrive.space.repository.SpaceMembershipRepository;
import solutions.shapeit.wethrive.space.service.SpaceAccessService;
import solutions.shapeit.wethrive.space.service.SpaceService;

class ExcelExportScalabilityTest {
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID SPACE = UUID.randomUUID();
    private static final UUID BUDGET = UUID.randomUUID();
    private static final UUID ITEM = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-07-15T10:00:00Z");
    private static final int ROW_COUNT = 2_500;

    @Test
    void largeStreamingLedgerReopensWithBoundedReusableStyles() throws Exception {
        BudgetService budgets = mock(BudgetService.class);
        SpendingService spending = mock(SpendingService.class);
        ReportService reports = mock(ReportService.class);
        SpaceService spaces = mock(SpaceService.class);
        SpaceMembershipRepository memberships = mock(SpaceMembershipRepository.class);
        AppUserRepository users = mock(AppUserRepository.class);
        BudgetItemRepository items = mock(BudgetItemRepository.class);
        BudgetMonthRepository budgetMonths = mock(BudgetMonthRepository.class);
        IncomeEntryRepository income = mock(IncomeEntryRepository.class);
        IncomeReceiptRepository receipts = mock(IncomeReceiptRepository.class);
        IncomeDeductionRepository deductions = mock(IncomeDeductionRepository.class);
        IncomeReceiptDeductionRepository actuals = mock(IncomeReceiptDeductionRepository.class);
        BudgetFundingAllocationRepository funding = mock(BudgetFundingAllocationRepository.class);
        SpendingEntryRepository spendingEntries = mock(SpendingEntryRepository.class);
        SpaceAccessService access = mock(SpaceAccessService.class);
        ExcelExportService exports = new ExcelExportService(budgets, spending, reports, spaces, memberships,
                users, items, budgetMonths, income, receipts, deductions, actuals, funding, spendingEntries,
                new FinanceCalculationService(), access,
                TestProperties.create(), Clock.fixed(NOW, ZoneOffset.UTC));
        SpaceResponse space = new SpaceResponse(SPACE, SpaceType.PERSONAL, "Personal", "personal", ACTOR,
                "ZAR", "en-ZA", "UTC", Role.OWNER, 0, NOW, NOW);
        List<SpendingResponse> entries = IntStream.range(0, ROW_COUNT).mapToObj(index -> new SpendingResponse(
                UUID.randomUUID(), SPACE, ITEM, index % 10 == 0 ? TransactionType.REFUND : TransactionType.EXPENSE,
                "Ledger row " + index, new BigDecimal("12.34"), NOW.minusSeconds(index),
                LocalDate.of(2026, 7, 15), LocalTime.of(10, index % 60), "UTC", "Card", "Merchant",
                "Review note " + index, ACTOR, ACTOR, 0)).toList();

        when(spaces.get(SPACE, ACTOR)).thenReturn(space);
        when(spending.list(eq(ACTOR), eq(SPACE), isNull(), isNull(), anyInt(), eq(200))).thenAnswer(invocation -> {
            int page = invocation.getArgument(4);
            int from = Math.min(page * 200, entries.size());
            int to = Math.min(from + 200, entries.size());
            return new PageImpl<>(entries.subList(from, to), PageRequest.of(page, 200), entries.size());
        });

        byte[] bytes = exports.spending(ACTOR, SPACE, null, null, null);
        assertThat(bytes).isNotEmpty();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var ledger = workbook.getSheet("Spending Ledger");
            assertThat(ledger.getLastRowNum()).isEqualTo(5 + ROW_COUNT + 3);
            assertThat(ledger.getRow(5).getCell(7).getStringCellValue()).isEqualTo("Budget Item");
            assertThat(ledger.getRow(5).getCell(8).getStringCellValue()).isEqualTo("Spender");
            assertThat(ledger.getRow(6).getCell(0).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(ledger.getRow(6).getCell(1).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(ledger.getRow(6).getCell(4).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(ledger.getRow(6).getCell(7).getCellType()).isEqualTo(CellType.STRING);
            assertThat(ledger.getRow(6).getCell(7).getStringCellValue()).isEqualTo("Archived budget item");
            assertThat(ledger.getRow(6).getCell(8).getStringCellValue()).isEqualTo("Former member");
            assertThat(ledger.getRow(6).getCell(0).getCellStyle().getIndex())
                    .isEqualTo(ledger.getRow(5 + ROW_COUNT).getCell(0).getCellStyle().getIndex());
            assertThat(ledger.getRow(6).getCell(4).getCellStyle().getIndex())
                    .isEqualTo(ledger.getRow(5 + ROW_COUNT).getCell(4).getCellStyle().getIndex());
            assertThat(ledger.getRow(6 + ROW_COUNT).getCell(3).getStringCellValue()).isEqualTo("Gross expenses");
            assertThat(workbook.getSheet("Export Information").getRow(5).getCell(1).getCellType())
                    .isEqualTo(CellType.NUMERIC);
            assertThat(workbook.getNumCellStyles()).isLessThanOrEqualTo(16);
        }
        verify(items, times(1)).findAllByIdInAndSpaceIdInAndDeletedAtIsNull(Set.of(ITEM), Set.of(SPACE));
        verify(memberships, times(1)).findAllBySpaceIdInAndUserIdInAndDeletedAtIsNull(
                Set.of(SPACE), Set.of(ACTOR));
        verify(users, times(1)).findAllById(List.of());
        verify(budgets, never()).listForExport(SPACE, ACTOR);
    }

    @Test
    void oversizedLedgerIsRejectedFromTheFirstPageBeforeWorkbookMaterialization() {
        SpendingService spending = mock(SpendingService.class);
        SpaceService spaces = mock(SpaceService.class);
        SpaceAccessService access = mock(SpaceAccessService.class);
        ExcelExportService exports = new ExcelExportService(null, spending, null, spaces,
                null, null, null, null, null, null, null, null, null, null,
                new FinanceCalculationService(), access, TestProperties.create(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        SpaceResponse space = new SpaceResponse(SPACE, SpaceType.PERSONAL, "Personal", "personal", ACTOR,
                "ZAR", "en-ZA", "UTC", Role.OWNER, 0, NOW, NOW);
        SpendingResponse first = new SpendingResponse(UUID.randomUUID(), SPACE, ITEM, TransactionType.EXPENSE,
                "First", BigDecimal.ONE, NOW, LocalDate.of(2026, 7, 15), LocalTime.NOON, "UTC",
                "Card", "Merchant", null, ACTOR, ACTOR, 0);
        when(spaces.get(SPACE, ACTOR)).thenReturn(space);
        when(spending.list(ACTOR, SPACE, null, null, 0, 200)).thenReturn(new PageImpl<>(List.of(first),
                PageRequest.of(0, 200), ExcelExportService.MAX_LEDGER_ROWS + 1L));

        assertThatThrownBy(() -> exports.spending(ACTOR, SPACE, null, null, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("25,000-row export limit");
        verify(spending, times(1)).list(ACTOR, SPACE, null, null, 0, 200);
    }

    @Test
    void directBudgetPreflightRejectsWholeSpaceBeforeBudgetDtoMaterialization() {
        BudgetService budgets = mock(BudgetService.class);
        BudgetMonthRepository budgetMonths = mock(BudgetMonthRepository.class);
        SpaceAccessService access = mock(SpaceAccessService.class);
        BudgetMonth budget = new BudgetMonth();
        budget.setId(BUDGET);
        budget.setSpaceId(SPACE);
        when(budgets.requireBudget(BUDGET)).thenReturn(budget);
        when(budgetMonths.countBySpaceIdInAndDeletedAtIsNull(Set.of(SPACE)))
                .thenReturn(ExcelExportService.MAX_WORKBOOK_DATA_ROWS + 1L);
        ExcelExportService exports = minimalExports(budgets, budgetMonths, access);

        assertThatThrownBy(() -> exports.budget(BUDGET, ACTOR))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("50,000-row conservative whole-space limit");
        verify(access).require(SPACE, ACTOR, SpaceAccessService.Capability.EXPORT);
        verify(budgets, never()).getForExport(BUDGET, ACTOR);
    }

    @Test
    void directBudgetItemPreflightRejectsWholeSpaceBeforeItemDtoMaterialization() {
        BudgetService budgets = mock(BudgetService.class);
        BudgetMonthRepository budgetMonths = mock(BudgetMonthRepository.class);
        SpaceAccessService access = mock(SpaceAccessService.class);
        BudgetItem item = new BudgetItem();
        item.setId(ITEM);
        item.setSpaceId(SPACE);
        when(budgets.requireItem(ITEM)).thenReturn(item);
        when(budgetMonths.countBySpaceIdInAndDeletedAtIsNull(Set.of(SPACE)))
                .thenReturn(ExcelExportService.MAX_WORKBOOK_DATA_ROWS + 1L);
        ExcelExportService exports = minimalExports(budgets, budgetMonths, access);

        assertThatThrownBy(() -> exports.budgetItem(ITEM, ACTOR))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("50,000-row conservative whole-space limit");
        verify(access).require(SPACE, ACTOR, SpaceAccessService.Capability.EXPORT);
        verify(budgets, never()).getItem(ITEM, ACTOR);
    }

    @Test
    void compressedWorkbookBufferRejectsTheFirstByteBeyondItsLimit() {
        ExcelExportService.BoundedByteArrayOutputStream output =
                new ExcelExportService.BoundedByteArrayOutputStream(4);
        output.write(new byte[] {1, 2, 3, 4}, 0, 4);

        assertThatThrownBy(() -> output.write(5))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("64 MiB response limit");
        assertThat(output.size()).isEqualTo(4);
    }

    private static ExcelExportService minimalExports(BudgetService budgets,
                                                      BudgetMonthRepository budgetMonths,
                                                      SpaceAccessService access) {
        return new ExcelExportService(budgets, null, null, null, null, null, null, budgetMonths,
                null, null, null, null, null, null, new FinanceCalculationService(), access,
                TestProperties.create(), Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
