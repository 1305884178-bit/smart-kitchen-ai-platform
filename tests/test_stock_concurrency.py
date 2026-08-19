"""
库存 Lua 脚本并发测试

直接加载项目真实的 Lua 脚本（deduct_stock.lua / return_stock.lua），
用多线程并发压测验证防超卖逻辑。全程使用 test:stock:* 测试键，
结束后自动清理，不影响业务数据。

运行：python3 tests/test_stock_concurrency.py
"""
import threading
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import redis

LUA_DIR = Path(__file__).resolve().parent.parent / "smart-kitchen" / "src" / "main" / "resources" / "scripts"
DEDUCT_LUA = (LUA_DIR / "deduct_stock.lua").read_text()
RETURN_LUA = (LUA_DIR / "return_stock.lua").read_text()

r = redis.Redis(host="localhost", port=6379, password="123456", db=0, decode_responses=True)

PASS, FAIL = "\033[92mPASS\033[0m", "\033[91mFAIL\033[0m"
results = []


def check(name, condition, detail=""):
    results.append(condition)
    print(f"  [{PASS if condition else FAIL}] {name}  {detail}")


def concurrent_run(worker, n):
    """n 个线程通过 Barrier 同时起跑，最大化并发冲突"""
    barrier = threading.Barrier(n)

    def wrapped():
        barrier.wait()
        return worker()

    with ThreadPoolExecutor(max_workers=n) as pool:
        return list(pool.map(lambda _: wrapped(), range(n)))


def cleanup(*keys):
    r.delete(*keys)


# ---------- 场景 1：50 线程抢 10 件库存，验证不超卖 ----------
print("\n场景 1：库存 10，50 个并发线程各抢 1 件（验证不超卖）")
cleanup("test:stock:s1")
r.set("test:stock:s1", 10)

res = concurrent_run(lambda: r.eval(DEDUCT_LUA, 1, "test:stock:s1", 1), 50)
success = sum(1 for x in res if x == 1)
failed = sum(1 for x in res if isinstance(x, int) and x < 0)
final_stock = int(r.get("test:stock:s1"))

print(f"  成功 {success} 笔，库存不足被拒绝 {failed} 笔，最终库存 {final_stock}")
check("成功数 == 库存总数 10", success == 10, f"实际 {success}")
check("拒绝数 == 40", failed == 40, f"实际 {failed}")
check("最终库存恰好为 0 且不为负", final_stock == 0, f"实际 {final_stock}")
check("无超卖（成功数未超过库存）", success <= 10)
cleanup("test:stock:s1")

# ---------- 场景 2：多 key 原子性，部分不足则整体不扣 ----------
print("\n场景 2：一单两菜，菜品A 库存 5、菜品B 库存 0，10 线程并发下单（验证不会扣一半）")
cleanup("test:stock:s2a", "test:stock:s2b")
r.set("test:stock:s2a", 5)
r.set("test:stock:s2b", 0)

res = concurrent_run(lambda: r.eval(DEDUCT_LUA, 2, "test:stock:s2a", "test:stock:s2b", 1, 1), 10)
success = sum(1 for x in res if x == 1)
stock_a = int(r.get("test:stock:s2a"))

print(f"  成功 {success} 笔，菜品A 剩余库存 {stock_a}（初始 5）")
check("整单全部失败", success == 0, f"实际成功 {success}")
check("菜品A 库存未被部分扣减", stock_a == 5, f"实际 {stock_a}")
cleanup("test:stock:s2a", "test:stock:s2b")

# ---------- 场景 3：扣减后回滚，库存完整恢复 ----------
print("\n场景 3：扣减 3 件后模拟后续失败执行回滚脚本（验证库存恢复）")
cleanup("test:stock:s3")
r.set("test:stock:s3", 10)

deduct_ret = r.eval(DEDUCT_LUA, 1, "test:stock:s3", 3)
mid_stock = int(r.get("test:stock:s3"))
r.eval(RETURN_LUA, 1, "test:stock:s3", 3)
final_stock = int(r.get("test:stock:s3"))

print(f"  扣减返回 {deduct_ret}，扣后库存 {mid_stock}，回滚后库存 {final_stock}")
check("扣减成功且扣后库存 == 7", deduct_ret == 1 and mid_stock == 7)
check("回滚后库存恢复为 10", final_stock == 10, f"实际 {final_stock}")
cleanup("test:stock:s3")

# ---------- 场景 4：库存充足时并发扣减总量精确 ----------
print("\n场景 4：库存 100，50 线程各扣 2 件（验证总量精确）")
cleanup("test:stock:s4")
r.set("test:stock:s4", 100)

res = concurrent_run(lambda: r.eval(DEDUCT_LUA, 1, "test:stock:s4", 2), 50)
success = sum(1 for x in res if x == 1)
final_stock = int(r.get("test:stock:s4"))

print(f"  成功 {success} 笔，最终库存 {final_stock}")
check("全部成功", success == 50, f"实际 {success}")
check("最终库存恰好为 0（100 - 50*2）", final_stock == 0, f"实际 {final_stock}")
cleanup("test:stock:s4")

# ---------- 汇总 ----------
print(f"\n{'=' * 50}\n结果：{sum(results)}/{len(results)} 项通过")
raise SystemExit(0 if all(results) else 1)
