package solutions.shapeit.wethrive.finance.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import solutions.shapeit.wethrive.common.web.GlobalExceptionHandler;
import solutions.shapeit.wethrive.finance.service.BudgetService;
import solutions.shapeit.wethrive.finance.service.ReferenceFinanceService;
import solutions.shapeit.wethrive.finance.service.SpendingService;
import solutions.shapeit.wethrive.identity.controller.SettingsController;
import solutions.shapeit.wethrive.identity.service.CurrentUser;
import solutions.shapeit.wethrive.identity.service.SettingsService;

/**
 * Exercises Jackson binding and controller validation for every public finance/settings mutation
 * whose optimistic version must distinguish an omitted value from an explicit zero.
 *
 * @author Daniel Jr Nkulu
 */
class MutationVersionValidationTest {
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String ENTITY_ID = "00000000-0000-0000-0000-000000000002";
    private static final String RELATED_ID = "00000000-0000-0000-0000-000000000003";

    private final BudgetService budgets = mock(BudgetService.class);
    private final ReferenceFinanceService references = mock(ReferenceFinanceService.class);
    private final SpendingService spending = mock(SpendingService.class);
    private final SettingsService settings = mock(SettingsService.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(currentUser.id()).thenReturn(USER_ID);
        mvc = MockMvcBuilders.standaloneSetup(
                        new ReferenceFinanceController(references, currentUser),
                        new BudgetController(budgets, currentUser),
                        new IncomeController(budgets, currentUser),
                        new BudgetItemController(budgets, currentUser),
                        new SpendingController(spending, currentUser),
                        new SettingsController(settings, currentUser))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("versionedMutationCases")
    void omittedVersionIsBadRequestWhileExplicitZeroReachesTheController(MutationCase mutation)
            throws Exception {
        mvc.perform(jsonRequest(mutation, mutation.body()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.violations[0].field").value("version"));

        mvc.perform(jsonRequest(mutation, withZeroVersion(mutation.body())))
                .andExpect(status().is(mutation.acceptedStatus()));
    }

    private static Stream<MutationCase> versionedMutationCases() {
        return Stream.of(
                mutation("category update", HttpMethod.PUT, "/api/v1/categories/" + ENTITY_ID,
                        """
                        {"name":"Housing","icon":"home","sortOrder":0,"archived":false}
                        """, 200),
                mutation("income type update", HttpMethod.PUT, "/api/v1/income-types/" + ENTITY_ID,
                        """
                        {"name":"Salary","sortOrder":0,"archived":false}
                        """, 200),
                mutation("budget update", HttpMethod.PUT, "/api/v1/budgets/" + ENTITY_ID,
                        """
                        {"name":"August 2026","status":"ACTIVE","notes":null}
                        """, 200),
                mutation("income update", HttpMethod.PUT, "/api/v1/income/" + ENTITY_ID,
                        """
                        {"sourceName":"Salary","incomeTypeId":"%s","expectedAmount":1000.00,
                         "expectedDate":"2026-08-25","timeZone":"Africa/Johannesburg",
                         "titheEnabled":false,"recurring":false,"sortOrder":0}
                        """.formatted(RELATED_ID), 200),
                mutation("budget item update", HttpMethod.PUT, "/api/v1/budget-items/" + ENTITY_ID,
                        """
                        {"name":"Groceries","categoryId":"%s","plannedAmount":500.00,
                         "tracked":true,"itemType":"PLANNED","recurring":false,
                         "rolloverEnabled":false,"sortOrder":0}
                        """.formatted(RELATED_ID), 200),
                mutation("spending update", HttpMethod.PUT, "/api/v1/spending/" + ENTITY_ID,
                        """
                        {"budgetItemId":"%s","transactionType":"EXPENSE","title":"Groceries",
                         "amount":25.00,"date":"2026-08-25","time":"12:00:00",
                         "timeZone":"Africa/Johannesburg"}
                        """.formatted(RELATED_ID), 200),
                mutation("spending move", HttpMethod.POST, "/api/v1/spending/" + ENTITY_ID + "/move",
                        """
                        {"budgetItemId":"%s"}
                        """.formatted(RELATED_ID), 200),
                mutation("spending restore", HttpMethod.POST,
                        "/api/v1/spending/" + ENTITY_ID + "/restore", "{}", 200),
                mutation("settings update", HttpMethod.PUT, "/api/v1/settings",
                        """
                        {"currencyCode":"ZAR","locale":"en-ZA","timeZone":"Africa/Johannesburg",
                         "weekStartDay":1,"defaultTitheEnabled":false,"theme":"SYSTEM",
                         "notificationsEnabled":true,"detailedNotificationsEnabled":true,
                         "autoLockMinutes":15,"onboardingComplete":true}
                        """, 200),
                mutation("budget delete", HttpMethod.DELETE, "/api/v1/budgets/" + ENTITY_ID,
                        "{}", 204),
                mutation("budget close", HttpMethod.POST, "/api/v1/budgets/" + ENTITY_ID + "/close",
                        "{}", 200),
                mutation("budget reopen", HttpMethod.POST, "/api/v1/budgets/" + ENTITY_ID + "/reopen",
                        "{}", 200),
                mutation("income delete", HttpMethod.DELETE, "/api/v1/income/" + ENTITY_ID,
                        "{}", 204),
                mutation("category archive", HttpMethod.DELETE, "/api/v1/categories/" + ENTITY_ID,
                        "{}", 204),
                mutation("income type archive", HttpMethod.DELETE, "/api/v1/income-types/" + ENTITY_ID,
                        "{}", 204),
                mutation("budget item delete", HttpMethod.DELETE, "/api/v1/budget-items/" + ENTITY_ID,
                        "{}", 204),
                mutation("spending delete", HttpMethod.DELETE, "/api/v1/spending/" + ENTITY_ID,
                        "{}", 204));
    }

    private static MutationCase mutation(String name, HttpMethod method, String path, String body,
                                         int acceptedStatus) {
        return new MutationCase(name, method, path, body.strip(), acceptedStatus);
    }

    private MockHttpServletRequestBuilder jsonRequest(MutationCase mutation, String body) {
        return request(mutation.method(), mutation.path())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private String withZeroVersion(String body) {
        return body.substring(0, body.length() - 1)
                + (body.length() == 2 ? "" : ",")
                + "\"version\":0}";
    }

    private record MutationCase(String name, HttpMethod method, String path, String body,
                                int acceptedStatus) {
        @Override public String toString() { return name; }
    }
}
