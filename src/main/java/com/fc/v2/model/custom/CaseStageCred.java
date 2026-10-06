package com.fc.v2.model.custom;

import java.io.Serializable;

/**
 * 进一格时随报送捎来的凭据与转述。
 * 报送里的 reportedStage 顶多算一句转述，系统格次以数留痕为准；
 * 其余各栏是各格门槛要核对的凭据，由外层编排照"要进的那一格"取用：
 * 进评估对上进度卡与评估分、进验收对上签认单号、进结清对上核销卡代号。
 *
 * @author fuce
 * @date 2026-10-06
 */
public class CaseStageCred implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 报送里捎来的格次号（只作转述；与系统数出的不合以数出的为准，隔格那一笔无效） */
    private Integer reportedStage;
    /** 进评估：入户评估对上的改造进度卡代号 */
    private String cardNo;
    /** 进评估：入户评估落定的分数 */
    private String score;
    /** 进验收：联户验收签认单号（编上号、结论可才算过） */
    private String billNo;
    /** 进结清：核销那一步对上的卡代号（核销行已收下才算走过） */
    private String payCardNo;
    /** 这一步落下的一句 */
    private String note;

    public Integer getReportedStage() {
        return reportedStage;
    }

    public void setReportedStage(Integer reportedStage) {
        this.reportedStage = reportedStage;
    }

    public String getCardNo() {
        return cardNo;
    }

    public void setCardNo(String cardNo) {
        this.cardNo = cardNo;
    }

    public String getScore() {
        return score;
    }

    public void setScore(String score) {
        this.score = score;
    }

    public String getBillNo() {
        return billNo;
    }

    public void setBillNo(String billNo) {
        this.billNo = billNo;
    }

    public String getPayCardNo() {
        return payCardNo;
    }

    public void setPayCardNo(String payCardNo) {
        this.payCardNo = payCardNo;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
