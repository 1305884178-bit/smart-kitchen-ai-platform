package com.smartkitchen.controller;

import com.smartkitchen.common.Result;
import com.smartkitchen.dto.CategoryDTO;
import com.smartkitchen.entity.Category;
import com.smartkitchen.service.CategoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 菜品分类控制器
 */
@RestController
@RequestMapping("/api/admin/dish/category")
public class CategoryController {

    @Autowired
    private CategoryService categoryService;

    /**
     * 获取所有菜品分类列表
     * @return 分类列表
     */
    @GetMapping("/list")
    public Result<List<Category>> list() {
        return Result.success(categoryService.listCategories());
    }

    /**
     * 根据ID获取分类详情
     * @param id 分类ID
     * @return 分类详情
     */
    @GetMapping("/{id}")
    public Result<Category> getById(@PathVariable Long id) {
        Category category = categoryService.getById(id);
        return Result.success(category);
    }

    /**
     * 添加分类
     * @param categoryDTO 分类信息
     * @return 添加结果
     */
    @PostMapping("/add")
    public Result<Void> add(@RequestBody CategoryDTO categoryDTO) {
        categoryService.addCategory(categoryDTO);
        return Result.success();
    }

    /**
     * 更新分类
     * @param categoryDTO 分类信息
     * @return 更新结果
     */
    @PutMapping("/update")
    public Result<Void> update(@RequestBody CategoryDTO categoryDTO) {
        categoryService.updateCategory(categoryDTO);
        return Result.success();
    }

    /**
     * 删除分类
     * @param id 分类ID
     * @return 删除结果
     */
    @DeleteMapping("/delete/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        categoryService.removeById(id);
        return Result.success();
    }
}
