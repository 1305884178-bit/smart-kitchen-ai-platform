package com.smartkitchen.service.impl;

import com.smartkitchen.config.KitchenBoardWebSocketHandler;
import com.smartkitchen.config.CustomerWebSocketHandler;
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
import com.smartkitchen.common.CancelReasonEnum;
import com.smartkitchen.mapper.DishMapper;
import com.smartkitchen.mapper.OrderMapper;
import com.smartkitchen.mapper.ReviewMapper;
import com.smartkitchen.mq.OrderTimeoutMessageSender;
import com.smartkitchen.service.OrderDetailService;
import com.smartkitchen.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Order> implements OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderServiceImpl.class);

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private OrderMapper orderMapper;

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

    @Autowired
    private OrderTimeoutMessageSender orderTimeoutMessageSender;

    @Value("${smart-kitchen.order.pay-timeout-ms:900000}")
    private long payTimeoutMs;

    private static final String STOCK_PREFIX = "dish:stock:";

    /**
     * deduct_stock.lua / return_stock.lua 的 ARGV 约定（n = KEYS 数量）：
     * ARGV[1..n] = 数量（扣减或返还）；ARGV[n+1..2n] = 对应初值
     * （扣减时为 dish.dailyStock，供 key 不存在时在 Lua 内初始化；
     * 返还时为 MySQL 加完后的 daily_stock，供 key 不存在时写入而非从 0 INCRBY）。
     */
    private Object[] stockLuaArgs(List<String> quantities, List<String> extraStocks) {
        List<String> args = new ArrayList<>(quantities.size() + extraStocks.size());
        args.addAll(quantities);
        args.addAll(extraStocks);
        return args.toArray(new String[0]);
    }

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
        List<String> quantities = new ArrayList<>();
        List<String> initStocks = new ArrayList<>();

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
                // 在售菜品：正常计算；key 初始化交给 Lua，避免 hasKey+set 竞态超卖
                keys.add(STOCK_PREFIX + dishId);
                quantities.add(String.valueOf(detailDTO.getQuantity()));
                initStocks.add(String.valueOf(dish.getDailyStock()));

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
            Long result = stringRedisTemplate.execute(deductStockScript, keys, stockLuaArgs(quantities, initStocks));
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

            // 先付后做：下单不推厨房看板，支付成功后才推 NEW_ORDER。
            // 事务提交后（afterCommit）发送支付超时延迟消息；MQ 异常不影响下单成功，
            // 故发送必须发生在事务提交之后、且不得进入上面的 catch 触发库存回滚。
            orderTimeoutMessageSender.sendAfterCommit(order.getId());

            return orderNo;
        } catch (Exception e) {
            if (!keys.isEmpty()) {
                // 回滚时 key 通常已存在，走 INCRBY；若已被 DEL，则写回下单时读到的 DB 库存
                stringRedisTemplate.execute(returnStockScript, keys, stockLuaArgs(quantities, initStocks));
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

        // 支付入口统一按父订单聚合：即使客户端误传加菜子单 ID，也支付该父单下全部待支付项。
        if (order.getParentOrderId() != null) {
            order = this.getById(order.getParentOrderId());
            if (order == null) {
                throw new RuntimeException("原订单不存在");
            }
        }

        // 本次需要合并支付的未支付子订单（加菜订单）：ORDERED/SERVED 且 pay_time 为空
        List<Order> childOrders = this.lambdaQuery()
                .eq(Order::getParentOrderId, order.getId())
                .list();
        List<Order> unpaidChildren = childOrders.stream()
                .filter(c -> c.getPayTime() == null)
                .filter(c -> c.getStatus() == OrderStatusEnum.ORDERED.getCode()
                        || c.getStatus() == OrderStatusEnum.SERVED.getCode())
                .toList();

        // 应用层预校验用于友好报错，并发最终裁决由 SQL 条件更新完成。
        // 父单已经结账时，仍可能存在待补付的加菜子单，此时必须允许继续走补付。
        boolean parentPayable = order.getStatus() == OrderStatusEnum.SERVED.getCode()
                || order.getStatus() == OrderStatusEnum.ORDERED.getCode();
        if (!parentPayable && unpaidChildren.isEmpty()) {
            throw new RuntimeException("当前状态不可支付");
        }

        // 父单已支付但仍有未支付子单时允许再支付（只给未支付子单写 pay_time），否则拒绝重复支付
        boolean parentNeedPay = order.getPayTime() == null && parentPayable;
        if (!parentNeedPay && unpaidChildren.isEmpty()) {
            throw new RuntimeException("该订单已支付，请勿重复操作");
        }

        String paymentTradeNo = "SIM_" + System.currentTimeMillis();
        LocalDateTime payTime = LocalDateTime.now();

        // 先付后做：本次支付成功且状态为 ORDERED（已支付待出餐）的订单，事务提交后推厨房看板 NEW_ORDER
        List<Order> boardPushOrders = new ArrayList<>();

        if (parentNeedPay) {
            int affected = orderMapper.markPaidIfUnpaid(order.getId(), paymentTradeNo, payTime,
                    OrderStatusEnum.ORDERED.getCode(), OrderStatusEnum.SERVED.getCode());
            if (affected == 0) {
                // 并发下另一事务已先完成支付/状态变更（含与超时取消竞争，超时单已被取消时报错）
                throw new RuntimeException("订单状态已变更或已支付，请刷新后重试");
            }
            order.setPayTime(payTime);
            if (order.getStatus() == OrderStatusEnum.ORDERED.getCode()) {
                boardPushOrders.add(order);
            }
        }

        int childIndex = 0;
        for (Order child : unpaidChildren) {
            childIndex++;
            int childAffected = orderMapper.markPaidIfUnpaid(child.getId(),
                    paymentTradeNo + "_" + childIndex, payTime,
                    OrderStatusEnum.ORDERED.getCode(), OrderStatusEnum.SERVED.getCode());
            if (childAffected == 0) {
                throw new RuntimeException("子订单状态已变更，请刷新后重试");
            }
            child.setPayTime(payTime);
            if (child.getStatus() == OrderStatusEnum.ORDERED.getCode()) {
                boardPushOrders.add(child);
            }
        }

        // 支付成功才推厨房看板；afterCommit 推送，避免事务回滚产生脏卡片
        if (!boardPushOrders.isEmpty()) {
            runAfterCommit(() -> boardPushOrders.forEach(o ->
                    sendNewOrderToKitchenBoard(o, loadActiveDetails(o.getId()))));
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelOrder(Long orderId) {
        Order order = this.getById(orderId);
        if (order == null) {
            throw new RuntimeException("订单不存在");
        }
        // 应用层预校验用于友好报错，并发最终裁决由 SQL 条件更新完成
        if (order.getStatus() != OrderStatusEnum.ORDERED.getCode() && order.getStatus() != OrderStatusEnum.SERVED.getCode()) {
            throw new RuntimeException("当前状态不可撤销");
        }
        if (order.getPayTime() != null) {
            throw new RuntimeException("订单已支付，不可撤销");
        }

        // 只有未出餐的订单撤销才返还库存
        boolean shouldReturnStock = order.getStatus() == OrderStatusEnum.ORDERED.getCode();

        int affected = orderMapper.cancelIfActive(orderId, "用户/管理员撤销",
                OrderStatusEnum.ORDERED.getCode(), OrderStatusEnum.SERVED.getCode(), OrderStatusEnum.CANCELLED.getCode());
        if (affected == 0) {
            throw new RuntimeException("订单状态已变更，请刷新后重试");
        }

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
                int childAffected = orderMapper.cancelIfActive(child.getId(), "父订单已撤销",
                        OrderStatusEnum.ORDERED.getCode(), OrderStatusEnum.SERVED.getCode(), OrderStatusEnum.CANCELLED.getCode());
                if (childAffected == 0) {
                    // 子订单被并发操作抢先变更，由对方操作负责其库存语义，跳过即可
                    continue;
                }
                // 子订单未出餐才返还库存
                if (child.getStatus() == OrderStatusEnum.ORDERED.getCode()) {
                    returnStockForOrder(child.getId());
                }
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelOrderForTimeout(Long orderId) {
        Order order = this.getById(orderId);
        if (order == null) {
            return;
        }
        // 仅未支付的 ORDERED 单参与超时取消；SERVED / 已支付 / 终态一律不动
        if (order.getStatus() != OrderStatusEnum.ORDERED.getCode() || order.getPayTime() != null) {
            return;
        }

        int affected = orderMapper.cancelIfUnpaid(orderId, CancelReasonEnum.PAY_TIMEOUT.getCode(),
                OrderStatusEnum.ORDERED.getCode(), OrderStatusEnum.CANCELLED.getCode());
        if (affected == 0) {
            // 与支付并发：支付已抢先登记 pay_time，视为支付胜出，静默跳过（消费端 ACK，不进 DLQ）
            log.info("订单超时取消跳过：订单已被并发支付或变更，orderId={}", orderId);
            return;
        }
        log.info("订单支付超时自动取消，orderId={}", orderId);
        returnStockForOrder(orderId);

        // 父单超时可级联未支付子单（子单无下级，对子单调用时循环自然为空）
        List<Order> childOrders = this.lambdaQuery()
                .eq(Order::getParentOrderId, orderId)
                .list();
        for (Order child : childOrders) {
            if (child.getStatus() == OrderStatusEnum.ORDERED.getCode() && child.getPayTime() == null) {
                int childAffected = orderMapper.cancelIfUnpaid(child.getId(), CancelReasonEnum.PAY_TIMEOUT.getCode(),
                        OrderStatusEnum.ORDERED.getCode(), OrderStatusEnum.CANCELLED.getCode());
                if (childAffected == 0) {
                    // 子单被并发支付/变更抢先，由对方操作负责其语义，跳过即可
                    continue;
                }
                log.info("父单超时级联取消未支付子单，childOrderId={}", child.getId());
                returnStockForOrder(child.getId());
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
        List<String> quantities = new ArrayList<>();
        List<String> afterStocks = new ArrayList<>();
        for (OrderDetail detail : details) {
            // 先改 MySQL 再改 Redis；SQL 原子加库存，避免并发返还互相覆盖
            int affected = dishMapper.addStock(detail.getDishId(), detail.getQuantity());
            if (affected == 0) {
                continue;
            }
            Dish updated = dishMapper.selectById(detail.getDishId());
            keys.add(STOCK_PREFIX + detail.getDishId());
            quantities.add(String.valueOf(detail.getQuantity()));
            afterStocks.add(String.valueOf(updated.getDailyStock()));
        }

        if (!keys.isEmpty()) {
            stringRedisTemplate.execute(returnStockScript, keys, stockLuaArgs(quantities, afterStocks));
        }
    }

    @Override
    public List<OrderVO> getOrderedOrders() {
        // 先付后做：厨房看板只展示已支付待出餐（ORDERED 且 pay_time 非空）
        List<Order> orders = this.lambdaQuery()
                .eq(Order::getStatus, OrderStatusEnum.ORDERED.getCode())
                .isNotNull(Order::getPayTime)
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
        // 应用层预校验用于友好报错，并发最终裁决由 SQL 条件更新完成
        if (order.getStatus() != OrderStatusEnum.ORDERED.getCode()) {
            throw new RuntimeException("当前状态不可操作出餐");
        }
        // 出餐只改变制作/用餐状态。支付由 pay_time 独立表达，顾客结束用餐后才关闭为 PAID。
        int affected = orderMapper.serveIfOrdered(orderId, OrderStatusEnum.SERVED.getCode(),
                LocalDateTime.now(), OrderStatusEnum.ORDERED.getCode());
        if (affected == 0) {
            throw new RuntimeException("订单状态已变更，请刷新后重试");
        }

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
        // 先付后做：未支付父单禁止加菜
        if (originalOrder.getPayTime() == null) {
            throw new RuntimeException("订单未支付，请先完成支付后再加菜");
        }

        List<String> keys = new ArrayList<>();
        List<String> quantities = new ArrayList<>();
        List<String> initStocks = new ArrayList<>();
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
                // 在售菜品：正常计算；key 初始化交给 Lua，避免 hasKey+set 竞态超卖
                keys.add(STOCK_PREFIX + dishId);
                quantities.add(String.valueOf(detailDTO.getQuantity()));
                initStocks.add(String.valueOf(dish.getDailyStock()));

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
            Long result = stringRedisTemplate.execute(deductStockScript, keys, stockLuaArgs(quantities, initStocks));
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

            // 先付后做：子单不推看板，事务提交后发该子单的支付超时延迟消息，支付成功才推 NEW_ORDER
            orderTimeoutMessageSender.sendAfterCommit(newOrder.getId());
        } catch (Exception e) {
            if (!keys.isEmpty()) {
                stringRedisTemplate.execute(returnStockScript, keys, stockLuaArgs(quantities, initStocks));
            }
            throw e;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void finishMeal(Long orderId) {
        Order parentOrder = this.getById(orderId);
        if (parentOrder == null) {
            throw new RuntimeException("订单不存在");
        }
        if (parentOrder.getParentOrderId() != null) {
            throw new RuntimeException("请从原订单确认结束用餐");
        }
        if (parentOrder.getStatus() == OrderStatusEnum.PAID.getCode()) {
            throw new RuntimeException("订单已结束，请勿重复操作");
        }

        List<Order> childOrders = this.lambdaQuery()
                .eq(Order::getParentOrderId, parentOrder.getId())
                .list();
        List<Order> activeOrders = new ArrayList<>();
        activeOrders.add(parentOrder);
        childOrders.stream()
                .filter(child -> child.getStatus() != OrderStatusEnum.CANCELLED.getCode())
                .forEach(activeOrders::add);

        boolean allPaidAndServed = activeOrders.stream().allMatch(order ->
                order.getPayTime() != null && order.getStatus() == OrderStatusEnum.SERVED.getCode());
        if (!allPaidAndServed) {
            throw new RuntimeException("请等待全部菜品出餐并完成支付后再结束用餐");
        }

        for (Order order : activeOrders) {
            int affected = orderMapper.finishMealIfServedAndPaid(order.getId(),
                    OrderStatusEnum.SERVED.getCode(), OrderStatusEnum.PAID.getCode());
            if (affected == 0) {
                throw new RuntimeException("订单状态已变更，请刷新后重试");
            }
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
            // WebSocket 推送失败不影响主流程
            log.warn("厨房看板推送失败，orderId={}", order.getId(), e);
        }
    }

    /**
     * 查询订单的在售菜品明细（过滤已下架菜品，price=0 不推看板）
     */
    private List<OrderDetail> loadActiveDetails(Long orderId) {
        return orderDetailService.lambdaQuery()
                .eq(OrderDetail::getOrderId, orderId)
                .list()
                .stream()
                .filter(d -> d.getPrice().compareTo(BigDecimal.ZERO) > 0)
                .toList();
    }

    /**
     * 事务提交后执行任务；当前无事务时直接执行
     */
    private void runAfterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
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

            // 合并状态：所有未取消子订单均完成才算已上菜/已结账
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

        // 合并状态：所有未取消子订单均完成才算已上菜/已结账
        vo.setStatus(computeMergedStatus(order, childOrders));

        // 待支付金额：只算未支付部分（已支付的父单/子单不重复计入，加菜补付场景只显示加菜金额）
        vo.setPayableAmount(computePayableAmount(order, childOrders));
        vo.setPayDeadline(computePayDeadline(order, childOrders));

        vo.setAvailableActions(getAvailableActions(order, childOrders));
        return vo;
    }

    /**
     * 计算待支付金额：未支付的父单金额 + 未支付子订单（ORDERED/SERVED）金额合计；已全部支付则为 0
     */
    private BigDecimal computePayableAmount(Order parentOrder, List<Order> childOrders) {
        BigDecimal payable = BigDecimal.ZERO;
        if (parentOrder.getPayTime() == null
                && (parentOrder.getStatus() == OrderStatusEnum.ORDERED.getCode()
                || parentOrder.getStatus() == OrderStatusEnum.SERVED.getCode())) {
            payable = payable.add(parentOrder.getTotalAmount());
        }
        for (Order child : childOrders) {
            if (child.getPayTime() == null
                    && (child.getStatus() == OrderStatusEnum.ORDERED.getCode()
                    || child.getStatus() == OrderStatusEnum.SERVED.getCode())) {
                payable = payable.add(child.getTotalAmount());
            }
        }
        return payable;
    }

    /**
     * 取所有待支付订单中最早的创建时间，避免多次加菜时把较早子单的支付窗口延长。
     */
    private LocalDateTime computePayDeadline(Order parentOrder, List<Order> childOrders) {
        List<Order> unpaidOrders = new ArrayList<>();
        if (isAwaitingPayment(parentOrder)) {
            unpaidOrders.add(parentOrder);
        }
        childOrders.stream()
                .filter(this::isAwaitingPayment)
                .forEach(unpaidOrders::add);
        return unpaidOrders.stream()
                .map(Order::getCreateTime)
                .filter(java.util.Objects::nonNull)
                .min(LocalDateTime::compareTo)
                .map(createTime -> createTime.plusNanos(payTimeoutMs * 1_000_000L))
                .orElse(null);
    }

    private boolean isAwaitingPayment(Order order) {
        return order.getPayTime() == null
                && (order.getStatus() == OrderStatusEnum.ORDERED.getCode()
                || order.getStatus() == OrderStatusEnum.SERVED.getCode());
    }

    /**
     * 计算父子订单的合并状态
     * 规则：父单取消→取消；忽略已取消的加菜子单；其余全部至少 SERVED→SERVED；全部 PAID→PAID；否则→ORDERED
     */
    private int computeMergedStatus(Order parentOrder, List<Order> childOrders) {
        // 任一下单被取消 → 整体取消
        if (parentOrder.getStatus() == OrderStatusEnum.CANCELLED.getCode()) {
            return OrderStatusEnum.CANCELLED.getCode();
        }
        // 全部PAID → PAID
        boolean allPaid = parentOrder.getStatus() == OrderStatusEnum.PAID.getCode();
        if (allPaid) {
            for (Order child : childOrders) {
                if (child.getStatus() == OrderStatusEnum.CANCELLED.getCode()) {
                    continue;
                }
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
                if (cs == OrderStatusEnum.CANCELLED.getCode()) {
                    continue;
                }
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
            // 先付后做：未支付只能支付，已支付才能加菜
            if (order.getPayTime() != null) {
                return List.of("ADD_DISH");
            }
            return List.of("PAY");
        } else if (status == OrderStatusEnum.SERVED.getCode()) {
            return Arrays.asList("ADD_DISH", "PAY");
        } else if (status == OrderStatusEnum.PAID.getCode()) {
            return hasReviewed(order.getId()) ? new ArrayList<>() : List.of("REVIEW");
        } else {
            return new ArrayList<>();
        }
    }

    /**
     * 根据订单状态计算可操作按钮列表（C端用，含子订单合并状态）
     * 支付与订单关闭分离：支付后可继续加菜，确认结束用餐（PAID）后才可评价。
     * @param parentOrder 父订单对象
     * @param childOrders 子订单列表（加菜订单）
     * @return 可用操作列表
     */
    private List<String> getAvailableActions(Order parentOrder, List<Order> childOrders) {
        int status = parentOrder.getStatus();

        // 已取消 → 无操作
        if (status == OrderStatusEnum.CANCELLED.getCode()) {
            return new ArrayList<>();
        }

        // 仍有未支付子单（ORDERED/SERVED 且 pay_time 为空）→ 允许补付
        boolean hasUnpaidChild = childOrders.stream().anyMatch(c -> c.getPayTime() == null
                && (c.getStatus() == OrderStatusEnum.ORDERED.getCode()
                || c.getStatus() == OrderStatusEnum.SERVED.getCode()));

        // 补付未完成时，冻结其他入口：防止顾客在加菜未支付时继续加菜或提前评价。
        if (hasUnpaidChild) {
            return List.of("PAY");
        }
        // 已结束用餐后才开放评价；关闭后的订单组不可再加菜。
        if (status == OrderStatusEnum.PAID.getCode()) {
            return hasReviewed(parentOrder.getId()) ? new ArrayList<>() : List.of("REVIEW");
        }

        // 父单未付款 → 只能支付
        if (parentOrder.getPayTime() == null) {
            return List.of("PAY");
        }

        // 已支付且仍在用餐中 → 可以继续加菜；全部已出餐时也可以确认结束用餐。
        List<String> actions = new ArrayList<>();
        actions.add("ADD_DISH");
        boolean allServed = parentOrder.getStatus() == OrderStatusEnum.SERVED.getCode()
                && childOrders.stream()
                .filter(child -> child.getStatus() != OrderStatusEnum.CANCELLED.getCode())
                .allMatch(child -> child.getStatus() == OrderStatusEnum.SERVED.getCode()
                        && child.getPayTime() != null);
        if (allServed) {
            actions.add("FINISH_MEAL");
        }

        return actions;
    }

    /**
     * 是否已评价（一单一评）
     */
    private boolean hasReviewed(Long orderId) {
        return reviewMapper.selectCount(
                new LambdaQueryWrapper<Review>()
                        .eq(Review::getOrderId, orderId)
        ) > 0;
    }
}
