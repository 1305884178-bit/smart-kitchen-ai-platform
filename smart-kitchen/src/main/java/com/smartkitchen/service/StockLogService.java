package com.smartkitchen.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.smartkitchen.dto.StockVO;
import com.smartkitchen.entity.StockLog;

/**
 * 库存变更流水服务接口
 */
public interface StockLogService extends IService<StockLog> {

    /**
     * 查看指定菜品库存及流水（含菜品名称、当前库存、预警阈值）
     * @param dishId 菜品ID
     * @return 库存视图对象
     */
    StockVO getStockView(Long dishId);

    /**
     * 修改指定菜品库存并记录流水
     * @param dishId 菜品ID
     * @param changeQty 变更数量
     */
    void updateStock(Long dishId, Integer changeQty);
}
