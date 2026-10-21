"""Summarize real JMeter CSV samples without dropping failures or warm-up IDs.

Uses Python's standard library. Raw JTL stays in ignored infra/.runtime.
The declared steady window starts at the first sampler timestamp plus ramp and
warmup. Only requests both started and completed inside it enter steady metrics.
ID range and uniqueness cover ALL recorded samples, including ramp and warmup.
"""
import argparse
import csv
import hashlib
import json
import math
from collections import Counter
from pathlib import Path


def percentile(histogram, count, fraction):
    if not count:
        return None
    rank = math.ceil(count * fraction)
    seen = 0
    for elapsed, frequency in sorted(histogram.items()):
        seen += frequency
        if seen >= rank:
            return elapsed


def summarize(run_dir):
    metadata_path = run_dir / "jmeter-metadata.json"
    metadata = json.loads(metadata_path.read_text(encoding="utf-8-sig"))
    if metadata.get("status") != "completed-awaiting-sample-analysis":
        raise ValueError("Runner did not complete with unchanged healthy targets; run is invalid.")
    # Metadata accepts the runner's documented parameter casing.
    parameters = metadata.get("parameters", metadata)
    normalized = {key.lower().replace("_", ""): value for key, value in parameters.items()}
    def parameter(name):
        return normalized[name.lower().replace("_", "")]
    ramp = int(parameter("RampSeconds"))
    warmup = int(parameter("WarmupSeconds"))
    duration = int(parameter("DurationSeconds"))
    scenario = str(parameter("Scenario"))
    jtl = run_dir / "samples.jtl"
    hist, codes, failures = Counter(), Counter(), Counter()
    seconds = [dict(second=i, completed=0, successful=0, errors=0, elapsed_sum_ms=0)
               for i in range(duration)]
    all_count = steady_count = successful = elapsed_sum = 0
    # JTL emission follows completion order; the first emitted row need not have
    # the earliest start timestamp. Find the true minimum in a separate pass.
    with jtl.open(newline="", encoding="utf-8-sig") as stream:
        first = min((int(row["timeStamp"]) for row in csv.DictReader(stream)), default=None)
    if first is None:
        raise ValueError("No recorded JMeter samples.")
    steady_start = first + (ramp + warmup) * 1000
    steady_end = steady_start + duration * 1000
    last = None
    ids = set()
    invalid_ids = duplicate_ids = id_count = all_errors = 0
    with jtl.open(newline="", encoding="utf-8-sig") as stream:
        for row in csv.DictReader(stream):
            start, elapsed = int(row["timeStamp"]), int(row["elapsed"])
            end = start + elapsed
            last = max(last or end, end)
            all_count += 1
            ok = row["success"].lower() == "true"
            all_errors += not ok
            if scenario == "leaf":
                raw = row.get("issued_id", "")
                if not raw.isascii() or not raw.isdigit() or not 0 < int(raw) <= 9223372036854775807:
                    invalid_ids += 1
                else:
                    value = int(raw)
                    id_count += 1
                    if value in ids:
                        duplicate_ids += 1
                    ids.add(value)
            if not (steady_start <= start and end < steady_end):
                continue
            steady_count += 1
            successful += ok
            elapsed_sum += elapsed
            hist[elapsed] += 1
            codes[row["responseCode"]] += 1
            if not ok:
                failures[row.get("failureMessage", "") or row["responseCode"]] += 1
            bucket = seconds[min(duration - 1, (end - steady_start) // 1000)]
            bucket["completed"] += 1
            bucket["successful"] += ok
            bucket["errors"] += not ok
            bucket["elapsed_sum_ms"] += elapsed
    for bucket in seconds:
        bucket["mean_ms"] = (round(bucket["elapsed_sum_ms"] / bucket["completed"], 4)
                             if bucket["completed"] else None)
    digest = hashlib.sha256()
    with jtl.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    result = {
        "run_id": run_dir.name, "scenario": scenario, "parameters": parameters,
        "window_method": "first_sample_start + ramp + warmup; start >= window_start and end < window_end",
        "first_sample_ms": first, "last_completion_ms": last,
        "steady_start_ms": steady_start, "steady_end_ms": steady_end,
        "duration_seconds": duration, "all_samples": all_count, "all_errors": all_errors,
        "steady_samples": steady_count, "successful_samples": successful,
        "successful_qps": round(successful / duration, 4),
        "all_completion_qps": round(steady_count / duration, 4),
        "mean_ms": round(elapsed_sum / steady_count, 4) if steady_count else None,
        "p50_ms": percentile(hist, steady_count, .50),
        "p95_ms": percentile(hist, steady_count, .95),
        "p99_ms": percentile(hist, steady_count, .99),
        "error_count": steady_count - successful,
        "error_percent": round(100 * (steady_count - successful) / steady_count, 6) if steady_count else None,
        "response_codes": dict(codes), "failures": dict(failures),
        "id_check_all_phases": ({"issued": id_count, "unique": len(ids),
                                  "invalid": invalid_ids, "duplicates": duplicate_ids}
                                 if scenario == "leaf" else None),
        "elapsed_histogram_ms": dict(sorted(hist.items())), "per_second": seconds,
        "raw_jtl_sha256": digest.hexdigest(),
    }
    (run_dir / "summary.json").write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("run_dir", type=Path)
    args = parser.parse_args()
    summary = summarize(args.run_dir)
    print(json.dumps({k: v for k, v in summary.items()
                      if k not in ("elapsed_histogram_ms", "per_second", "parameters")},
                     ensure_ascii=False, indent=2))
    if (summary["steady_samples"] == 0 or summary["all_errors"]
            or (summary["id_check_all_phases"] and
                (summary["id_check_all_phases"]["invalid"] or summary["id_check_all_phases"]["duplicates"]))):
        raise SystemExit(2)
