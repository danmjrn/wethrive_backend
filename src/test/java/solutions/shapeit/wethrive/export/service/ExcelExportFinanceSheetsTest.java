package solutions.shapeit.wethrive.export.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import solutions.shapeit.wethrive.TestProperties;
import solutions.shapeit.wethrive.common.domain.DomainEnums.BudgetItemType;
import solutions.shapeit.wethrive.common.domain.DomainEnums.BudgetStatus;
import solutions.shapeit.wethrive.common.domain.DomainEnums.FundingSourceType;
import solutions.shapeit.wethrive.common.domain.DomainEnums.FundingStatus;
import solutions.shapeit.wethrive.common.domain.DomainEnums.DeductionType;
import solutions.shapeit.wethrive.common.domain.DomainEnums.IncomeStatus;
import solutions.shapeit.wethrive.common.domain.DomainEnums.MembershipStatus;
import solutions.shapeit.wethrive.common.domain.DomainEnums.Role;
import solutions.shapeit.wethrive.common.domain.DomainEnums.SpaceType;
import solutions.shapeit.wethrive.common.web.ApiException;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.BudgetItemResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.BudgetResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.BudgetSummary;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.FundingAllocationResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.FundingSummary;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeAvailability;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.ItemCalculation;
import solutions.shapeit.wethrive.finance.entity.BudgetFundingAllocation;
import solutions.shapeit.wethrive.finance.entity.BudgetMonth;
import solutions.shapeit.wethrive.finance.entity.IncomeDeduction;
import solutions.shapeit.wethrive.finance.entity.IncomeReceipt;
import solutions.shapeit.wethrive.finance.entity.IncomeReceiptDeduction;
import solutions.shapeit.wethrive.finance.repository.BudgetFundingAllocationRepository;
import solutions.shapeit.wethrive.finance.repository.BudgetItemRepository;
import solutions.shapeit.wethrive.finance.repository.BudgetMonthRepository;
import solutions.shapeit.wethrive.finance.repository.IncomeDeductionRepository;
import solutions.shapeit.wethrive.finance.repository.IncomeEntryRepository;
import solutions.shapeit.wethrive.finance.repository.IncomeReceiptDeductionRepository;
import solutions.shapeit.wethrive.finance.repository.IncomeReceiptRepository;
import solutions.shapeit.wethrive.finance.repository.SpendingEntryRepository;
import solutions.shapeit.wethrive.finance.service.BudgetService;
import solutions.shapeit.wethrive.finance.service.FinanceCalculationService;
import solutions.shapeit.wethrive.finance.service.SpendingService;
import solutions.shapeit.wethrive.identity.repository.AppUserRepository;
import solutions.shapeit.wethrive.identity.entity.AppUser;
import solutions.shapeit.wethrive.report.dto.ReportDtos.AnnualReport;
import solutions.shapeit.wethrive.report.dto.ReportDtos.IncomeStatusCounts;
import solutions.shapeit.wethrive.report.dto.ReportDtos.MonthlyReport;
import solutions.shapeit.wethrive.report.dto.ReportDtos.NamedAmount;
import solutions.shapeit.wethrive.report.dto.ReportDtos.PeriodTotals;
import solutions.shapeit.wethrive.report.dto.ReportDtos.PeriodRow;
import solutions.shapeit.wethrive.report.dto.ReportDtos.QuarterlyReport;
import solutions.shapeit.wethrive.report.dto.ReportDtos.ReportChartData;
import solutions.shapeit.wethrive.report.service.ReportService;
import solutions.shapeit.wethrive.space.dto.SpaceDtos.SpaceResponse;
import solutions.shapeit.wethrive.space.entity.SpaceMembership;
import solutions.shapeit.wethrive.space.repository.SpaceMembershipRepository;
import solutions.shapeit.wethrive.space.service.SpaceAccessService;
import solutions.shapeit.wethrive.space.service.SpaceService;

class ExcelExportFinanceSheetsTest {
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID SPACE = UUID.randomUUID();
    private static final UUID BUDGET = UUID.randomUUID();
    private static final UUID INCOME = UUID.randomUUID();
    private static final UUID ITEM = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-07-15T10:00:00Z");
    private static final List<String> FINANCE_SHEETS = List.of("Income Schedule", "Planned Deductions",
            "Income Receipts", "Actual Deductions", "Funding Plan", "Funding Allocations",
            "Budget Funding Status", "Income Availability");

    private final BudgetService budgets = mock(BudgetService.class);
    private final SpendingService spending = mock(SpendingService.class);
    private final ReportService reports = mock(ReportService.class);
    private final SpaceService spaces = mock(SpaceService.class);
    private final SpaceMembershipRepository memberships = mock(SpaceMembershipRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final BudgetItemRepository itemRepository = mock(BudgetItemRepository.class);
    private final BudgetMonthRepository budgetRepository = mock(BudgetMonthRepository.class);
    private final IncomeEntryRepository incomeRepository = mock(IncomeEntryRepository.class);
    private final IncomeReceiptRepository receiptRepository = mock(IncomeReceiptRepository.class);
    private final IncomeDeductionRepository deductionRepository = mock(IncomeDeductionRepository.class);
    private final IncomeReceiptDeductionRepository actualRepository = mock(IncomeReceiptDeductionRepository.class);
    private final BudgetFundingAllocationRepository fundingRepository = mock(BudgetFundingAllocationRepository.class);
    private final SpendingEntryRepository spendingRepository = mock(SpendingEntryRepository.class);
    private final SpaceAccessService access = mock(SpaceAccessService.class);
    private final FinanceCalculationService calculations = new FinanceCalculationService();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ExcelExportService exports = new ExcelExportService(budgets, spending, reports, spaces, memberships,
            users, itemRepository, budgetRepository, incomeRepository, receiptRepository, deductionRepository, actualRepository,
            fundingRepository, spendingRepository, calculations, access, TestProperties.create(), clock);

    private final SpaceResponse space = new SpaceResponse(SPACE, SpaceType.PERSONAL, "Personal", "personal", ACTOR,
            "ZAR", "en-ZA", "UTC", Role.OWNER, 0, NOW, NOW);

    @BeforeEach
    void setUp() {
        when(spaces.get(SPACE, ACTOR)).thenReturn(space);
        when(budgets.listForExport(SPACE, 2026, 7, 7, ACTOR)).thenReturn(List.of());
        when(budgets.listForExport(SPACE, 2026, 7, 9, ACTOR)).thenReturn(List.of());
        when(budgets.listForExport(SPACE, 2026, 1, 12, ACTOR)).thenReturn(List.of());
    }

    @Test
    void periodExportsUseScopedBudgetQueriesAndDetailedReportsContainAllFinanceSheets() throws Exception {
        var emptyBudget = calculations.budget(List.of(), List.of(), List.of(), List.of(), List.of(),
                YearMonth.of(2026, 7), LocalDate.of(2026, 7, 15));
        PeriodTotals totals = totals();
        when(reports.monthly(SPACE, 2026, 7, ACTOR)).thenReturn(new MonthlyReport(SPACE, null, 2026, 7, null,
                emptyBudget, List.of(), List.of(), List.of(), List.of(), List.of(), totals, List.of(), List.of(), null));
        when(reports.quarterly(SPACE, 2026, 3, ACTOR)).thenReturn(new QuarterlyReport(SPACE, 2026, 3,
                List.of(), totals, List.of(), List.of(), List.of(), List.of()));
        when(reports.annual(SPACE, 2026, ACTOR)).thenReturn(new AnnualReport(SPACE, 2026,
                List.of(), List.of(), totals, List.of(), List.of(), List.of(), List.of()));

        assertFinanceSheets(exports.monthly(ACTOR, SPACE, 2026, 7));
        assertFinanceSheets(exports.quarterly(ACTOR, SPACE, 2026, 3));
        assertFinanceSheets(exports.annual(ACTOR, SPACE, 2026));
        assertThat(exports.full(ACTOR, SPACE, 2026,
                new ExcelExportService.ExcelExportOptions(false, false,
                        ExcelExportService.ExportTheme.SHAPE_IT_MONOCHROME, ""))).isNotEmpty();

        verify(budgets).listForExport(SPACE, 2026, 7, 7, ACTOR);
        verify(budgets).listForExport(SPACE, 2026, 7, 9, ACTOR);
        verify(budgets, times(2)).listForExport(SPACE, 2026, 1, 12, ACTOR);
        verify(budgets, never()).list(SPACE, ACTOR);
    }

    @Test
    void directBudgetExportUsesOneDirectExportDtoInsteadOfLoadingTheSpaceHistory() throws Exception {
        var emptyBudget = calculations.budget(List.of(), List.of(), List.of(), List.of(), List.of(),
                YearMonth.of(2026, 7), LocalDate.of(2026, 7, 15));
        BudgetResponse budget = new BudgetResponse(BUDGET, SPACE, 2026, 7, "July", BudgetStatus.ACTIVE,
                null, 0, NOW, emptyBudget);
        BudgetMonth reference = new BudgetMonth();
        reference.setId(BUDGET);
        reference.setSpaceId(SPACE);
        when(budgets.requireBudget(BUDGET)).thenReturn(reference);
        when(budgets.getForExport(BUDGET, ACTOR)).thenReturn(budget);
        when(reports.monthly(SPACE, 2026, 7, ACTOR)).thenReturn(new MonthlyReport(SPACE, BUDGET, 2026, 7,
                "July", emptyBudget, List.of(), List.of(), List.of(), List.of(), List.of(), totals(),
                List.of(), List.of(), null));
        var options = new ExcelExportService.ExcelExportOptions(false, false,
                ExcelExportService.ExportTheme.SHAPE_IT_MONOCHROME, "");

        byte[] exported = exports.budget(BUDGET, ACTOR, options);
        assertThat(exported).isNotEmpty();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(exported))) {
            assertThat(workbook.getSheet("Export Information").getRow(10).getCell(1).getStringCellValue())
                    .isEqualTo("SHAPE IT MONOCHROME");
            int titleFont = workbook.getSheet("Overview").getRow(0).getCell(0).getCellStyle().getFontIndex();
            assertThat(workbook.getFontAt(titleFont).getXSSFColor().getARGBHex()).endsWith("191919");
            assertThat(workbook.getSheet("Overview").getRow(5).getCell(1).getCellStyle()
                    .getDataFormatString()).doesNotContain("[Red]");
        }

        verify(budgets).getForExport(BUDGET, ACTOR);
        verify(budgets, never()).listForExport(SPACE, ACTOR);
        verify(budgets, never()).listForExport(SPACE, 2026, 7, 7, ACTOR);
        verify(budgets, never()).list(SPACE, ACTOR);
    }

    @Test
    void simpleExportOverloadUsesRequestedPaletteWhileLegacySignatureKeepsOriginalDefault() throws Exception {
        when(spending.list(ACTOR, SPACE, null, null, 0, 200)).thenReturn(new PageImpl<>(List.of()));
        var monochrome = new ExcelExportService.ExcelExportOptions(true, true,
                ExcelExportService.ExportTheme.SHAPE_IT_MONOCHROME, "");

        byte[] originalExport = exports.spending(ACTOR, SPACE, null, null, null);
        byte[] monochromeExport = exports.spending(ACTOR, SPACE, null, null, null, monochrome);

        try (XSSFWorkbook original = new XSSFWorkbook(new ByteArrayInputStream(originalExport));
             XSSFWorkbook configured = new XSSFWorkbook(new ByteArrayInputStream(monochromeExport))) {
            assertThat(original.getSheet("Export Information").getRow(10).getCell(1).getStringCellValue())
                    .isEqualTo("WETHRIVE ORIGINAL");
            assertThat(configured.getSheet("Export Information").getRow(10).getCell(1).getStringCellValue())
                    .isEqualTo("SHAPE IT MONOCHROME");
        }
    }

    @Test
    void financeSheetsIncludeSourceDatesAndReversalHistoryWhileNeutralizingFormulaText() throws Exception {
        var emptyBudget = calculations.budget(List.of(), List.of(), List.of(), List.of(), List.of(),
                YearMonth.of(2026, 7), LocalDate.of(2026, 7, 15));
        BudgetResponse budget = new BudgetResponse(BUDGET, SPACE, 2026, 7, "July", BudgetStatus.ACTIVE,
                null, 0, NOW, emptyBudget);
        IncomeAvailability availability = new IncomeAvailability(new BigDecimal("1000.00"), BigDecimal.ZERO,
                new BigDecimal("1000.00"), new BigDecimal("400.00"), BigDecimal.ZERO,
                new BigDecimal("400.00"), new BigDecimal("300.00"), new BigDecimal("100.00"),
                new BigDecimal("600.00"), IncomeStatus.PARTIALLY_RECEIVED);
        IncomeResponse income = new IncomeResponse(INCOME, BUDGET, SPACE, " \t=Salary", UUID.randomUUID(),
                new BigDecimal("1000.00"), LocalDate.of(2026, 7, 20), LocalTime.of(9, 0), "UTC",
                false, BigDecimal.ZERO, null, false, null, null, "\n=item note", 0, 0, availability);
        UUID receiptId = UUID.randomUUID(); UUID deductionId = UUID.randomUUID();
        UUID actualDeductionId = UUID.randomUUID();
        FundingAllocationResponse active = allocation(null, "800.00", "300.00");
        FundingAllocationResponse reversed = allocation(NOW.minusSeconds(10), "100.00", "100.00");
        FundingSummary funding = new FundingSummary(ITEM, new BigDecimal("800.00"), new BigDecimal("800.00"),
                new BigDecimal("300.00"), new BigDecimal("500.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                FundingStatus.PARTIALLY_FUNDED, List.of(active));
        ItemCalculation itemCalculation = new ItemCalculation(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("800.00"), BigDecimal.ZERO, "Healthy", 0, new BigDecimal("800.00"),
                new BigDecimal("300.00"), new BigDecimal("500.00"), new BigDecimal("300.00"),
                BigDecimal.ZERO, FundingStatus.PARTIALLY_FUNDED);
        BudgetItemResponse item = new BudgetItemResponse(ITEM, BUDGET, SPACE, "\u200B+Transport", UUID.randomUUID(),
                new BigDecimal("800.00"), true, BudgetItemType.PLANNED, false, false, "=item note", 0,
                0, itemCalculation, funding);
        budget = new BudgetResponse(BUDGET, SPACE, 2026, 7, "July", BudgetStatus.ACTIVE,
                null, 0, NOW, withItems(emptyBudget, List.of(item)));
        IncomeReceipt receiptEntity = receiptEntity(receiptId);
        IncomeDeduction plannedEntity = plannedDeductionEntity(deductionId);
        IncomeReceiptDeduction actualEntity = actualDeductionEntity(actualDeductionId, receiptId, deductionId);
        BudgetFundingAllocation activeEntity = allocationEntity(active);
        BudgetFundingAllocation reversedEntity = allocationEntity(reversed);
        SpaceMembership membership = new SpaceMembership(); membership.setId(UUID.randomUUID());
        membership.setSpaceId(SPACE); membership.setUserId(ACTOR); membership.setRole(Role.OWNER);
        membership.setStatus(MembershipStatus.ACTIVE); membership.setCreatedAt(NOW); membership.setUpdatedAt(NOW);
        AppUser user = new AppUser(); user.setId(ACTOR); user.setDisplayName("Sample Member");
        user.setEmail("sample.member@example.test"); user.setNormalizedEmail("sample.member@example.test");
        user.setPasswordHash("unused"); user.setCreatedAt(NOW); user.setUpdatedAt(NOW);
        ReportChartData charts = new ReportChartData(
                List.of(new NamedAmount(null, "Housing", new BigDecimal("500.00")),
                        new NamedAmount(null, "Transport", new BigDecimal("300.00"))),
                List.of(new NamedAmount(null, "Transport", BigDecimal.ZERO.setScale(2))),
                List.of(new NamedAmount(null, "Planned budget", new BigDecimal("800.00")),
                        new NamedAmount(null, "Confirmed funding", new BigDecimal("300.00")),
                        new NamedAmount(null, "Actual spending", BigDecimal.ZERO.setScale(2))),
                List.of(new NamedAmount(null, "Projected net", new BigDecimal("1000.00")),
                        new NamedAmount(null, "Received net", new BigDecimal("400.00")),
                        new NamedAmount(null, "Confirmed allocations", new BigDecimal("300.00")),
                        new NamedAmount(null, "Available to allocate", new BigDecimal("100.00"))),
                List.of(), List.of(), List.of());
        when(reports.monthly(SPACE, 2026, 7, ACTOR)).thenReturn(new MonthlyReport(SPACE, BUDGET, 2026, 7,
                "July", emptyBudget, List.of(), List.of(), List.of(), List.of(), List.of(), totals(), List.of(), List.of(), charts));
        when(budgets.listForExport(SPACE, 2026, 7, 7, ACTOR)).thenReturn(List.of(budget));
        when(budgets.income(BUDGET, ACTOR)).thenReturn(List.of(income));
        when(receiptRepository.countByIncomeEntryIdIn(java.util.Set.of(INCOME))).thenReturn(1L);
        when(receiptRepository.findAllByIncomeEntryIdIn(java.util.Set.of(INCOME))).thenReturn(List.of(receiptEntity));
        when(deductionRepository.countByIncomeEntryIdIn(java.util.Set.of(INCOME))).thenReturn(1L);
        when(deductionRepository.findAllByIncomeEntryIdIn(java.util.Set.of(INCOME))).thenReturn(List.of(plannedEntity));
        when(actualRepository.countByIncomeReceiptIdIn(java.util.Set.of(receiptId))).thenReturn(1L);
        when(actualRepository.findAllByIncomeReceiptIdIn(java.util.Set.of(receiptId))).thenReturn(List.of(actualEntity));
        when(fundingRepository.countByBudgetItemIdIn(java.util.Set.of(ITEM))).thenReturn(2L);
        when(fundingRepository.findAllByBudgetItemIdInOrderByCreatedAtAsc(java.util.Set.of(ITEM)))
                .thenReturn(List.of(activeEntity, reversedEntity));
        when(memberships.findAllBySpaceIdInAndUserIdInAndDeletedAtIsNull(
                java.util.Set.of(SPACE), java.util.Set.of(ACTOR))).thenReturn(List.of(membership));
        when(users.findAllById(List.of(ACTOR))).thenReturn(List.of(user));

        byte[] exported = exports.monthly(ACTOR, SPACE, 2026, 7);
        writeEvidence("wethrive-monthly-report.xlsx", exported);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(exported))) {
            assertThat(workbook.getSheet("Funding Plan").getRow(5).getCell(11).getStringCellValue()).isEqualTo("Reversed At (UTC)");
            assertThat(workbook.getSheet("Funding Plan").getRow(7).getCell(6).getStringCellValue()).isEqualTo("REVERSED");
            assertLiteral(workbook, "Income Schedule", 6, 1, "=");
            assertLiteral(workbook, "Income Schedule", 6, 18, "=");
            assertLiteral(workbook, "Income Receipts", 6, 11, "+");
            assertLiteral(workbook, "Planned Deductions", 6, 2, "@");
            assertLiteral(workbook, "Planned Deductions", 6, 11, "+");
            assertLiteral(workbook, "Actual Deductions", 6, 3, "=");
            assertLiteral(workbook, "Funding Plan", 6, 12, "@");
            assertLiteral(workbook, "Budget Funding Status", 6, 1, "+");
            assertLiteral(workbook, "Budget Funding Status", 6, 7, "=");

            assertThat(workbook.getSheet("Income Schedule").getRow(6).getCell(3).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(workbook.getSheet("Income Schedule").getRow(6).getCell(4).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(workbook.getSheet("Income Receipts").getRow(6).getCell(3).getNumericCellValue()).isEqualTo(100.0);
            assertThat(workbook.getSheet("Income Receipts").getRow(6).getCell(4).getNumericCellValue()).isEqualTo(300.0);
            assertThat(workbook.getSheet("Income Receipts").getRow(6).getCell(5).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(workbook.getSheet("Income Receipts").getRow(6).getCell(7).getStringCellValue()).isEqualTo("Sample Member");
            assertThat(workbook.getSheet("Income Receipts").getRow(6).getCell(8).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(workbook.getSheet("Funding Plan").getRow(6).getCell(7).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(workbook.getSheet("Funding Plan").getRow(7).getCell(11).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(workbook.getSheet("Export Information").getRow(5).getCell(1).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(workbook.getSheet("Export Information").getRow(10).getCell(1).getStringCellValue())
                    .isEqualTo("WETHRIVE ORIGINAL");
            assertThat(workbook.getSheet("Export Information").getRow(15).getCell(1).getStringCellValue())
                    .isEqualTo("1.0.0");
            assertThat(workbook.getSheet("Income Schedule").getRow(5).getCell(0).getCellStyle()
                    .getFillForegroundXSSFColor().getARGBHex()).endsWith("006400");
            assertThat(workbook.getFontAt(workbook.getSheet("Income Schedule").getRow(0).getCell(0)
                    .getCellStyle().getFontIndex()).getXSSFColor().getARGBHex()).endsWith("006400");
            assertThat(workbook.getSheet("Income Schedule").getRow(6).getCell(2).getCellStyle()
                    .getDataFormatString()).contains("[Red]");
            assertThat(workbook.getSheet("Funding Plan").getSheetConditionalFormatting()
                    .getNumConditionalFormattings())
                    .as("the 1.0.0 reference table style has no zebra-striping rule")
                    .isZero();
            assertThat(workbook.getSheet("Overview").getColumnWidth(0)).isGreaterThanOrEqualTo(36 * 256);
            assertThat(workbook.getSheet("Export Information").getColumnWidth(0)).isGreaterThanOrEqualTo(24 * 256);
            assertThat(workbook.getSheet("Export Information").getColumnWidth(1)).isGreaterThanOrEqualTo(54 * 256);
            assertNoInternalIdHeader(workbook, "Income Receipts");
            assertNoInternalIdHeader(workbook, "Funding Plan");
            assertNoInternalIdHeader(workbook, "Funding Allocations");
            assertNoInternalIdHeader(workbook, "Planned Deductions");
            assertNoInternalIdHeader(workbook, "Actual Deductions");
            assertThat(workbook.getAllPictures()).isNotEmpty();
            assertThat(workbook.getSheet("Overview").getDrawingPatriarch().getShapes())
                    .as("brandmark plus two selected summary charts")
                    .hasSizeGreaterThanOrEqualTo(3);
            assertThat(workbook.getSheet("Income Schedule").getRow(0).getCell(0).getStringCellValue()).isEqualTo("WeThrive");
            assertThat(workbook.getSheet("Income Schedule").getRow(1).getCell(0).getStringCellValue())
                    .isEqualTo("Powered by Shape It Solutions");
            assertThat(workbook.getSheet("Budget Funding Status").getRow(7).getCell(0).getStringCellValue()).isEqualTo("Total");
        }
        verify(receiptRepository, times(1)).findAllByIncomeEntryIdIn(java.util.Set.of(INCOME));
        verify(deductionRepository, times(1)).findAllByIncomeEntryIdIn(java.util.Set.of(INCOME));
        verify(actualRepository, times(1)).findAllByIncomeReceiptIdIn(java.util.Set.of(receiptId));
        verify(fundingRepository, times(1)).findAllByBudgetItemIdInOrderByCreatedAtAsc(java.util.Set.of(ITEM));
        verify(budgets, never()).receiptHistory(INCOME, ACTOR);
        verify(budgets, never()).incomeDeductionHistory(INCOME, ACTOR);
        verify(budgets, never()).receiptDeductionHistory(receiptId, ACTOR);
    }

    @Test
    void periodSummariesExposeBudgetVarianceAndCanonicalTotals() throws Exception {
        PeriodTotals period = totals(new BigDecimal("1000.00"), new BigDecimal("250.00"));
        when(reports.annual(SPACE, 2026, ACTOR)).thenReturn(new AnnualReport(SPACE, 2026,
                List.of(new PeriodRow("July", 2026, 7, period)), List.of(), period,
                List.of(), List.of(), List.of(), List.of()));

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(exports.annual(ACTOR, SPACE, 2026)))) {
            var summary = workbook.getSheet("Monthly Summary");
            assertThat(summary.getRow(5).getCell(17).getStringCellValue()).isEqualTo("Budget vs Actual Variance");
            assertThat(summary.getRow(6).getCell(17).getNumericCellValue()).isEqualTo(750.0);
            assertThat(summary.getRow(7).getCell(0).getStringCellValue()).isEqualTo("Total");
            assertThat(summary.getRow(7).getCell(17).getNumericCellValue()).isEqualTo(750.0);
        }
    }

    @Test
    void financeSourceCapRejectsBeforeAnyDetailedHistoryIsLoaded() {
        var emptyBudget = calculations.budget(List.of(), List.of(), List.of(), List.of(), List.of(),
                YearMonth.of(2026, 7), LocalDate.of(2026, 7, 15));
        BudgetResponse budget = new BudgetResponse(BUDGET, SPACE, 2026, 7, "July", BudgetStatus.ACTIVE,
                null, 0, NOW, emptyBudget);
        when(reports.monthly(SPACE, 2026, 7, ACTOR)).thenReturn(new MonthlyReport(SPACE, BUDGET, 2026, 7,
                "July", emptyBudget, List.of(), List.of(), List.of(), List.of(), List.of(), totals(),
                List.of(), List.of(), null));
        when(budgets.listForExport(SPACE, 2026, 7, 7, ACTOR)).thenReturn(List.of(budget));
        when(incomeRepository.countByBudgetMonthIdInAndDeletedAtIsNull(List.of(BUDGET)))
                .thenReturn(ExcelExportService.MAX_FINANCE_SOURCE_ROWS + 1L);

        assertThatThrownBy(() -> exports.monthly(ACTOR, SPACE, 2026, 7))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("25,000-row export limit");
        verify(budgets, never()).income(BUDGET, ACTOR);
        verify(receiptRepository, never()).findAllByIncomeEntryIdIn(java.util.Set.of(INCOME));
        verify(deductionRepository, never()).findAllByIncomeEntryIdIn(java.util.Set.of(INCOME));
    }

    private void assertFinanceSheets(byte[] bytes) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(FINANCE_SHEETS).allSatisfy(name -> assertThat(workbook.getSheet(name)).as(name).isNotNull());
        }
    }

    private void assertLiteral(XSSFWorkbook workbook, String sheetName, int rowNumber, int column, String marker) {
        var cell = workbook.getSheet(sheetName).getRow(rowNumber).getCell(column);
        assertThat(cell.getCellType()).isEqualTo(CellType.STRING);
        assertThat(cell.getStringCellValue()).startsWith("'").contains(marker);
    }

    private void assertNoInternalIdHeader(XSSFWorkbook workbook, String sheetName) {
        var row = workbook.getSheet(sheetName).getRow(5);
        assertThat(IntStream.range(0, row.getLastCellNum()).mapToObj(index -> row.getCell(index).getStringCellValue()))
                .noneMatch(value -> value.endsWith(" ID"));
    }

    private static void writeEvidence(String name, byte[] bytes) throws Exception {
        String directory = System.getProperty("wethrive.export.evidenceDir");
        if (directory == null || directory.isBlank()) return;
        Path target = Path.of(directory).resolve(name);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }

    private FundingAllocationResponse allocation(Instant deletedAt, String planned, String confirmed) {
        return new FundingAllocationResponse(UUID.randomUUID(), ITEM, INCOME, SPACE, FundingSourceType.INCOME_ENTRY,
                new BigDecimal(planned), new BigDecimal(confirmed), NOW.minusSeconds(180), "UTC", "\t@allocation note",
                ACTOR, ACTOR, NOW.minusSeconds(300), NOW.minusSeconds(60), deletedAt, 0);
    }

    private BudgetSummary withItems(BudgetSummary value, List<BudgetItemResponse> items) {
        return new BudgetSummary(value.projectedGrossIncome(), value.projectedNetIncome(),
                value.receivedGrossIncome(), value.receivedNetIncome(), value.projectedDeductions(),
                value.realizedDeductions(), value.confirmedAllocatedIncome(), value.unallocatedReceivedIncome(),
                value.remainingExpectedIncome(), value.plannedSpending(), value.confirmedFundedBudget(),
                value.fundingGap(), value.grossSpending(), value.refunds(), value.netSpending(),
                value.unbudgetedSpending(), value.remainingIncome(), value.spendingRate(), value.safeToSpend(),
                value.forecast(), value.fullyFundedItemCount(), value.partiallyFundedItemCount(),
                value.unfundedItemCount(), value.status(), items);
    }

    private IncomeReceipt receiptEntity(UUID id) {
        IncomeReceipt value = new IncomeReceipt(); value.setId(id); value.setSpaceId(SPACE);
        value.setIncomeEntryId(INCOME); value.setAmount(new BigDecimal("400.00")); value.setReceivedAt(NOW);
        value.setTimeZone("UTC"); value.setNotes("\r+receipt note"); value.setRecordedByUserId(ACTOR);
        value.setCreatedByUserId(ACTOR); value.setUpdatedByUserId(ACTOR);
        value.setCreatedAt(NOW.minusSeconds(120)); value.setUpdatedAt(NOW.minusSeconds(60)); return value;
    }

    private IncomeDeduction plannedDeductionEntity(UUID id) {
        IncomeDeduction value = new IncomeDeduction(); value.setId(id); value.setSpaceId(SPACE);
        value.setIncomeEntryId(INCOME); value.setName("@PAYE"); value.setDeductionType(DeductionType.FIXED);
        value.setFixedAmount(new BigDecimal("100.00")); value.setNotes("+planned note");
        value.setCreatedByUserId(ACTOR); value.setUpdatedByUserId(ACTOR);
        value.setCreatedAt(NOW.minusSeconds(200)); value.setUpdatedAt(NOW.minusSeconds(100)); return value;
    }

    private IncomeReceiptDeduction actualDeductionEntity(UUID id, UUID receiptId, UUID deductionId) {
        IncomeReceiptDeduction value = new IncomeReceiptDeduction(); value.setId(id); value.setSpaceId(SPACE);
        value.setIncomeReceiptId(receiptId); value.setIncomeDeductionId(deductionId); value.setNameSnapshot("=PAYE");
        value.setActualAmount(new BigDecimal("100.00")); value.setCreatedByUserId(ACTOR);
        value.setUpdatedByUserId(ACTOR); value.setCreatedAt(NOW.minusSeconds(100));
        value.setUpdatedAt(NOW.minusSeconds(50)); return value;
    }

    private BudgetFundingAllocation allocationEntity(FundingAllocationResponse source) {
        BudgetFundingAllocation value = new BudgetFundingAllocation(); value.setId(source.id()); value.setSpaceId(SPACE);
        value.setBudgetItemId(ITEM); value.setIncomeEntryId(INCOME); value.setSourceType(source.sourceType());
        value.setPlannedAmount(source.plannedAmount()); value.setConfirmedAllocatedAmount(source.confirmedAllocatedAmount());
        value.setAllocatedAt(source.allocatedAt()); value.setTimeZone(source.timeZone()); value.setNotes(source.notes());
        value.setCreatedByUserId(ACTOR); value.setUpdatedByUserId(ACTOR); value.setCreatedAt(source.createdAt());
        value.setUpdatedAt(source.updatedAt()); value.setDeletedAt(source.deletedAt()); return value;
    }

    private PeriodTotals totals() {
        BigDecimal zero = new BigDecimal("0.00");
        return new PeriodTotals(zero, zero, zero, zero, zero, zero, zero, zero, zero, zero, zero,
                zero, zero, 0, 0, 0, zero, zero, zero, zero, zero, zero, zero, zero, "Healthy",
                new IncomeStatusCounts(0, 0, 0, 0, 0, 0), 0, 0, 0);
    }

    private PeriodTotals totals(BigDecimal planned, BigDecimal net) {
        BigDecimal zero = new BigDecimal("0.00");
        return new PeriodTotals(zero, zero, zero, zero, zero, zero, zero, zero, zero, planned, zero,
                zero, zero, 0, 0, 0, net, zero, net, zero, zero, zero, planned.subtract(net),
                net.multiply(new BigDecimal("100")).divide(planned), "Healthy",
                new IncomeStatusCounts(0, 0, 0, 0, 0, 0), 0, 0, 0);
    }
}
