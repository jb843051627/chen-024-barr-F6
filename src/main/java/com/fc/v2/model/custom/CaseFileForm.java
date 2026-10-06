package com.fc.v2.model.custom;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 一户一档改造卷宗办事表单：起卷四样、挪格落下的话、各格闸口的凭据都走这一张。
 * reportedStage 只是报送里捎来的一句转述：收进来不作数，格次只认系统数出来的那一处，
 * 写库按数出来的写，读回也按数出来的读。
 *
 * @author fuce
 * @date 2026-10-06
 */
public class CaseFileForm implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 卷宗号（起卷时空着由系统落；本子自带号递来照收，不作唯一凭据） */
    private String bizNo;

    /** 住户代号（起卷四样之一） */
    private String householdNo;

    /** 点位代号（起卷四样之一；底册代号，卷从底册里挑出来） */
    private String siteNo;

    /** 拟改的处数（起卷四样之一；只收正整数） */
    private String planNum;

    /** 所走的补助那一档（起卷四样之一：1基础 2提升 3重点） */
    private Integer tier;

    /** 入户评估记录号（评估格闸口：记录落定没有） */
    private String assessNo;

    /** 评估分（评估格闸口：与定档那条答复对一对） */
    private BigDecimal score;

    /** 联户验收签认单号（验收格闸口：编没编上、结论是不是「可」） */
    private String billNo;

    /** 报送里捎来的格次号：只算一句转述，写库读回都不认它 */
    private Integer reportedStage;

    /** 这一格落下的话（进/退都少不了一句，空着递不上去） */
    private String note;

    public String getBizNo() {
        return bizNo;
    }

    public void setBizNo(String bizNo) {
        this.bizNo = bizNo;
    }

    public String getHouseholdNo() {
        return householdNo;
    }

    public void setHouseholdNo(String householdNo) {
        this.householdNo = householdNo;
    }

    public String getSiteNo() {
        return siteNo;
    }

    public void setSiteNo(String siteNo) {
        this.siteNo = siteNo;
    }

    public String getPlanNum() {
        return planNum;
    }

    public void setPlanNum(String planNum) {
        this.planNum = planNum;
    }

    public Integer getTier() {
        return tier;
    }

    public void setTier(Integer tier) {
        this.tier = tier;
    }

    public String getAssessNo() {
        return assessNo;
    }

    public void setAssessNo(String assessNo) {
        this.assessNo = assessNo;
    }

    public BigDecimal getScore() {
        return score;
    }

    public void setScore(BigDecimal score) {
        this.score = score;
    }

    public String getBillNo() {
        return billNo;
    }

    public void setBillNo(String billNo) {
        this.billNo = billNo;
    }

    public Integer getReportedStage() {
        return reportedStage;
    }

    public void setReportedStage(Integer reportedStage) {
        this.reportedStage = reportedStage;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
