package solutions.shapeit.wethrive.finance.repository;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import solutions.shapeit.wethrive.finance.entity.IncomeDeduction;

public interface IncomeDeductionRepository extends JpaRepository<IncomeDeduction, UUID> {
    List<IncomeDeduction> findAllByIncomeEntryIdAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(UUID incomeEntryId);
    List<IncomeDeduction> findAllByIncomeEntryIdOrderBySortOrderAscCreatedAtAsc(UUID incomeEntryId);
    List<IncomeDeduction> findAllByIncomeEntryIdIn(Collection<UUID> incomeEntryIds);
    List<IncomeDeduction> findAllByIncomeEntryIdInAndDeletedAtIsNull(Collection<UUID> incomeEntryIds);
    long countByIncomeEntryIdIn(Collection<UUID> incomeEntryIds);
    List<IncomeDeduction> findAllBySpaceIdOrderByCreatedAtAsc(UUID spaceId);
    long countBySpaceId(UUID spaceId);
    long countBySpaceIdIn(Collection<UUID> spaceIds);
    Optional<IncomeDeduction> findByIdAndDeletedAtIsNull(UUID id);
    Optional<IncomeDeduction> findByIncomeEntryIdAndLegacyTitheTrueAndDeletedAtIsNull(UUID incomeEntryId);

    @Query("select deduction.incomeEntryId from IncomeDeduction deduction where deduction.id = :id and deduction.deletedAt is null")
    Optional<UUID> findIncomeEntryIdById(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select deduction from IncomeDeduction deduction where deduction.id = :id and deduction.deletedAt is null")
    Optional<IncomeDeduction> findForUpdateById(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select deduction from IncomeDeduction deduction where deduction.incomeEntryId = :incomeId and deduction.deletedAt is null order by deduction.id")
    List<IncomeDeduction> findAllForUpdateByIncomeEntryId(@Param("incomeId") UUID incomeId);
}
