package com.smartkitchen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartkitchen.entity.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单主表 Mapper 接口
 */
@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    /**
     * 查询所有被占用的座位号（状态为 0:ORDERED 或 10:SERVED 的订单）
     *
     * @return 被占用的座位号列表
     */
    @Select("SELECT seat_number FROM oms_order WHERE status IN (0, 10)")
    List<String> selectOccupiedSeats();

    /**
     * 支付登记（状态校验下沉 SQL）：仅当订单处于 ORDERED/SERVED 且未支付时才更新，
     * SERVED 同步流转为 PAID，ORDERED 仅登记支付信息保持原状态。
     * 与库存扣减 daily_stock >= ? 同一思想：以数据库原子更新做并发最终裁决。
     *
     * @return 影响行数，0 表示订单已被并发修改或重复支付
     */
    @Update("UPDATE oms_order " +
            "SET status = CASE WHEN status = #{servedCode} THEN #{paidCode} ELSE status END, " +
            "    payment_trade_no = #{paymentTradeNo}, pay_time = #{payTime}, update_time = NOW() " +
            "WHERE id = #{id} AND status IN (#{orderedCode}, #{servedCode}) AND pay_time IS NULL")
    int markPaidIfUnpaid(@Param("id") Long id,
                         @Param("paymentTradeNo") String paymentTradeNo,
                         @Param("payTime") LocalDateTime payTime,
                         @Param("orderedCode") int orderedCode,
                         @Param("servedCode") int servedCode,
                         @Param("paidCode") int paidCode);

    /**
     * 撤销订单（状态校验下沉 SQL）：仅当订单处于 ORDERED/SERVED 时才更新为 CANCELLED
     *
     * @return 影响行数，0 表示订单已被并发修改
     */
    @Update("UPDATE oms_order " +
            "SET status = #{cancelledCode}, cancel_reason = #{cancelReason}, update_time = NOW() " +
            "WHERE id = #{id} AND status IN (#{orderedCode}, #{servedCode})")
    int cancelIfActive(@Param("id") Long id,
                       @Param("cancelReason") String cancelReason,
                       @Param("orderedCode") int orderedCode,
                       @Param("servedCode") int servedCode,
                       @Param("cancelledCode") int cancelledCode);

    /**
     * 出餐（状态校验下沉 SQL）：仅当订单处于 ORDERED 时才更新为目标状态（SERVED 或已提前支付的 PAID）
     *
     * @return 影响行数，0 表示订单已被并发修改
     */
    @Update("UPDATE oms_order " +
            "SET status = #{newStatus}, complete_time = #{completeTime}, update_time = NOW() " +
            "WHERE id = #{id} AND status = #{orderedCode}")
    int serveIfOrdered(@Param("id") Long id,
                       @Param("newStatus") int newStatus,
                       @Param("completeTime") LocalDateTime completeTime,
                       @Param("orderedCode") int orderedCode);
}
