package com.smartkitchen.service;

import com.smartkitchen.dto.CategoryDTO;
import com.smartkitchen.entity.Category;
import com.smartkitchen.mapper.CategoryMapper;
import com.smartkitchen.service.impl.CategoryServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class CategoryServiceTest {

    @Mock
    private CategoryMapper categoryMapper;

    @InjectMocks
    private CategoryServiceImpl categoryService;

    @org.junit.jupiter.api.BeforeEach
    public void setUp() {
        org.springframework.test.util.ReflectionTestUtils.setField(categoryService, "baseMapper", categoryMapper);
    }

    @Test
    public void testAddCategory() {
        CategoryDTO dto = new CategoryDTO();
        dto.setName("热菜");
        dto.setSort(1);

        when(categoryMapper.insert(any(Category.class))).thenReturn(1);

        boolean result = categoryService.addCategory(dto);
        
        assertEquals(true, result);
    }
    
    @Test
    public void testUpdateCategory() {
        CategoryDTO dto = new CategoryDTO();
        dto.setId(1L);
        dto.setName("热菜修改");
        dto.setSort(2);

        when(categoryMapper.updateById(any(Category.class))).thenReturn(1);

        boolean result = categoryService.updateCategory(dto);
        
        assertEquals(true, result);
    }
    
    @Test
    public void testListCategories() {
        Category category = new Category();
        category.setName("热菜");
        when(categoryMapper.selectList(any())).thenReturn(Collections.singletonList(category));
        
        List<Category> list = categoryService.listCategories();
        
        assertNotNull(list);
        assertEquals(1, list.size());
        assertEquals("热菜", list.get(0).getName());
    }

    @Test
    public void testGetCategoryById() {
        Category category = new Category();
        category.setId(1L);
        category.setName("凉菜");
        
        when(categoryMapper.selectById(1L)).thenReturn(category);
        
        Category result = categoryService.getById(1L);
        
        assertNotNull(result);
        assertEquals("凉菜", result.getName());
    }
}
