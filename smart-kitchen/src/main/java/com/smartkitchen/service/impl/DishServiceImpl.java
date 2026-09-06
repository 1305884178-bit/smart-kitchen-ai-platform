package com.smartkitchen.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartkitchen.common.DishStatusEnum;
import com.smartkitchen.dto.DishDetailVO;
import com.smartkitchen.dto.DishIngredientVO;
import com.smartkitchen.dto.DishInventoryVO;
import com.smartkitchen.entity.Category;
import com.smartkitchen.entity.Dish;
import com.smartkitchen.entity.OrderDetail;
import com.smartkitchen.entity.Review;
import com.smartkitchen.mapper.DishMapper;
import com.smartkitchen.service.CategoryService;
import com.smartkitchen.service.DishService;
import com.smartkitchen.service.OrderDetailService;
import com.smartkitchen.service.ReviewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 菜品服务实现类
 */
@Service
public class DishServiceImpl extends ServiceImpl<DishMapper, Dish> implements DishService {

    private static final Logger log = LoggerFactory.getLogger(DishServiceImpl.class);

    /** 与 deduct_stock.lua / return_stock.lua 同一 key 规则 */
    private static final String STOCK_PREFIX = "dish:stock:";

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private OrderDetailService orderDetailService;

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 根据分类ID查询起售状态的菜品列表
     * @param categoryId 分类ID
     * @return 菜品列表
     */
    @Override
    public List<Dish> listByCategoryId(Long categoryId) {
        LambdaQueryWrapper<Dish> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(categoryId != null, Dish::getCategoryId, categoryId);
        queryWrapper.eq(Dish::getStatus, DishStatusEnum.ON_SALE.getCode());
        queryWrapper.orderByDesc(Dish::getUpdateTime);
        return this.list(queryWrapper);
    }

    /**
     * 管理端查询所有菜品（含已下架）
     */
    @Override
    public List<Dish> listAll(Long categoryId) {
        LambdaQueryWrapper<Dish> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(categoryId != null, Dish::getCategoryId, categoryId);
        queryWrapper.orderByDesc(Dish::getUpdateTime);
        return this.list(queryWrapper);
    }

    /**
     * 根据菜品ID查询菜品详情（含分类名与已有评价）
     * @param dishId 菜品ID
     * @return 菜品详情VO，未找到返回null
     */
    @Override
    public DishDetailVO getDishDetail(Long dishId) {
        Dish dish = this.getById(dishId);
        if (dish == null) {
            return null;
        }

        DishDetailVO vo = new DishDetailVO();
        vo.setId(dish.getId());
        vo.setName(dish.getName());
        vo.setCategoryId(dish.getCategoryId());
        vo.setPrice(dish.getPrice());
        vo.setImage(dish.getImage());
        vo.setStatus(dish.getStatus());
        vo.setDailyStock(dish.getDailyStock());
        vo.setAlertThreshold(dish.getAlertThreshold());
        vo.setIngredients(dish.getIngredients());
        vo.setAllergens(dish.getAllergens());
        vo.setCreateTime(dish.getCreateTime());
        vo.setUpdateTime(dish.getUpdateTime());

        // 填入分类名
        Category category = categoryService.getById(dish.getCategoryId());
        if (category != null) {
            vo.setCategoryName(category.getName());
        }

        // 查询包含该菜品的所有订单明细，获取订单ID列表
        LambdaQueryWrapper<OrderDetail> odWrapper = new LambdaQueryWrapper<>();
        odWrapper.eq(OrderDetail::getDishId, dishId);
        odWrapper.select(OrderDetail::getOrderId);
        List<OrderDetail> orderDetails = orderDetailService.list(odWrapper);
        List<Long> orderIds = orderDetails.stream()
                .map(OrderDetail::getOrderId)
                .distinct()
                .collect(Collectors.toList());

        // 查询这些订单的评价
        List<DishDetailVO.ReviewItem> reviews = Collections.emptyList();
        if (!orderIds.isEmpty()) {
            LambdaQueryWrapper<Review> reviewWrapper = new LambdaQueryWrapper<>();
            reviewWrapper.in(Review::getOrderId, orderIds);
            reviewWrapper.orderByDesc(Review::getCreateTime);
            List<Review> reviewList = reviewService.list(reviewWrapper);
            reviews = reviewList.stream().map(r -> {
                DishDetailVO.ReviewItem item = new DishDetailVO.ReviewItem();
                item.setScore(r.getScore());
                item.setComment(r.getComment());
                item.setCreateTime(r.getCreateTime());
                return item;
            }).collect(Collectors.toList());
        }
        vo.setReviews(reviews);

        return vo;
    }

    /**
     * 根据菜品名称模糊查询菜品库存信息。
     * 库存口径与下单一致：优先读 Redis 库存 key（dish:stock:{dishId}，即 deduct_stock.lua
     * 扣减的同一份数据），miss 时用 MySQL daily_stock 回源写入；Redis 异常降级为 MySQL 值。
     * @param dishName 菜品名称
     * @return 库存VO（dailyStock 为可下单剩余），未找到返回null
     */
    @Override
    public DishInventoryVO getInventoryByName(String dishName) {
        Dish dish = queryByName(dishName);
        if (dish == null) {
            return null;
        }
        DishInventoryVO vo = new DishInventoryVO();
        vo.setName(dish.getName());
        vo.setDailyStock(resolveRemainingStock(dish));
        vo.setStatus(dish.getStatus());
        return vo;
    }

    /**
     * 可下单剩余库存：Redis 命中直接返回；miss 回源 MySQL 并写入（与 Lua 内初始化同值）；
     * Redis 不可用时不影响查询，降级返回 MySQL daily_stock。
     */
    private Integer resolveRemainingStock(Dish dish) {
        String key = STOCK_PREFIX + dish.getId();
        try {
            String cached = stringRedisTemplate.opsForValue().get(key);
            if (cached != null) {
                return Integer.parseInt(cached);
            }
            Integer stock = dish.getDailyStock();
            if (stock != null) {
                stringRedisTemplate.opsForValue().set(key, String.valueOf(stock));
            }
            return stock;
        } catch (Exception e) {
            log.warn("读取 Redis 库存失败，降级为 MySQL daily_stock（dishId={}）: {}", dish.getId(), e.getMessage());
            return dish.getDailyStock();
        }
    }

    /**
     * 根据菜品名称模糊查询菜品配料信息
     * @param dishName 菜品名称
     * @return 配料VO，未找到返回null
     */
    @Override
    public DishIngredientVO getIngredientsByName(String dishName) {
        Dish dish = queryByName(dishName);
        if (dish == null) {
            return null;
        }
        DishIngredientVO vo = new DishIngredientVO();
        vo.setName(dish.getName());
        vo.setIngredients(dish.getIngredients());
        vo.setAllergens(dish.getAllergens());
        return vo;
    }

    /**
     * 根据菜品名称模糊查询菜品实体
     * @param dishName 菜品名称
     * @return 菜品实体，未找到返回null
     */
    private Dish queryByName(String dishName) {
        LambdaQueryWrapper<Dish> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.like(Dish::getName, dishName).last("LIMIT 1");
        return this.getOne(queryWrapper);
    }
}
