#!/usr/bin/env python3
"""Computes per-scenario (low/high contention) throughput, latency percentiles, and
transfer-outcome counts from a raw k6 --out json= NDJSON file. Used to fill in the
isolation benchmark table in docs/experiments/01-isolation.md from committed, real
run output rather than by hand-typing numbers.
"""
import gzip
import json
import sys
from collections import defaultdict


def open_maybe_gzip(path):
    return gzip.open(path, "rt") if path.endswith(".gz") else open(path)


def percentile(sorted_vals, p):
    if not sorted_vals:
        return None
    k = (len(sorted_vals) - 1) * (p / 100)
    f = int(k)
    c = min(f + 1, len(sorted_vals) - 1)
    if f == c:
        return sorted_vals[f]
    return sorted_vals[f] + (sorted_vals[c] - sorted_vals[f]) * (k - f)


def main(path):
    durations = defaultdict(list)
    outcomes = defaultdict(lambda: defaultdict(int))
    scenario_span = {}

    with open_maybe_gzip(path) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            d = json.loads(line)
            if d.get("type") != "Point":
                continue
            metric = d.get("metric")
            data = d.get("data", {})
            tags = data.get("tags", {})
            scenario = tags.get("scenario")
            if not scenario:
                continue

            if metric == "http_req_duration":
                durations[scenario].append(data["value"])
                t = data["time"]
                lo, hi = scenario_span.get(scenario, (t, t))
                scenario_span[scenario] = (min(lo, t), max(hi, t))
                status = tags.get("status")
                outcomes[scenario][status] += 1

    print(f"{'Scenario':<16} {'Requests':>9} {'RPS':>8} {'p50(ms)':>9} {'p95(ms)':>9} {'p99(ms)':>9} {'201':>7} {'422':>6} {'other':>6}")
    for scenario in sorted(durations):
        vals = sorted(durations[scenario])
        n = len(vals)
        lo, hi = scenario_span[scenario]
        from datetime import datetime
        t0 = datetime.fromisoformat(lo)
        t1 = datetime.fromisoformat(hi)
        span_s = max((t1 - t0).total_seconds(), 0.001)
        rps = n / span_s
        p50 = percentile(vals, 50)
        p95 = percentile(vals, 95)
        p99 = percentile(vals, 99)
        c201 = outcomes[scenario].get("201", 0)
        c422 = outcomes[scenario].get("422", 0)
        other = n - c201 - c422
        print(f"{scenario:<16} {n:>9} {rps:>8.1f} {p50:>9.2f} {p95:>9.2f} {p99:>9.2f} {c201:>7} {c422:>6} {other:>6}")


if __name__ == "__main__":
    main(sys.argv[1])
