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
import solutions.shapeit.wethrive.finance.entity.BudgetFundingAllocation;

public interface BudgetFundingAllocationRepository extends JpaRepository<BudgetFundingAllocation, UUID> {
    interface LockScope {
        UUID getBudgetItemId();
        UUID getIncomeEntryId();
    }
    List<BudgetFundingAllocation> findAllByBudgetItemIdAndDeletedAtIsNullOrderByCreatedAtAsc(UUID budgetItemId);
    List<BudgetFundingAllocation> findAllByIncomeEntryIdAndDeletedAtIsNullOrderByCreatedAtAsc(UUID incomeEntryId);
    List<BudgetFundingAllocation> findAllByBudgetItemIdOrderByCreatedAtAsc(UUID budgetItemId);
    List<BudgetFundingAllocation> findAllByIncomeEntryIdOrderByCreatedAtAsc(UUID incomeEntryId);
    List<BudgetFundingAllocation> findAllBySpaceIdOrderByCreatedAtAsc(UUID spaceId);
    List<BudgetFundingAllocation> findAllByBudgetItemIdInAndDeletedAtIsNull(Collection<UUID> budgetItemIds);
    List<BudgetFundingAllocation> findAllByBudgetItemIdInOrderByCreatedAtAsc(Collection<UUID> budgetItemIds);
    boolean existsByBudgetItemId(UUID budgetItemId);
    long countByBudgetItemIdIn(Collection<UUID> budgetItemIds);
    long countBySpaceIdIn(Collection<UUID> spaceIds);
    List<BudgetFundingAllocation> findAllByIncomeEntryIdInAndDeletedAtIsNull(Collection<UUID> incomeEntryIds);
    Optional<BudgetFundingAllocation> findByIdAndDeletedAtIsNull(UUID id);
    @Query("""
            select allocation.budgetItemId as budgetItemId, allocation.incomeEntryId as incomeEntryId
            from BudgetFundingAllocation allocation
            where allocation.id = :id and allocation.deletedAt is null
            """)
    Optional<LockScope> findLockScopeById(@Param("id") UUID id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select allocation from BudgetFundingAllocation allocation where allocation.id = :id and allocation.deletedAt is null")
    Optional<BudgetFundingAllocation> findForUpdateById(@Param("id") UUID id);
}
