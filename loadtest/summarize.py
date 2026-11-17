"""Turns k6 summaries + Redis stats from run_matrix.sh into a Markdown results table.

Max sustainable throughput = highest tested rate where the median run has
p95 < 50 ms, error rate < 1%, and k6 actually achieved >= 95% of the target rate.
"""
import json
import re
import statistics
import sys
from collections import defaultdict
from pathlib import Path

P95_SLO_MS = 50.0
ERROR_SLO = 0.01
NAME = re.compile(r"^(?P<storage>\w+)_cache-(?P<cache>true|false)_(?P<rate>\d+)_r(?P<rep>\d+)$")


def load_run(json_path: Path) -> dict:
    data = json.loads(json_path.read_text())
    m = data["metrics"]
    d = m["http_req_duration"]["values"]
    run = {
        "p50": d["med"],
        "p95": d["p(95)"],
        "p99": d["p(99)"],
        "errors": m["http_req_failed"]["values"]["rate"],
        "achieved": m["http_reqs"]["values"]["rate"],
        "hit_rate": None,
    }
    redis_txt = json_path.with_suffix(".redis.txt")
    if redis_txt.exists():
        stats = dict(line.split(":", 1) for line in redis_txt.read_text().split() if ":" in line)
        hits, misses = int(stats.get("keyspace_hits", 0)), int(stats.get("keyspace_misses", 0))
        if hits + misses > 0:
            run["hit_rate"] = hits / (hits + misses)
    return run


def median_run(runs: list[dict]) -> dict:
    out = {}
    for k in ("p50", "p95", "p99", "errors", "achieved"):
        out[k] = statistics.median(r[k] for r in runs)
    hit = [r["hit_rate"] for r in runs if r["hit_rate"] is not None]
    out["hit_rate"] = statistics.median(hit) if hit else None
    out["n"] = len(runs)
    return out


def meets_slo(r: dict, rate: int) -> bool:
    return r["p95"] < P95_SLO_MS and r["errors"] < ERROR_SLO and r["achieved"] >= 0.95 * rate


def label(storage: str, cache: str) -> str:
    db = "MongoDB" if storage == "mongo" else "PostgreSQL"
    return f"{db} + Redis" if cache == "true" else f"{db}, no cache"


def fmt_hit(h):
    return "n/a" if h is None else f"{h * 100:.1f}%"


def main(raw_dir: str, conditions: str | None) -> None:
    grouped = defaultdict(list)
    for p in sorted(Path(raw_dir).glob("*.json")):
        m = NAME.match(p.stem)
        if m:
            grouped[(m["storage"], m["cache"], int(m["rate"]))].append(load_run(p))
    if not grouped:
        sys.exit("no k6 summaries found in " + raw_dir)

    results = {k: median_run(v) for k, v in grouped.items()}
    configs = sorted({(s, c) for s, c, _ in results}, key=lambda x: (x[0] != "postgres", x[1] == "true"))

    print("# Load test results\n")
    if conditions and Path(conditions).exists():
        print("```\n" + Path(conditions).read_text().strip() + "\n```\n")
    print(f"*Max sustainable RPS: highest tested rate with p95 < {P95_SLO_MS:.0f} ms, errors < "
          f"{ERROR_SLO * 100:.0f}%, and ≥ 95% of the target rate achieved. Median of repeats.*\n")

    print("## Summary\n")
    print("| Config | Max sustainable RPS | p50 | p95 | p99 | Cache hit rate |")
    print("|---|---|---|---|---|---|")
    for s, c in configs:
        rates = sorted(r for (s2, c2, r) in results if (s2, c2) == (s, c))
        ok = [r for r in rates if meets_slo(results[(s, c, r)], r)]
        if ok:
            best = max(ok)
            r = results[(s, c, best)]
            print(f"| {label(s, c)} | {best:,} | {r['p50']:.2f} ms | {r['p95']:.2f} ms | {r['p99']:.2f} ms | "
                  f"{fmt_hit(r['hit_rate']) if c == 'true' else '–'} |")
        else:
            print(f"| {label(s, c)} | below {min(rates):,} | – | – | – | – |")

    print("\n## Cache effect (same backend, same rate)\n")
    print("| Backend | Rate | p95 no cache | p95 with Redis | Hit rate |")
    print("|---|---|---|---|---|")
    for s in sorted({s for s, _ in configs}):
        for rate in sorted({r for (s2, _, r) in results if s2 == s}):
            off, on = results.get((s, "false", rate)), results.get((s, "true", rate))
            if off and on:
                print(f"| {'MongoDB' if s == 'mongo' else 'PostgreSQL'} | {rate:,}/s | {off['p95']:.2f} ms | "
                      f"{on['p95']:.2f} ms | {fmt_hit(on['hit_rate'])} |")

    print("\n## All runs\n")
    print("| Config | Target rate | Achieved | p50 | p95 | p99 | Errors | Hit rate | Meets SLO | Runs |")
    print("|---|---|---|---|---|---|---|---|---|---|")
    for s, c in configs:
        for rate in sorted(r for (s2, c2, r) in results if (s2, c2) == (s, c)):
            r = results[(s, c, rate)]
            print(f"| {label(s, c)} | {rate:,}/s | {r['achieved']:,.0f}/s | {r['p50']:.2f} ms | {r['p95']:.2f} ms | "
                  f"{r['p99']:.2f} ms | {r['errors'] * 100:.2f}% | {fmt_hit(r['hit_rate']) if c == 'true' else '–'} | "
                  f"{'yes' if meets_slo(r, rate) else 'no'} | {r['n']} |")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None)
