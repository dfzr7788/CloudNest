package com.cloudnest;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;

class AiMigrationTest {
  @Test
  void h2ExistingSchemaUpgradesWithoutChangingAccounts() throws Exception {
    upgrade(
        "jdbc:h2:mem:aiupgrade;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "sa",
        "h2",
        "10",
        "11");
  }

  @Test
  void mysqlExistingSchemaUpgradesWithoutChangingAccounts() throws Exception {
    String url = System.getenv("CN_AI_TEST_DATABASE_URL");
    Assumptions.assumeTrue(
        url != null, "Set CN_AI_TEST_DATABASE_URL to a local disposable MySQL test database");
    upgrade(url, "root", "mysql", "11", "12");
  }

  private void upgrade(String url, String user, String vendor, String previous, String current)
      throws Exception {
    // The schema is generated here, never taken from user input. clean() touches only this schema.
    String schema = "ai_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    Flyway old =
        Flyway.configure()
            .dataSource(url, user, "")
            .locations("classpath:db/migration/" + vendor)
            .schemas(schema)
            .defaultSchema(schema)
            .target(previous)
            .load();
    Flyway latest =
        Flyway.configure()
            .dataSource(url, user, "")
            .locations("classpath:db/migration/" + vendor)
            .schemas(schema)
            .defaultSchema(schema)
            .cleanDisabled(false)
            .load();
    try {
      old.migrate();
      try (var connection = DriverManager.getConnection(url, user, "")) {
        if (vendor.equals("mysql")) connection.setCatalog(schema);
        else connection.setSchema(schema);
        try (var statement = connection.createStatement()) {
          statement.executeUpdate(
              "INSERT INTO"
                  + " sys_user(id,username,password_hash,nickname,root_id,storage_quota,created_at)"
                  + " VALUES('upgrade-user','upgrade-user','fixture','fixture','fixture-root',1024,1)");
        }
      }
      latest.migrate();
      latest.validate();
      assertEquals(current, latest.info().current().getVersion().toString());
      try (var connection = DriverManager.getConnection(url, user, "")) {
        if (vendor.equals("mysql")) connection.setCatalog(schema);
        else connection.setSchema(schema);
        try (var statement = connection.createStatement()) {
          try (var row =
              statement.executeQuery("SELECT COUNT(*) FROM sys_user WHERE id='upgrade-user'")) {
            row.next();
            assertEquals(1, row.getInt(1));
          }
          try (var row = statement.executeQuery("SELECT COUNT(*) FROM ai_task")) {
            row.next();
            assertEquals(0, row.getInt(1));
          }
        }
      }
    } finally {
      latest.clean();
    }
  }
}
