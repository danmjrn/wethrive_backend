package solutions.shapeit.wethrive.finance.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import solutions.shapeit.wethrive.common.web.ApiException;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.BudgetRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeReceiptRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeReceiptResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeRequest;
import solutions.shapeit.wethrive.finance.repository.IncomeReceiptRepository;
import solutions.shapeit.wethrive.identity.dto.AuthDtos.BootstrapRequest;
import solutions.shapeit.wethrive.identity.repository.AppUserRepository;
import solutions.shapeit.wethrive.identity.service.AuthService;
import solutions.shapeit.wethrive.space.service.SpaceService;

/**
 * Exercises the PostgreSQL parent-income lock used to reject concurrent over-confirmation.
 *
 * @author Daniel Jr Nkulu
 */
@Testcontainers
@SpringBootTest(properties = {
        "wethrive.security.bootstrap-token=receipt-concurrency-bootstrap",
        "wethrive.security.expose-account-tokens=true",
        "wethrive.reminders.scheduler-enabled=false",
        "springdoc.api-docs.enabled=false",
        "springdoc.swagger-ui.enabled=false"
})
class IncomeReceiptConcurrencyIT {
    private static final String OWNER_EMAIL = "receipt-owner@example.test";
    private static final String OWNER_PASSWORD = "StrongPassword123";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.4-alpine")
            .withDatabaseName("wethrive_receipt_concurrency")
            .withUsername("wethrive")
            .withPassword("test-password");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired AuthService auth;
    @Autowired AppUserRepository users;
    @Autowired SpaceService spaces;
    @Autowired ReferenceFinanceService references;
    @Autowired BudgetService budgets;
    @Autowired IncomeReceiptRepository receipts;

    private UUID actorId;
    private UUID incomeId;

    @BeforeEach
    void seedIncome() {
        if (!users.existsByNormalizedEmail(OWNER_EMAIL)) {
            auth.bootstrap(new BootstrapRequest(OWNER_EMAIL, OWNER_PASSWORD, "Receipt owner",
                    "receipt-concurrency-bootstrap", UUID.randomUUID(), "Integration browser"), "test-agent");
        }
        actorId = users.findByNormalizedEmailAndDeletedAtIsNull(OWNER_EMAIL).orElseThrow().getId();
        UUID spaceId = spaces.list(actorId).getFirst().id();
        UUID budgetId = UUID.randomUUID();
        budgets.create(spaceId, actorId,
                new BudgetRequest(budgetId, 2026, 8, "Receipt concurrency", null));
        UUID incomeTypeId = references.incomeTypes(spaceId, actorId).getFirst().id();
        incomeId = UUID.randomUUID();
        budgets.createIncome(budgetId, actorId, new IncomeRequest(incomeId, "Concurrent salary", incomeTypeId,
                new BigDecimal("100.00"), LocalDate.of(2026, 8, 20), null, "Africa/Johannesburg",
                false, null, null, false, null, null, 0));
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void twoFullReceiptAttemptsCommitExactlyOneReceipt() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        var first = executor.submit(() -> createAfter(start, UUID.randomUUID()));
        var second = executor.submit(() -> createAfter(start, UUID.randomUUID()));
        try {
            start.countDown();
            Outcome a = first.get(20, TimeUnit.SECONDS);
            Outcome b = second.get(20, TimeUnit.SECONDS);
            assertThat(List.of(a, b).stream().filter(outcome -> outcome.response() != null).count()).isEqualTo(1);
            assertThat(List.of(a, b).stream().filter(outcome -> outcome.failure() != null).count()).isEqualTo(1);
            ApiException conflict = a.failure() == null ? b.failure() : a.failure();
            assertThat(conflict.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(conflict.getMessage()).contains("already fully received");
            assertThat(receipts.findAllByIncomeEntryIdAndDeletedAtIsNullOrderByReceivedAtAscCreatedAtAsc(incomeId))
                    .hasSize(1);
        } finally {
            first.cancel(true);
            second.cancel(true);
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private Outcome createAfter(CountDownLatch start, UUID receiptId) {
        try {
            if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Receipt start latch timed out");
            IncomeReceiptResponse response = budgets.createReceipt(incomeId, actorId,
                    new IncomeReceiptRequest(receiptId, incomeId, new BigDecimal("100.00"), Instant.now(),
                            "Africa/Johannesburg", "Concurrent receipt", List.of()));
            return new Outcome(response, null);
        } catch (ApiException ex) {
            return new Outcome(null, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    /**
     * Captures either the committed receipt or the expected competing-request failure.
     *
     * @author Daniel Jr Nkulu
     */
    private record Outcome(IncomeReceiptResponse response, ApiException failure) {}
}
