package com.fc.v2.model.custom;

import java.io.Serializable;

/**
 * 卷宗板面：在办张数、逐格分布、已封住张数，同一趟数出来。
 * 在办张数随起卷加一、随封卷减一；屏上显的那个数与回库逐格点出的那个数本来就同一回算的，
 * 不许一头 count 一头手填。
 *
 * @author fuce
 * @date 2026-10-06
 */
public class CaseFileBoard implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 在办（没封住）张数：随起卷加一、随封卷减一 */
    private int runningTotal;

    /** 已封住张数（收过尾的：屏上照旧列得出，改不动也删不掉） */
    private int sealedTotal;

    /** 逐格分布 0核卷 1评估 2验收 3结清 */
    private int[] atStage = new int[4];

    public int getRunningTotal() {
        return runningTotal;
    }

    public void setRunningTotal(int runningTotal) {
        this.runningTotal = runningTotal;
    }

    public int getSealedTotal() {
        return sealedTotal;
    }

    public void setSealedTotal(int sealedTotal) {
        this.sealedTotal = sealedTotal;
    }

    public int[] getAtStage() {
        return atStage;
    }

    public void setAtStage(int[] atStage) {
        this.atStage = atStage;
    }
}
