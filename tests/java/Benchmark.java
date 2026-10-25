import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Kruskal vs Prim on identical graphs at three sizes.
 * Graphs: Random(SEED) endpoints uniform in [0,n), weights uniform in [-1e6,1e6], m edges (loops/parallels possible).
 * Timed region: one call of Algorithms.kruskal / Algorithms.prim on the already-parsed request map. It includes
 * List->int[][] conversion (Input.edges), building DSU / adjacency lists / heap, the algorithm itself and building the
 * result map. It excludes graph generation, JSON text parsing/printing, I/O, JVM start-up and compilation.
 */
public final class Benchmark {
    private static final long SEED = 20261022L;
    private static final int WARMUP = 5;
    private static final int RUNS = 7;

    private static Map<String, Object> request(String task, int n, int[][] edges) {
        List<Object> rows = new ArrayList<>(edges.length);
        for (int[] e : edges) {
            List<Object> row = new ArrayList<>(3);
            row.add((long) e[0]);
            row.add((long) e[1]);
            row.add((long) e[2]);
            rows.add(row);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("task", task);
        m.put("n", (long) n);
        m.put("edges", rows);
        return m;
    }

    private static double median(double[] xs) {
        double[] s = xs.clone();
        Arrays.sort(s);
        return s[s.length / 2];
    }

    private static String fmt(double[] xs) {
        StringBuilder sb = new StringBuilder();
        for (double x : xs) {
            sb.append(String.format("%.2f ", x));
        }
        return sb.toString().trim();
    }

    public static void main(String[] args) throws Exception {
        int[][] sizes = {{1_000, 5_000}, {20_000, 100_000}, {100_000, 250_000}};
        StringBuilder out = new StringBuilder();
        out.append("# Kruskal vs Prim benchmark (tests/java/Benchmark.java), seed ").append(SEED).append('\n');
        out.append("java.version=").append(System.getProperty("java.version"))
           .append(" vm=").append(System.getProperty("java.vm.name"))
           .append(" os=").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.arch"))
           .append(" cpus=").append(Runtime.getRuntime().availableProcessors()).append('\n');
        out.append("timer=System.nanoTime, warm-up runs per method per size=").append(WARMUP)
           .append(", timed runs=").append(RUNS).append(" (Kruskal and Prim alternate)\n\n");
        for (int[] size : sizes) {
            int n = size[0];
            int m = size[1];
            Random rnd = new Random(SEED + n);
            int[][] edges = new int[m][];
            for (int i = 0; i < m; i++) {
                edges[i] = new int[] {rnd.nextInt(n), rnd.nextInt(n), rnd.nextInt(2_000_001) - 1_000_000};
            }
            Map<String, Object> kReq = request("kruskal", n, edges);
            Map<String, Object> pReq = request("prim", n, edges);

            // Correctness first: valid forests and equal optimum.
            Map<String, Object> k = Algorithms.kruskal(kReq);
            Map<String, Object> p = Algorithms.prim(pReq);
            boolean kOk = Check.forestValid(n, edges, (int[]) k.get("edge_ids"), (Long) k.get("cost"), (Integer) k.get("components"));
            boolean pOk = Check.forestValid(n, edges, (int[]) p.get("edge_ids"), (Long) p.get("cost"), (Integer) p.get("components"));
            if (!kOk || !pOk || !k.get("cost").equals(p.get("cost")) || !k.get("components").equals(p.get("components"))) {
                throw new IllegalStateException("verification failed for n=" + n);
            }
            for (int i = 0; i < WARMUP; i++) {
                Algorithms.kruskal(kReq);
                Algorithms.prim(pReq);
            }
            double[] kt = new double[RUNS];
            double[] pt = new double[RUNS];
            double[] conv = new double[RUNS];
            for (int i = 0; i < RUNS; i++) {
                long t = System.nanoTime();
                Algorithms.kruskal(kReq);
                kt[i] = (System.nanoTime() - t) / 1e6;
                t = System.nanoTime();
                Algorithms.prim(pReq);
                pt[i] = (System.nanoTime() - t) / 1e6;
                t = System.nanoTime();
                Input.edges(kReq);
                conv[i] = (System.nanoTime() - t) / 1e6;
            }
            out.append(String.format("n=%d m=%d components=%d cost=%d (both methods verified: valid forest, equal cost)%n",
                    n, m, (Integer) k.get("components"), (Long) k.get("cost")));
            out.append("  Kruskal ms: ").append(fmt(kt)).append(String.format("  | median %.2f%n", median(kt)));
            out.append("  Prim    ms: ").append(fmt(pt)).append(String.format("  | median %.2f%n", median(pt)));
            out.append("  (Input.edges conversion alone, included in both: median ")
               .append(String.format("%.2f ms)%n%n", median(conv)));
        }
        System.out.print(out);
        Path path = Path.of(args.length > 0 ? args[0] : "tests/benchmark_results.txt");
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8))) {
            w.print(out);
        }
    }
}
