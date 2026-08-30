package solutions.shapeit.wethrive.export.controller;

import java.util.UUID;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import solutions.shapeit.wethrive.export.service.ExcelExportService;
import solutions.shapeit.wethrive.export.service.ExcelExportService.ExcelExportOptions;
import solutions.shapeit.wethrive.export.service.ExcelExportService.ExportScope;
import solutions.shapeit.wethrive.export.service.ExcelExportService.ExportTheme;
import solutions.shapeit.wethrive.export.service.PdfExportService;
import solutions.shapeit.wethrive.export.service.PdfExportService.PdfDocument;
import solutions.shapeit.wethrive.common.domain.DomainEnums.Palette;
import solutions.shapeit.wethrive.identity.service.CurrentUser;
import solutions.shapeit.wethrive.identity.service.SettingsService;

@RestController
@RequestMapping("/api/v1/exports")
public class ExportController {
    private static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private static final MediaType PDF = MediaType.APPLICATION_PDF;
    private final ExcelExportService service;
    private final PdfExportService pdf;
    private final CurrentUser currentUser;
    private final SettingsService settings;
    public ExportController(ExcelExportService service, PdfExportService pdf, CurrentUser currentUser,
                            SettingsService settings) {
        this.service = service; this.pdf = pdf; this.currentUser = currentUser; this.settings = settings;
    }
    @GetMapping("/budgets") public ResponseEntity<byte[]> budgets(@RequestParam(required = false) UUID spaceId,
            @RequestParam(required = false) ExportTheme theme, @RequestParam(required = false) String accent) {
        return file(service.allBudgets(currentUser.id(), spaceId, simpleOptions(theme, accent)), "wethrive-budgets.xlsx"); }
    @GetMapping("/budgets/{budgetId}") public ResponseEntity<byte[]> budget(@PathVariable UUID budgetId,
            @RequestParam(defaultValue = "true") boolean includeDetailedLedgers,
            @RequestParam(defaultValue = "true") boolean includeCharts,
            @RequestParam(required = false) ExportTheme theme,
            @RequestParam(required = false) String accent,
            @RequestParam(defaultValue = "CURRENT_REPORT") ExportScope scope,
            @RequestParam(required = false) List<UUID> budgetIds,
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String category) {
        return file(service.budget(budgetId, currentUser.id(), options(includeDetailedLedgers, includeCharts,
                theme, accent, scope, budgetIds, from, to, category)), "wethrive-budget.xlsx");
    }
    @GetMapping("/budget-items/{budgetItemId}") public ResponseEntity<byte[]> item(@PathVariable UUID budgetItemId,
            @RequestParam(required = false) ExportTheme theme, @RequestParam(required = false) String accent) {
        return file(service.budgetItem(budgetItemId, currentUser.id(), simpleOptions(theme, accent)), "wethrive-budget-item.xlsx"); }
    @GetMapping("/spending") public ResponseEntity<byte[]> spending(@RequestParam(required = false) UUID spaceId,
            @RequestParam(required = false) UUID memberId, @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to, @RequestParam(required = false) ExportTheme theme,
            @RequestParam(required = false) String accent) {
        return file(service.spending(currentUser.id(), spaceId, memberId, from, to, simpleOptions(theme, accent)),
                "wethrive-spending.xlsx");
    }
    @GetMapping("/reports/monthly") public ResponseEntity<byte[]> monthly(@RequestParam UUID spaceId, @RequestParam int year, @RequestParam int month,
            @RequestParam(defaultValue = "true") boolean includeDetailedLedgers, @RequestParam(defaultValue = "true") boolean includeCharts,
            @RequestParam(required = false) ExportTheme theme, @RequestParam(required = false) String accent,
            @RequestParam(defaultValue = "CURRENT_REPORT") ExportScope scope,
            @RequestParam(required = false) List<UUID> budgetIds,
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String category) {
        return file(service.monthly(currentUser.id(), spaceId, year, month, options(includeDetailedLedgers,
                includeCharts, theme, accent, scope, budgetIds, from, to, category)), "wethrive-monthly-report.xlsx"); }
    @GetMapping("/reports/quarterly") public ResponseEntity<byte[]> quarterly(@RequestParam UUID spaceId, @RequestParam int year, @RequestParam int quarter,
            @RequestParam(defaultValue = "true") boolean includeDetailedLedgers, @RequestParam(defaultValue = "true") boolean includeCharts,
            @RequestParam(required = false) ExportTheme theme, @RequestParam(required = false) String accent,
            @RequestParam(defaultValue = "CURRENT_REPORT") ExportScope scope,
            @RequestParam(required = false) List<UUID> budgetIds,
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String category) {
        return file(service.quarterly(currentUser.id(), spaceId, year, quarter, options(includeDetailedLedgers,
                includeCharts, theme, accent, scope, budgetIds, from, to, category)), "wethrive-quarterly-report.xlsx"); }
    @GetMapping("/reports/annual") public ResponseEntity<byte[]> annual(@RequestParam UUID spaceId, @RequestParam int year,
            @RequestParam(defaultValue = "true") boolean includeDetailedLedgers, @RequestParam(defaultValue = "true") boolean includeCharts,
            @RequestParam(required = false) ExportTheme theme, @RequestParam(required = false) String accent,
            @RequestParam(defaultValue = "CURRENT_REPORT") ExportScope scope,
            @RequestParam(required = false) List<UUID> budgetIds,
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String category) {
        return file(service.annual(currentUser.id(), spaceId, year, options(includeDetailedLedgers, includeCharts,
                theme, accent, scope, budgetIds, from, to, category)), "wethrive-annual-report.xlsx"); }
    @GetMapping("/reports/full") public ResponseEntity<byte[]> full(@RequestParam UUID spaceId, @RequestParam int year,
            @RequestParam(defaultValue = "true") boolean includeDetailedLedgers, @RequestParam(defaultValue = "true") boolean includeCharts,
            @RequestParam(required = false) ExportTheme theme, @RequestParam(required = false) String accent,
            @RequestParam(defaultValue = "CURRENT_REPORT") ExportScope scope,
            @RequestParam(required = false) List<UUID> budgetIds,
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String category) {
        return file(service.full(currentUser.id(), spaceId, year, options(includeDetailedLedgers, includeCharts,
                theme, accent, scope, budgetIds, from, to, category)), "wethrive-complete-report.xlsx"); }
    @GetMapping("/pdf/budgets") public ResponseEntity<byte[]> budgetsPdf(@RequestParam(required = false) UUID spaceId,
            @RequestParam(required = false) ExportTheme theme, @RequestParam(required = false) String accent) {
        return pdf(pdf.allBudgets(currentUser.id(), spaceId, simpleOptions(theme, accent))); }
    @GetMapping("/pdf/budgets/{budgetId}") public ResponseEntity<byte[]> budgetPdf(@PathVariable UUID budgetId,
            @RequestParam(defaultValue = "true") boolean includeDetailedLedgers,
            @RequestParam(defaultValue = "true") boolean includeCharts,
            @RequestParam(required = false) ExportTheme theme,
            @RequestParam(required = false) String accent,
            @RequestParam(defaultValue = "CURRENT_REPORT") ExportScope scope,
            @RequestParam(required = false) List<UUID> budgetIds,
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String category) {
        return pdf(pdf.budget(budgetId, currentUser.id(), options(includeDetailedLedgers, includeCharts,
                theme, accent, scope, budgetIds, from, to, category))); }
    @GetMapping("/pdf/budget-items/{budgetItemId}") public ResponseEntity<byte[]> itemPdf(@PathVariable UUID budgetItemId,
            @RequestParam(required = false) ExportTheme theme, @RequestParam(required = false) String accent) {
        return pdf(pdf.budgetItem(budgetItemId, currentUser.id(), simpleOptions(theme, accent))); }
    @GetMapping("/pdf/spending") public ResponseEntity<byte[]> spendingPdf(@RequestParam(required = false) UUID spaceId,
            @RequestParam(required = false) UUID memberId, @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to, @RequestParam(required = false) ExportTheme theme,
            @RequestParam(required = false) String accent) {
        return pdf(pdf.spending(currentUser.id(), spaceId, memberId, from, to, simpleOptions(theme, accent))); }
    @GetMapping("/pdf/reports/monthly") public ResponseEntity<byte[]> monthlyPdf(@RequestParam UUID spaceId, @RequestParam int year, @RequestParam int month,
            @RequestParam(defaultValue = "true") boolean includeDetailedLedgers, @RequestParam(defaultValue = "true") boolean includeCharts,
            @RequestParam(required = false) ExportTheme theme, @RequestParam(required = false) String accent,
            @RequestParam(defaultValue = "CURRENT_REPORT") ExportScope scope, @RequestParam(required = false) List<UUID> budgetIds,
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String category) {
        return pdf(pdf.monthly(currentUser.id(), spaceId, year, month, options(includeDetailedLedgers, includeCharts,
                theme, accent, scope, budgetIds, from, to, category))); }
    @GetMapping("/pdf/reports/quarterly") public ResponseEntity<byte[]> quarterlyPdf(@RequestParam UUID spaceId, @RequestParam int year, @RequestParam int quarter,
            @RequestParam(defaultValue = "true") boolean includeDetailedLedgers, @RequestParam(defaultValue = "true") boolean includeCharts,
            @RequestParam(required = false) ExportTheme theme, @RequestParam(required = false) String accent,
            @RequestParam(defaultValue = "CURRENT_REPORT") ExportScope scope, @RequestParam(required = false) List<UUID> budgetIds,
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String category) {
        return pdf(pdf.quarterly(currentUser.id(), spaceId, year, quarter, options(includeDetailedLedgers, includeCharts,
                theme, accent, scope, budgetIds, from, to, category))); }
    @GetMapping("/pdf/reports/annual") public ResponseEntity<byte[]> annualPdf(@RequestParam UUID spaceId, @RequestParam int year,
            @RequestParam(defaultValue = "true") boolean includeDetailedLedgers, @RequestParam(defaultValue = "true") boolean includeCharts,
            @RequestParam(required = false) ExportTheme theme, @RequestParam(required = false) String accent,
            @RequestParam(defaultValue = "CURRENT_REPORT") ExportScope scope, @RequestParam(required = false) List<UUID> budgetIds,
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String category) {
        return pdf(pdf.annual(currentUser.id(), spaceId, year, options(includeDetailedLedgers, includeCharts,
                theme, accent, scope, budgetIds, from, to, category))); }
    @GetMapping("/pdf/reports/full") public ResponseEntity<byte[]> fullPdf(@RequestParam UUID spaceId, @RequestParam int year,
            @RequestParam(defaultValue = "true") boolean includeDetailedLedgers, @RequestParam(defaultValue = "true") boolean includeCharts,
            @RequestParam(required = false) ExportTheme theme, @RequestParam(required = false) String accent,
            @RequestParam(defaultValue = "CURRENT_REPORT") ExportScope scope, @RequestParam(required = false) List<UUID> budgetIds,
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String category) {
        return pdf(pdf.full(currentUser.id(), spaceId, year, options(includeDetailedLedgers, includeCharts,
                theme, accent, scope, budgetIds, from, to, category))); }
    private ResponseEntity<byte[]> file(byte[] bytes, String name) { return ResponseEntity.ok().contentType(XLSX).contentLength(bytes.length).header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString()).header(HttpHeaders.CACHE_CONTROL, "no-store").body(bytes); }
    private ExcelExportOptions options(boolean details, boolean charts, ExportTheme theme, String accent,
                                       ExportScope scope, List<UUID> budgetIds, LocalDate from, LocalDate to,
                                       String category) {
        return new ExcelExportOptions(details, charts, resolveTheme(theme), accent, scope, budgetIds, from, to, category);
    }
    private ExcelExportOptions simpleOptions(ExportTheme theme, String accent) {
        return options(true, true, theme, accent, ExportScope.CURRENT_REPORT, List.of(), null, null, null);
    }
    private ExportTheme resolveTheme(ExportTheme requested) {
        if (requested != null) return requested;
        Palette saved = settings.get(currentUser.id()).palette();
        return saved == Palette.MONOCHROME ? ExportTheme.SHAPE_IT_MONOCHROME : ExportTheme.WETHRIVE_ORIGINAL;
    }
    private ResponseEntity<byte[]> pdf(PdfDocument document) { return ResponseEntity.ok().contentType(PDF)
            .contentLength(document.bytes().length)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(document.filename()).build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "no-store").body(document.bytes()); }
}
