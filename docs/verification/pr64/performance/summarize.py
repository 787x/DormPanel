"""Recompute PR64 wall-time/OnDraw/FrameMetrics summaries; Python standard library only."""
import collections
import json
import statistics
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def triple(values):
    return "/".join(f"{v:.1f}" for v in (min(values), statistics.median(values), max(values)))


for name in ("before", "after"):
    print(f"\n{name}: min/median/max ms")
    rows = json.loads((ROOT / f"{name}.json").read_text(encoding="utf-8"))
    for layout in ("CLASSIC", "TIME", "PERIODS"):
        group = [r for r in rows if r["layout"] == layout]
        print(layout, "n=", len(group))
        for field in ("move", "warmMove", "up"):
            print(" ", field, triple([r[field] / 1e6 for r in group]))
        print("  target OnDraw", triple([(r["targetDraw"] - r["lock"]) / 1e6 for r in group]))
        phases = collections.defaultdict(list)
        for row in group:
            sums = collections.defaultdict(float)
            for event in row["events"]:
                if event["main"] and row["lock"] <= event["start"] <= row["lock"] + row["move"]:
                    sums[event["phase"]] += (event["end"] - event["start"]) / 1e6
            for phase, total in sums.items():
                phases[phase].append(total)
        print("  nested phase medians (DO NOT add):")
        for phase, values in sorted(phases.items()):
            print("   ", phase, f"{statistics.median(values):.1f}")
        commits = [sum((e["end"] - e["start"]) / 1e6 for e in r["events"] if e["phase"] == "commit") for r in group]
        print("  commit callback", triple(commits))

print("\nnative: real system injection, three forward/back/repeat gestures per layout")
rows = json.loads((ROOT / "native.json").read_text(encoding="utf-8"))
for layout in ("CLASSIC", "TIME", "PERIODS"):
    group = [r for r in rows if r["layout"] == layout]
    first = []
    for row in group:
        moves = [(e["end"] - e["start"]) / 1e6 for e in row["events"] if e["phase"] == "move"]
        frames = [f["total"] / 1e6 for f in row["frames"] if f["total"] > 0 and f["vsync"] + f["total"] >= row["lock"] and f["vsync"] <= row["end"]]
        stages = [(e["end"] - e["start"]) / 1e6 for e in row["events"] if e["phase"] == "staged-views"]
        first.append(moves[0])
        print(layout, row["index"], "firstMove=", f"{moves[0]:.1f}", "maxMove=", f"{max(moves):.1f}",
              "targetDraw=", f"{(row['targetDraw'] - row['lock']) / 1e6:.1f}",
              "maxStage=", f"{max(stages, default=0):.1f}", "maxFrame=", f"{max(frames, default=0):.1f}",
              "frames>32ms=", f"{sum(v > 32 for v in frames)}/{len(frames)}")
    print(layout, "firstMove", triple(first))
