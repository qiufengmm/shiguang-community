# 拾光社区（Shiguang Community）

拾光社区是一个围绕日常生活、兴趣与经验分享的图文社区，支持内容发布与搜索、点赞收藏、关注互动和评论回复。

后端采用 Spring Cloud Alibaba 微服务架构，仓库按业务域拆分为认证、用户、笔记、用户关系、评论、计数、搜索、KV、对象存储、分布式 ID 与数据对齐等模块，并以 Gateway 作为统一入口。

> **项目来源**：本项目跟随「犬小哈」Spring Cloud 实战专栏逐节实现，并在此教程主线之上持续做可靠性改造与本地部署完善，属于个人学习项目，不是商业化产品，也非完全原创。教程源码与讲解的版权归原作者所有；前端保留的《犬小哈实战专栏》署名用于说明来源，请勿移除。

## 已实现的功能

以下能力均在本仓库中实现，未包含尚未开发的规划功能。

- **账号与鉴权**：手机号验证码登录与自动注册、密码登录与修改密码、Sa-Token 会话校验；网关统一鉴权，并通过拦截器向内部服务透传用户 ID。
- **用户资料**：昵称、拾光号、性别、生日、个人简介、头像与背景图；用户主页、关注/粉丝列表与用户卡片。
- **笔记内容**：发布、编辑、删除、置顶、可见性调整；图文与视频；正文存放于 KV 服务（Cassandra），元数据在 MySQL；详情使用 Caffeine + Redis 两级缓存。
- **发现与搜索**：频道/话题信息流、笔记详情与评论、Elasticsearch 全文检索（含高亮与筛选），搜索索引由全量/增量同步维护。
- **互动**：点赞、收藏、关注/取关、评论与子评论回复；评论点赞与删除。
- **计数**：笔记点赞/收藏、用户关注/粉丝、作者获赞/获收藏/发布数等计数，支持 MQ 消费幂等、缓存失效与 XXL-JOB 定期对账。
- **基础设施能力**：对象存储（MinIO）、分布式 ID（美团 Leaf 号段与 Snowflake）、数据对齐、Sentinel 限流降级。

## 技术栈

Java 17 · Spring Boot 3.0 · Spring Cloud 2022 · Spring Cloud Alibaba · Nacos · Gateway · OpenFeign · MyBatis · MySQL · Redis · RocketMQ · Sa-Token · MinIO · Cassandra · Elasticsearch · XXL-JOB · Sentinel · Docker Compose

前端为 Vue 3 + Vite + Tailwind CSS，代码位于本仓库的 [`../frontend`](../frontend/README.md)。统一构建与目录说明见[仓库首页](../README.md)。

## 模块与端口

| 模块 | 端口 |
| --- | --- |
| `xiaohashu-gateway` | 8000 |
| `xiaohashu-auth` | 8080 |
| `xiaohashu-oss` | 8081 |
| `xiaohashu-user` | 8082 |
| `xiaohashu-kv` | 8084 |
| `xiaohashu-distributed-id-generator` | 8085 |
| `xiaohashu-note` | 8086 |
| `xiaohashu-user-relation` | 8087 |
| `xiaohashu-count` | 8090 |
| `xiaohashu-comment` | 8091 |
| `xiaohashu-search` | 8092 |
| `xiaohashu-data-align` | 8093 |

## 本地运行

完整应用（前端 + 12 个业务服务 + 基础设施）由 `infra/compose.yaml` 与 `infra/compose.app.yaml` 编排，启动方式、数据库迁移顺序和验收步骤见 [`infra/README.md`](infra/README.md)。

```powershell
# 从仓库根目录执行
Set-Location backend
& .\infra\Start-Local.ps1 -SkipBuild
```

前端在本机 8088 端口访问，Gateway 在 8000 端口提供统一 API 入口。

编译：显式使用 JDK 17（`D:\java\jdk-17.0.18+8`），Maven 不要加 `-o`。

```bash
JAVA_HOME=/d/java/jdk-17.0.18+8 mvn -DskipTests package
```

## 关于仓库内的 xiaohashu 标识

对外展示名已统一为「拾光社区 / Shiguang Community」，但以下技术标识仍沿用 `xiaohashu`，本次未做重命名：

Java 包名（`com.quanxiaoha.xiaohashu.*`）、Maven 坐标、模块目录、Nacos 服务名与命名空间、Feign 服务名、MySQL 库名与表名、Redis key、RocketMQ Topic 与消费者组、Elasticsearch 索引、Cassandra keyspace、Docker Compose 项目名与容器名。

原因是这些标识互相依赖，且与正在运行的注册中心、消息订阅关系、数据库和持久化卷一一对应；直接替换会破坏服务发现、消息投递和已有数据。数据库字段 `xiaohashu_id` 同样保留，仅在界面上显示为「拾光号」。

## 文档

- [`infra/README.md`](infra/README.md)：完整应用部署、迁移与运维说明
- [`docs/deployment/`](docs/deployment/)：本地验收记录与教程交付状态（历史记录，保留当时表述）
- [`docs/performance/`](docs/performance/)：本地性能实测报告与样本
- [`docs/tutorial/`](docs/tutorial/)：按教程章节整理的实现笔记（历史记录）
- [`docs/resume/`](docs/resume/)：项目简历材料

## 说明

- 仓库中的性能数字均来自本地实测且可追溯，不包含线上容量、用户规模或商业化数据。
- 教程原文位于仓库外的 `教程` 目录，本项目不修改其内容。
