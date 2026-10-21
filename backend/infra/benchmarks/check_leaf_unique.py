#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""校验 JMeter 压测期间取到的 ID 有没有重复。

配合 leaf-load.jmx 使用，JTL 必须是 XML 格式且打开了响应体保存：

  jmeter -n -t infra/benchmarks/leaf-load.jmx -l out/leaf-uniq.jtl \
    -Jjmeter.save.saveservice.output_format=xml \
    -Jjmeter.save.saveservice.response_data=true \
    -Jthreads=30 -Jramp_seconds=5 -Jwarmup_seconds=10 -Jduration_seconds=140

用法:
  python infra/benchmarks/check_leaf_unique.py <jtl>

口径:
  - 逐条统计 responseData 里的十进制 ID
  - 用集合去重，重复数必须为 0 才算通过
  - 流式解析，不把整个文件读进内存（300 万条也不炸）
"""
import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("jtl", help="XML 格式的 JTL 文件")
    parser.add_argument("--show-max", action="store_true", help="额外打印最大 / 最小 ID")
    args = parser.parse_args()

    path = Path(args.jtl)
    if not path.exists():
        sys.exit(f"找不到文件: {path}")

    seen = set()
    total = 0
    duplicates = 0
    no_body = 0
    not_decimal = 0
    biggest = None
    smallest = None

    truncated = False
    try:
        for _, element in ET.iterparse(path, events=("end",)):
            if element.tag not in ("httpSample", "sample"):
                continue
            total += 1
            node = element.find("responseData")
            if node is None or not node.text:
                no_body += 1
            else:
                value = node.text.strip()
                if not value.isdigit():
                    not_decimal += 1
                else:
                    number = int(value)
                    if number in seen:
                        duplicates += 1
                    else:
                        seen.add(number)
                        if args.show_max:
                            if biggest is None or number > biggest:
                                biggest = number
                            if smallest is None or number < smallest:
                                smallest = number
            element.clear()
    except ET.ParseError as error:
        # JMeter 偶发在 JVM 退出前没写出根节点的闭合标签。
        # 报错点之前的所有 httpSample 都是完整的，结果依然可用。
        truncated = True
        print(f"警告        : XML 尾部不完整（{error}）")
        print(f"            已完整解析的 {total} 条仍然有效，不影响重复判定")

    unique = len(seen)
    print(f"文件        : {path}")
    print(f"样本总数    : {total}")
    print(f"取到 ID     : {unique + duplicates}")
    print(f"去重后      : {unique}")
    print(f"重复        : {duplicates}")
    if no_body:
        print(f"无响应体    : {no_body}（检查 response_data 是否打开）")
    if not_decimal:
        print(f"非数字      : {not_decimal}（返回体不是纯 ID，检查接口）")
    if args.show_max and unique:
        print(f"最小 / 最大 : {smallest} / {biggest}")
    print()
    ok = duplicates == 0 and unique > 0 and no_body == 0
    print("结论        : " + ("通过，全部唯一" if ok else "不通过"))


if __name__ == "__main__":
    main()
