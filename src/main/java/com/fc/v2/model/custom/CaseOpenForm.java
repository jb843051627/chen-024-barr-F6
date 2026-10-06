package com.fc.v2.model.custom;

import java.io.Serializable;

/**
 * 起卷卷面：头一格要凑齐的四样连同挂靠底册。
 * 四样——住户代号、点位代号、拟改的处数、所走的补助那一档——缺一样这卷起不来；
 * 数与档照卷面原样收进，由业务层一把接住校验，控制层不替它判。
 *
 * @author fuce
 * @date 2026-10-06
 */
public class CaseOpenForm implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 挂在哪一处底册名下（底册代号/ id） */
    private String archId;
    /** 住户代号 */
    private String ownerCode;
    /** 点位代号 */
    private String siteNo;
    /** 拟改的处数（正整数，卷面字符串原样收） */
    private String planCount;
    /** 所走的补助那一档 1基础 2提升 3重点 */
    private String tierCode;
    /** 备注 */
    private String remark;

    public String getArchId() {
        return archId;
    }

    public void setArchId(String archId) {
        this.archId = archId;
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

    public String getPlanCount() {
        return planCount;
    }

    public void setPlanCount(String planCount) {
        this.planCount = planCount;
    }

    public String getTierCode() {
        return tierCode;
    }

    public void setTierCode(String tierCode) {
        this.tierCode = tierCode;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
