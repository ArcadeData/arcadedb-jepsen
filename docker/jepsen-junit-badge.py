#!/usr/bin/env python3
"""Summarise JUnit reports into a shields.io endpoint badge JSON.

Sums tests/failures/errors over every given report written by
jepsen-log-to-junit.py and writes {"schemaVersion": 1, ...} for
https://img.shields.io/endpoint. No tests at all (the sweep died before the
first run) is reported as "no results" rather than a misleading 0/0.

Usage: jepsen-junit-badge.py <badge-json-out> <junit-xml>...
"""

import json
import sys
import xml.etree.ElementTree as ET


def badge(reports):
    tests = failed = 0
    for path in reports:
        root = ET.parse(path).getroot()
        tests += int(root.get("tests", 0))
        failed += int(root.get("failures", 0)) + int(root.get("errors", 0))
    if tests == 0:
        message, color = "no results", "lightgrey"
    elif failed == 0:
        message, color = f"{tests}/{tests} passed", "brightgreen"
    else:
        message, color = f"{tests - failed}/{tests} passed", "red"
    return {"schemaVersion": 1, "label": "jepsen tests", "message": message, "color": color}


def main(argv):
    if len(argv) < 2:
        sys.stderr.write("Usage: jepsen-junit-badge.py <badge-json-out> <junit-xml>...\n")
        return 2
    data = badge(argv[2:])
    with open(argv[1], "w", encoding="utf-8") as f:
        json.dump(data, f)
    print(f"{argv[1]}: {data['message']}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
