package solutions.shapeit.wethrive.finance.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import solutions.shapeit.wethrive.common.model.SpaceOwnedEntity;

/** A user-reviewed deduction amount realized against one income receipt. */
@Entity
@Table(name = "income_receipt_deductions")
@Getter @Setter @NoArgsConstructor
public class IncomeReceiptDeduction extends SpaceOwnedEntity {
    @Column(nullable = false) private UUID incomeReceiptId;
    @Column(nullable = false) private UUID incomeDeductionId;
    @Column(nullable = false, length = 160) private String nameSnapshot;
    @Column(nullable = false, precision = 19, scale = 2) private BigDecimal actualAmount;
}
