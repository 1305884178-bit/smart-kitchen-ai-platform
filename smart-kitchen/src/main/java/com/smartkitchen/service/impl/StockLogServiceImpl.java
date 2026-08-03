package com.smartkitchen.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartkitchen.common.StockChangeTypeEnum;
import com.smartkitchen.dto.StockVO;
import com.smartkitchen.entity.Dish;
import com.smartkitchen.entity.StockLog;
import com.smartkitchen.mapper.StockLogMapper;
import com.smartkitchen.service.DishService;
import com.smartkitchen.service.StockLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 库存变更流水服务实现类
 */
@Service
public class StockLogServiceImpl extends ServiceImpl<StockLogMapper, StockLog> implements StockLogService {

    @Autowired
    private DishService dishService;

    @Override
    public StockVO getStockView(Long dishId) {
        Dish dish = dishService.getById(dishId);
        QueryWrapper<StockLog> wrapper = new QueryWrapper<>();
        wrapper.eq("dish_id", dishId).orderByDesc("create_time");
        List<StockLog> logs = this.list(wrapper);

        StockVO vo = new StockVO();
        vo.setDishId(dishId);
        if (dish != null) {
            vo.setDishName(dish.getName());
            vo.setDailyStock(dish.getDailyStock());
            vo.setAlertThreshold(dish.getAlertThreshold());
        }
        vo.setLogs(logs);
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStock(Long dishId, Integer changeQty) {
        Dish dish = dishService.getById(dishId);
        if (dish == null) {
            return;
        }
        Integer beforeQty = dish.getDailyStock();
        Integer afterQty = beforeQty + changeQty;

        UpdateWrapper<Dish> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("id", dishId)
                     .setSql("daily_stock = daily_stock + " + changeQty);
        dishService.update(updateWrapper);

        StockLog log = new StockLog();
        log.setDishId(dishId);
        log.setChangeType(StockChangeTypeEnum.MANUAL.getCode());
        log.setChangeQty(changeQty);
        log.setBeforeQty(beforeQty);
        log.setAfterQty(afterQty);
        log.setCreateTime(LocalDateTime.now());
        this.save(log);
    }
}
