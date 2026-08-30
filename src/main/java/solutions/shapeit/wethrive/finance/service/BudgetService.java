package solutions.shapeit.wethrive.finance.service;

import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import solutions.shapeit.wethrive.audit.service.AuditService;
import solutions.shapeit.wethrive.common.domain.DomainChangeRecorder;
import solutions.shapeit.wethrive.common.domain.DomainEnums.AuditResult;
import solutions.shapeit.wethrive.common.domain.DomainEnums.BudgetItemType;
import solutions.shapeit.wethrive.common.domain.DomainEnums.BudgetStatus;
import solutions.shapeit.wethrive.common.domain.DomainEnums.DeductionType;
import solutions.shapeit.wethrive.common.domain.DomainEnums.FundingSourceType;
import solutions.shapeit.wethrive.common.domain.DomainEnums.IncomeStatus;
import solutions.shapeit.wethrive.common.domain.DomainEnums.SyncOperationType;
import solutions.shapeit.wethrive.common.web.ApiException;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.BudgetCopyRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.BudgetFundingSummary;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.BudgetItemRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.BudgetItemResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.BudgetItemUpdateRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.BudgetRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.BudgetResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.BudgetUpdateRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.FundingAllocationRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.FundingAllocationResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.FundingAllocationUpdateRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.FundingSummary;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeAvailability;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeDeductionRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeDeductionResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeDeductionUpdateRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeReceiptRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeReceiptDeductionRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeReceiptDeductionResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeReceiptDeductionUpdateRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeReceiptResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeReceiptUpdateRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.ReceiptDeductionLineRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeStateRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeUpdateRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.VersionRequest;
import solutions.shapeit.wethrive.finance.entity.BudgetFundingAllocation;
import solutions.shapeit.wethrive.finance.entity.BudgetItem;
import solutions.shapeit.wethrive.finance.entity.BudgetMonth;
import solutions.shapeit.wethrive.finance.entity.IncomeEntry;
import solutions.shapeit.wethrive.finance.entity.IncomeDeduction;
import solutions.shapeit.wethrive.finance.entity.IncomeReceipt;
import solutions.shapeit.wethrive.finance.entity.IncomeReceiptDeduction;
import solutions.shapeit.wethrive.finance.entity.SpendingEntry;
import solutions.shapeit.wethrive.finance.event.BudgetPeriodStateChanged;
import solutions.shapeit.wethrive.finance.repository.BudgetFundingAllocationRepository;
import solutions.shapeit.wethrive.finance.repository.BudgetItemRepository;
import solutions.shapeit.wethrive.finance.repository.BudgetMonthRepository;
import solutions.shapeit.wethrive.finance.repository.IncomeEntryRepository;
import solutions.shapeit.wethrive.finance.repository.IncomeDeductionRepository;
import solutions.shapeit.wethrive.finance.repository.IncomeReceiptRepository;
import solutions.shapeit.wethrive.finance.repository.IncomeReceiptDeductionRepository;
import solutions.shapeit.wethrive.finance.repository.SpendingEntryRepository;
import solutions.shapeit.wethrive.space.service.SpaceAccessService;
import solutions.shapeit.wethrive.space.service.SpaceAccessService.Capability;
import solutions.shapeit.wethrive.space.service.SpaceService;

/** Finance command service. All mutation authorization is enforced here, not by the UI. */
@Service
public class BudgetService {
    private final BudgetMonthRepository budgets;
    private final IncomeEntryRepository income;
    private final IncomeReceiptRepository receipts;
    private final IncomeDeductionRepository deductions;
    private final IncomeReceiptDeductionRepository receiptDeductions;
    private final BudgetItemRepository items;
    private final BudgetFundingAllocationRepository allocations;
    private final SpendingEntryRepository spending;
    private final ReferenceFinanceService references;
    private final FinanceCalculationService calculations;
    private final SpaceAccessService access;
    private final SpaceService spaces;
    private final AuditService audit;
    private final ObjectProvider<DomainChangeRecorder> changes;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public BudgetService(BudgetMonthRepository budgets, IncomeEntryRepository income, IncomeReceiptRepository receipts,
                         IncomeDeductionRepository deductions, IncomeReceiptDeductionRepository receiptDeductions,
                         BudgetItemRepository items, BudgetFundingAllocationRepository allocations,
                         SpendingEntryRepository spending, ReferenceFinanceService references,
                         FinanceCalculationService calculations, SpaceAccessService access, SpaceService spaces,
                         AuditService audit, ObjectProvider<DomainChangeRecorder> changes, Clock clock,
                         ApplicationEventPublisher events) {
        this.budgets = budgets; this.income = income; this.receipts = receipts;
        this.deductions = deductions; this.receiptDeductions = receiptDeductions; this.items = items;
        this.allocations = allocations; this.spending = spending; this.references = references;
        this.calculations = calculations; this.access = access; this.spaces = spaces; this.audit = audit;
        this.changes = changes; this.clock = clock; this.events = events;
    }

    @Transactional
    public List<BudgetResponse> list(UUID spaceId, UUID actorId) {
        access.require(spaceId, actorId, Capability.VIEW);
        return budgets.findAllBySpaceIdAndDeletedAtIsNullOrderByYearDescMonthDesc(spaceId).stream().map(this::map).toList();
    }

    /**
     * Export-only budget register lookup. Child finance rows are loaded in a fixed number of
     * bulk queries instead of once per budget.
     */
    @Transactional
    public List<BudgetResponse> listForExport(UUID spaceId, UUID actorId) {
        access.require(spaceId, actorId, Capability.EXPORT);
        return mapAll(budgets.findAllBySpaceIdAndDeletedAtIsNullOrderByYearDescMonthDesc(spaceId));
    }

    /** Export-only, period-scoped lookup used by monthly, quarterly, and annual documents. */
    @Transactional
    public List<BudgetResponse> listForExport(UUID spaceId, int year, int firstMonth, int lastMonth,
                                              UUID actorId) {
        access.require(spaceId, actorId, Capability.EXPORT);
        return mapAll(budgets
                .findAllBySpaceIdAndYearAndMonthBetweenAndDeletedAtIsNullOrderByYearDescMonthDesc(
                        spaceId, year, firstMonth, lastMonth));
    }

    /** Export-only direct lookup; unlike the regular API mapper it never enters a list N+1 path. */
    @Transactional
    public BudgetResponse getForExport(UUID id, UUID actorId) {
        BudgetMonth budget = requireBudget(id);
        access.require(budget.getSpaceId(), actorId, Capability.EXPORT);
        return mapAll(List.of(budget)).getFirst();
    }

    @Transactional
    public BudgetResponse get(UUID id, UUID actorId) {
        BudgetMonth budget = requireBudget(id); access.require(budget.getSpaceId(), actorId, Capability.VIEW);
        return map(budget);
    }

    @Transactional
    public BudgetResponse create(UUID spaceId, UUID actorId, BudgetRequest request) {
        access.require(spaceId, actorId, Capability.EDIT_FINANCE);
        if (budgets.existsById(request.id())) throw ApiException.conflict("A budget with this identifier already exists");
        if (budgets.existsBySpaceIdAndYearAndMonthAndDeletedAtIsNull(spaceId, request.year(), request.month())) {
            throw ApiException.conflict("A budget already exists for this space and month");
        }
        BudgetMonth budget = new BudgetMonth(); budget.setId(request.id()); budget.setSpaceId(spaceId);
        budget.setCreatedByUserId(actorId); budget.setUpdatedByUserId(actorId); budget.setYear(request.year());
        budget.setMonth(request.month()); budget.setName(request.name().trim()); budget.setStatus(BudgetStatus.DRAFT);
        budget.setNotes(request.notes()); budget = budgets.saveAndFlush(budget);
        BudgetResponse response = map(budget);
        record(spaceId, "BUDGET_MONTH", budget.getId(), SyncOperationType.CREATE, budget.getVersion(), actorId, false, response);
        events.publishEvent(new BudgetPeriodStateChanged(spaceId, budget.getYear(), budget.getMonth(), true,
                Instant.now(clock)));
        return response;
    }

    @Transactional
    public BudgetResponse update(UUID id, UUID actorId, BudgetUpdateRequest request) {
        BudgetMonth budget = requireBudget(id); access.require(budget.getSpaceId(), actorId, Capability.EDIT_FINANCE);
        requireVersion(budget.getVersion(), request.version());
        if (request.status() != budget.getStatus()) throw ApiException.badRequest("Use the close or reopen action to change budget status");
        budget.setName(request.name().trim()); budget.setNotes(request.notes()); budget.setUpdatedByUserId(actorId);
        budget = budgets.saveAndFlush(budget); BudgetResponse response = map(budget);
        record(budget.getSpaceId(), "BUDGET_MONTH", id, SyncOperationType.UPDATE, budget.getVersion(), actorId, false, response);
        return response;
    }

    @Transactional public BudgetResponse close(UUID id, UUID actorId, VersionRequest request) {
        return changeStatus(id, actorId, BudgetStatus.CLOSED, request.version());
    }
    @Transactional public BudgetResponse reopen(UUID id, UUID actorId, VersionRequest request) {
        return changeStatus(id, actorId, BudgetStatus.ACTIVE, request.version());
    }

    @Transactional
    public void delete(UUID id, UUID actorId, VersionRequest request) {
        // Lifecycle mutations take locks in one order: budget, items by UUID, income by UUID,
        // then individual allocation rows. Funding commands use the same budget -> item prefix.
        BudgetMonth budget = requireBudgetForUpdate(id); access.require(budget.getSpaceId(), actorId, Capability.EDIT_FINANCE);
        requireVersion(budget.getVersion(), request.version());
        List<BudgetItem> listedItems = items.findAllByBudgetMonthIdAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(id);
        List<BudgetItem> budgetItems = listedItems.stream().map(BudgetItem::getId).sorted()
                .map(this::requireItemForUpdate).toList();
        List<IncomeEntry> budgetIncome = income.findAllByBudgetMonthIdAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(id);
        budgetIncome.stream().map(IncomeEntry::getId).sorted().forEach(this::requireIncomeForUpdate);
        List<UUID> historicalItemIds = items.findAllByBudgetMonthIdOrderBySortOrderAscCreatedAtAsc(id)
                .stream().map(BudgetItem::getId).toList();
        List<UUID> historicalIncomeIds = income.findAllByBudgetMonthIdOrderBySortOrderAscCreatedAtAsc(id)
                .stream().map(IncomeEntry::getId).toList();
        boolean hasLedgerHistory = !historicalItemIds.isEmpty()
                && (historicalItemIds.stream().anyMatch(spending::existsByBudgetItemId)
                    || allocations.countByBudgetItemIdIn(historicalItemIds) > 0);
        hasLedgerHistory = hasLedgerHistory || (!historicalIncomeIds.isEmpty()
                && receipts.countByIncomeEntryIdIn(historicalIncomeIds) > 0);
        if (hasLedgerHistory) {
            throw ApiException.conflict("A budget with spending, funding, or receipt history cannot be deleted; close it instead");
        }
        Instant now = Instant.now(clock);
        for (IncomeEntry entry : budgetIncome) {
            for (IncomeDeduction deduction : deductions.findAllForUpdateByIncomeEntryId(entry.getId())) {
                deduction.setDeletedAt(now); deduction.setUpdatedByUserId(actorId);
                deduction = deductions.saveAndFlush(deduction);
                recordDeduction(deduction, SyncOperationType.DELETE, actorId, true);
            }
            entry.setDeletedAt(now); entry.setUpdatedByUserId(actorId); entry = income.saveAndFlush(entry);
            record(entry.getSpaceId(), "INCOME_ENTRY", entry.getId(), SyncOperationType.DELETE, entry.getVersion(), actorId, true, null);
        }
        for (BudgetItem item : budgetItems) {
            item.setDeletedAt(now); item.setUpdatedByUserId(actorId); item = items.saveAndFlush(item);
            record(item.getSpaceId(), "BUDGET_ITEM", item.getId(), SyncOperationType.DELETE, item.getVersion(), actorId, true, null);
        }
        budget.setDeletedAt(now); budget.setUpdatedByUserId(actorId); budget = budgets.saveAndFlush(budget);
        record(budget.getSpaceId(), "BUDGET_MONTH", id, SyncOperationType.DELETE, budget.getVersion(), actorId, true, null);
        events.publishEvent(new BudgetPeriodStateChanged(budget.getSpaceId(), budget.getYear(), budget.getMonth(), false, now));
    }

    @Transactional
    public BudgetResponse copy(UUID sourceId, UUID actorId, BudgetCopyRequest request) {
        BudgetMonth source = requireBudget(sourceId); access.require(source.getSpaceId(), actorId, Capability.EDIT_FINANCE);
        if (budgets.existsById(request.id()) || budgets.existsBySpaceIdAndYearAndMonthAndDeletedAtIsNull(
                source.getSpaceId(), request.year(), request.month())) throw ApiException.conflict("The target budget already exists");
        create(source.getSpaceId(), actorId, new BudgetRequest(request.id(), request.year(), request.month(), request.name(), source.getNotes()));
        boolean copyIncome = request.copyRecurringIncomeDefinitions() == null || request.copyRecurringIncomeDefinitions();
        boolean copyItems = request.copyBudgetItemStructure() == null || request.copyBudgetItemStructure();
        boolean adjustSchedule = request.adjustScheduledIncomeDates() == null || request.adjustScheduledIncomeDates();
        Map<UUID, UUID> incomeIds = new HashMap<>(); Map<UUID, UUID> itemIds = new HashMap<>();
        if (copyIncome) {
            for (IncomeEntry original : income.findAllByBudgetMonthIdAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(sourceId)) {
                // This option copies recurring definitions, never one-off or cancelled income.
                if (!original.isRecurring() || original.getCancelledAt() != null) continue;
                IncomeEntry clone = new IncomeEntry(); clone.setId(UUID.randomUUID()); clone.setSpaceId(source.getSpaceId());
                clone.setBudgetMonthId(request.id()); clone.setCreatedByUserId(actorId); clone.setUpdatedByUserId(actorId);
                clone.setSourceName(original.getSourceName()); clone.setIncomeTypeId(original.getIncomeTypeId());
                clone.setExpectedAmount(request.copyPlannedAmounts() ? original.getExpectedAmount() : FinanceCalculationService.ZERO);
                // Preserve the day offset from the source budget's first day. This retains valid
                // early/late schedules that intentionally fall outside the nominal budget month.
                LocalDate copiedExpectedDate = adjustSchedule ? adjustedDate(original.getExpectedDate(), source,
                        request.year(), request.month()) : original.getExpectedDate();
                validateExpectedDate(copiedExpectedDate);
                clone.setExpectedDate(copiedExpectedDate);
                clone.setExpectedTime(original.getExpectedTime()); clone.setTimeZone(original.getTimeZone());
                clone.setTitheEnabled(original.isTitheEnabled()); clone.setTitheRate(original.getTitheRate());
                clone.setTitheAmountOverride(request.copyPlannedAmounts() ? original.getTitheAmountOverride()
                        : original.getTitheAmountOverride() == null ? null : FinanceCalculationService.ZERO);
                clone.setRecurring(original.isRecurring());
                clone.setRecurrenceRule(original.getRecurrenceRule()); clone.setNotes(original.getNotes()); clone.setSortOrder(original.getSortOrder());
                clone = income.saveAndFlush(clone); incomeIds.put(original.getId(), clone.getId());
                for (IncomeDeduction originalDeduction : deductions
                        .findAllByIncomeEntryIdAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(original.getId())) {
                    IncomeDeduction deduction = new IncomeDeduction();
                    deduction.setId(originalDeduction.isLegacyTithe() ? clone.getId() : UUID.randomUUID());
                    deduction.setSpaceId(clone.getSpaceId()); deduction.setIncomeEntryId(clone.getId());
                    deduction.setCreatedByUserId(actorId); deduction.setUpdatedByUserId(actorId);
                    deduction.setName(originalDeduction.getName()); deduction.setDeductionType(originalDeduction.getDeductionType());
                    deduction.setPercentageRate(originalDeduction.getPercentageRate());
                    deduction.setFixedAmount(originalDeduction.getFixedAmount() == null ? null
                            : request.copyPlannedAmounts() ? calculations.money(originalDeduction.getFixedAmount())
                            : FinanceCalculationService.ZERO);
                    deduction.setNotes(originalDeduction.getNotes()); deduction.setSortOrder(originalDeduction.getSortOrder());
                    deduction.setLegacyTithe(originalDeduction.isLegacyTithe());
                    deduction = deductions.saveAndFlush(deduction);
                    recordDeduction(deduction, SyncOperationType.CREATE, actorId, false);
                }
                record(clone.getSpaceId(), "INCOME_ENTRY", clone.getId(), SyncOperationType.CREATE,
                        clone.getVersion(), actorId, false, mapIncome(clone));
            }
        }
        if (copyItems) {
            for (BudgetItem original : items.findAllByBudgetMonthIdAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(sourceId)) {
                if (request.recurringOnly() && !original.isRecurring()) continue;
                BudgetItem clone = new BudgetItem(); clone.setId(UUID.randomUUID()); clone.setSpaceId(source.getSpaceId());
                clone.setBudgetMonthId(request.id()); clone.setCreatedByUserId(actorId); clone.setUpdatedByUserId(actorId);
                clone.setName(original.getName()); clone.setCategoryId(original.getCategoryId());
                clone.setPlannedAmount(request.copyPlannedAmounts() ? original.getPlannedAmount() : FinanceCalculationService.ZERO);
                clone.setTracked(original.isTracked()); clone.setItemType(original.getItemType()); clone.setRecurring(original.isRecurring());
                clone.setRolloverEnabled(original.isRolloverEnabled()); clone.setNotes(original.getNotes()); clone.setSortOrder(original.getSortOrder());
                clone = items.saveAndFlush(clone); itemIds.put(original.getId(), clone.getId());
                record(clone.getSpaceId(), "BUDGET_ITEM", clone.getId(), SyncOperationType.CREATE, clone.getVersion(), actorId, false, mapItem(clone));
            }
        }
        if (Boolean.TRUE.equals(request.copyFundingPlans()) && !itemIds.isEmpty()) {
            for (BudgetFundingAllocation original : allocations.findAllByBudgetItemIdInAndDeletedAtIsNull(itemIds.keySet())) {
                UUID newItemId = itemIds.get(original.getBudgetItemId());
                UUID newIncomeId = original.getIncomeEntryId() == null ? null : incomeIds.get(original.getIncomeEntryId());
                if (newItemId == null || original.getPlannedAmount().signum() <= 0 ||
                        (original.getSourceType() == FundingSourceType.INCOME_ENTRY && newIncomeId == null)) continue;
                BudgetFundingAllocation clone = new BudgetFundingAllocation(); clone.setId(UUID.randomUUID()); clone.setSpaceId(source.getSpaceId());
                clone.setBudgetItemId(newItemId); clone.setIncomeEntryId(newIncomeId); clone.setSourceType(original.getSourceType());
                clone.setPlannedAmount(calculations.money(original.getPlannedAmount())); clone.setConfirmedAllocatedAmount(FinanceCalculationService.ZERO);
                clone.setAllocatedAt(null); clone.setTimeZone(original.getTimeZone()); clone.setNotes(original.getNotes());
                clone.setCreatedByUserId(actorId); clone.setUpdatedByUserId(actorId); clone = allocations.saveAndFlush(clone);
                record(clone.getSpaceId(), "FUNDING_ALLOCATION", clone.getId(), SyncOperationType.CREATE, clone.getVersion(), actorId, false, calculations.allocation(clone));
            }
        }
        budgets.flush(); return map(requireBudget(request.id()));
    }

    @Transactional
    public List<IncomeResponse> income(UUID budgetId, UUID actorId) {
        BudgetMonth budget = requireBudget(budgetId); access.require(budget.getSpaceId(), actorId, Capability.VIEW);
        List<IncomeEntry> values = income.findAllByBudgetMonthIdAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(budgetId);
        if (values.isEmpty()) return List.of();
        List<IncomeReceipt> receiptValues = receipts.findAllByIncomeEntryIdInAndDeletedAtIsNull(values.stream().map(IncomeEntry::getId).toList());
        List<IncomeDeduction> deductionValues = deductions.findAllByIncomeEntryIdInAndDeletedAtIsNull(
                values.stream().map(IncomeEntry::getId).toList());
        List<IncomeReceiptDeduction> actualValues = receiptValues.isEmpty() ? List.of()
                : receiptDeductions.findAllByIncomeReceiptIdInAndDeletedAtIsNull(
                        receiptValues.stream().map(IncomeReceipt::getId).toList());
        List<BudgetFundingAllocation> allocationValues = allocations.findAllByIncomeEntryIdInAndDeletedAtIsNull(values.stream().map(IncomeEntry::getId).toList());
        Map<UUID, List<IncomeReceipt>> receiptByIncome = receiptValues.stream().collect(Collectors.groupingBy(IncomeReceipt::getIncomeEntryId));
        Map<UUID, List<IncomeDeduction>> deductionByIncome = deductionValues.stream().collect(Collectors.groupingBy(IncomeDeduction::getIncomeEntryId));
        Map<UUID, List<IncomeReceiptDeduction>> actualByReceipt = actualValues.stream().collect(Collectors.groupingBy(IncomeReceiptDeduction::getIncomeReceiptId));
        Map<UUID, List<BudgetFundingAllocation>> allocationByIncome = allocationValues.stream().collect(Collectors.groupingBy(BudgetFundingAllocation::getIncomeEntryId));
        return values.stream().map(entry -> {
            List<IncomeReceipt> entryReceipts = receiptByIncome.getOrDefault(entry.getId(), List.of());
            List<IncomeReceiptDeduction> entryActuals = entryReceipts.stream()
                    .flatMap(receipt -> actualByReceipt.getOrDefault(receipt.getId(), List.of()).stream()).toList();
            return calculations.income(entry, entryReceipts, deductionByIncome.getOrDefault(entry.getId(), List.of()),
                    entryActuals, allocationByIncome.getOrDefault(entry.getId(), List.of()), todayForIncome(entry));
        }).toList();
    }

    @Transactional
    public IncomeResponse createIncome(UUID budgetId, UUID actorId, IncomeRequest request) {
        BudgetMonth budget = requireEditableBudget(budgetId, actorId);
        if (income.existsById(request.id())) throw ApiException.conflict("An income entry with this identifier already exists");
        references.requireUsableIncomeType(request.incomeTypeId(), budget.getSpaceId());
        validateIncome(request.expectedAmount(), request.expectedDate(), request.timeZone(), request.titheAmountOverride());
        IncomeEntry entry = new IncomeEntry(); entry.setId(request.id()); entry.setSpaceId(budget.getSpaceId()); entry.setBudgetMonthId(budgetId);
        entry.setCreatedByUserId(actorId); entry.setUpdatedByUserId(actorId);
        apply(entry, request.sourceName(), request.incomeTypeId(), request.expectedAmount(), request.expectedDate(), request.expectedTime(),
                request.timeZone(), request.titheEnabled(), request.titheRate(), request.titheAmountOverride(), request.recurring(),
                request.recurrenceRule(), request.notes(), request.sortOrder());
        entry = income.saveAndFlush(entry);
        DeductionMutation legacy = synchronizeLegacyTithe(entry, actorId);
        IncomeResponse response = mapIncome(entry);
        record(entry.getSpaceId(), "INCOME_ENTRY", entry.getId(), SyncOperationType.CREATE, entry.getVersion(), actorId, false, response);
        recordDeductionMutation(legacy, actorId);
        return response;
    }

    @Transactional
    public IncomeResponse updateIncome(UUID id, UUID actorId, IncomeUpdateRequest request) {
        IncomeEntry entry = requireIncomeForUpdate(id); BudgetMonth budget = requireEditableBudget(entry.getBudgetMonthId(), actorId);
        requireVersion(entry.getVersion(), request.version()); references.requireUsableIncomeType(request.incomeTypeId(), entry.getSpaceId());
        validateIncome(request.expectedAmount(), request.expectedDate(), request.timeZone(), request.titheAmountOverride());
        apply(entry, request.sourceName(), request.incomeTypeId(), request.expectedAmount(), request.expectedDate(), request.expectedTime(),
                request.timeZone(), request.titheEnabled(), request.titheRate(), request.titheAmountOverride(), request.recurring(),
                request.recurrenceRule(), request.notes(), request.sortOrder());
        DeductionMutation legacy = synchronizeLegacyTithe(entry, actorId);
        validatePlannedDeductions(entry,
                deductions.findAllByIncomeEntryIdAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(id));
        // A planned legacy Tithe edit is prospective. Receipt-level actual lines are an
        // already-reviewed ledger and must only change through an explicit receipt edit.
        assertConfirmedCoverage(entry, receipts.findAllByIncomeEntryIdAndDeletedAtIsNullOrderByReceivedAtAscCreatedAtAsc(id));
        entry.setUpdatedByUserId(actorId); entry = income.saveAndFlush(entry); IncomeResponse response = mapIncome(entry);
        record(entry.getSpaceId(), "INCOME_ENTRY", id, SyncOperationType.UPDATE, entry.getVersion(), actorId, false, response);
        recordDeductionMutation(legacy, actorId);
        return response;
    }

    @Transactional
    public IncomeResponse cancelIncome(UUID id, UUID actorId, IncomeStateRequest request) {
        IncomeEntry entry = requireIncomeForUpdate(id); requireEditableBudget(entry.getBudgetMonthId(), actorId); requireVersion(entry.getVersion(), request.version());
        if (entry.getCancelledAt() == null) { entry.setCancelledAt(Instant.now(clock)); entry.setUpdatedByUserId(actorId); entry = income.saveAndFlush(entry); }
        IncomeResponse response = mapIncome(entry); record(entry.getSpaceId(), "INCOME_ENTRY", id, SyncOperationType.UPDATE, entry.getVersion(), actorId, false, response);
        audit.record(actorId, entry.getSpaceId(), "INCOME_CANCELLED", "INCOME_ENTRY", id, AuditResult.SUCCESS, "Expected income cancelled");
        return response;
    }

    @Transactional
    public IncomeResponse restoreIncome(UUID id, UUID actorId, IncomeStateRequest request) {
        IncomeEntry entry = requireIncomeForUpdate(id); requireEditableBudget(entry.getBudgetMonthId(), actorId); requireVersion(entry.getVersion(), request.version());
        if (entry.getCancelledAt() != null) { entry.setCancelledAt(null); entry.setUpdatedByUserId(actorId); entry = income.saveAndFlush(entry); }
        IncomeResponse response = mapIncome(entry); record(entry.getSpaceId(), "INCOME_ENTRY", id, SyncOperationType.UPDATE, entry.getVersion(), actorId, false, response);
        audit.record(actorId, entry.getSpaceId(), "INCOME_RESTORED", "INCOME_ENTRY", id, AuditResult.SUCCESS, "Expected income restored");
        return response;
    }

    @Transactional
    public void deleteIncome(UUID id, UUID actorId, VersionRequest request) {
        IncomeEntry entry = requireIncomeForUpdate(id); requireEditableBudget(entry.getBudgetMonthId(), actorId);
        requireVersion(entry.getVersion(), request.version());
        if (!receipts.findAllByIncomeEntryIdOrderByReceivedAtAscCreatedAtAsc(id).isEmpty() ||
                !allocations.findAllByIncomeEntryIdOrderByCreatedAtAsc(id).isEmpty()) {
            throw ApiException.conflict("Income with receipt or funding history cannot be deleted; cancel it instead");
        }
        Instant deletedAt = Instant.now(clock);
        List<IncomeDeduction> linkedDeductions = deductions.findAllForUpdateByIncomeEntryId(id);
        for (IncomeDeduction deduction : linkedDeductions) {
            deduction.setDeletedAt(deletedAt); deduction.setUpdatedByUserId(actorId);
            deduction = deductions.saveAndFlush(deduction);
            recordDeduction(deduction, SyncOperationType.DELETE, actorId, true);
        }
        entry.setDeletedAt(deletedAt); entry.setUpdatedByUserId(actorId); entry = income.saveAndFlush(entry);
        record(entry.getSpaceId(), "INCOME_ENTRY", id, SyncOperationType.DELETE, entry.getVersion(), actorId, true, null);
    }

    @Transactional
    public List<IncomeDeductionResponse> incomeDeductions(UUID incomeId, UUID actorId) {
        IncomeEntry entry = requireIncome(incomeId); access.require(entry.getSpaceId(), actorId, Capability.VIEW);
        return deductions.findAllByIncomeEntryIdAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(incomeId)
                .stream().map(value -> calculations.deduction(value, entry.getExpectedAmount())).toList();
    }

    @Transactional
    public List<IncomeDeductionResponse> incomeDeductionHistory(UUID incomeId, UUID actorId) {
        IncomeEntry entry = requireIncome(incomeId); access.require(entry.getSpaceId(), actorId, Capability.VIEW);
        return deductions.findAllByIncomeEntryIdOrderBySortOrderAscCreatedAtAsc(incomeId)
                .stream().map(value -> calculations.deduction(value, entry.getExpectedAmount())).toList();
    }

    @Transactional
    public List<IncomeDeductionResponse> incomeDeductionHistoryForSpace(UUID spaceId, UUID actorId) {
        access.require(spaceId, actorId, Capability.VIEW);
        List<IncomeDeduction> values = deductions.findAllBySpaceIdOrderByCreatedAtAsc(spaceId);
        Map<UUID, IncomeEntry> entries = income.findAllById(values
                .stream().map(IncomeDeduction::getIncomeEntryId).distinct().toList()).stream()
                .collect(Collectors.toMap(IncomeEntry::getId, value -> value));
        return values.stream()
                .map(value -> calculations.deduction(value,
                        entries.get(value.getIncomeEntryId()).getExpectedAmount())).toList();
    }

    @Transactional
    public IncomeDeductionResponse createIncomeDeduction(UUID incomeId, UUID actorId,
                                                         IncomeDeductionRequest request) {
        IncomeEntry entry = requireIncomeForUpdate(incomeId); requireEditableBudget(entry.getBudgetMonthId(), actorId);
        if (!incomeId.equals(request.incomeEntryId())) {
            throw ApiException.badRequest("Deduction incomeEntryId does not match the path");
        }
        if (incomeId.equals(request.id())) {
            throw ApiException.badRequest("This identifier is reserved for legacy Tithe compatibility");
        }
        if (deductions.existsById(request.id())) {
            throw ApiException.conflict("An income deduction with this identifier already exists");
        }
        IncomeDeduction deduction = new IncomeDeduction(); deduction.setId(request.id());
        deduction.setSpaceId(entry.getSpaceId()); deduction.setIncomeEntryId(incomeId);
        deduction.setCreatedByUserId(actorId); deduction.setUpdatedByUserId(actorId);
        apply(deduction, request.name(), request.deductionType(), request.percentageRate(),
                request.fixedAmount(), request.notes(), request.sortOrder());
        List<IncomeDeduction> prospective = new java.util.ArrayList<>(deductions
                .findAllByIncomeEntryIdAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(incomeId));
        prospective.add(deduction); validatePlannedDeductions(entry, prospective);
        deduction = deductions.saveAndFlush(deduction);
        IncomeDeductionResponse response = calculations.deduction(deduction, entry.getExpectedAmount());
        recordDeduction(deduction, SyncOperationType.CREATE, actorId, false);
        auditDeduction(actorId, deduction, "INCOME_DEDUCTION_CREATED", "Planned deduction created");
        return response;
    }

    @Transactional
    public IncomeDeductionResponse updateIncomeDeduction(UUID id, UUID actorId,
                                                         IncomeDeductionUpdateRequest request) {
        UUID incomeId = deductions.findIncomeEntryIdById(id).orElseThrow(() -> ApiException.notFound("Income deduction"));
        IncomeEntry entry = requireIncomeForUpdate(incomeId); requireEditableBudget(entry.getBudgetMonthId(), actorId);
        List<IncomeDeduction> locked = deductions.findAllForUpdateByIncomeEntryId(incomeId);
        IncomeDeduction deduction = locked.stream().filter(value -> value.getId().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Income deduction"));
        requireVersion(deduction.getVersion(), request.version());
        apply(deduction, request.name(), request.deductionType(), request.percentageRate(),
                request.fixedAmount(), request.notes(), request.sortOrder());
        validatePlannedDeductions(entry, locked);
        deduction.setUpdatedByUserId(actorId); deduction = deductions.saveAndFlush(deduction);
        if (deduction.isLegacyTithe()) {
            mirrorLegacyTithe(entry, deduction, actorId);
            assertConfirmedCoverage(entry, receipts
                    .findAllByIncomeEntryIdAndDeletedAtIsNullOrderByReceivedAtAscCreatedAtAsc(entry.getId()));
        }
        IncomeDeductionResponse response = calculations.deduction(deduction, entry.getExpectedAmount());
        recordDeduction(deduction, SyncOperationType.UPDATE, actorId, false);
        auditDeduction(actorId, deduction, "INCOME_DEDUCTION_UPDATED", "Planned deduction updated");
        return response;
    }

    @Transactional
    public void deleteIncomeDeduction(UUID id, UUID actorId, IncomeStateRequest request) {
        UUID incomeId = deductions.findIncomeEntryIdById(id).orElseThrow(() -> ApiException.notFound("Income deduction"));
        IncomeEntry entry = requireIncomeForUpdate(incomeId); requireEditableBudget(entry.getBudgetMonthId(), actorId);
        IncomeDeduction deduction = deductions.findForUpdateById(id)
                .orElseThrow(() -> ApiException.notFound("Income deduction"));
        requireVersion(deduction.getVersion(), request.version());
        deduction.setDeletedAt(Instant.now(clock)); deduction.setUpdatedByUserId(actorId);
        deduction = deductions.saveAndFlush(deduction);
        if (deduction.isLegacyTithe()) {
            entry.setTitheEnabled(false); entry.setTitheAmountOverride(null); entry.setUpdatedByUserId(actorId);
            entry = income.saveAndFlush(entry);
            record(entry.getSpaceId(), "INCOME_ENTRY", entry.getId(), SyncOperationType.UPDATE,
                    entry.getVersion(), actorId, false, mapIncome(entry));
        }
        recordDeduction(deduction, SyncOperationType.DELETE, actorId, true);
        auditDeduction(actorId, deduction, "INCOME_DEDUCTION_DELETED", "Planned deduction removed");
    }

    @Transactional
    public List<IncomeReceiptResponse> receipts(UUID incomeId, UUID actorId) {
        IncomeEntry entry = requireIncome(incomeId); access.require(entry.getSpaceId(), actorId, Capability.VIEW);
        List<IncomeReceipt> values = receipts
                .findAllByIncomeEntryIdAndDeletedAtIsNullOrderByReceivedAtAscCreatedAtAsc(incomeId);
        return mapReceipts(values, false);
    }

    @Transactional
    public List<IncomeReceiptResponse> receiptHistory(UUID incomeId, UUID actorId) {
        IncomeEntry entry = requireIncome(incomeId); access.require(entry.getSpaceId(), actorId, Capability.VIEW);
        return mapReceipts(receipts.findAllByIncomeEntryIdOrderByReceivedAtAscCreatedAtAsc(incomeId), true);
    }

    @Transactional
    public List<IncomeReceiptResponse> receiptHistoryForSpace(UUID spaceId, UUID actorId) {
        access.require(spaceId, actorId, Capability.VIEW);
        return mapReceipts(receipts.findAllBySpaceIdOrderByReceivedAtAscCreatedAtAsc(spaceId), true);
    }

    @Transactional
    public IncomeReceiptResponse createReceipt(UUID incomeId, UUID actorId, IncomeReceiptRequest request) {
        IncomeEntry entry = requireIncomeForUpdate(incomeId); requireEditableBudget(entry.getBudgetMonthId(), actorId);
        if (!incomeId.equals(request.incomeEntryId())) throw ApiException.badRequest("Receipt incomeEntryId does not match the path");
        if (entry.getCancelledAt() != null) throw ApiException.conflict("Restore cancelled income before recording a receipt");
        // The parent-income write lock is the serialization point. Re-read all active receipts only
        // after acquiring it so two devices cannot both observe a partially received balance.
        if (mapIncome(entry).availability().status() == IncomeStatus.RECEIVED) {
            throw ApiException.conflict("income_already_received",
                    "This income is already fully received; edit an existing receipt or the scheduled amount instead");
        }
        if (receipts.existsById(request.id())) throw ApiException.conflict("An income receipt with this identifier already exists");
        validateReceipt(request.receivedAt(), request.timeZone());
        IncomeReceipt receipt = new IncomeReceipt(); receipt.setId(request.id()); receipt.setSpaceId(entry.getSpaceId()); receipt.setIncomeEntryId(incomeId);
        receipt.setCreatedByUserId(actorId); receipt.setUpdatedByUserId(actorId); receipt.setRecordedByUserId(actorId);
        apply(receipt, request.amount(), request.receivedAt(), request.timeZone(), request.notes());
        receipt = receipts.saveAndFlush(receipt);
        List<DeductionMutation> mutations = request.deductions() == null
                ? rebalanceLegacyReceiptDeductions(entry, actorId)
                : reconcileReceiptDeductions(entry, receipt, request.deductions(), actorId);
        assertReceiptDeductionTotal(receipt,
                receiptDeductions.findAllByIncomeReceiptIdAndDeletedAtIsNullOrderByCreatedAtAsc(receipt.getId()));
        assertConfirmedCoverage(entry,
                receipts.findAllByIncomeEntryIdAndDeletedAtIsNullOrderByReceivedAtAscCreatedAtAsc(entry.getId()));
        IncomeReceiptResponse response = mapReceipt(receipt, false);
        record(receipt.getSpaceId(), "INCOME_RECEIPT", receipt.getId(), SyncOperationType.CREATE, receipt.getVersion(), actorId, false, response);
        recordDeductionMutations(mutations, actorId);
        audit.record(actorId, receipt.getSpaceId(), "INCOME_RECEIPT_CREATED", "INCOME_RECEIPT",
                receipt.getId(), AuditResult.SUCCESS, "gross=" + calculations.money(receipt.getAmount())
                        + "; deductions=" + response.totalDeductions());
        return response;
    }

    @Transactional
    public IncomeReceiptResponse updateReceipt(UUID id, UUID actorId, IncomeReceiptUpdateRequest request) {
        UUID incomeId = receipts.findIncomeEntryIdById(id).orElseThrow(() -> ApiException.notFound("Income receipt"));
        IncomeEntry entry = requireIncomeForUpdate(incomeId); IncomeReceipt receipt = requireReceiptForUpdate(id);
        requireEditableBudget(entry.getBudgetMonthId(), actorId); requireVersion(receipt.getVersion(), request.version()); validateReceipt(request.receivedAt(), request.timeZone());
        receiptDeductions.findAllForUpdateByIncomeReceiptId(id);
        apply(receipt, request.amount(), request.receivedAt(), request.timeZone(), request.notes()); receipt.setUpdatedByUserId(actorId);
        receipt = receipts.saveAndFlush(receipt);
        List<DeductionMutation> mutations = request.deductions() == null
                ? rebalanceLegacyReceiptDeductions(entry, actorId)
                : reconcileReceiptDeductions(entry, receipt, request.deductions(), actorId);
        assertReceiptDeductionTotal(receipt,
                receiptDeductions.findAllByIncomeReceiptIdAndDeletedAtIsNullOrderByCreatedAtAsc(receipt.getId()));
        assertConfirmedCoverage(entry, receipts.findAllByIncomeEntryIdAndDeletedAtIsNullOrderByReceivedAtAscCreatedAtAsc(entry.getId()));
        IncomeReceiptResponse response = mapReceipt(receipt, false);
        record(receipt.getSpaceId(), "INCOME_RECEIPT", id, SyncOperationType.UPDATE, receipt.getVersion(), actorId, false, response);
        recordDeductionMutations(mutations, actorId);
        audit.record(actorId, receipt.getSpaceId(), "INCOME_RECEIPT_UPDATED", "INCOME_RECEIPT",
                receipt.getId(), AuditResult.SUCCESS, "gross=" + calculations.money(receipt.getAmount())
                        + "; deductions=" + response.totalDeductions());
        return response;
    }

    @Transactional
    public void deleteReceipt(UUID id, UUID actorId, IncomeStateRequest request) {
        UUID incomeId = receipts.findIncomeEntryIdById(id).orElseThrow(() -> ApiException.notFound("Income receipt"));
        IncomeEntry entry = requireIncomeForUpdate(incomeId); IncomeReceipt receipt = requireReceiptForUpdate(id);
        requireEditableBudget(entry.getBudgetMonthId(), actorId); requireVersion(receipt.getVersion(), request.version());
        List<IncomeReceiptDeduction> lines = receiptDeductions.findAllForUpdateByIncomeReceiptId(id);
        Instant reversedAt = Instant.now(clock);
        List<DeductionMutation> mutations = new java.util.ArrayList<>();
        for (IncomeReceiptDeduction line : lines) {
            line.setDeletedAt(reversedAt); line.setUpdatedByUserId(actorId);
            line = receiptDeductions.saveAndFlush(line);
            mutations.add(new DeductionMutation(line, SyncOperationType.DELETE));
        }
        List<IncomeReceipt> remaining = receipts.findAllByIncomeEntryIdAndDeletedAtIsNullOrderByReceivedAtAscCreatedAtAsc(entry.getId())
                .stream().filter(value -> !value.getId().equals(id)).toList();
        receipt.setDeletedAt(reversedAt); receipt.setUpdatedByUserId(actorId); receipt = receipts.saveAndFlush(receipt);
        assertConfirmedCoverage(entry, remaining);
        IncomeReceiptResponse tombstone = mapReceipt(receipt, true);
        record(receipt.getSpaceId(), "INCOME_RECEIPT", id, SyncOperationType.DELETE, receipt.getVersion(), actorId, true, tombstone);
        recordDeductionMutations(mutations, actorId);
        audit.record(actorId, receipt.getSpaceId(), "INCOME_RECEIPT_REVERSED", "INCOME_RECEIPT",
                receipt.getId(), AuditResult.SUCCESS, "gross=" + calculations.money(receipt.getAmount())
                        + "; deductions=" + tombstone.totalDeductions());
    }

    @Transactional
    public List<IncomeReceiptDeductionResponse> receiptDeductions(UUID receiptId, UUID actorId) {
        IncomeReceipt receipt = receipts.findByIdAndDeletedAtIsNull(receiptId)
                .orElseThrow(() -> ApiException.notFound("Income receipt"));
        access.require(receipt.getSpaceId(), actorId, Capability.VIEW);
        return receiptDeductions.findAllByIncomeReceiptIdAndDeletedAtIsNullOrderByCreatedAtAsc(receiptId)
                .stream().map(calculations::receiptDeduction).toList();
    }

    @Transactional
    public List<IncomeReceiptDeductionResponse> receiptDeductionHistory(UUID receiptId, UUID actorId) {
        IncomeReceipt receipt = receipts.findById(receiptId).orElseThrow(() -> ApiException.notFound("Income receipt"));
        access.require(receipt.getSpaceId(), actorId, Capability.VIEW);
        return receiptDeductions.findAllByIncomeReceiptIdOrderByCreatedAtAsc(receiptId)
                .stream().map(calculations::receiptDeduction).toList();
    }

    @Transactional
    public List<IncomeReceiptDeductionResponse> receiptDeductionHistoryForSpace(UUID spaceId, UUID actorId) {
        access.require(spaceId, actorId, Capability.VIEW);
        return receiptDeductions.findAllBySpaceIdOrderByCreatedAtAsc(spaceId)
                .stream().map(calculations::receiptDeduction).toList();
    }

    @Transactional
    public IncomeReceiptDeductionResponse createReceiptDeduction(UUID receiptId, UUID actorId,
                                                                 IncomeReceiptDeductionRequest request) {
        if (!receiptId.equals(request.incomeReceiptId())) {
            throw ApiException.badRequest("Receipt deduction incomeReceiptId does not match the path");
        }
        UUID incomeId = receipts.findIncomeEntryIdById(receiptId)
                .orElseThrow(() -> ApiException.notFound("Income receipt"));
        IncomeEntry entry = requireIncomeForUpdate(incomeId); requireEditableBudget(entry.getBudgetMonthId(), actorId);
        IncomeReceipt receipt = requireReceiptForUpdate(receiptId);
        receiptDeductions.findAllForUpdateByIncomeReceiptId(receiptId);
        if (receiptDeductions.existsById(request.id())) {
            throw ApiException.conflict("A receipt deduction with this identifier already exists");
        }
        IncomeDeduction planned = requireDeductionForIncome(request.incomeDeductionId(), incomeId);
        if (request.id().equals(receiptId) && !planned.isLegacyTithe()) {
            throw ApiException.conflict("The receipt identifier is reserved for legacy Tithe compatibility");
        }
        IncomeReceiptDeduction line = new IncomeReceiptDeduction(); line.setId(request.id());
        line.setSpaceId(entry.getSpaceId()); line.setIncomeReceiptId(receiptId);
        line.setIncomeDeductionId(planned.getId()); line.setNameSnapshot(planned.getName());
        line.setActualAmount(calculations.money(request.amount())); line.setCreatedByUserId(actorId);
        line.setUpdatedByUserId(actorId);
        List<IncomeReceiptDeduction> prospective = new java.util.ArrayList<>(receiptDeductions
                .findAllByIncomeReceiptIdAndDeletedAtIsNullOrderByCreatedAtAsc(receiptId));
        prospective.add(line); assertReceiptDeductionTotal(receipt, prospective);
        line = receiptDeductions.saveAndFlush(line);
        assertConfirmedCoverage(entry, receipts
                .findAllByIncomeEntryIdAndDeletedAtIsNullOrderByReceivedAtAscCreatedAtAsc(incomeId));
        recordReceiptDeduction(line, SyncOperationType.CREATE, actorId, false);
        auditReceiptDeduction(actorId, line, "INCOME_RECEIPT_DEDUCTION_CREATED");
        return calculations.receiptDeduction(line);
    }

    @Transactional
    public IncomeReceiptDeductionResponse updateReceiptDeduction(UUID id, UUID actorId,
                                                                 IncomeReceiptDeductionUpdateRequest request) {
        UUID receiptId = receiptDeductions.findIncomeReceiptIdById(id)
                .orElseThrow(() -> ApiException.notFound("Receipt deduction"));
        UUID incomeId = receipts.findIncomeEntryIdById(receiptId)
                .orElseThrow(() -> ApiException.notFound("Income receipt"));
        IncomeEntry entry = requireIncomeForUpdate(incomeId); requireEditableBudget(entry.getBudgetMonthId(), actorId);
        IncomeReceipt receipt = requireReceiptForUpdate(receiptId);
        List<IncomeReceiptDeduction> locked = receiptDeductions.findAllForUpdateByIncomeReceiptId(receiptId);
        IncomeReceiptDeduction line = locked.stream().filter(value -> value.getId().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Receipt deduction"));
        requireVersion(line.getVersion(), request.version()); line.setActualAmount(calculations.money(request.amount()));
        line.setUpdatedByUserId(actorId); assertReceiptDeductionTotal(receipt, locked);
        line = receiptDeductions.saveAndFlush(line);
        assertConfirmedCoverage(entry, receipts
                .findAllByIncomeEntryIdAndDeletedAtIsNullOrderByReceivedAtAscCreatedAtAsc(incomeId));
        recordReceiptDeduction(line, SyncOperationType.UPDATE, actorId, false);
        auditReceiptDeduction(actorId, line, "INCOME_RECEIPT_DEDUCTION_UPDATED");
        return calculations.receiptDeduction(line);
    }

    @Transactional
    public void deleteReceiptDeduction(UUID id, UUID actorId, IncomeStateRequest request) {
        UUID receiptId = receiptDeductions.findIncomeReceiptIdById(id)
                .orElseThrow(() -> ApiException.notFound("Receipt deduction"));
        UUID incomeId = receipts.findIncomeEntryIdById(receiptId)
                .orElseThrow(() -> ApiException.notFound("Income receipt"));
        IncomeEntry entry = requireIncomeForUpdate(incomeId); requireEditableBudget(entry.getBudgetMonthId(), actorId);
        requireReceiptForUpdate(receiptId);
        IncomeReceiptDeduction line = receiptDeductions.findForUpdateById(id)
                .orElseThrow(() -> ApiException.notFound("Receipt deduction"));
        requireVersion(line.getVersion(), request.version()); line.setDeletedAt(Instant.now(clock));
        line.setUpdatedByUserId(actorId); line = receiptDeductions.saveAndFlush(line);
        recordReceiptDeduction(line, SyncOperationType.DELETE, actorId, true);
        auditReceiptDeduction(actorId, line, "INCOME_RECEIPT_DEDUCTION_DELETED");
    }

    @Transactional
    public IncomeAvailability incomeAvailability(UUID incomeId, UUID actorId) {
        IncomeEntry entry = requireIncome(incomeId); access.require(entry.getSpaceId(), actorId, Capability.VIEW);
        return mapIncome(entry).availability();
    }

    @Transactional
    public List<BudgetItemResponse> items(UUID budgetId, UUID actorId) {
        BudgetMonth budget = requireBudget(budgetId); access.require(budget.getSpaceId(), actorId, Capability.VIEW);
        return items.findAllByBudgetMonthIdAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(budgetId).stream().map(this::mapItem).toList();
    }

    @Transactional
    public BudgetItemResponse getItem(UUID id, UUID actorId) {
        BudgetItem item = requireItem(id); access.require(item.getSpaceId(), actorId, Capability.VIEW); return mapItem(item);
    }

    @Transactional
    public BudgetItemResponse createItem(UUID budgetId, UUID actorId, BudgetItemRequest request) {
        BudgetMonth budget = requireEditableBudgetForUpdate(budgetId, actorId);
        if (items.existsById(request.id())) throw ApiException.conflict("A budget item with this identifier already exists");
        references.requireUsableCategoryForReference(request.categoryId(), budget.getSpaceId());
        validateItem(request.itemType(), request.plannedAmount());
        BudgetItem item = new BudgetItem(); item.setId(request.id()); item.setSpaceId(budget.getSpaceId()); item.setBudgetMonthId(budgetId);
        item.setCreatedByUserId(actorId); item.setUpdatedByUserId(actorId);
        apply(item, request.name(), request.categoryId(), request.plannedAmount(), request.tracked(), request.itemType(), request.recurring(), request.rolloverEnabled(), request.notes(), request.sortOrder());
        item = items.saveAndFlush(item); BudgetItemResponse response = mapItem(item);
        record(item.getSpaceId(), "BUDGET_ITEM", item.getId(), SyncOperationType.CREATE, item.getVersion(), actorId, false, response); return response;
    }

    @Transactional
    public BudgetItemResponse updateItem(UUID id, UUID actorId, BudgetItemUpdateRequest request) {
        BudgetItem item = requireEditableItemForUpdate(id, actorId); requireVersion(item.getVersion(), request.version());
        references.requireUsableCategoryForReference(request.categoryId(), item.getSpaceId());
        validateItem(request.itemType(), request.plannedAmount());
        apply(item, request.name(), request.categoryId(), request.plannedAmount(), request.tracked(), request.itemType(), request.recurring(), request.rolloverEnabled(), request.notes(), request.sortOrder());
        item.setUpdatedByUserId(actorId); item = items.saveAndFlush(item); BudgetItemResponse response = mapItem(item);
        record(item.getSpaceId(), "BUDGET_ITEM", id, SyncOperationType.UPDATE, item.getVersion(), actorId, false, response); return response;
    }

    @Transactional
    public void deleteItem(UUID id, UUID actorId, VersionRequest request) {
        BudgetItem item = requireEditableItemForUpdate(id, actorId);
        requireVersion(item.getVersion(), request.version());
        if (spending.existsByBudgetItemId(id)) {
            throw ApiException.conflict("A budget item with spending history cannot be deleted");
        }
        if (allocations.existsByBudgetItemId(id)) {
            throw ApiException.conflict("A budget item with funding history cannot be deleted");
        }
        item.setDeletedAt(Instant.now(clock)); item.setUpdatedByUserId(actorId); item = items.saveAndFlush(item);
        record(item.getSpaceId(), "BUDGET_ITEM", id, SyncOperationType.DELETE, item.getVersion(), actorId, true, null);
    }

    @Transactional
    public List<FundingAllocationResponse> fundingAllocations(UUID itemId, UUID actorId) {
        BudgetItem item = requireItem(itemId); access.require(item.getSpaceId(), actorId, Capability.VIEW);
        // Reversed rows remain visible as immutable history; calculations explicitly query active rows.
        return allocations.findAllByBudgetItemIdOrderByCreatedAtAsc(itemId).stream().map(calculations::allocation).toList();
    }

    @Transactional
    public List<FundingAllocationResponse> incomeFundingAllocations(UUID incomeId, UUID actorId) {
        IncomeEntry entry = requireIncome(incomeId); access.require(entry.getSpaceId(), actorId, Capability.VIEW);
        return allocations.findAllByIncomeEntryIdOrderByCreatedAtAsc(incomeId).stream().map(calculations::allocation).toList();
    }

    @Transactional
    public List<FundingAllocationResponse> fundingAllocationHistory(UUID spaceId, UUID actorId) {
        access.require(spaceId, actorId, Capability.VIEW);
        return allocations.findAllBySpaceIdOrderByCreatedAtAsc(spaceId).stream().map(calculations::allocation).toList();
    }

    @Transactional
    public FundingAllocationResponse createFundingAllocation(UUID itemId, UUID actorId, FundingAllocationRequest request) {
        if (!itemId.equals(request.budgetItemId())) throw ApiException.badRequest("Funding allocation budgetItemId does not match the path");
        BudgetItem item = requireEditableItemForUpdate(itemId, actorId);
        if (allocations.existsById(request.id())) throw ApiException.conflict("A funding allocation with this identifier already exists");
        if (request.sourceType() == FundingSourceType.INCOME_ENTRY && request.incomeEntryId() != null) {
            requireIncomeForUpdate(request.incomeEntryId());
        }
        validateFunding(item, null, request.incomeEntryId(), request.sourceType(), request.plannedAmount(), request.confirmedAllocatedAmount(), request.allocatedAt(), request.timeZone());
        BudgetFundingAllocation allocation = new BudgetFundingAllocation(); allocation.setId(request.id()); allocation.setSpaceId(item.getSpaceId()); allocation.setBudgetItemId(itemId);
        allocation.setCreatedByUserId(actorId); allocation.setUpdatedByUserId(actorId);
        apply(allocation, request.incomeEntryId(), request.sourceType(), request.plannedAmount(), request.confirmedAllocatedAmount(), request.allocatedAt(), request.timeZone(), request.notes());
        allocation = allocations.saveAndFlush(allocation); FundingAllocationResponse response = calculations.allocation(allocation);
        record(allocation.getSpaceId(), "FUNDING_ALLOCATION", allocation.getId(), SyncOperationType.CREATE, allocation.getVersion(), actorId, false, response);
        auditFundingCreate(actorId, allocation); return response;
    }

    @Transactional
    public FundingAllocationResponse updateFundingAllocation(UUID id, UUID actorId, FundingAllocationUpdateRequest request) {
        var scope = allocations.findLockScopeById(id).orElseThrow(() -> ApiException.notFound("Funding allocation"));
        BudgetItem item = requireEditableItemForUpdate(scope.getBudgetItemId(), actorId);
        lockIncomeSources(scope.getIncomeEntryId(), request.sourceType() == FundingSourceType.INCOME_ENTRY ? request.incomeEntryId() : null);
        BudgetFundingAllocation allocation = requireAllocationForUpdate(id); requireVersion(allocation.getVersion(), request.version());
        BigDecimal previousConfirmed = calculations.money(allocation.getConfirmedAllocatedAmount());
        FundingSourceType previousSourceType = allocation.getSourceType();
        UUID previousIncomeId = allocation.getIncomeEntryId();
        validateFunding(item, allocation.getId(), request.incomeEntryId(), request.sourceType(), request.plannedAmount(), request.confirmedAllocatedAmount(), request.allocatedAt(), request.timeZone());
        apply(allocation, request.incomeEntryId(), request.sourceType(), request.plannedAmount(), request.confirmedAllocatedAmount(), request.allocatedAt(), request.timeZone(), request.notes());
        allocation.setUpdatedByUserId(actorId); allocation = allocations.saveAndFlush(allocation); FundingAllocationResponse response = calculations.allocation(allocation);
        record(allocation.getSpaceId(), "FUNDING_ALLOCATION", id, SyncOperationType.UPDATE, allocation.getVersion(), actorId, false, response);
        auditFundingUpdate(actorId, allocation, previousConfirmed, previousSourceType, previousIncomeId); return response;
    }

    @Transactional
    public void reverseFundingAllocation(UUID id, UUID actorId, IncomeStateRequest request) {
        var scope = allocations.findLockScopeById(id).orElseThrow(() -> ApiException.notFound("Funding allocation"));
        BudgetItem item = requireEditableItemForUpdate(scope.getBudgetItemId(), actorId);
        lockIncomeSources(scope.getIncomeEntryId());
        BudgetFundingAllocation allocation = requireAllocationForUpdate(id); requireVersion(allocation.getVersion(), request.version());
        allocation.setDeletedAt(Instant.now(clock)); allocation.setUpdatedByUserId(actorId); allocation = allocations.saveAndFlush(allocation);
        record(allocation.getSpaceId(), "FUNDING_ALLOCATION", id, SyncOperationType.DELETE, allocation.getVersion(), actorId, true,
                calculations.allocation(allocation));
        audit.record(actorId, allocation.getSpaceId(), "FUNDING_ALLOCATION_REVERSED", "FUNDING_ALLOCATION", id,
                AuditResult.SUCCESS, "released=" + calculations.money(allocation.getConfirmedAllocatedAmount())
                        + "; incomeEntryId=" + allocation.getIncomeEntryId()
                        + "; budgetItemId=" + allocation.getBudgetItemId());
    }

    @Transactional
    public FundingSummary fundingSummary(UUID itemId, UUID actorId) {
        BudgetItem item = requireItem(itemId); access.require(item.getSpaceId(), actorId, Capability.VIEW); return mapItem(item).funding();
    }

    @Transactional
    public BudgetFundingSummary budgetFundingSummary(UUID budgetId, UUID actorId) {
        BudgetMonth budget = requireBudget(budgetId); access.require(budget.getSpaceId(), actorId, Capability.VIEW);
        return calculations.budgetFunding(budgetId, items(budgetId, actorId));
    }

    public BudgetMonth requireBudget(UUID id) { return budgets.findByIdAndDeletedAtIsNull(id).orElseThrow(() -> ApiException.notFound("Budget")); }
    public BudgetItem requireItem(UUID id) { return items.findByIdAndDeletedAtIsNull(id).orElseThrow(() -> ApiException.notFound("Budget item")); }

    /**
     * Serializes spending against budget/item lifecycle changes while enforcing the caller's capability.
     */
    public BudgetItem requireOpenItemForUpdate(UUID id, UUID actorId, Capability capability) {
        UUID budgetId = items.findBudgetMonthIdById(id).orElseThrow(() -> ApiException.notFound("Budget item"));
        BudgetMonth budget = requireBudgetForUpdate(budgetId);
        access.require(budget.getSpaceId(), actorId, capability);
        if (budget.getStatus() == BudgetStatus.CLOSED) {
            throw ApiException.conflict("Reopen this budget before changing spending");
        }
        BudgetItem item = requireItemForUpdate(id);
        if (!budgetId.equals(item.getBudgetMonthId()) || !budget.getSpaceId().equals(item.getSpaceId())) {
            throw ApiException.conflict("The budget item changed while acquiring its lifecycle lock");
        }
        return item;
    }

    private BudgetMonth requireBudgetForUpdate(UUID id) {
        return budgets.findForUpdateById(id).orElseThrow(() -> ApiException.notFound("Budget"));
    }

    private BudgetItem requireItemForUpdate(UUID id) {
        return items.findForUpdateById(id).orElseThrow(() -> ApiException.notFound("Budget item"));
    }

    private BudgetItem requireEditableItemForUpdate(UUID id, UUID actorId) {
        return requireOpenItemForUpdate(id, actorId, Capability.EDIT_FINANCE);
    }

    private BudgetMonth requireEditableBudgetForUpdate(UUID id, UUID actorId) {
        BudgetMonth budget = requireBudgetForUpdate(id);
        access.require(budget.getSpaceId(), actorId, Capability.EDIT_FINANCE);
        if (budget.getStatus() == BudgetStatus.CLOSED) {
            throw ApiException.conflict("Reopen this budget before changing it");
        }
        return budget;
    }

    private BudgetMonth requireEditableBudget(UUID id, UUID actorId) {
        BudgetMonth budget = requireBudget(id); access.require(budget.getSpaceId(), actorId, Capability.EDIT_FINANCE);
        if (budget.getStatus() == BudgetStatus.CLOSED) throw ApiException.conflict("Reopen this budget before changing it"); return budget;
    }

    private BudgetResponse changeStatus(UUID id, UUID actorId, BudgetStatus status, Long requestedVersion) {
        BudgetMonth budget = requireBudgetForUpdate(id); access.require(budget.getSpaceId(), actorId, Capability.EDIT_FINANCE);
        requireVersion(budget.getVersion(), requestedVersion);
        budget.setStatus(status); budget.setUpdatedByUserId(actorId); budget = budgets.saveAndFlush(budget); BudgetResponse response = map(budget);
        record(budget.getSpaceId(), "BUDGET_MONTH", id, SyncOperationType.UPDATE, budget.getVersion(), actorId, false, response); return response;
    }

    private BudgetResponse map(BudgetMonth budget) {
        List<IncomeEntry> budgetIncome = income.findAllByBudgetMonthIdAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(budget.getId());
        List<BudgetItem> budgetItems = items.findAllByBudgetMonthIdAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(budget.getId());
        List<SpendingEntry> entries = budgetItems.isEmpty() ? List.of() : spending.findAllByBudgetItemIdInAndDeletedAtIsNull(budgetItems.stream().map(BudgetItem::getId).toList());
        List<IncomeReceipt> receiptValues = budgetIncome.isEmpty() ? List.of() : receipts.findAllByIncomeEntryIdInAndDeletedAtIsNull(budgetIncome.stream().map(IncomeEntry::getId).toList());
        List<IncomeDeduction> deductionValues = budgetIncome.isEmpty() ? List.of()
                : deductions.findAllByIncomeEntryIdInAndDeletedAtIsNull(budgetIncome.stream().map(IncomeEntry::getId).toList());
        List<IncomeReceiptDeduction> actualDeductionValues = receiptValues.isEmpty() ? List.of()
                : receiptDeductions.findAllByIncomeReceiptIdInAndDeletedAtIsNull(
                        receiptValues.stream().map(IncomeReceipt::getId).toList());
        List<BudgetFundingAllocation> allocationValues = budgetItems.isEmpty() ? List.of() : allocations.findAllByBudgetItemIdInAndDeletedAtIsNull(budgetItems.stream().map(BudgetItem::getId).toList());
        return new BudgetResponse(budget.getId(), budget.getSpaceId(), budget.getYear(), budget.getMonth(), budget.getName(), budget.getStatus(), budget.getNotes(), budget.getVersion(), budget.getCreatedAt(),
                calculations.budget(budgetIncome, budgetItems, entries, receiptValues, deductionValues,
                        actualDeductionValues, allocationValues,
                        YearMonth.of(budget.getYear(), budget.getMonth()), todayForSpace(budget.getSpaceId()),
                        this::todayForIncome));
    }

    private List<BudgetResponse> mapAll(List<BudgetMonth> budgetValues) {
        if (budgetValues.isEmpty()) return List.of();
        List<UUID> budgetIds = budgetValues.stream().map(BudgetMonth::getId).toList();
        List<IncomeEntry> incomeValues = income.findAllByBudgetMonthIdInAndDeletedAtIsNull(budgetIds);
        List<BudgetItem> itemValues = items.findAllByBudgetMonthIdInAndDeletedAtIsNull(budgetIds);
        List<UUID> incomeIds = incomeValues.stream().map(IncomeEntry::getId).toList();
        List<UUID> itemIds = itemValues.stream().map(BudgetItem::getId).toList();
        List<SpendingEntry> spendingValues = itemIds.isEmpty() ? List.of()
                : spending.findAllByBudgetItemIdInAndDeletedAtIsNull(itemIds);
        List<IncomeReceipt> receiptValues = incomeIds.isEmpty() ? List.of()
                : receipts.findAllByIncomeEntryIdInAndDeletedAtIsNull(incomeIds);
        List<IncomeDeduction> deductionValues = incomeIds.isEmpty() ? List.of()
                : deductions.findAllByIncomeEntryIdInAndDeletedAtIsNull(incomeIds);
        List<UUID> receiptIds = receiptValues.stream().map(IncomeReceipt::getId).toList();
        List<IncomeReceiptDeduction> actualDeductionValues = receiptIds.isEmpty() ? List.of()
                : receiptDeductions.findAllByIncomeReceiptIdInAndDeletedAtIsNull(receiptIds);
        List<BudgetFundingAllocation> allocationValues = itemIds.isEmpty() ? List.of()
                : allocations.findAllByBudgetItemIdInAndDeletedAtIsNull(itemIds);

        Map<UUID, List<IncomeEntry>> incomeByBudget = incomeValues.stream()
                .collect(Collectors.groupingBy(IncomeEntry::getBudgetMonthId));
        Map<UUID, List<BudgetItem>> itemsByBudget = itemValues.stream()
                .collect(Collectors.groupingBy(BudgetItem::getBudgetMonthId));
        Map<UUID, List<SpendingEntry>> spendingByItem = spendingValues.stream()
                .collect(Collectors.groupingBy(SpendingEntry::getBudgetItemId));
        Map<UUID, List<IncomeReceipt>> receiptsByIncome = receiptValues.stream()
                .collect(Collectors.groupingBy(IncomeReceipt::getIncomeEntryId));
        Map<UUID, List<IncomeDeduction>> deductionsByIncome = deductionValues.stream()
                .collect(Collectors.groupingBy(IncomeDeduction::getIncomeEntryId));
        Map<UUID, List<IncomeReceiptDeduction>> actualsByReceipt = actualDeductionValues.stream()
                .collect(Collectors.groupingBy(IncomeReceiptDeduction::getIncomeReceiptId));
        Map<UUID, List<BudgetFundingAllocation>> allocationsByItem = allocationValues.stream()
                .collect(Collectors.groupingBy(BudgetFundingAllocation::getBudgetItemId));
        Map<UUID, LocalDate> todayBySpace = new HashMap<>();

        return budgetValues.stream().map(budget -> {
            List<IncomeEntry> budgetIncome = incomeByBudget.getOrDefault(budget.getId(), List.of());
            List<BudgetItem> budgetItems = itemsByBudget.getOrDefault(budget.getId(), List.of());
            List<SpendingEntry> entries = budgetItems.stream()
                    .flatMap(item -> spendingByItem.getOrDefault(item.getId(), List.of()).stream()).toList();
            List<IncomeReceipt> budgetReceipts = budgetIncome.stream()
                    .flatMap(entry -> receiptsByIncome.getOrDefault(entry.getId(), List.of()).stream()).toList();
            List<IncomeDeduction> budgetDeductions = budgetIncome.stream()
                    .flatMap(entry -> deductionsByIncome.getOrDefault(entry.getId(), List.of()).stream()).toList();
            List<IncomeReceiptDeduction> budgetActuals = budgetReceipts.stream()
                    .flatMap(receipt -> actualsByReceipt.getOrDefault(receipt.getId(), List.of()).stream()).toList();
            List<BudgetFundingAllocation> budgetAllocations = budgetItems.stream()
                    .flatMap(item -> allocationsByItem.getOrDefault(item.getId(), List.of()).stream()).toList();
            LocalDate budgetToday = todayBySpace.computeIfAbsent(budget.getSpaceId(), this::todayForSpace);
            return new BudgetResponse(budget.getId(), budget.getSpaceId(), budget.getYear(), budget.getMonth(),
                    budget.getName(), budget.getStatus(), budget.getNotes(), budget.getVersion(), budget.getCreatedAt(),
                    calculations.budget(budgetIncome, budgetItems, entries, budgetReceipts, budgetDeductions,
                            budgetActuals, budgetAllocations, YearMonth.of(budget.getYear(), budget.getMonth()),
                            budgetToday, this::todayForIncome));
        }).toList();
    }

    private BudgetItemResponse mapItem(BudgetItem item) {
        return calculations.item(item, spending.findAllByBudgetItemIdAndDeletedAtIsNullOrderBySpentAtDesc(item.getId()),
                allocations.findAllByBudgetItemIdAndDeletedAtIsNullOrderByCreatedAtAsc(item.getId()));
    }

    private List<IncomeReceiptResponse> mapReceipts(List<IncomeReceipt> values, boolean includeDeletedDeductions) {
        if (values.isEmpty()) return List.of();
        List<UUID> ids = values.stream().map(IncomeReceipt::getId).toList();
        List<IncomeReceiptDeduction> lines = includeDeletedDeductions
                ? receiptDeductions.findAllByIncomeReceiptIdIn(ids)
                : receiptDeductions.findAllByIncomeReceiptIdInAndDeletedAtIsNull(ids);
        Map<UUID, List<IncomeReceiptDeduction>> byReceipt = lines.stream()
                .collect(Collectors.groupingBy(IncomeReceiptDeduction::getIncomeReceiptId));
        return values.stream().map(receipt -> calculations.receipt(receipt,
                byReceipt.getOrDefault(receipt.getId(), List.of()))).toList();
    }

    private IncomeReceiptResponse mapReceipt(IncomeReceipt receipt, boolean includeDeletedDeductions) {
        List<IncomeReceiptDeduction> lines = includeDeletedDeductions
                ? receiptDeductions.findAllByIncomeReceiptIdOrderByCreatedAtAsc(receipt.getId())
                : receiptDeductions.findAllByIncomeReceiptIdAndDeletedAtIsNullOrderByCreatedAtAsc(receipt.getId());
        return calculations.receipt(receipt, lines);
    }

    private IncomeResponse mapIncome(IncomeEntry entry) {
        List<IncomeReceipt> entryReceipts = receipts
                .findAllByIncomeEntryIdAndDeletedAtIsNullOrderByReceivedAtAscCreatedAtAsc(entry.getId());
        List<IncomeReceiptDeduction> actuals = entryReceipts.isEmpty() ? List.of()
                : receiptDeductions.findAllByIncomeReceiptIdInAndDeletedAtIsNull(
                        entryReceipts.stream().map(IncomeReceipt::getId).toList());
        return calculations.income(entry, entryReceipts,
                deductions.findAllByIncomeEntryIdAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(entry.getId()),
                actuals, allocations.findAllByIncomeEntryIdAndDeletedAtIsNullOrderByCreatedAtAsc(entry.getId()),
                todayForIncome(entry));
    }

    private DeductionMutation synchronizeLegacyTithe(IncomeEntry entry, UUID actorId) {
        IncomeDeduction existing = deductions.findById(entry.getId()).orElse(null);
        if (!entry.isTitheEnabled()) {
            if (existing == null || existing.getDeletedAt() != null || !existing.isLegacyTithe()) return null;
            existing.setDeletedAt(Instant.now(clock)); existing.setUpdatedByUserId(actorId);
            return new DeductionMutation(deductions.saveAndFlush(existing), SyncOperationType.DELETE);
        }
        if (existing != null && !existing.isLegacyTithe()) {
            throw ApiException.conflict("The income identifier is reserved for legacy Tithe compatibility");
        }
        boolean created = existing == null;
        IncomeDeduction legacy = created ? new IncomeDeduction() : existing;
        if (created) {
            legacy.setId(entry.getId()); legacy.setSpaceId(entry.getSpaceId()); legacy.setIncomeEntryId(entry.getId());
            legacy.setCreatedByUserId(actorId); legacy.setLegacyTithe(true); legacy.setSortOrder(0);
            legacy.setNotes("Legacy Tithe compatibility");
        }
        legacy.setDeletedAt(null); legacy.setUpdatedByUserId(actorId); legacy.setName("Tithe");
        if (entry.getTitheAmountOverride() == null) {
            legacy.setDeductionType(DeductionType.PERCENTAGE);
            legacy.setPercentageRate(entry.getTitheRate() == null ? new BigDecimal("0.1000") : entry.getTitheRate());
            legacy.setFixedAmount(null);
        } else {
            legacy.setDeductionType(DeductionType.FIXED); legacy.setPercentageRate(null);
            legacy.setFixedAmount(calculations.money(entry.getTitheAmountOverride()));
        }
        UUID legacyId = legacy.getId();
        List<IncomeDeduction> prospective = new java.util.ArrayList<>(deductions
                .findAllByIncomeEntryIdAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(entry.getId()).stream()
                .filter(value -> !value.getId().equals(legacyId)).toList());
        prospective.add(legacy); validatePlannedDeductions(entry, prospective);
        legacy = deductions.saveAndFlush(legacy);
        return new DeductionMutation(legacy, created ? SyncOperationType.CREATE : SyncOperationType.UPDATE);
    }

    private void mirrorLegacyTithe(IncomeEntry entry, IncomeDeduction deduction, UUID actorId) {
        entry.setTitheEnabled(true);
        if (deduction.getDeductionType() == DeductionType.PERCENTAGE) {
            entry.setTitheRate(deduction.getPercentageRate()); entry.setTitheAmountOverride(null);
        } else {
            entry.setTitheAmountOverride(calculations.money(deduction.getFixedAmount()));
        }
        entry.setUpdatedByUserId(actorId); entry = income.saveAndFlush(entry);
        record(entry.getSpaceId(), "INCOME_ENTRY", entry.getId(), SyncOperationType.UPDATE,
                entry.getVersion(), actorId, false, mapIncome(entry));
    }

    private void apply(IncomeDeduction deduction, String name, DeductionType type, BigDecimal rate,
                       BigDecimal fixed, String notes, int sortOrder) {
        validateDeductionShape(type, rate, fixed);
        deduction.setName(name.trim()); deduction.setDeductionType(type);
        deduction.setPercentageRate(rate == null ? null : rate.setScale(4, java.math.RoundingMode.HALF_EVEN));
        deduction.setFixedAmount(calculations.nullableMoney(fixed)); deduction.setNotes(notes);
        deduction.setSortOrder(sortOrder);
    }

    private void validateDeductionShape(DeductionType type, BigDecimal rate, BigDecimal fixed) {
        if (type == DeductionType.PERCENTAGE) {
            if (rate == null || fixed != null || rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
                throw ApiException.badRequest("A percentage deduction requires only a rate between 0 and 1");
            }
        } else if (fixed == null || rate != null || fixed.signum() < 0) {
            throw ApiException.badRequest("A fixed deduction requires only a non-negative fixed amount");
        }
    }

    private void validatePlannedDeductions(IncomeEntry entry, List<IncomeDeduction> values) {
        BigDecimal total = values.stream().filter(value -> value.getDeletedAt() == null)
                .map(value -> calculations.projectedAmount(value, entry.getExpectedAmount()))
                .reduce(FinanceCalculationService.ZERO, BigDecimal::add);
        if (calculations.money(total).compareTo(calculations.money(entry.getExpectedAmount())) > 0) {
            throw ApiException.badRequest("Planned deductions cannot exceed expected gross income");
        }
    }

    private IncomeDeduction requireDeductionForIncome(UUID deductionId, UUID incomeId) {
        IncomeDeduction deduction = deductions.findByIdAndDeletedAtIsNull(deductionId)
                .orElseThrow(() -> ApiException.notFound("Income deduction"));
        if (!deduction.getIncomeEntryId().equals(incomeId)) {
            throw ApiException.badRequest("The receipt deduction must belong to the same income entry");
        }
        return deduction;
    }

    private List<DeductionMutation> reconcileReceiptDeductions(IncomeEntry entry, IncomeReceipt receipt,
                                                               List<ReceiptDeductionLineRequest> requested,
                                                               UUID actorId) {
        List<IncomeDeduction> planned = deductions.findAllForUpdateByIncomeEntryId(entry.getId());
        Map<UUID, IncomeDeduction> plannedById = planned.stream()
                .collect(Collectors.toMap(IncomeDeduction::getId, value -> value));
        List<IncomeReceiptDeduction> current = receiptDeductions.findAllForUpdateByIncomeReceiptId(receipt.getId());
        Map<UUID, IncomeReceiptDeduction> currentById = current.stream()
                .collect(Collectors.toMap(IncomeReceiptDeduction::getId, value -> value));
        java.util.Set<UUID> ids = new java.util.HashSet<>();
        java.util.Set<UUID> plannedIds = new java.util.HashSet<>();
        for (ReceiptDeductionLineRequest line : requested) {
            if (!ids.add(line.id()) || !plannedIds.add(line.incomeDeductionId())) {
                throw ApiException.badRequest("Each receipt deduction and planned deduction may appear only once");
            }
            if (!currentById.containsKey(line.id()) && receiptDeductions.existsById(line.id())) {
                throw ApiException.conflict("A receipt deduction with this identifier already exists");
            }
        }
        Instant now = Instant.now(clock);
        List<DeductionMutation> pending = new java.util.ArrayList<>();
        List<IncomeReceiptDeduction> prospective = new java.util.ArrayList<>();
        for (IncomeReceiptDeduction line : current) {
            if (!ids.contains(line.getId())) {
                line.setDeletedAt(now); line.setUpdatedByUserId(actorId);
                pending.add(new DeductionMutation(line, SyncOperationType.DELETE));
            }
        }
        for (ReceiptDeductionLineRequest request : requested) {
            IncomeDeduction plannedDeduction = plannedById.get(request.incomeDeductionId());
            if (plannedDeduction == null) throw ApiException.notFound("Income deduction");
            if (request.id().equals(receipt.getId()) && !plannedDeduction.isLegacyTithe()) {
                throw ApiException.badRequest("The receipt identifier is reserved for legacy Tithe compatibility");
            }
            IncomeReceiptDeduction line = currentById.get(request.id());
            SyncOperationType operation;
            if (line == null) {
                if (request.version() != null) throw ApiException.conflict("A new receipt deduction cannot have a version");
                line = new IncomeReceiptDeduction(); line.setId(request.id()); line.setSpaceId(entry.getSpaceId());
                line.setIncomeReceiptId(receipt.getId()); line.setIncomeDeductionId(plannedDeduction.getId());
                line.setCreatedByUserId(actorId); operation = SyncOperationType.CREATE;
            } else {
                requireVersion(line.getVersion(), request.version());
                if (!line.getIncomeDeductionId().equals(plannedDeduction.getId())) {
                    throw ApiException.badRequest("A receipt deduction cannot change its planned deduction");
                }
                operation = SyncOperationType.UPDATE;
            }
            line.setNameSnapshot(plannedDeduction.getName()); line.setActualAmount(calculations.money(request.amount()));
            line.setUpdatedByUserId(actorId); prospective.add(line);
            pending.add(new DeductionMutation(line, operation));
        }
        assertReceiptDeductionTotal(receipt, prospective);
        List<DeductionMutation> mutations = new java.util.ArrayList<>();
        for (DeductionMutation mutation : pending) {
            IncomeReceiptDeduction saved = receiptDeductions
                    .saveAndFlush((IncomeReceiptDeduction) mutation.value());
            mutations.add(new DeductionMutation(saved, mutation.operation()));
        }
        return mutations;
    }

    private List<DeductionMutation> rebalanceLegacyReceiptDeductions(IncomeEntry entry, UUID actorId) {
        IncomeDeduction legacy = deductions.findByIncomeEntryIdAndLegacyTitheTrueAndDeletedAtIsNull(entry.getId())
                .orElse(null);
        if (legacy == null) return List.of();
        List<IncomeReceipt> activeReceipts = receipts
                .findAllByIncomeEntryIdAndDeletedAtIsNullOrderByReceivedAtAscCreatedAtAsc(entry.getId()).stream()
                .sorted(java.util.Comparator.comparing(IncomeReceipt::getReceivedAt)
                        .thenComparing(IncomeReceipt::getCreatedAt, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                        .thenComparing(IncomeReceipt::getId)).toList();
        if (activeReceipts.isEmpty()) return List.of();
        List<UUID> receiptIds = activeReceipts.stream().map(IncomeReceipt::getId).toList();
        List<IncomeReceiptDeduction> lockedLines = receiptDeductions
                .findAllForUpdateByIncomeReceiptIdIn(receiptIds);
        Map<UUID, IncomeReceiptDeduction> current = lockedLines.stream()
                .filter(value -> value.getIncomeDeductionId().equals(legacy.getId()))
                .collect(Collectors.toMap(IncomeReceiptDeduction::getIncomeReceiptId, value -> value));
        Map<UUID, IncomeReceiptDeduction> byReservedId = receiptDeductions.findAllById(receiptIds).stream()
                .collect(Collectors.toMap(IncomeReceiptDeduction::getId, value -> value));
        Map<UUID, IncomeReceiptDeduction> archivedById = byReservedId.values().stream()
                .filter(value -> value.getDeletedAt() != null)
                .collect(Collectors.toMap(IncomeReceiptDeduction::getId, value -> value));
        for (IncomeReceipt receipt : activeReceipts) {
            IncomeReceiptDeduction reserved = byReservedId.get(receipt.getId());
            if (!current.containsKey(receipt.getId()) && reserved != null
                    && (reserved.getDeletedAt() == null || !reserved.getIncomeReceiptId().equals(receipt.getId())
                    || !reserved.getIncomeDeductionId().equals(legacy.getId()))) {
                throw ApiException.conflict("The receipt identifier is reserved for legacy Tithe compatibility");
            }
        }
        BigDecimal gross = calculations.money(activeReceipts.stream().map(IncomeReceipt::getAmount)
                .reduce(FinanceCalculationService.ZERO, BigDecimal::add));
        BigDecimal target;
        if (legacy.getDeductionType() == DeductionType.PERCENTAGE) {
            target = calculations.money(gross.multiply(legacy.getPercentageRate()));
        } else if (calculations.money(entry.getExpectedAmount()).signum() > 0) {
            target = calculations.money(legacy.getFixedAmount().multiply(gross)
                    .divide(calculations.money(entry.getExpectedAmount()), 8, java.math.RoundingMode.HALF_EVEN)
                    .min(legacy.getFixedAmount()));
        } else {
            target = calculations.money(gross.min(legacy.getFixedAmount()));
        }
        List<DeductionMutation> pending = new java.util.ArrayList<>();
        BigDecimal cumulativeGross = BigDecimal.ZERO;
        BigDecimal previouslyAllocated = FinanceCalculationService.ZERO;
        for (int index = 0; index < activeReceipts.size(); index++) {
            IncomeReceipt receipt = activeReceipts.get(index); cumulativeGross = cumulativeGross.add(receipt.getAmount());
            BigDecimal cumulativeAllocation = index == activeReceipts.size() - 1 ? target
                    : calculations.money(target.multiply(cumulativeGross).divide(gross, 8,
                            java.math.RoundingMode.HALF_EVEN));
            BigDecimal lineAmount = calculations.money(cumulativeAllocation.subtract(previouslyAllocated));
            previouslyAllocated = cumulativeAllocation;
            IncomeReceiptDeduction line = current.get(receipt.getId());
            SyncOperationType operation;
            if (line == null) {
                line = archivedById.get(receipt.getId());
                if (line == null) {
                    line = new IncomeReceiptDeduction(); line.setId(receipt.getId()); line.setSpaceId(entry.getSpaceId());
                    line.setIncomeReceiptId(receipt.getId()); line.setIncomeDeductionId(legacy.getId());
                    line.setCreatedByUserId(actorId); operation = SyncOperationType.CREATE;
                } else {
                    line.setDeletedAt(null); operation = SyncOperationType.UPDATE;
                }
            } else {
                if (calculations.money(line.getActualAmount()).compareTo(lineAmount) == 0
                        && java.util.Objects.equals(line.getNameSnapshot(), legacy.getName())) continue;
                operation = SyncOperationType.UPDATE;
            }
            line.setNameSnapshot(legacy.getName()); line.setActualAmount(lineAmount);
            line.setUpdatedByUserId(actorId); current.put(receipt.getId(), line);
            pending.add(new DeductionMutation(line, operation));
        }
        Map<UUID, IncomeReceipt> receiptById = activeReceipts.stream()
                .collect(Collectors.toMap(IncomeReceipt::getId, value -> value));
        Map<UUID, List<IncomeReceiptDeduction>> nonLegacyByReceipt = lockedLines.stream()
                .filter(value -> !value.getIncomeDeductionId().equals(legacy.getId()))
                .collect(Collectors.groupingBy(IncomeReceiptDeduction::getIncomeReceiptId));
        for (UUID receiptId : receiptIds) {
            List<IncomeReceiptDeduction> prospective = new java.util.ArrayList<>(
                    nonLegacyByReceipt.getOrDefault(receiptId, List.of()));
            prospective.add(current.get(receiptId));
            assertReceiptDeductionTotal(receiptById.get(receiptId), prospective);
        }
        List<DeductionMutation> mutations = new java.util.ArrayList<>();
        for (DeductionMutation mutation : pending) {
            IncomeReceiptDeduction saved = receiptDeductions
                    .saveAndFlush((IncomeReceiptDeduction) mutation.value());
            mutations.add(new DeductionMutation(saved, mutation.operation()));
        }
        return mutations;
    }

    private void assertReceiptDeductionTotal(IncomeReceipt receipt, List<IncomeReceiptDeduction> values) {
        BigDecimal total = values.stream().filter(value -> value.getDeletedAt() == null)
                .map(IncomeReceiptDeduction::getActualAmount)
                .reduce(FinanceCalculationService.ZERO, BigDecimal::add);
        if (calculations.money(total).compareTo(calculations.money(receipt.getAmount())) > 0) {
            throw ApiException.badRequest("Receipt deductions cannot exceed receipt gross amount");
        }
    }

    private void recordDeduction(IncomeDeduction deduction, SyncOperationType operation, UUID actorId,
                                 boolean tombstone) {
        BigDecimal expected = income.findById(deduction.getIncomeEntryId())
                .map(IncomeEntry::getExpectedAmount).orElse(FinanceCalculationService.ZERO);
        record(deduction.getSpaceId(), "INCOME_DEDUCTION", deduction.getId(), operation,
                deduction.getVersion(), actorId, tombstone, calculations.deduction(deduction, expected));
    }

    private void recordReceiptDeduction(IncomeReceiptDeduction deduction, SyncOperationType operation,
                                        UUID actorId, boolean tombstone) {
        record(deduction.getSpaceId(), "INCOME_RECEIPT_DEDUCTION", deduction.getId(), operation,
                deduction.getVersion(), actorId, tombstone, calculations.receiptDeduction(deduction));
    }

    private void recordDeductionMutation(DeductionMutation mutation, UUID actorId) {
        if (mutation == null) return;
        boolean tombstone = mutation.operation() == SyncOperationType.DELETE;
        if (mutation.value() instanceof IncomeDeduction deduction) {
            recordDeduction(deduction, mutation.operation(), actorId, tombstone);
            auditDeduction(actorId, deduction, tombstone ? "INCOME_DEDUCTION_DELETED"
                    : mutation.operation() == SyncOperationType.CREATE ? "INCOME_DEDUCTION_CREATED"
                    : "INCOME_DEDUCTION_UPDATED", "Legacy Tithe compatibility updated");
        } else if (mutation.value() instanceof IncomeReceiptDeduction deduction) {
            recordReceiptDeduction(deduction, mutation.operation(), actorId, tombstone);
            auditReceiptDeduction(actorId, deduction, tombstone ? "INCOME_RECEIPT_DEDUCTION_DELETED"
                    : mutation.operation() == SyncOperationType.CREATE ? "INCOME_RECEIPT_DEDUCTION_CREATED"
                    : "INCOME_RECEIPT_DEDUCTION_UPDATED");
        }
    }

    private void recordDeductionMutations(List<DeductionMutation> mutations, UUID actorId) {
        mutations.forEach(value -> recordDeductionMutation(value, actorId));
    }

    private void auditDeduction(UUID actorId, IncomeDeduction deduction, String action, String metadata) {
        audit.record(actorId, deduction.getSpaceId(), action, "INCOME_DEDUCTION", deduction.getId(),
                AuditResult.SUCCESS, metadata + "; incomeEntryId=" + deduction.getIncomeEntryId());
    }

    private void auditReceiptDeduction(UUID actorId, IncomeReceiptDeduction deduction, String action) {
        audit.record(actorId, deduction.getSpaceId(), action, "INCOME_RECEIPT_DEDUCTION", deduction.getId(),
                AuditResult.SUCCESS, "receiptId=" + deduction.getIncomeReceiptId()
                        + "; incomeDeductionId=" + deduction.getIncomeDeductionId()
                        + "; amount=" + calculations.money(deduction.getActualAmount()));
    }

    private record DeductionMutation(Object value, SyncOperationType operation) {}

    private IncomeEntry requireIncome(UUID id) { return income.findByIdAndDeletedAtIsNull(id).orElseThrow(() -> ApiException.notFound("Income entry")); }
    private IncomeEntry requireIncomeForUpdate(UUID id) { return income.findForUpdateById(id).orElseThrow(() -> ApiException.notFound("Income entry")); }
    private IncomeReceipt requireReceiptForUpdate(UUID id) { return receipts.findForUpdateById(id).orElseThrow(() -> ApiException.notFound("Income receipt")); }
    private BudgetFundingAllocation requireAllocationForUpdate(UUID id) { return allocations.findForUpdateById(id).orElseThrow(() -> ApiException.notFound("Funding allocation")); }
    private void requireVersion(long actual, Long requested) {
        if (requested == null) throw ApiException.badRequest("version is required");
        if (actual != requested.longValue()) throw ApiException.conflict("The record was changed on another device");
    }
    private void validateItem(BudgetItemType type, BigDecimal amount) { if (type == BudgetItemType.UNBUDGETED && amount.signum() != 0) throw ApiException.badRequest("An unbudgeted item must have a zero planned amount"); }

    private void validateIncome(BigDecimal expected, LocalDate expectedDate, String timeZone, BigDecimal override) {
        validateTimeZone(timeZone);
        validateExpectedDate(expectedDate);
        if (override != null && override.compareTo(expected) > 0) throw ApiException.badRequest("A deduction override cannot exceed expected income");
    }

    private void validateExpectedDate(LocalDate expectedDate) {
        if (expectedDate == null || expectedDate.getYear() < 2000 || expectedDate.getYear() > 2200) {
            throw ApiException.badRequest("Expected income date must use a supported year from 2000 to 2200");
        }
    }

    private void validateReceipt(Instant receivedAt, String timeZone) {
        validateTimeZone(timeZone);
        if (receivedAt.isAfter(Instant.now(clock).plusSeconds(86_400))) throw ApiException.badRequest("A receipt cannot be more than one day in the future");
    }

    private void assertConfirmedCoverage(IncomeEntry entry, List<IncomeReceipt> prospectiveReceipts) {
        List<BudgetFundingAllocation> active = allocations
                .findAllByIncomeEntryIdAndDeletedAtIsNullOrderByCreatedAtAsc(entry.getId());
        List<IncomeReceiptDeduction> actuals = prospectiveReceipts.isEmpty() ? List.of()
                : receiptDeductions.findAllByIncomeReceiptIdInAndDeletedAtIsNull(
                        prospectiveReceipts.stream().map(IncomeReceipt::getId).toList());
        IncomeAvailability availability = calculations.income(entry, prospectiveReceipts,
                deductions.findAllByIncomeEntryIdAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(entry.getId()),
                actuals, active, todayForIncome(entry)).availability();
        if (availability.confirmedAllocatedAmount().compareTo(availability.receivedNetIncome()) > 0) {
            throw ApiException.conflict("This change would reduce received net income below its confirmed allocations");
        }
    }

    private void lockIncomeSources(UUID... incomeIds) {
        java.util.Arrays.stream(incomeIds).filter(java.util.Objects::nonNull).distinct().sorted()
                .forEach(this::requireIncomeForUpdate);
    }

    private void validateFunding(BudgetItem item, UUID replacingAllocationId, UUID incomeId, FundingSourceType sourceType,
                                 BigDecimal planned, BigDecimal confirmed, Instant allocatedAt, String timeZone) {
        if (planned.signum() < 0 || confirmed.signum() < 0 || (planned.signum() == 0 && confirmed.signum() == 0)) throw ApiException.badRequest("Enter a planned or confirmed funding amount");
        if (sourceType == FundingSourceType.INCOME_ENTRY) {
            if (incomeId == null) throw ApiException.badRequest("Choose an income source for an income-funded allocation");
            IncomeEntry source = requireIncome(incomeId);
            if (!source.getSpaceId().equals(item.getSpaceId()) || !source.getBudgetMonthId().equals(item.getBudgetMonthId())) throw ApiException.badRequest("Funding income must belong to this budget and space");
            if (confirmed.signum() > 0) {
                if (allocatedAt == null || timeZone == null || timeZone.isBlank()) throw ApiException.badRequest("Confirmed funding requires an allocation date, time, and time zone");
                validateTimeZone(timeZone);
                List<BudgetFundingAllocation> existing = allocations.findAllByIncomeEntryIdAndDeletedAtIsNullOrderByCreatedAtAsc(incomeId).stream()
                        .filter(value -> !value.getId().equals(replacingAllocationId)).toList();
                List<IncomeReceipt> sourceReceipts = receipts
                        .findAllByIncomeEntryIdAndDeletedAtIsNullOrderByReceivedAtAscCreatedAtAsc(incomeId);
                List<IncomeReceiptDeduction> actuals = sourceReceipts.isEmpty() ? List.of()
                        : receiptDeductions.findAllByIncomeReceiptIdInAndDeletedAtIsNull(
                                sourceReceipts.stream().map(IncomeReceipt::getId).toList());
                BigDecimal available = calculations.income(source, sourceReceipts,
                        deductions.findAllByIncomeEntryIdAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(incomeId),
                        actuals, existing, todayForIncome(source)).availability().unallocatedReceivedIncome();
                if (confirmed.compareTo(available) > 0) throw ApiException.conflict("Confirmed allocation exceeds this income's unallocated received balance");
            }
        } else if (incomeId != null) {
            throw ApiException.badRequest("External, unassigned, and rollover funding cannot link directly to an income entry");
        } else if (confirmed.signum() > 0) {
            if (allocatedAt == null || timeZone == null || timeZone.isBlank()) throw ApiException.badRequest("Confirmed funding requires an allocation date, time, and time zone");
            validateTimeZone(timeZone);
        }
    }

    private void apply(IncomeEntry entry, String source, UUID type, BigDecimal expected, LocalDate expectedDate,
                       java.time.LocalTime expectedTime, String timeZone, boolean tithe, BigDecimal rate,
                       BigDecimal override, boolean recurring, String recurrenceRule, String notes, int sort) {
        entry.setSourceName(source.trim()); entry.setIncomeTypeId(type); entry.setExpectedAmount(calculations.money(expected));
        entry.setExpectedDate(expectedDate); entry.setExpectedTime(expectedTime); entry.setTimeZone(timeZone); entry.setTitheEnabled(tithe);
        entry.setTitheRate(rate == null ? new BigDecimal("0.1000") : rate); entry.setTitheAmountOverride(calculations.nullableMoney(override));
        entry.setRecurring(recurring); entry.setRecurrenceRule(recurrenceRule); entry.setNotes(notes); entry.setSortOrder(sort);
    }
    private void apply(IncomeReceipt receipt, BigDecimal amount, Instant receivedAt, String timeZone, String notes) {
        receipt.setAmount(calculations.money(amount)); receipt.setReceivedAt(receivedAt); receipt.setTimeZone(timeZone); receipt.setNotes(notes);
    }
    private void apply(BudgetFundingAllocation allocation, UUID incomeId, FundingSourceType sourceType,
                       BigDecimal planned, BigDecimal confirmed, Instant allocatedAt, String timeZone, String notes) {
        allocation.setIncomeEntryId(incomeId); allocation.setSourceType(sourceType); allocation.setPlannedAmount(calculations.money(planned));
        allocation.setConfirmedAllocatedAmount(calculations.money(confirmed)); allocation.setAllocatedAt(allocatedAt);
        allocation.setTimeZone(timeZone); allocation.setNotes(notes);
    }
    private void apply(BudgetItem item, String name, UUID category, BigDecimal planned, boolean tracked, BudgetItemType type,
                       boolean recurring, boolean rollover, String notes, int sort) {
        item.setName(name.trim()); item.setCategoryId(category); item.setPlannedAmount(calculations.money(planned)); item.setTracked(tracked);
        item.setItemType(type); item.setRecurring(recurring); item.setRolloverEnabled(rollover); item.setNotes(notes); item.setSortOrder(sort);
    }

    private LocalDate adjustedDate(LocalDate date, BudgetMonth source, int year, int month) {
        long offset = ChronoUnit.DAYS.between(YearMonth.of(source.getYear(), source.getMonth()).atDay(1), date);
        return YearMonth.of(year, month).atDay(1).plusDays(offset);
    }
    private LocalDate todayForIncome(IncomeEntry entry) { return LocalDate.now(clock.withZone(ZoneId.of(entry.getTimeZone()))); }
    private LocalDate todayForSpace(UUID spaceId) { return LocalDate.now(clock.withZone(ZoneId.of(spaces.requireSpace(spaceId).getTimeZone()))); }
    private void validateTimeZone(String timeZone) { try { ZoneId.of(timeZone); } catch (Exception ex) { throw ApiException.badRequest("Select a valid IANA time zone"); } }
    private void auditFundingCreate(UUID actorId, BudgetFundingAllocation allocation) {
        if (allocation.getSourceType() == FundingSourceType.EXTERNAL_FUNDS && allocation.getConfirmedAllocatedAmount().signum() > 0) {
            audit.record(actorId, allocation.getSpaceId(), "EXTERNAL_FUNDS_ALLOCATED", "FUNDING_ALLOCATION", allocation.getId(), AuditResult.SUCCESS,
                    "External funds confirmed against a budget item");
        }
    }
    private void auditFundingUpdate(UUID actorId, BudgetFundingAllocation allocation, BigDecimal previousConfirmed,
                                    FundingSourceType previousSourceType, UUID previousIncomeId) {
        BigDecimal current = calculations.money(allocation.getConfirmedAllocatedAmount());
        if (current.compareTo(previousConfirmed) < 0) {
            audit.record(actorId, allocation.getSpaceId(), "FUNDING_ALLOCATION_REDUCED", "FUNDING_ALLOCATION",
                    allocation.getId(), AuditResult.SUCCESS,
                    "released=" + calculations.money(previousConfirmed.subtract(current))
                            + "; previousConfirmed=" + previousConfirmed + "; confirmed=" + current
                            + "; incomeEntryId=" + previousIncomeId + "; budgetItemId=" + allocation.getBudgetItemId());
        }
        if (previousSourceType != allocation.getSourceType()
                || !java.util.Objects.equals(previousIncomeId, allocation.getIncomeEntryId())) {
            audit.record(actorId, allocation.getSpaceId(), "FUNDING_ALLOCATION_SOURCE_CHANGED", "FUNDING_ALLOCATION",
                    allocation.getId(), AuditResult.SUCCESS,
                    "from=" + previousSourceType + ":" + previousIncomeId + "; to=" + allocation.getSourceType()
                            + ":" + allocation.getIncomeEntryId());
        }
        if (allocation.getSourceType() == FundingSourceType.EXTERNAL_FUNDS
                && current.compareTo(previousConfirmed) > 0) auditFundingCreate(actorId, allocation);
    }
    private void record(UUID spaceId, String type, UUID id, SyncOperationType operation, long version, UUID actor, boolean tombstone, Object payload) {
        changes.orderedStream().forEach(recorder -> recorder.record(spaceId, type, id, operation, version, actor, tombstone, payload));
    }
}
