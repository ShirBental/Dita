package app.dita.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration;

/**
 * Data sources are created only by PostgresRuntimeConfiguration from typed dita.storage settings. Spring Boot's JDBC
 * auto-configuration is excluded, and Flyway/SQL init are excluded so the application NEVER runs migrations or
 * scripts against a database (migrations are applied explicitly by scripts/e2e-verify.sh as the schema owner).
 */
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class, FlywayAutoConfiguration.class, SqlInitializationAutoConfiguration.class})
public class DitaApplication {
    public static void main(String[] args) { SpringApplication.run(DitaApplication.class, args); }
}
