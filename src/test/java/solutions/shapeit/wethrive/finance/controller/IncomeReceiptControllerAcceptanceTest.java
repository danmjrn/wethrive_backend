package solutions.shapeit.wethrive.finance.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import solutions.shapeit.wethrive.common.web.ApiException;
import solutions.shapeit.wethrive.common.web.GlobalExceptionHandler;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeReceiptRequest;
import solutions.shapeit.wethrive.finance.service.BudgetService;
import solutions.shapeit.wethrive.identity.service.CurrentUser;

/**
 * Acceptance coverage for the public income-receipt HTTP conflict contract.
 *
 * @author Daniel Jr Nkulu
 */
class IncomeReceiptControllerAcceptanceTest {
    private static final UUID ACTOR = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID INCOME = UUID.fromString("30000000-0000-0000-0000-000000000002");
    private static final UUID RECEIPT = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final Instant RECEIVED_AT = Instant.parse("2026-08-24T10:00:00Z");

    @Test
    void fullyReceivedIncomeReturnsStructuredConflictFromTheReceiptEndpoint() throws Exception {
        BudgetService budgets = mock(BudgetService.class);
        CurrentUser currentUser = mock(CurrentUser.class);
        when(currentUser.id()).thenReturn(ACTOR);
        IncomeReceiptRequest request = new IncomeReceiptRequest(RECEIPT, INCOME,
                new BigDecimal("1.00"), RECEIVED_AT, "Africa/Johannesburg", "Stale device", List.of());
        when(budgets.createReceipt(INCOME, ACTOR, request)).thenThrow(ApiException.conflict(
                "income_already_received",
                "This income is already fully received; edit an existing receipt or the scheduled amount instead"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new IncomeController(budgets, currentUser))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mvc.perform(post("/api/v1/income/{id}/receipts", INCOME)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"%s","incomeEntryId":"%s","amount":1.00,
                                 "receivedAt":"%s","timeZone":"Africa/Johannesburg",
                                 "notes":"Stale device","deductions":[]}
                                """.formatted(RECEIPT, INCOME, RECEIVED_AT)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("income_already_received"))
                .andExpect(jsonPath("$.message").value(
                        "This income is already fully received; edit an existing receipt or the scheduled amount instead"))
                .andExpect(jsonPath("$.path").value("/api/v1/income/" + INCOME + "/receipts"))
                .andExpect(jsonPath("$.violations").isEmpty());

        verify(budgets).createReceipt(INCOME, ACTOR, request);
    }
}
