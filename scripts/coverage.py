#!/usr/bin/env python3
"""Report coverage the way SonarCloud's new-code gate measures it.

SonarCloud's `coverage` metric is **not** line coverage. It is

    (covered lines + covered conditions) / (lines to cover + conditions to cover)

so a file with every line executed can still sit well below the gate if its
branches are not taken. Reading Kover's LINE counter alone -- the obvious thing
to do -- overstates the number the gate will apply, and did: three pull requests
in a row were reported locally in the high eighties and arrived at the gate in
the low eighties or below.

Usage:
    python3 scripts/coverage.py [path-prefix ...]

With no arguments, reports the whole project. With prefixes, reports only
classes whose package path contains one of them, which is how to ask "what will
the gate say about the code this change touches".
"""

import sys

# The input is this build's own Kover report, not anything fetched or user-supplied, so the XXE and
# entity-expansion attacks the stdlib parser is vulnerable to have no way in. `defusedxml` is used
# where it is installed anyway, because "the input is trusted today" is the assumption that stops
# being true quietly.
try:
    from defusedxml import ElementTree as ET
except ImportError:
    import xml.etree.ElementTree as ET

REPORT = "build/reports/kover/report.xml"


def counters(element):
    """Covered and missed, for lines and branches, from a Kover element."""
    lines = branches = 0
    covered_lines = covered_branches = 0
    for counter in element.findall("counter"):
        kind = counter.get("type")
        covered = int(counter.get("covered"))
        missed = int(counter.get("missed"))
        if kind == "LINE":
            covered_lines, lines = covered, covered + missed
        elif kind == "BRANCH":
            covered_branches, branches = covered, covered + missed
    return covered_lines, lines, covered_branches, branches


def main(prefixes):
    try:
        tree = ET.parse(REPORT)
    except FileNotFoundError:
        sys.exit(f"{REPORT} not found -- run ./gradlew koverXmlReport first")

    rows = []
    totals = [0, 0, 0, 0]
    for package in tree.getroot().iter("package"):
        name = package.get("name", "")
        if prefixes and not any(prefix in name for prefix in prefixes):
            continue
        for klass in package.iter("class"):
            simple = klass.get("name", "").split("/")[-1]
            cl, tl, cb, tb = counters(klass)
            if tl + tb == 0:
                continue
            rows.append((simple, cl, tl, cb, tb))
            for index, value in enumerate((cl, tl, cb, tb)):
                totals[index] += value

    rows.sort(key=lambda row: (row[1] + row[3]) / max(row[2] + row[4], 1))
    for simple, cl, tl, cb, tb in rows:
        combined = (cl + cb) / max(tl + tb, 1) * 100
        line = cl / max(tl, 1) * 100
        branch = cb / max(tb, 1) * 100 if tb else 100.0
        flag = "  <-- below gate" if combined < 80 else ""
        print(f"{simple:44s} sonar={combined:5.1f}%  line={line:5.1f}%  branch={branch:5.1f}%{flag}")

    cl, tl, cb, tb = totals
    combined = (cl + cb) / max(tl + tb, 1) * 100
    print("-" * 92)
    print(
        f"SONAR-EQUIVALENT: {combined:.1f}%   "
        f"(lines {cl}/{tl}, branches {cb}/{tb})   gate is 80%"
    )
    return 0 if combined >= 80 else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
