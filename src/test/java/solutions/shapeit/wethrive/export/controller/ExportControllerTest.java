package solutions.shapeit.wethrive.export.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import solutions.shapeit.wethrive.common.domain.DomainEnums.BudgetItemView;
import solutions.shapeit.wethrive.common.domain.DomainEnums.Palette;
import solutions.shapeit.wethrive.common.domain.DomainEnums.Theme;
import solutions.shapeit.wethrive.export.service.ExcelExportService;
import solutions.shapeit.wethrive.export.service.ExcelExportService.ExcelExportOptions;
import solutions.shapeit.wethrive.export.service.ExcelExportService.ExportScope;
import solutions.shapeit.wethrive.export.service.ExcelExportService.ExportTheme;
import solutions.shapeit.wethrive.export.service.PdfExportService;
import solutions.shapeit.wethrive.export.service.PdfExportService.PdfDocument;
import solutions.shapeit.wethrive.identity.dto.SettingsDtos.SettingsResponse;
import solutions.shapeit.wethrive.identity.service.CurrentUser;
import solutions.shapeit.wethrive.identity.service.SettingsService;

/**
 * Verifies that export request defaults follow the current user's saved colour palette.
 *
 * @author Daniel Jr Nkulu
 */
class ExportControllerTest {
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID SPACE = UUID.randomUUID();
    private static final UUID BUDGET = UUID.randomUUID();
    private static final UUID ITEM = UUID.randomUUID();
    private static final byte[] XLSX = {1, 2, 3};
    private static final PdfDocument PDF = new PdfDocument(new byte[]{'%', 'P', 'D', 'F'}, "export.pdf");

    private final ExcelExportService excel = mock(ExcelExportService.class);
    private final PdfExportService pdf = mock(PdfExportService.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final SettingsService settings = mock(SettingsService.class);
    private ExportController controller;

    @BeforeEach
    void setUp() {
        when(currentUser.id()).thenReturn(ACTOR);
        controller = new ExportController(excel, pdf, currentUser, settings);
    }

    @Test
    void savedOriginalDefaultsBudgetAndReportFamiliesInBothFormats() {
        when(settings.get(ACTOR)).thenReturn(settings(Palette.ORIGINAL));
        when(excel.budget(eq(BUDGET), eq(ACTOR), any())).thenReturn(XLSX);
        when(excel.monthly(eq(ACTOR), eq(SPACE), eq(2026), eq(8), any())).thenReturn(XLSX);
        when(pdf.budget(eq(BUDGET), eq(ACTOR), any())).thenReturn(PDF);
        when(pdf.monthly(eq(ACTOR), eq(SPACE), eq(2026), eq(8), any())).thenReturn(PDF);

        controller.budget(BUDGET, true, true, null, null, ExportScope.CURRENT_REPORT,
                null, null, null, null);
        controller.monthly(SPACE, 2026, 8, true, true, null, null, ExportScope.CURRENT_REPORT,
                null, null, null, null);
        controller.budgetPdf(BUDGET, true, true, null, null, ExportScope.CURRENT_REPORT,
                null, null, null, null);
        controller.monthlyPdf(SPACE, 2026, 8, true, true, null, null, ExportScope.CURRENT_REPORT,
                null, null, null, null);

        assertCapturedTheme(excel, "budget", ExportTheme.WETHRIVE_ORIGINAL);
        assertCapturedTheme(excel, "monthly", ExportTheme.WETHRIVE_ORIGINAL);
        assertCapturedTheme(pdf, "budget", ExportTheme.WETHRIVE_ORIGINAL);
        assertCapturedTheme(pdf, "monthly", ExportTheme.WETHRIVE_ORIGINAL);
    }

    @Test
    void savedMonochromeDefaultsEverySimpleFamilyInBothFormats() {
        when(settings.get(ACTOR)).thenReturn(settings(Palette.MONOCHROME));
        when(excel.allBudgets(eq(ACTOR), eq(SPACE), any())).thenReturn(XLSX);
        when(excel.budgetItem(eq(ITEM), eq(ACTOR), any())).thenReturn(XLSX);
        when(excel.spending(eq(ACTOR), eq(SPACE), eq(null), eq(null), eq(null), any())).thenReturn(XLSX);
        when(pdf.allBudgets(eq(ACTOR), eq(SPACE), any())).thenReturn(PDF);
        when(pdf.budgetItem(eq(ITEM), eq(ACTOR), any())).thenReturn(PDF);
        when(pdf.spending(eq(ACTOR), eq(SPACE), eq(null), eq(null), eq(null), any())).thenReturn(PDF);

        controller.budgets(SPACE, null, null);
        controller.item(ITEM, null, null);
        controller.spending(SPACE, null, null, null, null, null);
        controller.budgetsPdf(SPACE, null, null);
        controller.itemPdf(ITEM, null, null);
        controller.spendingPdf(SPACE, null, null, null, null, null);

        assertCapturedTheme(excel, "allBudgets", ExportTheme.SHAPE_IT_MONOCHROME);
        assertCapturedTheme(excel, "budgetItem", ExportTheme.SHAPE_IT_MONOCHROME);
        assertCapturedTheme(excel, "spending", ExportTheme.SHAPE_IT_MONOCHROME);
        assertCapturedTheme(pdf, "allBudgets", ExportTheme.SHAPE_IT_MONOCHROME);
        assertCapturedTheme(pdf, "budgetItem", ExportTheme.SHAPE_IT_MONOCHROME);
        assertCapturedTheme(pdf, "spending", ExportTheme.SHAPE_IT_MONOCHROME);
    }

    @Test
    void explicitCompatibilityThemeOverridesSettingsAcrossAllFamilies() {
        when(excel.budget(eq(BUDGET), eq(ACTOR), any())).thenReturn(XLSX);
        when(pdf.monthly(eq(ACTOR), eq(SPACE), eq(2026), eq(8), any())).thenReturn(PDF);
        when(excel.budgetItem(eq(ITEM), eq(ACTOR), any())).thenReturn(XLSX);
        when(pdf.allBudgets(eq(ACTOR), eq(SPACE), any())).thenReturn(PDF);

        controller.budget(BUDGET, true, true, ExportTheme.WORKBOOK_INSPIRED, null,
                ExportScope.CURRENT_REPORT, null, null, null, null);
        controller.monthlyPdf(SPACE, 2026, 8, true, true, ExportTheme.WORKBOOK_INSPIRED, null,
                ExportScope.CURRENT_REPORT, null, null, null, null);
        controller.item(ITEM, ExportTheme.WORKBOOK_INSPIRED, null);
        controller.budgetsPdf(SPACE, ExportTheme.WORKBOOK_INSPIRED, null);

        assertCapturedTheme(excel, "budget", ExportTheme.WORKBOOK_INSPIRED);
        assertCapturedTheme(pdf, "monthly", ExportTheme.WORKBOOK_INSPIRED);
        assertCapturedTheme(excel, "budgetItem", ExportTheme.WORKBOOK_INSPIRED);
        assertCapturedTheme(pdf, "allBudgets", ExportTheme.WORKBOOK_INSPIRED);
        verify(settings, never()).get(ACTOR);
    }

    @Test
    void legacyNullPaletteFallsBackToOriginal() {
        when(settings.get(ACTOR)).thenReturn(settings(null));
        when(excel.allBudgets(eq(ACTOR), eq(SPACE), any())).thenReturn(XLSX);

        controller.budgets(SPACE, null, null);

        assertCapturedTheme(excel, "allBudgets", ExportTheme.WETHRIVE_ORIGINAL);
    }

    private static SettingsResponse settings(Palette palette) {
        return new SettingsResponse(UUID.randomUUID(), "ZAR", "en-ZA", "Africa/Johannesburg", 1,
                false, new BigDecimal("0.1000"), new BigDecimal("0.7500"), new BigDecimal("0.9000"),
                Theme.SYSTEM, palette, true, false, null, 5, true, BudgetItemView.CARDS, 0);
    }

    private static void assertCapturedTheme(Object service, String method, ExportTheme expected) {
        ArgumentCaptor<ExcelExportOptions> options = ArgumentCaptor.forClass(ExcelExportOptions.class);
        if (service instanceof ExcelExportService excel) {
            switch (method) {
                case "allBudgets" -> verify(excel).allBudgets(eq(ACTOR), eq(SPACE), options.capture());
                case "budget" -> verify(excel).budget(eq(BUDGET), eq(ACTOR), options.capture());
                case "budgetItem" -> verify(excel).budgetItem(eq(ITEM), eq(ACTOR), options.capture());
                case "spending" -> verify(excel).spending(eq(ACTOR), eq(SPACE), eq(null), eq(null), eq(null),
                        options.capture());
                case "monthly" -> verify(excel).monthly(eq(ACTOR), eq(SPACE), eq(2026), eq(8), options.capture());
                default -> throw new IllegalArgumentException("Unknown Excel method: " + method);
            }
        } else if (service instanceof PdfExportService pdf) {
            switch (method) {
                case "allBudgets" -> verify(pdf).allBudgets(eq(ACTOR), eq(SPACE), options.capture());
                case "budget" -> verify(pdf).budget(eq(BUDGET), eq(ACTOR), options.capture());
                case "budgetItem" -> verify(pdf).budgetItem(eq(ITEM), eq(ACTOR), options.capture());
                case "spending" -> verify(pdf).spending(eq(ACTOR), eq(SPACE), eq(null), eq(null), eq(null),
                        options.capture());
                case "monthly" -> verify(pdf).monthly(eq(ACTOR), eq(SPACE), eq(2026), eq(8), options.capture());
                default -> throw new IllegalArgumentException("Unknown PDF method: " + method);
            }
        } else {
            throw new IllegalArgumentException("Unsupported export service");
        }
        assertThat(options.getValue().theme()).isEqualTo(expected);
    }
}
