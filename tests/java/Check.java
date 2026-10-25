import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Semantic checkers and brute-force references used by the tests and the benchmark.
 * They deliberately do not reuse the production code (own union-find without size/rank,
 * BFS, Floyd-Warshall, Bellman-Ford), so a bug in Algorithms.java cannot hide itself.
 */
final class Check {
    private Check() {
    }

    // ---- tiny independent union-find (plain path halving, no rank) --------------------
    private static int find(int[] p, int x) {
        while (p[x] != x) {
            p[x] = p[p[x]];
            x = p[x];
        }
        return x;
    }

    /** Number of connected components of the undirected graph, by BFS. */
    static int componentsBfs(int n, int[][] edges) {
        int[] head = new int[n];
        Arrays.fill(head, -1);
        int[] next = new int[2 * edges.length];
        int[] to = new int[2 * edges.length];
        int k = 0;
        for (int[] e : edges) {
            to[k] = e[1]; next[k] = head[e[0]]; head[e[0]] = k++;
            to[k] = e[0]; next[k] = head[e[1]]; head[e[1]] = k++;
        }
        boolean[] seen = new boolean[n];
        int comps = 0;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int s = 0; s < n; s++) {
            if (seen[s]) {
                continue;
            }
            comps++;
            seen[s] = true;
            queue.add(s);
            while (!queue.isEmpty()) {
                int v = queue.poll();
                for (int i = head[v]; i != -1; i = next[i]) {
                    if (!seen[to[i]]) {
                        seen[to[i]] = true;
                        queue.add(to[i]);
                    }
                }
            }
        }
        return comps;
    }

    /** Feasible minimum-forest witness: distinct ids, no cycle, spans every component, sum matches. */
    static boolean forestValid(int n, int[][] edges, int[] ids, long claimedCost, int claimedComponents) {
        int c = componentsBfs(n, edges);
        if (claimedComponents != c || ids.length != n - c) {
            return false;
        }
        int[] p = new int[n];
        for (int i = 0; i < n; i++) {
            p[i] = i;
        }
        Set<Integer> used = new HashSet<>();
        long sum = 0;
        for (int id : ids) {
            if (id < 0 || id >= edges.length || !used.add(id)) {
                return false;
            }
            int a = find(p, edges[id][0]);
            int b = find(p, edges[id][1]);
            if (a == b) {
                return false; // cycle or self-loop
            }
            p[a] = b;
            sum += edges[id][2];
        }
        return sum == claimedCost; // n-c acyclic edges inside components => spanning forest
    }

    /** Brute-force optimum: cheapest acyclic subset with exactly n-c edges (small m only). */
    static long bruteForestCost(int n, int[][] edges) {
        int c = componentsBfs(n, edges);
        int m = edges.length;
        long best = Long.MAX_VALUE;
        for (int mask = 0; mask < (1 << m); mask++) {
            if (Integer.bitCount(mask) != n - c) {
                continue;
            }
            int[] p = new int[n];
            for (int i = 0; i < n; i++) {
                p[i] = i;
            }
            boolean ok = true;
            long sum = 0;
            for (int i = 0; i < m && ok; i++) {
                if ((mask >> i & 1) != 0) {
                    int a = find(p, edges[i][0]);
                    int b = find(p, edges[i][1]);
                    if (a == b) {
                        ok = false;
                    } else {
                        p[a] = b;
                        sum += edges[i][2];
                    }
                }
            }
            if (ok && sum < best) {
                best = sum;
            }
        }
        return best;
    }

    // ---- reachability (Floyd-Warshall on a boolean matrix) ----------------------------
    /** reach[u][v] == true iff a path with at least one edge leads from u to v. */
    static boolean[][] reach(int n, int[][] edges) {
        boolean[][] r = new boolean[n][n];
        for (int[] e : edges) {
            r[e[0]][e[1]] = true;
        }
        for (int k = 0; k < n; k++) {
            for (int i = 0; i < n; i++) {
                if (r[i][k]) {
                    for (int j = 0; j < n; j++) {
                        if (r[k][j]) {
                            r[i][j] = true;
                        }
                    }
                }
            }
        }
        return r;
    }

    static boolean hasCycle(int n, int[][] edges) {
        boolean[][] r = reach(n, edges);
        for (int v = 0; v < n; v++) {
            if (r[v][v]) {
                return true;
            }
        }
        return false;
    }

    /** Equal labels iff mutually reachable, and condensation == exact set of cross pairs. */
    static boolean sccValid(int n, int[][] edges, int[] label, List<int[]> condensation) {
        if (label.length != n) {
            return false;
        }
        boolean[][] r = reach(n, edges);
        for (int u = 0; u < n; u++) {
            if (label[u] < 0) {
                return false;
            }
            for (int v = 0; v < n; v++) {
                boolean same = u == v || (r[u][v] && r[v][u]);
                if (same != (label[u] == label[v])) {
                    return false;
                }
            }
        }
        Set<Long> expected = new HashSet<>();
        for (int[] e : edges) {
            if (label[e[0]] != label[e[1]]) {
                expected.add(((long) label[e[0]] << 32) | label[e[1]]);
            }
        }
        Set<Long> actual = new HashSet<>();
        for (int[] pair : condensation) {
            if (!actual.add(((long) pair[0] << 32) | pair[1])) {
                return false; // duplicate pair
            }
        }
        return actual.equals(expected);
    }

    /** Large-input SCC check using BFS-free facts: label count and cross-edge set only. */
    static boolean condensationExact(int[][] edges, int[] label, List<int[]> condensation) {
        Set<Long> expected = new HashSet<>();
        for (int[] e : edges) {
            if (label[e[0]] != label[e[1]]) {
                expected.add(((long) label[e[0]] << 32) | label[e[1]]);
            }
        }
        Set<Long> actual = new HashSet<>();
        for (int[] pair : condensation) {
            if (!actual.add(((long) pair[0] << 32) | pair[1])) {
                return false;
            }
        }
        return actual.equals(expected);
    }

    /** Every vertex exactly once and every edge goes forward. */
    static boolean topoValid(int n, int[][] edges, int[] order) {
        if (order.length != n) {
            return false;
        }
        int[] pos = new int[n];
        Arrays.fill(pos, -1);
        for (int i = 0; i < n; i++) {
            if (order[i] < 0 || order[i] >= n || pos[order[i]] != -1) {
                return false;
            }
            pos[order[i]] = i;
        }
        for (int[] e : edges) {
            if (pos[e[0]] >= pos[e[1]]) {
                return false;
            }
        }
        return true;
    }

    // ---- DAG shortest paths -------------------------------------------------------------
    static final long INF = Long.MAX_VALUE / 4;

    /** Bellman-Ford reference (n-1 full rounds); INF marks unreachable. */
    static long[] bellmanFord(int n, int[][] edges, int source) {
        long[] d = new long[n];
        Arrays.fill(d, INF);
        d[source] = 0;
        for (int round = 0; round < n; round++) {
            boolean changed = false;
            for (int[] e : edges) {
                if (d[e[0]] != INF && d[e[0]] + e[2] < d[e[1]]) {
                    d[e[1]] = d[e[0]] + e[2];
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
        return d;
    }

    /** Distances equal to the reference and parent edges form valid paths ending at each vertex. */
    static boolean dagValid(int n, int[][] edges, int source, Long[] dist, int[] parent, long[] reference) {
        if (dist.length != n || parent.length != n) {
            return false;
        }
        for (int v = 0; v < n; v++) {
            boolean reachable = reference[v] != INF;
            if (reachable != (dist[v] != null) || (reachable && dist[v] != reference[v])) {
                return false;
            }
            if (v == source || !reachable) {
                if (parent[v] != -1) {
                    return false;
                }
                continue;
            }
            int id = parent[v];
            if (id < 0 || id >= edges.length || edges[id][1] != v) {
                return false; // must be an incoming edge of v
            }
            int u = edges[id][0];
            if (dist[u] == null || dist[u] + edges[id][2] != dist[v]) {
                return false; // must lie on a shortest path
            }
        }
        for (int v = 0; v < n; v++) { // every chain must reach the source within n steps
            if (dist[v] == null) {
                continue;
            }
            int steps = 0;
            int cur = v;
            while (cur != source) {
                if (++steps > n) {
                    return false;
                }
                cur = edges[parent[cur]][0];
            }
        }
        return true;
    }
}
