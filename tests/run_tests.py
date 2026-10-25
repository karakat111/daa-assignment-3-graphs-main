"""Compile and run all of our own tests from the repository root:

    python tests/run_tests.py

It (1) compiles src/Java + tests/java, (2) runs the Java test suite (writes tests/actual_outputs.txt),
(3) runs tests/my_cases.jsonl through the real JSON-Lines program and validates the answers semantically.
Pass --bench to also run the Kruskal-vs-Prim benchmark (writes tests/benchmark_results.txt).
"""
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
BUILD = ROOT / ".build" / "tests-java"


def run(cmd, **kw):
    print("$", " ".join(map(str, cmd)))
    return subprocess.run(cmd, check=True, cwd=ROOT, **kw)


def main():
    BUILD.mkdir(parents=True, exist_ok=True)
    sources = sorted((ROOT / "src" / "Java").glob("*.java")) + sorted((ROOT / "tests" / "java").glob("*.java"))
    run(["javac", "--release", "17", "-encoding", "UTF-8", "-d", str(BUILD), *map(str, sources)])
    run(["java", "-Xmx384m", "-cp", str(BUILD), "Tests", "tests/actual_outputs.txt"])
    run([sys.executable, "tests/compare_cases.py"])
    if "--bench" in sys.argv:
        run(["java", "-Xmx1g", "-cp", str(BUILD), "Benchmark", "tests/benchmark_results.txt"])


if __name__ == "__main__":
    main()
