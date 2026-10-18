// E01 storage probe only: no application schema, endpoints, keys or paid embeddings.
// Java 21 source-file mode; add the project's H2 runtime jar to --class-path.
import java.lang.management.*;
import java.nio.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.h2.tools.Restore;

public class VectorStoreBenchmark {
  record Hit(int id, double score) {}

  record Row(int id, float[] vector) {}

  static final int QUERIES = 20;
  static final AtomicLong observedHeapPeak = new AtomicLong();

  public static void main(String[] args) throws Exception {
    var sampler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              var thread = new Thread(r, "heap-sampler");
              thread.setDaemon(true);
              return thread;
            });
    sampler.scheduleAtFixedRate(
        () ->
            observedHeapPeak.accumulateAndGet(
                ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(), Math::max),
        0,
        20,
        TimeUnit.MILLISECONDS);
    String dimensions = args.length == 0 ? "1536" : args[0];
    System.out.printf(
        Locale.ROOT,
        "{\"type\":\"environment\",\"data\":\"seeded synthetic float32; retrieval correctness only,"
            + " not semantic"
            + " quality\",\"java\":\"%s\",\"os\":\"%s\",\"arch\":\"%s\",\"processors\":%d,\"maxHeapMiB\":%d}%n",
        System.getProperty("java.version"),
        System.getProperty("os.name"),
        System.getProperty("os.arch"),
        Runtime.getRuntime().availableProcessors(),
        Runtime.getRuntime().maxMemory() / (1024 * 1024));
    for (String raw : dimensions.split(",")) {
      int dimension = Integer.parseInt(raw);
      if (dimension < 1 || dimension > 4096)
        throw new IllegalArgumentException("dimension 1..4096");
      for (int count : new int[] {100, 1000, 10000}) run(count, dimension);
    }
  }

  static void run(int count, int dimensions) throws Exception {
    Path root = Files.createTempDirectory("cloudnest-vector-bench-").toRealPath();
    try {
      String url =
          "jdbc:h2:file:"
              + root.resolve("bench").toString().replace('\\', '/')
              + ";DB_CLOSE_ON_EXIT=FALSE";
      Path backup = root.resolve("backup.zip");
      long started = System.nanoTime();
      List<Row> memory = new ArrayList<>();
      double streaming50, streaming95, memory50, memory95, backupMs, deleteMs;
      try (Connection db = DriverManager.getConnection(url, "sa", "")) {
        db.createStatement()
            .execute(
                "CREATE TABLE vectors(user_id VARCHAR(20),generation INT,id INT,embedding"
                    + " BLOB,PRIMARY KEY(user_id,generation,id))");
        db.setAutoCommit(false);
        try (PreparedStatement insert =
            db.prepareStatement("INSERT INTO vectors VALUES(?,?,?,?)")) {
          for (int i = 0; i < count; i++) {
            float[] vector = vector(i, dimensions);
            insert.setString(1, "owner");
            insert.setInt(2, 1);
            insert.setInt(3, i);
            insert.setBytes(4, bytes(vector));
            insert.addBatch();
            if (i % 100 == 99) insert.executeBatch();
          }
          insert.executeBatch();
          // Enemy tenant and stale generation contain exact query vectors. Scope must exclude both.
          for (int i = 0; i < QUERIES; i++)
            for (String scope : List.of("enemy", "owner")) {
              insert.setString(1, scope);
              insert.setInt(2, scope.equals("owner") ? 2 : 1);
              insert.setInt(3, i);
              insert.setBytes(4, bytes(query(i * count / QUERIES, dimensions)));
              insert.addBatch();
            }
          insert.executeBatch();
        }
        db.commit();
        db.setAutoCommit(true);
        long insertEnd = System.nanoTime();
        try (PreparedStatement select =
            db.prepareStatement(
                "SELECT id,embedding FROM vectors WHERE user_id=? AND generation=?")) {
          select.setString(1, "owner");
          select.setInt(2, 1);
          try (ResultSet rows = select.executeQuery()) {
            while (rows.next()) memory.add(new Row(rows.getInt(1), floats(rows.getBytes(2))));
          }
          double[] streamTimes = new double[QUERIES], memoryTimes = new double[QUERIES];
          for (int i = -5; i < QUERIES; i++) {
            int target = Math.max(i, 0) * count / QUERIES;
            float[] q = query(target, dimensions);
            long before = System.nanoTime();
            List<Hit> hits = stream(select, q, 6);
            double elapsed = ms(before);
            if (hits.getFirst().id() != target)
              throw new AssertionError("SQL scope or ranking failure");
            if (i >= 0) streamTimes[i] = elapsed;
            before = System.nanoTime();
            hits = search(memory, q, 6);
            elapsed = ms(before);
            if (hits.getFirst().id() != target) throw new AssertionError("memory ranking failure");
            if (i >= 0) memoryTimes[i] = elapsed;
          }
          streaming50 = percentile(streamTimes, .5);
          streaming95 = percentile(streamTimes, .95);
          memory50 = percentile(memoryTimes, .5);
          memory95 = percentile(memoryTimes, .95);
        }
        long before = System.nanoTime();
        try (Statement statement = db.createStatement()) {
          statement.execute("BACKUP TO '" + backup.toString().replace("'", "''") + "'");
        }
        backupMs = ms(before);
        before = System.nanoTime();
        db.createStatement()
            .executeUpdate("DELETE FROM vectors WHERE user_id='owner' AND generation=1");
        deleteMs = ms(before);
        try (ResultSet rows =
            db.createStatement()
                .executeQuery("SELECT COUNT(*) FROM vectors WHERE user_id='enemy'")) {
          rows.next();
          if (rows.getInt(1) != QUERIES) throw new AssertionError("delete crossed tenant");
        }
        try (ResultSet rows =
            db.createStatement()
                .executeQuery(
                    "SELECT COUNT(*) FROM vectors WHERE user_id='owner' AND generation=1")) {
          rows.next();
          if (rows.getInt(1) != 0) throw new AssertionError("delete incomplete");
        }
        long beforeRestore = System.nanoTime();
        Restore.execute(backup.toString(), root.resolve("restore").toString(), "bench");
        try (Connection restored =
                DriverManager.getConnection(
                    "jdbc:h2:file:"
                        + root.resolve("restore/bench").toString().replace('\\', '/')
                        + ";DB_CLOSE_ON_EXIT=FALSE",
                    "sa",
                    "");
            PreparedStatement select =
                restored.prepareStatement(
                    "SELECT id,embedding FROM vectors WHERE user_id=? AND generation=?")) {
          select.setString(1, "owner");
          select.setInt(2, 1);
          for (int i = 0; i < QUERIES; i++)
            if (stream(select, query(i * count / QUERIES, dimensions), 6).getFirst().id()
                != i * count / QUERIES) throw new AssertionError("restore query failure");
        }
        double restoreMs = ms(beforeRestore);
        long heapPeak =
            observedHeapPeak
                .get(); // One process-wide observed peak, not a sum of unrelated pool peaks.
        System.out.printf(
            Locale.ROOT,
            "{\"type\":\"benchmark\",\"blocks\":%d,\"dimensions\":%d,\"queries\":%d,\"topK\":6,\"knownTop1\":20,\"isolationAndRestore\":true,\"insertMs\":%.2f,\"h2P50Ms\":%.2f,\"h2P95Ms\":%.2f,\"memoryP50Ms\":%.2f,\"memoryP95Ms\":%.2f,\"rawVectorMiB\":%.2f,\"databaseMiB\":%.2f,\"backupMiB\":%.2f,\"processObservedHeapPeakMiB\":%.2f,\"backupMs\":%.2f,\"restoreAnd20QueriesMs\":%.2f,\"scopedDeleteMs\":%.2f}%n",
            count,
            dimensions,
            QUERIES,
            (insertEnd - started) / 1e6,
            streaming50,
            streaming95,
            memory50,
            memory95,
            count * (double) dimensions * 4 / (1024 * 1024),
            Files.size(root.resolve("bench.mv.db")) / (1024.0 * 1024),
            Files.size(backup) / (1024.0 * 1024),
            heapPeak / (1024.0 * 1024),
            backupMs,
            restoreMs,
            deleteMs);
      }
    } finally {
      // Only remove this invocation's verified temp directory; never accept a deletion path.
      Path temp = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
      if (!root.getParent().equals(temp)
          || !root.getFileName().toString().startsWith("cloudnest-vector-bench-"))
        throw new IllegalStateException("Unexpected temp location");
      try (var paths = Files.walk(root)) {
        for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
      }
    }
  }

  static float[] vector(int id, int dimensions) {
    var random = new SplittableRandom(934857L + id);
    float[] values = new float[dimensions];
    double norm = 0;
    for (int i = 0; i < dimensions; i++) {
      values[i] = (float) random.nextDouble(-1, 1);
      norm += (double) values[i] * values[i];
    }
    for (int i = 0; i < dimensions; i++) values[i] /= (float) Math.sqrt(norm);
    return values;
  }

  static float[] query(int id, int dimensions) {
    float[] q = vector(id, dimensions);
    for (int i = 0; i < q.length; i++) q[i] += (i % 2 == 0 ? .0001f : -.0001f);
    return q;
  }

  static byte[] bytes(float[] values) {
    ByteBuffer buffer = ByteBuffer.allocate(values.length * 4);
    for (float value : values) buffer.putFloat(value);
    return buffer.array();
  }

  static float[] floats(byte[] bytes) {
    float[] values = new float[bytes.length / 4];
    ByteBuffer buffer = ByteBuffer.wrap(bytes);
    for (int i = 0; i < values.length; i++) values[i] = buffer.getFloat();
    return values;
  }

  static List<Hit> stream(PreparedStatement select, float[] query, int k) throws Exception {
    var heap = new PriorityQueue<Hit>(Comparator.comparingDouble(Hit::score));
    try (ResultSet rows = select.executeQuery()) {
      while (rows.next())
        offer(heap, new Hit(rows.getInt(1), cosine(floats(rows.getBytes(2)), query)), k);
    }
    return sorted(heap);
  }

  static List<Hit> search(List<Row> rows, float[] query, int k) {
    var heap = new PriorityQueue<Hit>(Comparator.comparingDouble(Hit::score));
    for (Row row : rows) offer(heap, new Hit(row.id(), cosine(row.vector(), query)), k);
    return sorted(heap);
  }

  static void offer(PriorityQueue<Hit> heap, Hit hit, int k) {
    if (heap.size() < k) heap.offer(hit);
    else if (hit.score() > heap.peek().score()) {
      heap.poll();
      heap.offer(hit);
    }
  }

  static List<Hit> sorted(PriorityQueue<Hit> heap) {
    return heap.stream().sorted(Comparator.comparingDouble(Hit::score).reversed()).toList();
  }

  static double cosine(float[] a, float[] b) {
    double dot = 0, aa = 0, bb = 0;
    for (int i = 0; i < a.length; i++) {
      dot += (double) a[i] * b[i];
      aa += (double) a[i] * a[i];
      bb += (double) b[i] * b[i];
    }
    return dot / Math.sqrt(aa * bb);
  }

  static double ms(long start) {
    return (System.nanoTime() - start) / 1e6;
  }

  static double percentile(double[] values, double percentile) {
    Arrays.sort(values);
    return values[(int) Math.ceil(values.length * percentile) - 1];
  }
}
