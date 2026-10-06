package com.fc.v2.mapper.custom;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 卷宗留痕层次第按卷取号。
 * 与进度卡季取号、受理簿日取号同一套数法：INSERT IGNORE 保底有行，
 * UPDATE … LAST_INSERT_ID 在本连接内取号，并发两笔各拿各的号——
 * 同一秒撞进来的两层，layer_no 也钉死先后，不并成一条、不漏一条。
 *
 * @author fuce
 * @date 2026-10-06
 */
@Mapper
public interface BarrCaseSeqDao {

    /** 该卷取号行没有就先立一行（0 起），有则不动 */
    @Insert("INSERT IGNORE INTO t_barr_case_seq(case_id, next_val) VALUES(#{caseId}, 0)")
    int ensureCase(@Param("caseId") Long caseId);

    /** 本连接内把号往前挪一格，并把挪到的值记进 LAST_INSERT_ID */
    @Update("UPDATE t_barr_case_seq SET next_val = LAST_INSERT_ID(next_val + 1) WHERE case_id = #{caseId}")
    int bump(@Param("caseId") Long caseId);

    /** 取出本连接刚挪到的号 */
    @Select("SELECT LAST_INSERT_ID()")
    long currentValue();
}
