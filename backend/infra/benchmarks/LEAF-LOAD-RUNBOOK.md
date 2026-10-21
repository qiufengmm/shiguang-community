# Leaf 取号接口压测运行手册

目标：在**本机单机单节点**上，用 JMeter 打出分布式 ID 生成服务的吞吐、延迟与 ID 唯一性，数字要能被复现、被追问。

正文命令按 **Git Bash** 写。**如果你的终端是 PowerShell，直接跳到最后的「附录：PowerShell 版命令」**，
那里有四条已经实测通过的等价命令。两套 shell 的坑不一样，混着抄会报错。

所有命令都在仓库根目录执行：

```bash
cd "F:/code/xiaohashu/xiaohashu"
```

---

## 0. 想在 GUI 里点一下就看到结果

**用 `leaf-load-gui.jmx`，不要用 `leaf-load.jmx`。**

区别：前者所有参数都写死了（`127.0.0.1:8085`、30 并发、5 秒爬坡、60 秒），
后者用的是 `${__P(...)}` 占位，只有在命令行传 `-J` 才有值，在 GUI 里会显示成一堆表达式。

| 文件 | 用在哪 | 结果能不能写简历 |
| --- | --- | --- |
| `leaf-load-gui.jmx` | 双击 `bin\jmeter.bat` 打开，点绿色三角 | **不能**（GUI 自身开销混进去了） |
| `leaf-load.jmx` | `docker run ... -n` 命令行 | 能 |

GUI 版的用法：

1. 双击 `D:\jmeter\apache-jmeter-5.6.3\bin\jmeter.bat`
2. `文件 → 打开` → `F:\code\xiaohashu\xiaohashu\infra\benchmarks\leaf-load-gui.jmx`
3. 点绿色三角 ▶
4. 跑完看左边的「聚合报告（看这个）」

前提是宿主机能访问到服务。`infra/compose.app.yaml` 里已经给 id-generator 加了
`ports: ["127.0.0.1:8085:8085"]`，宿主机 `curl http://127.0.0.1:8085/id/snowflake/get/resume-bench` 能拿到 ID 就说明通了。

**注意**：同一个测试计划里那个「察看结果树」默认是**关闭**的。别在 30 并发时打开它，
它会把每条响应都塞进内存，很容易把 JMeter 自己搞崩。

---

## 0.5 正式口径

| 项 | 口径 | 说明 |
| 被测对象 | `xiaohashu-distributed-id-generator-1` 单节点 | 雪花算法取号接口 `/id/snowflake/get/resume-bench` |
| 压力来源 | JMeter 也在本机 Docker 里跑 | 客户端和服务抢同一台机器的 CPU，**数字偏保守** |
| 有效窗口 | 去掉爬坡期 + 预热期后的稳态段 | 冷启动那几秒不算，否则公平性无从谈起 |
| QPS | 稳态样本数 ÷ 稳态实际跨度 | 用实际时间戳算，不用 JMeter 打印的整段值 |
| 延迟 | 用 P99，不用平均值 | 见第 6 节「坑 3」 |

**写完简历要能回答的三个问题**：并发多少？跑多久？失败多少个？三个数字必须和原始 JTL 对得上。

---

## 1. 前置检查（30 秒）

```bash
docker ps --format "{{.Names}}\t{{.Status}}" | grep id-generator
docker images --format "{{.Repository}}:{{.Tag}}" | grep temurin
docker network ls --format "{{.Name}}" | grep xiaohashu
```

三条都要有输出：

- 容器必须是 `Up ... (healthy)`。只 `Up` 不 `healthy` 说明健康检查还没过，等一会儿再压。
- 要有 `eclipse-temurin:17-jre`。没有就先 `docker pull eclipse-temurin:17-jre`。
- 要有 `xiaohashu_default` 网络。JMeter 容器靠它用容器名解析到被测服务。

> 为什么不直接用宿主机的 JMeter：本机没装 PowerShell 7.2，包装脚本 `Invoke-JMeterLoad.ps1` 跑不起来。
> 用 JRE 容器跑 JMeter 反而更干净 —— 能直接通过容器名访问服务，不受宿主机端口和代理干扰。

---

## 2. 冒烟跑（10 秒，确认链路通）

先小参数跑一遍，**不要一上来就 30 并发**。冒烟的目的是确认「能连上、能返回、断言不报错」。

```bash
MSYS_NO_PATHCONV=1 docker run --rm --network xiaohashu_default \
  -v "D:/jmeter/apache-jmeter-5.6.3:/jmeter:ro" \
  -v "$PWD/infra/benchmarks:/plan:ro" \
  -v "$PWD/infra/.runtime/benchmarks:/out" \
  eclipse-temurin:17-jre \
  java -jar /jmeter/bin/ApacheJMeter.jar -n -t /plan/leaf-load.jmx -l /out/smoke.jtl \
    -Jthreads=5 -Jramp_seconds=1 -Jwarmup_seconds=1 -Jduration_seconds=3
```

看到这样的输出就算通：

```
summary =  10428 in 00:00:06 = 1684.4/s Avg: 1 Min: 0 Max: 221 Err: 0
```

**`Err: 0` 是硬门槛。** 冒烟就有错，后面正式跑的数字全部作废，先回去看第 6 节。

---

## 3. 正式跑（约 1 分 20 秒）

30 并发，5 秒爬到 30 线程，预热 10 秒，然后稳态跑 60 秒。总时长 = 5 + 10 + 60 = 75 秒左右。

```bash
MSYS_NO_PATHCONV=1 docker run --rm --network xiaohashu_default \
  -v "D:/jmeter/apache-jmeter-5.6.3:/jmeter:ro" \
  -v "$PWD/infra/benchmarks:/plan:ro" \
  -v "$PWD/infra/.runtime/benchmarks:/out" \
  eclipse-temurin:17-jre \
  java -jar /jmeter/bin/ApacheJMeter.jar -n -t /plan/leaf-load.jmx -l /out/leaf-30t.jtl \
    -Jthreads=30 -Jramp_seconds=5 -Jwarmup_seconds=10 -Jduration_seconds=60
```

### 每个参数在干什么

| 片段 | 作用 | 不写会怎样 |
| --- | --- | --- |
| `MSYS_NO_PATHCONV=1` | 关掉 Git Bash 的路径自动转换 | `/jmeter/bin/...` 会被改写成 `D:/jmeter/...`，容器里找不到 |
| `--network xiaohashu_default` | 把 JMeter 容器接进服务所在网络 | 容器名解析不了，报 `UnknownHostException` |
| `-v "D:/jmeter/apache-jmeter-5.6.3:/jmeter:ro"` | 挂载本机 JMeter，只读 | 容器里没有 JMeter |
| `-v "$PWD/infra/benchmarks:/plan:ro"` | 挂载测试计划 jmx | 找不到 `-t` 指定的文件 |
| `-v "$PWD/infra/.runtime/benchmarks:/out"` | 挂载结果目录，**可写** | 结果写不出来 |
| `-n` | 非 GUI 模式 | 弹不出图形界面，容器里直接崩 |
| `-t` | 测试计划路径（容器内路径） | — |
| `-l` | 结果文件路径（容器内路径） | — |
| `-Jthreads` | 并发线程数 | 默认 1，等于没压 |
| `-Jramp_seconds` | 多少秒爬满并发 | 瞬间打满，容易把服务打出一堆超时 |
| `-Jwarmup_seconds` | 爬满后再空跑多久才开始计时 | JIT、连接池、缓存都还没热，前几秒数据失真 |
| `-Jduration_seconds` | 稳态持续多久 | 太短则样本不足，分位数不可信 |

### 怎么读 JMeter 打印的这几行

```
summary +   9009 in 00:00:03 = 3473.0/s ... Active: 30 Started: 30 Finished: 0
summary + 222126 in 00:00:11 = 19531.0/s ... Active: 0 Started: 30 Finished: 30
summary = 231135 in 00:00:14 = 16548.7/s ...
```

- 带 `+` 的是**每 30 秒滚动的一段**，带 `=` 的是全量累计
- **最下面那个 `=` 的全量 QPS 不是你要的数** —— 它把爬坡期和预热期也算进去了，偏低
- 要的是稳态段，必须用第 4 节的脚本单独算

---

## 4. 汇总出可以写进简历的数字

```bash
"C:/Users/qiufengm/.workbuddy/binaries/python/versions/3.11.9/python.exe" \
  infra/benchmarks/summarize_leaf.py infra/.runtime/benchmarks/leaf-30t.jtl --ramp 5 --warmup 10
```

`--ramp` 和 `--warmup` **必须和第 3 节命令里传的值一致**，否则稳态窗口算错。

实际输出（2026-10-20，30 并发）：

```
总时长      : 74.9s（首个到末个样本）
稳态窗口    : 15s 之后，跨度 59.9s
总样本      : 1585021
稳态样本    : 1324612（失败 0）
QPS         : 22128 /s
平均耗时    : 0.83 ms
P50 / P95 / P99 : 1 / 3 / 5 ms
最快 / 最慢 : 0 / 41 ms

结论可用性  : 可以引用
```

**只有出现「可以引用」才能往简历上写。** 有失败样本时脚本会打出失败分布并判定不可引用 —— 不能只挑成功的样本报数。

---

## 5. 验证 ID 不重复

默认的 CSV 格式**不记录响应体**，只记了字节数。想查重必须换成 XML 格式并把响应体打开。

**关键前提：吞吐和查重必须分两轮跑，不能合成一轮。**

实测对比（同为 30 并发）：

| 结果格式 | 稳态 QPS | 说明 |
| --- | --- | --- |
| CSV，不存响应体 | 22128 /s | 这才是能写进简历的吞吐 |
| XML，存响应体 | 2318 /s | **慢了近 10 倍，这轮的吞吐作废** |

原因不是磁盘慢（477 字节 × 2318 条 ≈ 1 MB/s，对 SSD 毫无压力），而是 JMeter 要把每个响应体
读进内存再拼成 XML 字符串，30 个线程不停堆对象，GC 直接成为瓶颈。**压力源自己先撑不住了**，
测出来的就不是被测服务的上限。

所以：**第 3 节那轮出吞吐，本节这轮只出 ID 唯一性，两轮的数字不许混着用。**

本节命令（130 秒稳态，约 34 万样本，文件 155MB）：

```bash
MSYS_NO_PATHCONV=1 docker run --rm --network xiaohashu_default \
  -v "D:/jmeter/apache-jmeter-5.6.3:/jmeter:ro" \
  -v "$PWD/infra/benchmarks:/plan:ro" \
  -v "$PWD/infra/.runtime/benchmarks:/out" \
  eclipse-temurin:17-jre \
  java -jar /jmeter/bin/ApacheJMeter.jar -n -t /plan/leaf-load.jmx -l /out/leaf-uniq.jtl \
    -Jjmeter.save.saveservice.output_format=xml \
    -Jjmeter.save.saveservice.response_data=true \
    -Jjmeter.save.saveservice.response_message=false \
    -Jjmeter.save.saveservice.thread_name=false \
    -Jjmeter.save.saveservice.data_type=false \
    -Jjmeter.save.saveservice.sent_bytes=false \
    -Jjmeter.save.saveservice.idle_time=false \
    -Jjmeter.save.saveservice.connect_time=false \
    -Jjmeter.save.saveservice.url=false \
    -Jjmeter.save.saveservice.latency=false \
    -Jthreads=30 -Jramp_seconds=5 -Jwarmup_seconds=10 -Jduration_seconds=130
```

那一堆 `jmeter.save.saveservice.*` 是为了瘦身：每去掉一个字段，每条样本就少几十字节，
乘以 34 万条就是几十 MB 的差别。**`output_format=xml` + `response_data=true` 这两个不能省**，
其余可以按需删。

然后流式解析查重（不要一次性读进内存）：

```bash
"C:/Users/qiufengm/.workbuddy/binaries/python/versions/3.11.9/python.exe" \
  infra/benchmarks/check_leaf_unique.py infra/.runtime/benchmarks/leaf-uniq.jtl --show-max
```

输出长这样：

```
样本总数    : 338508
取到 ID     : 338508
去重后      : 338508
重复        : 0
最小 / 最大 : 2112382676706021376 / 2112426016573063040

结论        : 通过，全部唯一
```

判据：`重复 0` 且 `无响应体 0` 才成立。只要出现 1 个重复，雪花算法就有问题，简历上不能写这句话。

> 已实测两次：
> - 2026-10-20 13:12（容器内跑）：样本 338508 条，去重后 338508，**重复 0**
> - 2026-10-20 14:29（宿主机跑，`-Jhost=127.0.0.1`）：见下方「最近一次结果」
>
> 想提高验证数量只能把 `duration_seconds` 调长 —— 存响应体会把吞吐压到 2000~2500/s，
> 跑到 100 万大约 7 分钟。

---

## 6. 踩过的坑

### 坑 1：`pwsh` 没装，包装脚本跑不起来

`Invoke-JMeterLoad.ps1` 要求 PowerShell 7.2+，本机只有 5.1。所以别走包装脚本，直接 `docker run`。

### 坑 2：路径被 Git Bash 改写

不加 `MSYS_NO_PATHCONV=1`，`-v "D:/jmeter/...:/jmeter"` 里的 `/jmeter` 会被翻译成 Windows 路径，挂载点错位。
**症状**：`Cannot find /plan/leaf-load.jmx` 或类找不到。

### 坑 3：平均值不可信，只能用 P99

JTL 里的 `elapsed` 是**整数毫秒**。这个接口大部分请求在 1ms 以内，被截断成 `0`。
所以算出来的「平均 0.83ms」是**偏低**的，`最快 0` 也是同一个原因。

用 Little's Law 一验就知道了：

```
实际平均延迟 = 并发 ÷ QPS = 30 ÷ 22128 ≈ 1.36 ms
```

和 0.83ms 差一倍，差额全被毫秒截断吃掉了。

**结论**：简历上写 QPS 和 P99，**不要写平均值**。被追问「平均多少」时，直接答「按 30 并发除以实测 QPS 推出来约 1.4ms，JTL 的毫秒精度不够，平均会被低估」。

### 坑 4：客户端和服务同机，数字偏保守

JMeter 容器和 Leaf 容器跑在同一台机器上，抢同一份 CPU。所以 30 并发跑出 2.2 万 QPS，
真实独立客户端压过来会比这个高。

**这不是缺陷，是口径。** 汇报时必须带上这句话，否则被追问「客户端在哪」就露馅了。

### 坑 5：JTL 文件太大

30 并发 60 秒的 CSV 就有 275MB，XML 带响应体的 130 秒会到 1.4GB。
这些文件在 `infra/.runtime/benchmarks/` 下（已被 git 忽略），**不要提交进仓库**。
查完重就把大文件删掉，只留汇总出来的数字和命令。

### 坑 6：容器不是 healthy 就开始压

服务刚启动时连接池是空的、JIT 还没编译，第一波请求会慢得离谱。
**必须等 `healthy`**，并且靠 `warmup_seconds` 把这几秒从统计窗口里剔掉。

---

## 7. 数字和简历怎么对应

跑完之后，简历那一句的每一处数字都要换成实测值：

| 简历里的位置 | 填什么 | 来源 | 本轮实测 |
| --- | --- | --- | --- |
| 并发数 | 30 | `-Jthreads` | 30 |
| QPS | 2.2 万 | 第 4 节脚本的 `QPS` | 22128 /s |
| 延迟 | P99 5 ms | 第 4 节脚本的 `P99` | 5 ms |
| 失败数 | 0 | 第 4 节脚本的「失败」 | 0 |
| ID 唯一性 | 33 万 | 第 5 节脚本的「去重后」 | 338508，重复 0 |

**不许写没跑出来的数字。** 教程原句「22000+/s、平均响应 4ms、日提供 19 亿次」是别人的环境：
- `22000+/s`：我们复现到 22128，这句成立
- `平均 4ms`：本机实测是 0.83ms（且被毫秒截断低估），写 4ms 属于编造
- `日提供 19 亿次`：没连续跑过一天，无法验证

**建议的最终写法**（三处数字全部可复现）：

> 单机单节点压测 30 并发约 2.2 万 QPS、P99 5 ms、0 失败，逐条校验 33 万个 ID 无重复。

---

# 附录：PowerShell 版命令

Windows 自带的终端默认是 PowerShell，直接抄正文的 bash 命令会全线报错。四条命令都已在本机实测通过。

## 先看两套 shell 的五处差异

| 差异点 | Git Bash | PowerShell | 后果 |
| --- | --- | --- | --- |
| `grep` | 有 | **没有** | 报 `无法将"grep"项识别为 cmdlet` |
| 管道过滤 | `\| grep xxx` | `\| Select-String xxx` | — |
| `MSYS_NO_PATHCONV=1` | 必须加 | **写了也没用，反而报错** | 它只对 Git Bash 的路径改写生效；PS 里会被当成命令名 |
| 多行续行 | 行尾 `\` | 行尾反引号 `` ` ``（后面不能有空格） | 续行符后多一个空格，整条命令就断了 |
| 执行带引号的程序路径 | `"C:/xxx/python.exe"` | `& "C:\xxx\python.exe"` | PS 里光写引号路径会被当成字符串，`&` 才是调用运算符 |

**建议：PowerShell 下把 `docker run` 写成一行。** 反引号续行太容易因为看不见的尾随空格断掉，
一行虽然长，但复制粘贴绝不会错。

## ① 检查环境

```powershell
docker ps --format "{{.Names}} | {{.Status}}" | Select-String id-generator
docker images --format "{{.Repository}}:{{.Tag}}" | Select-String temurin
docker network ls --format "{{.Name}}" | Select-String xiaohashu
```

实测输出：

```
xiaohashu-distributed-id-generator-1 | Up 29 minutes (healthy)
eclipse-temurin:17-jre
xiaohashu_default
```

## ② 冒烟（10 秒）

```powershell
docker run --rm --network xiaohashu_default -v "D:/jmeter/apache-jmeter-5.6.3:/jmeter:ro" -v "F:/code/xiaohashu/xiaohashu/infra/benchmarks:/plan:ro" -v "F:/code/xiaohashu/xiaohashu/infra/.runtime/benchmarks:/out" eclipse-temurin:17-jre java -jar /jmeter/bin/ApacheJMeter.jar -n -t /plan/leaf-load.jmx -l /out/smoke.jtl -Jthreads=5 -Jramp_seconds=1 -Jwarmup_seconds=1 -Jduration_seconds=3 2>&1 | Select-String "summary|Err"
```

实测输出：

```
summary =  61373 in 00:00:06 = 10570.6/s Avg:     0 Min:     0 Max:    48 Err:     0 (0.00%)
```

## ③ 正式跑（30 并发 / 约 1 分 20 秒）

```powershell
docker run --rm --network xiaohashu_default -v "D:/jmeter/apache-jmeter-5.6.3:/jmeter:ro" -v "F:/code/xiaohashu/xiaohashu/infra/benchmarks:/plan:ro" -v "F:/code/xiaohashu/xiaohashu/infra/.runtime/benchmarks:/out" eclipse-temurin:17-jre java -jar /jmeter/bin/ApacheJMeter.jar -n -t /plan/leaf-load.jmx -l /out/leaf-30t.jtl -Jthreads=30 -Jramp_seconds=5 -Jwarmup_seconds=10 -Jduration_seconds=60 2>&1 | Select-String "summary|Err"
```

## ④ 汇总

```powershell
& "C:\Users\qiufengm\.workbuddy\binaries\python\versions\3.11.9\python.exe" "F:\code\xiaohashu\xiaohashu\infra\benchmarks\summarize_leaf.py" "F:\code\xiaohashu\xiaohashu\infra\.runtime\benchmarks\leaf-30t.jtl" --ramp 5 --warmup 10
```

实测输出（冒烟文件示例，正式文件换成 `leaf-30t.jtl`）：

```
稳态样本    : 44559（失败 0）
QPS         : 15553 /s
P50 / P95 / P99 : 0 / 1 / 2 ms
结论可用性  : 可以引用
```

## ⑤ 查重

第 5 节那条 `docker run` 同样改成一行、去掉 `MSYS_NO_PATHCONV=1` 即可；
解析脚本用 `& "C:\...\python.exe" -c "..."` 调用，双引号内的字符串用单引号包，避免转义地狱。
如果一行写不下，把脚本存成 `.py` 文件再 `& python.exe 文件路径` 执行，比在 PS 里拼 `-c` 可靠得多。
