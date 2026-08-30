package solutions.shapeit.wethrive.finance.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import solutions.shapeit.wethrive.common.model.SpaceOwnedEntity;

/** A received tranche of a scheduled income entry. Totals are always derived from live receipts. */
@Entity
@Table(name = "income_receipts")
@Getter @Setter @NoArgsConstructor
public class IncomeReceipt extends SpaceOwnedEntity {
    @Column(nullable = false) private UUID incomeEntryId;
    @Column(nullable = false, precision = 19, scale = 2) private BigDecimal amount;
    @Column(nullable = false) private Instant receivedAt;
    @Column(nullable = false, length = 60) private String timeZone;
    @Column(length = 2000) private String notes;
    @Column(nullable = false) private UUID recordedByUserId;
}
