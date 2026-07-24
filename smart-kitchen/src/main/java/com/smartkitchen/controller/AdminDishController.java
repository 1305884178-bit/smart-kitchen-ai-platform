package com.smartkitchen.controller;

import com.smartkitchen.common.Result;
import com.smartkitchen.entity.Dish;
import com.smartkitchen.service.DishService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 管理端菜品管理控制器
 */
@RestController
@RequestMapping("/api/admin/dish")
public class AdminDishController {

    @Autowired
    private DishService dishService;

    /**
     * 新增菜品
     * @param dish 菜品实体
     * @return 返回操作结果
     */
    @PostMapping("/create")
    public Result<Object> createDish(@RequestBody Dish dish) {
        dishService.save(dish);
        return Result.success();
    }

    /**
     * 更新菜品信息
     * @param id 菜品ID
     * @param dish 菜品实体
     * @return 返回操作结果
     */
    @PutMapping("/update/{id}")
    public Result<Object> updateDish(@PathVariable("id") Long id, @RequestBody Dish dish) {
        dish.setId(id);
        dishService.updateById(dish);
        return Result.success();
    }

    /**
     * 删除菜品
     * @param id 菜品ID
     * @return 返回操作结果
     */
    @DeleteMapping("/delete/{id}")
    public Result<Object> deleteDish(@PathVariable("id") Long id) {
        dishService.removeById(id);
        return Result.success();
    }

    /**
     * 查询指定菜品详情
     * @param id 菜品ID
     * @return 返回菜品详细信息
     */
    @GetMapping("/detail/{id}")
    public Result<Dish> getDishDetail(@PathVariable("id") Long id) {
        Dish dish = dishService.getById(id);
        return Result.success(dish);
    }
}
