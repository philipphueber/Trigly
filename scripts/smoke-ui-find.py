"""Find a control in a uiautomator dump, for scripts/smoke-ui.sh.

Kept beside that script rather than inside it because the dump is XML and awk
reading XML is how a smoke test starts lying: a label that holds an escaped
quote, or a node whose attributes wrap, is enough to shift a tap to the wrong
control, and a tap on the wrong control is the failure this whole check exists
to catch.

Modes:
    exact   the label equals the wanted text, ignoring case and outer space
    sub     the label contains the wanted text
    fields  print "x y" for every editable field, top to bottom
"""

import re
import sys

NODE = re.compile(r"<node[^>]*?/?>")
BOUNDS = re.compile(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')


def attr(tag, name):
    m = re.search(r'%s="([^"]*)"' % name, tag)
    return m.group(1) if m else ""


def centre(tag):
    b = BOUNDS.search(tag)
    if not b:
        return None
    x1, y1, x2, y2 = (int(v) for v in b.groups())
    if x2 <= x1 or y2 <= y1:
        return None
    return (x1 + x2) // 2, (y1 + y2) // 2


def main():
    path, want, mode = sys.argv[1], sys.argv[2], sys.argv[3]
    xml = open(path, encoding="utf-8", errors="replace").read()

    if mode == "fields":
        for m in NODE.finditer(xml):
            tag = m.group(0)
            if "EditText" not in attr(tag, "class"):
                continue
            point = centre(tag)
            if point:
                print(point[0], point[1])
        return

    wanted = want.strip()
    lowered = wanted.lower()
    best = None

    for m in NODE.finditer(xml):
        tag = m.group(0)
        cls = attr(tag, "class")
        for label in (attr(tag, "text"), attr(tag, "content-desc")):
            if not label:
                continue
            shown = label.strip()
            hit = shown.lower() == lowered if mode == "exact" else lowered in shown.lower()
            if not hit:
                continue
            point = centre(tag)
            if point is None:
                continue
            # An EditText holding the wanted text is almost always the search
            # box it was just typed into, not the result being searched for.
            # Tapping it puts the cursor back in the box and looks like the
            # script doing nothing at all, so a real control always wins.
            score = 0
            if shown == wanted:
                score += 2
            if "EditText" not in cls:
                score += 4
            if best is None or score > best[0]:
                best = (score, point[0], point[1])

    if best:
        print(best[1], best[2])


if __name__ == "__main__":
    main()
