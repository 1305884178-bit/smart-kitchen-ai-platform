package com.smartkitchen.controller;

import com.smartkitchen.common.Result;
import com.smartkitchen.dto.DishIngredientVO;
import com.smartkitchen.dto.DishInventoryVO;
import com.smartkitchen.service.DishService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 菜品信息代理控制器，供Python AI服务调用
 * 提供库存查询和配料查询接口；不经过用户JWT鉴权，
 * 由 InternalTokenInterceptor 校验服务间内部 token（配置后强制，未配置放行便于本地联调）
 */
@RestController
@RequestMapping("/api/proxy/dish")
public class DishProxyController {

    @Autowired
    private DishService dishService;

    /**
     * 查询指定菜品的实时库存
     * @param dishName 菜品名称（支持模糊匹配）
     * @return 菜品库存信息
     */
    @GetMapping("/inventory")
    public Result<DishInventoryVO> getInventory(@RequestParam String dishName) {
        DishInventoryVO vo = dishService.getInventoryByName(dishName);
        if (vo == null) {
            return Result.error(404, "未找到该菜品");
        }
        return Result.success(vo);
    }

    /**
     * 查询指定菜品的配料和过敏原信息
     * @param dishName 菜品名称（支持模糊匹配）
     * @return 菜品配料信息
     */
    @GetMapping("/ingredients")
    public Result<DishIngredientVO> getIngredients(@RequestParam String dishName) {
        DishIngredientVO vo = dishService.getIngredientsByName(dishName);
        if (vo == null) {
            return Result.error(404, "未找到该菜品");
        }
        return Result.success(vo);
    }
}
