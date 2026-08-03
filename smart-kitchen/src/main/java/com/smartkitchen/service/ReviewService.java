package com.smartkitchen.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.smartkitchen.entity.Review;

import java.util.List;

/**
 * 评价服务接口
 */
public interface ReviewService extends IService<Review> {

    /**
     * 按评分筛选评价列表（score为null时返回全部）
     * @param score 评分（可选，1-5）
     * @return 评价列表
     */
    List<Review> listByScore(Integer score);
}
