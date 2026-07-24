package com.smartkitchen.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.smartkitchen.common.Result;
import com.smartkitchen.common.StockChangeTypeEnum;
import com.smartkitchen.entity.Dish;
import com.smartkitchen.entity.StockLog;
import com.smartkitchen.service.DishService;
import com.smartkitchen.service.StockLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端库存管理控制器
 */
@RestController
@RequestMapping("/api/admin/stock")
public class AdminStockController {

    @Autowired
    private StockLogService stockLogService;

    @Autowired
    private DishService dishService;

    /**
     * 查看指定菜品库存及流水
     * @param dishId 菜品ID
     * @return 返回库存信息
     */
    @GetMapping("/view/{dishId}")
    public Result<List<StockLog>> viewStock(@PathVariable("dishId") Long dishId) {
        QueryWrapper<StockLog> wrapper = new QueryWrapper<>();
        wrapper.eq("dish_id", dishId).orderByDesc("create_time");
        List<StockLog> logs = stockLogService.list(wrapper);
        return Result.success(logs);
    }

    /**
     * 修改指定菜品库存
     * @param dishId 菜品ID
     * @param changeQty 变更数量
     * @return 返回修改结果
     */
    @PutMapping("/update/{dishId}")
    public Result<Object> updateStock(@PathVariable("dishId") Long dishId, @RequestParam("changeQty") Integer changeQty) {
        Dish dish = dishService.getById(dishId);
        if (dish != null) {
            Integer beforeQty = dish.getDailyStock();
            Integer afterQty = beforeQty + changeQty;

            // 使用 SQL 级别的原子更新，防止并发读写时丢失修改
            UpdateWrapper<Dish> updateWrapper = new UpdateWrapper<>();
            updateWrapper.eq("id", dishId)
                         .setSql("daily_stock = daily_stock + " + changeQty);
            dishService.update(updateWrapper);

            // 记录流水 (此处 beforeQty 和 afterQty 基于更新前快照记录)
            StockLog log = new StockLog();
            log.setDishId(dishId);
            log.setChangeType(StockChangeTypeEnum.MANUAL.getCode());
            log.setChangeQty(changeQty);
            log.setBeforeQty(beforeQty);
            log.setAfterQty(afterQty);
            log.setCreateTime(LocalDateTime.now());
            stockLogService.save(log);
        }
        return Result.success();
    }
}
