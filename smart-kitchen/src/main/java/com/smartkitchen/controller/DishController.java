package com.smartkitchen.controller;

import com.smartkitchen.common.Result;
import com.smartkitchen.dto.DishDetailVO;
import com.smartkitchen.entity.Dish;
import com.smartkitchen.service.DishService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 顾客端菜品控制器
 */
@RestController
@RequestMapping("/api/dish")
public class DishController {

    @Autowired
    private DishService dishService;

    /**
     * 获取菜品列表（包含分类、库存、价格信息）
     * @param categoryId 分类ID，可选
     * @return 返回菜品列表数据
     */
    @GetMapping("/list")
    public Result<List<Dish>> list(@RequestParam(required = false) Long categoryId) {
        List<Dish> dishList = dishService.listByCategoryId(categoryId);
        return Result.success(dishList);
    }

    /**
     * 获取菜品详情（含分类名与已有评价列表）
     * @param id 菜品ID
     * @return 返回菜品详情数据
     */
    @GetMapping("/detail/{id}")
    public Result<DishDetailVO> detail(@PathVariable("id") Long id) {
        DishDetailVO vo = dishService.getDishDetail(id);
        if (vo == null) {
            return Result.error(404, "菜品不存在");
        }
        return Result.success(vo);
    }
}
