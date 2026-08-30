package solutions.shapeit.wethrive.export.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.stream.IntStream;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import solutions.shapeit.wethrive.TestProperties;
import solutions.shapeit.wethrive.common.web.ApiException;

class ExcelExportSafetyTest {
    private final ExcelExportService service = new ExcelExportService(null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null, null,
            TestProperties.create(), Clock.systemUTC());

    @Test
    void neutralizesFormulaPrefixesAfterWhitespaceAndControlsAndWritesNativeTemporalCells() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var row = workbook.createSheet("Safety").createRow(0);
            service.text(row, 0, "=HYPERLINK(\"https://evil.invalid\")");
            service.text(row, 1, " \t+1+1");
            service.text(row, 2, "\r\n-1+1");
            service.text(row, 3, "\u200B@SUM(A1:A2)");
            service.text(row, 4, "  ordinary text");
            service.date(row, 5, LocalDate.of(2026, 7, 16), workbook);
            service.time(row, 6, LocalTime.of(14, 30), workbook);
            service.timestamp(row, 7, Instant.parse("2026-07-16T12:30:00Z"), "Africa/Johannesburg", workbook);
            assertThat(row.getCell(0).getStringCellValue()).startsWith("'=");
            assertThat(row.getCell(1).getStringCellValue()).startsWith("' \t+");
            assertThat(row.getCell(2).getStringCellValue()).startsWith("'\r\n-");
            assertThat(row.getCell(3).getStringCellValue()).startsWith("'\u200B@");
            assertThat(row.getCell(4).getStringCellValue()).isEqualTo("  ordinary text");
            assertThat(IntStream.rangeClosed(0, 4).mapToObj(row::getCell))
                    .allMatch(cell -> cell.getCellType() == CellType.STRING);
            assertThat(IntStream.rangeClosed(5, 7).mapToObj(row::getCell))
                    .allMatch(cell -> cell.getCellType() == CellType.NUMERIC);
            assertThat(row.getCell(5).getCellStyle().getDataFormatString()).isEqualTo("yyyy-mm-dd");
            assertThat(row.getCell(6).getCellStyle().getDataFormatString()).isEqualTo("hh:mm");
            assertThat(row.getCell(7).getCellStyle().getDataFormatString()).isEqualTo("yyyy-mm-dd hh:mm:ss");
        }
    }

    @Test
    void originalPaletteIsTheDefaultAndCustomAccentValidationRemainsStrict() {
        assertThat(ExcelExportService.ExcelExportOptions.defaults().theme())
                .isEqualTo(ExcelExportService.ExportTheme.WETHRIVE_ORIGINAL);
        assertThat(ExcelExportService.accent(ExcelExportService.ExcelExportOptions.defaults()))
                .containsExactly(0, 100, 0);
        assertThat(ExcelExportService.accent(new ExcelExportService.ExcelExportOptions(false, false,
                ExcelExportService.ExportTheme.SHAPE_IT_MONOCHROME, "")))
                .containsExactly(25, 25, 25);
        assertThatThrownBy(() -> new ExcelExportService.ExcelExportOptions(false, false,
                ExcelExportService.ExportTheme.CUSTOM, "not-a-colour"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("six-digit hexadecimal colour");
    }
}
