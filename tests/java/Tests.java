import java.io.IOException;
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
import java.util.function.Function;

/**
 * Self-contained test runner (no external dependencies). Run it with tests/run_tests.py.
 * Evidence (inputs, actual outputs, route-helper calls) is written to tests/actual_outputs.txt.
 */
public final class Tests {
    private static int passed;
    private static int failed;
    private static final StringBuilder LOG = new StringBuilder();

    // ------------------------------------------------------------------ infrastructure
    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("PASS " + name);
        } else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    private static void evidence(String text) {
        LOG.append(text).append('\n');
    }

    private static Map<String, Object> request(String json) {
        return Json.parseRequest(json);
    }

    private static List<Object> rows(int[][] a) {
        List<Object> out = new ArrayList<>(a.length);
        for (int[] row : a) {
            List<Object> r = new ArrayList<>(row.length);
            for (int x : row) {
                r.add((long) x);
            }
            out.add(r);
        }
        return out;
    }

    private static Map<String, Object> graphRequest(String task, int n, int[][] edges, Integer source) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("task", task);
        m.put("n", (long) n);
        m.put("edges", rows(edges));
        if (source != null) {
            m.put("source", (long) source);
        }
        return m;
    }

    private static Map<String, Object> dsuRequest(int n, int[][] ops) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("task", "dsu");
        m.put("n", (long) n);
        m.put("operations", rows(ops));
        return m;
    }

    private static int[][] mst(int n, int[][] edges, Function<Map<String, Object>, Map<String, Object>> f,
                               String task, long[] costOut, int[] compOut) {
        Map<String, Object> r = f.apply(graphRequest(task, n, edges, null));
        costOut[0] = (Long) r.get("cost");
        compOut[0] = (Integer) r.get("components");
        return new int[][] {(int[]) r.get("edge_ids")};
    }

    @SuppressWarnings("unchecked")
    private static List<int[]> cond(Map<String, Object> r) {
        return (List<int[]>) r.get("condensation");
    }

    // ------------------------------------------------------------------ hand-justified cases
    private static void dsuHandCases() {
        Map<String, Object> r = Algorithms.dsu(request("{\"task\":\"dsu\",\"n\":0,\"operations\":[]}"));
        check("dsu n=0 -> states=[]", Json.write(r).equals("{\"states\":[]}"));

        // n=5: (0,1)->4 comps,max2; (2,3)->3,2; (1,0) repeat->3,2; (3,3) self->3,2;
        // (1,3) joins {0,1}+{2,3}->2,4; (4,4) self->2,4
        r = Algorithms.dsu(request("{\"task\":\"dsu\",\"n\":5,\"operations\":[[0,1],[2,3],[1,0],[3,3],[1,3],[4,4]]}"));
        String expected = "{\"states\":[[4,2],[3,2],[3,2],[3,2],[2,4],[2,4]]}";
        evidence("dsu n=5 actual: " + Json.write(r) + "   expected: " + expected);
        check("dsu repeated and self unions", Json.write(r).equals(expected));

        r = Algorithms.dsu(request("{\"task\":\"dsu\",\"n\":3,\"operations\":[]}"));
        check("dsu no operations on n=3 -> states=[]", Json.write(r).equals("{\"states\":[]}"));
    }

    private static void forestHandCases() {
        // vertices 0..5; component {0,1,2}: edges 0 (w5), 1 (w-2) parallel 0-1; 2 (w4) 1-2;
        // edge 3 is a self-loop (-100) that must be ignored; component {3,4}: edge 4 (w7); vertex 5 isolated.
        // Optimum: ids {1,2,4}, cost -2+4+7 = 9, components 3 (n-c = 3 edges).
        int[][] e = {{0, 1, 5}, {0, 1, -2}, {1, 2, 4}, {2, 2, -100}, {3, 4, 7}};
        for (String task : new String[] {"kruskal", "prim"}) {
            Map<String, Object> r = task.equals("kruskal")
                    ? Algorithms.kruskal(graphRequest(task, 6, e, null))
                    : Algorithms.prim(graphRequest(task, 6, e, null));
            int[] ids = (int[]) r.get("edge_ids");
            int[] sorted = ids.clone();
            Arrays.sort(sorted);
            evidence(task + " mixed hand case actual: " + Json.write(r) + "   expected: cost 9, ids [1,2,4], components 3");
            check(task + " parallel+negative+loop+isolate: cost 9, ids {1,2,4}, 3 components",
                    (Long) r.get("cost") == 9L && Arrays.equals(sorted, new int[] {1, 2, 4})
                            && (Integer) r.get("components") == 3);
        }
        for (String task : new String[] {"kruskal", "prim"}) {
            Function<Map<String, Object>, Map<String, Object>> f =
                    task.equals("kruskal") ? Algorithms::kruskal : Algorithms::prim;
            Map<String, Object> r = f.apply(graphRequest(task, 0, new int[0][], null));
            check(task + " n=0 -> cost 0, no ids, 0 components",
                    Json.write(r).equals("{\"cost\":0,\"edge_ids\":[],\"components\":0}"));
            r = f.apply(graphRequest(task, 4, new int[0][], null));
            check(task + " no edges, 4 isolates -> 4 components",
                    Json.write(r).equals("{\"cost\":0,\"edge_ids\":[],\"components\":4}"));
            // 4-cycle of weight 1e9: any 3 edges, total 3e9 > 2^31-1
            r = f.apply(graphRequest(task, 4, new int[][] {{0, 1, 1000000000}, {1, 2, 1000000000},
                    {2, 3, 1000000000}, {0, 3, 1000000000}}, null));
            check(task + " total beyond 32 bits: 3e9", (Long) r.get("cost") == 3_000_000_000L);
            r = f.apply(graphRequest(task, 3, new int[][] {{0, 1, -1000000000}, {1, 2, -1000000000}}, null));
            check(task + " negative extreme weights: -2e9", (Long) r.get("cost") == -2_000_000_000L);
        }
    }

    private static void sccHandCases() {
        // 0->1->2->0 is a cycle A={0,1,2}; 3<->4 is B={3,4}; 5 has a self-loop (own SCC); 6 isolated.
        // Cross edges: 2->3 and 0->3 (duplicate pair A->B), so exactly one condensation pair A->B.
        int[][] e = {{0, 1}, {1, 2}, {2, 0}, {2, 3}, {3, 4}, {4, 3}, {5, 5}, {0, 3}, {0, 3}};
        Map<String, Object> r = Algorithms.scc(graphRequest("scc", 7, e, null));
        int[] label = (int[]) r.get("component");
        evidence("scc hand case actual: " + Json.write(r)
                + "   expected: groups {0,1,2},{3,4},{5},{6}; one pair (label(0),label(3))");
        boolean groups = label[0] == label[1] && label[1] == label[2] && label[3] == label[4]
                && label[0] != label[3] && label[5] != label[0] && label[5] != label[3]
                && label[6] != label[5] && label[6] != label[0] && label[6] != label[3];
        check("scc hand case groups", groups);
        check("scc hand case condensation is exactly one pair A->B",
                cond(r).size() == 1 && cond(r).get(0)[0] == label[0] && cond(r).get(0)[1] == label[3]);
        r = Algorithms.scc(graphRequest("scc", 0, new int[0][], null));
        check("scc n=0", Json.write(r).equals("{\"component\":[],\"condensation\":[]}"));
        r = Algorithms.scc(graphRequest("scc", 3, new int[0][], null));
        label = (int[]) r.get("component");
        check("scc isolated vertices are 3 separate SCCs",
                label[0] != label[1] && label[1] != label[2] && label[0] != label[2] && cond(r).isEmpty());
    }

    private static void topoHandCases() {
        // 2 is isolated; order must keep 3 before 1, 1 before 0, 4 before 0 (duplicate edge 3->1 is harmless)
        int[][] e = {{3, 1}, {3, 1}, {1, 0}, {4, 0}};
        Map<String, Object> r = Algorithms.topo(graphRequest("topo", 5, e, null));
        evidence("topo hand case actual: " + Json.write(r) + "   expected: acyclic, any order with 3<1<0 and 4<0");
        check("topo acyclic with duplicate edge + isolate",
                (Boolean) r.get("acyclic") && Check.topoValid(5, e, (int[]) r.get("order")));
        // cycle 2->3->4->2 in a component not containing vertex 0
        r = Algorithms.topo(graphRequest("topo", 5, new int[][] {{0, 1}, {2, 3}, {3, 4}, {4, 2}}, null));
        check("topo cycle in a later component", !(Boolean) r.get("acyclic") && ((int[]) r.get("order")).length == 0);
        r = Algorithms.topo(graphRequest("topo", 2, new int[][] {{1, 1}}, null));
        check("topo self-loop is a cycle", !(Boolean) r.get("acyclic"));
        // diamond: forward edge into a FINISHED vertex must not be reported as a cycle
        int[][] diamond = {{0, 1}, {0, 2}, {1, 3}, {2, 3}, {0, 3}};
        r = Algorithms.topo(graphRequest("topo", 4, diamond, null));
        check("topo edge to finished vertex is not a cycle",
                (Boolean) r.get("acyclic") && Check.topoValid(4, diamond, (int[]) r.get("order")));
        r = Algorithms.topo(graphRequest("topo", 0, new int[0][], null));
        check("topo n=0", Json.write(r).equals("{\"acyclic\":true,\"order\":[]}"));
    }

    private static void dagHandCases() {
        // README example. Route to 3 must be 0->1->2->3 with cost 3-7+4 = 0.
        int[][] ex = {{0, 1, 3}, {0, 2, 8}, {1, 2, -7}, {2, 3, 4}};
        Algorithms.DagPaths p = Algorithms.dagShortestPaths(5, ex, 0);
        int[] routeReach = Algorithms.reconstructRoute(ex, p.parentEdge, 0, 3);
        int[] routeSource = Algorithms.reconstructRoute(ex, p.parentEdge, 0, 0);
        int[] routeUnreach = Algorithms.reconstructRoute(ex, p.parentEdge, 0, 4);
        evidence("dag README example: distance=" + Arrays.toString(p.distance) + " reachable="
                + Arrays.toString(p.reachable) + " parent_edge=" + Arrays.toString(p.parentEdge));
        evidence("route helper (README example): route(0->3)=" + Arrays.toString(routeReach)
                + " cost=" + routeCost(ex, p.parentEdge, routeReach)
                + "; route(source 0)=" + Arrays.toString(routeSource)
                + "; route(unreachable 4)=" + Arrays.toString(routeUnreach) + " (empty = no route)");
        check("route helper: reachable target", Arrays.equals(routeReach, new int[] {0, 1, 2, 3})
                && routeCost(ex, p.parentEdge, routeReach) == 0 && p.distance[3] == 0);
        check("route helper: source -> [source]", Arrays.equals(routeSource, new int[] {0}));
        check("route helper: unreachable target -> empty array", routeUnreach.length == 0);

        // tied shortest routes and parallel edges: 0->1 twice (ids 0 and 4), 0->1->3 and 0->2->3 both cost 2
        int[][] tied = {{0, 1, 1}, {0, 2, 1}, {1, 3, 1}, {2, 3, 1}, {0, 1, 1}};
        p = Algorithms.dagShortestPaths(4, tied, 0);
        int[] route = Algorithms.reconstructRoute(tied, p.parentEdge, 0, 3);
        int[] route1 = Algorithms.reconstructRoute(tied, p.parentEdge, 0, 1);
        evidence("dag tied/parallel example: distance=" + Arrays.toString(p.distance)
                + " parent_edge=" + Arrays.toString(p.parentEdge)
                + " route(0->3)=" + Arrays.toString(route) + " cost=" + routeCost(tied, p.parentEdge, route)
                + " route(0->1)=" + Arrays.toString(route1) + " via edge " + p.parentEdge[1]);
        boolean either = Arrays.equals(route, new int[] {0, 1, 3}) || Arrays.equals(route, new int[] {0, 2, 3});
        check("route helper: tied routes give a valid shortest route of cost 2",
                either && routeCost(tied, p.parentEdge, route) == 2 && p.distance[3] == 2);
        check("route helper: parallel edges 0->1 resolved to edge 0 or 4 with distance 1",
                (p.parentEdge[1] == 0 || p.parentEdge[1] == 4) && p.distance[1] == 1);

        // vertex 0 is unreachable from source 1 but has an edge 0->2 of weight -100:
        // relaxing from an unreachable vertex would wrongly give dist(2) = -100.
        int[][] e = {{0, 1, 5}, {1, 2, -3}, {1, 2, -3}, {1, 3, 10}, {2, 3, 7}, {0, 2, -100}};
        Map<String, Object> r = Algorithms.dag(graphRequest("dag", 4, e, 1));
        evidence("dag unreachable-relax guard actual: " + Json.write(r)
                + "   expected distance [null,0,-3,4]");
        Long[] d = (Long[]) r.get("distance");
        check("dag never relaxes from unreachable vertex", d[0] == null && d[1] == 0 && d[2] == -3 && d[3] == 4);
        check("dag guard case witness valid", Check.dagValid(4, e, 1, d, (int[]) r.get("parent_edge"),
                Check.bellmanFord(4, e, 1)));

        r = Algorithms.dag(graphRequest("dag", 4, new int[][] {{0, 1, 2}, {2, 3, 1}, {3, 2, 1}}, 0));
        check("dag cycle unreachable from source -> acyclic=false, empty arrays",
                Json.write(r).equals("{\"acyclic\":false,\"distance\":[],\"parent_edge\":[]}"));
        r = Algorithms.dag(graphRequest("dag", 2, new int[][] {{0, 1, 3}}, 1));
        check("dag source with only outgoing-to-nothing: unreachable is null, not -1",
                Json.write(r).equals("{\"acyclic\":true,\"distance\":[null,0],\"parent_edge\":[-1,-1]}"));
        r = Algorithms.dag(graphRequest("dag", 1, new int[0][], 0));
        check("dag singleton", Json.write(r).equals("{\"acyclic\":true,\"distance\":[0],\"parent_edge\":[-1]}"));

        // helper robustness: an invalid parent array must be rejected, not loop forever
        boolean threw = false;
        try {
            Algorithms.reconstructRoute(ex, new int[] {-1, 0, 0, 3, -1}, 0, 3); // edge 3 ends at 3 (2->3); edge 0 for vertex 2 ends at 1
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        check("route helper rejects a parent edge that does not end at its vertex", threw);
    }

    private static long routeCost(int[][] edges, int[] parentEdge, int[] route) {
        long cost = 0;
        for (int i = 1; i < route.length; i++) {
            int id = parentEdge[route[i]];
            if (edges[id][0] != route[i - 1] || edges[id][1] != route[i]) {
                throw new IllegalStateException("edge " + id + " is not " + route[i - 1] + "->" + route[i]);
            }
            cost += edges[id][2];
        }
        return cost;
    }

    // ------------------------------------------------------------------ checker self-tests
    private static void checkerSelfTests() {
        // Alternative valid orders are accepted, invalid ones rejected.
        int[][] e = {{3, 1}, {1, 0}, {4, 0}};
        check("checker accepts topo order A", Check.topoValid(5, e, new int[] {4, 3, 2, 1, 0}));
        check("checker accepts a different valid topo order B", Check.topoValid(5, e, new int[] {2, 3, 1, 4, 0}));
        check("checker rejects order violating 3->1", !Check.topoValid(5, e, new int[] {1, 3, 4, 2, 0}));
        check("checker rejects order with a repeated vertex", !Check.topoValid(5, e, new int[] {4, 3, 3, 1, 0}));

        // Forests: two different optimal forests accepted; cycle / wrong cost / missing edge rejected.
        int[][] t = {{0, 1, 1}, {1, 2, 1}, {0, 2, 1}};
        check("checker accepts forest {0,1}", Check.forestValid(3, t, new int[] {0, 1}, 2, 1));
        check("checker accepts alternative forest {1,2}", Check.forestValid(3, t, new int[] {1, 2}, 2, 1));
        check("checker rejects forest with a cycle", !Check.forestValid(3, t, new int[] {0, 1, 2}, 3, 1));
        check("checker rejects wrong cost", !Check.forestValid(3, t, new int[] {0, 1}, 3, 1));
        check("checker rejects too few edges", !Check.forestValid(3, t, new int[] {0}, 1, 1));
        check("checker rejects repeated edge id", !Check.forestValid(3, t, new int[] {0, 0}, 2, 1));

        // SCC: relabelled answer accepted; merged SCCs and wrong condensation rejected.
        int[][] g = {{0, 1}, {1, 0}, {1, 2}, {0, 2}, {2, 3}};
        List<int[]> good = new ArrayList<>(List.of(new int[] {5, 7}, new int[] {7, 9}));
        check("checker accepts labels [5,5,7,9]", Check.sccValid(4, g, new int[] {5, 5, 7, 9}, good));
        check("checker accepts other labels [0,0,1,2]",
                Check.sccValid(4, g, new int[] {0, 0, 1, 2}, List.of(new int[] {0, 1}, new int[] {1, 2})));
        check("checker rejects merged SCCs", !Check.sccValid(4, g, new int[] {5, 5, 5, 9}, List.of(new int[] {5, 9})));
        check("checker rejects missing condensation pair",
                !Check.sccValid(4, g, new int[] {5, 5, 7, 9}, List.of(new int[] {5, 7})));
        check("checker rejects duplicate condensation pair",
                !Check.sccValid(4, g, new int[] {5, 5, 7, 9},
                        List.of(new int[] {5, 7}, new int[] {5, 7}, new int[] {7, 9})));
        check("checker rejects internal edge in condensation",
                !Check.sccValid(4, g, new int[] {5, 5, 7, 9},
                        List.of(new int[] {5, 5}, new int[] {5, 7}, new int[] {7, 9})));

        // DAG witness: valid parent array accepted, tied alternative accepted, bad witnesses rejected.
        int[][] tied = {{0, 1, 1}, {0, 2, 1}, {1, 3, 1}, {2, 3, 1}};
        long[] ref = Check.bellmanFord(4, tied, 0);
        Long[] dist = {0L, 1L, 1L, 2L};
        check("checker accepts parent via 1->3", Check.dagValid(4, tied, 0, dist, new int[] {-1, 0, 1, 2}, ref));
        check("checker accepts tied parent via 2->3", Check.dagValid(4, tied, 0, dist, new int[] {-1, 0, 1, 3}, ref));
        check("checker rejects parent edge not ending at vertex",
                !Check.dagValid(4, tied, 0, dist, new int[] {-1, 0, 1, 0}, ref));
        check("checker rejects parent edge that is not tight",
                !Check.dagValid(4, tied, 0, new Long[] {0L, 1L, 1L, 5L}, new int[] {-1, 0, 1, 3}, ref));
        check("checker rejects -1 parent for a reachable vertex",
                !Check.dagValid(4, tied, 0, dist, new int[] {-1, 0, 1, -1}, ref));
        check("checker rejects wrong distance", !Check.dagValid(4, tied, 0, new Long[] {0L, 1L, 1L, 3L},
                new int[] {-1, 0, 1, 2}, ref));
    }

    // ------------------------------------------------------------------ randomized vs references
    private static int[][] randomEdges(Random rnd, int n, int m, int maxW, boolean weighted) {
        int[][] e = new int[m][];
        for (int i = 0; i < m; i++) {
            int w = weighted ? rnd.nextInt(2 * maxW + 1) - maxW : 0;
            e[i] = weighted ? new int[] {rnd.nextInt(n), rnd.nextInt(n), w}
                            : new int[] {rnd.nextInt(n), rnd.nextInt(n)};
        }
        return e;
    }

    private static void randomizedTests() {
        Random rnd = new Random(20261022L);
        int bad = 0;
        int cases = 3000;
        for (int it = 0; it < cases; it++) { // spanning forests (tiny weight range => many ties)
            int n = rnd.nextInt(8);
            int m = n == 0 ? 0 : rnd.nextInt(13);
            int[][] e = randomEdges(rnd, Math.max(n, 1), m, 4, true);
            if (n == 0) {
                e = new int[0][];
            }
            long best = Check.bruteForestCost(n, e);
            for (int which = 0; which < 2; which++) {
                Map<String, Object> req = graphRequest(which == 0 ? "kruskal" : "prim", n, e, null);
                Map<String, Object> r = which == 0 ? Algorithms.kruskal(req) : Algorithms.prim(req);
                int[] ids = (int[]) r.get("edge_ids");
                long cost = (Long) r.get("cost");
                if (!Check.forestValid(n, e, ids, cost, (Integer) r.get("components")) || cost != best) {
                    bad++;
                }
            }
        }
        check("random Kruskal/Prim vs brute-force optimum (" + cases + " graphs, ties+negatives+loops)", bad == 0);

        bad = 0;
        for (int it = 0; it < cases; it++) { // SCC and topo vs Floyd-Warshall
            int n = rnd.nextInt(9);
            int m = n == 0 ? 0 : rnd.nextInt(16);
            int[][] e = n == 0 ? new int[0][] : randomEdges(rnd, n, m, 0, false);
            Map<String, Object> r = Algorithms.scc(graphRequest("scc", n, e, null));
            if (!Check.sccValid(n, e, (int[]) r.get("component"), cond(r))) {
                bad++;
            }
            r = Algorithms.topo(graphRequest("topo", n, e, null));
            boolean cyc = Check.hasCycle(n, e);
            boolean acyclic = (Boolean) r.get("acyclic");
            int[] order = (int[]) r.get("order");
            if (acyclic == cyc || (acyclic ? !Check.topoValid(n, e, order) : order.length != 0)) {
                bad++;
            }
        }
        check("random SCC + topo vs Floyd-Warshall (" + cases + " graphs)", bad == 0);

        bad = 0;
        int dagCases = 3000;
        for (int it = 0; it < dagCases; it++) { // DAG shortest paths vs Bellman-Ford
            int n = 1 + rnd.nextInt(8);
            int m = rnd.nextInt(16);
            int[] perm = new int[n];
            for (int i = 0; i < n; i++) {
                perm[i] = i;
            }
            for (int i = n - 1; i > 0; i--) {
                int j = rnd.nextInt(i + 1);
                int t = perm[i]; perm[i] = perm[j]; perm[j] = t;
            }
            int[] pos = new int[n];
            for (int i = 0; i < n; i++) {
                pos[perm[i]] = i;
            }
            List<int[]> list = new ArrayList<>();
            for (int i = 0; i < m && n > 1; i++) {
                int a = rnd.nextInt(n);
                int b = rnd.nextInt(n);
                if (a == b) {
                    continue;
                }
                if (pos[a] > pos[b]) {
                    int t = a; a = b; b = t;
                }
                list.add(new int[] {a, b, rnd.nextInt(11) - 5});
            }
            boolean makeCyclic = rnd.nextInt(5) == 0 && n > 1;
            if (makeCyclic) { // add a back edge along an existing forward path => directed cycle
                int a = perm[0];
                int b = perm[n - 1];
                list.add(new int[] {a, b, 1});
                list.add(new int[] {b, a, 1});
            }
            int[][] e = list.toArray(new int[0][]);
            int source = rnd.nextInt(n);
            Map<String, Object> r = Algorithms.dag(graphRequest("dag", n, e, source));
            boolean cyc = Check.hasCycle(n, Arrays.stream(e).map(x -> new int[] {x[0], x[1]}).toArray(int[][]::new));
            boolean acyclic = (Boolean) r.get("acyclic");
            if (acyclic == cyc) {
                bad++;
            } else if (acyclic && !Check.dagValid(n, e, source, (Long[]) r.get("distance"),
                    (int[]) r.get("parent_edge"), Check.bellmanFord(n, e, source))) {
                bad++;
            }
        }
        check("random DAG shortest paths vs Bellman-Ford incl. cycles (" + dagCases + " graphs)", bad == 0);

        bad = 0;
        for (int it = 0; it < 2000; it++) { // DSU vs naive relabelling
            int n = rnd.nextInt(12);
            int q = n == 0 ? 0 : rnd.nextInt(20);
            int[][] ops = n == 0 ? new int[0][] : randomEdges(rnd, n, q, 0, false);
            int[] lab = new int[n];
            for (int i = 0; i < n; i++) {
                lab[i] = i;
            }
            int[][] expected = new int[q][];
            for (int i = 0; i < q; i++) {
                int a = lab[ops[i][0]];
                int b = lab[ops[i][1]];
                if (a != b) {
                    for (int v = 0; v < n; v++) {
                        if (lab[v] == b) {
                            lab[v] = a;
                        }
                    }
                }
                int[] count = new int[n];
                int comps = 0;
                int largest = 0;
                for (int v = 0; v < n; v++) {
                    if (count[lab[v]]++ == 0) {
                        comps++;
                    }
                    largest = Math.max(largest, count[lab[v]]);
                }
                expected[i] = new int[] {comps, largest};
            }
            int[][] actual = (int[][]) Algorithms.dsu(dsuRequest(n, ops)).get("states");
            if (!Arrays.deepEquals(actual, expected)) {
                bad++;
            }
        }
        check("random DSU vs naive relabelling (2000 sequences)", bad == 0);
    }

    // ------------------------------------------------------------------ scale
    private static long millis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static void largeTests() {
        final int N = 100_000;
        final int M = 250_000;
        Random rnd = new Random(7);

        // DSU: q = 250000 random unions; final state cross-checked with an independent BFS
        int[][] ops = randomEdges(rnd, N, M, 0, false);
        long t0 = System.nanoTime();
        int[][] states = (int[][]) Algorithms.dsu(dsuRequest(N, ops)).get("states");
        long ms = millis(t0);
        int[] last = states[states.length - 1];
        int comps = Check.componentsBfs(N, ops);
        boolean monotone = true;
        for (int i = 1; i < states.length; i++) {
            monotone &= states[i][0] <= states[i - 1][0] && states[i][1] >= states[i - 1][1];
        }
        check("scale dsu n=1e5 q=2.5e5 (" + ms + " ms): final components match BFS (" + comps + ")",
                states.length == M && last[0] == comps && monotone);
        // deep chain of unions (worst case for naive find)
        int[][] chainOps = new int[N - 1][];
        for (int i = 0; i + 1 < N; i++) {
            chainOps[i] = new int[] {i, i + 1};
        }
        states = (int[][]) Algorithms.dsu(dsuRequest(N, chainOps)).get("states");
        check("scale dsu chain of 99999 unions -> 1 component of size 1e5",
                states[N - 2][0] == 1 && states[N - 2][1] == N);

        // Kruskal / Prim on a random graph at the published limits, weights +-1e9
        int[][] w = randomEdges(rnd, N, M, 1_000_000_000, true);
        t0 = System.nanoTime();
        Map<String, Object> k = Algorithms.kruskal(graphRequest("kruskal", N, w, null));
        long kMs = millis(t0);
        t0 = System.nanoTime();
        Map<String, Object> p = Algorithms.prim(graphRequest("prim", N, w, null));
        long pMs = millis(t0);
        boolean kOk = Check.forestValid(N, w, (int[]) k.get("edge_ids"), (Long) k.get("cost"), (Integer) k.get("components"));
        boolean pOk = Check.forestValid(N, w, (int[]) p.get("edge_ids"), (Long) p.get("cost"), (Integer) p.get("components"));
        check("scale kruskal n=1e5 m=2.5e5 (" + kMs + " ms): valid forest", kOk);
        check("scale prim n=1e5 m=2.5e5 (" + pMs + " ms): valid forest", pOk);
        check("scale Kruskal and Prim agree on optimum cost and components",
                k.get("cost").equals(p.get("cost")) && k.get("components").equals(p.get("components")));

        // 64-bit totals: path of 1e5 vertices, every weight +1e9 and -1e9
        for (int sign = -1; sign <= 1; sign += 2) {
            int[][] path = new int[N - 1][];
            for (int i = 0; i + 1 < N; i++) {
                path[i] = new int[] {i, i + 1, sign * 1_000_000_000};
            }
            long expected = (long) sign * 1_000_000_000L * (N - 1);
            boolean ok = (Long) Algorithms.kruskal(graphRequest("kruskal", N, path, null)).get("cost") == expected
                    && (Long) Algorithms.prim(graphRequest("prim", N, path, null)).get("cost") == expected;
            check("scale 64-bit MST total " + expected + " (path, both methods)", ok);
        }

        // deep chain 0->1->...->N-1 (no recursion allowed)
        int[][] chain = new int[N - 1][];
        int[][] weighted = new int[N - 1][];
        for (int i = 0; i + 1 < N; i++) {
            chain[i] = new int[] {i, i + 1};
            weighted[i] = new int[] {i, i + 1, -1_000_000_000};
        }
        t0 = System.nanoTime();
        Map<String, Object> r = Algorithms.scc(graphRequest("scc", N, chain, null));
        ms = millis(t0);
        int[] label = (int[]) r.get("component");
        boolean distinct = Arrays.stream(label).distinct().count() == N;
        check("scale scc deep chain 1e5 (" + ms + " ms): 1e5 singleton SCCs, 99999 condensation edges, exact",
                distinct && cond(r).size() == N - 1 && Check.condensationExact(chain, label, cond(r)));
        int[][] ring = Arrays.copyOf(chain, N);
        ring[N - 1] = new int[] {N - 1, 0};
        r = Algorithms.scc(graphRequest("scc", N, ring, null));
        label = (int[]) r.get("component");
        check("scale scc ring of 1e5 -> one SCC, empty condensation",
                Arrays.stream(label).distinct().count() == 1 && cond(r).isEmpty());
        // random graph at the limits: exactness of the condensation against labels
        int[][] rg = randomEdges(rnd, N, M, 0, false);
        r = Algorithms.scc(graphRequest("scc", N, rg, null));
        label = (int[]) r.get("component");
        check("scale scc random n=1e5 m=2.5e5: condensation exact, no duplicates",
                Check.condensationExact(rg, label, cond(r)));

        t0 = System.nanoTime();
        r = Algorithms.topo(graphRequest("topo", N, chain, null));
        ms = millis(t0);
        check("scale topo deep chain 1e5 (" + ms + " ms): valid order",
                (Boolean) r.get("acyclic") && Check.topoValid(N, chain, (int[]) r.get("order")));
        r = Algorithms.topo(graphRequest("topo", N, ring, null));
        check("scale topo ring 1e5 -> cyclic", !(Boolean) r.get("acyclic") && ((int[]) r.get("order")).length == 0);

        t0 = System.nanoTime();
        r = Algorithms.dag(graphRequest("dag", N, weighted, 0));
        ms = millis(t0);
        Long[] dist = (Long[]) r.get("distance");
        long expectedLast = -1_000_000_000L * (N - 1);
        check("scale dag deep chain 1e5 (" + ms + " ms): distance " + expectedLast,
                (Boolean) r.get("acyclic") && dist[N - 1] == expectedLast);
        int[] route = Algorithms.reconstructRoute(weighted, (int[]) r.get("parent_edge"), 0, N - 1);
        check("scale route helper on 1e5-vertex chain", route.length == N && route[0] == 0 && route[N - 1] == N - 1);

        // random DAG at the limits (edges forward along a hidden order) vs Bellman-Ford is too slow;
        // instead verify witnesses with the DP-free checker against an independent topological relaxation.
        int[][] dg = new int[M][];
        for (int i = 0; i < M; i++) {
            int a = rnd.nextInt(N - 1);
            int b = a + 1 + rnd.nextInt(Math.min(50, N - 1 - a));
            dg[i] = new int[] {a, b, rnd.nextInt(2_000_001) - 1_000_000};
        }
        r = Algorithms.dag(graphRequest("dag", N, dg, 0));
        dist = (Long[]) r.get("distance");
        int[] parent = (int[]) r.get("parent_edge");
        long[] ref = new long[N]; // independent DP: edges point to higher indices, so index order is topological
        Arrays.fill(ref, Check.INF);
        ref[0] = 0;
        int[][] byStart = dg.clone();
        Arrays.sort(byStart, (x, y) -> Integer.compare(x[0], y[0]));
        for (int[] e : byStart) {
            if (ref[e[0]] != Check.INF && ref[e[0]] + e[2] < ref[e[1]]) {
                ref[e[1]] = ref[e[0]] + e[2];
            }
        }
        check("scale dag random n=1e5 m=2.5e5: distances equal independent DP, parent paths valid",
                (Boolean) r.get("acyclic") && Check.dagValid(N, dg, 0, dist, parent, ref));
    }

    // ------------------------------------------------------------------ state reset
    private static void stateResetTests() {
        String[] lines = {
            "{\"task\":\"dsu\",\"n\":5,\"operations\":[[0,1],[2,3],[1,3]]}",
            "{\"task\":\"kruskal\",\"n\":4,\"edges\":[[0,1,4],[1,2,2],[0,2,3]]}",
            "{\"task\":\"prim\",\"n\":6,\"edges\":[[0,1,5],[0,1,-2],[1,2,4],[3,4,7]]}",
            "{\"task\":\"scc\",\"n\":4,\"edges\":[[0,1],[1,0],[1,2],[0,2],[2,3]]}",
            "{\"task\":\"topo\",\"n\":3,\"edges\":[[0,1],[1,2],[2,0]]}",
            "{\"task\":\"dag\",\"n\":5,\"source\":0,\"edges\":[[0,1,3],[0,2,8],[1,2,-7],[2,3,4]]}",
            "{\"task\":\"dsu\",\"n\":0,\"operations\":[]}",
            "{\"task\":\"scc\",\"n\":0,\"edges\":[]}",
        };
        String[] first = new String[lines.length];
        for (int i = 0; i < lines.length; i++) {
            first[i] = run(lines[i]);
        }
        boolean same = true;
        for (int round = 0; round < 3; round++) { // repeat forwards, then backwards, then shuffled
            Integer[] idx = new Integer[lines.length];
            for (int i = 0; i < idx.length; i++) {
                idx[i] = i;
            }
            if (round == 1) {
                java.util.Collections.reverse(Arrays.asList(idx));
            }
            if (round == 2) {
                java.util.Collections.shuffle(Arrays.asList(idx), new Random(3));
            }
            for (int i : idx) {
                same &= run(lines[i]).equals(first[i]);
            }
        }
        evidence("state reset: eight mixed requests re-run forwards/backwards/shuffled in one JVM; outputs identical = " + same);
        for (int i = 0; i < lines.length; i++) {
            evidence("  " + lines[i] + "  ->  " + first[i]);
        }
        check("state reset: mixed requests give identical results when repeated/reordered in one process", same);
    }

    private static String run(String line) {
        Map<String, Object> req = Json.parseRequest(line);
        switch ((String) req.get("task")) {
            case "dsu": return Json.write(Algorithms.dsu(req));
            case "kruskal": return Json.write(Algorithms.kruskal(req));
            case "prim": return Json.write(Algorithms.prim(req));
            case "scc": return Json.write(Algorithms.scc(req));
            case "topo": return Json.write(Algorithms.topo(req));
            default: return Json.write(Algorithms.dag(req));
        }
    }

    // ------------------------------------------------------------------ main
    public static void main(String[] args) throws IOException {
        dsuHandCases();
        forestHandCases();
        sccHandCases();
        topoHandCases();
        dagHandCases();
        checkerSelfTests();
        randomizedTests();
        largeTests();
        stateResetTests();

        System.out.println();
        System.out.println("Passed: " + passed + "  Failed: " + failed);
        Path out = Path.of(args.length > 0 ? args[0] : "tests/actual_outputs.txt");
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
            w.println("# Evidence written by tests/java/Tests.java (command: python tests/run_tests.py)");
            w.print(LOG);
            w.println("# Result: passed=" + passed + " failed=" + failed);
        }
        if (failed > 0) {
            System.exit(1);
        }
    }
}
