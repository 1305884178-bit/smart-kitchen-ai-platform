package com.smartkitchen.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.smartkitchen.dto.ReviewVO;
import com.smartkitchen.entity.Review;

import java.util.List;

/**
 * 评价服务接口
 */
public interface ReviewService extends IService<Review> {

    /**
     * 提交餐后评价（含订单状态校验）
     * @param review 评价实体（不含userId和createTime）
     */
    void submitReview(Review review);

    /**
     * 按评分筛选评价列表（含订单号，score为null时返回全部）
     * @param score 评分（可选，1-5）
     * @return 评价VO列表
     */
    List<ReviewVO> listByScore(Integer score);
}
