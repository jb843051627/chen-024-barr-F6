package com.fc.v2.model.auto;

import com.baomidou.mybatisplus.annotation.FieldFill;
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
import java.math.BigDecimal;
import java.util.Date;

/**
 * 一户一档改造卷宗对象 t_barr_case_flow
 *
 * @author fuce
 * @date 2026-09-12
 */
@TableName(value = "t_barr_case_flow", autoResultMap = true)
@ApiModel(value = "TBarrCaseFlow", description = "一户一档改造卷宗")
public class TBarrCaseFlow implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 头一格：核卷（未起卷宗也落在此格） */
    public static final int STAGE_FIRST = 0;
    /** 第二格：评估 */
    public static final int STAGE_EVAL = 1;
    /** 第三格：验收 */
    public static final int STAGE_ACCEPT = 2;
    /** 末一格：结清。走到此格即封住 */
    public static final int STAGE_TERMINAL = 3;

    /** 卷宗情形：0未起（还停在头一格） */
    public static final int STATUS_NOT_STARTED = 0;
    /** 卷宗情形：1在办 */
    public static final int STATUS_ACTIVE = 1;
    /** 卷宗情形：2已封住 */
    public static final int STATUS_TERMINAL = 2;

    /** 封卷缘故：走到结清 */
    public static final String END_SETTLE = "settle";
    /** 封卷缘故：住户中途撤了申请 */
    public static final String END_WITHDRAW = "withdraw";
    /** 封卷缘故：底册那一处并走了 */
    public static final String END_MERGE = "merge";

    /** 主键 */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    @ApiModelProperty(value = "主键")
    private Long id;

    /** 一户一档改造卷宗号（系统按卷取号，本子自带号不认） */
    @TableField("biz_no")
    @ApiModelProperty(value = "一户一档改造卷宗号")
    private String bizNo;

    /** 住户代号（起卷四样之一） */
    @TableField("owner_code")
    @ApiModelProperty(value = "住户代号")
    private String ownerCode;

    /** 点位代号（起卷四样之一） */
    @TableField("site_no")
    @ApiModelProperty(value = "点位代号")
    private String siteNo;

    /** 挂在哪一处底册名下 */
    @TableField("arch_id")
    @JsonSerialize(using = ToStringSerializer.class)
    @ApiModelProperty(value = "挂在哪一处底册名下")
    private Long archId;

    /** 底册代号（以底册为准，随卷冗余） */
    @TableField("arch_no")
    @ApiModelProperty(value = "底册代号")
    private String archNo;

    /** 拟改的处数（起卷四样之一） */
    @TableField("plan_count")
    @ApiModelProperty(value = "拟改的处数")
    private Integer planCount;

    /** 所走的补助那一档（1基础 2提升 3重点） */
    @TableField("tier_code")
    @ApiModelProperty(value = "所走的补助那一档")
    private Integer tierCode;

    /** 入户评估对上的改造进度卡代号 */
    @TableField("card_no")
    @ApiModelProperty(value = "入户评估对上的改造进度卡代号")
    private String cardNo;

    /** 入户评估落定的分数 */
    @TableField("eval_score")
    @ApiModelProperty(value = "入户评估落定的分数")
    private BigDecimal evalScore;

    /** 评估当场系统复算的档（与所走档对不住进不了验收） */
    @TableField("eval_tier")
    @ApiModelProperty(value = "评估当场系统复算的档")
    private Integer evalTier;

    /** 评估说话那条定档线代号（随卷留底） */
    @TableField("eval_rule_code")
    @ApiModelProperty(value = "评估说话那条定档线代号")
    private String evalRuleCode;

    /** 验收联户签认单号（编上号、结论可才算过） */
    @TableField("sign_bill_no")
    @ApiModelProperty(value = "验收联户签认单号")
    private String signBillNo;

    /** 结清时核销对上的卡代号（核销行已收下才算走过） */
    @TableField("pay_card_no")
    @ApiModelProperty(value = "结清时核销对上的卡代号")
    private String payCardNo;

    /** 当前格次 0..3（只由系统数留痕得出，落库以数出的为准） */
    @TableField("stage")
    @ApiModelProperty(value = "当前格次 0..3（核卷/评估/验收/结清）")
    private Integer stage;

    /** 卷宗情形 0未起 1在办 2已封住（只由格次/封卷派生） */
    @TableField("status")
    @ApiModelProperty(value = "卷宗情形 0未起 1在办 2已封住")
    private Integer status;

    /** 封卷缘故 settle/withdraw/merge；空着=没封 */
    @TableField("end_reason")
    @ApiModelProperty(value = "封卷缘故 settle结清 withdraw撤申请 merge底册并走")
    private String endReason;

    /** 各格走到第几轮，竖线隔开（回退一格该格轮次+1） */
    @TableField("stage_rounds")
    @ApiModelProperty(value = "各格走到第几轮，竖线隔开")
    private String stageRounds;

    /** 卷面最近一句（逐层原话在留痕表） */
    @TableField("content")
    @ApiModelProperty(value = "卷面最近一句")
    private String content;

    /** 最近一次挪格动作 */
    @TableField("last_action")
    @ApiModelProperty(value = "最近一次挪格动作")
    private String lastAction;

    /** 删除标记 0正常 1撤下留底 */
    @TableField("del_flag")
    @ApiModelProperty(value = "删除标记 0正常 1撤下留底")
    private Integer delFlag;

    /** 创建者 */
    @TableField(value = "create_by", fill = FieldFill.INSERT)
    @ApiModelProperty(value = "创建者")
    private String createBy;

    /** 创建时间 */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @ApiModelProperty(value = "创建时间")
    private Date createTime;

    /** 更新者 */
    @TableField(value = "update_by", fill = FieldFill.UPDATE)
    @ApiModelProperty(value = "更新者")
    private String updateBy;

    /** 更新时间 */
    @TableField(value = "update_time", fill = FieldFill.UPDATE)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @ApiModelProperty(value = "更新时间")
    private Date updateTime;

    /** 备注 */
    @TableField("remark")
    @ApiModelProperty(value = "备注")
    private String remark;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getBizNo() {
        return bizNo;
    }

    public void setBizNo(String bizNo) {
        this.bizNo = bizNo;
    }

    public String getOwnerCode() {
        return ownerCode;
    }

    public void setOwnerCode(String ownerCode) {
        this.ownerCode = ownerCode;
    }

    public String getSiteNo() {
        return siteNo;
    }

    public void setSiteNo(String siteNo) {
        this.siteNo = siteNo;
    }

    public Long getArchId() {
        return archId;
    }

    public void setArchId(Long archId) {
        this.archId = archId;
    }

    public String getArchNo() {
        return archNo;
    }

    public void setArchNo(String archNo) {
        this.archNo = archNo;
    }

    public Integer getPlanCount() {
        return planCount;
    }

    public void setPlanCount(Integer planCount) {
        this.planCount = planCount;
    }

    public Integer getTierCode() {
        return tierCode;
    }

    public void setTierCode(Integer tierCode) {
        this.tierCode = tierCode;
    }

    public String getCardNo() {
        return cardNo;
    }

    public void setCardNo(String cardNo) {
        this.cardNo = cardNo;
    }

    public BigDecimal getEvalScore() {
        return evalScore;
    }

    public void setEvalScore(BigDecimal evalScore) {
        this.evalScore = evalScore;
    }

    public Integer getEvalTier() {
        return evalTier;
    }

    public void setEvalTier(Integer evalTier) {
        this.evalTier = evalTier;
    }

    public String getEvalRuleCode() {
        return evalRuleCode;
    }

    public void setEvalRuleCode(String evalRuleCode) {
        this.evalRuleCode = evalRuleCode;
    }

    public String getSignBillNo() {
        return signBillNo;
    }

    public void setSignBillNo(String signBillNo) {
        this.signBillNo = signBillNo;
    }

    public String getPayCardNo() {
        return payCardNo;
    }

    public void setPayCardNo(String payCardNo) {
        this.payCardNo = payCardNo;
    }

    public Integer getStage() {
        return stage;
    }

    public void setStage(Integer stage) {
        this.stage = stage;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getEndReason() {
        return endReason;
    }

    public void setEndReason(String endReason) {
        this.endReason = endReason;
    }

    public String getStageRounds() {
        return stageRounds;
    }

    public void setStageRounds(String stageRounds) {
        this.stageRounds = stageRounds;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getLastAction() {
        return lastAction;
    }

    public void setLastAction(String lastAction) {
        this.lastAction = lastAction;
    }

    public Integer getDelFlag() {
        return delFlag;
    }

    public void setDelFlag(Integer delFlag) {
        this.delFlag = delFlag;
    }

    public String getCreateBy() {
        return createBy;
    }

    public void setCreateBy(String createBy) {
        this.createBy = createBy;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public String getUpdateBy() {
        return updateBy;
    }

    public void setUpdateBy(String updateBy) {
        this.updateBy = updateBy;
    }

    public Date getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Date updateTime) {
        this.updateTime = updateTime;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
