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
import solutions.shapeit.wethrive.finance.entity.IncomeReceipt;

public interface IncomeReceiptRepository extends JpaRepository<IncomeReceipt, UUID> {
    List<IncomeReceipt> findAllByIncomeEntryIdAndDeletedAtIsNullOrderByReceivedAtAscCreatedAtAsc(UUID incomeEntryId);
    List<IncomeReceipt> findAllByIncomeEntryIdOrderByReceivedAtAscCreatedAtAsc(UUID incomeEntryId);
    List<IncomeReceipt> findAllByIncomeEntryIdIn(Collection<UUID> incomeEntryIds);
    List<IncomeReceipt> findAllByIncomeEntryIdInAndDeletedAtIsNull(Collection<UUID> incomeEntryIds);
    long countByIncomeEntryIdIn(Collection<UUID> incomeEntryIds);
    List<IncomeReceipt> findAllBySpaceIdOrderByReceivedAtAscCreatedAtAsc(UUID spaceId);
    long countBySpaceId(UUID spaceId);
    long countBySpaceIdIn(Collection<UUID> spaceIds);
    Optional<IncomeReceipt> findByIdAndDeletedAtIsNull(UUID id);
    @Query("select receipt.incomeEntryId from IncomeReceipt receipt where receipt.id = :id and receipt.deletedAt is null")
    Optional<UUID> findIncomeEntryIdById(@Param("id") UUID id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select receipt from IncomeReceipt receipt where receipt.id = :id and receipt.deletedAt is null")
    Optional<IncomeReceipt> findForUpdateById(@Param("id") UUID id);
}
