package com.smartkitchen.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.smartkitchen.config.UserContext;
import com.smartkitchen.dto.PredictConfirmDTO;
import com.smartkitchen.dto.PredictUpsertDTO;
import com.smartkitchen.entity.PredictionRecord;
import com.smartkitchen.mapper.PredictionRecordMapper;
import com.smartkitchen.service.impl.PredictionServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * PredictionServiceImpl 单元测试（mapper 全 mock）。
 * 核心约定：confirmedBy 取 UserContext 当前 ADMIN；upsert 对已有记录覆盖建议并置回待确认。
 */
@ExtendWith(MockitoExtension.class)
public class PredictionServiceImplTest {

    @Mock
    private PredictionRecordMapper predictionRecordMapper;

    @InjectMocks
    private PredictionServiceImpl predictionService;

    @AfterEach
    public void tearDown() {
        UserContext.clear();
    }

    @Test
    public void testConfirmUsesCurrentAdminFromUserContext() {
        PredictionRecord record = new PredictionRecord();
        record.setId(1L);
        record.setStatus(0);
        when(predictionRecordMapper.selectById(1L)).thenReturn(record);

        UserContext.setUserId(7L);
        PredictConfirmDTO dto = new PredictConfirmDTO();
        dto.setRecordId(1L);
        dto.setFinalQuantity(120);
        dto.setConfirmedBy(999L); // 前端伪造值必须被忽略

        predictionService.confirmPrediction(dto);

        ArgumentCaptor<PredictionRecord> captor = ArgumentCaptor.forClass(PredictionRecord.class);
        verify(predictionRecordMapper).updateById(captor.capture());
        PredictionRecord updated = captor.getValue();
        assertEquals(120, updated.getFinalQuantity());
        assertEquals(1, updated.getStatus());
        assertEquals(7L, updated.getConfirmedBy());
    }

    @Test
    public void testConfirmRecordNotFoundThrows() {
        when(predictionRecordMapper.selectById(999L)).thenReturn(null);

        PredictConfirmDTO dto = new PredictConfirmDTO();
        dto.setRecordId(999L);
        dto.setFinalQuantity(10);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> predictionService.confirmPrediction(dto));
        assertTrue(ex.getMessage().contains("预测记录不存在"));
        verify(predictionRecordMapper, never()).updateById(any());
    }

    @Test
    public void testUpsertInsertWhenNotExists() {
        when(predictionRecordMapper.selectOne(any())).thenReturn(null);

        predictionService.upsertPrediction(buildUpsertDTO());

        ArgumentCaptor<PredictionRecord> captor = ArgumentCaptor.forClass(PredictionRecord.class);
        verify(predictionRecordMapper).insert(captor.capture());
        PredictionRecord inserted = captor.getValue();
        assertEquals(0, inserted.getStatus());
        assertEquals(60, inserted.getAiSuggestQuantity());
        verify(predictionRecordMapper, never()).update(any(), any(UpdateWrapper.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testUpsertUpdateResetsToPending() {
        PredictionRecord exist = new PredictionRecord();
        exist.setId(5L);
        exist.setStatus(1); // 已确认记录被重新预测覆盖
        exist.setConfirmedBy(3L);
        when(predictionRecordMapper.selectOne(any())).thenReturn(exist);

        predictionService.upsertPrediction(buildUpsertDTO());

        ArgumentCaptor<UpdateWrapper<PredictionRecord>> captor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(predictionRecordMapper).update(isNull(), captor.capture());
        String sqlSet = captor.getValue().getSqlSet();
        // 覆盖建议字段 + status 置回 0 待确认 + 清空 confirmed_by
        assertTrue(sqlSet.contains("ai_suggest_quantity"));
        assertTrue(sqlSet.contains("final_quantity"));
        assertTrue(sqlSet.contains("status"));
        assertTrue(sqlSet.contains("confirmed_by"));
        verify(predictionRecordMapper, never()).insert(any());
    }

    @Test
    public void testUpsertMissingFieldsRejected() {
        PredictUpsertDTO dto = new PredictUpsertDTO();
        assertThrows(IllegalArgumentException.class, () -> predictionService.upsertPrediction(dto));
        verifyNoInteractions(predictionRecordMapper);
    }

    private PredictUpsertDTO buildUpsertDTO() {
        PredictUpsertDTO dto = new PredictUpsertDTO();
        dto.setPredictDate("2026-08-10");
        dto.setDishId(1L);
        dto.setBaseQuantity(55);
        dto.setAiSuggestQuantity(60);
        dto.setFinalQuantity(60);
        dto.setReasoning("unit test");
        dto.setConfidence(new BigDecimal("0.8"));
        dto.setRecentAvgScore(new BigDecimal("4.5"));
        return dto;
    }
}
