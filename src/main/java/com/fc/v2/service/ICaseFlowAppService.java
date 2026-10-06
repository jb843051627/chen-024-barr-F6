package com.fc.v2.service;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.model.auto.TBarrCaseFlow;
import com.fc.v2.model.auto.TBarrCaseLog;
import com.fc.v2.model.custom.CaseOpenForm;
import com.fc.v2.model.custom.CaseStageCred;

/**
 * 一户一档卷宗编排 Service（另起一层调流转内核，不碰本体）。
 * 起卷四样、同点位唯一未封卷、各格门槛（评估落定与分档对得上、签认单签讫结论可、核销走过）、
 * 撤申请／底册并走收尾、在办计数——都在这一层把凭据凑齐、把门，挪格与编号交给内核。
 * 卷宗停在哪一格、封过尾还认不认新笔、留痕怎么叠，仍只由内核一处作答。
 *
 * @author fuce
 * @date 2026-10-06
 */
public interface ICaseFlowAppService {

    /** 起卷：凑齐住户代号、点位代号、拟改处数、所走补助档四样；同点位前一张未封第二张起不来 */
    TBarrCaseFlow open(CaseOpenForm form, String operator);

    /**
     * 进一格：报送格次号只作转述。系统照内核数出格次，隔格无效、同格重报不叠层、旧报送不动；
     * 进评估／验收／结清分别核各自门槛，凭据对不上这一格进不去。
     * 返回卷宗（同格重报原样回当下卷）；被拒抛 CaseFlowRuleException 讲清卡在哪。
     */
    TBarrCaseFlow advance(Long id, Integer reportedStage, CaseStageCred cred, String operator);

    /** 退一格（内核规矩：一回一格、旧笔沉底重走另起一轮）；头一格退不回 */
    TBarrCaseFlow rollback(Long id, String note, String operator);

    /** 住户中途撤申请：也算收尾，封住只读 */
    TBarrCaseFlow withdraw(Long id, String note, String operator);

    /** 底册那一处并走了：挂它名下还在办的卷一并封住 */
    List<TBarrCaseFlow> mergeByArch(String archId, String note, String operator);

    /** 全卷逐层留痕（从末格往回数对受理簿用） */
    List<TBarrCaseLog> trace(Long id);

    /** 台账（含封死终态卷，改不动也删不掉但列得出） */
    List<TBarrCaseFlow> list(QueryWrapper<TBarrCaseFlow> wrapper);

    /** 单卷回看 */
    TBarrCaseFlow get(Long id);

    /** 在办卷宗张数：屏上显的与回库逐格点的同一回算 */
    int activeCount();
}
