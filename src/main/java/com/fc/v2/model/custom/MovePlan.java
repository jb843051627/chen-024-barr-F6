package com.fc.v2.model.custom;

import java.io.Serializable;

/**
 * 一笔报送进来后，内核照"数留痕数出的格次"给它定的走法。
 * 报送里的格次号只算一句转述：合不合、算不算重复、是不是隔格，全由这一处裁，
 * 值班岗、控制层、报表都不许替卷宗把格次定好。
 *
 * @author fuce
 * @date 2026-10-06
 */
public class MovePlan implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum Kind {
        /** 正常进紧邻一格，可走下一步门槛 */
        ADVANCE,
        /** 同一格重复递上来：卷面只认头一句，不另叠一层，原样回当下卷 */
        DUP,
        /** 报送号落在后头：旧报送一句，卷宗早走过了，不动 */
        STALE,
        /** 想隔一格甚至更多迈：这一笔无效 */
        JUMP,
        /** 卷已封死：改的口子全关 */
        SEALED,
        /** 册子里翻不着（含已撤下留底） */
        NO_CASE
    }

    private final Kind kind;
    private final int currentStage;
    private final int targetStage;

    private MovePlan(Kind kind, int currentStage, int targetStage) {
        this.kind = kind;
        this.currentStage = currentStage;
        this.targetStage = targetStage;
    }

    public static MovePlan of(Kind kind, int currentStage, int targetStage) {
        return new MovePlan(kind, currentStage, targetStage);
    }

    public Kind getKind() {
        return kind;
    }

    public int getCurrentStage() {
        return currentStage;
    }

    public int getTargetStage() {
        return targetStage;
    }
}
