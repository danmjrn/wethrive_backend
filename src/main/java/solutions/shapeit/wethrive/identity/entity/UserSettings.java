package solutions.shapeit.wethrive.identity.entity;

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
import solutions.shapeit.wethrive.common.domain.DomainEnums.BudgetItemView;
import solutions.shapeit.wethrive.common.domain.DomainEnums.Palette;
import solutions.shapeit.wethrive.common.domain.DomainEnums.Theme;
import solutions.shapeit.wethrive.common.model.BaseEntity;

@Entity
@Table(name = "user_settings")
@Getter
@Setter
@NoArgsConstructor
public class UserSettings extends BaseEntity {
    @Column(nullable = false, unique = true)
    private UUID userId;

    @Column(nullable = false, length = 3)
    private String currencyCode;

    @Column(nullable = false, length = 35)
    private String locale;

    @Column(nullable = false, length = 60)
    private String timeZone;

    @Column(nullable = false)
    private int weekStartDay = 1;

    @Column(nullable = false)
    private boolean defaultTitheEnabled;

    @Column(nullable = false, precision = 7, scale = 4)
    private BigDecimal defaultTitheRate = new BigDecimal("0.1000");

    @Column(nullable = false, precision = 7, scale = 4)
    private BigDecimal warningThreshold = new BigDecimal("0.7500");

    @Column(nullable = false, precision = 7, scale = 4)
    private BigDecimal criticalThreshold = new BigDecimal("0.9000");

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Theme theme = Theme.SYSTEM;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Palette palette = Palette.ORIGINAL;

    @Column(nullable = false)
    private boolean notificationsEnabled;

    @Column(nullable = false)
    private boolean detailedNotificationsEnabled;

    private UUID defaultSpaceId;

    @Column(nullable = false)
    private int autoLockMinutes = 5;

    @Column(nullable = false)
    private boolean onboardingComplete;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private BudgetItemView budgetItemView = BudgetItemView.CARDS;
}
