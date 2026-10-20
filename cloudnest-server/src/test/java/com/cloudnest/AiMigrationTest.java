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
        "12",
        "22");
  }

  @Test
  void mysqlExistingSchemaUpgradesWithoutChangingAccounts() throws Exception {
    String url = System.getenv("CN_AI_TEST_DATABASE_URL");
    Assumptions.assumeTrue(
        url != null, "Set CN_AI_TEST_DATABASE_URL to a local disposable MySQL test database");
    upgrade(url, "root", "mysql", "13", "22");
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
          statement.executeUpdate(
              "INSERT INTO"
                  + " storage_policy(id,name,endpoint,bucket,access_key,secret_key,region,enabled)"
                  + " VALUES('abcdef123456','old"
                  + " disabled','http://old.internal:3900','cloudnest','fixture','fixture','garage',FALSE)");
          statement.executeUpdate(
              "INSERT INTO"
                  + " file_blob(id,user_id,object_key,backend,size,sha256,mime,status,created_at)"
                  + " VALUES('old-blob','upgrade-user','objects/00000000-0000-0000-0000-000000000000','s3:abcdef123456',1,'"
                  + "a".repeat(64)
                  + "','text/plain','READY',1)");
          statement.executeUpdate(
              "INSERT INTO"
                  + " upload_session(id,user_id,folder_id,name,size,sha256,part_size,part_count,status,object_key,backend,created_at,expires_at)"
                  + " VALUES('old-upload','upgrade-user','fixture-root','old',5,'"
                  + "b".repeat(64)
                  + "',1024,1,'UPLOADING','objects/11111111-1111-1111-1111-111111111111','unknown',1,9999999999999)");
          statement.executeUpdate(
              "INSERT INTO"
                  + " ai_connection(id,user_id,name,base_url,enabled,config_version,created_at,updated_at)"
                  + " VALUES('connection','upgrade-user','old"
                  + " connection','https://model.example/v1',FALSE,1,1,1)");
          statement.executeUpdate(
              "INSERT INTO"
                  + " ai_model(id,user_id,connection_id,name,model_id,context_limit,max_output,output_param_mode,enabled,config_version)"
                  + " VALUES('model','upgrade-user','connection','old"
                  + " chat','fixture',32768,1024,'max_tokens',TRUE,1)");
          statement.executeUpdate(
              "INSERT INTO"
                  + " ai_task(id,user_id,kind,model_id,connection_id,model_version,connection_version,model_name,status,stage,request_hash,idempotency_key,cache_key,request_json,created_at,updated_at,expires_at)"
                  + " VALUES('existing-task','upgrade-user','SUMMARY','model','connection',1,1,'fixture','SUCCEEDED','FINISHED','hash','old-key','cache','{}',1,1,9999999999999)");
        }
      }
      latest.migrate();
      latest.validate();
      try (var c = DriverManager.getConnection(url, user, "")) {
        if (vendor.equals("mysql")) c.setCatalog(schema);
        else c.setSchema(schema);
        try (var statement = c.createStatement();
            var row =
                statement.executeQuery("SELECT state FROM blob_replica WHERE blob_id='old-blob'")) {
          assertTrue(row.next());
          assertEquals("UNVERIFIED", row.getString(1));
        }
      }
      assertEquals(0, latest.migrate().migrationsExecuted);
      assertEquals(current, latest.info().current().getVersion().toString());
      try (var connection = DriverManager.getConnection(url, user, "")) {
        if (vendor.equals("mysql")) connection.setCatalog(schema);
        else connection.setSchema(schema);
        try (var statement = connection.createStatement()) {
          try (var row = statement.executeQuery("SELECT COUNT(*) FROM storage_node")) {
            row.next();
            assertEquals(3, row.getInt(1));
          }
          try (var row =
              statement.executeQuery(
                  "SELECT enabled FROM storage_node WHERE backend='s3:abcdef123456'")) {
            row.next();
            assertFalse(row.getBoolean(1));
          }
          try (var row =
              statement.executeQuery("SELECT backend FROM file_blob WHERE id='old-blob'")) {
            row.next();
            assertEquals("s3:abcdef123456", row.getString(1));
          }
          try (var row =
              statement.executeQuery(
                  "SELECT backend,temp_bytes FROM storage_reservation WHERE id='old-upload'")) {
            row.next();
            assertEquals("unknown", row.getString(1));
            assertEquals(15, row.getLong(2));
          }
          try (var row =
              statement.executeQuery("SELECT COUNT(*) FROM sys_user WHERE id='upgrade-user'")) {
            row.next();
            assertEquals(1, row.getInt(1));
          }
          try (var row =
              statement.executeQuery(
                  "SELECT request_json,conversation_id FROM ai_task WHERE id='existing-task'")) {
            assertTrue(row.next());
            assertEquals("{}", row.getString(1));
            assertNull(row.getString(2));
          }
          try (var row =
              statement.executeQuery(
                  "SELECT purpose,embedding_dimensions,last_test_status FROM ai_model WHERE"
                      + " id='model'")) {
            assertTrue(row.next());
            assertEquals("CHAT", row.getString(1));
            assertNull(row.getObject(2));
            assertEquals("UNTESTED", row.getString(3));
          }
          try (var row = statement.executeQuery("SELECT COUNT(*) FROM ai_conversation")) {
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
