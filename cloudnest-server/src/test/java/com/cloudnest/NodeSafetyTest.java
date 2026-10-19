package com.cloudnest;

import static com.cloudnest.common.Db.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudnest.common.*;
import com.cloudnest.file.DirectDownloadService;
import com.cloudnest.node.*;
import com.cloudnest.storage.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:nodes;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "cloudnest.index-enabled=false"
    })
class NodeSafetyTest {
  static Path data;

  static {
    try {
      data = Files.createTempDirectory("cloudnest-nodes-");
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("cloudnest.data-dir", () -> data.toString());
    String url = System.getenv("CN_NODE_TEST_DATABASE_URL");
    if (url != null) {
      r.add("spring.datasource.url", () -> url);
      r.add("spring.datasource.username", () -> "root");
      r.add("spring.datasource.password", () -> "");
    }
  }

  @Autowired Db db;
  @Autowired NodeService nodes;
  @Autowired DirectDownloadService direct;
  @MockitoSpyBean ObjectStorage storage;

  @BeforeEach
  void resetState() {
    reset(storage);
    db.update("DELETE FROM storage_reservation");
    db.update("DELETE FROM storage_copy");
    db.update(
        "UPDATE storage_node SET"
            + " budget_bytes=0,enabled=TRUE,management='ACTIVE',allow_upload=TRUE,allow_migration=TRUE"
            + " WHERE backend='local'");
    nodes.check("local", true);
    nodes.check("local", true);
  }

  @Test
  void concurrentBudgetReservationCannotOversubscribe() throws Exception {
    db.update("UPDATE storage_node SET budget_bytes=100 WHERE backend='local'");
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      List<Future<Boolean>> jobs = new ArrayList<>();
      for (int i = 0; i < 2; i++)
        jobs.add(
            pool.submit(
                () -> {
                  start.await();
                  try {
                    return nodes.locked(
                        () -> {
                          nodes.admit("local", false, 70);
                          nodes.reserve(Util.id(), "local", 70, 0);
                          return true;
                        });
                  } catch (Problem denied) {
                    assertEquals("NODE_CAPACITY", denied.code);
                    return false;
                  }
                }));
      start.countDown();
      int successes = 0;
      for (var j : jobs) if (j.get(10, TimeUnit.SECONDS)) successes++;
      assertEquals(1, successes);
      assertEquals(70, nodes.reserved("local"));
    }
  }

  @Test
  void healthFailuresRecoveryStalenessAndManagementAreIndependent() throws Exception {
    db.update("UPDATE storage_node SET management='MAINTENANCE' WHERE backend='local'");
    doThrow(new IOException("secret provider details")).when(storage).probe("local");
    assertEquals("DEGRADED", nodes.check("local", false).get("state"));
    nodes.check("local", false);
    assertEquals("OFFLINE", nodes.check("local", false).get("state"));
    doCallRealMethod().when(storage).probe("local");
    assertEquals("DEGRADED", nodes.check("local", false).get("state"));
    assertEquals("ONLINE", nodes.check("local", false).get("state"));
    assertEquals("MAINTENANCE", s(nodes.node("local"), "management"));
    assertThrows(Problem.class, () -> nodes.admit("local", false, 0));
    db.update("UPDATE storage_node SET management='ACTIVE' WHERE backend='local'");
    db.update("UPDATE storage_node_health SET checked_at=1 WHERE backend='local'");
    assertThrows(Problem.class, () -> nodes.admit("local", false, 0));
  }

  @Test
  void reservationRollbackReleaseAndRetainedCopiesAreCounted() {
    assertThrows(
        Problem.class,
        () ->
            nodes.locked(
                () -> {
                  nodes.reserve("rollback", "local", 80, 0);
                  throw Problem.conflict("rollback");
                }));
    assertEquals(0, nodes.reserved("local"));
    nodes.locked(
        () -> {
          nodes.copied("local", "objects/" + Util.id(), 80);
          nodes.reserve("release", "local", 20, 0);
          nodes.release("release");
          nodes.release("release");
          return null;
        });
    assertEquals(80, nodes.used("local"));
    assertEquals(0, nodes.reserved("local"));
    db.update("UPDATE storage_node SET budget_bytes=100 WHERE backend='local'");
    assertThrows(Problem.class, () -> nodes.admit("local", true, 21));
    assertTrue(nodes.referenced("local"));
  }

  @Test
  void tempGuardRejectsImpossibleAllocation() {
    assertThrows(
        Problem.class,
        () ->
            nodes.locked(
                () -> {
                  nodes.reserve("disk", "local", 0, Long.MAX_VALUE);
                  return null;
                }));
    assertEquals(0, db.count("SELECT COUNT(*) FROM storage_reservation"));
  }

  @Test
  void slowProbeDoesNotHoldAdmissionLockAndOldConfigurationCannotBecomeHealthy() throws Exception {
    var entered = new CountDownLatch(1);
    var resume = new CountDownLatch(1);
    doAnswer(
            call -> {
              entered.countDown();
              assertTrue(resume.await(5, TimeUnit.SECONDS));
              return null;
            })
        .when(storage)
        .probe("local");
    try (var pool = Executors.newFixedThreadPool(2)) {
      var probe = pool.submit(() -> nodes.check("local", false));
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      try {
        var admission =
            pool.submit(
                () ->
                    nodes.locked(
                        () -> {
                          nodes.admit("local", false, 1);
                          db.update(
                              "UPDATE storage_node_health SET"
                                  + " config_version=config_version+1,state='UNKNOWN',validated=FALSE"
                                  + " WHERE backend='local'");
                          return true;
                        }));
        assertTrue(admission.get(2, TimeUnit.SECONDS));
      } finally {
        resume.countDown();
      }
      assertEquals(false, probe.get(2, TimeUnit.SECONDS).get("ok"));
      assertFalse(b(nodes.node("local"), "validated"));
    }
  }

  @Test
  void localAuthorizationPreservesProxyAndNoStore() {
    var res = new MockHttpServletResponse();
    var result =
        direct.authorize(
            Map.of("name", "中文 #?.txt"),
            Map.of("backend", "local"),
            "/api/files/fixture/download",
            Long.MAX_VALUE,
            res);
    assertEquals("proxy", result.get("mode"));
    assertEquals("no-store, private", res.getHeader("Cache-Control"));
    assertThrows(Problem.class, () -> direct.authorize(Map.of(), Map.of(), "/x", 1, res));
  }
}
