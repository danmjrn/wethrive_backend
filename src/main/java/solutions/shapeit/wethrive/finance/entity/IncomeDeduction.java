package solutions.shapeit.wethrive.finance.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import solutions.shapeit.wethrive.common.domain.DomainEnums.DeductionType;
import solutions.shapeit.wethrive.common.model.SpaceOwnedEntity;

/** A planned deduction attached to an income definition. */
@Entity
@Table(name = "income_deductions")
@Getter @Setter @NoArgsConstructor
public class IncomeDeduction extends SpaceOwnedEntity {
    @Column(nullable = false) private UUID incomeEntryId;
    @Column(nullable = false, length = 160) private String name;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private DeductionType deductionType;
    @Column(precision = 7, scale = 4) private BigDecimal percentageRate;
    @Column(precision = 19, scale = 2) private BigDecimal fixedAmount;
    @Column(length = 2000) private String notes;
    @Column(nullable = false) private int sortOrder;
    @Column(nullable = false) private boolean legacyTithe;
}
