package com.smartkitchen.controller;

import com.smartkitchen.common.Result;
import org.springframework.web.bind.annotation.*;

/**
 * 管理端库存管理控制器
 */
@RestController
@RequestMapping("/api/admin/stock")
public class AdminStockController {

    /**
     * 查看指定菜品库存
     * @param dishId 菜品ID
     * @return 返回库存信息
     */
    @GetMapping("/view/{dishId}")
    public Result<Object> viewStock(@PathVariable("dishId") Long dishId) {
        return Result.success(null);
    }

    /**
     * 修改指定菜品库存
     * @param dishId 菜品ID
     * @return 返回修改结果
     */
    @PutMapping("/update/{dishId}")
    public Result<Object> updateStock(@PathVariable("dishId") Long dishId) {
        return Result.success(null);
    }
}
