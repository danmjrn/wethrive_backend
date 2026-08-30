package solutions.shapeit.wethrive.finance.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import solutions.shapeit.wethrive.finance.entity.Category;

public interface CategoryRepository extends JpaRepository<Category, UUID> {
    List<Category> findAllBySpaceIdAndDeletedAtIsNullOrderBySortOrderAscNameAsc(UUID spaceId);
    Optional<Category> findByIdAndSpaceIdAndDeletedAtIsNull(UUID id, UUID spaceId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select category from Category category where category.id = :id and category.deletedAt is null")
    Optional<Category> findForUpdateById(@Param("id") UUID id);
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select category from Category category where category.id = :id and category.spaceId = :spaceId and category.deletedAt is null")
    Optional<Category> findUsableForReference(@Param("id") UUID id, @Param("spaceId") UUID spaceId);
    boolean existsBySpaceIdAndNameIgnoreCase(UUID spaceId, String name);
    boolean existsBySpaceIdAndNameIgnoreCaseAndDeletedAtIsNull(UUID spaceId, String name);
}
