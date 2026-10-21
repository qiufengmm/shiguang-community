# 简历读接口基准

使用已有本地 Docker 服务，复现两项可核查的接口指标：

| 指标 | 测量口径 |
| --- | --- |
| 关注列表热读平均耗时、P95 | `/relation/following/list` 第 1 页，固定返回 10 个用户；关系 ZSet 和用户资料 Redis 已预热，包含真实用户服务 RPC |
| 笔记详情缓存耗时降幅 | 每篇新夹具笔记首次回源读取，与紧接着的 Caffeine 本地缓存命中读取配对；降幅为 `1 - 即时读取平均耗时 / 首次读取平均耗时` |

脚本为单客户端、并发 1，从 frontend 容器使用 curl 直连内部服务。每个请求新建 curl 进程和 TCP 连接；`time_total` 包含 DNS、连接及 HTTP 响应传输，不含 Docker exec 启动、宿主机进程和 JSON 解析开销。首次回源指笔记详情两级缓存未命中，不代表数据库或操作系统缓存冷启动。当前代码在返回完整详情前同步写入 Caffeine。

## 运行

需要 PowerShell 7.2+，并先确保已有服务健康。脚本不启动、停止或重启共享容器。以下命令在仓库根目录执行；每次换用新的 `RunId`，长度不超过 17，只含英文字母、数字和连字符，首位为字母或数字。已有结果目录不会覆盖。

先小跑验证数据、响应及清理：

```powershell
pwsh -File infra/benchmarks/Invoke-ResumeReadBenchmarks.ps1 -RunId 20261020-smk01 -Warmups 2 -Rounds 1 -PairsPerRound 2 -FollowingSamplesPerRound 3
```

正式运行：

```powershell
pwsh -File infra/benchmarks/Invoke-ResumeReadBenchmarks.ps1 -RunId 20261020-bench01 -Warmups 20 -Rounds 3 -PairsPerRound 100 -FollowingSamplesPerRound 100
```

正式配置产生 300 个关注热读样本、300 对笔记首读/即时读取样本；预热样本为 Round 0，不进入正式汇总。各请求检查 HTTP 和业务状态，关注结果检查目标用户与昵称，笔记检查 ID、标题、完整正文、作者和可见性。P95 使用 nearest-rank。失败样本使本轮不具备简历引用资格，不能只挑成功或最快结果。

## 结果文件

结果保存在 Git 忽略目录 `infra/.runtime/benchmarks/<RunId>/`：

- `report.json`：总量、分轮平均值/P50/P95、笔记平均耗时降幅，以及 `eligibleForResume`。
- `samples.jsonl`：逐请求耗时、校验结果与响应正文。
- `following-round-*.csv`、`note-round-*.csv`：分轮样本；对应 JSON 也保留。
- `manifest.json`：夹具身份、所有权检查及清理进度；另有容器身份、健康状态和资源快照。

引用前确认 `eligibleForResume=true`、样本齐全、清理完成且测量期间容器没有重启。原始文件保留在本地，便于复核；最终实测数字由本次运行报告给出。

## 夹具与恢复清理

脚本直接向 MySQL 插入独立用户、关注关系和笔记读夹具，正文通过真实 KV API 写入 Cassandra；这些夹具绕过关注/发布接口及计数事件，不能用于证明发布吞吐或写链路性能。SQL 变化仍可能触发 Canal/ES 同步。

正常结束或异常退出都会尝试清理。清理依据 manifest 核对所有权，按明确行、Redis key 和正文 UUID 处理；笔记设私密接口在事务中产生缓存失效 outbox，提交后清除该笔记实例的 Caffeine。未知 KV 写入结果会保留删除围栏以阻止迟到写复活，清理失败不能当作已完成。

失败后保留原 manifest，并用同一个 `RunId` 重试：

```powershell
pwsh -File infra/benchmarks/Invoke-ResumeReadBenchmarks.ps1 -RunId 20261020-bench01 -CleanupOnly
```

清理会验证本轮 SQL、Redis、Cassandra 数据已移除，并轮询确认对应的 Elasticsearch 文档不存在。合成作者在用户服务内的一条 Caffeine 资料缓存没有外部失效接口，会在 1 小时后自然过期；不为清除它重启共享服务。不能据此宣称所有系统痕迹立即清零。

## 简历使用边界

这些数字只描述本机已有 Docker 环境、短连接、并发 1 下的真实读接口与缓存收益，不证明 CompletableFuture 串并行收益、持续吞吐、线上 QPS 或公网时延。报告参数和环境必须与数字一起保存。

128 路热评重建竞争是受控正确性验收，八项计数覆盖是审计范围；两者都不能改写为性能数字，也不能据此推断承载人数或吞吐提升。
