package com.smartkitchen.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.smartkitchen.common.PredictionStatusEnum;
import com.smartkitchen.config.UserContext;
import com.smartkitchen.dto.PredictConfirmDTO;
import com.smartkitchen.dto.PredictUpsertDTO;
import com.smartkitchen.entity.Dish;
import com.smartkitchen.entity.PredictionRecord;
import com.smartkitchen.mapper.DishMapper;
import com.smartkitchen.mapper.PredictionRecordMapper;
import com.smartkitchen.service.PredictionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AI 备菜预测业务实现：ai_prediction_record 写入口集中在 Java。
 */
@Service
public class PredictionServiceImpl implements PredictionService {

    @Autowired
    private PredictionRecordMapper predictionRecordMapper;

    @Autowired
    private DishMapper dishMapper;

    @Override
    public void confirmPrediction(PredictConfirmDTO dto) {
        if (dto.getRecordId() == null) {
            throw new IllegalArgumentException("recordId 不能为空");
        }
        PredictionRecord record = predictionRecordMapper.selectById(dto.getRecordId());
        if (record == null) {
            throw new IllegalArgumentException("预测记录不存在：" + dto.getRecordId());
        }
        // confirmed_by 必须取当前登录 ADMIN，忽略前端 body 里的 confirmedBy
        Long adminId = UserContext.getUserId();
        record.setFinalQuantity(dto.getFinalQuantity());
        record.setStatus(PredictionStatusEnum.CONFIRMED.getCode());
        record.setConfirmedBy(adminId);
        // 幂等：重复确认同一值只是再次写入相同值，视为成功
        predictionRecordMapper.updateById(record);
    }

    @Override
    public List<Map<String, Object>> getPredictionResult(String targetDate) {
        QueryWrapper<PredictionRecord> qw = new QueryWrapper<>();
        qw.eq("predict_date", LocalDate.parse(targetDate)).orderByDesc("create_time");
        List<PredictionRecord> records = predictionRecordMapper.selectList(qw);
        if (records.isEmpty()) {
            return new ArrayList<>();
        }
        // 批量取菜名，避免逐条 N+1
        Set<Long> dishIds = records.stream().map(PredictionRecord::getDishId).collect(Collectors.toSet());
        Map<Long, String> dishNames = new HashMap<>();
        for (Dish dish : dishMapper.selectBatchIds(dishIds)) {
            dishNames.put(dish.getId(), dish.getName());
        }
        // 键名保持 snake_case，与旧 Python /ai/predict/result 返回字段对齐（admin-web 自行映射 camelCase）
        List<Map<String, Object>> rows = new ArrayList<>(records.size());
        for (PredictionRecord r : records) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", r.getId());
            row.put("predict_date", r.getPredictDate() == null ? null : r.getPredictDate().toString());
            row.put("dish_id", r.getDishId());
            row.put("dish_name", dishNames.get(r.getDishId()));
            row.put("base_quantity", r.getBaseQuantity());
            row.put("ai_suggest_quantity", r.getAiSuggestQuantity());
            row.put("final_quantity", r.getFinalQuantity());
            row.put("reasoning", r.getReasoning());
            row.put("confidence", r.getConfidence());
            row.put("recent_avg_score", r.getRecentAvgScore());
            row.put("status", r.getStatus());
            row.put("confirmed_by", r.getConfirmedBy());
            row.put("create_time",
                    r.getCreateTime() == null ? null : r.getCreateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            rows.add(row);
        }
        return rows;
    }

    @Override
    public void upsertPrediction(PredictUpsertDTO dto) {
        if (dto.getPredictDate() == null || dto.getDishId() == null) {
            throw new IllegalArgumentException("predict_date 与 dish_id 不能为空");
        }
        LocalDate predictDate = LocalDate.parse(dto.getPredictDate());
        QueryWrapper<PredictionRecord> qw = new QueryWrapper<>();
        qw.eq("predict_date", predictDate).eq("dish_id", dto.getDishId());
        PredictionRecord exist = predictionRecordMapper.selectOne(qw);

        if (exist != null) {
            // 重新预测：覆盖建议字段（与旧 Python upsert 对齐），并明确规则——
            // status 置回 0 待确认、清空 confirmed_by，新建议需重新人工确认，
            // 避免已确认记录被新预测静默覆盖却仍显示「已确认」。
            UpdateWrapper<PredictionRecord> uw = new UpdateWrapper<>();
            uw.eq("id", exist.getId())
                    .set("base_quantity", dto.getBaseQuantity())
                    .set("ai_suggest_quantity", dto.getAiSuggestQuantity())
                    .set("final_quantity", dto.getFinalQuantity())
                    .set("reasoning", dto.getReasoning())
                    .set("confidence", dto.getConfidence())
                    .set("recent_avg_score", dto.getRecentAvgScore())
                    .set("status", PredictionStatusEnum.PENDING.getCode())
                    .set("confirmed_by", null);
            predictionRecordMapper.update(null, uw);
        } else {
            PredictionRecord record = new PredictionRecord();
            record.setPredictDate(predictDate);
            record.setDishId(dto.getDishId());
            record.setBaseQuantity(dto.getBaseQuantity());
            record.setAiSuggestQuantity(dto.getAiSuggestQuantity());
            record.setFinalQuantity(dto.getFinalQuantity());
            record.setReasoning(dto.getReasoning());
            record.setConfidence(dto.getConfidence());
            record.setRecentAvgScore(dto.getRecentAvgScore());
            record.setStatus(PredictionStatusEnum.PENDING.getCode());
            predictionRecordMapper.insert(record);
        }
    }
}
