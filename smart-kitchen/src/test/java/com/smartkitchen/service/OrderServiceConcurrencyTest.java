package com.smartkitchen.service;

import com.smartkitchen.common.OrderStatusEnum;
import com.smartkitchen.entity.Order;
import com.smartkitchen.entity.OrderDetail;
import com.smartkitchen.mapper.OrderDetailMapper;
import com.smartkitchen.mapper.OrderMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 订单状态并发测试（真实 H2 数据库 + 线程池并发）
 *
 * 验证「状态校验下沉 SQL 的条件更新」能堵住先查后改窗口：
 * - 20 线程并发支付同一订单 → 仅 1 笔生效，其余报并发错误
 * - 10 线程并发出餐同一订单 → 仅 1 笔生效
 * - 支付与撤销并发竞争 → 恰好一方获胜，终态自洽
 *
 * payOrder/serveOrder 不触碰 Redis；cancelOrder 会执行回滚脚本，此处 Mock Redis 仅作桩。
 * 若未来链路依赖真实 Redis 行为，可换 Testcontainers / embedded-redis 复测。
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class OrderServiceConcurrencyTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderDetailMapper orderDetailMapper;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @AfterEach
    public void cleanup() {
        orderDetailMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<OrderDetail>()
                .eq(OrderDetail::getDishName, "并发测试菜"));
        orderMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Order>()
                .eq(Order::getSeatNumber, "T99"));
    }

    private Order newOrder(int status) {
        Order order = new Order();
        order.setOrderNo("CONC" + System.nanoTime());
        order.setUserId(1001L);
        order.setSeatNumber("T99");
        order.setTotalAmount(new BigDecimal("10.00"));
        order.setStatus(status);
        order.setCreateTime(LocalDateTime.now());
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.insert(order);
        return order;
    }

    /** n 个线程经同一起跑门并发执行任务，返回每线程的异常（成功则为 null） */
    private List<Throwable> concurrentRun(int n, Runnable task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        CopyOnWriteArrayList<Throwable> errors = new CopyOnWriteArrayList<>();
        try {
            Future<?>[] futures = new Future<?>[n];
            for (int i = 0; i < n; i++) {
                futures[i] = pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await(5, TimeUnit.SECONDS);
                        task.run();
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS), "线程未全部就绪");
            start.countDown();
            for (Future<?> f : futures) {
                f.get(15, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        return errors;
    }

    @Test
    public void testConcurrentPay_orderedOrder_onlyOneWins() throws Exception {
        Order order = newOrder(OrderStatusEnum.ORDERED.getCode());
        Long orderId = order.getId();

        List<Throwable> errors = concurrentRun(20, () -> orderService.payOrder(orderId));

        long failures = errors.size();
        assertEquals(19, failures, "20 并发支付应恰好 1 笔成功、19 笔失败");
        errors.forEach(t -> assertTrue(t.getMessage().contains("已变更") || t.getMessage().contains("已支付"),
                "失败原因应为并发/重复支付，实际: " + t.getMessage()));

        Order after = orderMapper.selectById(orderId);
        assertNotNull(after.getPayTime());
        assertNotNull(after.getPaymentTradeNo());
        assertEquals(OrderStatusEnum.ORDERED.getCode(), after.getStatus(), "ORDERED 结账仅登记支付，不变状态");
    }

    @Test
    public void testConcurrentPay_servedOrder_transitionsToPaidOnce() throws Exception {
        Order order = newOrder(OrderStatusEnum.SERVED.getCode());
        Long orderId = order.getId();

        List<Throwable> errors = concurrentRun(20, () -> orderService.payOrder(orderId));

        assertEquals(19, errors.size(), "20 并发支付应恰好 1 笔成功、19 笔失败");
        Order after = orderMapper.selectById(orderId);
        assertEquals(OrderStatusEnum.PAID.getCode(), after.getStatus(), "SERVED 结账应流转为 PAID");
        assertNotNull(after.getPaymentTradeNo());
    }

    @Test
    public void testConcurrentServe_onlyOneWins() throws Exception {
        Order order = newOrder(OrderStatusEnum.ORDERED.getCode());
        Long orderId = order.getId();

        List<Throwable> errors = concurrentRun(10, () -> orderService.serveOrder(orderId));

        assertEquals(9, errors.size(), "10 并发出餐应恰好 1 笔成功、9 笔失败");
        Order after = orderMapper.selectById(orderId);
        assertEquals(OrderStatusEnum.SERVED.getCode(), after.getStatus());
        assertNotNull(after.getCompleteTime());
    }

    @Test
    public void testPayVsCancel_exactlyOneWins() throws Exception {
        // cancelOrder 的库存回滚会走 Redis 回滚脚本，Mock 掉避免依赖真实 Redis
        when(stringRedisTemplate.execute(any(RedisScript.class), any(java.util.List.class), any(), any())).thenReturn(1L);

        Order order = newOrder(OrderStatusEnum.SERVED.getCode());
        Long orderId = order.getId();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CopyOnWriteArrayList<String> outcomes = new CopyOnWriteArrayList<>();
        try {
            Future<?> payFuture = pool.submit(() -> {
                await(start);
                try {
                    orderService.payOrder(orderId);
                    outcomes.add("PAY_OK");
                } catch (Throwable t) {
                    outcomes.add("PAY_FAIL");
                }
            });
            Future<?> cancelFuture = pool.submit(() -> {
                await(start);
                try {
                    orderService.cancelOrder(orderId);
                    outcomes.add("CANCEL_OK");
                } catch (Throwable t) {
                    outcomes.add("CANCEL_FAIL");
                }
            });
            start.countDown();
            payFuture.get(15, TimeUnit.SECONDS);
            cancelFuture.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertTrue(outcomes.contains("PAY_OK") != outcomes.contains("CANCEL_OK"),
                "支付与撤销并发应恰好一方成功，实际: " + outcomes);
        Order after = orderMapper.selectById(orderId);
        if (outcomes.contains("PAY_OK")) {
            assertEquals(OrderStatusEnum.PAID.getCode(), after.getStatus(), "支付胜出后状态应为 PAID 且不可被撤销覆盖");
            assertNotNull(after.getPaymentTradeNo());
        } else {
            assertEquals(OrderStatusEnum.CANCELLED.getCode(), after.getStatus(), "撤销胜出后状态应为 CANCELLED 且不可被支付覆盖");
            assertNull(after.getPaymentTradeNo());
        }
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
