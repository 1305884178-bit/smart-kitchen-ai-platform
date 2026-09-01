package com.smartkitchen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartkitchen.common.CancelReasonEnum;
import com.smartkitchen.common.OrderStatusEnum;
import com.smartkitchen.config.KitchenBoardWebSocketHandler;
import com.smartkitchen.config.UserContext;
import com.smartkitchen.dto.AddDishDTO;
import com.smartkitchen.dto.OrderDetailDTO;
import com.smartkitchen.dto.OrderSubmitDTO;
import com.smartkitchen.entity.Dish;
import com.smartkitchen.entity.Order;
import com.smartkitchen.entity.OrderDetail;
import com.smartkitchen.mapper.DishMapper;
import com.smartkitchen.mapper.OrderDetailMapper;
import com.smartkitchen.mapper.OrderMapper;
import com.smartkitchen.task.OrderTimeoutScanTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * 先付后做主流程测试（真实 H2 + Mock Redis/RabbitTemplate/看板 WS）
 *
 * 覆盖验收点：
 * 1. 下单后看板为空（不推 NEW_ORDER），支付后才推
 * 2. 超时未支付：MQ 消费逻辑/扫表取消，MySQL 库存归还（Redis 回滚脚本被调用）
 * 3. 已支付后超时消息到期：跳过不取消
 * 4. 支付与超时取消只能成功一笔
 * 5. 未支付父单不能加菜；子单未付不上厨，付完才上厨；子单超时不影响已付父单
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class OrderServicePayFirstFlowTest {

    private static final String SEAT = "T88";

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderDetailMapper orderDetailMapper;

    @Autowired
    private DishMapper dishMapper;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @MockBean
    private RabbitTemplate rabbitTemplate;

    @MockBean
    private KitchenBoardWebSocketHandler kitchenBoardWebSocketHandler;

    @BeforeEach
    public void setup() {
        UserContext.setUserId(1001L);
        // Mock Redis：Lua 扣减/回滚均成功（ARGV = qty + init/after，共两个可变参数）
        when(stringRedisTemplate.execute(any(RedisScript.class), any(List.class), anyString(), anyString()))
                .thenReturn(1L);
    }

    @AfterEach
    public void cleanup() {
        UserContext.clear();
        List<Long> ids = orderMapper.selectList(
                        new LambdaQueryWrapper<Order>().eq(Order::getSeatNumber, SEAT))
                .stream().map(Order::getId).toList();
        if (!ids.isEmpty()) {
            orderDetailMapper.delete(new LambdaQueryWrapper<OrderDetail>().in(OrderDetail::getOrderId, ids));
            orderMapper.delete(new LambdaQueryWrapper<Order>().in(Order::getId, ids));
        }
    }

    private Order submitOne() {
        OrderSubmitDTO dto = new OrderSubmitDTO();
        dto.setSeatNumber(SEAT);
        OrderDetailDTO detail = new OrderDetailDTO();
        detail.setDishId(1L);
        detail.setQuantity(1);
        dto.setDetails(Collections.singletonList(detail));
        String orderNo = orderService.submitOrder(dto);
        return orderMapper.selectOne(new LambdaQueryWrapper<Order>().eq(Order::getOrderNo, orderNo));
    }

    private AddDishDTO addDishDTO(Long dishId, int quantity) {
        AddDishDTO dto = new AddDishDTO();
        OrderDetailDTO detail = new OrderDetailDTO();
        detail.setDishId(dishId);
        detail.setQuantity(quantity);
        dto.setDetails(Collections.singletonList(detail));
        return dto;
    }

    private boolean boardContains(Long orderId) {
        return orderService.getOrderedOrders().stream().anyMatch(o -> o.getId().equals(orderId));
    }

    @Test
    public void testSubmit_noBoardPush_sendsDelayMessage_boardExcludesUnpaid() {
        Order order = submitOne();

        assertNotNull(order.getId());
        assertEquals(OrderStatusEnum.ORDERED.getCode(), order.getStatus());
        assertNull(order.getPayTime());

        // 下单不推厨房看板
        verify(kitchenBoardWebSocketHandler, never()).sendMessage(anyString());
        // 事务提交后发出延迟消息：order.delay.exchange / rk=order.timeout
        verify(rabbitTemplate, times(1)).convertAndSend(
                eq("order.delay.exchange"), eq("order.timeout"),
                any(org.springframework.amqp.core.Message.class),
                any(org.springframework.amqp.rabbit.connection.CorrelationData.class));
        // 看板 HTTP 快照不含未支付单
        assertFalse(boardContains(order.getId()));
    }

    @Test
    public void testPay_pushesNewOrder_boardShowsAfterPay() {
        Order order = submitOne();
        verify(kitchenBoardWebSocketHandler, never()).sendMessage(anyString());

        orderService.payOrder(order.getId());

        // 支付成功才推 NEW_ORDER
        verify(kitchenBoardWebSocketHandler, times(1)).sendMessage(argThat(
                msg -> msg.contains("NEW_ORDER") && msg.contains("\"id\":" + order.getId())));
        // 看板快照出现该单；ORDERED 仅登记 pay_time，状态不变
        assertTrue(boardContains(order.getId()));
        Order after = orderMapper.selectById(order.getId());
        assertNotNull(after.getPayTime());
        assertEquals(OrderStatusEnum.ORDERED.getCode(), after.getStatus());
    }

    @Test
    public void testAddDish_unpaidParent_forbidden() {
        Order order = submitOne();

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> orderService.addDish(order.getId(), addDishDTO(2L, 1)));
        assertTrue(ex.getMessage().contains("未支付"), "实际: " + ex.getMessage());
    }

    @Test
    public void testAddDish_paidParent_childHiddenUntilPaid() {
        Order parent = submitOne();
        orderService.payOrder(parent.getId());
        verify(kitchenBoardWebSocketHandler, times(1)).sendMessage(anyString());

        // 已支付父单加菜成功，但不推看板
        orderService.addDish(parent.getId(), addDishDTO(2L, 1));
        verify(kitchenBoardWebSocketHandler, times(1)).sendMessage(anyString());

        Order child = orderMapper.selectOne(
                new LambdaQueryWrapper<Order>().eq(Order::getParentOrderId, parent.getId()));
        assertNotNull(child);
        assertNull(child.getPayTime());
        // 子单未支付不上厨；afterCommit 发出了子单的延迟消息
        assertFalse(boardContains(child.getId()));
        verify(rabbitTemplate, times(2)).convertAndSend(
                eq("order.delay.exchange"), eq("order.timeout"),
                any(org.springframework.amqp.core.Message.class),
                any(org.springframework.amqp.rabbit.connection.CorrelationData.class));

        // 父单已支付但有未支付子单 → 允许再支付，只给子单写 pay_time，并推子单 NEW_ORDER
        orderService.payOrder(parent.getId());
        Order childAfter = orderMapper.selectById(child.getId());
        assertNotNull(childAfter.getPayTime());
        assertEquals(OrderStatusEnum.ORDERED.getCode(), childAfter.getStatus());
        verify(kitchenBoardWebSocketHandler, times(1)).sendMessage(argThat(
                msg -> msg.contains("NEW_ORDER") && msg.contains("\"id\":" + child.getId())));
        assertTrue(boardContains(child.getId()));
    }

    @Test
    public void testPayAgain_allPaid_rejected() {
        Order parent = submitOne();
        orderService.payOrder(parent.getId());

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> orderService.payOrder(parent.getId()));
        assertTrue(ex.getMessage().contains("已支付"), "实际: " + ex.getMessage());
    }

    @Test
    public void testPayVsTimeoutCancel_onlyOneWins() {
        // 场景一：先支付 → 超时取消消息到期应跳过（视为支付胜出）
        Order paid = submitOne();
        orderService.payOrder(paid.getId());
        orderService.cancelOrderForTimeout(paid.getId());
        Order paidAfter = orderMapper.selectById(paid.getId());
        assertEquals(OrderStatusEnum.ORDERED.getCode(), paidAfter.getStatus());
        assertNotNull(paidAfter.getPayTime());

        // 场景二：先超时取消 → 支付应失败
        Order cancelled = submitOne();
        orderService.cancelOrderForTimeout(cancelled.getId());
        Order cancelledAfter = orderMapper.selectById(cancelled.getId());
        assertEquals(OrderStatusEnum.CANCELLED.getCode(), cancelledAfter.getStatus());
        assertEquals(CancelReasonEnum.PAY_TIMEOUT.getCode(), cancelledAfter.getCancelReason());
        assertThrows(RuntimeException.class, () -> orderService.payOrder(cancelled.getId()));
    }

    @Test
    public void testTimeoutCancel_neverCancelsServed() {
        Order served = new Order();
        served.setOrderNo("SERVED" + System.nanoTime());
        served.setUserId(1001L);
        served.setSeatNumber(SEAT);
        served.setTotalAmount(new BigDecimal("10.00"));
        served.setStatus(OrderStatusEnum.SERVED.getCode());
        served.setCreateTime(LocalDateTime.now().minusMinutes(20));
        served.setUpdateTime(LocalDateTime.now());
        orderMapper.insert(served);

        orderService.cancelOrderForTimeout(served.getId());

        assertEquals(OrderStatusEnum.SERVED.getCode(), orderMapper.selectById(served.getId()).getStatus());
    }

    @Test
    public void testChildTimeout_onlyCancelsChild_paidParentUntouched() {
        Order parent = submitOne();
        orderService.payOrder(parent.getId());
        orderService.addDish(parent.getId(), addDishDTO(2L, 1));
        Order child = orderMapper.selectOne(
                new LambdaQueryWrapper<Order>().eq(Order::getParentOrderId, parent.getId()));

        int dish2StockBefore = dishMapper.selectById(2L).getDailyStock();
        orderService.cancelOrderForTimeout(child.getId());

        // 子单取消并还库存；已支付父单不受影响
        Order childAfter = orderMapper.selectById(child.getId());
        assertEquals(OrderStatusEnum.CANCELLED.getCode(), childAfter.getStatus());
        assertEquals(CancelReasonEnum.PAY_TIMEOUT.getCode(), childAfter.getCancelReason());
        assertEquals(dish2StockBefore + 1, dishMapper.selectById(2L).getDailyStock());

        Order parentAfter = orderMapper.selectById(parent.getId());
        assertEquals(OrderStatusEnum.ORDERED.getCode(), parentAfter.getStatus());
        assertNotNull(parentAfter.getPayTime());
    }

    @Test
    public void testParentTimeout_cascadesUnpaidChildren() {
        // 未支付父单无法通过 addDish 产生子单，此处直接落库构造（兼容存量数据语义）
        Order parent = newUnpaidOrder("CASCADE_P");
        Order child = new Order();
        child.setOrderNo("CASCADE_C" + System.nanoTime());
        child.setUserId(1001L);
        child.setSeatNumber(SEAT);
        child.setTotalAmount(new BigDecimal("10.00"));
        child.setStatus(OrderStatusEnum.ORDERED.getCode());
        child.setParentOrderId(parent.getId());
        child.setCreateTime(LocalDateTime.now().minusMinutes(20));
        child.setUpdateTime(LocalDateTime.now());
        orderMapper.insert(child);

        orderService.cancelOrderForTimeout(parent.getId());

        assertEquals(OrderStatusEnum.CANCELLED.getCode(), orderMapper.selectById(parent.getId()).getStatus());
        Order childAfter = orderMapper.selectById(child.getId());
        assertEquals(OrderStatusEnum.CANCELLED.getCode(), childAfter.getStatus());
        assertEquals(CancelReasonEnum.PAY_TIMEOUT.getCode(), childAfter.getCancelReason());
    }

    private Order newUnpaidOrder(String orderNoPrefix) {
        Order order = new Order();
        order.setOrderNo(orderNoPrefix + System.nanoTime());
        order.setUserId(1001L);
        order.setSeatNumber(SEAT);
        order.setTotalAmount(new BigDecimal("10.00"));
        order.setStatus(OrderStatusEnum.ORDERED.getCode());
        order.setCreateTime(LocalDateTime.now().minusMinutes(20));
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.insert(order);
        return order;
    }

    @Test
    public void testScanTask_cancelsTimeoutUnpaidAndReturnsStock() {
        // 超时未支付单（create_time 早于 15 分钟前）+ 一笔刚下的未支付单
        Order timeout = newUnpaidOrder("SCAN_OLD");
        OrderDetail detail = new OrderDetail();
        detail.setOrderId(timeout.getId());
        detail.setDishId(1L);
        detail.setDishName("水煮鱼");
        detail.setQuantity(2);
        detail.setPrice(new BigDecimal("88.00"));
        detail.setIsAdded(0);
        detail.setCreateTime(timeout.getCreateTime());
        orderDetailMapper.insert(detail);

        Order fresh = submitOne();

        int dish1StockBefore = dishMapper.selectById(1L).getDailyStock();

        // 测试 profile 下扫表 Bean 不启动，直接构造并调用任务方法
        OrderTimeoutScanTask task = new OrderTimeoutScanTask();
        ReflectionTestUtils.setField(task, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(task, "orderService", orderService);
        ReflectionTestUtils.setField(task, "payTimeoutMs", 900000L);
        task.scanTimeoutUnpaidOrders();

        // 超时单被取消（PAY_TIMEOUT）且 MySQL 库存归还
        Order timeoutAfter = orderMapper.selectById(timeout.getId());
        assertEquals(OrderStatusEnum.CANCELLED.getCode(), timeoutAfter.getStatus());
        assertEquals(CancelReasonEnum.PAY_TIMEOUT.getCode(), timeoutAfter.getCancelReason());
        Dish dish1After = dishMapper.selectById(1L);
        assertEquals(dish1StockBefore + 2, dish1After.getDailyStock());

        // 未超时的新单不受影响
        assertEquals(OrderStatusEnum.ORDERED.getCode(), orderMapper.selectById(fresh.getId()).getStatus());

        // Redis 回滚脚本被调用（返还库存）
        verify(stringRedisTemplate, atLeastOnce()).execute(
                any(RedisScript.class), any(List.class), anyString(), anyString());
    }
}
