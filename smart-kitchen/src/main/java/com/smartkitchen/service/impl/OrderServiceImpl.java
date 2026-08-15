package com.smartkitchen.service.impl;

import com.smartkitchen.config.KitchenBoardWebSocketHandler;
import com.smartkitchen.config.CustomerWebSocketHandler;
import com.smartkitchen.config.RabbitMQConfig;
import com.smartkitchen.config.UserContext;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartkitchen.dto.OrderDetailDTO;
import com.smartkitchen.dto.OrderSubmitDTO;
import com.smartkitchen.entity.Dish;
import com.smartkitchen.entity.Order;
import com.smartkitchen.entity.OrderDetail;
import com.smartkitchen.entity.Review;
import com.smartkitchen.mapper.DishMapper;
import com.smartkitchen.mapper.OrderMapper;
import com.smartkitchen.mapper.ReviewMapper;
import com.smartkitchen.service.OrderDetailService;
import com.smartkitchen.service.OrderService;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
    private ReviewMapper reviewMapper;

    @Autowired
    private KitchenBoardWebSocketHandler kitchenBoardWebSocketHandler;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CustomerWebSocketHandler customerWebSocketHandler;

    @Autowired(required = false)
    private RabbitTemplate rabbitTemplate;

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
    public String submitOrder(OrderSubmitDTO submitDTO) {
        Long userId = UserContext.getUserId();
        List<String> keys = new ArrayList<>();
        List<String> args = new ArrayList<>();

        BigDecimal totalAmount = BigDecimal.ZERO;
        List<OrderDetail> detailList = new ArrayList<>();
        List<OrderDetail> activeDetails = new ArrayList<>(); // 在售菜品，需扣库存

        for (OrderDetailDTO detailDTO : submitDTO.getDetails()) {
            Long dishId = detailDTO.getDishId();
            Dish dish = dishMapper.selectById(dishId);
            if (dish == null) {
                throw new RuntimeException("菜品不存在: " + dishId);
            }

            OrderDetail detail = new OrderDetail();
            detail.setDishId(dishId);
            detail.setQuantity(detailDTO.getQuantity());
            detail.setIsAdded(OrderDetailAddedEnum.FIRST_ORDER.getCode());
            detail.setCreateTime(LocalDateTime.now());

            if (dish.getStatus() != null && dish.getStatus() == 1) {
                // 在售菜品：正常计算
                String key = STOCK_PREFIX + dishId;
                if (Boolean.FALSE.equals(stringRedisTemplate.hasKey(key))) {
                    stringRedisTemplate.opsForValue().set(key, String.valueOf(dish.getDailyStock()));
                }
                keys.add(key);
                args.add(String.valueOf(detailDTO.getQuantity()));

                detail.setDishName(dish.getName());
                detail.setPrice(dish.getPrice());
                totalAmount = totalAmount.add(dish.getPrice().multiply(new BigDecimal(detailDTO.getQuantity())));
                activeDetails.add(detail);
            } else {
                // 已下架菜品：不计金额、不扣库存、标记名称
                detail.setDishName(dish.getName() + "（已下架）");
                detail.setPrice(BigDecimal.ZERO);
            }
            detailList.add(detail);
        }

        // Lua脚本扣减库存（仅对在售菜品）
        if (!keys.isEmpty()) {
            Long result = stringRedisTemplate.execute(deductStockScript, keys, args.toArray(new String[0]));
            if (result != null && result < 0) {
                int index = (int) (-result - 1);
                throw new RuntimeException("库存不足: " + submitDTO.getDetails().get(index).getDishId());
            }
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

            // 同步扣减数据库库存（仅对在售菜品）
            for (OrderDetail detail : activeDetails) {
                int affectedRows = dishMapper.deductStock(detail.getDishId(), detail.getQuantity());
                if (affectedRows == 0) {
                    throw new RuntimeException("数据库库存同步异常，菜品ID: " + detail.getDishId());
                }
            }

            // 推送厨房看板（仅包含在售菜品）
            try {
                String orderJson = objectMapper.writeValueAsString(buildOrderVO(order, activeDetails));
                kitchenBoardWebSocketHandler.sendMessage("{\"type\":\"NEW_ORDER\",\"order\":" + orderJson + "}");
            } catch (Exception ignored) {
                // WebSocket 推送失败不影响主流程
            }

            // 发送延迟消息到RabbitMQ，30分钟后未支付自动取消
            if (rabbitTemplate != null) {
                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.ORDER_DELAY_EXCHANGE,
                        RabbitMQConfig.ORDER_DELAY_ROUTING_KEY,
                        order.getId().toString()
                );
            }

            return orderNo;
        } catch (Exception e) {
            if (!keys.isEmpty()) {
                stringRedisTemplate.execute(returnStockScript, keys, args.toArray(new String[0]));
            }
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
        if (order.getPayTime() != null) {
            throw new RuntimeException("该订单已支付，请勿重复操作");
        }
        order.setPaymentTradeNo("SIM_" + System.currentTimeMillis());
        order.setPayTime(LocalDateTime.now());
        order.setUpdateTime(LocalDateTime.now());
        if (order.getStatus() == OrderStatusEnum.SERVED.getCode()) {
            order.setStatus(OrderStatusEnum.PAID.getCode());
        }
        this.updateById(order);

        // 合并支付所有子订单（加菜订单）
        List<Order> childOrders = this.lambdaQuery()
                .eq(Order::getParentOrderId, orderId)
                .list();
        int childIndex = 0;
        for (Order child : childOrders) {
            if (child.getStatus() == OrderStatusEnum.ORDERED.getCode()
                    || child.getStatus() == OrderStatusEnum.SERVED.getCode()) {
                childIndex++;
                child.setPaymentTradeNo(order.getPaymentTradeNo() + "_" + childIndex);
                child.setPayTime(order.getPayTime());
                child.setUpdateTime(LocalDateTime.now());
                if (child.getStatus() == OrderStatusEnum.SERVED.getCode()) {
                    child.setStatus(OrderStatusEnum.PAID.getCode());
                }
                this.updateById(child);
            }
        }
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

        // 只有未出餐的订单撤销才返还库存
        boolean shouldReturnStock = order.getStatus() == OrderStatusEnum.ORDERED.getCode();

        order.setStatus(OrderStatusEnum.CANCELLED.getCode());
        order.setCancelReason("用户/管理员撤销");
        order.setUpdateTime(LocalDateTime.now());
        this.updateById(order);

        if (shouldReturnStock) {
            returnStockForOrder(orderId);
        }

        // 级联取消所有子订单（加菜订单）
        List<Order> childOrders = this.lambdaQuery()
                .eq(Order::getParentOrderId, orderId)
                .list();
        for (Order child : childOrders) {
            if (child.getStatus() == OrderStatusEnum.ORDERED.getCode()
                    || child.getStatus() == OrderStatusEnum.SERVED.getCode()) {
                // 子订单未出餐才返还库存
                if (child.getStatus() == OrderStatusEnum.ORDERED.getCode()) {
                    returnStockForOrder(child.getId());
                }
                child.setStatus(OrderStatusEnum.CANCELLED.getCode());
                child.setCancelReason("父订单已撤销");
                child.setUpdateTime(LocalDateTime.now());
                this.updateById(child);
            }
        }
    }

    /**
     * 返还指定订单的库存（Redis + DB同步）
     */
    private void returnStockForOrder(Long orderId) {
        List<OrderDetail> details = orderDetailService.lambdaQuery().eq(OrderDetail::getOrderId, orderId).list();
        if (details.isEmpty()) return;

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

        stringRedisTemplate.execute(returnStockScript, keys, args.toArray(new String[0]));
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
                    .filter(d -> d.getPrice().compareTo(BigDecimal.ZERO) > 0) // 过滤已下架菜品
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
        // 若顾客已提前支付，出餐后自动流转到 PAID
        if (order.getPayTime() != null) {
            order.setStatus(OrderStatusEnum.PAID.getCode());
        } else {
            order.setStatus(OrderStatusEnum.SERVED.getCode());
        }
        order.setCompleteTime(LocalDateTime.now());
        order.setUpdateTime(LocalDateTime.now());
        this.updateById(order);

        customerWebSocketHandler.sendMessageToUser(order.getUserId(), "{\"type\":\"ORDER_SERVED\",\"message\":\"您的订单已出餐，请取餐\"}");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addDish(Long orderId, AddDishDTO addDishDTO) {
        Order originalOrder = this.getById(orderId);
        if (originalOrder == null) {
            throw new RuntimeException("订单不存在");
        }
        if (originalOrder.getStatus() != OrderStatusEnum.ORDERED.getCode()
                && originalOrder.getStatus() != OrderStatusEnum.SERVED.getCode()) {
            throw new RuntimeException("当前状态不可加菜");
        }

        List<String> keys = new ArrayList<>();
        List<String> args = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;
        List<OrderDetail> newDetails = new ArrayList<>();
        List<OrderDetail> activeDetails = new ArrayList<>(); // 在售菜品，需扣库存

        for (OrderDetailDTO detailDTO : addDishDTO.getDetails()) {
            Long dishId = detailDTO.getDishId();

            Dish dish = dishMapper.selectById(dishId);
            if (dish == null) {
                throw new RuntimeException("菜品不存在: " + dishId);
            }

            OrderDetail detail = new OrderDetail();
            detail.setDishId(dishId);
            detail.setQuantity(detailDTO.getQuantity());
            detail.setIsAdded(OrderDetailAddedEnum.ADDED.getCode());
            detail.setCreateTime(LocalDateTime.now());

            if (dish.getStatus() != null && dish.getStatus() == 1) {
                // 在售菜品：正常计算
                String key = STOCK_PREFIX + dishId;
                if (Boolean.FALSE.equals(stringRedisTemplate.hasKey(key))) {
                    stringRedisTemplate.opsForValue().set(key, String.valueOf(dish.getDailyStock()));
                }
                keys.add(key);
                args.add(String.valueOf(detailDTO.getQuantity()));

                detail.setDishName(dish.getName());
                detail.setPrice(dish.getPrice());
                totalAmount = totalAmount.add(dish.getPrice().multiply(new BigDecimal(detailDTO.getQuantity())));
                activeDetails.add(detail);
            } else {
                // 已下架菜品：不计金额、不扣库存、标记名称
                detail.setDishName(dish.getName() + "（已下架）");
                detail.setPrice(BigDecimal.ZERO);
            }
            newDetails.add(detail);
        }

        // Lua脚本扣减库存（仅对在售菜品）
        if (!keys.isEmpty()) {
            Long result = stringRedisTemplate.execute(deductStockScript, keys, args.toArray(new String[0]));
            if (result != null && result < 0) {
                throw new RuntimeException("库存不足，加菜失败");
            }
        }

        try {
            // 创建新订单（独立于原订单，相同座位号）
            String orderNo = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            Order newOrder = new Order();
            newOrder.setOrderNo(orderNo);
            newOrder.setUserId(originalOrder.getUserId());
            newOrder.setSeatNumber(originalOrder.getSeatNumber());
            newOrder.setTotalAmount(totalAmount);
            newOrder.setStatus(OrderStatusEnum.ORDERED.getCode());
            newOrder.setParentOrderId(originalOrder.getId());
            newOrder.setRemark("加菜（原订单#" + originalOrder.getOrderNo() + "）");
            newOrder.setCreateTime(LocalDateTime.now());
            newOrder.setUpdateTime(LocalDateTime.now());
            this.save(newOrder);

            for (OrderDetail detail : newDetails) {
                detail.setOrderId(newOrder.getId());
            }
            orderDetailService.saveBatch(newDetails);

            // 同步扣减数据库库存（仅对在售菜品）
            for (OrderDetail detail : activeDetails) {
                int affectedRows = dishMapper.deductStock(detail.getDishId(), detail.getQuantity());
                if (affectedRows == 0) {
                    throw new RuntimeException("数据库库存同步异常，菜品ID: " + detail.getDishId());
                }
            }

            // 推送新订单到厨房看板（仅包含在售菜品）
            sendNewOrderToKitchenBoard(newOrder, activeDetails);
        } catch (Exception e) {
            if (!keys.isEmpty()) {
                stringRedisTemplate.execute(returnStockScript, keys, args.toArray(new String[0]));
            }
            throw e;
        }
    }

    /**
     * 构建 OrderVO
     */
    private OrderVO buildOrderVO(Order order, List<OrderDetail> details) {
        OrderVO vo = new OrderVO();
        BeanUtils.copyProperties(order, vo);
        vo.setDetails(details);
        return vo;
    }

    /**
     * 构建 OrderVO 并推送到厨房看板 WebSocket
     */
    private void sendNewOrderToKitchenBoard(Order order, List<OrderDetail> details) {
        try {
            OrderVO vo = buildOrderVO(order, details);
            String orderJson = objectMapper.writeValueAsString(vo);
            String message = "{\"type\":\"NEW_ORDER\",\"order\":" + orderJson + "}";
            kitchenBoardWebSocketHandler.sendMessage(message);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public Page<OrderVO> listUserOrders(Integer page, Integer size) {
        Long userId = UserContext.getUserId();
        Page<Order> orderPage = new Page<>(page, size);
        this.lambdaQuery()
                .eq(Order::getUserId, userId)
                .isNull(Order::getParentOrderId)
                .orderByDesc(Order::getCreateTime)
                .page(orderPage);

        Page<OrderVO> voPage = new Page<>(page, size, orderPage.getTotal());
        if (orderPage.getRecords().isEmpty()) {
            return voPage;
        }

        List<Long> parentIds = orderPage.getRecords().stream().map(Order::getId).toList();

        // 查询所有子订单（加菜订单）
        List<Order> childOrders = this.lambdaQuery()
                .in(Order::getParentOrderId, parentIds)
                .list();

        // 合并所有订单ID（父 + 子）
        List<Long> allOrderIds = new ArrayList<>(parentIds);
        childOrders.forEach(c -> allOrderIds.add(c.getId()));

        List<OrderDetail> allDetails = orderDetailService.lambdaQuery()
                .in(OrderDetail::getOrderId, allOrderIds)
                .list();

        List<OrderVO> voList = orderPage.getRecords().stream().map(order -> {
            OrderVO vo = new OrderVO();
            BeanUtils.copyProperties(order, vo);
            // 合并父订单 + 子订单的菜品明细
            List<Long> childIds = childOrders.stream()
                    .filter(c -> c.getParentOrderId().equals(order.getId()))
                    .map(Order::getId)
                    .toList();
            List<Long> mergedOrderIds = new ArrayList<>();
            mergedOrderIds.add(order.getId());
            mergedOrderIds.addAll(childIds);
            List<OrderDetail> details = allDetails.stream()
                    .filter(d -> mergedOrderIds.contains(d.getOrderId()))
                    .toList();
            vo.setDetails(details);

            // 合并总金额
            BigDecimal childTotal = childOrders.stream()
                    .filter(c -> c.getParentOrderId().equals(order.getId()))
                    .map(Order::getTotalAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            vo.setTotalAmount(order.getTotalAmount().add(childTotal));

            // 合并状态：所有子订单都完成才算已上菜/已结账
            List<Order> orderChildren = childOrders.stream()
                    .filter(c -> c.getParentOrderId().equals(order.getId()))
                    .toList();
            vo.setStatus(computeMergedStatus(order, orderChildren));
            return vo;
        }).toList();

        voPage.setRecords(voList);
        return voPage;
    }

    @Override
    public OrderDetailVO getUserOrderDetail(Long orderId) {
        Long userId = UserContext.getUserId();
        Order order = this.lambdaQuery()
                .eq(Order::getId, orderId)
                .eq(Order::getUserId, userId)
                .one();
        if (order == null) {
            throw new RuntimeException("订单不存在");
        }

        OrderDetailVO vo = new OrderDetailVO();
        BeanUtils.copyProperties(order, vo);

        // 查询子订单（加菜订单）
        List<Order> childOrders = this.lambdaQuery()
                .eq(Order::getParentOrderId, orderId)
                .list();

        List<Long> allOrderIds = new ArrayList<>();
        allOrderIds.add(orderId);
        childOrders.forEach(c -> allOrderIds.add(c.getId()));

        List<OrderDetail> details = orderDetailService.lambdaQuery()
                .in(OrderDetail::getOrderId, allOrderIds)
                .list();
        vo.setDetails(details);

        // 合并总金额
        BigDecimal childTotal = childOrders.stream()
                .map(Order::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        vo.setTotalAmount(order.getTotalAmount().add(childTotal));

        // 合并状态：所有子订单都完成才算已上菜/已结账
        vo.setStatus(computeMergedStatus(order, childOrders));

        vo.setAvailableActions(getAvailableActions(order, childOrders));
        return vo;
    }

    /**
     * 计算父子订单的合并状态
     * 规则：任一取消→取消；全部至少SERVED→SERVED；全部PAID→PAID；否则→ORDERED
     */
    private int computeMergedStatus(Order parentOrder, List<Order> childOrders) {
        // 任一下单被取消 → 整体取消
        if (parentOrder.getStatus() == OrderStatusEnum.CANCELLED.getCode()) {
            return OrderStatusEnum.CANCELLED.getCode();
        }
        for (Order child : childOrders) {
            if (child.getStatus() == OrderStatusEnum.CANCELLED.getCode()) {
                return OrderStatusEnum.CANCELLED.getCode();
            }
        }

        // 全部PAID → PAID
        boolean allPaid = parentOrder.getStatus() == OrderStatusEnum.PAID.getCode();
        if (allPaid) {
            for (Order child : childOrders) {
                if (child.getStatus() != OrderStatusEnum.PAID.getCode()) {
                    allPaid = false;
                    break;
                }
            }
        }
        if (allPaid) {
            return OrderStatusEnum.PAID.getCode();
        }

        // 全部至少SERVED → SERVED
        boolean allServed = parentOrder.getStatus() == OrderStatusEnum.SERVED.getCode()
                || parentOrder.getStatus() == OrderStatusEnum.PAID.getCode();
        if (allServed) {
            for (Order child : childOrders) {
                int cs = child.getStatus();
                if (cs != OrderStatusEnum.SERVED.getCode() && cs != OrderStatusEnum.PAID.getCode()) {
                    allServed = false;
                    break;
                }
            }
        }
        if (allServed) {
            return OrderStatusEnum.SERVED.getCode();
        }

        // 其余情况 → ORDERED
        return OrderStatusEnum.ORDERED.getCode();
    }

    @Override
    public OrderDetailVO getAdminOrderDetail(Long orderId) {
        Order order = this.getById(orderId);
        if (order == null) {
            throw new RuntimeException("订单不存在");
        }

        OrderDetailVO vo = new OrderDetailVO();
        BeanUtils.copyProperties(order, vo);

        List<OrderDetail> details = orderDetailService.lambdaQuery()
                .eq(OrderDetail::getOrderId, orderId)
                .list();
        vo.setDetails(details);
        vo.setAvailableActions(getAvailableActions(order));
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
                    .filter(d -> d.getPrice().compareTo(BigDecimal.ZERO) > 0) // 过滤已下架菜品
                    .toList();
            vo.setDetails(details);
            return vo;
        }).toList();

        voPage.setRecords(voList);
        return voPage;
    }

    /**
     * 根据订单状态计算可操作按钮列表（管理员用，单订单）
     * @param order 订单对象
     * @return 可用操作列表
     */
    private List<String> getAvailableActions(Order order) {
        int status = order.getStatus();
        if (status == OrderStatusEnum.ORDERED.getCode()) {
            if (order.getPayTime() != null) {
                return List.of("ADD_DISH");
            }
            return Arrays.asList("ADD_DISH", "PAY");
        } else if (status == OrderStatusEnum.SERVED.getCode()) {
            return Arrays.asList("ADD_DISH", "PAY");
        } else if (status == OrderStatusEnum.PAID.getCode()) {
            boolean hasReviewed = reviewMapper.selectCount(
                    new LambdaQueryWrapper<Review>()
                            .eq(Review::getOrderId, order.getId())
            ) > 0;
            return hasReviewed ? new ArrayList<>() : List.of("REVIEW");
        } else {
            return new ArrayList<>();
        }
    }

    /**
     * 根据订单状态计算可操作按钮列表（C端用，含子订单合并状态）
     * @param parentOrder 父订单对象
     * @param childOrders 子订单列表（加菜订单）
     * @return 可用操作列表
     */
    private List<String> getAvailableActions(Order parentOrder, List<Order> childOrders) {
        int status = parentOrder.getStatus();

        // 已结账 → 只能评价
        if (status == OrderStatusEnum.PAID.getCode()) {
            boolean hasReviewed = reviewMapper.selectCount(
                    new LambdaQueryWrapper<Review>()
                            .eq(Review::getOrderId, parentOrder.getId())
            ) > 0;
            return hasReviewed ? new ArrayList<>() : List.of("REVIEW");
        }

        // 已取消 → 无操作
        if (status == OrderStatusEnum.CANCELLED.getCode()) {
            return new ArrayList<>();
        }

        // ORDERED 或 SERVED：可加菜 + 可结账
        // 提前支付的订单不显示结账
        List<String> actions = new ArrayList<>();
        actions.add("ADD_DISH");
        if (parentOrder.getPayTime() == null) {
            actions.add("PAY");
        }
        return actions;
    }
}
