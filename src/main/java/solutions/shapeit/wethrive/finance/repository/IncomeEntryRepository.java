package solutions.shapeit.wethrive.finance.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import solutions.shapeit.wethrive.finance.entity.IncomeEntry;

public interface IncomeEntryRepository extends JpaRepository<IncomeEntry, UUID> {
    List<IncomeEntry> findAllByBudgetMonthIdAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(UUID budgetMonthId);
    List<IncomeEntry> findAllByBudgetMonthIdOrderBySortOrderAscCreatedAtAsc(UUID budgetMonthId);
    List<IncomeEntry> findAllByBudgetMonthIdInAndDeletedAtIsNull(Collection<UUID> budgetMonthIds);
    long countByBudgetMonthIdInAndDeletedAtIsNull(Collection<UUID> budgetMonthIds);
    long countBySpaceIdInAndDeletedAtIsNull(Collection<UUID> spaceIds);
    Optional<IncomeEntry> findByIdAndDeletedAtIsNull(UUID id);

    /** Serialization point for receipt, deduction, and confirmed-allocation mutations. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select entry from IncomeEntry entry where entry.id = :id and entry.deletedAt is null")
    Optional<IncomeEntry> findForUpdateById(@Param("id") UUID id);
}
