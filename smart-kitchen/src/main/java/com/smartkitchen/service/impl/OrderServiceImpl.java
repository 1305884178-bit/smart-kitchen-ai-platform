package com.smartkitchen.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartkitchen.common.OrderDetailAddedEnum;
import com.smartkitchen.common.OrderStatusEnum;
import com.smartkitchen.dto.OrderDetailDTO;
import com.smartkitchen.dto.OrderSubmitDTO;
import com.smartkitchen.entity.Dish;
import com.smartkitchen.entity.Order;
import com.smartkitchen.entity.OrderDetail;
import com.smartkitchen.mapper.DishMapper;
import com.smartkitchen.mapper.OrderMapper;
import com.smartkitchen.service.OrderDetailService;
import com.smartkitchen.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Order> implements OrderService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private DishMapper dishMapper;

    @Autowired
    private OrderDetailService orderDetailService;

    private static final String STOCK_PREFIX = "dish:stock:";

    private DefaultRedisScript<Long> deductStockScript;
    private DefaultRedisScript<Long> returnStockScript;

    @PostConstruct
    public void init() {
        deductStockScript = new DefaultRedisScript<>();
        deductStockScript.setLocation(new ClassPathResource("scripts/deduct_stock.lua"));
        deductStockScript.setResultType(Long.class);

        returnStockScript = new DefaultRedisScript<>();
        returnStockScript.setLocation(new ClassPathResource("scripts/return_stock.lua"));
        returnStockScript.setResultType(Long.class);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    // TODO 如果 JVM 进程直接崩溃了呢？ （比如 OOM、kill -9、机房断电）
    public String submitOrder(OrderSubmitDTO submitDTO, Long userId) {
        List<String> keys = new ArrayList<>();
        List<String> args = new ArrayList<>();

        BigDecimal totalAmount = BigDecimal.ZERO;
        List<OrderDetail> detailList = new ArrayList<>();

        for (OrderDetailDTO detailDTO : submitDTO.getDetails()) {
            Long dishId = detailDTO.getDishId();
            String key = STOCK_PREFIX + dishId;
            
            Dish dish = dishMapper.selectById(dishId);
            if (dish == null) {
                throw new RuntimeException("菜品不存在: " + dishId);
            }

            if (Boolean.FALSE.equals(stringRedisTemplate.hasKey(key))) {
                stringRedisTemplate.opsForValue().set(key, String.valueOf(dish.getDailyStock()));
            }
            
            keys.add(key);
            args.add(String.valueOf(detailDTO.getQuantity()));

            // 组装明细
            OrderDetail detail = new OrderDetail();
            detail.setDishId(dishId);
            detail.setDishName(dish.getName());
            detail.setQuantity(detailDTO.getQuantity());
            detail.setPrice(dish.getPrice());
            detail.setIsAdded(OrderDetailAddedEnum.FIRST_ORDER.getCode());
            detail.setCreateTime(LocalDateTime.now());
            detailList.add(detail);

            totalAmount = totalAmount.add(dish.getPrice().multiply(new BigDecimal(detailDTO.getQuantity())));
        }

        // Lua脚本扣减库存
        Long result = stringRedisTemplate.execute(deductStockScript, keys, args.toArray(new String[0]));

        if (result != null && result < 0) {
            int index = (int) (-result - 1);
            throw new RuntimeException("库存不足: " + submitDTO.getDetails().get(index).getDishId());
        }

        try {
            // 落库
            String orderNo = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            Order order = new Order();
            order.setOrderNo(orderNo);
            order.setUserId(userId);
            order.setSeatNumber(submitDTO.getSeatNumber());
            order.setTotalAmount(totalAmount);
            order.setStatus(OrderStatusEnum.ORDERED.getCode());
            order.setRemark(submitDTO.getRemark());
            order.setCreateTime(LocalDateTime.now());
            order.setUpdateTime(LocalDateTime.now());
            this.save(order);

            for (OrderDetail detail : detailList) {
                detail.setOrderId(order.getId());
            }
            orderDetailService.saveBatch(detailList);

            // 同步扣减数据库库存 (乐观锁兜底)
            for (OrderDetail detail : detailList) {
                int affectedRows = dishMapper.deductStock(detail.getDishId(), detail.getQuantity());
                if (affectedRows == 0) {
                    // 极端情况兜底：Redis过了但DB不够，抛异常让事务回滚
                    throw new RuntimeException("数据库库存同步异常，菜品ID: " + detail.getDishId());
                }
            }

            return orderNo;
        } catch (Exception e) {
            // 数据库操作失败，执行补偿操作：将已扣减的 Redis 库存退还
            stringRedisTemplate.execute(returnStockScript, keys, args.toArray(new String[0]));
            throw e; // 继续抛出异常以触发 MySQL 事务回滚
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void payOrder(Long orderId) {
        Order order = this.getById(orderId);
        if (order == null) {
            throw new RuntimeException("订单不存在");
        }
        if (order.getStatus() != OrderStatusEnum.SERVED.getCode() && order.getStatus() != OrderStatusEnum.ORDERED.getCode()) {
            throw new RuntimeException("当前状态不可结账");
        }
        order.setStatus(OrderStatusEnum.PAID.getCode());
        order.setPaymentTradeNo("SIM_" + System.currentTimeMillis());
        order.setPayTime(LocalDateTime.now());
        order.setUpdateTime(LocalDateTime.now());
        this.updateById(order);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelOrder(Long orderId) {
        Order order = this.getById(orderId);
        if (order == null) {
            throw new RuntimeException("订单不存在");
        }
        if (order.getStatus() != OrderStatusEnum.ORDERED.getCode() && order.getStatus() != OrderStatusEnum.SERVED.getCode()) {
            throw new RuntimeException("当前状态不可撤销");
        }
        order.setStatus(OrderStatusEnum.CANCELLED.getCode());
        order.setCancelReason("用户/管理员撤销");
        order.setUpdateTime(LocalDateTime.now());
        this.updateById(order);

        // 恢复库存
        List<OrderDetail> details = orderDetailService.lambdaQuery().eq(OrderDetail::getOrderId, orderId).list();
        List<String> keys = new ArrayList<>();
        List<String> args = new ArrayList<>();
        for (OrderDetail detail : details) {
            keys.add(STOCK_PREFIX + detail.getDishId());
            args.add(String.valueOf(detail.getQuantity()));
            
            Dish dish = dishMapper.selectById(detail.getDishId());
            if (dish != null) {
                dish.setDailyStock(dish.getDailyStock() + detail.getQuantity());
                dishMapper.updateById(dish);
            }
        }

        if (!keys.isEmpty()) {
            stringRedisTemplate.execute(returnStockScript, keys, args.toArray(new String[0]));
        }
    }
}
