package com.smartkitchen.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartkitchen.dto.OrderDetailDTO;
import com.smartkitchen.dto.OrderSubmitDTO;
import com.smartkitchen.entity.Dish;
import com.smartkitchen.mapper.DishMapper;
import com.smartkitchen.config.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class OrderControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DishMapper dishMapper;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private JwtUtil jwtUtil;

    private Long testDishId;
    private String validToken;

    @BeforeEach
    public void setup() {
        // 生成测试用的 Token
        validToken = jwtUtil.generateToken(1L, "USER", "test_openid");

        // 模拟 Redis 的 opsForValue
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.hasKey(anyString())).thenReturn(true);
        when(stringRedisTemplate.execute(any(RedisScript.class), any(), any())).thenReturn(1L);

        // 插入测试菜品
        Dish dish = new Dish();
        dish.setName("测试红烧肉");
        dish.setPrice(new BigDecimal("58.00"));
        dish.setDailyStock(10);
        dish.setCategoryId(1L);
        dishMapper.insert(dish);
        testDishId = dish.getId();
    }

    @Test
    public void testSubmitOrder() throws Exception {
        OrderSubmitDTO dto = new OrderSubmitDTO();
        dto.setSeatNumber("A01");
        
        OrderDetailDTO detail = new OrderDetailDTO();
        detail.setDishId(testDishId);
        detail.setQuantity(2);
        dto.setDetails(Collections.singletonList(detail));

        mockMvc.perform(post("/api/order/submit")
                .header("Authorization", "Bearer " + validToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isNotEmpty());
                
        // 验证数据库库存被扣减为 8
        Dish updatedDish = dishMapper.selectById(testDishId);
        assert updatedDish.getDailyStock() == 8;
    }
}
