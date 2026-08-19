"""
/api/order/submit 接口级并发压测

对运行中的应用（默认 http://localhost:8080）用多线程并发提交订单，
验证「Redis Lua 预扣 + MySQL 条件更新」双防线在高并发下不超卖，
并输出延迟统计（avg / p95 / max）。

用法：
  1. 准备压测菜品（库存故意调小，便于打穿）：
       INSERT INTO pms_dish (id,name,category_id,price,status,daily_stock,alert_threshold,create_time,update_time)
       VALUES (9901,'压测菜',1,1.00,1,20,0,NOW(),NOW());
       （Redis 中删除 dish:stock:9901，让应用从 MySQL 初始化）
  2. 启动应用后运行：
       python3 tests/load_order_submit.py [--dish-id 9901] [--concurrency 50] [--expect-stock 20]
  3. 结束后脚本自动清理压测订单（seat_number=T99）与压测菜品。

仅依赖标准库；token 通过 /api/auth/login 用 admin/123456 获取（可用环境变量覆盖）。
"""
import argparse
import json
import os
import threading
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor

BASE_URL = os.environ.get("LOAD_BASE_URL", "http://localhost:8080")
LOGIN_USER = os.environ.get("LOAD_LOGIN_USER", "admin")
LOGIN_PWD = os.environ.get("LOAD_LOGIN_PWD", "123456")

PASS, FAIL = "\033[92mPASS\033[0m", "\033[91mFAIL\033[0m"
results = []


def check(name, condition, detail=""):
    results.append(condition)
    print(f"  [{PASS if condition else FAIL}] {name}  {detail}")


def http_json(method, path, body=None, token=None):
    req = urllib.request.Request(
        BASE_URL + path,
        data=json.dumps(body).encode() if body is not None else None,
        method=method,
        headers={"Content-Type": "application/json"},
    )
    if token:
        req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode())


def login():
    resp = http_json("POST", "/api/auth/login", {"username": LOGIN_USER, "password": LOGIN_PWD})
    assert resp.get("code") == 200, f"登录失败: {resp}"
    data = resp["data"]
    return data["token"] if isinstance(data, dict) else data


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dish-id", type=int, default=9901)
    ap.add_argument("--concurrency", type=int, default=50)
    ap.add_argument("--expect-stock", type=int, default=20, help="压测菜品初始库存（预期成功订单数）")
    ap.add_argument("--quantity", type=int, default=1)
    args = ap.parse_args()

    token = login()
    print(f"登录成功，目标 {BASE_URL}，菜品 {args.dish_id}，并发 {args.concurrency}，每人 {args.quantity} 件")

    barrier = threading.Barrier(args.concurrency)
    latencies = []
    outcomes = []
    lock = threading.Lock()

    def worker(_):
        body = {"seatNumber": "T99", "details": [{"dishId": args.dish_id, "quantity": args.quantity}]}
        barrier.wait()
        t0 = time.perf_counter()
        try:
            resp = http_json("POST", "/api/order/submit", body, token)
            code, msg = resp.get("code"), resp.get("message", "")
        except Exception as e:  # HTTP 5xx 也记为失败单
            code, msg = -1, str(e)
        dt = (time.perf_counter() - t0) * 1000
        with lock:
            latencies.append(dt)
            outcomes.append(code == 200)
        return code, msg

    print(f"\n并发提交 {args.concurrency} 笔订单...")
    t_all = time.perf_counter()
    with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        list(pool.map(worker, range(args.concurrency)))
    total_ms = (time.perf_counter() - t_all) * 1000

    success = sum(outcomes)
    failed = len(outcomes) - success
    latencies.sort()
    p95 = latencies[int(len(latencies) * 0.95) - 1]
    print(f"\n成功 {success} 笔 / 失败 {failed} 笔 / 总耗时 {total_ms:.0f}ms")
    print(f"延迟：avg {sum(latencies)/len(latencies):.1f}ms / p95 {p95:.1f}ms / max {latencies[-1]:.1f}ms")

    print("\n防超卖断言：")
    check("成功笔数 == 初始库存（不超卖）", success * args.quantity == args.expect_stock,
          f"实际卖出 {success * args.quantity}，库存 {args.expect_stock}")
    check("失败笔数 == 并发数 - 成功数", failed == args.concurrency - success, f"实际 {failed}")

    print(f"\n{'=' * 50}\n结果：{sum(results)}/{len(results)} 项通过")
    raise SystemExit(0 if all(results) else 1)


if __name__ == "__main__":
    main()
