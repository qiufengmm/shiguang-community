# 本地备份的隔离恢复

恢复前先验证清单。备份含部署密钥及用户数据，应放在受保护的位置。恢复必须用单独的项目检出、Compose 项目名、数据根目录、外部卷名和端口；不要把恢复操作指向正在使用的 `infra/.runtime`、`F:\docker` 或当前 Docker 卷。

`Backup-Local.ps1` 提供每个存储各自的恢复点，不是跨 MySQL、Cassandra、MinIO 和 ZooKeeper 的原子快照。Cassandra 的命名快照带 1 天 TTL，会由 Cassandra 自动过期；过期前硬链接可能使旧 SSTable 暂时不能回收，期间新写入仍会增加磁盘占用。MySQL 系统库 `mysql` 不在逻辑导出中，因此 Canal 复制账号需要在恢复后重新创建。Redis 和 Elasticsearch 不包含在备份中，RocketMQ 消息也不包含在备份中。恢复后应以 MySQL 为准重建搜索索引并检查计数对账。

## 1. 准备独立环境

1. 在一个新的项目目录检出/复制相同应用版本。不要复用当前项目的 `infra/.runtime`。
2. 解压备份，把 `host-config` 作为静态配置种子；它包含 `redis/modules`、`nacos/conf`、`cassandra5/config`、`zookeeper/conf`、RocketMQ 启动脚本、ES/Kibana 配置。
3. 创建一个全新的数据目录，例如 `F:\docker\xiaohashu-restore-20261018`，并把备份中的 `minio/data` 复制到该目录下的 `minio/data`。为其余 Compose 绑定源创建空目录：

   ```powershell
   $dataRoot = 'F:\docker\xiaohashu-restore-20261018'
   $backup = 'F:\docker\backups\xiaohashu-<timestamp>'
   New-Item -ItemType Directory -Path $dataRoot | Out-Null
   Copy-Item -Path (Join-Path $backup 'host-config/*') -Destination $dataRoot -Recurse
   New-Item -ItemType Directory -Path (Join-Path $dataRoot 'minio') -Force | Out-Null
   Copy-Item -LiteralPath (Join-Path $backup 'minio/data') -Destination (Join-Path $dataRoot 'minio') -Recurse
   foreach ($relativePath in @(
     'redis/data','nacos/data','nacos/logs','cassandra5/data','zookeeper/data',
     'rocketmq/namesrv/logs','rocketmq/broker/logs','rocketmq/broker/store',
     'elasticsearch/data','elasticsearch/logs','frp/frps','frp/frpc'
   )) { New-Item -ItemType Directory -Path (Join-Path $dataRoot $relativePath) -Force | Out-Null }
   ```

4. 将备份 `deployment/runtime` 的内容复制到这个独立检出的 `infra/.runtime`。在该副本的 `compose.env` 中只修改 `XIAOHASHU_DATA_ROOT`、四个外部卷名和全部宿主机端口值；保留其余凭据。不要把凭据打印到终端或粘贴到聊天。`data-root.txt` 也改成新的数据根目录。
5. 选择从未用过的卷名，例如 `xiaohashu-restore-mysql`、`xiaohashu-restore-zk-datalog`、`xiaohashu-restore-zk-logs`、`xiaohashu-restore-xxl-logs`，并手动创建这些 external volumes。先确认端口空闲；例如可为 MySQL/Redis/Nacos/MinIO/Cassandra/ZooKeeper/RocketMQ/ES/Kibana/XXL-JOB/Sentinel/Gateway/前端分别选择 `13306/16379/18848/19000+19090/19042/12181/19876+20912/19200/15601/18080/18060/18000/18088`。恢复项目不启用 `frp-local-test` 或 `tools` profile。
6. 使用此副本单独的 Compose 项目名，例如 `xiaohashu-restore-20261018`。验证渲染出的 bind 源全都在新数据根目录下，并且所有卷名与当前部署不同。此项目名会建立独立 Compose 网络。若需要在同一台电脑重新构建镜像，先给恢复项目的镜像配置使用独立标签，避免覆盖当前项目的 `xiaohashu/*:local` 标签。

这些准备步骤故意不提供一条“全栈一键恢复”命令：端口、卷名、宿主机目录和本机运行配置都需要在恢复前逐项核对。

## 2. 恢复 MySQL

先仅启动隔离项目的 MySQL，并等待健康状态。复制逻辑导出文件到该容器，然后导入：

```powershell
docker cp (Join-Path $backup 'mysql/databases.sql') '<restore-mysql-container>:/tmp/xiaohashu-restore.sql'
docker exec <restore-mysql-container> sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot < /tmp/xiaohashu-restore.sql'
```

MySQL 导出包含 `xiaohashu`、`nacos_config`、`xxl_job` 和 `leaf`。它不含 `mysql` 系统库，也不会恢复 MySQL 用户/授权。按 `infra/.runtime/canal-example/instance.properties` 中的复制账号配置重新创建账号并授予 Canal 所需的读取与 binlog 复制权限。先确认容器内 Nacos、Canal 和 XXL-JOB 使用的密码与隔离运行配置一致，再启动这些服务。

## 3. 恢复 Cassandra

使用备份的 `cassandra/keyspace.cql` 在全新的 Cassandra 5.0.4 实例创建 keyspace 和表。`snapshot.tar.gz` 保存 SSTable 快照，`table-map.txt` 列出表名和快照目录中的表 UUID。

在宿主机解压到临时目录，按照 `table-map.txt` 把各 UUID 下 `snapshots/<snapshot-tag>` 中的 SSTable 文件复制到以真实表名命名的目录结构 `load/xiaohashu/<table-name>/`。跳过没有 `*-Data.db` 文件的空表。随后使用同版本 Cassandra 工具容器，通过隔离 Compose 网络对每个非空表运行 `sstableloader --nodes cassandra /restore/xiaohashu/<table-name>`；目标 Cassandra 必须已创建 schema 并可连接。工具容器只运行 `sstableloader`，不要在它里面同时启动 Cassandra。不要直接把不同表 UUID 的文件复制进刚创建的 Cassandra 数据目录。

这套 SSTable 导入流程基于 [Apache Cassandra 的快照恢复说明](https://cassandra.apache.org/doc/latest/cassandra/managing/tools/sstable/sstableloader.html)。本任务没有执行实际恢复演练；应先在可丢弃的隔离数据根目录验证每个表能被加载并查到记录，再把备份作为正式恢复依据。

## 4. 恢复 ZooKeeper 和 MinIO

- MinIO 的 `data` 目录在 MinIO 停止时按原始文件树复制，所以应在 Compose 启动 MinIO 前放入新数据根目录。保持 MinIO 镜像版本一致；启动后确认 `xiaohashu-local` 桶与对象可读。
- ZooKeeper 有一个 bind 目录和两个 external volumes。创建隔离 Compose 卷后用 `docker compose create zookeeper` 生成尚未启动的容器，把备份的 `zookeeper/data`、`zookeeper/datalog`、`zookeeper/logs` **各自目录内容**复制到目标容器的 `/data`、`/datalog`、`/logs`，再启动 ZooKeeper。只对隔离恢复容器执行这些操作。
- Leaf Snowflake 状态存在 ZooKeeper 中，Leaf segment 数据在 MySQL `leaf` 库中。恢复两边后检查 ID 生成服务；不要将隔离测试环境生成的 ID 用回当前开发环境。

## 5. 启动其余服务并验收

确认 MySQL、Cassandra、MinIO 和 ZooKeeper 数据已导入后，再启动隔离项目中的其余 Compose 服务。Redis 和 Elasticsearch 会以空数据启动；从 MySQL 重建缓存和 ES 索引。RocketMQ 消息没有备份，启动前确认没有把积压消息误认为已经恢复。

备份同时包含 `.runtime/canal-example` 的消费位点，但各存储快照时间不同。若要从 MySQL 重建 ES，应先为隔离环境重新校准 Canal 位点，再执行完整索引构建；不要把旧位点与新索引重建交错启动。用随机测试数据验证登录、笔记正文查询、MinIO 媒体读取和搜索后清理测试记录。跨设备的 LAN 访问还需要第二台设备实测。

FRP 公网可达性仍需用户自有公网服务器；本地 FRP 隧道不代表公网恢复验收。
