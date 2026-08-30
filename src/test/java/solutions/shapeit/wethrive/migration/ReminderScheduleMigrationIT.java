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
class ReminderScheduleMigrationIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.4-alpine")
            .withDatabaseName("wethrive_reminders").withUsername("wethrive").withPassword("test-password");

    @Test
    void v4AddsVersionedPerPeriodSchedulesWithOwnershipAndUniquenessGuards() throws Exception {
        Flyway throughV3 = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target("3").load();
        assertThat(throughV3.migrate().targetSchemaVersion).hasToString("3");
        seedPreference();

        Flyway throughV4 = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target("4").load();
        assertThat(throughV4.migrate().targetSchemaVersion).hasToString("4");

        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute(scheduleInsert("00000000-0000-0000-0000-000000000010",
                    "00000000-0000-0000-0000-000000000001", 2026, 9, "2026-08-20", "08:30"));
            try (var row = statement.executeQuery("""
                    select target_year,target_month,initial_reminder_date,local_time,time_zone,
                           repeat_interval_days,enabled,next_run_at,version
                      from budget_reminder_schedules
                    """)) {
                assertThat(row.next()).isTrue();
                assertThat(row.getInt("target_year")).isEqualTo(2026);
                assertThat(row.getInt("target_month")).isEqualTo(9);
                assertThat(row.getDate("initial_reminder_date").toLocalDate()).hasToString("2026-08-20");
                assertThat(row.getTime("local_time").toLocalTime()).hasToString("08:30");
                assertThat(row.getString("time_zone")).isEqualTo("Africa/Johannesburg");
                assertThat(row.getInt("repeat_interval_days")).isEqualTo(1);
                assertThat(row.getBoolean("enabled")).isTrue();
                assertThat(row.getLong("version")).isZero();
            }
            assertThatThrownBy(() -> statement.execute(scheduleInsert(
                    "00000000-0000-0000-0000-000000000011",
                    "00000000-0000-0000-0000-000000000001", 2026, 9, "2026-08-25", "18:00")))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.execute(scheduleInsert(
                    "00000000-0000-0000-0000-000000000012",
                    "00000000-0000-0000-0000-000000000099", 2026, 10, "2026-09-25", "18:00")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("same user, space, and monthly preference");
        }
        assertThat(throughV4.migrate().migrationsExecuted).isZero();
    }

    private void seedPreference() throws Exception {
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("""
                    insert into app_users(id,email,normalized_email,password_hash,display_name,created_at,updated_at,version)
                    values
                      ('00000000-0000-0000-0000-000000000001','owner@example.test','owner@example.test','hash','Owner',now(),now(),0),
                      ('00000000-0000-0000-0000-000000000099','other@example.test','other@example.test','hash','Other',now(),now(),0);
                    insert into spaces(id,type,name,slug,owner_user_id,currency_code,locale,time_zone,created_at,updated_at,version)
                    values ('00000000-0000-0000-0000-000000000002','PERSONAL','Personal','personal-reminder-test',
                            '00000000-0000-0000-0000-000000000001','ZAR','en-ZA','Africa/Johannesburg',now(),now(),0);
                    insert into reminder_preferences(
                        id,user_id,space_id,reminder_type,enabled,frequency_type,local_time,time_zone,
                        repeat_until_completed,next_run_at,created_at,updated_at,version)
                    values ('00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000001',
                            '00000000-0000-0000-0000-000000000002','MONTHLY_BUDGET_SETUP',true,'MONTHLY',
                            time '09:00','Africa/Johannesburg',true,'2026-08-25T07:00:00Z',now(),now(),0);
                    """);
        }
    }

    private String scheduleInsert(String id, String userId, int year, int month, String date, String time) {
        return """
                insert into budget_reminder_schedules(
                    id,reminder_preference_id,user_id,space_id,target_year,target_month,initial_reminder_date,
                    local_time,time_zone,repeat_until_budget_exists,repeat_interval_days,enabled,next_run_at,
                    created_by_user_id,updated_by_user_id,created_at,updated_at,version)
                values ('%s','00000000-0000-0000-0000-000000000003','%s',
                        '00000000-0000-0000-0000-000000000002',%d,%d,date '%s',time '%s',
                        'Africa/Johannesburg',true,1,true,'2026-08-20T06:30:00Z',
                        '00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001',
                        now(),now(),0)
                """.formatted(id, userId, year, month, date, time);
    }
}
