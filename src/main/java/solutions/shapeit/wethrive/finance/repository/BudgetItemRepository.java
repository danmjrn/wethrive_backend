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
import solutions.shapeit.wethrive.finance.entity.BudgetItem;

public interface BudgetItemRepository extends JpaRepository<BudgetItem, UUID> {
    List<BudgetItem> findAllByBudgetMonthIdAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(UUID budgetMonthId);
    List<BudgetItem> findAllByBudgetMonthIdOrderBySortOrderAscCreatedAtAsc(UUID budgetMonthId);
    List<BudgetItem> findAllByBudgetMonthIdInAndDeletedAtIsNull(Collection<UUID> budgetMonthIds);
    List<BudgetItem> findAllByIdInAndSpaceIdInAndDeletedAtIsNull(
            Collection<UUID> ids, Collection<UUID> spaceIds);
    long countByBudgetMonthIdInAndDeletedAtIsNull(Collection<UUID> budgetMonthIds);
    long countBySpaceIdInAndDeletedAtIsNull(Collection<UUID> spaceIds);
    Optional<BudgetItem> findByIdAndDeletedAtIsNull(UUID id);
    @Query("select item.budgetMonthId from BudgetItem item where item.id = :id and item.deletedAt is null")
    Optional<UUID> findBudgetMonthIdById(@Param("id") UUID id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select item from BudgetItem item where item.id = :id and item.deletedAt is null")
    Optional<BudgetItem> findForUpdateById(@Param("id") UUID id);
}
