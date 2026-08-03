package com.smartkitchen.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartkitchen.entity.Review;
import com.smartkitchen.mapper.ReviewMapper;
import com.smartkitchen.service.ReviewService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 评价服务实现类
 */
@Service
public class ReviewServiceImpl extends ServiceImpl<ReviewMapper, Review> implements ReviewService {

    @Override
    public List<Review> listByScore(Integer score) {
        QueryWrapper<Review> wrapper = new QueryWrapper<>();
        if (score != null) {
            wrapper.eq("score", score);
        }
        wrapper.orderByDesc("create_time");
        return this.list(wrapper);
    }
}
