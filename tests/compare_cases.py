"""Run tests/my_cases.jsonl through the real program, validate semantically, write actual outputs.

Answers are compared with the hand-justified expectations in tests/my_cases.expected.jsonl using the
public semantic validator (alternative optimal forests / orders / parent edges are accepted).
It also proves the validator rejects invalid witnesses by mutating correct answers.
"""
import copy
import json
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from public_validate import check  # noqa: E402


def main():
    requests = [json.loads(l) for l in (ROOT / "tests/my_cases.jsonl").read_text(encoding="utf-8").splitlines()]
    expected = [json.loads(l) for l in (ROOT / "tests/my_cases.expected.jsonl").read_text(encoding="utf-8").splitlines()]
    sys.path.insert(0, str(ROOT / "tools"))
    import run
    command = run.build("Java")
    answers = run.execute(command, requests)
    (ROOT / "tests/my_cases.actual.jsonl").write_text(
        "".join(json.dumps(a, separators=(",", ":")) + "\n" for a in answers), encoding="utf-8")
    failures = 0
    for req, exp, ans in zip(requests, expected, answers):
        ok = check(req, ans, exp["output"])
        failures += not ok
        print(("PASS " if ok else "FAIL ") + "my_cases: " + exp["name"])

    # Negative tests: corrupted witnesses must be rejected by the checker.
    def reject(label, req, good, mutate):
        nonlocal failures
        bad = copy.deepcopy(good)
        mutate(bad)
        ok = not check(req, bad, good)
        failures += not ok
        print(("PASS " if ok else "FAIL ") + "checker rejects invalid witness: " + label)

    by_name = {e["name"]: (r, a) for r, e, a in zip(requests, expected, answers)}
    r, a = by_name["kruskal parallel negative loop isolate"]
    reject("forest containing the self-loop", r, a, lambda x: x["edge_ids"].__setitem__(0, 3))
    reject("forest with wrong cost", r, a, lambda x: x.__setitem__("cost", 10))
    r, a = by_name["scc cycles, self-loop, duplicate cross edges"]
    reject("SCC with a missing condensation pair", r, a, lambda x: x.__setitem__("condensation", []))
    reject("SCC with merged components", r, a, lambda x: x.__setitem__("component", [0, 0, 0, 0, 0, 2, 3]))
    r, a = by_name["topo duplicate edge and isolate"]
    reject("topological order violating an edge", r, a, lambda x: x.__setitem__("order", [0, 1, 2, 3, 4]))
    r, a = by_name["dag guard: do not relax from unreachable vertex"]
    reject("DAG parent edge not ending at its vertex", r, a, lambda x: x["parent_edge"].__setitem__(3, 1))
    reject("DAG reachable vertex with no parent", r, a, lambda x: x["parent_edge"].__setitem__(2, -1))
    print("my_cases summary:", "OK" if not failures else f"{failures} failure(s)")
    return int(bool(failures))


if __name__ == "__main__":
    sys.exit(main())
