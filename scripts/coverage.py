#!/usr/bin/env python3
"""Report coverage the way SonarCloud's new-code gate measures it.

SonarCloud's `coverage` metric is **not** line coverage. It is

    (covered lines + covered conditions) / (lines to cover + conditions to cover)

so a file with every line executed can still sit well below the gate if its
branches are not taken. Reading Kover's LINE counter alone -- the obvious thing
to do -- overstates the number the gate will apply, and did: three pull requests
in a row were reported locally in the high eighties and arrived at the gate in
the low eighties or below.

There is a second trap under the first. The gate measures **new code** -- the
lines this change touched -- not whole classes. A class sitting at 85% overall
can have its changed lines far below that, so `--diff` is the mode to trust
before pushing; the prefix mode answers a different and looser question.

Usage:
    python3 scripts/coverage.py --diff [base]   # what the gate will say (default origin/main)
    python3 scripts/coverage.py [path-prefix ...]  # whole classes, for orientation
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


def changed_lines(base):
    """The lines this branch adds or edits under src/main/kotlin, by file."""
    import collections
    import subprocess

    lines = collections.defaultdict(set)
    diff = subprocess.run(
        ["git", "diff", "-U0", f"{base}...HEAD", "--", "src/main/kotlin"],
        capture_output=True, text=True, check=True,
    ).stdout
    path = None
    for line in diff.splitlines():
        if line.startswith("+++ b/"):
            path = line[6:]
        elif line.startswith("@@") and path:
            span = line.split("+")[1].split("@@")[0].strip()
            start = int(span.split(",")[0])
            count = int(span.split(",")[1]) if "," in span else 1
            lines[path].update(range(start, start + count))
    return lines


def diff_mode(base):
    """Coverage of the changed lines alone, which is what the gate scores."""
    changed = changed_lines(base)
    if not changed:
        print(f"no changed Kotlin sources against {base}")
        return 0
    tree = ET.parse(REPORT)
    covered_lines = total_lines = covered_branches = total_branches = 0
    uncovered = []
    for package in tree.getroot().iter("package"):
        for source in package.iter("sourcefile"):
            path = f"src/main/kotlin/{package.get('name', '')}/{source.get('name')}"
            if path not in changed:
                continue
            for line in source.findall("line"):
                number = int(line.get("nr"))
                if number not in changed[path]:
                    continue
                instructions = int(line.get("ci", 0))
                missed, taken = int(line.get("mb", 0)), int(line.get("cb", 0))
                total_lines += 1
                if instructions > 0:
                    covered_lines += 1
                total_branches += missed + taken
                covered_branches += taken
                if instructions == 0 or missed > 0:
                    why = "uncovered" if instructions == 0 else f"{missed} branches missed"
                    uncovered.append((path, number, why))

    for path, number, why in uncovered:
        print(f"{path}:{number}  {why}")
    combined = (covered_lines + covered_branches) / max(total_lines + total_branches, 1) * 100
    print("-" * 92)
    print(
        f"CHANGED LINES: {combined:.1f}%   "
        f"(lines {covered_lines}/{total_lines}, branches {covered_branches}/{total_branches})   gate is 80%"
    )
    return 0 if combined >= 80 else 1


if __name__ == "__main__":
    if sys.argv[1:2] == ["--diff"]:
        sys.exit(diff_mode(sys.argv[2] if len(sys.argv) > 2 else "origin/main"))
    sys.exit(main(sys.argv[1:]))
