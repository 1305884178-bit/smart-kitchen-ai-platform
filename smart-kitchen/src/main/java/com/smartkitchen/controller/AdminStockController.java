package com.smartkitchen.controller;

import com.smartkitchen.common.Result;
import com.smartkitchen.dto.StockVO;
import com.smartkitchen.service.StockLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 管理端库存管理控制器
 */
@RestController
@RequestMapping("/api/admin/stock")
public class AdminStockController {

    @Autowired
    private StockLogService stockLogService;

    /**
     * 查看指定菜品库存及流水（含菜品名称、当前库存、预警阈值）
     * @param dishId 菜品ID
     * @return 返回库存信息
     */
    @GetMapping("/view/{dishId}")
    public Result<StockVO> viewStock(@PathVariable("dishId") Long dishId) {
        return Result.success(stockLogService.getStockView(dishId));
    }

    /**
     * 修改指定菜品库存
     * @param dishId 菜品ID
     * @param changeQty 变更数量
     * @return 返回修改结果
     */
    @PutMapping("/update/{dishId}")
    public Result<Object> updateStock(@PathVariable("dishId") Long dishId, @RequestParam("changeQty") Integer changeQty) {
        stockLogService.updateStock(dishId, changeQty);
        return Result.success();
    }
}
