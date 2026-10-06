package com.fc.v2.model.auto;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

import java.io.Serializable;
import java.util.Date;

/**
 * 一户一档卷宗逐格留痕对象 t_barr_case_log。
 * 一笔一行，只许追加，谁都不许改行删行；格次与先后只由这张表一处数出。
 * 回退不抹旧痕，重走该格轮次+1、另起一层，旧层沉底不并排。
 *
 * @author fuce
 * @date 2026-10-06
 */
@TableName("t_barr_case_log")
@ApiModel(value = "TBarrCaseLog", description = "一户一档卷宗逐格留痕")
public class TBarrCaseLog implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 起卷 */
    public static final String ACTION_OPEN = "open";
    /** 进一格 */
    public static final String ACTION_ADVANCE = "advance";
    /** 退一格 */
    public static final String ACTION_ROLLBACK = "rollback";
    /** 同格补一句按语 */
    public static final String ACTION_NOTE = "note";
    /** 走到结清封住 */
    public static final String ACTION_SETTLE = "settle";
    /** 住户撤申请收口 */
    public static final String ACTION_WITHDRAW = "withdraw";
    /** 底册并走收口 */
    public static final String ACTION_MERGE = "merge";

    /** 本格结论：可 */
    public static final String VERDICT_YES = "可";
    /** 本格结论：不可 */
    public static final String VERDICT_NO = "不可";

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 落在哪一张卷宗上 */
    @TableField("case_id")
    private Long caseId;

    /** 当时卷宗号（随笔冗余） */
    @TableField("case_no")
    private String caseNo;

    /** 动作 open/advance/rollback/note/settle/withdraw/merge */
    @TableField("action")
    private String action;

    /** 动前格次 */
    @TableField("from_stage")
    private Integer fromStage;

    /** 动后格次 */
    @TableField("to_stage")
    private Integer toStage;

    /** 这笔落在哪一格 0..3（系统钉死，报文格次号不作数） */
    @TableField("stage")
    private Integer stage;

    /** 落在哪一轮（回退重走新旧两轮分开放） */
    @TableField("round_no")
    private Integer roundNo;

    /** 全卷层次第（只增不退，数格次/排先后只认它） */
    @TableField("layer_no")
    private Integer layerNo;

    /** 本格本轮落笔次第（重走该格从1重新攒） */
    @TableField("stage_round_seq")
    private Integer stageRoundSeq;

    /** 本格结论 可/不可（落格那句） */
    @TableField("verdict")
    private String verdict;

    /** 这一层落下的一句（一格一记原话） */
    @TableField("note")
    private String note;

    /** 经办人（登录身份） */
    @TableField("operator")
    private String operator;

    /** 落笔时间 */
    @TableField("create_time")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getCaseId() {
        return caseId;
    }

    public void setCaseId(Long caseId) {
        this.caseId = caseId;
    }

    public String getCaseNo() {
        return caseNo;
    }

    public void setCaseNo(String caseNo) {
        this.caseNo = caseNo;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public Integer getFromStage() {
        return fromStage;
    }

    public void setFromStage(Integer fromStage) {
        this.fromStage = fromStage;
    }

    public Integer getToStage() {
        return toStage;
    }

    public void setToStage(Integer toStage) {
        this.toStage = toStage;
    }

    public Integer getStage() {
        return stage;
    }

    public void setStage(Integer stage) {
        this.stage = stage;
    }

    public Integer getRoundNo() {
        return roundNo;
    }

    public void setRoundNo(Integer roundNo) {
        this.roundNo = roundNo;
    }

    public Integer getLayerNo() {
        return layerNo;
    }

    public void setLayerNo(Integer layerNo) {
        this.layerNo = layerNo;
    }

    public Integer getStageRoundSeq() {
        return stageRoundSeq;
    }

    public void setStageRoundSeq(Integer stageRoundSeq) {
        this.stageRoundSeq = stageRoundSeq;
    }

    public String getVerdict() {
        return verdict;
    }

    public void setVerdict(String verdict) {
        this.verdict = verdict;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }
}
