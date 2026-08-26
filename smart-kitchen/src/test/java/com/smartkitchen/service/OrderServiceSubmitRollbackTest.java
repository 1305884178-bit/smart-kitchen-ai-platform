package com.smartkitchen.service;

import com.smartkitchen.config.UserContext;
import com.smartkitchen.dto.OrderDetailDTO;
import com.smartkitchen.dto.OrderSubmitDTO;
import com.smartkitchen.entity.Dish;
import com.smartkitchen.mapper.DishMapper;
import com.smartkitchen.mapper.OrderDetailMapper;
import com.smartkitchen.mapper.OrderMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 下单中途失败补偿测试
 *
 * 场景：Redis Lua 预扣成功后，MySQL 条件更新（dishMapper.deductStock）返回 0 行。
 * 断言：事务回滚订单与明细不落库，且执行了 Redis 回滚脚本返还库存。
 * 库存 key 初始化已下沉到 Lua（ARGV 后半段为初值），不再依赖 hasKey + set。
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class OrderServiceSubmitRollbackTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderDetailMapper orderDetailMapper;

    @MockBean
    private DishMapper dishMapper;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    public void setup() {
        UserContext.setUserId(1001L);

        // Mock Redis：Lua 扣减成功返回 1（ARGV = qty + init，共两个可变参数）
        when(stringRedisTemplate.execute(any(RedisScript.class), any(List.class), anyString(), anyString()))
                .thenReturn(1L);

        // Mock 菜品：在售、库存充足；但 deductStock 返回 0，模拟 MySQL 侧库存不足（极端不一致）
        Dish dish = new Dish();
        dish.setId(1L);
        dish.setName("回滚测试菜");
        dish.setPrice(new BigDecimal("10.00"));
        dish.setStatus(1);
        dish.setDailyStock(100);
        when(dishMapper.selectById(1L)).thenReturn(dish);
        when(dishMapper.deductStock(anyLong(), anyInt())).thenReturn(0);
    }

    @AfterEach
    public void tearDown() {
        UserContext.clear();
    }

    @Test
    public void testSubmitOrder_mysqlDeductFails_orderNotPersistedAndRedisRolledBack() {
        long orderCountBefore = orderMapper.selectCount(null);
        long detailCountBefore = orderDetailMapper.selectCount(null);

        OrderSubmitDTO dto = new OrderSubmitDTO();
        dto.setSeatNumber("T01");
        OrderDetailDTO detail = new OrderDetailDTO();
        detail.setDishId(1L);
        detail.setQuantity(1);
        dto.setDetails(Collections.singletonList(detail));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> orderService.submitOrder(dto));
        assertTrue(ex.getMessage().contains("库存"), "异常信息应指向库存问题，实际: " + ex.getMessage());

        // 事务回滚：订单与明细均未落库
        assertEquals(orderCountBefore, orderMapper.selectCount(null), "订单不应落库");
        assertEquals(detailCountBefore, orderDetailMapper.selectCount(null), "订单明细不应落库");

        // 不再使用 hasKey + set 预热
        verify(stringRedisTemplate, never()).hasKey(anyString());

        // Redis 补偿：execute 共两次——先扣减脚本，后回滚脚本；ARGV = [qty, init]
        verify(stringRedisTemplate, times(2)).execute(
                any(RedisScript.class),
                any(List.class),
                eq("1"),
                eq("100")
        );
    }

    @Test
    public void testSubmitOrder_redisDeductInsufficient_failsBeforeDbWrite() {
        // Lua 返回 -1（首个菜品库存不足）
        when(stringRedisTemplate.execute(any(RedisScript.class), any(List.class), anyString(), anyString()))
                .thenReturn(-1L);

        long orderCountBefore = orderMapper.selectCount(null);

        OrderSubmitDTO dto = new OrderSubmitDTO();
        dto.setSeatNumber("T01");
        OrderDetailDTO detail = new OrderDetailDTO();
        detail.setDishId(1L);
        detail.setQuantity(999);
        dto.setDetails(Collections.singletonList(detail));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> orderService.submitOrder(dto));
        assertTrue(ex.getMessage().contains("库存不足"), "异常信息应为库存不足，实际: " + ex.getMessage());
        assertEquals(orderCountBefore, orderMapper.selectCount(null), "Redis 预扣失败时不应有任何落库");

        // 未发生 MySQL 扣减尝试，也无需回滚
        verify(dishMapper, times(0)).deductStock(anyLong(), anyInt());
        verify(stringRedisTemplate, times(1)).execute(any(RedisScript.class), any(List.class), anyString(), anyString());
        verify(stringRedisTemplate, never()).hasKey(anyString());
    }
}
