# 贡献指南

## 提交前检查

```bash
cd smart-kitchen && mvn test
cd smart-kitchen-ai && .venv/bin/python -m pytest tests/test_dish_tools_auth.py -q
python3 tests/test_miniprogram.py
```

## 分支与提交

- 功能分支使用 `feat/`，修复使用 `fix/`，文档使用 `docs/` 前缀。
- 提交信息建议使用 `feat:`、`fix:`、`docs:`、`test:`、`chore:`。
- 不提交 `.env`、构建产物、日志、密钥、Token、用户数据或 Milvus 运行缓存。

## 合并请求

请说明改动目标、影响模块、测试结果、数据库迁移脚本，以及配置或接口兼容性变化。可复制 [合并请求模板](docs/PULL_REQUEST_TEMPLATE.md)。
