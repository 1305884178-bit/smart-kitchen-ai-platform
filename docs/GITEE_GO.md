# Gitee Go 持续校验

在 Gitee 仓库“流水线”页面创建推送触发的持续集成流水线，至少覆盖 `dev3.9` 和主分支。

## 推荐阶段

1. Java 测试：`cd smart-kitchen && mvn test`
2. 管理端构建：`cd admin-web && npm install && npm run build`
3. 小程序静态检查：`python3 tests/test_miniprogram.py`
4. AI 基础校验：安装依赖后执行 `python -m compileall smart-kitchen-ai/app`

AI 完整测试依赖 MySQL、Redis、Milvus 和模型配置，建议在具备这些依赖的自托管 Runner 或测试环境中执行。

密钥、数据库密码和模型 API Key 应通过 Gitee Go 凭证/环境变量配置，不写入流水线脚本。Gitee Go 支持可视化和 YAML 编排，请在流水线页面按上述命令创建对应任务，并以实际 Runner 镜像配置 JDK、Node 与 Python 版本。
