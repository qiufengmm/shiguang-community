#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Summarize a JMeter JTL produced by leaf-load.jmx.

只用标准库。与 infra/benchmarks/summarize_jmeter.py 的区别：
那个脚本依赖包装脚本生成的 jmeter-metadata.json，本脚本只吃裸 JTL，
适合手工 docker run JMeter 的场景。

用法:
  python infra/benchmarks/summarize_leaf.py <jtl> --ramp 5 --warmup 10

口径:
  - 稳态窗口 = 第一个样本时间戳 + ramp + warmup 之后的全部样本
  - QPS = 稳态样本数 / 稳态实际跨度（秒）
  - 分位数用 nearest-rank
  - 失败样本单独统计，不计入 QPS，但会让结论失去简历引用资格
"""
import argparse
import csv
import math
import sys
from pathlib import Path


def percentile(histogram, count, fraction):
    if not count:
        return None
    rank = math.ceil(count * fraction)
    seen = 0
    for elapsed in sorted(histogram):
        seen += histogram[elapsed]
        if seen >= rank:
            return elapsed
    return None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("jtl", help="JMeter 结果文件路径")
    parser.add_argument("--ramp", type=int, default=5, help="爬坡秒数，默认 5")
    parser.add_argument("--warmup", type=int, default=10, help="预热秒数，默认 10")
    args = parser.parse_args()

    path = Path(args.jtl)
    if not path.exists():
        sys.exit(f"找不到文件: {path}")

    with path.open(encoding="utf-8-sig", newline="") as handle:
        rows = list(csv.DictReader(handle))
    if not rows:
        sys.exit("JTL 里没有样本")

    stamps = [int(r["timeStamp"]) for r in rows]
    first, last = min(stamps), max(stamps)
    steady_from = first + (args.ramp + args.warmup) * 1000

    steady = [r for r in rows if int(r["timeStamp"]) >= steady_from]
    if not steady:
        sys.exit("稳态窗口内没有样本，检查 --ramp/--warmup 是否大于总时长")

    ok = [r for r in steady if r["success"].strip().lower() == "true"]
    bad = len(steady) - len(ok)

    elapsed = [int(r["elapsed"]) for r in ok]
    hist = {}
    for value in elapsed:
        hist[value] = hist.get(value, 0) + 1

    span = (max(int(r["timeStamp"]) for r in steady) - steady_from) / 1000.0
    qps = len(steady) / span if span > 0 else 0

    errors = {}
    for r in steady:
        if r["success"].strip().lower() != "true":
            key = f'{r["responseCode"]} {r["responseMessage"]}'.strip()
            errors[key] = errors.get(key, 0) + 1

    print(f"文件        : {path}")
    print(f"总时长      : {(last - first) / 1000.0:.1f}s（首个到末个样本）")
    print(f"稳态窗口    : {(first + (args.ramp + args.warmup) * 1000 - first) / 1000.0:.0f}s 之后，跨度 {span:.1f}s")
    print(f"总样本      : {len(rows)}")
    print(f"稳态样本    : {len(steady)}（失败 {bad}）")
    print(f"QPS         : {qps:.0f} /s")
    if ok:
        print(f"平均耗时    : {sum(elapsed) / len(elapsed):.2f} ms")
        print(f"P50 / P95 / P99 : {percentile(hist, len(elapsed), 0.50)} / "
              f"{percentile(hist, len(elapsed), 0.95)} / {percentile(hist, len(elapsed), 0.99)} ms")
        print(f"最快 / 最慢 : {min(elapsed)} / {max(elapsed)} ms")
    if errors:
        print("失败分布    :")
        for key, count in sorted(errors.items(), key=lambda kv: -kv[1]):
            print(f"  {count:>6}  {key}")
    print()
    print("结论可用性  : " + ("可以引用" if bad == 0 else f"不可引用（{bad} 个失败样本）"))


if __name__ == "__main__":
    main()
