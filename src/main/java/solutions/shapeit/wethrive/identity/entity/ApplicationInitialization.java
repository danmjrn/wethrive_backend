package solutions.shapeit.wethrive.identity.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "application_initialization")
@Getter
@Setter
public class ApplicationInitialization {
    @Id private Integer id;
    private Instant completedAt;
    private UUID ownerUserId;
}
