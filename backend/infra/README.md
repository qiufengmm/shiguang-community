# 拾光社区（Shiguang Community）本机完整应用部署

拾光社区是一个围绕日常生活、兴趣与经验分享的图文社区，支持内容发布与搜索、点赞收藏、关注互动和评论回复。

完整应用使用 `compose.yaml` 加 `compose.app.yaml`：在 Docker Desktop 的同一个 **xiaohashu** 应用组中运行原版 Vue 3 前端、12 个业务服务和基础设施。业务服务包括 auth、gateway、oss、user、kv、distributed-id-generator、note、user-relation、count、data-align、search、comment。本文后半部分保留基础设施及 FRP 的单独运行说明；查看完整项目效果请使用本节的应用启动脚本。

| 入口 | 地址 | 用途 |
| --- | --- | --- |
| 原版前端 | <http://localhost:8088>；局域网设备访问 `http://<本机局域网IP>:8088` | 浏览发现页、个人页等页面 |
| Gateway | <http://localhost:8000> | 后端统一 API 入口 |
| 本地 FRP | <http://localhost:18000> | 经本机 frps/frpc 转发到前端，供本地联调 |

### 启动与重新构建

先启动 Docker Desktop 并等待 Docker 引擎就绪。在这台已经构建好镜像的电脑上，日常启动时在 PowerShell 中执行：

```powershell
# 从仓库根目录执行
Set-Location backend
& .\infra\Start-Local.ps1 -SkipBuild
```

首次启用评论可靠投递版本前，先将新增的 outbox/inbox、删除墓碑和评论计数事件表建到当前 MySQL。脚本是幂等的，只创建缺失表；它不会自动执行数据库迁移：

```powershell
Get-Content -Raw database/25.12-comment-message-reliability.sql |
  docker compose --env-file infra/.runtime/compose.env -f infra/compose.yaml -f infra/compose.app.yaml exec -T mysql sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 -uroot'
```

启用笔记发布数可靠投递前，还需应用 `database/25.13-note-publish-count-reliability.sql`。该迁移会在 `xiaohashu` 库中创建笔记 outbox、计数幂等事件表和按用户加锁表；先迁移，再部署更新后的 note/count 镜像：

```powershell
Get-Content -Raw database/25.13-note-publish-count-reliability.sql |
  docker compose --env-file infra/.runtime/compose.env -f infra/compose.yaml -f infra/compose.app.yaml exec -T mysql sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 -uroot'
```

启用笔记正文 MySQL/Cassandra 故障恢复前，还需应用 `database/25.15-note-content-saga.sql`，然后再部署新版 note 镜像。该表为正文发布、编辑、清空、旧正文清理及迟到写补偿保存持久恢复状态；未完成的发布暂存正文载荷，完成后会清空载荷。恢复任务使用租约和退避持续处理未收敛操作。正文指针提交前会锁定有效租约，过期写请求完成后使用独立补偿记录重新排队，避免恢复任务已经结案后留下 Cassandra 孤儿正文。清空恢复会同时检查软删除记录，防止把仍被笔记引用的正文误删。

```powershell
Get-Content -Raw database/25.15-note-content-saga.sql |
  docker compose --env-file infra/.runtime/compose.env -f infra/compose.yaml -f infra/compose.app.yaml exec -T mysql sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 -uroot'
```

还需在 Cassandra 为正文表增加删除围栏，再部署新版 KV 和 note 镜像。删除会保留 `is_deleted=true` 的轻量行；正文 UUID 永不复用，保存和删除都使用 Cassandra LWT 同一分区串行化，因此删除完成后迟到的旧写入会被拒绝。这个标记不能设 TTL 或定期清理，否则过期请求仍可能复活已删除正文；会随历史正文版本数持续增长。

```powershell
Get-Content -Raw database/25.16-note-content-delete-fence.cql |
  docker exec -i xiaohashu-cassandra-1 cqlsh
```

`database/25.14-reconcile-note-publish-count.sql` 是单用户手动对账脚本。运行前须确认笔记写服务和计数服务都已更新，并检查 `NoteOperateTopic`、对应重试队列对 `xiaohashu_group_NoteOperateTopic` 的积压与在途数为 0，且该组没有 DLQ 消息。脚本会锁住该用户的笔记计数锁行、读取 `t_note.status = 1` 的源数量、将已有 outbox 事件登记为已计数后再校准 `t_user_count.note_total`；只能明确设置用户 ID 后单用户运行，不用于批量自动写回。

迁移完成后再启动或重建评论服务。恢复或迁移已有数据时，先确认 MySQL 数据库为 `xiaohashu`，不要把脚本指向临时恢复实例之外的目标。

`-SkipBuild` 使用已经生成的本机镜像。脚本准备本机配置，检查 Compose 配置、绑定路径和端口，并等待容器健康检查，最多等待 600 秒。脚本不会停止其他容器；若发现其他 Compose 项目正占用所需数据目录或卷，会报错并保留现状。首次冷启动需要数分钟。前端默认监听所有网卡的 8088 端口，局域网设备能否访问还取决于 Windows 防火墙与网络隔离设置；基础设施和网关端口只监听本机回环地址。

首次构建、应用镜像缺失或修改代码后，重新构建并启动：

```powershell
# 同时构建全部后端镜像、原版前端镜像，再启动应用。
& .\infra\Start-Local.ps1

# 也可分别构建；完成后用 -SkipBuild 启动新镜像。
& .\infra\Build-Images.ps1
& .\infra\Build-Frontend.ps1
& .\infra\Start-Local.ps1 -SkipBuild
```

`Build-Images.ps1` 显式使用 `D:\java\jdk-17.0.18+8`，不要用 shell 默认的 Java 21 替代。单仓库中的前端位于后端目录的 `../frontend`；先在 `frontend/` 执行 `npm ci`，再从 `backend/` 运行构建脚本。脚本执行 `npm run build`，将产物打入 `xiaohashu/frontend:local`。前端通过同源 `/api` 请求，由 Nginx 去掉 `/api` 后转发到 `gateway:8000`，保留各服务的双层路径前缀。

查看启动状态或日志时，始终带上两个 Compose 文件和本机环境文件：

```powershell
docker compose --env-file infra/.runtime/compose.env -f infra/compose.yaml -f infra/compose.app.yaml --profile frp-local-test ps -a
docker compose --env-file infra/.runtime/compose.env -f infra/compose.yaml -f infra/compose.app.yaml --profile frp-local-test logs --tail 100 gateway note frontend
```

`minio-init` 是创建本地桶并设置下载权限的一次性任务，显示 `Exited (0)` 表示成功，属于正常状态。其他服务应结合健康状态、日志及实际页面/API 请求判断是否就绪，不能仅看容器数量。

日常可在 Docker Desktop 中对 `xiaohashu` 整组点击停止。终端停止整组时执行下面的命令；它保留容器、镜像及数据，之后用启动脚本恢复：

```powershell
docker compose --env-file infra/.runtime/compose.env -f infra/compose.yaml -f infra/compose.app.yaml --profile frp-local-test --profile tools stop
```

### 现有数据、凭据与本机配置

默认部署复用这台电脑已有的 `F:/docker/...` 挂载目录、MySQL/ZooKeeper 外部卷及 XXL-JOB 日志卷。Compose 通过 `XIAOHASHU_DATA_ROOT` 统一这些目录，默认值仍是 `F:/docker`；卷名默认沿用当前主机上的现有卷。不要让两套数据库容器同时使用同一数据目录，也不要执行 `docker compose down -v`。换电脑时需要迁移数据并指定新路径和新卷名，单独导入镜像不等于迁移了数据库和上传文件。

`Start-Local.ps1` 默认使用 `-Mode ReuseExisting`，保留已有数据映射。显式的 `-Mode Fresh` 只用于新的独立项目检出和全新数据根目录：它需要可信的静态配置种子目录，不会导入数据库或对象数据；如果 `infra/.runtime` 已存在或原 `xiaohashu` Compose 项目仍在运行，脚本会拒绝继续。新机器恢复数据前先阅读 [本地备份与隔离恢复](RESTORE.md)。

`Prepare-Local.ps1` 从现有容器 metadata 和现有本机基础设施配置读取凭据，写入已被 Git 忽略的 `infra/.runtime/`。项目根 `.evn` 始终不读、不写、不提交；运行时凭据也不进入镜像构建上下文。已有配置完整时可重复使用；需要重新读取本机配置时执行 `& .\infra\Prepare-Local.ps1 -Refresh`。不要把 `.runtime` 内容贴到日志或提交到仓库。

Java 服务代码和 JRE 放在镜像中，容器可以按需重建。部署配置和凭据保存在 Git 忽略的 `infra/.runtime/`，避免把本机机密放进镜像或仓库。请把 `.runtime` 和数据目录一起保留。

Canal 实例配置及消费位点保存于 `infra/.runtime/canal-example` 并挂载到容器，重建容器继续复用已有位点。这里包含部署所需的持久状态，需要和其他本机配置一同保留。

### 运行配置与体验范围

应用覆盖配置位于 `infra/docker/application-docker.yml`，使用 `dev,docker` profile；容器之间通过 Compose 服务名访问。Docker 环境关闭 Count 的 Sentinel 随机故障演示，原教程 dev 默认行为仍保留。只在 `docker` profile 加载的只读前端适配接口提供频道、话题、发现页及个人页列表，属于本地联调补充。

Docker 网关使用单独的身份头过滤器：绑定当前请求的 Sa-Token 上下文后读取登录身份，再设置下游 `userId`，避免服务发现切换线程后用户 ID 丢失。该过滤器替代旧过滤器仅在 `docker` profile 生效，普通 dev 仍使用教程原实现。

这套配置针对 Docker Desktop 分配约 **8 GiB 内存**的本机环境收紧了 JVM 堆和连接池：业务服务使用较小堆；Cassandra 堆为 512 MiB；Nacos 配置旧 JDK 的容器/cgroup 兼容参数。Redis 镜像默认 entrypoint 自动加载 Bloom，配置文件再加载 Roaring，避免重复加载模块。这里的内存限制供本地看效果使用，没有经过生产容量或并发验收。

Kibana、ES Head、RocketMQ Dashboard 放在可选 `tools` profile，默认不启动。需要这些工具时执行：

```powershell
& .\infra\Start-Local.ps1 -SkipBuild -WithTools
```

页面读取已有数据库数据。容器内的 MinIO 地址是 `minio:9000`，浏览器图片和视频通过同源 `/media/xiaohashu-local/` 只读代理访问，支持视频 Range 请求；历史记录中已知的本地 MinIO 绝对 URL 会转换成该同源路径，云 OSS URL 保持原样。上传结果直接返回同源媒体路径，因此另一台局域网设备不需要访问主机的 MinIO 端口。阿里云短信密钥可通过运行 Compose 的当前 shell 环境变量 `ALIYUN_ACCESS_KEY_ID` 和 `ALIYUN_ACCESS_KEY_SECRET` 传给 `auth` 容器；不要把 AccessKey 写入 Git 跟踪文件。未设置时，Docker 登录仍可使用本地演示验证码，真实短信不可用。短信发送接口是异步的，接口成功不代表手机已经收到短信。

本地当前版本的评论持久投递依赖 `database/25.12-comment-message-reliability.sql`；笔记发布数幂等投递依赖 `database/25.13-note-publish-count-reliability.sql`；笔记正文跨存储恢复依赖 MySQL 迁移 `database/25.15-note-content-saga.sql` 和 Cassandra 迁移 `database/25.16-note-content-delete-fence.cql`。新数据库部署前需按顺序应用所需迁移；当前电脑的 `xiaohashu` 库已包含这些结构。七项计数任务（关注、粉丝、笔记点赞/收藏、作者获赞/获收藏、笔记发布）会按每日增量标记重算对应 MySQL 源表，并在行锁事务内修复不一致值。用户 100 的发布笔记数曾按 `database/25.14-reconcile-note-publish-count.sql` 的锁与 MQ 水位前置条件从 49 校准到源表 52。配置或手动重跑对账时，在 PowerShell 设置 XXL-JOB 管理员密码后执行：

```powershell
$env:XXL_JOB_ADMIN_PASSWORD = '<本机 XXL-JOB 管理员密码>'
& .\infra\Configure-CountAudits.ps1 -EnableMarkerCleanup -RunOnce
```

七项任务每天 03:00 扫描标记实体；发现差异会调用独立事务服务，在目标计数行 `SELECT ... FOR UPDATE` 后重新读取源表并写入绝对值。无法获得行锁或写回失败时任务失败，不会静默略过。任务不扫描从未被标记的全部历史实体；相关业务消息在源写入后同步投递，计数消费按源表重算，异常会重试，数据对齐消费者另行标记实体供日常校准。可选的过期标记表清理任务每天 23:00 执行，先重新对账，仍未解决的差异或对账失败会保留表。

原版前端仅提供验证码登录弹窗。本机体验可在 PowerShell 中为自己的手机号生成一个有效期为 180 秒的本地验证码：

```powershell
& .\infra\Get-LocalLoginCode.ps1 -Phone '你的11位手机号'
```

打开 8088 前端的登录弹窗，填写该手机号与终端显示的验证码，直接登录，不点击“获取验证码”（该按钮会重新生成验证码并尝试云短信）。脚本只在本机 Redis 写入临时验证码，登录继续经过原有验证码检查、自动注册及 SaToken 鉴权，不会发送短信。首次登录自动创建的账户是供你使用的本地数据。密码修改和密码登录 API 已纳入本次端到端验证，原版前端未提供对应的密码登录页面。

教程主线推进到 28.10，若干教程缺项与已知边界仍未完成专项验收，详见 [教程交付状态与本地部署边界](../docs/deployment/tutorial-status.md)。即使整组应用启动成功、前端显示数据，也只说明对应部署路径可用，不表示全部教程已无缺项或全部功能无 Bug。FRP 18000 是本地转发入口；公网服务器验收等用户有服务器后再做。

本轮已通过的实际接口链路、清理结果及部署适配说明见 [完整应用本地运行验收](../docs/deployment/local-verification.md)。

评论消息持续失败时的 outbox/inbox 查询、重试边界和 DLQ 重投步骤见[评论消息故障排查与恢复](COMMENT-DELIVERY-RECOVERY.md)。

---

# Xiaohashu local infrastructure

`compose.yaml` groups the Docker images used by this project: MySQL, Redis with roaring-bitmap support, Nacos, MinIO, Cassandra, ZooKeeper, RocketMQ, Elasticsearch/Kibana/ES Head, Canal, XXL-JOB, and Sentinel Dashboard. Logstash is installed on the Windows host at `F:\java_workspace\logstash-7.3.0`, so it is not a container in this file.

## Existing host data

This Compose file is wired to the current Windows machine's existing data:

- It mounts the existing `F:\docker\...` directories for Redis, Nacos, MinIO, Cassandra, ZooKeeper, RocketMQ, Elasticsearch, and Kibana.
- It references the current MySQL and ZooKeeper Docker volumes as external volumes. Compose will not create, initialize, or remove these volumes.
- It reuses the existing XXL-JOB log volume.
- The Compose-only RocketMQ broker config advertises `host.docker.internal` so host-run Spring services can reach the published broker port. The existing `F:\docker\rocketmq\broker\config\broker.conf` is left unchanged.

Published infrastructure ports and local FRP ports bind to `127.0.0.1`, so these dashboards and databases are for local access from this computer. Container-to-container traffic continues to use Compose service names. MinIO, Canal, XXL-JOB Admin, and Sentinel Dashboard expose Compose health status; dependent services wait for MinIO, Canal, or XXL-JOB Admin to become healthy where applicable.

The Compose ports are bound to loopback. The application start script checks for foreign containers using the required bind paths or external volumes and refuses to proceed; it does not stop or remove containers. Resolve a reported conflict manually after checking ownership. Do not run `docker compose down -v`.

The bind paths and anonymous-volume IDs are specific to this Docker host. On a different computer, update those paths and volume names to the data that should be retained before starting the stack.

## Start the full local stack

Set the credentials in the current PowerShell session; the repository does not contain them. `CANAL_DB_USERNAME` and `CANAL_DB_PASSWORD` must match the MySQL account already granted binlog replication privileges. `XXL_JOB_MYSQL_PASSWORD` is the password for the existing `xxl_job` database account.

```powershell
$env:MYSQL_ROOT_PASSWORD = '<existing MySQL root password>'
$env:MINIO_ROOT_PASSWORD = '<existing MinIO root password>'
$env:CANAL_DB_USERNAME = '<existing Canal MySQL user>'
$env:CANAL_DB_PASSWORD = '<existing Canal MySQL password>'
$env:XXL_JOB_MYSQL_PASSWORD = '<existing XXL-JOB MySQL password>'

docker compose -f infra/compose.yaml config --quiet
docker compose -f infra/compose.yaml up -d
```

The MySQL root password environment variable is required by the image but does not change a password in an already initialized data volume. Use the current password for that existing database.

## FRP from tutorial 27.8

FRP is optional and uses the tutorial's `fatedier/frps:v0.61.2` and `fatedier/frpc:v0.61.2` images. Token and dashboard password are read from the process environment and are not stored in the repository. FRP's official TOML template support reads values as `{{ .Envs.NAME }}`; FRP 0.61.2 supports this format. See the [FRP configuration guide](https://gofrp.org/en/docs/features/common/configure/).

Generate a fresh token and dashboard password in PowerShell, then set the server address. The random values stay in this terminal session:

```powershell
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
$tokenBytes = New-Object byte[] 48
$rng.GetBytes($tokenBytes)
$env:FRP_TOKEN = [Convert]::ToBase64String($tokenBytes)

$passwordBytes = New-Object byte[] 32
$rng.GetBytes($passwordBytes)
$env:FRP_WEB_PASSWORD = [Convert]::ToBase64String($passwordBytes)
$rng.Dispose()
$env:FRP_WEB_USER = 'admin'
```

For a **local-only forwarding check**, FRPC forwards to the frontend container on port 80 and publishes the forwarded port at `127.0.0.1:18000`:

```powershell
$env:FRP_SERVER_ADDR = 'frps'
$env:FRP_LOCAL_IP = 'frontend'
$env:FRP_LOCAL_PORT = '80'
$env:FRP_REMOTE_PORT = '8000'
$env:FRPS_HOST_BIND_PORT = '17000'
$env:FRPS_HOST_WEB_PORT = '17500'
$env:FRPS_HOST_REMOTE_PORT = '18000'

docker compose --env-file infra/.runtime/compose.env -f infra/compose.yaml -f infra/compose.app.yaml --profile frp-local-test up -d frps frpc
Invoke-WebRequest -UseBasicParsing 'http://127.0.0.1:18000'
docker compose --env-file infra/.runtime/compose.env -f infra/compose.yaml -f infra/compose.app.yaml --profile frp-local-test stop frpc frps
```

For the tutorial's public setup, a public VPS is still required. Run `frps` there with profile `frp-server`, set `FRPS_HOST_BIND_ADDR` to the VPS public-facing interface address (or `0.0.0.0`), run only `frpc` on this computer with profile `frp-client`, and set `FRP_SERVER_ADDR` to the VPS public IP. Open the configured FRP bind and remote TCP ports in the VPS firewall; restrict the dashboard port to your own IP. The repository does not include a token or VPS address. No public reachability test is possible until a VPS is available.

The local forwarding check verifies FRP's TCP tunnel between this PC and its own Docker containers; it does not prove the external firewall, public IP, or VPS path.

## Back up and restore local data

`Backup-Local.ps1` creates a timestamped directory under `F:\docker\backups` (or the configured data root) with a SHA-256 manifest. It exports the `xiaohashu`, `nacos_config`, `xxl_job`, and `leaf` MySQL databases; snapshots Cassandra keyspace `xiaohashu`; copies the stopped MinIO data directory; copies ZooKeeper data; and includes deployment/runtime configuration. The Cassandra snapshot is named and has a one-day TTL. Cassandra expires it automatically; until then, snapshot hard links can keep old SSTables from being reclaimed while new writes create more SSTables, so allow for temporary disk growth. The script does not run `clearsnapshot` or remove snapshot trees. The backup-stopped check covers the named application services, Canal, Nacos, XXL-JOB, the ID generator, ZooKeeper, and MinIO; MySQL and Cassandra must remain running. The physical MinIO copy preserves the local store metadata and object versions. Runtime configuration contains credentials, so protect the backup like a secret. Redis and Elasticsearch are marked rebuildable, RocketMQ in-flight messages are excluded, and separate store snapshots are not an atomic cross-store point in time.

Full database backups may contain comment delivery payloads captured before the live-table 30-day cleanup. Keep local backups for at most 30 days; the backup scripts do not expire them automatically. After verifying which dated backup has expired, remove that single exact backup directory manually. Do not use a bulk-delete command.

Before backup, stop writes to the application and let queued work finish. The script does not stop services. You must also stop the application services, Canal, `distributed-id-generator`, `zookeeper`, and `minio` yourself. Leave MySQL and Cassandra running so the script can take their logical dump and snapshot:

```powershell
$compose = @('--env-file','infra/.runtime/compose.env','-f','infra/compose.yaml','-f','infra/compose.app.yaml')
try {
  docker compose @compose stop auth gateway oss user kv distributed-id-generator note user-relation count data-align search comment frontend canal nacos xxl-job-admin zookeeper minio
  if ($LASTEXITCODE -ne 0) { throw 'Could not stop all application and storage writers.' }
  & .\infra\Backup-Local.ps1
} finally {
  docker compose @compose start auth gateway oss user kv distributed-id-generator note user-relation count data-align search comment frontend canal nacos xxl-job-admin zookeeper minio
}
```

Review the manifest and verify its SHA-256 checksums before relying on a backup:

```powershell
& .\infra\Test-LocalBackup.ps1 -BackupPath 'F:\docker\backups\xiaohashu-<timestamp>'
```

Restore steps use an isolated checkout, data root, Compose project name, external volume names, network, and alternate loopback ports; they never point at the active deployment. See [RESTORE.md](RESTORE.md) for the procedure and the per-store limitations.
