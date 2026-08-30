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
import solutions.shapeit.wethrive.finance.entity.IncomeReceiptDeduction;

public interface IncomeReceiptDeductionRepository extends JpaRepository<IncomeReceiptDeduction, UUID> {
    List<IncomeReceiptDeduction> findAllByIncomeReceiptIdAndDeletedAtIsNullOrderByCreatedAtAsc(UUID incomeReceiptId);
    List<IncomeReceiptDeduction> findAllByIncomeReceiptIdOrderByCreatedAtAsc(UUID incomeReceiptId);
    List<IncomeReceiptDeduction> findAllByIncomeReceiptIdInAndDeletedAtIsNull(Collection<UUID> incomeReceiptIds);
    List<IncomeReceiptDeduction> findAllByIncomeReceiptIdIn(Collection<UUID> incomeReceiptIds);
    long countByIncomeReceiptIdIn(Collection<UUID> incomeReceiptIds);
    List<IncomeReceiptDeduction> findAllBySpaceIdOrderByCreatedAtAsc(UUID spaceId);
    long countBySpaceId(UUID spaceId);
    long countBySpaceIdIn(Collection<UUID> spaceIds);
    Optional<IncomeReceiptDeduction> findByIdAndDeletedAtIsNull(UUID id);
    Optional<IncomeReceiptDeduction> findByIncomeReceiptIdAndIncomeDeductionIdAndDeletedAtIsNull(
            UUID incomeReceiptId, UUID incomeDeductionId);

    @Query("select deduction.incomeReceiptId from IncomeReceiptDeduction deduction where deduction.id = :id and deduction.deletedAt is null")
    Optional<UUID> findIncomeReceiptIdById(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select deduction from IncomeReceiptDeduction deduction where deduction.id = :id and deduction.deletedAt is null")
    Optional<IncomeReceiptDeduction> findForUpdateById(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select deduction from IncomeReceiptDeduction deduction where deduction.incomeReceiptId = :receiptId and deduction.deletedAt is null order by deduction.id")
    List<IncomeReceiptDeduction> findAllForUpdateByIncomeReceiptId(@Param("receiptId") UUID receiptId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select deduction from IncomeReceiptDeduction deduction where deduction.incomeReceiptId in :receiptIds and deduction.deletedAt is null order by deduction.id")
    List<IncomeReceiptDeduction> findAllForUpdateByIncomeReceiptIdIn(@Param("receiptIds") Collection<UUID> receiptIds);
}
