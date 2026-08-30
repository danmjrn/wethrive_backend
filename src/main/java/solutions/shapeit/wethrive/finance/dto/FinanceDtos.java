package solutions.shapeit.wethrive.finance.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import solutions.shapeit.wethrive.common.domain.DomainEnums.BudgetItemType;
import solutions.shapeit.wethrive.common.domain.DomainEnums.BudgetStatus;
import solutions.shapeit.wethrive.common.domain.DomainEnums.DeductionType;
import solutions.shapeit.wethrive.common.domain.DomainEnums.FundingSourceType;
import solutions.shapeit.wethrive.common.domain.DomainEnums.FundingStatus;
import solutions.shapeit.wethrive.common.domain.DomainEnums.IncomeStatus;
import solutions.shapeit.wethrive.common.domain.DomainEnums.TransactionType;

/** JSON contracts deliberately use decimal values, never binary floating-point values. */
public final class FinanceDtos {
    private FinanceDtos() {}

    public record CategoryRequest(@NotNull UUID id, @NotBlank @Size(max = 100) String name,
                                  @Size(max = 60) String icon, @Min(0) int sortOrder) {}
    public record CategoryUpdateRequest(@NotBlank @Size(max = 100) String name, @Size(max = 60) String icon,
                                        @Min(0) int sortOrder, boolean archived, @NotNull Long version) {}
    public record CategoryResponse(UUID id, UUID spaceId, String name, String icon, int sortOrder,
                                   boolean archived, boolean systemDefault, long version) {}
    public record IncomeTypeRequest(@NotNull UUID id, @NotBlank @Size(max = 100) String name, @Min(0) int sortOrder) {}
    public record IncomeTypeUpdateRequest(@NotBlank @Size(max = 100) String name, @Min(0) int sortOrder,
                                          boolean archived, @NotNull Long version) {}
    public record IncomeTypeResponse(UUID id, UUID spaceId, String name, int sortOrder,
                                     boolean archived, boolean systemDefault, long version) {}

    public record BudgetRequest(@NotNull UUID id, @Min(2000) @Max(2200) int year, @Min(1) @Max(12) int month,
                                @NotBlank @Size(max = 120) String name, @Size(max = 2000) String notes) {}
    public record BudgetUpdateRequest(@NotBlank @Size(max = 120) String name, @NotNull BudgetStatus status,
                                      @Size(max = 2000) String notes, @NotNull Long version) {}
    /** New options are nullable so old queued copy payloads preserve their original behavior. */
    public record BudgetCopyRequest(@NotNull UUID id, @Min(2000) @Max(2200) int year, @Min(1) @Max(12) int month,
                                    @NotBlank @Size(max = 120) String name, boolean recurringOnly,
                                    boolean copyPlannedAmounts, Boolean copyBudgetItemStructure,
                                    Boolean copyRecurringIncomeDefinitions, Boolean adjustScheduledIncomeDates,
                                    Boolean copyFundingPlans) {}
    public record BudgetResponse(UUID id, UUID spaceId, int year, int month, String name, BudgetStatus status,
                                 String notes, long version, Instant createdAt, BudgetSummary summary) {}

    public record IncomeRequest(@NotNull UUID id, @NotBlank @Size(max = 160) String sourceName,
                                @NotNull UUID incomeTypeId, @NotNull @DecimalMin("0") @Digits(integer = 17, fraction = 2) BigDecimal expectedAmount,
                                @NotNull LocalDate expectedDate, LocalTime expectedTime,
                                @NotBlank @Size(max = 60) String timeZone, boolean titheEnabled,
                                @DecimalMin("0") @DecimalMax("1") @Digits(integer = 1, fraction = 4) BigDecimal titheRate,
                                @DecimalMin("0") @Digits(integer = 17, fraction = 2) BigDecimal titheAmountOverride, boolean recurring,
                                @Size(max = 300) String recurrenceRule, @Size(max = 2000) String notes,
                                @Min(0) int sortOrder) {}
    public record IncomeUpdateRequest(@NotBlank @Size(max = 160) String sourceName, @NotNull UUID incomeTypeId,
                                      @NotNull @DecimalMin("0") @Digits(integer = 17, fraction = 2) BigDecimal expectedAmount,
                                      @NotNull LocalDate expectedDate, LocalTime expectedTime,
                                      @NotBlank @Size(max = 60) String timeZone, boolean titheEnabled,
                                      @DecimalMin("0") @DecimalMax("1") @Digits(integer = 1, fraction = 4) BigDecimal titheRate,
                                      @DecimalMin("0") @Digits(integer = 17, fraction = 2) BigDecimal titheAmountOverride, boolean recurring,
                                      @Size(max = 300) String recurrenceRule, @Size(max = 2000) String notes,
                                      @Min(0) int sortOrder, @NotNull Long version) {}
    public record IncomeStateRequest(@NotNull Long version) {}
    public record IncomeAvailability(BigDecimal projectedGrossIncome, BigDecimal projectedDeductions,
                                     BigDecimal projectedNetIncome, BigDecimal receivedGrossIncome,
                                     BigDecimal realizedDeductions, BigDecimal receivedNetIncome,
                                     BigDecimal confirmedAllocatedAmount, BigDecimal unallocatedReceivedIncome,
                                     BigDecimal remainingExpectedIncome, IncomeStatus status) {}
    public record IncomeResponse(UUID id, UUID budgetMonthId, UUID spaceId, String sourceName, UUID incomeTypeId,
                                 BigDecimal expectedAmount, LocalDate expectedDate, LocalTime expectedTime,
                                 String timeZone, boolean titheEnabled, BigDecimal titheRate,
                                 BigDecimal titheAmountOverride, boolean recurring, String recurrenceRule,
                                 Instant cancelledAt, String notes, int sortOrder, long version,
                                 IncomeAvailability availability,
                                 List<IncomeDeductionResponse> deductions) {
        public IncomeResponse(UUID id, UUID budgetMonthId, UUID spaceId, String sourceName, UUID incomeTypeId,
                              BigDecimal expectedAmount, LocalDate expectedDate, LocalTime expectedTime,
                              String timeZone, boolean titheEnabled, BigDecimal titheRate,
                              BigDecimal titheAmountOverride, boolean recurring, String recurrenceRule,
                              Instant cancelledAt, String notes, int sortOrder, long version,
                              IncomeAvailability availability) {
            this(id, budgetMonthId, spaceId, sourceName, incomeTypeId, expectedAmount, expectedDate,
                    expectedTime, timeZone, titheEnabled, titheRate, titheAmountOverride, recurring,
                    recurrenceRule, cancelledAt, notes, sortOrder, version, availability, List.of());
        }
    }

    public record IncomeDeductionRequest(@NotNull UUID id, @NotNull UUID incomeEntryId,
                                         @NotBlank @Size(max = 160) String name,
                                         @NotNull DeductionType deductionType,
                                         @DecimalMin("0") @DecimalMax("1") @Digits(integer = 1, fraction = 4) BigDecimal percentageRate,
                                         @DecimalMin("0") @Digits(integer = 17, fraction = 2) BigDecimal fixedAmount,
                                         @Size(max = 2000) String notes, @Min(0) int sortOrder) {}
    public record IncomeDeductionUpdateRequest(@NotBlank @Size(max = 160) String name,
                                               @NotNull DeductionType deductionType,
                                               @DecimalMin("0") @DecimalMax("1") @Digits(integer = 1, fraction = 4) BigDecimal percentageRate,
                                               @DecimalMin("0") @Digits(integer = 17, fraction = 2) BigDecimal fixedAmount,
                                               @Size(max = 2000) String notes, @Min(0) int sortOrder,
                                               @NotNull Long version) {}
    public record IncomeDeductionResponse(UUID id, UUID incomeEntryId, UUID spaceId, String name,
                                          DeductionType deductionType, BigDecimal percentageRate,
                                          BigDecimal fixedAmount, BigDecimal projectedAmount,
                                          String notes, int sortOrder, boolean legacyTithe,
                                          UUID createdByUserId, UUID updatedByUserId,
                                          Instant createdAt, Instant updatedAt, Instant deletedAt,
                                          long version) {}

    /** Null means a legacy V2 client did not review deductions; an empty list is an explicit review. */
    public record ReceiptDeductionLineRequest(@NotNull UUID id, @NotNull UUID incomeDeductionId,
                                              @NotNull @DecimalMin("0") @Digits(integer = 17, fraction = 2) BigDecimal amount,
                                              Long version) {}

    public record IncomeReceiptRequest(@NotNull UUID id, @NotNull UUID incomeEntryId,
                                       @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 17, fraction = 2) BigDecimal amount,
                                       @NotNull Instant receivedAt, @NotBlank @Size(max = 60) String timeZone,
                                       @Size(max = 2000) String notes,
                                       @Size(max = 100) List<@Valid ReceiptDeductionLineRequest> deductions) {
        public IncomeReceiptRequest(UUID id, UUID incomeEntryId, BigDecimal amount, Instant receivedAt,
                                    String timeZone, String notes) {
            this(id, incomeEntryId, amount, receivedAt, timeZone, notes, null);
        }
    }
    public record IncomeReceiptUpdateRequest(@NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 17, fraction = 2) BigDecimal amount,
                                             @NotNull Instant receivedAt, @NotBlank @Size(max = 60) String timeZone,
                                             @Size(max = 2000) String notes, @NotNull Long version,
                                             @Size(max = 100) List<@Valid ReceiptDeductionLineRequest> deductions) {
        public IncomeReceiptUpdateRequest(BigDecimal amount, Instant receivedAt, String timeZone,
                                          String notes, Long version) {
            this(amount, receivedAt, timeZone, notes, version, null);
        }
    }
    public record IncomeReceiptResponse(UUID id, UUID incomeEntryId, UUID spaceId, BigDecimal amount,
                                        Instant receivedAt, String timeZone, String notes, UUID recordedByUserId,
                                        UUID createdByUserId, UUID updatedByUserId, Instant createdAt,
                                        Instant updatedAt, Instant deletedAt, long version,
                                        List<IncomeReceiptDeductionResponse> deductions,
                                        BigDecimal totalDeductions, BigDecimal netAmount) {
        public IncomeReceiptResponse(UUID id, UUID incomeEntryId, UUID spaceId, BigDecimal amount,
                                     Instant receivedAt, String timeZone, String notes, UUID recordedByUserId,
                                     UUID createdByUserId, UUID updatedByUserId, Instant createdAt,
                                     Instant updatedAt, Instant deletedAt, long version) {
            this(id, incomeEntryId, spaceId, amount, receivedAt, timeZone, notes, recordedByUserId,
                    createdByUserId, updatedByUserId, createdAt, updatedAt, deletedAt, version,
                    List.of(), BigDecimal.ZERO.setScale(2), amount);
        }
    }

    public record IncomeReceiptDeductionRequest(@NotNull UUID id, @NotNull UUID incomeReceiptId,
                                                @NotNull UUID incomeDeductionId,
                                                @NotNull @DecimalMin("0") @Digits(integer = 17, fraction = 2) BigDecimal amount) {}
    public record IncomeReceiptDeductionUpdateRequest(@NotNull @DecimalMin("0") @Digits(integer = 17, fraction = 2) BigDecimal amount,
                                                      @NotNull Long version) {}
    public record IncomeReceiptDeductionResponse(UUID id, UUID incomeReceiptId, UUID incomeDeductionId,
                                                 UUID spaceId, String name, BigDecimal amount,
                                                 UUID createdByUserId, UUID updatedByUserId,
                                                 Instant createdAt, Instant updatedAt, Instant deletedAt,
                                                 long version) {}

    public record FundingAllocationRequest(@NotNull UUID id, @NotNull UUID budgetItemId, UUID incomeEntryId,
                                           @NotNull FundingSourceType sourceType,
                                           @NotNull @DecimalMin("0") @Digits(integer = 17, fraction = 2) BigDecimal plannedAmount,
                                           @NotNull @DecimalMin("0") @Digits(integer = 17, fraction = 2) BigDecimal confirmedAllocatedAmount,
                                           Instant allocatedAt, @Size(max = 60) String timeZone,
                                           @Size(max = 2000) String notes) {}
    public record FundingAllocationUpdateRequest(UUID incomeEntryId, @NotNull FundingSourceType sourceType,
                                                 @NotNull @DecimalMin("0") @Digits(integer = 17, fraction = 2) BigDecimal plannedAmount,
                                                 @NotNull @DecimalMin("0") @Digits(integer = 17, fraction = 2) BigDecimal confirmedAllocatedAmount,
                                                 Instant allocatedAt, @Size(max = 60) String timeZone,
                                                 @Size(max = 2000) String notes, @NotNull Long version) {}
    public record FundingAllocationResponse(UUID id, UUID budgetItemId, UUID incomeEntryId, UUID spaceId,
                                            FundingSourceType sourceType, BigDecimal plannedAmount,
                                            BigDecimal confirmedAllocatedAmount, Instant allocatedAt, String timeZone,
                                            String notes, UUID createdByUserId, UUID updatedByUserId,
                                            Instant createdAt, Instant updatedAt, Instant deletedAt, long version) {}
    public record FundingSummary(UUID budgetItemId, BigDecimal plannedBudgetAmount, BigDecimal plannedFunding,
                                 BigDecimal confirmedAllocatedAmount, BigDecimal fundingGap,
                                 BigDecimal unspentAllocatedFunds, BigDecimal unfundedSpending,
                                 FundingStatus status, List<FundingAllocationResponse> allocations) {}
    public record BudgetFundingSummary(UUID budgetId, BigDecimal plannedBudgetAmount,
                                       BigDecimal plannedFunding, BigDecimal confirmedAllocatedAmount,
                                       BigDecimal fundingGap, int fullyFundedItemCount,
                                       int partiallyFundedItemCount, int unfundedItemCount,
                                       List<FundingSummary> items) {}

    public record BudgetItemRequest(@NotNull UUID id, @NotBlank @Size(max = 160) String name,
                                    @NotNull UUID categoryId, @NotNull @DecimalMin("0") @Digits(integer = 17, fraction = 2) BigDecimal plannedAmount,
                                    boolean tracked, @NotNull BudgetItemType itemType, boolean recurring,
                                    boolean rolloverEnabled, @Size(max = 2000) String notes, @Min(0) int sortOrder) {}
    public record BudgetItemUpdateRequest(@NotBlank @Size(max = 160) String name, @NotNull UUID categoryId,
                                          @NotNull @DecimalMin("0") @Digits(integer = 17, fraction = 2) BigDecimal plannedAmount, boolean tracked,
                                          @NotNull BudgetItemType itemType, boolean recurring, boolean rolloverEnabled,
                                          @Size(max = 2000) String notes, @Min(0) int sortOrder, @NotNull Long version) {}
    public record BudgetItemResponse(UUID id, UUID budgetMonthId, UUID spaceId, String name, UUID categoryId,
                                     BigDecimal plannedAmount, boolean tracked, BudgetItemType itemType,
                                     boolean recurring, boolean rolloverEnabled, String notes, int sortOrder,
                                     long version, ItemCalculation calculation, FundingSummary funding) {}

    public record SpendingRequest(@NotNull UUID id, @NotNull UUID budgetItemId, @NotNull TransactionType transactionType,
                                  @NotBlank @Size(max = 180) String title, @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 17, fraction = 2) BigDecimal amount,
                                  @NotNull LocalDate date, @NotNull LocalTime time, @NotBlank @Size(max = 60) String timeZone,
                                  @Size(max = 80) String paymentMethod, @Size(max = 160) String merchant,
                                  @Size(max = 2000) String notes, UUID spentByUserId,
                                  UUID refundForSpendingEntryId) {
        /** Compatibility constructor for expenses and legacy payload readers; new refunds require a source link. */
        public SpendingRequest(UUID id, UUID budgetItemId, TransactionType transactionType,
                               String title, BigDecimal amount, LocalDate date, LocalTime time, String timeZone,
                               String paymentMethod, String merchant, String notes, UUID spentByUserId) {
            this(id, budgetItemId, transactionType, title, amount, date, time, timeZone, paymentMethod,
                    merchant, notes, spentByUserId, null);
        }
    }
    public record SpendingUpdateRequest(@NotNull UUID budgetItemId, @NotNull TransactionType transactionType,
                                        @NotBlank @Size(max = 180) String title,
                                        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 17, fraction = 2) BigDecimal amount,
                                        @NotNull LocalDate date, @NotNull LocalTime time,
                                        @NotBlank @Size(max = 60) String timeZone, @Size(max = 80) String paymentMethod,
                                        @Size(max = 160) String merchant, @Size(max = 2000) String notes,
                                        UUID spentByUserId, @NotNull Long version) {}
    public record RefundRequest(@NotNull UUID id, @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 17, fraction = 2) BigDecimal amount,
                                @NotNull LocalDate date, @NotNull LocalTime time, @NotBlank @Size(max = 60) String timeZone,
                                @Size(max = 2000) String notes) {}
    public record MoveSpendingRequest(@NotNull UUID budgetItemId, @NotNull Long version) {}
    public record RestoreSpendingRequest(@NotNull Long version) {}
    /**
     * Carries the optimistic version required by destructive finance mutations.
     *
     * @author Daniel Jr Nkulu
     */
    public record VersionRequest(@NotNull Long version) {}
    public record SpendingResponse(UUID id, UUID spaceId, UUID budgetItemId, TransactionType transactionType,
                                   String title, BigDecimal amount, Instant spentAt, LocalDate date, LocalTime time,
                                   String timeZone, String paymentMethod, String merchant, String notes,
                                   UUID spentByUserId, UUID createdByUserId, UUID refundForSpendingEntryId,
                                   long version) {
        /** Compatibility constructor for callers that do not yet consume linked-refund metadata. */
        public SpendingResponse(UUID id, UUID spaceId, UUID budgetItemId, TransactionType transactionType,
                                String title, BigDecimal amount, Instant spentAt, LocalDate date, LocalTime time,
                                String timeZone, String paymentMethod, String merchant, String notes,
                                UUID spentByUserId, UUID createdByUserId, long version) {
            this(id, spaceId, budgetItemId, transactionType, title, amount, spentAt, date, time, timeZone,
                    paymentMethod, merchant, notes, spentByUserId, createdByUserId, null, version);
        }
    }

    public record ItemCalculation(BigDecimal grossSpending, BigDecimal refunds, BigDecimal netSpending,
                                  BigDecimal remaining, BigDecimal usagePercentage, String status, int transactionCount,
                                  BigDecimal plannedFunding, BigDecimal confirmedAllocatedAmount,
                                  BigDecimal fundingGap, BigDecimal unspentAllocatedFunds,
                                  BigDecimal unfundedSpending, FundingStatus fundingStatus) {}
    public record BudgetSummary(BigDecimal projectedGrossIncome, BigDecimal projectedNetIncome,
                                BigDecimal receivedGrossIncome, BigDecimal receivedNetIncome,
                                BigDecimal projectedDeductions, BigDecimal realizedDeductions,
                                BigDecimal confirmedAllocatedIncome, BigDecimal unallocatedReceivedIncome,
                                BigDecimal remainingExpectedIncome, BigDecimal plannedSpending,
                                BigDecimal confirmedFundedBudget, BigDecimal fundingGap,
                                BigDecimal grossSpending, BigDecimal refunds, BigDecimal netSpending,
                                BigDecimal unbudgetedSpending, BigDecimal remainingIncome,
                                BigDecimal spendingRate, BigDecimal safeToSpend, BigDecimal forecast,
                                int fullyFundedItemCount, int partiallyFundedItemCount, int unfundedItemCount,
                                String status, List<BudgetItemResponse> items) {}
}
