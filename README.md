# 拾光社区 · Shiguang Community

![拾光社区项目封面](frontend/public/og-cover.png)

一个围绕日常生活、兴趣与经验分享的图文社区个人学习项目。支持短信登录、笔记发布与搜索、点赞收藏、关注互动及评论回复；本仓库包含 Java 微服务后端、Vue 3 前端、数据库迁移和 Docker Compose 部署脚本。

> 本项目跟随「犬小哈」实战专栏实现，并在教程基础上持续完善可靠性与本地部署。教程源码、讲解及原作者作品的版权归相应权利人所有；前端保留专栏来源署名。项目不是商业化产品，不声称完全原创。第三方来源说明见 [NOTICE.md](NOTICE.md)。

## 仓库结构

```text
shiguang-community/
├── backend/       Spring Cloud 微服务、SQL/CQL、部署脚本和历史验证记录
├── frontend/      Vue 3 Web 前端
├── docs/          单仓库整理与发布记录
└── third_party/   已引入第三方源码的许可证
```

原 Java 包名、服务注册名、数据库名及 Docker 项目名沿用 `xiaohashu`，以兼容已有调用与持久化数据。对外展示名统一为「拾光社区」。

## 主要功能

- **账号与用户：** 手机验证码登录、自动注册、密码登录、用户资料与头像编辑。
- **笔记：** 图文/视频发布、编辑、删除、置顶、可见性控制与详情两级缓存。
- **互动：** 关注/取关、关注与粉丝列表、点赞、收藏、评论、回复与评论点赞。
- **搜索：** Elasticsearch 笔记/用户检索、高亮与筛选、索引同步。
- **基础能力：** Gateway 鉴权、Feign 用户身份透传、MinIO 对象存储、Leaf 分布式 ID、RocketMQ 异步消息与 XXL-JOB 数据对账。

## 技术栈

**后端：** Java 17、Spring Boot 3、Spring Cloud Alibaba、Nacos、Gateway、OpenFeign、MyBatis、MySQL、Redis、RocketMQ、Sa-Token、MinIO、Cassandra、Elasticsearch、XXL-JOB、Sentinel。

**前端：** Vue 3、Vite、Vue Router、Pinia、Tailwind CSS、Axios。

## 构建

需要 Java 17、Maven、Node.js/npm；完整本地部署另需 PowerShell 7 与 Docker Desktop/Compose。

```powershell
git clone https://github.com/qiufengmm/shiguang-community.git
Set-Location shiguang-community

# 按本机实际安装位置设置，必须使用 JDK 17。
$env:JAVA_HOME = 'D:\java\jdk-17.0.18+8'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
mvn -f backend/pom.xml -DskipTests package

Push-Location frontend
npm ci
npm run build
Pop-Location
```

单独开发前端：在 `frontend/` 执行 `npm run dev`，默认将 `/api` 转发至本机 Gateway `http://127.0.0.1:8000`。网关地址不同可将 `frontend/.env.example` 复制为 `.env.local` 并设置 `VITE_API_PROXY_TARGET`。

### Docker 部署前置条件

部署脚本位于 [`backend/infra/`](backend/infra/README.md)，从后端目录执行；前端构建脚本已适配同级 `../frontend`。

已有本机环境可按部署说明复用现有数据目录、卷和运行配置后执行：

```powershell
Set-Location backend
& .\infra\Start-Local.ps1
```

**新机器需要另外准备基础设施配置、数据库结构/迁移以及运行凭据。** 当前 `ReuseExisting` 模式面向已有环境；`Fresh` 模式还要求可信的静态配置种子目录。Git 仓库不包含原电脑的数据库、MinIO 对象、索引数据、云密钥或 `infra/.runtime/`。仅克隆源码不会获得已有演示数据与可直接复用的运行环境。

完整部署完成后的入口：Web `http://localhost:8088`，Gateway `http://localhost:8000`。数据恢复前先阅读 [备份与恢复说明](backend/infra/RESTORE.md)，不要删除现有数据卷。

## 文档与验证

- [后端模块说明](backend/README.md) / [前端开发说明](frontend/README.md)
- [Docker 部署说明](backend/infra/README.md) / [数据备份与恢复](backend/infra/RESTORE.md)
- [本地性能实测](backend/docs/performance/)：注明测试条件和局限，不能当作线上容量数据。
- [教程交付与历史验证记录](backend/docs/deployment/)：保留当时的目录、环境及验证结果。
- [源码快照来源](docs/SOURCE-SNAPSHOTS.md) / [本次发布检查](docs/PUBLICATION-CHECK.md)

FRP 公网验收等待服务器；评论与 Canal 的隔离故障恢复验收因本机内存限制暂缓。历史文档中的本机路径、部署状态与待办以对应日期为准，不表示新机器已完成相同验收。
