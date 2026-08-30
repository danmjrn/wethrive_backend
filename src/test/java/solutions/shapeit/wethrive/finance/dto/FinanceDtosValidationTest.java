package solutions.shapeit.wethrive.finance.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import solutions.shapeit.wethrive.common.domain.DomainEnums.BudgetItemType;
import solutions.shapeit.wethrive.common.domain.DomainEnums.TransactionType;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.BudgetItemRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.SpendingRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeReceiptRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.ReceiptDeductionLineRequest;

class FinanceDtosValidationTest {
    private final jakarta.validation.Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void rejectsSubCentSpendingBeforeServiceRounding() {
        var request = spending(new BigDecimal("0.001"));

        assertThat(validator.validate(request))
                .anySatisfy(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("amount"));
    }

    @Test
    void enforcesNumericNineteenTwoBoundaryForBudgetPlans() {
        var valid = new BudgetItemRequest(UUID.randomUUID(), "Plan", UUID.randomUUID(),
                new BigDecimal("99999999999999999.99"), true, BudgetItemType.PLANNED,
                false, false, null, 0);
        var tooLarge = new BudgetItemRequest(UUID.randomUUID(), "Plan", UUID.randomUUID(),
                new BigDecimal("100000000000000000.00"), true, BudgetItemType.PLANNED,
                false, false, null, 0);

        assertThat(validator.validate(valid)).isEmpty();
        assertThat(validator.validate(tooLarge))
                .anySatisfy(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("plannedAmount"));
    }

    @Test
    void receiptGrossMustBePositiveAndReviewedDeductionsMustBeCentExact() {
        UUID receiptId = UUID.randomUUID();
        var zeroGross = new IncomeReceiptRequest(receiptId, UUID.randomUUID(), BigDecimal.ZERO,
                Instant.parse("2026-07-21T10:00:00Z"), "Africa/Johannesburg", null, List.of());
        var subCentDeduction = new IncomeReceiptRequest(receiptId, UUID.randomUUID(),
                new BigDecimal("100.00"), Instant.parse("2026-07-21T10:00:00Z"),
                "Africa/Johannesburg", null, List.of(new ReceiptDeductionLineRequest(
                        UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("1.001"), null)));

        assertThat(validator.validate(zeroGross)).anySatisfy(violation ->
                assertThat(violation.getPropertyPath().toString()).isEqualTo("amount"));
        assertThat(validator.validate(subCentDeduction)).anySatisfy(violation ->
                assertThat(violation.getPropertyPath().toString()).contains("deductions"));
    }

    private SpendingRequest spending(BigDecimal amount) {
        return new SpendingRequest(UUID.randomUUID(), UUID.randomUUID(), TransactionType.EXPENSE,
                "Groceries", amount, LocalDate.of(2026, 7, 21), LocalTime.NOON, "Africa/Johannesburg",
                null, null, null, null);
    }
}
