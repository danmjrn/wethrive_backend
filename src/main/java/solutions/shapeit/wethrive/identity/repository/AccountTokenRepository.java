package solutions.shapeit.wethrive.identity.repository;

import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import solutions.shapeit.wethrive.common.domain.DomainEnums.AccountTokenType;
import solutions.shapeit.wethrive.identity.entity.AccountToken;

public interface AccountTokenRepository extends JpaRepository<AccountToken, UUID> {
    Optional<AccountToken> findByTokenHashAndTypeAndConsumedAtIsNull(String hash, AccountTokenType type);
    List<AccountToken> findAllByUserIdAndConsumedAtIsNull(UUID userId);
    List<AccountToken> findAllByUserIdAndTypeAndConsumedAtIsNull(UUID userId, AccountTokenType type);
}
