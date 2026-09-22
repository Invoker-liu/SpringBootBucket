package com.xncoding.transaction.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xncoding.transaction.domain.TxEvent;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TxEventMapper extends BaseMapper<TxEvent> {

    @Select("SELECT id, tx_name, phase, detail, created_at FROM t_tx_event " +
            "WHERE tx_name = #{txName} ORDER BY id")
    List<TxEvent> findByTxName(@Param("txName") String txName);

    @Select("SELECT id, tx_name, phase, detail, created_at FROM t_tx_event ORDER BY id")
    List<TxEvent> findAll();

    @Select("SELECT COUNT(*) FROM t_tx_event")
    long countAll();

    @Delete("DELETE FROM t_tx_event")
    int deleteAll();
}
