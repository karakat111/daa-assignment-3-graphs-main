import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Graph algorithms for the campus-festival assignment.
 *
 * Every method builds all of its state (arrays, heaps, sets) locally, so nothing
 * survives between two requests handled by the same process. All DFS traversals are
 * iterative: each suspended vertex keeps a cursor into its adjacency range, so deep
 * chains (100 000 vertices) never touch the call stack and no edge is rescanned.
 */
public final class Algorithms {
    private Algorithms() {
    }

    // ------------------------------------------------------------------ DSU

    /** Disjoint-set union: path compression (iterative) + union by size. */
    static final class Dsu {
        private final int[] parent;
        private final int[] size;
        private int components;
        private int largest;

        Dsu(int n) {
            parent = new int[n];
            size = new int[n];
            for (int i = 0; i < n; i++) {
                parent[i] = i;
                size[i] = 1;
            }
            components = n;
            largest = n > 0 ? 1 : 0;
        }

        int find(int x) {
            int root = x;
            while (parent[root] != root) {
                root = parent[root];
            }
            while (parent[x] != root) { // second pass: compress the whole path
                int next = parent[x];
                parent[x] = root;
                x = next;
            }
            return root;
        }

        boolean union(int a, int b) {
            int ra = find(a);
            int rb = find(b);
            if (ra == rb) {
                return false;
            }
            if (size[ra] < size[rb]) { // attach the smaller tree below the larger one
                int t = ra;
                ra = rb;
                rb = t;
            }
            parent[rb] = ra;
            size[ra] += size[rb];
            components--;
            if (size[ra] > largest) {
                largest = size[ra];
            }
            return true;
        }

        int components() {
            return components;
        }

        int largest() {
            return largest;
        }
    }

    /** DSU with path compression and union by size/rank; return states. */
    static Map<String, Object> dsu(Map<String, Object> input) {
        int n = Input.vertexCount(input);
        int[][] operations = Input.operations(input);

        Dsu dsu = new Dsu(n);
        int[][] states = new int[operations.length][];
        for (int i = 0; i < operations.length; i++) {
            dsu.union(operations[i][0], operations[i][1]);
            states[i] = new int[] {dsu.components(), dsu.largest()};
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("states", states);
        return result;
    }

    // ------------------------------------------------------- Kruskal / Prim

    private static final long WEIGHT_OFFSET = 1_000_000_000L; // makes weights non-negative
    private static final int ID_BITS = 20;                    // 2^20 > 250 000 edge ids
    private static final long ID_MASK = (1L << ID_BITS) - 1;

    /** Packs (weight, edge id) into one long whose natural order is weight, then id. */
    private static long key(int weight, int id) {
        return ((weight + WEIGHT_OFFSET) << ID_BITS) | id;
    }

    private static Map<String, Object> forestResult(long cost, int[] ids, int count, int components) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cost", cost);
        result.put("edge_ids", Arrays.copyOf(ids, count));
        result.put("components", components);
        return result;
    }

    /** Separate Kruskal implementation; return cost, edge_ids, components. */
    static Map<String, Object> kruskal(Map<String, Object> input) {
        int n = Input.vertexCount(input);
        int[][] edges = Input.edges(input);

        long[] keys = new long[edges.length];
        for (int i = 0; i < edges.length; i++) {
            keys[i] = key(edges[i][2], i);
        }
        Arrays.sort(keys); // primitive sort: no boxing, ties broken by edge id

        Dsu dsu = new Dsu(n);
        int[] chosen = new int[Math.max(0, n - 1)];
        int count = 0;
        long cost = 0;
        for (long k : keys) {
            int id = (int) (k & ID_MASK);
            if (dsu.union(edges[id][0], edges[id][1])) { // self-loops fail: find(u) == find(u)
                chosen[count++] = id;
                cost += edges[id][2];
            }
        }
        return forestResult(cost, chosen, count, dsu.components());
    }

    /** Minimal binary min-heap of longs (no boxing). */
    private static final class LongHeap {
        private long[] data = new long[16];
        private int size;

        boolean isEmpty() {
            return size == 0;
        }

        void push(long value) {
            if (size == data.length) {
                data = Arrays.copyOf(data, size * 2);
            }
            int i = size++;
            while (i > 0) {
                int p = (i - 1) >> 1;
                if (data[p] <= value) {
                    break;
                }
                data[i] = data[p];
                i = p;
            }
            data[i] = value;
        }

        long pop() {
            long top = data[0];
            long last = data[--size];
            if (size > 0) {
                int i = 0;
                while (true) {
                    int c = 2 * i + 1;
                    if (c >= size) {
                        break;
                    }
                    if (c + 1 < size && data[c + 1] < data[c]) {
                        c++;
                    }
                    if (data[c] >= last) {
                        break;
                    }
                    data[i] = data[c];
                    i = c;
                }
                data[i] = last;
            }
            return top;
        }
    }

    /** Compressed adjacency lists: the edges of v are slots start[v] .. start[v+1]-1. */
    private static final class Csr {
        final int[] start;
        final int[] target;
        final int[] edgeId;

        /** Directed (or doubled, when {@code both}) adjacency; {@code reverse} swaps direction. */
        Csr(int n, int[][] edges, boolean both, boolean reverse) {
            start = new int[n + 1];
            for (int[] e : edges) {
                int from = reverse ? e[1] : e[0];
                int to = reverse ? e[0] : e[1];
                start[from + 1]++;
                if (both) {
                    start[to + 1]++;
                }
            }
            for (int i = 0; i < n; i++) {
                start[i + 1] += start[i];
            }
            int slots = start[n];
            target = new int[slots];
            edgeId = new int[slots];
            int[] fill = Arrays.copyOf(start, n);
            for (int id = 0; id < edges.length; id++) {
                int from = reverse ? edges[id][1] : edges[id][0];
                int to = reverse ? edges[id][0] : edges[id][1];
                target[fill[from]] = to;
                edgeId[fill[from]++] = id;
                if (both) {
                    target[fill[to]] = from;
                    edgeId[fill[to]++] = id;
                }
            }
        }
    }

    /** Separate Prim implementation; restart for every unvisited component. */
    static Map<String, Object> prim(Map<String, Object> input) {
        int n = Input.vertexCount(input);
        int[][] edges = Input.edges(input);

        Csr graph = new Csr(n, edges, true, false);
        boolean[] visited = new boolean[n];
        LongHeap heap = new LongHeap(); // lazy heap: stale entries are skipped on pop
        int[] chosen = new int[Math.max(0, n - 1)];
        int count = 0;
        long cost = 0;
        int components = 0;

        for (int root = 0; root < n; root++) {
            if (visited[root]) {
                continue;
            }
            components++;
            visited[root] = true;
            for (int s = graph.start[root]; s < graph.start[root + 1]; s++) {
                heap.push(key(edges[graph.edgeId[s]][2], graph.edgeId[s]));
            }
            while (!heap.isEmpty()) {
                int id = (int) (heap.pop() & ID_MASK);
                int next;
                if (!visited[edges[id][0]]) {
                    next = edges[id][0];
                } else if (!visited[edges[id][1]]) {
                    next = edges[id][1];
                } else {
                    continue; // both ends already in the tree (cycle edge, self-loop or stale entry)
                }
                visited[next] = true;
                chosen[count++] = id;
                cost += edges[id][2];
                for (int s = graph.start[next]; s < graph.start[next + 1]; s++) {
                    if (!visited[graph.target[s]]) {
                        heap.push(key(edges[graph.edgeId[s]][2], graph.edgeId[s]));
                    }
                }
            }
        }
        return forestResult(cost, chosen, count, components);
    }

    // ------------------------------------------------------------------ SCC

    /** Kosaraju's two DFS passes; return component and exact condensation. */
    static Map<String, Object> scc(Map<String, Object> input) {
        int n = Input.vertexCount(input);
        int[][] edges = Input.edges(input);

        Csr forward = new Csr(n, edges, false, false);
        Csr transposed = new Csr(n, edges, false, true);

        // Pass 1: iterative DFS on G; record vertices in order of finishing.
        int[] finishOrder = new int[n];
        int finished = 0;
        boolean[] seen = new boolean[n];
        int[] cursor = new int[n];
        int[] stack = new int[n];
        for (int root = 0; root < n; root++) {
            if (seen[root]) {
                continue;
            }
            int top = 0;
            stack[top++] = root;
            seen[root] = true;
            cursor[root] = forward.start[root];
            while (top > 0) {
                int v = stack[top - 1];
                if (cursor[v] < forward.start[v + 1]) {
                    int w = forward.target[cursor[v]++];
                    if (!seen[w]) {
                        seen[w] = true;
                        cursor[w] = forward.start[w];
                        stack[top++] = w;
                    }
                } else {
                    finishOrder[finished++] = v;
                    top--;
                }
            }
        }

        // Pass 2: DFS on the transpose, roots taken by decreasing finish time.
        int[] component = new int[n];
        Arrays.fill(component, -1);
        int labels = 0;
        for (int i = n - 1; i >= 0; i--) {
            int root = finishOrder[i];
            if (component[root] != -1) {
                continue;
            }
            int top = 0;
            stack[top++] = root;
            component[root] = labels;
            while (top > 0) { // plain stack traversal: reachability only, order irrelevant here
                int v = stack[--top];
                for (int s = transposed.start[v]; s < transposed.start[v + 1]; s++) {
                    int w = transposed.target[s];
                    if (component[w] == -1) {
                        component[w] = labels;
                        stack[top++] = w;
                    }
                }
            }
            labels++;
        }

        // Condensation: every cross-component pair exactly once (hash-based dedupe).
        HashSet<Long> pairs = new HashSet<>();
        List<int[]> condensation = new ArrayList<>();
        for (int[] e : edges) {
            int a = component[e[0]];
            int b = component[e[1]];
            if (a != b && pairs.add(((long) a << 32) | b)) {
                condensation.add(new int[] {a, b});
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("component", component);
        result.put("condensation", condensation);
        return result;
    }

    // ----------------------------------------------------------------- topo

    /**
     * Three-state DFS (0 unvisited, 1 active, 2 finished) over every component.
     * Returns vertices in reverse finishing order, or null if a directed cycle exists.
     */
    static int[] topologicalOrder(int n, int[][] edges) {
        Csr graph = new Csr(n, edges, false, false);
        byte[] state = new byte[n];
        int[] cursor = new int[n];
        int[] stack = new int[n];
        int[] order = new int[n];
        int placed = n; // fill from the back: reverse finish order without a second pass

        for (int root = 0; root < n; root++) {
            if (state[root] != 0) {
                continue;
            }
            int top = 0;
            stack[top++] = root;
            state[root] = 1;
            cursor[root] = graph.start[root];
            while (top > 0) {
                int v = stack[top - 1];
                if (cursor[v] < graph.start[v + 1]) {
                    int w = graph.target[cursor[v]++];
                    if (state[w] == 1) {
                        return null; // edge back to an active vertex (self-loop included)
                    }
                    if (state[w] == 0) {
                        state[w] = 1;
                        cursor[w] = graph.start[w];
                        stack[top++] = w;
                    } // state 2: already finished, a forward/cross edge, not a cycle
                } else {
                    state[v] = 2;
                    order[--placed] = v;
                    top--;
                }
            }
        }
        return order;
    }

    /** Three-state DFS and reverse finish order; return acyclic and order. */
    static Map<String, Object> topo(Map<String, Object> input) {
        int n = Input.vertexCount(input);
        int[][] edges = Input.edges(input);

        int[] order = topologicalOrder(n, edges);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("acyclic", order != null);
        result.put("order", order != null ? order : new int[0]);
        return result;
    }

    // ------------------------------------------------------------------ DAG

    /** Shortest-path result: distance[v] is meaningful only when reachable[v]. */
    static final class DagPaths {
        final boolean acyclic;
        final boolean[] reachable;
        final long[] distance;
        final int[] parentEdge;

        DagPaths(boolean acyclic, boolean[] reachable, long[] distance, int[] parentEdge) {
            this.acyclic = acyclic;
            this.reachable = reachable;
            this.distance = distance;
            this.parentEdge = parentEdge;
        }
    }

    /** Whole-graph cycle check, then relaxation in topological order from reachable vertices. */
    static DagPaths dagShortestPaths(int n, int[][] edges, int source) {
        int[] order = topologicalOrder(n, edges); // checks every component, even unreachable ones
        if (order == null) {
            return new DagPaths(false, new boolean[0], new long[0], new int[0]);
        }
        Csr graph = new Csr(n, edges, false, false);
        boolean[] reachable = new boolean[n];
        long[] distance = new long[n];
        int[] parentEdge = new int[n];
        Arrays.fill(parentEdge, -1);
        reachable[source] = true;

        for (int v : order) {
            if (!reachable[v]) {
                continue; // never relax from an unreachable vertex
            }
            for (int s = graph.start[v]; s < graph.start[v + 1]; s++) {
                int w = graph.target[s];
                int id = graph.edgeId[s];
                long candidate = distance[v] + edges[id][2];
                if (!reachable[w] || candidate < distance[w]) {
                    reachable[w] = true;
                    distance[w] = candidate;
                    parentEdge[w] = id;
                }
            }
        }
        return new DagPaths(true, reachable, distance, parentEdge);
    }

    /**
     * Route helper. Walks parent edge IDs from {@code target} back to {@code source} and
     * returns the vertex sequence source -> ... -> target.
     *
     * <p>No-route convention: an unreachable target yields an EMPTY array (length 0).
     * The route to the source itself is {@code [source]}.
     *
     * @throws IllegalArgumentException if parentEdge is not a valid parent forest
     *         (an edge that does not end at its vertex, or a chain that never reaches source)
     */
    public static int[] reconstructRoute(int[][] edges, int[] parentEdge, int source, int target) {
        if (target == source) {
            return new int[] {source};
        }
        if (parentEdge[target] == -1) {
            return new int[0];
        }
        int[] reversed = new int[parentEdge.length];
        int length = 0;
        int v = target;
        while (v != source) {
            if (length >= parentEdge.length) {
                throw new IllegalArgumentException("parent edges contain a cycle");
            }
            int id = parentEdge[v];
            if (id < 0 || edges[id][1] != v) {
                throw new IllegalArgumentException("parent edge " + id + " does not end at " + v);
            }
            reversed[length++] = v;
            v = edges[id][0];
        }
        reversed[length++] = source;
        int[] route = new int[length];
        for (int i = 0; i < length; i++) {
            route[i] = reversed[length - 1 - i];
        }
        return route;
    }

    /** Whole-graph cycle check and DAG relaxation; return acyclic, distance, parent_edge. */
    static Map<String, Object> dag(Map<String, Object> input) {
        int n = Input.vertexCount(input);
        int source = Input.source(input);
        int[][] edges = Input.edges(input);

        DagPaths paths = dagShortestPaths(n, edges, source);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("acyclic", paths.acyclic);
        if (!paths.acyclic) {
            result.put("distance", new Long[0]);
            result.put("parent_edge", new int[0]);
            return result;
        }
        Long[] distance = new Long[n]; // null stays null: unreachable
        for (int v = 0; v < n; v++) {
            if (paths.reachable[v]) {
                distance[v] = paths.distance[v];
            }
        }
        result.put("distance", distance);
        result.put("parent_edge", paths.parentEdge);
        return result;
    }
}
