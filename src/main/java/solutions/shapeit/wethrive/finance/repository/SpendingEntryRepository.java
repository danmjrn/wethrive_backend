package solutions.shapeit.wethrive.finance.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import solutions.shapeit.wethrive.finance.entity.SpendingEntry;

public interface SpendingEntryRepository extends JpaRepository<SpendingEntry, UUID> {
    List<SpendingEntry> findAllByBudgetItemIdAndDeletedAtIsNullOrderBySpentAtDesc(UUID budgetItemId);
    List<SpendingEntry> findAllByBudgetItemIdInAndDeletedAtIsNull(Collection<UUID> budgetItemIds);
    List<SpendingEntry> findAllByRefundForSpendingEntryIdAndDeletedAtIsNullOrderBySpentAtAscCreatedAtAsc(UUID originalId);
    boolean existsByBudgetItemId(UUID budgetItemId);
    Optional<SpendingEntry> findByIdAndDeletedAtIsNull(UUID id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select entry from SpendingEntry entry where entry.id = :id and entry.deletedAt is null")
    Optional<SpendingEntry> findForUpdateById(@Param("id") UUID id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select entry from SpendingEntry entry where entry.id = :id")
    Optional<SpendingEntry> findAnyForUpdateById(@Param("id") UUID id);
    Page<SpendingEntry> findAllBySpaceIdInAndDeletedAtIsNull(Collection<UUID> spaceIds, Pageable pageable);
    long countBySpaceIdInAndDeletedAtIsNull(Collection<UUID> spaceIds);
    Page<SpendingEntry> findAllBySpaceIdAndSpentAtBetweenAndDeletedAtIsNull(UUID spaceId, Instant from, Instant to, Pageable pageable);
    boolean existsBySpaceIdAndSpentAtBetweenAndDeletedAtIsNull(UUID spaceId, Instant from, Instant to);
    boolean existsByCreatedByUserIdAndSpentAtBetweenAndDeletedAtIsNull(UUID userId, Instant from, Instant to);
}
