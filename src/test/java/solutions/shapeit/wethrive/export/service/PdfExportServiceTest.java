package solutions.shapeit.wethrive.export.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import solutions.shapeit.wethrive.TestProperties;
import solutions.shapeit.wethrive.common.domain.DomainEnums.BudgetStatus;
import solutions.shapeit.wethrive.common.domain.DomainEnums.DeductionType;
import solutions.shapeit.wethrive.common.domain.DomainEnums.IncomeStatus;
import solutions.shapeit.wethrive.common.domain.DomainEnums.Role;
import solutions.shapeit.wethrive.common.domain.DomainEnums.SpaceType;
import solutions.shapeit.wethrive.common.domain.DomainEnums.TransactionType;
import solutions.shapeit.wethrive.common.web.ApiException;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.BudgetSummary;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.BudgetResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeAvailability;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeDeductionResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeReceiptDeductionResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeReceiptResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.SpendingResponse;
import solutions.shapeit.wethrive.finance.entity.BudgetMonth;
import solutions.shapeit.wethrive.finance.service.BudgetService;
import solutions.shapeit.wethrive.finance.service.SpendingService;
import solutions.shapeit.wethrive.finance.repository.BudgetMonthRepository;
import solutions.shapeit.wethrive.finance.repository.IncomeDeductionRepository;
import solutions.shapeit.wethrive.finance.repository.IncomeEntryRepository;
import solutions.shapeit.wethrive.finance.repository.IncomeReceiptDeductionRepository;
import solutions.shapeit.wethrive.finance.repository.IncomeReceiptRepository;
import solutions.shapeit.wethrive.report.dto.ReportDtos.AnnualReport;
import solutions.shapeit.wethrive.report.dto.ReportDtos.IncomeStatusCounts;
import solutions.shapeit.wethrive.report.dto.ReportDtos.MonthlyReport;
import solutions.shapeit.wethrive.report.dto.ReportDtos.NamedAmount;
import solutions.shapeit.wethrive.report.dto.ReportDtos.PeriodTotals;
import solutions.shapeit.wethrive.report.dto.ReportDtos.QuarterlyReport;
import solutions.shapeit.wethrive.report.dto.ReportDtos.ReportChartData;
import solutions.shapeit.wethrive.report.service.ReportService;
import solutions.shapeit.wethrive.space.dto.SpaceDtos.SpaceResponse;
import solutions.shapeit.wethrive.space.service.SpaceAccessService;
import solutions.shapeit.wethrive.space.service.SpaceAccessService.Capability;
import solutions.shapeit.wethrive.space.service.SpaceService;

class PdfExportServiceTest {
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID SPACE = UUID.randomUUID();
    private static final UUID BUDGET = UUID.randomUUID();
    private static final UUID INCOME = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-07-15T10:00:00Z");
    private final BudgetService budgets = mock(BudgetService.class);
    private final SpendingService spending = mock(SpendingService.class);
    private final ReportService reports = mock(ReportService.class);
    private final SpaceService spaces = mock(SpaceService.class);
    private final SpaceAccessService access = mock(SpaceAccessService.class);
    private final BudgetMonthRepository budgetMonths = mock(BudgetMonthRepository.class);
    private final IncomeEntryRepository incomeEntries = mock(IncomeEntryRepository.class);
    private final IncomeReceiptRepository incomeReceipts = mock(IncomeReceiptRepository.class);
    private final IncomeDeductionRepository incomeDeductions = mock(IncomeDeductionRepository.class);
    private final IncomeReceiptDeductionRepository actualDeductions = mock(IncomeReceiptDeductionRepository.class);
    private PdfExportService exports;

    @BeforeEach
    void setUp() {
        exports = new PdfExportService(budgets, spending, reports, spaces, budgetMonths, incomeEntries,
                incomeReceipts, incomeDeductions, actualDeductions, access,
                TestProperties.create(), Clock.fixed(NOW, ZoneOffset.UTC));
        when(spaces.get(SPACE, ACTOR)).thenReturn(new SpaceResponse(SPACE, SpaceType.PERSONAL,
                "Synthetic household", "synthetic", ACTOR, "ZAR", "en-ZA", "Africa/Johannesburg",
                Role.OWNER, 0, NOW, NOW));
    }

    @Test
    void monthlyPdfHasSignaturePagesBrandingAndCanonicalTotals() throws Exception {
        PeriodTotals totals = totals();
        BudgetSummary summary = summary();
        ReportChartData charts = new ReportChartData(
                List.of(new NamedAmount(null, "Housing", new BigDecimal("4000.00")),
                        new NamedAmount(null, "Transport", new BigDecimal("2000.00"))),
                List.of(), List.of(new NamedAmount(null, "Planned budget", new BigDecimal("6000.00")),
                        new NamedAmount(null, "Confirmed funding", new BigDecimal("2500.00")),
                        new NamedAmount(null, "Actual spending", new BigDecimal("3000.00"))),
                List.of(new NamedAmount(null, "Projected net", new BigDecimal("8000.00")),
                        new NamedAmount(null, "Available to allocate", new BigDecimal("5500.00"))),
                List.of(), List.of(), List.of());
        BudgetResponse budget = new BudgetResponse(BUDGET, SPACE, 2026, 7, "July", BudgetStatus.ACTIVE,
                null, 0, NOW, summary);
        IncomeAvailability availability = new IncomeAvailability(new BigDecimal("1000.00"),
                new BigDecimal("100.00"), new BigDecimal("900.00"), new BigDecimal("400.00"),
                new BigDecimal("40.00"), new BigDecimal("360.00"), BigDecimal.ZERO.setScale(2),
                new BigDecimal("360.00"), new BigDecimal("600.00"), IncomeStatus.PARTIALLY_RECEIVED);
        IncomeResponse income = new IncomeResponse(INCOME, BUDGET, SPACE, "Salary", UUID.randomUUID(),
                new BigDecimal("1000.00"), LocalDate.of(2026, 7, 20), LocalTime.of(9, 0), "UTC",
                false, BigDecimal.ZERO, null, false, null, null, null, 0, 0, availability);
        UUID receiptId = UUID.randomUUID();
        UUID deductionId = UUID.randomUUID();
        IncomeReceiptDeductionResponse actualDeduction = new IncomeReceiptDeductionResponse(UUID.randomUUID(),
                receiptId, deductionId, SPACE, "PAYE", new BigDecimal("40.00"), ACTOR, ACTOR,
                NOW.minusSeconds(100), NOW.minusSeconds(50), null, 0);
        IncomeReceiptResponse receipt = new IncomeReceiptResponse(receiptId, INCOME, SPACE,
                new BigDecimal("400.00"), NOW, "UTC", null, ACTOR, ACTOR, ACTOR,
                NOW.minusSeconds(120), NOW.minusSeconds(60), null, 0, List.of(actualDeduction),
                new BigDecimal("40.00"), new BigDecimal("360.00"));
        IncomeDeductionResponse plannedDeduction = new IncomeDeductionResponse(deductionId, INCOME, SPACE,
                "PAYE", DeductionType.PERCENTAGE, new BigDecimal("0.10"), null,
                new BigDecimal("100.00"), null, 0, false, ACTOR, ACTOR,
                NOW.minusSeconds(200), NOW.minusSeconds(100), null, 0);
        when(budgets.listForExport(SPACE, 2026, 7, 7, ACTOR)).thenReturn(List.of(budget));
        when(budgets.income(BUDGET, ACTOR)).thenReturn(List.of(income));
        when(budgets.incomeDeductionHistoryForSpace(SPACE, ACTOR)).thenReturn(List.of(plannedDeduction));
        when(budgets.receiptHistoryForSpace(SPACE, ACTOR)).thenReturn(List.of(receipt));
        when(budgets.receiptDeductionHistoryForSpace(SPACE, ACTOR)).thenReturn(List.of(actualDeduction));
        when(reports.monthly(SPACE, 2026, 7, ACTOR)).thenReturn(new MonthlyReport(SPACE, BUDGET, 2026, 7,
                "July", summary, List.of(), List.of(), List.of(), List.of(), List.of(), totals,
                List.of(), List.of(), charts));

        PdfExportService.PdfDocument result = exports.monthly(ACTOR, SPACE, 2026, 7);
        writeEvidence("wethrive-monthly-report.pdf", result.bytes());

        assertThat(result.filename()).isEqualTo("wethrive-monthly-report.pdf");
        assertThat(result.bytes()).startsWith((byte) '%', (byte) 'P', (byte) 'D', (byte) 'F', (byte) '-');
        try (PDDocument document = Loader.loadPDF(result.bytes())) {
            assertThat(document.getNumberOfPages()).isPositive();
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("WeThrive", "Powered by Shape It Solutions", "Synthetic household",
                    "Monthly report", "Projected net income", "ZAR 8,000.00", "Available to allocate",
                    "ZAR 5,500.00", "Projected expenses by category", "Salary", "PAYE",
                    "WeThrive 1.0.0", "Page 1");
            assertSectionSharesPage(document, "Projected expenses by category", "Projected expenses chart");
            assertSectionSharesPage(document, "Funding and income availability", "Funding and income chart");
            assertSectionSharesPage(document, "Funding summary", "No data for this section.");
        }
        verify(access).require(SPACE, ACTOR, Capability.EXPORT);
        verify(budgets, times(1)).incomeDeductionHistoryForSpace(SPACE, ACTOR);
        verify(budgets, times(1)).receiptHistoryForSpace(SPACE, ACTOR);
        verify(budgets, times(1)).receiptDeductionHistoryForSpace(SPACE, ACTOR);
        verify(budgets, never()).incomeDeductionHistory(INCOME, ACTOR);
        verify(budgets, never()).receiptHistory(INCOME, ACTOR);
        verify(budgets, never()).receiptDeductionHistory(receiptId, ACTOR);
    }

    @Test
    void authorizationFailureStopsBeforeReportGeneration() {
        doThrow(ApiException.forbidden())
                .when(access).require(SPACE, ACTOR, Capability.EXPORT);
        assertThatThrownBy(() -> exports.monthly(ACTOR, SPACE, 2026, 7)).isInstanceOf(ApiException.class);
    }

    @Test
    void monthlyQuarterlyAnnualAndFullPdfsUseOnlyPeriodScopedBudgetLookups() {
        BudgetSummary summary = summary();
        PeriodTotals totals = totals();
        var options = new ExcelExportService.ExcelExportOptions(false, false,
                ExcelExportService.ExportTheme.SHAPE_IT_MONOCHROME, "");
        when(budgets.listForExport(SPACE, 2026, 7, 7, ACTOR)).thenReturn(List.of());
        when(budgets.listForExport(SPACE, 2026, 7, 9, ACTOR)).thenReturn(List.of());
        when(budgets.listForExport(SPACE, 2026, 1, 12, ACTOR)).thenReturn(List.of());
        when(reports.monthly(SPACE, 2026, 7, ACTOR)).thenReturn(new MonthlyReport(SPACE, null, 2026, 7,
                null, summary, List.of(), List.of(), List.of(), List.of(), List.of(), totals,
                List.of(), List.of(), null));
        when(reports.quarterly(SPACE, 2026, 3, ACTOR)).thenReturn(new QuarterlyReport(SPACE, 2026, 3,
                List.of(), totals, List.of(), List.of(), List.of(), List.of()));
        when(reports.annual(SPACE, 2026, ACTOR)).thenReturn(new AnnualReport(SPACE, 2026,
                List.of(), List.of(), totals, List.of(), List.of(), List.of(), List.of()));

        assertThat(exports.monthly(ACTOR, SPACE, 2026, 7, options).bytes()).isNotEmpty();
        assertThat(exports.quarterly(ACTOR, SPACE, 2026, 3, options).bytes()).isNotEmpty();
        assertThat(exports.annual(ACTOR, SPACE, 2026, options).bytes()).isNotEmpty();
        assertThat(exports.full(ACTOR, SPACE, 2026, options).bytes()).isNotEmpty();

        verify(budgets).listForExport(SPACE, 2026, 7, 7, ACTOR);
        verify(budgets).listForExport(SPACE, 2026, 7, 9, ACTOR);
        verify(budgets, times(2)).listForExport(SPACE, 2026, 1, 12, ACTOR);
        verify(budgets, never()).list(SPACE, ACTOR);
    }

    @Test
    void directBudgetPdfUsesOnlyTheTargetMonthLookup() {
        BudgetMonth reference = new BudgetMonth();
        reference.setId(BUDGET);
        reference.setSpaceId(SPACE);
        reference.setYear(2026);
        reference.setMonth(7);
        when(budgets.requireBudget(BUDGET)).thenReturn(reference);
        when(budgets.listForExport(SPACE, 2026, 7, 7, ACTOR)).thenReturn(List.of());
        when(reports.monthly(SPACE, 2026, 7, ACTOR)).thenReturn(new MonthlyReport(SPACE, null, 2026, 7,
                null, summary(), List.of(), List.of(), List.of(), List.of(), List.of(), totals(),
                List.of(), List.of(), null));
        var options = new ExcelExportService.ExcelExportOptions(false, false,
                ExcelExportService.ExportTheme.SHAPE_IT_MONOCHROME, "");

        assertThat(exports.budget(BUDGET, ACTOR, options).bytes()).isNotEmpty();

        verify(budgets).listForExport(SPACE, 2026, 7, 7, ACTOR);
        verify(budgets, never()).listForExport(SPACE, ACTOR);
        verify(budgets, never()).list(SPACE, ACTOR);
        verify(budgets, never()).get(BUDGET, ACTOR);
    }

    @Test
    void filenameSanitizerRejectsPathAndHeaderCharacters() {
        assertThat(PdfExportService.safeFilename("../../Quarterly\r\nContent-Disposition: evil"))
                .isEqualTo("quarterly-content-disposition-evil.pdf");
    }

    @Test
    void originalAndMonochromeChartPalettesShareDataButRenderDistinctVisualLanguages() {
        List<NamedAmount> values = List.of(
                new NamedAmount(null, "Housing", new BigDecimal("700.00")),
                new NamedAmount(null, "Transport", new BigDecimal("300.00")));
        var originalOptions = new ExcelExportService.ExcelExportOptions(false, true,
                ExcelExportService.ExportTheme.WETHRIVE_ORIGINAL, "");
        var monochromeOptions = new ExcelExportService.ExcelExportOptions(false, true,
                ExcelExportService.ExportTheme.SHAPE_IT_MONOCHROME, "");

        BufferedImage original = PdfExportService.doughnut(values, "ZAR", originalOptions);
        BufferedImage monochrome = PdfExportService.doughnut(values, "ZAR", monochromeOptions);

        assertThat(hasExactRgb(original, 0, 100, 0)).isTrue();
        assertThat(hasChromaticPixel(original)).isTrue();
        assertThat(hasChromaticPixel(monochrome)).isFalse();
    }

    @Test
    void simpleExportOverloadUsesRequestedPaletteWhileLegacySignatureKeepsOriginalDefault() throws Exception {
        when(spending.list(ACTOR, SPACE, null, null, 0, 200)).thenReturn(new PageImpl<>(List.of()));
        var monochrome = new ExcelExportService.ExcelExportOptions(true, true,
                ExcelExportService.ExportTheme.SHAPE_IT_MONOCHROME, "");

        PdfExportService.PdfDocument original = exports.spending(ACTOR, SPACE, null, null, null);
        PdfExportService.PdfDocument configured = exports.spending(ACTOR, SPACE, null, null, null, monochrome);

        try (PDDocument originalDocument = Loader.loadPDF(original.bytes());
             PDDocument configuredDocument = Loader.loadPDF(configured.bytes())) {
            BufferedImage originalPage = new PDFRenderer(originalDocument).renderImage(0);
            BufferedImage configuredPage = new PDFRenderer(configuredDocument).renderImage(0);
            assertThat(hasChromaticPixel(originalPage)).isTrue();
            assertThat(hasChromaticPixel(configuredPage)).isFalse();
        }
    }

    @Test
    void oversizedSpendingIsRejectedFromTheFirstPage() {
        SpendingResponse first = new SpendingResponse(UUID.randomUUID(), SPACE, UUID.randomUUID(),
                TransactionType.EXPENSE, "First", BigDecimal.ONE, NOW, LocalDate.of(2026, 7, 15),
                LocalTime.NOON, "UTC", "Card", "Merchant", null, ACTOR, ACTOR, 0);
        when(spending.list(ACTOR, SPACE, null, null, 0, 200)).thenReturn(new PageImpl<>(List.of(first),
                PageRequest.of(0, 200), 10_001));

        assertThatThrownBy(() -> exports.spending(ACTOR, SPACE, null, null, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("10,000-row export limit");
        verify(spending, times(1)).list(ACTOR, SPACE, null, null, 0, 200);
    }

    @Test
    void oversizedSpaceHistoryIsRejectedBeforeBulkHistoryMaterialization() {
        BudgetSummary summary = summary();
        BudgetResponse budget = new BudgetResponse(BUDGET, SPACE, 2026, 7, "July", BudgetStatus.ACTIVE,
                null, 0, NOW, summary);
        IncomeAvailability availability = new IncomeAvailability(BigDecimal.ONE, BigDecimal.ZERO,
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ONE, IncomeStatus.SCHEDULED);
        IncomeResponse income = new IncomeResponse(INCOME, BUDGET, SPACE, "Salary", UUID.randomUUID(),
                BigDecimal.ONE, LocalDate.of(2026, 7, 20), LocalTime.NOON, "UTC", false,
                BigDecimal.ZERO, null, false, null, null, null, 0, 0, availability);
        when(budgets.listForExport(SPACE, 2026, 7, 7, ACTOR)).thenReturn(List.of(budget));
        when(budgets.income(BUDGET, ACTOR)).thenReturn(List.of(income));
        when(incomeReceipts.countBySpaceId(SPACE)).thenReturn(10_001L);
        when(reports.monthly(SPACE, 2026, 7, ACTOR)).thenReturn(new MonthlyReport(SPACE, BUDGET, 2026, 7,
                "July", summary, List.of(), List.of(), List.of(), List.of(), List.of(), totals(),
                List.of(), List.of(), null));

        assertThatThrownBy(() -> exports.monthly(ACTOR, SPACE, 2026, 7))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("10,000-row export limit");
        verify(budgets, never()).incomeDeductionHistoryForSpace(SPACE, ACTOR);
        verify(budgets, never()).receiptHistoryForSpace(SPACE, ACTOR);
        verify(budgets, never()).receiptDeductionHistoryForSpace(SPACE, ACTOR);
    }

    @Test
    void oversizedBudgetRegisterIsRejectedBeforeBudgetResponsesAreLoaded() {
        when(budgetMonths.countBySpaceIdInAndDeletedAtIsNull(java.util.Set.of(SPACE))).thenReturn(10_001L);

        assertThatThrownBy(() -> exports.allBudgets(ACTOR, SPACE))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("10,000-row export limit");
        verify(budgets, never()).listForExport(SPACE, ACTOR);
    }

    @Test
    void directBudgetAliasPreflightsBeforeAnyBudgetDtoMaterialization() {
        BudgetMonth budget = new BudgetMonth();
        budget.setId(BUDGET);
        budget.setSpaceId(SPACE);
        budget.setYear(2026);
        budget.setMonth(7);
        when(budgets.requireBudget(BUDGET)).thenReturn(budget);
        when(budgetMonths.countBySpaceIdInAndDeletedAtIsNull(java.util.Set.of(SPACE))).thenReturn(10_001L);

        assertThatThrownBy(() -> exports.budget(BUDGET, ACTOR))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("10,000-row export limit");
        verify(budgets, never()).getForExport(BUDGET, ACTOR);
        verify(budgets, never()).listForExport(SPACE, 2026, 7, 7, ACTOR);
    }

    @Test
    void compressedPdfBufferRejectsTheFirstByteBeyondItsLimit() {
        PdfExportService.BoundedByteArrayOutputStream output =
                new PdfExportService.BoundedByteArrayOutputStream(4);
        output.write(new byte[] {1, 2, 3, 4}, 0, 4);

        assertThatThrownBy(() -> output.write(5))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("32 MiB response limit");
        assertThat(output.size()).isEqualTo(4);
    }

    private static BudgetSummary summary() {
        return new BudgetSummary(new BigDecimal("10000.00"), new BigDecimal("8000.00"),
                new BigDecimal("10000.00"), new BigDecimal("8000.00"), new BigDecimal("2000.00"),
                new BigDecimal("2000.00"), new BigDecimal("2500.00"), new BigDecimal("5500.00"),
                BigDecimal.ZERO.setScale(2), new BigDecimal("6000.00"), new BigDecimal("2500.00"),
                new BigDecimal("3500.00"), new BigDecimal("3000.00"), BigDecimal.ZERO.setScale(2),
                new BigDecimal("3000.00"), BigDecimal.ZERO.setScale(2), new BigDecimal("5000.00"),
                new BigDecimal("37.50"), new BigDecimal("5000.00"), new BigDecimal("5000.00"),
                0, 1, 0, "Healthy", List.of());
    }

    private static PeriodTotals totals() {
        return new PeriodTotals(new BigDecimal("10000.00"), new BigDecimal("8000.00"),
                new BigDecimal("10000.00"), new BigDecimal("8000.00"), new BigDecimal("2000.00"),
                new BigDecimal("2000.00"), BigDecimal.ZERO.setScale(2), new BigDecimal("2500.00"),
                new BigDecimal("5500.00"), new BigDecimal("6000.00"), new BigDecimal("3000.00"),
                new BigDecimal("2500.00"), new BigDecimal("3500.00"), 0, 1, 0,
                new BigDecimal("3000.00"), BigDecimal.ZERO.setScale(2), new BigDecimal("3000.00"),
                BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), new BigDecimal("500.00"),
                new BigDecimal("5000.00"), new BigDecimal("37.50"), "Healthy",
                new IncomeStatusCounts(0, 0, 0, 0, 1, 0), 1, 1, 0);
    }

    private static void writeEvidence(String name, byte[] bytes) throws Exception {
        String directory = System.getProperty("wethrive.export.evidenceDir");
        if (directory == null || directory.isBlank()) return;
        Path target = Path.of(directory).resolve(name);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }

    private static void assertSectionSharesPage(PDDocument document, String heading, String firstContent)
            throws Exception {
        for (int page = 1; page <= document.getNumberOfPages(); page++) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            String pageText = stripper.getText(document);
            if (pageText.contains(heading)) {
                assertThat(pageText).as("section heading must stay with its first content block")
                        .contains(firstContent);
                return;
            }
        }
        throw new AssertionError("Section heading not found: " + heading);
    }

    private static boolean hasExactRgb(BufferedImage image, int red, int green, int blue) {
        int expected = (red << 16) | (green << 8) | blue;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) & 0x00ff_ffff) == expected) return true;
            }
        }
        return false;
    }

    private static boolean hasChromaticPixel(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                int red = (rgb >>> 16) & 0xff;
                int green = (rgb >>> 8) & 0xff;
                int blue = rgb & 0xff;
                if (red != green || green != blue) return true;
            }
        }
        return false;
    }
}
