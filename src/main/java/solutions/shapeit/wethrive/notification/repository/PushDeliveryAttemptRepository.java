package solutions.shapeit.wethrive.notification.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import solutions.shapeit.wethrive.notification.entity.PushDeliveryAttempt;

public interface PushDeliveryAttemptRepository extends JpaRepository<PushDeliveryAttempt, UUID> {
    boolean existsByIdempotencyKey(String idempotencyKey);
    @Query(value = "select * from push_delivery_attempts where status = 'RETRY_PENDING' and next_attempt_at <= :now " +
            "order by next_attempt_at for update skip locked limit :batchSize", nativeQuery = true)
    List<PushDeliveryAttempt> lockRetries(@Param("now") Instant now, @Param("batchSize") int batchSize);
}
