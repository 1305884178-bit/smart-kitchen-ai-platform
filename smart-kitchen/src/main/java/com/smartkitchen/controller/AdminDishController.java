package com.smartkitchen.controller;

import com.smartkitchen.common.Result;
import org.springframework.web.bind.annotation.*;

/**
 * 管理端菜品管理控制器
 */
@RestController
@RequestMapping("/api/admin/dish")
public class AdminDishController {

    /**
     * 新增菜品
     * @return 返回操作结果
     */
    @PostMapping("/create")
    public Result<Object> createDish() {
        return Result.success(null);
    }

    /**
     * 更新菜品信息
     * @param id 菜品ID
     * @return 返回操作结果
     */
    @PutMapping("/update/{id}")
    public Result<Object> updateDish(@PathVariable("id") Long id) {
        return Result.success(null);
    }

    /**
     * 删除菜品
     * @param id 菜品ID
     * @return 返回操作结果
     */
    @DeleteMapping("/delete/{id}")
    public Result<Object> deleteDish(@PathVariable("id") Long id) {
        return Result.success(null);
    }

    /**
     * 查询指定菜品详情
     * @param id 菜品ID
     * @return 返回菜品详细信息
     */
    @GetMapping("/detail/{id}")
    public Result<Object> getDishDetail(@PathVariable("id") Long id) {
        return Result.success(null);
    }
}
