package solutions.shapeit.wethrive.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class UserViewPreferenceMigrationIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.4-alpine")
            .withDatabaseName("wethrive_view_preference").withUsername("wethrive").withPassword("test-password");

    @Test
    void v5DefaultsExistingAccountsToCardsAndConstrainsFutureValues() throws Exception {
        Flyway throughV4 = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target("4").load();
        assertThat(throughV4.migrate().targetSchemaVersion).hasToString("4");
        seedSettings();

        Flyway throughV5 = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target("5").load();
        assertThat(throughV5.migrate().targetSchemaVersion).hasToString("5");

        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            try (var row = statement.executeQuery("select budget_item_view from user_settings")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isEqualTo("CARDS");
            }
            statement.execute("update user_settings set budget_item_view = 'ROWS'");
            assertThatThrownBy(() -> statement.execute("update user_settings set budget_item_view = 'GRID'"))
                    .isInstanceOf(SQLException.class);
        }
        assertThat(throughV5.migrate().migrationsExecuted).isZero();
    }

    private void seedSettings() throws Exception {
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("""
                    insert into app_users(id,email,normalized_email,password_hash,display_name,created_at,updated_at,version)
                    values ('00000000-0000-0000-0000-000000000001','owner@example.test','owner@example.test','hash','Owner',now(),now(),0);
                    insert into user_settings(id,user_id,currency_code,locale,time_zone,week_start_day,
                        default_tithe_enabled,default_tithe_rate,warning_threshold,critical_threshold,theme,
                        notifications_enabled,detailed_notifications_enabled,auto_lock_minutes,onboarding_complete,
                        created_at,updated_at,version)
                    values ('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000001',
                        'ZAR','en-ZA','Africa/Johannesburg',1,false,0.1000,0.7500,0.9000,'SYSTEM',false,false,5,false,
                        now(),now(),0);
                    """);
        }
    }
}
