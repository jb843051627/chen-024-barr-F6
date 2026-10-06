package com.fc.v2.service;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.model.auto.TBarrCaseFlow;
import com.fc.v2.model.auto.TBarrCaseLog;
import com.fc.v2.model.custom.MovePlan;

/**
 * 一户一档改造卷宗 Service（state-machine 形状：卷宗流转内核）。
 * 形状锁住：一个号进、一个数出。卷宗停在哪一格只有"系统逐笔数留痕"这一个出处，
 * 报送里捎来的格次号只算一句转述；层数与先后也只由按卷取号在这一处排定，别处不替卷面补一句。
 * 不掺入户评估、签认、核销的联动判定——那些另起一层（编排服务）调本内核，不碰本体。
 *
 * @author fuce
 * @date 2026-10-06
 */
public interface ITBarrCaseFlowService {

    /** 按主键查卷宗（在册的；格次/情形当场按留痕接住） */
    TBarrCaseFlow selectTBarrCaseFlowById(Long id);

    /** 卷宗台账（含已封住的终态卷；已撤下留底的不上台账；每行情形按格次接住） */
    List<TBarrCaseFlow> selectTBarrCaseFlowList(QueryWrapper<TBarrCaseFlow> queryWrapper);

    /** 装载一张还在手上（在册、没封住）的卷宗；翻不着或已封死回 null */
    TBarrCaseFlow loadLiveCase(Long id);

    /** 逐笔数留痕，当下停在哪一格（格次唯一出处） */
    int currentStage(Long id);

    /**
     * 照系统数出的格次裁一笔报送该怎么走：正常进一格 / 同格重报 / 旧报送 / 隔格 / 已封死 / 查无卷。
     * reportedStage 是报送里捎来的转述号，空着按"进下一格"办。
     */
    MovePlan plan(Long id, Integer reportedStage);

    /** 起卷落库：卷体已由外层凑齐四样；内核排卷宗号、写头一层留痕（open） */
    TBarrCaseFlow openCase(TBarrCaseFlow seeded, String operator);

    /** 进紧邻一格：凭据快照由外层先落到卷体上，内核只挪格、编号、留痕；走到末格即结清封住 */
    TBarrCaseFlow commitAdvance(TBarrCaseFlow live, String note, String operator);

    /** 退一格：一回只退一格，目标格轮次+1，旧笔沉底、新一轮从1重新攒；头一格退不回 */
    TBarrCaseFlow commitRollback(TBarrCaseFlow live, String note, String operator);

    /** 同格补一句按语：另起一层但不挪格；封死的卷补不进 */
    TBarrCaseFlow addNote(Long id, String note, String operator);

    /** 收尾封卷：settle 结清 / withdraw 住户撤申请 / merge 底册并走；封后只读，谁都改不动删不掉 */
    TBarrCaseFlow close(Long id, String reason, String note, String operator);

    /** 全卷逐层留痕（从末格往回数靠它；按 layer_no 摆先后） */
    List<TBarrCaseLog> trace(Long id);

    /** 在办（没封住、在册）卷宗张数：屏上显的与逐格点的同一回算 */
    int activeCount();
}
