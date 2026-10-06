package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.common.exception.CaseFlowRuleException;
import com.fc.v2.mapper.auto.TBarrArchMapper;
import com.fc.v2.mapper.auto.TBarrJointSignBillMapper;
import com.fc.v2.mapper.auto.TBarrPayRowMapper;
import com.fc.v2.mapper.auto.TBarrSiteCardMapper;
import com.fc.v2.model.auto.TBarrArch;
import com.fc.v2.model.auto.TBarrCaseFlow;
import com.fc.v2.model.auto.TBarrCaseLog;
import com.fc.v2.model.auto.TBarrJointSignBill;
import com.fc.v2.model.auto.TBarrPayRow;
import com.fc.v2.model.auto.TBarrSiteCard;
import com.fc.v2.model.custom.CaseOpenForm;
import com.fc.v2.model.custom.CaseStageCred;
import com.fc.v2.model.custom.MovePlan;
import com.fc.v2.model.custom.TierVerdict;
import com.fc.v2.service.ICaseFlowAppService;
import com.fc.v2.service.ITBarrCaseFlowService;
import com.fc.v2.service.ITBarrTierLineService;

/**
 * 一户一档卷宗编排 Service（另起一层调流转内核，不碰本体）。
 * 本层只做"把凭据凑齐、把各格门槛守住、把尾收好"；卷宗格次、封死、留痕叠层一律问内核，
 * 不在这里替卷宗把格次定好。日后想换走法，改这一层即可，内核形状不动。
 *
 * @author fuce
 * @date 2026-10-06
 */
@Service
public class CaseFlowAppServiceImpl implements ICaseFlowAppService {

    private static final Pattern WHOLE_NUMBER = Pattern.compile("^\\d+$");

    /** 核销行落地情形：1已收下（结清只认这一步走过） */
    private static final int PAY_ROW_RECEIVED = 1;

    @javax.annotation.Resource
    private ITBarrCaseFlowService caseFlowService;
    @javax.annotation.Resource
    private ITBarrTierLineService tierLineService;
    @javax.annotation.Resource
    private TBarrArchMapper barrArchMapper;
    @javax.annotation.Resource
    private TBarrSiteCardMapper barrSiteCardMapper;
    @javax.annotation.Resource
    private TBarrJointSignBillMapper barrJointSignBillMapper;
    @javax.annotation.Resource
    private TBarrPayRowMapper barrPayRowMapper;
    @javax.annotation.Resource
    private com.fc.v2.mapper.auto.TBarrCaseFlowMapper barrCaseFlowMapper;

    // ============================ 起卷 ============================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TBarrCaseFlow open(CaseOpenForm form, String operator) {
        if (form == null) {
            throw new CaseFlowRuleException("卷面空着，这卷起不来");
        }
        String owner = trim(form.getOwnerCode());
        String site = trim(form.getSiteNo());
        if (owner == null) {
            throw new CaseFlowRuleException("核卷：住户代号空着，四样缺一样，这卷起不来");
        }
        if (site == null) {
            throw new CaseFlowRuleException("核卷：点位代号空着，四样缺一样，这卷起不来");
        }
        int plan = parsePlan(form.getPlanCount());
        int tier = parseTier(form.getTierCode());
        TBarrArch arch = loadArch(form.getArchId());

        // 同一住户同一点位，同时只许一张没封住的卷在前头
        Integer active = caseFlowCountActive(owner, site);
        if (active != null && active > 0) {
            throw new CaseFlowRuleException("住户「" + owner + "」点位「" + site
                    + "」已有一张在办或中途止住的卷挂着，前一张没收尾前第二张起不来");
        }

        TBarrCaseFlow seeded = new TBarrCaseFlow();
        seeded.setOwnerCode(owner);
        seeded.setSiteNo(site);
        seeded.setArchId(arch.getId());
        seeded.setArchNo(arch.getArchNo());
        seeded.setPlanCount(plan);
        seeded.setTierCode(tier);
        seeded.setRemark(trim(form.getRemark()));
        try {
            return caseFlowService.openCase(seeded, operator);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // uk_case_active 兜底：并发两卷抢同一活位，只立得起一张
            throw new CaseFlowRuleException("住户「" + owner + "」点位「" + site
                    + "」已有一张没封住的卷，一点位不许两卷叠着");
        }
    }

    // ============================ 进一格 ============================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TBarrCaseFlow advance(Long id, Integer reportedStage, CaseStageCred cred, String operator) {
        // 格次只问内核这一处：报送号与数出的不合，按数出的办
        MovePlan plan = caseFlowService.plan(id, reportedStage);
        switch (plan.getKind()) {
            case NO_CASE:
                throw new CaseFlowRuleException("这张卷册子里翻不着，或已经撤下留底");
            case SEALED:
                throw new CaseFlowRuleException("这张卷已经封死，改的口子关严，进不动了");
            case JUMP:
                throw new CaseFlowRuleException("想从第" + plan.getCurrentStage()
                        + "格一下迈到第" + plan.getTargetStage() + "格，隔格那一笔无效，只许迈紧邻一格");
            case STALE:
                // 报送里的号落在后头：旧报送一句，卷宗早往前走了，不动库、原样回当下卷
                return caseFlowService.selectTBarrCaseFlowById(id);
            case DUP:
                // 同一格重复递上来：卷面只认头一遍那句，不另叠一层
                return caseFlowService.selectTBarrCaseFlowById(id);
            case ADVANCE:
            default:
                break;
        }

        TBarrCaseFlow live = caseFlowService.loadLiveCase(id);
        if (live == null) {
            throw new CaseFlowRuleException("这张卷已经封死或翻不着");
        }
        int target = plan.getTargetStage();
        CaseStageCred c = cred == null ? new CaseStageCred() : cred;
        if (target == TBarrCaseFlow.STAGE_EVAL) {
            applyEval(live, c);
        } else if (target == TBarrCaseFlow.STAGE_ACCEPT) {
            applyAccept(live, c);
        } else if (target == TBarrCaseFlow.STAGE_TERMINAL) {
            applySettle(live, c);
        }
        return caseFlowService.commitAdvance(live, c.getNote(), operator);
    }

    // ============================ 退 / 收尾 ============================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TBarrCaseFlow rollback(Long id, String note, String operator) {
        TBarrCaseFlow live = caseFlowService.loadLiveCase(id);
        if (live == null) {
            throw new CaseFlowRuleException("这张卷已经封死或翻不着，退不动");
        }
        TBarrCaseFlow back = caseFlowService.commitRollback(live, note, operator);
        if (back == null) {
            throw new CaseFlowRuleException("还停在头一格，前面没有格可退，回头挪半格也不行");
        }
        return back;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TBarrCaseFlow withdraw(Long id, String note, String operator) {
        TBarrCaseFlow closed = caseFlowService.close(id, TBarrCaseFlow.END_WITHDRAW, note, operator);
        if (closed == null) {
            throw new CaseFlowRuleException("这张卷已经封死或翻不着，撤申请收不了口");
        }
        return closed;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<TBarrCaseFlow> mergeByArch(String archId, String note, String operator) {
        if (trim(archId) == null) {
            throw new CaseFlowRuleException("不知道并走的是哪一处底册");
        }
        Long aid;
        try {
            aid = Long.valueOf(archId.trim());
        } catch (NumberFormatException e) {
            throw new CaseFlowRuleException("底册代号合不上，封不了卷");
        }
        List<TBarrCaseFlow> actives = caseFlowService.selectTBarrCaseFlowList(
                new QueryWrapper<TBarrCaseFlow>().eq("arch_id", aid).isNull("end_reason"));
        List<TBarrCaseFlow> closed = new ArrayList<>();
        for (TBarrCaseFlow r : actives) {
            TBarrCaseFlow c = caseFlowService.close(r.getId(), TBarrCaseFlow.END_MERGE, note, operator);
            if (c != null) {
                closed.add(c);
            }
        }
        return closed;
    }

    // ============================ 查 ============================

    @Override
    public List<TBarrCaseLog> trace(Long id) {
        return caseFlowService.trace(id);
    }

    @Override
    public List<TBarrCaseFlow> list(QueryWrapper<TBarrCaseFlow> wrapper) {
        return caseFlowService.selectTBarrCaseFlowList(wrapper);
    }

    @Override
    public TBarrCaseFlow get(Long id) {
        return caseFlowService.selectTBarrCaseFlowById(id);
    }

    @Override
    public int activeCount() {
        return caseFlowService.activeCount();
    }

    // ============================ 各格门槛 ============================

    /** 进评估：入户评估记录要落定（对上在册进度卡），分数与"定档那条答复"对得上 */
    private void applyEval(TBarrCaseFlow live, CaseStageCred c) {
        String cardNo = trim(c.getCardNo());
        if (cardNo == null) {
            throw new CaseFlowRuleException("评估：入户评估对上的进度卡代号空着，记录没落定，进不了评估");
        }
        TBarrSiteCard card = barrSiteCardMapper.selectOne(new QueryWrapper<TBarrSiteCard>()
                .eq("card_no", cardNo).eq("del_flag", 0));
        if (card == null) {
            throw new CaseFlowRuleException("评估：进度卡「" + cardNo + "」在册里对不上，入户评估记录没落定");
        }
        if (live.getSiteNo() != null && !live.getSiteNo().equals(card.getSiteNo())) {
            throw new CaseFlowRuleException("评估：进度卡点位「" + card.getSiteNo()
                    + "」与卷宗点位「" + live.getSiteNo() + "」对不住，评估记录落不到这卷上");
        }
        BigDecimal score = parseScore(c.getScore());
        // 分档那条答复只由定档线服务一处给：屏上填的档、转述的档都不算
        TierVerdict verdict = tierLineService.evaluateVerdict(score, new Date());
        if (verdict.getTier() == TierVerdict.TIER_NONE) {
            throw new CaseFlowRuleException("评估：" + score + " 分请不出在位线（" + verdict.getConclusion()
                    + "），定不了档，进不了评估");
        }
        if (live.getTierCode() == null || verdict.getTier() != live.getTierCode()) {
            throw new CaseFlowRuleException("评估：" + score + " 分定的是「" + verdict.getConclusion()
                    + "」档，与起卷所走第" + live.getTierCode() + "档对不上，进不了验收");
        }
        live.setCardNo(cardNo);
        live.setEvalScore(score);
        live.setEvalTier(verdict.getTier());
        live.setEvalRuleCode(verdict.getRuleCode());
    }

    /** 进验收：联户签认单要编得上号、结论是「可」（会签单签讫即三口都落可点齐） */
    private void applyAccept(TBarrCaseFlow live, CaseStageCred c) {
        String billNo = trim(c.getBillNo());
        if (billNo == null) {
            throw new CaseFlowRuleException("验收：联户验收签认单没编号，进不了验收");
        }
        TBarrJointSignBill bill = barrJointSignBillMapper.selectOne(
                new QueryWrapper<TBarrJointSignBill>().eq("bill_no", billNo));
        if (bill == null) {
            throw new CaseFlowRuleException("验收：签认单「" + billNo + "」编不上号，进不了验收");
        }
        if (bill.getStatus() == null || bill.getStatus() != TBarrJointSignBill.STATUS_FINISHED) {
            throw new CaseFlowRuleException("验收：签认单「" + billNo + "」还没签讫点齐，结论不是「可」，进不了验收");
        }
        live.setSignBillNo(billNo);
    }

    /** 进结清：只认核销那一步走没走过——对得上卡且已有已收下的核销行 */
    private void applySettle(TBarrCaseFlow live, CaseStageCred c) {
        String cardNo = trim(c.getPayCardNo());
        if (cardNo == null) {
            cardNo = trim(live.getCardNo());
        }
        if (cardNo == null) {
            throw new CaseFlowRuleException("结清：没对上改造进度卡，核销那一步无从核，结不清");
        }
        Integer received = barrPayRowMapper.selectCount(new QueryWrapper<TBarrPayRow>()
                .eq("item_code", cardNo).eq("del_flag", 0).eq("status", PAY_ROW_RECEIVED));
        if (received == null || received <= 0) {
            throw new CaseFlowRuleException("结清：卡「" + cardNo + "」还没有已收下的核销行，核销那步没走过，结不清");
        }
        live.setPayCardNo(cardNo);
    }

    // ============================ 校验/装载 ============================

    private TBarrArch loadArch(String archIdText) {
        if (trim(archIdText) == null) {
            throw new CaseFlowRuleException("核卷：挂在哪一处底册名下空着，四样之外底册也合不上，这卷起不来");
        }
        Long archId;
        try {
            archId = Long.valueOf(archIdText.trim());
        } catch (NumberFormatException e) {
            throw new CaseFlowRuleException("核卷：底册代号合不上在册底册，这卷起不来");
        }
        TBarrArch arch = barrArchMapper.selectOne(new QueryWrapper<TBarrArch>()
                .eq("id", archId).eq("del_flag", 0));
        if (arch == null) {
            throw new CaseFlowRuleException("核卷：底册代号合不上在册底册，这卷起不来");
        }
        if (arch.getStatus() != null && arch.getStatus() == 1) {
            throw new CaseFlowRuleException("核卷：底册「" + arch.getArchNo() + "」已并走停用，往它名下立不了新卷");
        }
        return arch;
    }

    private Integer caseFlowCountActive(String owner, String site) {
        return barrCaseFlowMapper.selectCount(new QueryWrapper<TBarrCaseFlow>()
                .eq("del_flag", 0).isNull("end_reason")
                .eq("owner_code", owner).eq("site_no", site));
    }

    private int parsePlan(String raw) {
        String v = trim(raw);
        if (v == null) {
            throw new CaseFlowRuleException("核卷：拟改的处数空着，四样缺一样，这卷起不来");
        }
        v = v.replaceFirst("^[+]", "");
        if (v.startsWith("-") || v.contains(".") || !WHOLE_NUMBER.matcher(v).matches()) {
            throw new CaseFlowRuleException("核卷：拟改的处数「" + raw.trim() + "」不是正整数，这卷起不来");
        }
        int n;
        try {
            n = Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new CaseFlowRuleException("核卷：拟改的处数「" + raw.trim() + "」立不住，这卷起不来");
        }
        if (n <= 0) {
            throw new CaseFlowRuleException("核卷：拟改的处数得是一处以上（" + n + "），这卷起不来");
        }
        return n;
    }

    private int parseTier(String raw) {
        String v = trim(raw);
        if (v == null) {
            throw new CaseFlowRuleException("核卷：所走的补助那一档空着，四样缺一样，这卷起不来");
        }
        int tier;
        try {
            tier = Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new CaseFlowRuleException("核卷：补助档「" + v + "」立不住，只收 1基础 2提升 3重点");
        }
        if (tier < 1 || tier > 3) {
            throw new CaseFlowRuleException("核卷：补助档「" + tier + "」不在 1基础 2提升 3重点 里，这卷起不来");
        }
        return tier;
    }

    private BigDecimal parseScore(String raw) {
        String v = trim(raw);
        if (v == null) {
            throw new CaseFlowRuleException("评估：入户评估分数空着，记录没落定，进不了评估");
        }
        try {
            BigDecimal score = new BigDecimal(v);
            if (score.signum() < 0 || score.compareTo(new BigDecimal("100")) > 0) {
                throw new CaseFlowRuleException("评估：分数「" + v + "」越出 0..100，定不了档");
            }
            return score;
        } catch (NumberFormatException e) {
            throw new CaseFlowRuleException("评估：分数「" + v + "」不像个数，定不了档");
        }
    }

    private String trim(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
