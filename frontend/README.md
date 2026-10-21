# 拾光社区 · Web 前端（Shiguang Community）

拾光社区是一个围绕日常生活、兴趣与经验分享的图文社区，支持内容发布与搜索、点赞收藏、关注互动和评论回复。

本仓库是该社区的 Vue 3 前端，通过 `/api` 代理访问 Gateway，与后端微服务联调。

## 技术栈

Vue 3 · Vite 6 · Vue Router · Pinia · Tailwind CSS 4 · Axios · GSAP

## 页面与组件

- 发现页：频道与话题信息流、笔记卡片与详情弹窗
- 搜索：关键词搜索、结果列表
- 个人主页：资料展示与编辑（昵称、拾光号、性别、生日、简介、头像与背景图）、笔记列表
- 关注与粉丝列表、通知页
- 评论：发布、回复、点赞、删除

## 开发与构建

```sh
npm install
npm run dev     # 本地开发，/api 代理到 Gateway
npm run build   # 产出 dist/
```

开发代理默认连接本机 Gateway（`http://127.0.0.1:8000`）。网关位于其他地址时，将 `.env.example` 复制为 `.env.local` 并修改 `VITE_API_PROXY_TARGET`，然后重启开发服务。`.env.local` 不纳入 Git；不要在 `VITE_*` 变量中放置密钥。

构建产物由 [`../backend/infra/frontend/Dockerfile`](../backend/infra/frontend/Dockerfile) 打包进 Nginx 镜像，容器端口 80，映射到宿主机 8088。完整应用启动方式见[后端部署说明](../backend/infra/README.md)，统一目录与构建入口见[仓库首页](../README.md)。

## 品牌

- 中文名：拾光社区；英文名：Shiguang Community
- 站点标题、meta 描述、favicon、分享默认图与页面内文案统一使用「拾光社区」
- 用户 ID 对外显示为「拾光号」，接口字段仍为 `xiaohashuId`
- 界面保留《犬小哈实战专栏》署名，用于说明教程来源
