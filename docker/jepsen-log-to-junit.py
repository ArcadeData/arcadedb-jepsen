#!/usr/bin/env python3
"""Convert the console log of a run-*-tests.sh sweep into a JUnit XML report.

The sweep scripts print one block per test:

    TEST 3/20: workload=bank nemesis=kill (90s) [extra flags]
    Started at: <date>
    ... lein run output, including the Jepsen ":valid? ..." verdict ...
    Result: PASS|FAIL|UNKNOWN
    Finished at: <date>

Each block becomes one <testcase>. FAIL and UNKNOWN are reported as failures
(the sweeps count both as failed); a block cut short with no "Result:" line
(sweep crashed or was cancelled) is reported as an error. The tail of the
test's output is attached to every non-passing case so the report names the
cause without downloading the Jepsen store.

Usage: jepsen-log-to-junit.py <suite-name> <sweep-log> <junit-xml-out>
"""

import re
import sys
import xml.etree.ElementTree as ET
from datetime import datetime

TEST_RE = re.compile(r"^TEST (\d+)/(\d+): (.*?)(?: \((\d+)s\)(.*))?$")
RESULT_RE = re.compile(r"^Result: (PASS|FAIL|UNKNOWN)\s*$")
STARTED_RE = re.compile(r"^Started at: (.*)$")
FINISHED_RE = re.compile(r"^Finished at: (.*)$")
SEPARATOR = "=" * 54
FAILURE_TAIL_LINES = 200


def parse_date(text):
    for fmt in ("%a %b %d %H:%M:%S %Z %Y", "%a %d %b %Y %H:%M:%S %Z"):
        try:
            return datetime.strptime(" ".join(text.split()), fmt)
        except ValueError:
            pass
    return None


def parse_tests(lines):
    tests = []
    current = None
    for line in lines:
        line = line.rstrip("\n")
        m = TEST_RE.match(line)
        if m:
            current = {
                "name": " ".join(p.strip() for p in (m.group(3), m.group(5)) if p and p.strip()),
                "time_limit": m.group(4),
                "status": None,
                "started": None,
                "finished": None,
                "output": [],
            }
            tests.append(current)
            continue
        if current is None:
            continue
        if line.startswith("FINAL RESULTS"):
            current = None
            continue
        m = RESULT_RE.match(line)
        if m:
            current["status"] = m.group(1)
            continue
        m = STARTED_RE.match(line)
        if m and current["started"] is None:
            current["started"] = parse_date(m.group(1))
            continue
        m = FINISHED_RE.match(line)
        if m and current["status"] is not None:
            current["finished"] = parse_date(m.group(1))
            continue
        if line != SEPARATOR:
            current["output"].append(line)
    return tests


def verdict_line(output):
    for line in reversed(output):
        if ":valid?" in line:
            return line.strip()
    return None


def build_report(suite, tests):
    suite_el = ET.Element("testsuite", name=suite)
    counts = {"tests": 0, "failures": 0, "errors": 0}
    total_time = 0.0
    for t in tests:
        case = ET.SubElement(suite_el, "testcase", classname=f"jepsen.{suite}", name=t["name"])
        seconds = 0.0
        if t["started"] and t["finished"]:
            seconds = max(0.0, (t["finished"] - t["started"]).total_seconds())
        case.set("time", f"{seconds:.3f}")
        total_time += seconds
        counts["tests"] += 1

        status = t["status"]
        if status == "PASS":
            continue
        tail = "\n".join(t["output"][-FAILURE_TAIL_LINES:])
        if status is None:
            counts["errors"] += 1
            el = ET.SubElement(case, "error", type="Incomplete",
                               message="Test did not report a result (sweep crashed or was cancelled)")
        else:
            counts["failures"] += 1
            verdict = verdict_line(t["output"])
            message = f"Jepsen verdict {status}" + (f": {verdict}" if verdict else " (no :valid? verdict in output)")
            el = ET.SubElement(case, "failure", type=status, message=message)
        el.text = tail

    for key, value in counts.items():
        suite_el.set(key, str(value))
    suite_el.set("skipped", "0")
    suite_el.set("time", f"{total_time:.3f}")
    return suite_el


def main(argv):
    if len(argv) != 4:
        sys.stderr.write("Usage: jepsen-log-to-junit.py <suite-name> <sweep-log> <junit-xml-out>\n")
        return 2
    suite, log_path, out_path = argv[1:]
    with open(log_path, encoding="utf-8", errors="replace") as f:
        tests = parse_tests(f)
    root = build_report(suite, tests)
    ET.indent(root)
    ET.ElementTree(root).write(out_path, encoding="UTF-8", xml_declaration=True)
    print(f"{out_path}: {root.get('tests')} tests, {root.get('failures')} failures, {root.get('errors')} errors")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
