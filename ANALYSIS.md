# Assignment 3 — Graph Algorithms (Java 17)

Language: **Java** (`submission.json` → `{"language":"Java"}`). Source: `src/Java/Algorithms.java` (all six methods + route helper). Supplied files `Main.java`, `Input.java`, `Json.java` are unchanged.

## 1. Build, run and test commands

All commands are run from the repository root (JDK 17+ and Python 3.12+ installed).

```sh
python tools/run.py --setup                      # compile + ping check
python tools/run.py --check                      # 36 public cases + two batches in one process
python tools/run.py --input tests/my_cases.jsonl # actual output of my hand-made cases
python tests/run_tests.py                        # compile src+tests, run own tests (75 checks) and my_cases validation
python tests/run_tests.py --bench                # additionally run the Kruskal-vs-Prim benchmark
```

Results of the last run: `--check` → every public case `PASS` and `PASS state reset`; `tests/run_tests.py` → `Passed: 75  Failed: 0` and `my_cases summary: OK`. In my development container, a full-limit batch (DSU q=250 000, Kruskal and Prim n=100 000 m=250 000, SCC n=100 000 m=250 000, topo and DAG on a 100 000-vertex chain) ran end to end through `tools/run.py --input` (compile + JVM start + parsing + output included, `-Xmx384m`) in about 4.1 s wall time, far below the 20 s limit.

Files in `tests/`: `java/Tests.java` (runner), `java/Check.java` (independent checkers/references), `java/Benchmark.java`, `run_tests.py`, `my_cases.jsonl` (inputs), `my_cases.expected.jsonl` (hand-justified expectations with a `why` field), `my_cases.actual.jsonl` (actual outputs), `compare_cases.py`, `actual_outputs.txt` (evidence from `Tests`), `benchmark_results.txt`.

## 2. Common design

* **Representation.** Edge IDs are the row indices of `edges`. Adjacency is stored in compressed arrays (CSR): `start[v]..start[v+1]-1` index parallel arrays `target[]` and `edgeId[]`, so sorting/adjacency never loses the original ID. Undirected graphs insert each edge twice (the same ID in both directions).
* **Arithmetic.** Sums and distances are `long`. Weights are `int` in `[-1e9, 1e9]`; with at most 250 000 edges the extreme total is 2.5e14, far from 2^63.
* **State reset.** There is no static mutable state. Every method allocates its arrays, heap, hash set and DSU locally from the request, so a request cannot see an earlier request. `Tests.stateResetTests` runs eight mixed requests, then repeats them forwards, backwards and shuffled in one JVM and compares output strings (identical = true; see `tests/actual_outputs.txt`); `tools/run.py --check` additionally runs the public cases twice in one process.
* **Deep DFS without recursion.** Pass 1 of Kosaraju and the topological DFS use an explicit stack of vertices plus a per-vertex `cursor[v]` into its adjacency range. A suspended vertex therefore resumes exactly where it stopped, each edge is examined once (no rescanning), and the depth is limited by heap, not by the call stack. Tests run chains of 100 000 vertices.

## 3. Methods

### 3.1 DSU (`dsu`)
* **Steps.** `parent[i]=i`, `size[i]=1`, `components=n`, `largest = n>0 ? 1 : 0`. `find` walks to the root, then a second walk points every vertex on the path directly at the root (path compression, iterative). `union` finds both roots; if equal it does nothing, otherwise attaches the smaller tree under the larger (union by **size**), adds the sizes, decrements `components` and updates `largest`. After each operation `[components, largest]` is appended; there is no initial state.
* **Invariant.** Each tree is one component; `size[root]` is the exact vertex count; `components` is the number of roots; `largest` is the maximum root size (sizes only grow, so a running maximum is enough).
* **Correctness.** Repeated and self unions give equal roots, so nothing changes. Because sizes are maintained explicitly, `largest` is a real size (not a rank).
* **Boundary cases.** `n=0` → `states=[]`; `n>0` with no operations → `[]`; self-union; repeated union; unions on the same set.
* **Complexity.** `O(n + q·α(n))` amortised time; `O(n+q)` memory (the `q` states are part of the result).

### 3.2 Kruskal (`kruskal`)
* **Steps.** Pack every edge as `((w+1e9) << 20) | id` into one `long` (non-negative, 2^20 > 250 000 IDs), sort the primitive array (ties break by ID), then scan: accept an edge when `union(u,v)` succeeds, i.e. `find(u) != find(v)`. `components` is what remains in the DSU.
* **Invariant.** After processing a prefix of the sorted edges the accepted edges are a minimum spanning forest of that prefix (cut/cycle property).
* **Correctness.** The cycle property: an edge closing a cycle is a heaviest-or-tied edge of that cycle in sorted order, so it is never needed; the cut property shows an accepted edge is the lightest crossing its cut. Negative weights are harmless (only order matters). Self-loops fail `find(u)==find(u)`; parallel edges are rejected once their endpoints are joined. The result has exactly `n-c` edges.
* **Boundary cases.** `n=0`; no edges; only isolated vertices (`components=n`); self-loops; parallel edges; ties; totals beyond 32 bits (3e9 and ±1e5·1e9).
* **Complexity.** `O(n + m log m)` time (sort dominates), `O(n+m)` memory.

### 3.3 Prim (`prim`)
* **Steps.** Build adjacency lists; for every unvisited vertex start a new component: mark it, push its incident edges into a **lazy binary min-heap** (own `long` heap, key = weight/ID as above). Pop the smallest edge; if both endpoints are already visited (cycle edge, self-loop, stale entry) discard it, otherwise visit the new endpoint, record the edge ID and weight, push the new vertex's edges to unvisited neighbours. When the heap is empty the component is complete; continue with the next unvisited vertex.
* **Invariant.** The visited set of the current component is a tree contained in a minimum spanning tree, and the heap holds all edges leaving it (plus stale ones).
* **Correctness.** The popped non-stale edge is the lightest edge crossing the cut (visited, unvisited), so the cut property applies. Restarting handles disconnected graphs; isolated vertices form components with no edges.
* **Boundary cases.** Same as Kruskal, plus the restart (`components` counts starts).
* **Complexity.** `O(n + m log m)` time (every edge is pushed at most twice), `O(n+m)` memory.

### 3.4 Kosaraju SCC (`scc`)
* **Steps.** (1) Build `G` and its transpose. (2) Iterative DFS over all vertices of `G`, recording vertices in finishing order. (3) Process vertices in decreasing finish order; each still-unlabelled vertex starts a traversal on the **transpose**, labelling everything it reaches with the next label. (4) Condensation: for each edge `(a,b)` with different labels, add `(label[a],label[b])` once, using a `HashSet<Long>` keyed by `(a<<32)|b`.
* **Invariant.** After pass 1, the vertex finishing last lies in a source SCC of the condensation; each pass-2 traversal on the transpose cannot leave its SCC because every SCC reachable in the transpose from it was already labelled.
* **Correctness.** Standard Kosaraju argument: if SCC `C` has an edge to SCC `C'` in `G`, then the maximum finish time in `C` exceeds that in `C'`; hence in decreasing order the traversal on the transpose starts in a sink-in-the-reversed order and collects exactly one SCC. Verified against Floyd–Warshall reachability on 3 000 random graphs (equal labels iff mutual reachability, condensation == exact cross-pair set).
* **Boundary cases.** `n=0`; isolates; self-loops (internal, dropped from the condensation); duplicate edges (one pair); long chain; one big ring.
* **Complexity.** `O(n+m)` expected time (hash dedupe), `O(n+m)` memory.

### 3.5 DFS topological order (`topo`)
* **Steps.** States 0/1/2 (unvisited/active/finished). Iterative DFS with a per-vertex cursor from every unvisited root. An edge to a state-1 vertex means a cycle → return `acyclic=false`, `order=[]`. An edge to a state-2 vertex is ignored. When a vertex's edges are exhausted it becomes state 2 and is written at `order[--placed]`, i.e. reverse finishing order is built directly.
* **Invariant.** The stack is exactly the active path (state 1); finished vertices have all descendants finished.
* **Correctness.** A directed graph has a cycle iff some DFS finds an edge to an active vertex (back edge). Without back edges every edge `u→v` finishes `v` before `u`, so reverse finishing order puts `u` first. A self-loop `u→u` hits state 1 immediately. Checked against Floyd–Warshall cycle detection on 3 000 random graphs and with an explicit "diamond with shortcut" test showing that an edge into a finished vertex is not a cycle.
* **Boundary cases.** `n=0` → `true, []`; self-loop; cycle in a later component; duplicate edges; isolates.
* **Complexity.** `O(n+m)` time and memory.

### 3.6 DAG shortest paths (`dag`) and route helper
* **Steps.** Run the topological DFS on the **whole** graph (so a cycle anywhere, even unreachable from the source, gives `acyclic=false`, `distance=[]`, `parent_edge=[]`). Otherwise set `reachable[source]=true`, `distance[source]=0`, `parent_edge=-1` and process vertices in topological order; **only reachable** vertices relax their outgoing edges: if `w` is unreachable or `dist[v]+weight < dist[w]`, update `dist[w]` and `parent_edge[w]=edge ID`. Unreachable vertices keep `parent_edge=-1` and are written as JSON `null`.
* **Invariant.** When `v` is processed its distance is final: all its predecessors precede it in topological order and have already relaxed into it.
* **Correctness.** Dynamic programming over a DAG; negative weights are fine because order, not greed, drives it. A separate `reachable` flag (never a sentinel like `-1`) prevents confusing a legitimate distance of `-1` with "unreachable" and prevents relaxing from unreachable vertices (test: vertex 0 unreachable with edge `0→2` of weight −100 does not change `dist(2)`).
* **Route helper.** `Algorithms.reconstructRoute(edges, parentEdge, source, target)` walks `parentEdge` from the target back to the source and reverses the sequence. **No-route convention: an unreachable target returns an empty array.** The source returns `[source]`. It throws `IllegalArgumentException` if a parent edge does not end at its vertex or the chain does not reach the source (cycle guard).
* **Boundary cases.** `n=1`; self-loop; unreachable cycle; unreachable vertices; parallel/tied edges; distances beyond 32 bits (−1e9·99 999 on a chain).
* **Complexity.** `O(n+m)` time and memory; route reconstruction `O(L)`.

#### Route-helper evidence (from `tests/actual_outputs.txt`)

| Case | Call | Result | Check |
|---|---|---|---|
| README example (edges `0→1:3, 0→2:8, 1→2:-7, 2→3:4`, source 0) | route to 3 (reachable) | `[0,1,2,3]` via edges 0,2,3 | each edge goes forward in direction; total 3−7+4 = **0** = `distance[3]` |
| same | route to 0 (source) | `[0]` | — |
| same | route to 4 (unreachable) | `[]` (documented no-route value); JSON `distance[4]` is `null` | — |
| tied/parallel (edges `0→1:1, 0→2:1, 1→3:1, 2→3:1, 0→1:1`, source 0) | route to 3 | `[0,2,3]` via edges 1,3 (the tie `[0,1,3]` is equally valid) | total 1+1 = **2** = `distance[3]` |
| same | route to 1 | `[0,1]` via parallel edge **0** (edge 4 is an equal alternative) | cost 1 = `distance[1]` |

`distance=[0,3,-4,0,null]`, `parent_edge=[-1,0,2,3,-1]` for the README example (identical to the published output).

## 4. Tests (details in `tests/`)

| Group | What is tested | How the expected answer is justified |
|---|---|---|
| Hand cases (`my_cases.jsonl`, 13 requests; `Tests` hand cases) | `n=0`, isolates, repeated/self unions, parallel/negative/self-loop edges, ties, 64-bit totals, SCC with cycles/self-loop/duplicate cross edges, topo with duplicate edges/self-loop/cycle in later component, DAG with unreachable vertex having a negative edge, unreachable cycle, null vs −1 | Derived by hand in `my_cases.expected.jsonl` (`why` field) |
| Randomized | 3 000 graphs Kruskal/Prim vs brute force over all edge subsets (weights −4..4 for many ties); 3 000 SCC + topo vs Floyd–Warshall; 3 000 DAGs (with cycles injected) vs Bellman–Ford; 2 000 DSU sequences vs naive relabelling | Independent references in `Check.java`, fixed seed 20261022 |
| Semantic checking of non-unique answers | Forest feasibility + optimum, SCC labels + exact condensation, topological precedence, distances + parent paths | `Check.java`; self-tests show it **accepts alternative valid answers** (two different optimal forests, two valid topological orders, relabelled SCCs, tied parent edges) and **rejects invalid witnesses** (cycle in forest, wrong cost, repeated ID, merged SCCs, missing/duplicate/internal condensation pair, order violating an edge, parent edge not ending at its vertex, non-tight parent, missing parent). `compare_cases.py` repeats the rejection test with the public validator. |
| Scale (published limits) | DSU n=1e5 q=2.5e5 (+ chain of unions); Kruskal/Prim n=1e5 m=2.5e5 with ±1e9 weights (valid forests, equal cost); 64-bit totals ±99 999·1e9; SCC/topo/DAG on chains of 1e5 vertices; SCC ring; random SCC n=1e5 m=2.5e5; random DAG n=1e5 m=2.5e5 vs an independent DP | See `tests/actual_outputs.txt` and the console output of `run_tests.py` |
| State reset | Mixed request sequence repeated/reordered in one JVM; public checks twice in one process | Identical outputs |

## 5. Benchmark: Kruskal vs Prim on identical graphs

* **Generator.** `tests/java/Benchmark.java`, `java.util.Random(20261022 + n)`; `m` edges with endpoints uniform in `[0,n)` and weights uniform in `[-1 000 000, 1 000 000]` (self-loops and parallel edges possible). Both methods receive the same edge array.
* **Verification first.** For each size both results were checked with `Check.forestValid` (distinct IDs, acyclic, `n−c` edges, spans every component, recomputed cost) and the costs/component counts were compared: equal.
* **Timed region (same for both).** One call `Algorithms.kruskal(request)` / `Algorithms.prim(request)` on an already-parsed request. **Included:** conversion `List → int[][]` (`Input.edges`), building DSU / adjacency lists / heap, the algorithm, building the result map. **Excluded:** graph generation, JSON text parsing and printing, I/O, JVM start-up, compilation.
* **Protocol.** `System.nanoTime`; 5 untimed warm-up runs per method and size; 7 timed runs per method, alternating Kruskal/Prim; the median is reported. Command: `python tests/run_tests.py --bench` (or `java -Xmx1g -cp .build/tests-java Benchmark tests/benchmark_results.txt`).
* **Hardware/runtime actually used.** Windows 11 amd64, 12 logical processors reported by `Runtime.availableProcessors()`, Java HotSpot(TM) 64-Bit Server VM 25.0.1 (compiled with `javac --release 17`). The CPU model is not recorded by the benchmark program. Numbers are from this machine only; decimal commas in `tests/benchmark_results.txt` are the Windows locale's formatting.

| n | m | components | optimal cost | Kruskal timings (ms) | Kruskal median | Prim timings (ms) | Prim median |
|---:|---:|---:|---:|---|---:|---|---:|
| 1 000 | 5 000 | 1 | −766 772 895 | 0.85, 1.15, 1.36, 1.49, 0.63, 0.95, 0.49 | **0.95** | 1.41, 1.26, 1.57, 1.41, 0.90, 1.20, 0.76 | **1.26** |
| 20 000 | 100 000 | 2 | −15 167 102 236 | 9.79, 9.90, 11.58, 12.08, 8.79, 8.91, 9.54 | **9.79** | 18.88, 19.01, 26.68, 27.96, 17.65, 17.45, 17.12 | **18.88** |
| 100 000 | 250 000 | 655 | −52 922 239 178 | 27.93, 27.40, 28.55, 28.33, 26.03, 26.31, 28.59 | **27.93** | 66.72, 108.87, 76.74, 70.05, 67.73, 71.33, 112.30 | **71.33** |

`Input.edges` conversion alone (included in both columns) had medians of 0.10, 1.37 and 3.77 ms.

**Observations.** At all three sizes Kruskal's median was lower than Prim's: about 1.3× at n=1 000, 1.9× at n=20 000 and 2.6× at n=100 000, so the gap widens as the graph grows. Both methods have the same asymptotic bound `O(n + m log m)` (Prim with a lazy heap), so the difference is a constant-factor and memory-access effect of this implementation, not an asymptotic one: Kruskal sorts a primitive `long[]` and then runs a tight DSU loop, whereas Prim performs up to `2m` heap pushes and pops with irregular access through the adjacency arrays and the `visited` array. Growth from n=20 000 to n=100 000 (5× vertices, 2.5× edges) is 2.9× for Kruskal and 3.8× for Prim, which is compatible with the `m log m` terms plus cache effects.

**Limitations.** One machine and one random sparse graph family (average degree 5–10); denser graphs, other weight distributions or an indexed-heap Prim could change the ratio. The JVM is not quiescent, so single slow runs occur (for example Prim at n=100 000 took 108.87 and 112.30 ms against a typical 67–77 ms), which is why medians are reported. Only seven timed runs were taken, so the n=1 000 difference (about 0.3 ms) is within noise; the n=20 000 and n=100 000 gaps are much larger than the run-to-run spread.

## 6. Sources, AI tools and verification

* **Algorithms** follow the descriptions in the assignment text and standard textbook material (Cormen et al., *Introduction to Algorithms*: DSU, Kruskal, Prim, SCC, topological sort, DAG shortest paths).
* **AI tools.** Claude explain this assignment and help understand.
* **Verification actually performed.** All commands from section 1 were run on the student's Windows 11 machine (Python 3.13, JDK 25 compiling with `--release 17`): `python tools/run.py --setup` printed `PASS setup: ping`; `python tools/run.py --check` passed all 36 public cases and the state-reset check; `python tests/run_tests.py` reported `Passed: 75  Failed: 0` and `my_cases summary: OK`; `python tests/run_tests.py --bench` produced the benchmark in section 5 (raw output in `tests/benchmark_results.txt`). The code was first developed and tested in a Linux container with the same results.
* **Responsibility.** The submitting student is responsible for the content of this repository and must be able to explain every algorithm.
