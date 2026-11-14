import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class Benchmark {
    private Benchmark() { }

    private static void exercise(DbEngine db, String sql, int repetitions) throws DbException {
        for (int i = 0; i < repetitions; i++) {
            QueryResult result = db.execute(sql);
            if (result.rows.size() != 100) throw new AssertionError("Ожидались 100 рейсов");
        }
    }

    private static double median(double[] array) {
        double[] sorted = array.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    public static void main(String[] args) throws Exception {
        int n = 10000;
        int iterations = 300;
        int trials = 5;
        Path folder = Files.createTempDirectory("java-db-benchmark-");
        DbEngine db = new DbEngine(folder);
        StringBuilder insert = new StringBuilder("INSERT INTO flights (id, code, departure, price, active) VALUES ");
        for (int i = 1; i <= n; i++) {
            if (i > 1) insert.append(',');
            insert.append('(').append(i).append(", 'AIR").append(i % 100)
                    .append("', '2026-11-15', ").append(1000 + i)
                    .append(".0, TRUE)");
        }
        insert.append(';');
        db.execute(insert.toString());
        String sql = "SELECT id FROM flights WHERE code = 'AIR7';";
        double[] scan = new double[trials];
        double[] indexed = new double[trials];
        exercise(db, sql, 50);
        for (int i = 0; i < trials; i++) {
            long start = System.nanoTime();
            exercise(db, sql, iterations);
            scan[i] = (System.nanoTime() - start) / 1_000_000.0 / iterations;
        }

        db.execute("CREATE INDEX idx_code ON flights (code);");
        if (!db.execute(sql).indexUsed) throw new AssertionError("Индекс не задействован");
        exercise(db, sql, 50);
        for (int i = 0; i < trials; i++) {
            long start = System.nanoTime();
            exercise(db, sql, iterations);
            indexed[i] = (System.nanoTime() - start) / 1_000_000.0 / iterations;
        }
        double mScan = median(scan);
        double mIndex = median(indexed);
        System.out.println("Java: " + System.getProperty("java.version"));
        System.out.println("Записей: " + n + "; подходящих записей: 100");
        System.out.println("SELECT в серии: " + iterations + "; серий: " + trials);
        for (int i = 0; i < trials; i++) {
            System.out.printf(java.util.Locale.US,
                    "Серия %d: без индекса %.3f мс; с индексом %.3f мс%n",
                    i + 1, scan[i], indexed[i]);
        }
        System.out.printf(java.util.Locale.US, "Медиана без индекса: %.3f мс/SELECT%n", mScan);
        System.out.printf(java.util.Locale.US, "Медиана с индексом: %.3f мс/SELECT%n", mIndex);
        System.out.printf(java.util.Locale.US, "Отношение медиан: %.2fx%n", mScan / mIndex);
        System.out.println("Учитывается только SELECT в RAM, без консольного вывода и сохранения.");
    }
}