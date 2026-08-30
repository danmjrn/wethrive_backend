package solutions.shapeit.wethrive.finance.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import solutions.shapeit.wethrive.finance.entity.IncomeType;

public interface IncomeTypeRepository extends JpaRepository<IncomeType, UUID> {
    List<IncomeType> findAllBySpaceIdAndDeletedAtIsNullOrderBySortOrderAscNameAsc(UUID spaceId);
    Optional<IncomeType> findByIdAndSpaceIdAndDeletedAtIsNull(UUID id, UUID spaceId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select type from IncomeType type where type.id = :id and type.deletedAt is null")
    Optional<IncomeType> findForUpdateById(@Param("id") UUID id);
    boolean existsBySpaceIdAndNameIgnoreCase(UUID spaceId, String name);
    boolean existsBySpaceIdAndNameIgnoreCaseAndDeletedAtIsNull(UUID spaceId, String name);
}
