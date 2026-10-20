package solutions.shapeit.wethrive.identity.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import solutions.shapeit.wethrive.identity.entity.ApplicationInitialization;

public interface ApplicationInitializationRepository extends JpaRepository<ApplicationInitialization, Integer> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select initialization from ApplicationInitialization initialization where initialization.id = 1")
    Optional<ApplicationInitialization> lockState();
}
