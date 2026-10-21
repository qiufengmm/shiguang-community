# 2026-10-20 JMeter 并发压测报告

## 一、结论摘要

| 场景 | 稳定并发 | 中位 QPS | P95 / P99 | 错误率 | 备注 |
|---|---:|---:|---:|---:|---|
| 关注列表 `/relation/following/list` | **20** | **1532** | 22 ms / 32 ms | **0 / 3 轮** | 每页固定 10 条，缓存与用户资料均预热 |
| Snowflake 取号 `/id/snowflake/get/resume-bench` | **30** | **22960** | 2 ms / 4 ms | **0 / 3 轮** | 290 万个 ID 无重复、无非法 |

关注列表在 **30 并发**仍可跑到 1548 QPS，但 3 轮中 1 轮在测试尾声出现 0.39% 业务失败；**40 并发起错误率升至 3.76%，QPS 反而下降到 1462**，说明吞吐已在 30 并发附近饱和，瓶颈为容器 4 vCPU 配额。

## 二、环境与口径

| 项 | 值 |
|---|---|
| 宿主 / Docker | 4 vCPU，约 7.76 GiB 可分配内存 |
| 服务部署 | 既有 `xiaohashu_default` 网络内单实例，Docker Compose 本地栈 |
| 压测客户端 | 临时容器 `eclipse-temurin:17-jre`，512 MiB 上限，`-Xmx256m`，`-XX:ActiveProcessorCount=2` |
| 客户端位置 | **与服务同机**，共享同一 4 vCPU（结果不代表生产容量） |
| 工具 | Apache JMeter 5.6.3，CLI 无 GUI，`-n -t plan.jmx -l samples.jtl` |
| 协议 | HTTPClient4，keep-alive，闭环线程、无思考时间 |
| 超时 | 连接 3 s，响应 5 s |
| 时长 | 爬升 5 s + 预热 10 s + 正式 60 s（Leaf 为正式 30 s） |
| 统计窗口 | 首个采样时间 + 爬升 + 预热 起算；只统计窗口内开始且完成的请求 |
| 判定 | HTTP 200 且业务断言通过才算成功；失败样本全部保留，不筛选 |

关注列表断言：`success=true`、`pageNo=1`、`pageSize=10`、`totalCount=10`、`data` 恰好 10 条、用户 ID 集合与夹具完全一致、昵称与简介非空、无重复 ID。
Snowflake 断言：HTTP 200、响应为完整正整数；另对**全阶段**（含爬升与预热）ID 做范围与重复校验。

## 三、关注列表并发梯度

| 并发 | 轮次 | QPS | 平均 | P95 | P99 | 错误数 | 错误率 |
|---:|---|---:|---:|---:|---:|---:|---:|
| 1 | 1 | 51.7 | 11.30 ms | 22 ms | 34 ms | 0 | 0% |
| 10 | 1 | 1389.7 | 5.83 ms | 11 ms | 17 ms | 0 | 0% |
| 20 | 3 | 1532.4 / 1549.7 / 1522.2 | 11.33 / 11.24 / 11.41 ms | 23 / 22 / 22 ms | 32 / 32 / 32 ms | 0 | 0% |
| 30 | 3 | 1548.0 / 1538.2 / 1557.7 | 17.37 / 17.37 / 17.13 ms | 35 / 35 / 34 ms | 49 / 50 / 49 ms | 0 / 360 / 0 | 0% / 0.39% / 0% |
| 40 | 1 | 1462.0 | 23.93 ms | 50 ms | 70 ms | 3431 | 3.76% |
| 50 | 1 | 1417.5 | 30.74 ms | 67 ms | 94 ms | 5054 | 5.61% |

**拐点判断**：20 → 30 并发 QPS 仅从 1532 升到 1548（+1%），P95 从 22 ms 涨到 35 ms；40 并发 QPS 回落到 1462 且开始出错。吞吐在 **30 并发附近饱和**，继续加压只增加排队与延迟。

## 四、Snowflake 取号并发梯度

| 并发 | 轮次 | QPS | 平均 | P95 | P99 | 错误率 | ID 唯一性 |
|---:|---|---:|---:|---:|---:|---:|---|
| 1（冒烟） | 1 | 1726.7 | 0.32 ms | 1 ms | 2 ms | 0% | 8770 个，0 重复 |
| 10 | 1 | 20901.3 | 0.32 ms | 1 ms | 2 ms | 0% | 878531 个，0 重复 |
| 30 | 3 | 21942.6 / 23136.7 / 22959.8 | 0.72 / 0.69 / 0.69 ms | 2 ms | 4 ms | 0% | 938024 / 989303 / 974064，合计 2,901,391 个，0 重复、0 非法 |

Snowflake 为纯内存 CAS 取号，不吃下游 IO，因此单实例吞吐远高于业务读接口；同机客户端本身也是瓶颈之一。

## 五、失败分析（关注列表 40/50 并发）

- 失败样本 HTTP 状态码均为 **200**，响应体仅 **292 字节**（正常成功响应为 1433 字节），业务断言判定 `success` 不为 true。
- 服务端 `xiaohashu-user-relation-1` 在对应时间窗内 **无 ERROR、无 WARN、无 `success:false`、无 Sentinel 限流记录**。
- 失败**集中在测试尾声**（第 50 轮为 56–75 s，30 并发那一轮为 68–72 s），且同期成功请求数同步下降。
- 结论：属于高压末期服务端资源（连接池 / 下游 RPC）或客户端读取的偶发失败，**根因未定位**。因此简历只引用 **20 并发三轮零错误** 的数据，30 并发数据仅作饱和点参考。

## 六、局限（不得越界引用）

1. 客户端与服务**同机共享 4 vCPU**，压测客户端自身 CPU 占用会拉低服务端可用算力，**结果不代表生产容量或线上 QPS**。
2. 关注列表夹具为 10 条固定关系、每页固定 10 条、缓存与用户资料已预热，**不代表冷启动或大分页场景**。
3. 引用的是 `infra/.runtime` 下的原始 JTL，未提交仓库；本报告只保留脱敏摘要。
4. 本次未测写链路（发布、关注、评论），不能据此推断写吞吐。
5. 30 并发那一轮的 0.39% 失败使该轮不具备简历引用资格，仅记录在案。

## 七、复现

```powershell
# 关注列表（需夹具 manifest）
pwsh -NoProfile -File infra/benchmarks/Invoke-JMeterLoad.ps1 -RunId <id> -Scenario following `
  -Threads 20 -RampSeconds 5 -WarmupSeconds 10 -DurationSeconds 60 `
  -FixtureManifestPath <manifest.json>

# Snowflake 取号
pwsh -NoProfile -File infra/benchmarks/Invoke-JMeterLoad.ps1 -RunId <id> -Scenario leaf `
  -Threads 30 -RampSeconds 5 -WarmupSeconds 10 -DurationSeconds 30

# 分析
python infra/benchmarks/summarize_jmeter.py infra/.runtime/benchmarks/<id>
```

## 八、清理记录

| 项 | 状态 |
|---|---|
| 关注列表夹具（RunId `20261020-loadfx`） | 已清理，SQL / Redis / Cassandra 夹具移除，ES 文档确认不存在；合成作者一条用户服务 L1 缓存按 1 h TTL 自然过期 |
| Leaf 专用标签 `resume_jmeter_b29e35ee9826` | 已核验 `description=owned_resume_load_20261020` 与 `max_id=10001` 后精确删除，复查 `resume_jmeter_%` 计数为 0 |
| JMeter 临时容器 | 全部由 runner 按所有权标签移除，`docker ps -a --filter label=codex.benchmark.kind=jmeter` 计数为 0 |
| 共享服务 | 压测结束后 user-relation / user / note / distributed-id-generator 均 healthy，未重启、未 OOM |

## 九、对旧稿 22000 QPS 的核对

旧简历写「JMeter 压测单机单节点吞吐量 22000+/s，平均响应约 4 ms」。

- **吞吐属实**：本次 30 并发三轮分别为 21943 / 23137 / 22960 QPS，中位 22960。
- **延迟口径不符**：实测平均 0.69–0.72 ms，P99 为 4 ms。旧稿的「4 ms」很可能把 P99 当成了平均值。
- **适用面必须限定**：该数字属于 Snowflake 纯内存取号接口，不能与业务读接口（关注列表约 1530 QPS）混为一谈。
