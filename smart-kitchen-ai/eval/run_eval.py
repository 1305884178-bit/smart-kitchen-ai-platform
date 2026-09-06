#!/usr/bin/env python3
"""
RAG 与语义缓存评测脚本（可跑，非文档）。

- 语义缓存：近义问句对算 embedding 余弦相似度，对照 SEMANTIC_CACHE_THRESHOLD（0.92）
  输出命中率 / 误命中率；阈值只读不改。
- RAG：以 scripts/knowledge_corpus.py 的菜品知识卡为 golden，写入独立临时 Milvus 库
  （不污染 data/milvus.db），统计 Recall@3 与低相关 query 的空结果率；
  RAG_SCORE_THRESHOLD 可在 0.55~0.70 微调（改 config/.env 后重跑本脚本对比）。

用法：
    cd smart-kitchen-ai
    .venv/bin/python eval/run_eval.py            # 全部评测
    .venv/bin/python eval/run_eval.py --cache    # 只评测语义缓存
    .venv/bin/python eval/run_eval.py --rag      # 只评测 RAG

需要 .env 中的 EMBEDDING_API_KEY / EMBEDDING_BASE_URL / EMBEDDING_MODEL。
"""
import argparse
import json
import math
import os
import sys
import tempfile

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
EVAL_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, BASE_DIR)
sys.path.insert(0, os.path.join(BASE_DIR, "scripts"))


def _load_cases(name):
    with open(os.path.join(EVAL_DIR, name), encoding="utf-8") as f:
        return json.load(f)["cases"]


def _cosine(a, b):
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(x * x for x in b))
    return dot / (na * nb) if na and nb else 0.0


def eval_semantic_cache():
    from app.config import settings
    from app.services.rag_service import embeddings

    threshold = settings.semantic_cache_threshold
    cases = _load_cases("semantic_cache_cases.json")

    # 批量 embedding（一次请求多条）
    texts = sorted({c["query"] for c in cases} | {c["cached_question"] for c in cases})
    vectors = dict(zip(texts, embeddings.embed_documents(texts)))

    tp = fp = fn = tn = 0
    print(f"\n===== 语义缓存评测（阈值 {threshold}，共 {len(cases)} 条）=====")
    print(f"{'id':<10} {'expect':<6} {'score':<8} {'判定':<4} 说明")
    for c in cases:
        sim = _cosine(vectors[c["query"]], vectors[c["cached_question"]])
        hit = sim >= threshold
        expect_hit = c["expect"] == "hit"
        if hit and expect_hit:
            tp += 1
        elif hit and not expect_hit:
            fp += 1
        elif not hit and expect_hit:
            fn += 1
        else:
            tn += 1
        verdict = "✓" if hit == expect_hit else "✗"
        print(f"{c['id']:<10} {c['expect']:<6} {sim:<8.4f} {verdict:<4} {c.get('note', '')}")

    hit_rate = tp / (tp + fn) if (tp + fn) else 0.0
    false_hit_rate = fp / (fp + tn) if (fp + tn) else 0.0
    print(f"\n命中率(应命中且命中): {hit_rate:.2%}  ({tp}/{tp + fn})")
    print(f"误命中率(不应命中却命中): {false_hit_rate:.2%}  ({fp}/{fp + tn})")
    return {"hit_rate": hit_rate, "false_hit_rate": false_hit_rate}


def _build_golden_kb():
    """把菜品知识卡写入独立临时 Milvus 库，返回临时目录路径。"""
    tmp_dir = tempfile.mkdtemp(prefix="rag_eval_")
    os.environ["MILVUS_DB_PATH"] = os.path.join(tmp_dir, "eval.db")
    return tmp_dir


def eval_rag():
    # 必须先指定独立库路径再 import 业务模块（MILVUS_DB_PATH 在 import 时读取）
    _build_golden_kb()

    from datetime import datetime
    from app.config import settings
    from app.services.rag_service import process_and_store_document, search_knowledge
    from knowledge_corpus import DISHES, doc_text

    print(f"\n===== RAG 评测（RAG_SCORE_THRESHOLD={settings.rag_score_threshold}）=====")
    print("写入 golden 知识库（临时库，不污染 data/milvus.db）...")
    for name, d in DISHES.items():
        document_id, chunk_count = process_and_store_document(
            content=doc_text(name, d),
            metadata={"title": name},
            version="v2.0",
            status="active",
            effective_from=datetime(2026, 8, 16),
        )
        print(f"  - {name}: {chunk_count} chunks (document_id={document_id[:8]}...)")

    cases = _load_cases("rag_cases.json")
    recall_hits = recall_total = 0
    empty_ok = empty_total = 0
    print(f"\n{'id':<9} {'expect':<6} {'结果数':<5} {'top1距离':<8} {'判定':<4} query")
    for c in cases:
        results = search_knowledge(c["query"], top_k=3)
        top_distance = results[0]["distance"] if results else 0.0
        if c["expect_empty"]:
            empty_total += 1
            ok = len(results) == 0
            empty_ok += 1 if ok else 0
        else:
            recall_total += 1
            corpus = "\n".join((r.get("title") or "") + " " + (r.get("text") or "") for r in results)
            ok = any(g in corpus for g in c["golden"])
            recall_hits += 1 if ok else 0
        verdict = "✓" if ok else "✗"
        expect = "empty" if c["expect_empty"] else "recall"
        print(f"{c['id']:<9} {expect:<6} {len(results):<5} {top_distance:<8.4f} {verdict:<4} {c['query']}")

    recall_at_3 = recall_hits / recall_total if recall_total else 0.0
    empty_rate = empty_ok / empty_total if empty_total else 0.0
    print(f"\nRecall@3（阈值过滤后）: {recall_at_3:.2%}  ({recall_hits}/{recall_total})")
    print(f"低相关空结果率: {empty_rate:.2%}  ({empty_ok}/{empty_total})")
    return {"recall_at_3": recall_at_3, "empty_rate": empty_rate}


def main():
    parser = argparse.ArgumentParser(description="RAG / 语义缓存评测")
    parser.add_argument("--cache", action="store_true", help="只评测语义缓存")
    parser.add_argument("--rag", action="store_true", help="只评测 RAG")
    args = parser.parse_args()
    run_all = not (args.cache or args.rag)

    summary = {}
    if run_all or args.cache:
        summary["semantic_cache"] = eval_semantic_cache()
    if run_all or args.rag:
        summary["rag"] = eval_rag()

    print("\n===== 汇总 =====")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
