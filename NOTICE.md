# 项目与第三方来源说明

本仓库是基于「犬小哈」实战专栏学习并持续改造的个人项目。教程相关源码、讲解、署名及原作者作品的版权归相应权利人所有；保留来源说明不代表本仓库对这些作品另行授予许可。

## 美团 Leaf

分布式 ID 模块接入并适配了 [Meituan-Dianping/Leaf](https://github.com/Meituan-Dianping/Leaf) 的号段与 Snowflake 实现。相关源码位于：

`backend/xiaohashu-distributed-id-generator/xiaohashu-distributed-id-generator-biz/src/main/java/com/quanxiaoha/xiaohashu/distributed/id/generator/biz/`

本项目对包路径、Spring Boot 集成及本地运行配置做了适配。上游使用 Apache License 2.0，原文保存在 [third_party/Leaf-LICENSE](third_party/Leaf-LICENSE)，来源为上游仓库的 `LICENSE` 文件。

其他 Java/npm 依赖按各自上游许可证使用。本仓库没有通过新增顶层许可证将全部教程相关作品重新授权为某种开源许可。
