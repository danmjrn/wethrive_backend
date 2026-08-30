package solutions.shapeit.wethrive.finance.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import solutions.shapeit.wethrive.common.domain.DomainEnums.TransactionType;
import solutions.shapeit.wethrive.common.model.SpaceOwnedEntity;

@Entity
@Table(name = "spending_entries")
@Getter @Setter @NoArgsConstructor
public class SpendingEntry extends SpaceOwnedEntity {
    @Column(nullable = false) private UUID budgetItemId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private TransactionType transactionType;
    @Column(nullable = false, length = 180) private String title;
    @Column(nullable = false, precision = 19, scale = 2) private BigDecimal amount;
    @Column(nullable = false) private Instant spentAt;
    @Column(nullable = false) private LocalDate userSelectedDate;
    @Column(nullable = false) private LocalTime userSelectedTime;
    @Column(nullable = false, length = 60) private String timeZone;
    @Column(length = 80) private String paymentMethod;
    @Column(length = 160) private String merchant;
    @Column(length = 2000) private String notes;
    private UUID spentByUserId;
    /** Null for expenses and legacy unlinked refunds; otherwise identifies the refunded expense. */
    private UUID refundForSpendingEntryId;
}
