package solutions.shapeit.wethrive.notification.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import solutions.shapeit.wethrive.notification.entity.PushSubscription;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, UUID> {
    List<PushSubscription> findAllByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(UUID userId);
    Optional<PushSubscription> findByEndpointHash(String endpointHash);
    Optional<PushSubscription> findByIdAndUserIdAndRevokedAtIsNull(UUID id, UUID userId);
    List<PushSubscription> findAllByDeviceIdAndRevokedAtIsNull(UUID deviceId);
}
