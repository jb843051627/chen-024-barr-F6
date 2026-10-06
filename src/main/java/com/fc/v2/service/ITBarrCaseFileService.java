package com.fc.v2.service;

import com.fc.v2.model.auto.TBarrCaseFlow;
import com.fc.v2.model.custom.CaseFileBoard;
import com.fc.v2.model.custom.CaseFileForm;

/**
 * 一户一档改造卷宗·办事口（起卷、进格、退格、收尾、板面）。
 *
 * 格次与留痕的唯一出处仍是本体 {@link ITBarrCaseFlowService}：本层只在它之上调它，
 * 不替它数格次、不替它写卷面；报送里捎来的号只算一句转述，写库读回都按那一处点出来的。
 * 原先那一套继续一个号进、一个数出，形状锁住——本层就是日后另起的那一层，只调它不碰它。
 *
 * @author fuce
 * @date 2026-10-06
 */
public interface ITBarrCaseFileService {

    /**
     * 起卷：四样凑齐（住户代号、点位代号、拟改的处数、所走的补助那一档）才立得起来，缺一样起不来；
     * 卷从底册里挑出来，底册合不上或已并走的起不来；
     * 一户同一处点位同时只许一张没封住的卷在场，前一张还挂在半道第二张起不来。
     */
    TBarrCaseFlow openCase(CaseFileForm form, String operator);

    /**
     * 进一格：只许迈紧邻的一格，隔格那一笔无效；出格前先过本格闸口——
     * 评估格看入户评估记录落定没有、分数与定档那条答复对得上合不上；
     * 验收格看签认单编没编上、结论是不是「可」；结清格只认核销那一步走没走过。
     * 同一格重复递上来，卷面只认头一遍写下那句，不再另叠一层。
     */
    TBarrCaseFlow advance(Long id, CaseFileForm form, String operator);

    /**
     * 退一格：一回只收一格，头一格前面没有路；
     * 被收那格里早先落下的按语挪到末位留底，重走这一格就从这一格重新攒起。
     */
    TBarrCaseFlow rollback(Long id, CaseFileForm form, String operator);

    /**
     * 收尾·这户中途撤了申请：也算收了尾。一收尾便封死——往后只能读，
     * 改的口子一律关严，整卷抽不走，回头挪半格也不行。
     */
    TBarrCaseFlow sealWithdraw(Long id, String note, String operator);

    /**
     * 收尾·底册那一处并走了：这一点位名下没封住的卷一并封住，返回封了几张。
     * 底册还在册的，谈不上并走，这条收尾路走不得。
     */
    int sealMergedArch(String siteNo, String operator);

    /** 回查一张：读回也按那一处点出来的读 */
    TBarrCaseFlow view(Long id);

    /** 板面：在办张数随起卷加一、随封卷减一，与回库逐格点出的数同一回算 */
    CaseFileBoard board();
}
