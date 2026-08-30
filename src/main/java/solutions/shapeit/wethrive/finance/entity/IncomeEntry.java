package solutions.shapeit.wethrive.finance.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import solutions.shapeit.wethrive.common.model.SpaceOwnedEntity;

@Entity
@Table(name = "income_entries")
@Getter @Setter @NoArgsConstructor
public class IncomeEntry extends SpaceOwnedEntity {
    @Column(nullable = false) private UUID budgetMonthId;
    @Column(nullable = false, length = 160) private String sourceName;
    @Column(nullable = false) private UUID incomeTypeId;
    @Column(precision = 19, scale = 2) private BigDecimal expectedAmount;
    /** Retained only for migration compatibility. New code derives receipts from IncomeReceipt rows. */
    @Deprecated @Column(precision = 19, scale = 2) private BigDecimal actualAmount;
    @Column(nullable = false) private LocalDate expectedDate;
    private LocalTime expectedTime;
    @Column(nullable = false, length = 60) private String timeZone;
    @Column(length = 300) private String recurrenceRule;
    private Instant cancelledAt;
    @Column(nullable = false) private boolean titheEnabled;
    @Column(nullable = false, precision = 7, scale = 4) private BigDecimal titheRate;
    @Column(precision = 19, scale = 2) private BigDecimal titheAmountOverride;
    @Column(nullable = false) private boolean recurring;
    @Column(length = 2000) private String notes;
    @Column(nullable = false) private int sortOrder;
}
