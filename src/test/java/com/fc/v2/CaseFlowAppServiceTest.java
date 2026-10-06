package com.fc.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import com.fc.v2.common.exception.CaseFlowRuleException;
import com.fc.v2.mapper.auto.TBarrArchMapper;
import com.fc.v2.mapper.auto.TBarrCaseFlowMapper;
import com.fc.v2.mapper.auto.TBarrCaseLogMapper;
import com.fc.v2.mapper.auto.TBarrJointSignBillMapper;
import com.fc.v2.mapper.auto.TBarrPayRowMapper;
import com.fc.v2.mapper.auto.TBarrSiteCardMapper;
import com.fc.v2.mapper.custom.BarrCaseSeqDao;
import com.fc.v2.model.auto.TBarrArch;
import com.fc.v2.model.auto.TBarrCaseFlow;
import com.fc.v2.model.auto.TBarrJointSignBill;
import com.fc.v2.model.auto.TBarrSiteCard;
import com.fc.v2.model.custom.CaseOpenForm;
import com.fc.v2.model.custom.CaseStageCred;
import com.fc.v2.model.custom.TierVerdict;
import com.fc.v2.service.ITBarrTierLineService;
import com.fc.v2.service.impl.CaseFlowAppServiceImpl;
import com.fc.v2.service.impl.TBarrCaseFlowServiceImpl;

/**
 * 卷宗编排（另起一层）规矩单测（不起 Spring；内核用真件＋内存库，评估/签认/核销联动打桩）。
 * 盯：起卷四样缺一不可、同点位唯一未封卷、进评估分档要对得上、进验收签认单签讫可、
 * 进结清核销行已收下、报送号隔格/重报怎么裁、撤申请与底册并走收尾。
 */
public class CaseFlowAppServiceTest {

    private CaseFlowAppServiceImpl app;
    private TBarrCaseFlowServiceImpl core;

    private TBarrArchMapper archMapper;
    private TBarrSiteCardMapper siteCardMapper;
    private TBarrJointSignBillMapper jointSignBillMapper;
    private TBarrPayRowMapper payRowMapper;
    private TBarrCaseFlowMapper appFlowMapper;
    private ITBarrTierLineService tierLineService;

    private final List<TBarrCaseFlow> cases = new ArrayList<>();
    private final List<com.fc.v2.model.auto.TBarrCaseLog> logs = new ArrayList<>();
    private final AtomicLong caseIds = new AtomicLong(1000);
    private final AtomicLong logIds = new AtomicLong(5000);
    private final java.util.Map<Long, AtomicLong> seqStore = new java.util.HashMap<>();
    private final ThreadLocal<Long> lastBump = new ThreadLocal<>();

    private int payReceived = 0;

    @BeforeEach
    public void setUp() throws Exception {
        core = new TBarrCaseFlowServiceImpl();
        TBarrCaseFlowMapper flowMapper = mock(TBarrCaseFlowMapper.class);
        TBarrCaseLogMapper logMapper = mock(TBarrCaseLogMapper.class);
        BarrCaseSeqDao seqDao = mock(BarrCaseSeqDao.class);
        setField(core, "barrCaseFlowMapper", flowMapper);
        setField(core, "barrCaseLogMapper", logMapper);
        setField(core, "barrCaseSeqDao", seqDao);
        wireCore(flowMapper, logMapper, seqDao);

        app = new CaseFlowAppServiceImpl();
        archMapper = mock(TBarrArchMapper.class);
        siteCardMapper = mock(TBarrSiteCardMapper.class);
        jointSignBillMapper = mock(TBarrJointSignBillMapper.class);
        payRowMapper = mock(TBarrPayRowMapper.class);
        appFlowMapper = mock(TBarrCaseFlowMapper.class);
        tierLineService = mock(ITBarrTierLineService.class);
        setField(app, "caseFlowService", core);
        setField(app, "tierLineService", tierLineService);
        setField(app, "barrArchMapper", archMapper);
        setField(app, "barrSiteCardMapper", siteCardMapper);
        setField(app, "barrJointSignBillMapper", jointSignBillMapper);
        setField(app, "barrPayRowMapper", payRowMapper);
        setField(app, "barrCaseFlowMapper", appFlowMapper);

        // 编排层同点位计数：默认没有别的未封卷
        when(appFlowMapper.selectCount(any())).thenReturn(0);
        when(payRowMapper.selectCount(any())).thenAnswer(inv -> payReceived);

        TBarrArch arch = new TBarrArch();
        arch.setId(0L);
        arch.setArchNo("DC00");
        arch.setArchName("祥和楼组片区");
        arch.setStatus(0);
        arch.setDelFlag(0);
        when(archMapper.selectOne(any())).thenReturn(arch);

        // 评估：85→提升、70→基础、50→无可用线
        when(tierLineService.evaluateVerdict(any(), any())).thenAnswer(inv -> {
            java.math.BigDecimal s = inv.getArgument(0);
            if (s.doubleValue() >= 80) {
                return TierVerdict.of(TierVerdict.TIER_LIFT, "L-A", new Date());
            }
            if (s.doubleValue() >= 60) {
                return TierVerdict.of(TierVerdict.TIER_BASIC, "L-B", new Date());
            }
            return TierVerdict.noLine();
        });
    }

    private void wireCore(TBarrCaseFlowMapper flowMapper, TBarrCaseLogMapper logMapper, BarrCaseSeqDao seqDao) {
        when(flowMapper.insert(any())).thenAnswer(inv -> {
            TBarrCaseFlow r = inv.getArgument(0);
            // 模拟 uk_case_active：同住户同点位、在册未封只容一张
            boolean dupActive = cases.stream().anyMatch(x ->
                    (x.getDelFlag() == null || x.getDelFlag() == 0)
                    && x.getEndReason() == null
                    && java.util.Objects.equals(x.getOwnerCode(), r.getOwnerCode())
                    && java.util.Objects.equals(x.getSiteNo(), r.getSiteNo()));
            if (dupActive) {
                throw new DuplicateKeyException("uk_case_active");
            }
            if (r.getId() == null) {
                r.setId(caseIds.incrementAndGet());
            }
            cases.add(r);
            return 1;
        });
        when(flowMapper.updateById(any())).thenAnswer(inv -> {
            TBarrCaseFlow r = inv.getArgument(0);
            cases.removeIf(x -> x.getId().equals(r.getId()));
            cases.add(r);
            return 1;
        });
        when(flowMapper.selectById(any())).thenAnswer(inv -> cases.stream()
                .filter(r -> inv.getArgument(0).equals(r.getId())).findFirst().orElse(null));
        when(flowMapper.selectOne(any())).thenAnswer(inv -> {
            WrapperConds c = WrapperConds.of(inv.getArgument(0));
            return cases.stream().filter(r -> WrapperConds.match(r, c)).findFirst().orElse(null);
        });
        when(flowMapper.selectList(any())).thenAnswer(inv -> {
            WrapperConds c = WrapperConds.of(inv.getArgument(0));
            return cases.stream().filter(r -> WrapperConds.match(r, c))
                    .sorted(Comparator.comparing(TBarrCaseFlow::getId)).collect(Collectors.toList());
        });
        when(flowMapper.selectCount(any())).thenAnswer(inv -> {
            WrapperConds c = WrapperConds.of(inv.getArgument(0));
            return (int) cases.stream().filter(r -> WrapperConds.match(r, c)).count();
        });
        when(logMapper.insert(any())).thenAnswer(inv -> {
            com.fc.v2.model.auto.TBarrCaseLog l = inv.getArgument(0);
            synchronized (logs) {
                boolean dup = logs.stream().anyMatch(x -> x.getCaseId().equals(l.getCaseId())
                        && (x.getLayerNo().equals(l.getLayerNo())
                            || (x.getStage().equals(l.getStage()) && x.getRoundNo().equals(l.getRoundNo())
                                && x.getStageRoundSeq().equals(l.getStageRoundSeq()))));
                if (dup) {
                    throw new DuplicateKeyException("uk");
                }
                if (l.getId() == null) {
                    l.setId(logIds.incrementAndGet());
                }
                logs.add(l);
            }
            return 1;
        });
        when(logMapper.selectList(any())).thenAnswer(inv -> {
            WrapperConds c = WrapperConds.of(inv.getArgument(0));
            return logs.stream().filter(l -> WrapperConds.match(l, c))
                    .sorted(Comparator.comparing(com.fc.v2.model.auto.TBarrCaseLog::getLayerNo))
                    .collect(Collectors.toList());
        });
        when(logMapper.selectCount(any())).thenAnswer(inv -> {
            WrapperConds c = WrapperConds.of(inv.getArgument(0));
            return (int) logs.stream().filter(l -> WrapperConds.match(l, c)).count();
        });
        when(seqDao.currentValue()).thenAnswer(inv -> lastBump.get());
        org.mockito.Mockito.doAnswer(inv -> {
            seqStore.computeIfAbsent(inv.getArgument(0), k -> new AtomicLong(0));
            return 1;
        }).when(seqDao).ensureCase(any());
        org.mockito.Mockito.doAnswer(inv -> {
            long v = seqStore.computeIfAbsent(inv.getArgument(0), k -> new AtomicLong(0)).incrementAndGet();
            lastBump.set(v);
            return 1;
        }).when(seqDao).bump(any());
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private CaseOpenForm form() {
        CaseOpenForm f = new CaseOpenForm();
        f.setArchId("0");
        f.setOwnerCode("HZ-1");
        f.setSiteNo("SITE-1");
        f.setPlanCount("10");
        f.setTierCode("2");
        return f;
    }

    private CaseStageCred cred(String cardNo, String score, String billNo, String payCardNo) {
        CaseStageCred c = new CaseStageCred();
        c.setCardNo(cardNo);
        c.setScore(score);
        c.setBillNo(billNo);
        c.setPayCardNo(payCardNo);
        return c;
    }

    private void assertRule(String keyword, Runnable call) {
        CaseFlowRuleException ex = assertThrows(CaseFlowRuleException.class, call::run);
        assertTrue(ex.getMessage().contains(keyword), "毛病要讲清，实际：" + ex.getMessage());
    }

    private TBarrSiteCard stubCard(String cardNo, String siteNo) {
        TBarrSiteCard card = new TBarrSiteCard();
        card.setId(7L);
        card.setCardNo(cardNo);
        card.setSiteNo(siteNo);
        card.setDelFlag(0);
        when(siteCardMapper.selectOne(any())).thenReturn(card);
        return card;
    }

    private TBarrJointSignBill stubBill(String billNo, int status) {
        TBarrJointSignBill bill = new TBarrJointSignBill();
        bill.setId(9L);
        bill.setBillNo(billNo);
        bill.setStatus(status);
        when(jointSignBillMapper.selectOne(any())).thenReturn(bill);
        return bill;
    }

    // ============================ 起卷四样 ============================

    @Test
    public void 四样缺一样卷都起不来() {
        CaseOpenForm noOwner = form();
        noOwner.setOwnerCode(" ");
        assertRule("住户代号", () -> app.open(noOwner, "u"));

        CaseOpenForm noSite = form();
        noSite.setSiteNo(null);
        assertRule("点位代号", () -> app.open(noSite, "u"));

        CaseOpenForm noPlan = form();
        noPlan.setPlanCount("");
        assertRule("拟改的处数", () -> app.open(noPlan, "u"));

        CaseOpenForm zero = form();
        zero.setPlanCount("0");
        assertRule("一处以上", () -> app.open(zero, "u"));

        CaseOpenForm decimal = form();
        decimal.setPlanCount("3.5");
        assertRule("正整数", () -> app.open(decimal, "u"));

        CaseOpenForm badTier = form();
        badTier.setTierCode("9");
        assertRule("补助档", () -> app.open(badTier, "u"));

        CaseOpenForm noTier = form();
        noTier.setTierCode("");
        assertRule("补助那一档", () -> app.open(noTier, "u"));
    }

    @Test
    public void 底册合不上或已并走_卷起不来() {
        when(archMapper.selectOne(any())).thenReturn(null);
        assertRule("合不上在册底册", () -> app.open(form(), "u"));
        TBarrArch stopped = new TBarrArch();
        stopped.setId(1L);
        stopped.setArchNo("DC01");
        stopped.setStatus(1);
        stopped.setDelFlag(0);
        when(archMapper.selectOne(any())).thenReturn(stopped);
        assertRule("已并走停用", () -> app.open(form(), "u"));
    }

    @Test
    public void 同点位前一张未封_第二张起不来() {
        TBarrCaseFlow first = app.open(form(), "u");
        assertNotNull(first);
        // 明面上数得到前一张：按计数拦下
        when(appFlowMapper.selectCount(any())).thenReturn(1);
        assertRule("第二张起不来", () -> app.open(form(), "u"));

        // 两卷并发：计数那一眼都看着 0，落库时撞 uk_case_active，也只立得起一张
        when(appFlowMapper.selectCount(any())).thenReturn(0);
        assertRule("一点位不许两卷叠着", () -> app.open(form(), "u"));
    }

    // ============================ 进评估：分档对得上 ============================

    @Test
    public void 进评估_卡对不上_点位不符_无可用线_分档不符_都拦下() {
        TBarrCaseFlow r = app.open(form(), "u");
        when(siteCardMapper.selectOne(any())).thenReturn(null);
        assertRule("在册里对不上", () -> app.advance(r.getId(), null, cred("K-X", "85", null, null), "u"));

        stubCard("K-1", "OTHER");
        assertRule("点位", () -> app.advance(r.getId(), null, cred("K-1", "85", null, null), "u"));

        stubCard("K-1", "SITE-1");
        assertRule("请不出在位线", () -> app.advance(r.getId(), null, cred("K-1", "50", null, null), "u"));

        // 起卷走提升档(2)，70 只够基础(1)：对不上
        assertRule("对不上", () -> app.advance(r.getId(), null, cred("K-1", "70", null, null), "u"));
        assertEquals(0, app.get(r.getId()).getStage(), "拦下后卷宗仍停在核卷格");
    }

    @Test
    public void 进评估分档对得上_落评估快照() {
        TBarrCaseFlow r = app.open(form(), "u");
        stubCard("K-1", "SITE-1");
        TBarrCaseFlow now = app.advance(r.getId(), null, cred("K-1", "85", null, null), "u");
        assertEquals(1, now.getStage());
        assertEquals("K-1", now.getCardNo());
        assertEquals(2, now.getEvalTier().intValue());
        assertEquals("L-A", now.getEvalRuleCode());
    }

    // ============================ 进验收：签认单签讫可 ============================

    @Test
    public void 进验收_单号对不上_没签讫_都拦下_签讫才过() {
        TBarrCaseFlow r = openTo(1);
        when(jointSignBillMapper.selectOne(any())).thenReturn(null);
        assertRule("编不上号", () -> app.advance(r.getId(), null, cred(null, null, "QR-X", null), "u"));

        stubBill("QR-1", 0);
        assertRule("还没签讫", () -> app.advance(r.getId(), null, cred(null, null, "QR-1", null), "u"));

        stubBill("QR-1", TBarrJointSignBill.STATUS_FINISHED);
        TBarrCaseFlow now = app.advance(r.getId(), null, cred(null, null, "QR-1", null), "u");
        assertEquals(2, now.getStage());
        assertEquals("QR-1", now.getSignBillNo());
    }

    // ============================ 进结清：核销走过 ============================

    @Test
    public void 进结清_核销行没收下结不清_收下即封() {
        TBarrCaseFlow r = openTo(2);
        payReceived = 0;
        assertRule("核销那步没走过", () -> app.advance(r.getId(), null, cred(null, null, null, null), "u"));
        assertEquals(2, app.get(r.getId()).getStage());

        payReceived = 1;
        TBarrCaseFlow settled = app.advance(r.getId(), null, cred(null, null, null, null), "u");
        assertEquals(3, settled.getStage());
        assertEquals(TBarrCaseFlow.END_SETTLE, settled.getEndReason());
        assertEquals("K-1", settled.getPayCardNo(), "结清卡代号空着时沿用评估对上的卡");
        // 封后再进：请不动
        assertRule("封死", () -> app.advance(r.getId(), null, cred(null, null, null, null), "u"));
    }

    // ============================ 报送号只作转述 ============================

    @Test
    public void 报送隔格无效_同格重报不动_读回还是数出的() {
        TBarrCaseFlow r = app.open(form(), "u");
        stubCard("K-1", "SITE-1");
        assertRule("隔格那一笔无效",
                () -> app.advance(r.getId(), 2, cred("K-1", "85", null, null), "u"));
        // 同格重报：不叠层、原样回，格次仍 0
        TBarrCaseFlow dup = app.advance(r.getId(), 0, cred("K-1", "85", null, null), "u");
        assertEquals(0, dup.getStage());
        // 没捎号默认进紧邻一格，凭据齐就过；读回是系统数出的 1
        TBarrCaseFlow now = app.advance(r.getId(), null, cred("K-1", "85", null, null), "u");
        assertEquals(1, now.getStage());
    }

    // ============================ 退 / 收尾 ============================

    @Test
    public void 头格退不回_封死退不动() {
        TBarrCaseFlow r = app.open(form(), "u");
        assertRule("前面没有格可退", () -> app.rollback(r.getId(), "x", "u"));
        TBarrCaseFlow sealed = app.withdraw(r.getId(), "撤", "u");
        assertEquals(TBarrCaseFlow.END_WITHDRAW, sealed.getEndReason());
        assertRule("退不动", () -> app.rollback(r.getId(), "x", "u"));
    }

    @Test
    public void 底册并走_挂它名下在办卷一并封住() {
        TBarrCaseFlow a = app.open(form(), "u");
        CaseOpenForm f2 = form();
        f2.setOwnerCode("HZ-2");
        TBarrCaseFlow b = app.open(f2, "u");
        List<TBarrCaseFlow> closed = app.mergeByArch("0", "底册并走", "u");
        assertEquals(2, closed.size());
        for (TBarrCaseFlow c : closed) {
            assertEquals(TBarrCaseFlow.END_MERGE, c.getEndReason());
            assertEquals(TBarrCaseFlow.STATUS_TERMINAL, c.getStatus());
        }
        assertEquals(0, app.activeCount(), "两卷都封，在办归 0");
        assertTrue(app.get(a.getId()) != null && app.get(b.getId()) != null, "封死仍翻得着");
    }

    /** 起到指定格（1评估/2验收），凭据打桩齐 */
    private TBarrCaseFlow openTo(int stage) {
        TBarrCaseFlow r = app.open(form(), "u");
        if (stage >= 1) {
            stubCard("K-1", "SITE-1");
            r = app.advance(r.getId(), null, cred("K-1", "85", null, null), "u");
        }
        if (stage >= 2) {
            stubBill("QR-1", TBarrJointSignBill.STATUS_FINISHED);
            r = app.advance(r.getId(), null, cred(null, null, "QR-1", null), "u");
        }
        return r;
    }
}
