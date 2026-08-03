package com.smartkitchen.service.impl;

import com.smartkitchen.config.KitchenBoardWebSocketHandler;
import com.smartkitchen.config.CustomerWebSocketHandler;
import com.smartkitchen.dto.AddDishDTO;
import com.smartkitchen.dto.OrderDetailVO;
import com.smartkitchen.dto.OrderVO;
import org.springframework.beans.BeanUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import java.util.Arrays;
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

    @Autowired
    private KitchenBoardWebSocketHandler kitchenBoardWebSocketHandler;

    @Autowired
    private CustomerWebSocketHandler customerWebSocketHandler;

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
                    throw new RuntimeException("数据库库存同步异常，菜品ID: " + detail.getDishId());
                }
            }

            kitchenBoardWebSocketHandler.sendMessage("{\"type\":\"NEW_ORDER\",\"message\":\"有新订单了\"}");
            return orderNo;
        } catch (Exception e) {
            stringRedisTemplate.execute(returnStockScript, keys, args.toArray(new String[0]));
            throw e;
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

    @Override
    public List<OrderVO> getOrderedOrders() {
        List<Order> orders = this.lambdaQuery()
                .eq(Order::getStatus, OrderStatusEnum.ORDERED.getCode())
                .orderByAsc(Order::getCreateTime)
                .list();

        if (orders == null || orders.isEmpty()) {
            return new ArrayList<>();
        }

        List<Long> orderIds = orders.stream().map(Order::getId).toList();
        List<OrderDetail> allDetails = orderDetailService.lambdaQuery()
                .in(OrderDetail::getOrderId, orderIds)
                .list();

        return orders.stream().map(order -> {
            OrderVO vo = new OrderVO();
            BeanUtils.copyProperties(order, vo);
            List<OrderDetail> details = allDetails.stream()
                    .filter(d -> d.getOrderId().equals(order.getId()))
                    .toList();
            vo.setDetails(details);
            return vo;
        }).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void serveOrder(Long orderId) {
        Order order = this.getById(orderId);
        if (order == null) {
            throw new RuntimeException("订单不存在");
        }
        if (order.getStatus() != OrderStatusEnum.ORDERED.getCode()) {
            throw new RuntimeException("当前状态不可操作出餐");
        }
        order.setStatus(OrderStatusEnum.SERVED.getCode());
        order.setCompleteTime(LocalDateTime.now());
        order.setUpdateTime(LocalDateTime.now());
        this.updateById(order);

        customerWebSocketHandler.sendMessageToUser(order.getUserId(), "{\"type\":\"ORDER_SERVED\",\"message\":\"您的订单已出餐，请取餐\"}");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addDish(Long orderId, AddDishDTO addDishDTO) {
        Order order = this.getById(orderId);
        if (order == null) {
            throw new RuntimeException("订单不存在");
        }
        if (order.getStatus() != OrderStatusEnum.ORDERED.getCode() && order.getStatus() != OrderStatusEnum.SERVED.getCode()) {
            throw new RuntimeException("当前状态不可加菜");
        }

        // 若当前状态为SERVED，回退到ORDERED
        boolean wasServed = order.getStatus().equals(OrderStatusEnum.SERVED.getCode());
        if (wasServed) {
            order.setStatus(OrderStatusEnum.ORDERED.getCode());
            order.setUpdateTime(LocalDateTime.now());
            this.updateById(order);
        }

        List<String> keys = new ArrayList<>();
        List<String> args = new ArrayList<>();
        BigDecimal addAmount = BigDecimal.ZERO;
        List<OrderDetail> newDetails = new ArrayList<>();

        for (OrderDetailDTO detailDTO : addDishDTO.getDetails()) {
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

            OrderDetail detail = new OrderDetail();
            detail.setOrderId(orderId);
            detail.setDishId(dishId);
            detail.setDishName(dish.getName());
            detail.setQuantity(detailDTO.getQuantity());
            detail.setPrice(dish.getPrice());
            detail.setIsAdded(OrderDetailAddedEnum.ADDED.getCode());
            detail.setCreateTime(LocalDateTime.now());
            newDetails.add(detail);

            addAmount = addAmount.add(dish.getPrice().multiply(new BigDecimal(detailDTO.getQuantity())));
        }

        Long result = stringRedisTemplate.execute(deductStockScript, keys, args.toArray(new String[0]));
        if (result != null && result < 0) {
            throw new RuntimeException("库存不足，加菜失败");
        }

        try {
            orderDetailService.saveBatch(newDetails);

            // 更新订单总金额
            order.setTotalAmount(order.getTotalAmount().add(addAmount));
            order.setUpdateTime(LocalDateTime.now());
            this.updateById(order);

            // 同步扣减数据库库存
            for (OrderDetail detail : newDetails) {
                int affectedRows = dishMapper.deductStock(detail.getDishId(), detail.getQuantity());
                if (affectedRows == 0) {
                    throw new RuntimeException("数据库库存同步异常，菜品ID: " + detail.getDishId());
                }
            }

            kitchenBoardWebSocketHandler.sendMessage("{\"type\":\"ORDER_UPDATED\",\"message\":\"订单有加菜更新\"}");
        } catch (Exception e) {
            stringRedisTemplate.execute(returnStockScript, keys, args.toArray(new String[0]));
            throw e;
        }
    }

    @Override
    public Page<OrderVO> listUserOrders(Long userId, Integer page, Integer size) {
        Page<Order> orderPage = new Page<>(page, size);
        this.lambdaQuery()
                .eq(Order::getUserId, userId)
                .orderByDesc(Order::getCreateTime)
                .page(orderPage);

        Page<OrderVO> voPage = new Page<>(page, size, orderPage.getTotal());
        if (orderPage.getRecords().isEmpty()) {
            return voPage;
        }

        List<Long> orderIds = orderPage.getRecords().stream().map(Order::getId).toList();
        List<OrderDetail> allDetails = orderDetailService.lambdaQuery()
                .in(OrderDetail::getOrderId, orderIds)
                .list();

        List<OrderVO> voList = orderPage.getRecords().stream().map(order -> {
            OrderVO vo = new OrderVO();
            BeanUtils.copyProperties(order, vo);
            List<OrderDetail> details = allDetails.stream()
                    .filter(d -> d.getOrderId().equals(order.getId()))
                    .toList();
            vo.setDetails(details);
            return vo;
        }).toList();

        voPage.setRecords(voList);
        return voPage;
    }

    @Override
    public OrderDetailVO getUserOrderDetail(Long orderId, Long userId) {
        Order order = this.lambdaQuery()
                .eq(Order::getId, orderId)
                .eq(Order::getUserId, userId)
                .one();
        if (order == null) {
            throw new RuntimeException("订单不存在");
        }

        OrderDetailVO vo = new OrderDetailVO();
        BeanUtils.copyProperties(order, vo);

        List<OrderDetail> details = orderDetailService.lambdaQuery()
                .eq(OrderDetail::getOrderId, orderId)
                .list();
        vo.setDetails(details);
        vo.setAvailableActions(getAvailableActions(order.getStatus()));
        return vo;
    }

    @Override
    public Page<OrderVO> listAdminOrders(Integer page, Integer size, Integer status, String seatNumber) {
        Page<Order> orderPage = new Page<>(page, size);
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        if (status != null) {
            wrapper.eq(Order::getStatus, status);
        }
        if (StringUtils.isNotBlank(seatNumber)) {
            wrapper.eq(Order::getSeatNumber, seatNumber);
        }
        wrapper.orderByDesc(Order::getCreateTime);
        this.page(orderPage, wrapper);

        Page<OrderVO> voPage = new Page<>(page, size, orderPage.getTotal());
        if (orderPage.getRecords().isEmpty()) {
            return voPage;
        }

        List<Long> orderIds = orderPage.getRecords().stream().map(Order::getId).toList();
        List<OrderDetail> allDetails = orderDetailService.lambdaQuery()
                .in(OrderDetail::getOrderId, orderIds)
                .list();

        List<OrderVO> voList = orderPage.getRecords().stream().map(order -> {
            OrderVO vo = new OrderVO();
            BeanUtils.copyProperties(order, vo);
            List<OrderDetail> details = allDetails.stream()
                    .filter(d -> d.getOrderId().equals(order.getId()))
                    .toList();
            vo.setDetails(details);
            return vo;
        }).toList();

        voPage.setRecords(voList);
        return voPage;
    }

    /**
     * 根据订单状态计算可操作按钮列表
     * @param status 订单状态码
     * @return 可用操作列表
     */
    private List<String> getAvailableActions(int status) {
        if (status == OrderStatusEnum.ORDERED.getCode()) {
            return Arrays.asList("ADD_DISH", "PAY");
        } else if (status == OrderStatusEnum.SERVED.getCode()) {
            return Arrays.asList("ADD_DISH", "PAY");
        } else if (status == OrderStatusEnum.PAID.getCode()) {
            return List.of("REVIEW");
        } else {
            return new ArrayList<>();
        }
    }
}
