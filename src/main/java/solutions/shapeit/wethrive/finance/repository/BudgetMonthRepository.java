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
import solutions.shapeit.wethrive.finance.entity.BudgetMonth;

public interface BudgetMonthRepository extends JpaRepository<BudgetMonth, UUID> {
    List<BudgetMonth> findAllBySpaceIdAndDeletedAtIsNullOrderByYearDescMonthDesc(UUID spaceId);
    List<BudgetMonth> findAllBySpaceIdAndYearAndMonthBetweenAndDeletedAtIsNullOrderByYearDescMonthDesc(
            UUID spaceId, int year, int firstMonth, int lastMonth);
    long countBySpaceIdInAndDeletedAtIsNull(Collection<UUID> spaceIds);
    Optional<BudgetMonth> findByIdAndDeletedAtIsNull(UUID id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select budget from BudgetMonth budget where budget.id = :id and budget.deletedAt is null")
    Optional<BudgetMonth> findForUpdateById(@Param("id") UUID id);
    Optional<BudgetMonth> findBySpaceIdAndYearAndMonthAndDeletedAtIsNull(UUID spaceId, int year, int month);
    boolean existsBySpaceIdAndYearAndMonthAndDeletedAtIsNull(UUID spaceId, int year, int month);
}
