# 拾光社区公开单仓库整理与上传计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将已检查的前后端代码整理为一个可构建的仓库，并上传到用户指定的公开 GitHub 仓库。

**Architecture:** 从原有两个仓库的指定提交导出受版本控制的文件，分别放入 `backend/`、`frontend/`；原仓库及其历史保留。新仓库记录来源提交，调整前端构建路径，维护统一入口文档与忽略规则。

**Tech Stack:** Git、PowerShell 7、Python 标准库、Java 17、Maven、Node.js、npm、Vue 3、Docker Compose。

**Spec:** 用户已确认前后端使用一个公开仓库，地址为 `https://github.com/qiufengmm/shiguang-community`；沿用此前说明的 `backend/`、`frontend/` 目录结构。

## 全局约束

- 禁止批量删除文件或目录；原两个仓库不移动、不覆盖。
- 项目根 `.evn` 不读、不写、不提交；运行凭据、运行时状态及数据库文件不导出。
- 不导出 IDE 配置、构建产物、依赖目录或教程原文目录。
- JDK 17 显式使用 `D:\java\jdk-17.0.18+8`；Maven 不加 `-o`。
- 用户已授权向该公开仓库上传；推送仅使用普通 fast-forward，不覆盖远端内容，不强推。
- 仅暂存逐个核对的明确文件路径，不用 `git add -A`。

## 任务 1：导出源代码快照

**Files:** 新增 `backend/`、`frontend/`、`docs/SOURCE-SNAPSHOTS.md`。

- [x] 核对原仓库状态及来源提交，确认目标公开仓库没有现存分支。
- [x] 用 `git archive <commit>` 导出明确的受控文件，排除 IDE 配置并验证路径不逃逸目标目录。
- [x] 记录来源提交和文件数量，确认没有嵌套 `.git` 或 gitlink。

## 任务 2：适配单仓库布局

**Files:** 新增根 `README.md`、`.gitignore`；修改 `backend/infra/Build-Frontend.ps1`、`backend/README.md`、`backend/infra/README.md`、`frontend/README.md`。

- [x] 前端默认源码路径改为后端同级的 `../frontend`。
- [x] 根 README 提供模块说明、构建命令、部署前置条件和资料链接，保留项目来源说明。
- [x] 文档明确 Docker 的数据目录、配置和数据库迁移仍需准备，不声称新机器克隆后即可一键运行。
- [x] 根忽略规则覆盖环境文件、运行目录、日志、依赖及构建产物。

## 任务 3：核验并上传

**Files:** 新增 `docs/PUBLICATION-CHECK.md`；暂存全部经核对的受控文件。

- [x] 在新目录运行后端 JDK 17 编译和前端依赖安装、生产构建。
- [x] 检查前端配置、PowerShell 解析及新前端路径；扫描受控文件的明显密钥/令牌/私钥与大文件。
- [x] 核对暂存文件清单、路径、来源差异及 `git diff --cached --check`，记录未运行的验收项目。
- [x] 创建初始提交，配置用户指定的 origin；先 dry-run，再普通推送 main。
- [x] 比较远端 main 与本地 HEAD，核对公开可见性与 README、backend、frontend；报告结果和后续使用目录。

## 执行结果

- 源码导入提交：`27bfc35`；目录适配与检查记录提交：`93060c4`。
- 已通过 GitHub 匿名 API 确认仓库公开、默认分支 `main`，初次推送后的远端 SHA 与本地一致。
- 远端树共 1015 个文件，必需的后端、前端、README、发布记录和第三方许可证均存在；未发现受排除的运行或构建路径。
- 本机统一仓库目录为 `F:\code\xiaohashu\shiguang-community`；原两个仓库的 HEAD 与未提交状态保持原样。
- Docker 引擎不可用，因此本次验证止于打包、前端安装/构建、配置与上传核验；未声称完成运行时联调。
