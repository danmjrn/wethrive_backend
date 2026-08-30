package solutions.shapeit.wethrive.finance.service;

import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import solutions.shapeit.wethrive.common.domain.DomainChangeRecorder;
import solutions.shapeit.wethrive.common.domain.DomainEnums.MembershipStatus;
import solutions.shapeit.wethrive.common.domain.DomainEnums.SyncOperationType;
import solutions.shapeit.wethrive.common.domain.DomainEnums.TransactionType;
import solutions.shapeit.wethrive.common.web.ApiException;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.MoveSpendingRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.RefundRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.SpendingRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.SpendingResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.SpendingUpdateRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.RestoreSpendingRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.VersionRequest;
import solutions.shapeit.wethrive.finance.entity.BudgetItem;
import solutions.shapeit.wethrive.finance.entity.SpendingEntry;
import solutions.shapeit.wethrive.finance.repository.SpendingEntryRepository;
import solutions.shapeit.wethrive.space.repository.SpaceMembershipRepository;
import solutions.shapeit.wethrive.space.service.SpaceAccessService;
import solutions.shapeit.wethrive.space.service.SpaceAccessService.Capability;

@Service
public class SpendingService {
    private final SpendingEntryRepository spending;
    private final BudgetService budgets;
    private final SpaceMembershipRepository memberships;
    private final SpaceAccessService access;
    private final FinanceCalculationService calculations;
    private final ObjectProvider<DomainChangeRecorder> changes;
    private final Clock clock;

    public SpendingService(SpendingEntryRepository spending, BudgetService budgets,
                           SpaceMembershipRepository memberships, SpaceAccessService access,
                           FinanceCalculationService calculations, ObjectProvider<DomainChangeRecorder> changes,
                           Clock clock) {
        this.spending = spending;
        this.budgets = budgets;
        this.memberships = memberships;
        this.access = access;
        this.calculations = calculations;
        this.changes = changes;
        this.clock = clock;
    }

    @Transactional
    public Page<SpendingResponse> list(UUID actorId, UUID spaceId, Instant from, Instant to, int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(size, 1), 200), Sort.by(Sort.Direction.DESC, "spentAt"));
        if (spaceId != null) {
            access.require(spaceId, actorId, Capability.VIEW);
            if (from != null || to != null) {
                Instant start = from == null ? Instant.EPOCH : from;
                Instant end = to == null ? Instant.now(clock).plusSeconds(315_576_000L) : to;
                return spending.findAllBySpaceIdAndSpentAtBetweenAndDeletedAtIsNull(spaceId, start, end, pageable).map(this::map);
            }
            return spending.findAllBySpaceIdInAndDeletedAtIsNull(List.of(spaceId), pageable).map(this::map);
        }
        List<UUID> authorizedSpaces = memberships.findAllByUserIdAndStatusAndDeletedAtIsNull(actorId, MembershipStatus.ACTIVE)
                .stream().map(m -> m.getSpaceId()).toList();
        return spending.findAllBySpaceIdInAndDeletedAtIsNull(authorizedSpaces, pageable).map(this::map);
    }

    @Transactional
    public SpendingResponse get(UUID id, UUID actorId) {
        SpendingEntry entry = require(id);
        access.require(entry.getSpaceId(), actorId, Capability.VIEW);
        return map(entry);
    }

    @Transactional
    public SpendingResponse create(UUID actorId, SpendingRequest request) {
        Capability capability = request.transactionType() == TransactionType.REFUND
                ? Capability.EDIT_FINANCE : Capability.RECORD_SPENDING;
        BudgetItem item = budgets.requireOpenItemForUpdate(request.budgetItemId(), actorId, capability);
        if (spending.existsById(request.id())) throw ApiException.conflict("A spending entry with this identifier already exists");
        SpendingEntry refundedExpense = requireLinkedRefundSource(request, item);
        UUID spentBy = request.spentByUserId() == null ? actorId : request.spentByUserId();
        validateAttribution(item.getSpaceId(), actorId, spentBy);
        SpendingEntry entry = new SpendingEntry();
        entry.setId(request.id()); entry.setSpaceId(item.getSpaceId()); entry.setBudgetItemId(item.getId());
        entry.setCreatedByUserId(actorId); entry.setUpdatedByUserId(actorId);
        entry.setRefundForSpendingEntryId(refundedExpense == null ? null : refundedExpense.getId());
        apply(entry, request.transactionType(), request.title(), request.amount(), request.date(), request.time(),
                request.timeZone(), request.paymentMethod(), request.merchant(), request.notes(), spentBy);
        assertItemRefundCoverage(item.getId(), entry.getId(), entry.getTransactionType(), entry.getAmount(), true);
        entry = spending.saveAndFlush(entry);
        SpendingResponse response = map(entry);
        record(entry, SyncOperationType.CREATE, actorId, false, response);
        return response;
    }

    @Transactional
    public SpendingResponse update(UUID id, UUID actorId, SpendingUpdateRequest request) {
        SpendingEntry preview = require(id);
        BudgetItem source = budgets.requireItem(preview.getBudgetItemId());
        BudgetItem requestedTarget = budgets.requireItem(request.budgetItemId());
        requireSameBudget(source, requestedTarget);
        Capability capability = preview.getTransactionType() == TransactionType.REFUND
                ? Capability.EDIT_FINANCE : Capability.RECORD_SPENDING;
        BudgetItem target = budgets.requireOpenItemForUpdate(request.budgetItemId(), actorId, capability);
        SpendingEntry entry = requireForUpdateWithRefundSource(id, false);
        requireCanEdit(entry, actorId);
        requireVersion(entry.getVersion(), request.version());
        if (request.transactionType() != entry.getTransactionType()) {
            throw ApiException.conflict("Transaction type cannot be changed; create the correct transaction instead");
        }
        requireSameBudget(source, target);
        validateItemMove(entry, target);
        validateProspectiveAmount(entry, request.amount());
        assertProspectiveItemCoverage(entry, target.getId(), request.transactionType(), request.amount());
        UUID spentBy = request.spentByUserId() == null ? actorId : request.spentByUserId();
        validateAttribution(entry.getSpaceId(), actorId, spentBy);
        entry.setBudgetItemId(target.getId());
        entry.setUpdatedByUserId(actorId);
        apply(entry, request.transactionType(), request.title(), request.amount(), request.date(), request.time(),
                request.timeZone(), request.paymentMethod(), request.merchant(), request.notes(), spentBy);
        entry = spending.saveAndFlush(entry);
        SpendingResponse response = map(entry);
        record(entry, SyncOperationType.UPDATE, actorId, false, response);
        return response;
    }

    @Transactional
    public void delete(UUID id, UUID actorId, VersionRequest request) {
        SpendingEntry preview = require(id);
        Capability capability = preview.getTransactionType() == TransactionType.REFUND
                ? Capability.EDIT_FINANCE : Capability.RECORD_SPENDING;
        budgets.requireOpenItemForUpdate(preview.getBudgetItemId(), actorId, capability);
        SpendingEntry entry = requireForUpdateWithRefundSource(id, false);
        requireCanEdit(entry, actorId);
        requireVersion(entry.getVersion(), request.version());
        if (entry.getTransactionType() == TransactionType.EXPENSE && !activeRefunds(entry.getId()).isEmpty()) {
            throw ApiException.conflict("Delete linked refunds before deleting their original expense");
        }
        assertItemRefundCoverage(entry.getBudgetItemId(), entry.getId(), null, null, false);
        entry.setDeletedAt(Instant.now(clock)); entry.setUpdatedByUserId(actorId);
        entry = spending.saveAndFlush(entry);
        record(entry, SyncOperationType.DELETE, actorId, true, null);
    }

    @Transactional
    public SpendingResponse restore(UUID id, UUID actorId, RestoreSpendingRequest request) {
        SpendingEntry preview = spending.findById(id).orElseThrow(() -> ApiException.notFound("Spending entry"));
        Capability capability = preview.getTransactionType() == TransactionType.REFUND
                ? Capability.EDIT_FINANCE : Capability.RECORD_SPENDING;
        budgets.requireOpenItemForUpdate(preview.getBudgetItemId(), actorId, capability);
        SpendingEntry entry = requireForUpdateWithRefundSource(id, true);
        requireCanEdit(entry, actorId);
        requireVersion(entry.getVersion(), request.version());
        if (entry.getDeletedAt() == null) return map(entry);
        if (isLegacyUnlinkedRefund(entry)) {
            throw legacyRefundReconciliationRequired("A legacy unlinked refund cannot be restored until it is reconciled");
        }
        if (entry.getRefundForSpendingEntryId() != null) {
            SpendingEntry original = requireActiveExpense(entry.getRefundForSpendingEntryId());
            assertRefundWithinOriginal(original, entry.getId(), entry.getAmount());
        }
        assertItemRefundCoverage(entry.getBudgetItemId(), entry.getId(), entry.getTransactionType(), entry.getAmount(), true);
        entry.setDeletedAt(null);
        entry.setUpdatedByUserId(actorId);
        entry = spending.saveAndFlush(entry);
        SpendingResponse response = map(entry);
        record(entry, SyncOperationType.RESTORE, actorId, false, response);
        return response;
    }

    @Transactional
    public SpendingResponse refund(UUID id, UUID actorId, RefundRequest request) {
        SpendingEntry preview = require(id);
        BudgetItem item = budgets.requireOpenItemForUpdate(preview.getBudgetItemId(), actorId,
                Capability.EDIT_FINANCE);
        SpendingEntry original = spending.findForUpdateById(id)
                .orElseThrow(() -> ApiException.notFound("Spending entry"));
        requireRefundableExpense(original, item);
        if (spending.existsById(request.id())) throw ApiException.conflict("A spending entry with this identifier already exists");
        assertRefundWithinOriginal(original, null, request.amount());
        SpendingEntry refund = new SpendingEntry();
        refund.setId(request.id()); refund.setSpaceId(original.getSpaceId()); refund.setBudgetItemId(original.getBudgetItemId());
        refund.setCreatedByUserId(actorId); refund.setUpdatedByUserId(actorId);
        refund.setRefundForSpendingEntryId(original.getId());
        apply(refund, TransactionType.REFUND, "Refund: " + original.getTitle(), request.amount(), request.date(),
                request.time(), request.timeZone(), original.getPaymentMethod(), original.getMerchant(), request.notes(),
                original.getSpentByUserId());
        assertItemRefundCoverage(item.getId(), refund.getId(), TransactionType.REFUND, refund.getAmount(), true);
        refund = spending.saveAndFlush(refund);
        SpendingResponse response = map(refund);
        record(refund, SyncOperationType.CREATE, actorId, false, response);
        return response;
    }

    @Transactional
    public SpendingResponse move(UUID id, UUID actorId, MoveSpendingRequest request) {
        SpendingEntry preview = require(id);
        BudgetItem source = budgets.requireItem(preview.getBudgetItemId());
        BudgetItem requestedTarget = budgets.requireItem(request.budgetItemId());
        requireSameBudget(source, requestedTarget);
        BudgetItem target = budgets.requireOpenItemForUpdate(request.budgetItemId(), actorId,
                preview.getTransactionType() == TransactionType.REFUND
                        ? Capability.EDIT_FINANCE : Capability.RECORD_SPENDING);
        SpendingEntry entry = requireForUpdateWithRefundSource(id, false);
        requireCanEdit(entry, actorId);
        requireVersion(entry.getVersion(), request.version());
        validateItemMove(entry, target);
        assertProspectiveItemCoverage(entry, target.getId(), entry.getTransactionType(), entry.getAmount());
        entry.setBudgetItemId(target.getId()); entry.setUpdatedByUserId(actorId);
        entry = spending.saveAndFlush(entry);
        SpendingResponse response = map(entry);
        record(entry, SyncOperationType.UPDATE, actorId, false, response);
        return response;
    }

    private void requireCanEdit(SpendingEntry entry, UUID actorId) {
        if (entry.getTransactionType() == TransactionType.REFUND) {
            access.require(entry.getSpaceId(), actorId, Capability.EDIT_FINANCE);
            return;
        }
        access.require(entry.getSpaceId(), actorId, Capability.VIEW);
        boolean own = actorId.equals(entry.getCreatedByUserId()) || actorId.equals(entry.getSpentByUserId());
        if (!own && !access.can(entry.getSpaceId(), actorId, Capability.EDIT_FINANCE)) throw ApiException.forbidden();
    }

    private SpendingEntry requireLinkedRefundSource(SpendingRequest request, BudgetItem item) {
        UUID originalId = request.refundForSpendingEntryId();
        if (request.transactionType() == TransactionType.EXPENSE) {
            if (originalId != null) throw ApiException.badRequest("An expense cannot reference a refunded transaction");
            return null;
        }
        // Historical null-link refunds remain readable, but every new refund must participate in
        // source and cumulative validation on both direct API and synchronized creation paths.
        if (originalId == null) {
            throw ApiException.badRequest("Choose the original expense for this refund");
        }
        SpendingEntry original = spending.findForUpdateById(originalId)
                .orElseThrow(() -> ApiException.notFound("Original spending entry"));
        requireRefundableExpense(original, item);
        assertRefundWithinOriginal(original, null, request.amount());
        return original;
    }

    private SpendingEntry requireForUpdateWithRefundSource(UUID id, boolean includeDeleted) {
        SpendingEntry snapshot = spending.findById(id).orElseThrow(() -> ApiException.notFound("Spending entry"));
        if (snapshot.getRefundForSpendingEntryId() != null) {
            spending.findAnyForUpdateById(snapshot.getRefundForSpendingEntryId())
                    .orElseThrow(() -> ApiException.conflict("The linked original expense no longer exists"));
        }
        return (includeDeleted ? spending.findAnyForUpdateById(id) : spending.findForUpdateById(id))
                .orElseThrow(() -> ApiException.notFound("Spending entry"));
    }

    private SpendingEntry requireActiveExpense(UUID id) {
        SpendingEntry original = spending.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> ApiException.conflict("Restore the original expense before changing its refund"));
        if (original.getTransactionType() != TransactionType.EXPENSE
                || original.getRefundForSpendingEntryId() != null) {
            throw ApiException.conflict("A refund must reference an active expense");
        }
        return original;
    }

    private void requireRefundableExpense(SpendingEntry original, BudgetItem item) {
        if (original.getTransactionType() != TransactionType.EXPENSE
                || original.getRefundForSpendingEntryId() != null) {
            throw ApiException.badRequest("Only an expense can receive a linked refund");
        }
        if (!original.getSpaceId().equals(item.getSpaceId())
                || !original.getBudgetItemId().equals(item.getId())) {
            throw ApiException.badRequest("A refund must use its original expense's space and budget item");
        }
    }

    private List<SpendingEntry> activeRefunds(UUID originalId) {
        return spending.findAllByRefundForSpendingEntryIdAndDeletedAtIsNullOrderBySpentAtAscCreatedAtAsc(originalId);
    }

    private void assertRefundWithinOriginal(SpendingEntry original, UUID replacingRefundId, BigDecimal amount) {
        BigDecimal existing = activeRefunds(original.getId()).stream()
                .filter(refund -> !refund.getId().equals(replacingRefundId))
                .map(SpendingEntry::getAmount)
                .reduce(FinanceCalculationService.ZERO, BigDecimal::add);
        BigDecimal prospective = calculations.money(existing.add(calculations.money(amount)));
        if (prospective.compareTo(calculations.money(original.getAmount())) > 0) {
            throw ApiException.conflict("refund_capacity_exceeded",
                    "Total active refunds cannot exceed the original expense amount");
        }
    }

    private void validateProspectiveAmount(SpendingEntry entry, BigDecimal amount) {
        if (isLegacyUnlinkedRefund(entry)) {
            if (calculations.money(amount).compareTo(calculations.money(entry.getAmount())) > 0) {
                throw legacyRefundReconciliationRequired("A legacy unlinked refund amount can only be reduced until it is reconciled");
            }
            return;
        }
        if (entry.getRefundForSpendingEntryId() != null) {
            assertRefundWithinOriginal(requireActiveExpense(entry.getRefundForSpendingEntryId()),
                    entry.getId(), amount);
            return;
        }
        if (entry.getTransactionType() == TransactionType.EXPENSE) {
            BigDecimal refunded = activeRefunds(entry.getId()).stream().map(SpendingEntry::getAmount)
                    .reduce(FinanceCalculationService.ZERO, BigDecimal::add);
            if (calculations.money(refunded).compareTo(calculations.money(amount)) > 0) {
                throw ApiException.conflict("The expense amount cannot be lower than its active refunds");
            }
        }
    }

    private void validateItemMove(SpendingEntry entry, BudgetItem target) {
        if (!target.getSpaceId().equals(entry.getSpaceId())) {
            throw ApiException.badRequest("Spending cannot be moved between spaces");
        }
        if (target.getId().equals(entry.getBudgetItemId())) return;
        if (isLegacyUnlinkedRefund(entry)) {
            throw legacyRefundReconciliationRequired("A legacy unlinked refund cannot be moved until it is reconciled");
        }
        if (entry.getRefundForSpendingEntryId() != null) {
            throw ApiException.conflict("A linked refund must remain on its original expense's budget item");
        }
        if (entry.getTransactionType() == TransactionType.EXPENSE && !activeRefunds(entry.getId()).isEmpty()) {
            throw ApiException.conflict("Move linked refunds before moving their original expense");
        }
    }

    /**
     * Keeps inherited unlinked refunds visible without allowing either direct API or sync mutations
     * to make an item's active refunds exceed its active expenses. The budget lifecycle lock held by
     * every caller serializes this aggregate check across all spending rows in the budget.
     */
    private void assertItemRefundCoverage(UUID itemId, UUID replacingEntryId, TransactionType replacementType,
                                          BigDecimal replacementAmount, boolean replacementActive) {
        List<SpendingEntry> active = spending.findAllByBudgetItemIdAndDeletedAtIsNullOrderBySpentAtDesc(itemId);
        BigDecimal currentExpenses = FinanceCalculationService.ZERO;
        BigDecimal currentRefunds = FinanceCalculationService.ZERO;
        BigDecimal prospectiveExpenses = FinanceCalculationService.ZERO;
        BigDecimal prospectiveRefunds = FinanceCalculationService.ZERO;
        for (SpendingEntry value : active) {
            if (value.getTransactionType() == TransactionType.EXPENSE) currentExpenses = currentExpenses.add(value.getAmount());
            else currentRefunds = currentRefunds.add(value.getAmount());
            if (value.getId().equals(replacingEntryId)) continue;
            if (value.getTransactionType() == TransactionType.EXPENSE) prospectiveExpenses = prospectiveExpenses.add(value.getAmount());
            else prospectiveRefunds = prospectiveRefunds.add(value.getAmount());
        }
        if (replacementActive && replacementType != null && replacementAmount != null) {
            if (replacementType == TransactionType.EXPENSE) prospectiveExpenses = prospectiveExpenses.add(replacementAmount);
            else prospectiveRefunds = prospectiveRefunds.add(replacementAmount);
        }
        BigDecimal currentExcess = calculations.money(currentRefunds.subtract(currentExpenses)).max(FinanceCalculationService.ZERO);
        BigDecimal prospectiveExcess = calculations.money(prospectiveRefunds.subtract(prospectiveExpenses)).max(FinanceCalculationService.ZERO);
        if (prospectiveExcess.compareTo(currentExcess) > 0) {
            throw ApiException.conflict("refund_capacity_exceeded",
                    "Total active refunds cannot exceed total active expenses for the budget item");
        }
    }

    private void assertProspectiveItemCoverage(SpendingEntry entry, UUID targetItemId,
                                               TransactionType replacementType, BigDecimal replacementAmount) {
        if (!entry.getBudgetItemId().equals(targetItemId)) {
            assertItemRefundCoverage(entry.getBudgetItemId(), entry.getId(), null, null, false);
        }
        assertItemRefundCoverage(targetItemId, entry.getId(), replacementType, replacementAmount, true);
    }

    private boolean isLegacyUnlinkedRefund(SpendingEntry entry) {
        return entry.getTransactionType() == TransactionType.REFUND
                && entry.getRefundForSpendingEntryId() == null;
    }

    private ApiException legacyRefundReconciliationRequired(String message) {
        return ApiException.conflict("legacy_refund_reconciliation_required", message);
    }

    private void requireSameBudget(BudgetItem source, BudgetItem target) {
        if (!source.getSpaceId().equals(target.getSpaceId())) {
            throw ApiException.badRequest("Spending cannot be moved between spaces");
        }
        if (!source.getBudgetMonthId().equals(target.getBudgetMonthId())) {
            throw ApiException.badRequest("Spending cannot be moved between budget months");
        }
    }

    private void validateAttribution(UUID spaceId, UUID actorId, UUID spentBy) {
        if (spentBy == null) return;
        if (!memberships.findBySpaceIdAndUserIdAndStatusAndDeletedAtIsNull(spaceId, spentBy, MembershipStatus.ACTIVE).isPresent()) {
            throw ApiException.badRequest("The selected spender is not an active member of this space");
        }
        if (!actorId.equals(spentBy) && !access.can(spaceId, actorId, Capability.EDIT_FINANCE)) throw ApiException.forbidden();
    }

    private void apply(SpendingEntry e, TransactionType type, String title, java.math.BigDecimal amount,
                       LocalDate date, LocalTime time, String timeZone, String payment, String merchant,
                       String notes, UUID spentBy) {
        try {
            e.setTransactionType(type); e.setTitle(title.trim()); e.setAmount(calculations.money(amount));
            e.setUserSelectedDate(date); e.setUserSelectedTime(time.withNano(0)); e.setTimeZone(timeZone);
            e.setSpentAt(ZonedDateTime.of(date, time, ZoneId.of(timeZone)).toInstant());
            e.setPaymentMethod(blankToNull(payment)); e.setMerchant(blankToNull(merchant)); e.setNotes(blankToNull(notes));
            e.setSpentByUserId(spentBy);
        } catch (java.time.DateTimeException ex) {
            throw ApiException.badRequest("The selected date, time, or time zone is invalid");
        }
    }

    private SpendingEntry require(UUID id) { return spending.findByIdAndDeletedAtIsNull(id).orElseThrow(() -> ApiException.notFound("Spending entry")); }
    private void requireVersion(long actual, Long requested) {
        if (requested == null) throw ApiException.badRequest("version is required");
        if (actual != requested.longValue()) throw ApiException.conflict("The record was changed on another device");
    }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private SpendingResponse map(SpendingEntry e) { return new SpendingResponse(e.getId(), e.getSpaceId(), e.getBudgetItemId(), e.getTransactionType(), e.getTitle(), e.getAmount(), e.getSpentAt(), e.getUserSelectedDate(), e.getUserSelectedTime(), e.getTimeZone(), e.getPaymentMethod(), e.getMerchant(), e.getNotes(), e.getSpentByUserId(), e.getCreatedByUserId(), e.getRefundForSpendingEntryId(), e.getVersion()); }
    private void record(SpendingEntry entry, SyncOperationType operation, UUID actor, boolean tombstone, Object payload) {
        changes.orderedStream().forEach(recorder -> recorder.record(entry.getSpaceId(), "SPENDING_ENTRY", entry.getId(),
                operation, entry.getVersion(), actor, tombstone, payload));
    }
}
