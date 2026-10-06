package com.fc.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fc.v2.common.exception.CaseFileRuleException;
import com.fc.v2.mapper.auto.TBarrArchMapper;
import com.fc.v2.mapper.auto.TBarrCaseFlowMapper;
import com.fc.v2.mapper.auto.TBarrJointSignBillMapper;
import com.fc.v2.mapper.auto.TBarrPayRowMapper;
import com.fc.v2.mapper.auto.TBarrSiteCardMapper;
import com.fc.v2.model.auto.TBarrArch;
import com.fc.v2.model.auto.TBarrCaseFlow;
import com.fc.v2.model.auto.TBarrJointSignBill;
import com.fc.v2.model.auto.TBarrSiteCard;
import com.fc.v2.model.custom.CaseFileBoard;
import com.fc.v2.model.custom.CaseFileForm;
import com.fc.v2.model.custom.TierVerdict;
import com.fc.v2.service.ITBarrTierLineService;
import com.fc.v2.service.impl.TBarrCaseFileServiceImpl;
import com.fc.v2.service.impl.TBarrCaseFlowServiceImpl;

/**
 * 一户一档改造卷宗·办事口单测（不起 Spring、不连库；mapper 与定档线打桩，本体走真身）。
 * 盯这几件事：起卷四样、同点位只许一张没封住的卷、格次闸口、报送号只算转述、
 * 同格重报不另叠一层、退格按语挪末位、两条收尾路、在办张数同一回算。
 */
public class CaseFileServiceTest {

    private TBarrCaseFileServiceImpl service;
    private TBarrCaseFlowServiceImpl core;
    private TBarrCaseFlowMapper caseFlowMapper;
    private TBarrArchMapper archMapper;
    private TBarrJointSignBillMapper jointSignBillMapper;
    private TBarrSiteCardMapper siteCardMapper;
    private TBarrPayRowMapper payRowMapper;
    private ITBarrTierLineService tierLineService;

    @BeforeEach
    public void setUp() throws Exception {
        caseFlowMapper = mock(TBarrCaseFlowMapper.class);
        archMapper = mock(TBarrArchMapper.class);
        jointSignBillMapper = mock(TBarrJointSignBillMapper.class);
        siteCardMapper = mock(TBarrSiteCardMapper.class);
        payRowMapper = mock(TBarrPayRowMapper.class);
        tierLineService = mock(ITBarrTierLineService.class);

        core = new TBarrCaseFlowServiceImpl();
        setField(core, "barrCaseFlowMapper", caseFlowMapper);

        service = new TBarrCaseFileServiceImpl();
        setField(service, "caseFlowService", core);
        setField(service, "caseFlowMapper", caseFlowMapper);
        setField(service, "archMapper", archMapper);
        setField(service, "jointSignBillMapper", jointSignBillMapper);
        setField(service, "siteCardMapper", siteCardMapper);
        setField(service, "payRowMapper", payRowMapper);
        setField(service, "tierLineService", tierLineService);

        when(caseFlowMapper.updateById(any())).thenReturn(1);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** 一张停在某一格的在办卷 */
    private TBarrCaseFlow fileAt(int stage, String content, String remark) {
        TBarrCaseFlow r = new TBarrCaseFlow();
        r.setId(7L);
        r.setBizNo("JZ-7");
        r.setStage(stage);
        r.setDelFlag(0);
        r.setContent(content);
        r.setRemark(remark);
        when(caseFlowMapper.selectById(7L)).thenReturn(r);
        when(caseFlowMapper.selectOne(any())).thenReturn(r);
        return r;
    }

    private CaseFileForm openForm() {
        CaseFileForm form = new CaseFileForm();
        form.setHouseholdNo("H001");
        form.setSiteNo("DC00");
        form.setPlanNum("2");
        form.setTier(2);
        return form;
    }

    private TBarrArch archInService() {
        TBarrArch arch = new TBarrArch();
        arch.setId(0L);
        arch.setArchNo("DC00");
        arch.setStatus(0);
        arch.setDelFlag(0);
        return arch;
    }

    // ============================ 起卷 ============================

    @Test
    public void 起卷四样缺一样这卷起不来() {
        when(archMapper.selectOne(any())).thenReturn(archInService());
        when(caseFlowMapper.selectCount(any())).thenReturn(0);

        CaseFileForm noHousehold = openForm();
        noHousehold.setHouseholdNo(null);
        assertThrows(CaseFileRuleException.class, () -> service.openCase(noHousehold, "张三"), "缺住户代号");

        CaseFileForm noSite = openForm();
        noSite.setSiteNo("  ");
        assertThrows(CaseFileRuleException.class, () -> service.openCase(noSite, "张三"), "缺点位代号");

        CaseFileForm noPlan = openForm();
        noPlan.setPlanNum("");
        assertThrows(CaseFileRuleException.class, () -> service.openCase(noPlan, "张三"), "缺拟改处数");

        CaseFileForm badPlan = openForm();
        badPlan.setPlanNum("2.5");
        assertThrows(CaseFileRuleException.class, () -> service.openCase(badPlan, "张三"), "处数带零头");

        CaseFileForm zeroPlan = openForm();
        zeroPlan.setPlanNum("0");
        assertThrows(CaseFileRuleException.class, () -> service.openCase(zeroPlan, "张三"), "处数为零");

        CaseFileForm noTier = openForm();
        noTier.setTier(null);
        assertThrows(CaseFileRuleException.class, () -> service.openCase(noTier, "张三"), "缺补助档");

        CaseFileForm badTier = openForm();
        badTier.setTier(4);
        assertThrows(CaseFileRuleException.class, () -> service.openCase(badTier, "张三"), "档落不进三档");

        assertThrows(CaseFileRuleException.class, () -> service.openCase(null, "张三"), "卷面空着");
    }

    @Test
    public void 起卷落库_四样上卷面_报送号只算转述() {
        when(archMapper.selectOne(any())).thenReturn(archInService());
        when(caseFlowMapper.selectCount(any())).thenReturn(0);
        final TBarrCaseFlow[] inserted = new TBarrCaseFlow[1];
        when(caseFlowMapper.insert(any())).thenAnswer(inv -> {
            inserted[0] = inv.getArgument(0);
            inserted[0].setId(100L);
            return 1;
        });

        CaseFileForm form = openForm();
        form.setReportedStage(3); // 报送里捎来的号：只算一句转述
        TBarrCaseFlow r = service.openCase(form, "张三");

        assertNotNull(r);
        assertEquals(0, r.getStage(), "起卷落在头一格，报送的号不作数");
        assertEquals(0, r.getStatus(), "头一格即未起");
        assertEquals("住户=H001|点位=DC00|档=2", r.getRemark());
        assertTrue(r.getBizNo() != null && r.getBizNo().startsWith("JZ-"), "卷宗号系统落：" + r.getBizNo());
        assertTrue(r.getContent().startsWith("[0]["), "起卷那一层挂在头一格：" + r.getContent());
        assertTrue(r.getContent().contains("起卷：住户=H001 点位=DC00 拟改=2处 档=提升"), "四样上卷面：" + r.getContent());
        assertTrue(r.getContent().contains("[张三]"), "谁经的手上卷面");
    }

    @Test
    public void 底册合不上或已并走的起不来() {
        when(archMapper.selectOne(any())).thenReturn(null);
        assertThrows(CaseFileRuleException.class, () -> service.openCase(openForm(), "张三"), "底册翻不着");

        TBarrArch merged = archInService();
        merged.setStatus(1);
        when(archMapper.selectOne(any())).thenReturn(merged);
        assertThrows(CaseFileRuleException.class, () -> service.openCase(openForm(), "张三"), "底册已并走");
    }

    @Test
    public void 前一张没封住第二张起不来() {
        when(archMapper.selectOne(any())).thenReturn(archInService());
        when(caseFlowMapper.selectCount(any())).thenReturn(1); // 同户同点位有一张还挂在半道
        assertThrows(CaseFileRuleException.class, () -> service.openCase(openForm(), "张三"));
    }

    // ============================ 格次闸口 ============================

    @Test
    public void 评估闸口_记录落定且分数对得上档才放行() {
        TBarrCaseFlow r = fileAt(1, "[0][2026-10-06 09:00:00][张三] 起卷：住户=H001 点位=DC00 拟改=2处 档=提升",
                "住户=H001|点位=DC00|档=2");

        CaseFileForm form = new CaseFileForm();
        form.setNote("评估落定");
        assertThrows(CaseFileRuleException.class, () -> service.advance(7L, form, "张三"), "记录号空着出不得");

        form.setAssessNo("PG-2026-001");
        assertThrows(CaseFileRuleException.class, () -> service.advance(7L, form, "张三"), "评估分空着出不得");

        form.setScore(new BigDecimal("80"));
        when(tierLineService.evaluateVerdict(any(), any())).thenReturn(TierVerdict.noLine());
        assertThrows(CaseFileRuleException.class, () -> service.advance(7L, form, "张三"), "请不出线出不得");

        when(tierLineService.evaluateVerdict(any(), any())).thenReturn(TierVerdict.of(3, "RULE-1", new Date()));
        assertThrows(CaseFileRuleException.class, () -> service.advance(7L, form, "张三"), "分数落在重点档，与卷上提升档合不上");

        when(tierLineService.evaluateVerdict(any(), any())).thenReturn(TierVerdict.of(2, "RULE-1", new Date()));
        TBarrCaseFlow moved = service.advance(7L, form, "张三");
        assertEquals(2, moved.getStage());
        assertTrue(moved.getContent().contains("[2]["), "进格留痕挂在验收格：" + moved.getContent());
        assertTrue(moved.getContent().contains("评估落定"), "这一格落下的话上卷面");
    }

    @Test
    public void 验收闸口_签认单编上且结论可才放行() {
        TBarrCaseFlow r = fileAt(2, "[1][2026-10-06 09:00:00][张三] 评估落定", "住户=H001|点位=DC00|档=2");

        CaseFileForm form = new CaseFileForm();
        form.setNote("验收通过");
        assertThrows(CaseFileRuleException.class, () -> service.advance(7L, form, "张三"), "单号空着出不得");

        form.setBillNo("QRB-20261005-0001");
        when(jointSignBillMapper.selectOne(any())).thenReturn(null);
        assertThrows(CaseFileRuleException.class, () -> service.advance(7L, form, "张三"), "单翻不着出不得");

        TBarrJointSignBill bill = new TBarrJointSignBill();
        bill.setId(1L);
        bill.setBillNo("QRB-20261005-0001");
        bill.setArchNo("DC00");
        bill.setStatus(TBarrJointSignBill.STATUS_RUNNING);
        when(jointSignBillMapper.selectOne(any())).thenReturn(bill);
        assertThrows(CaseFileRuleException.class, () -> service.advance(7L, form, "张三"), "没签讫结论落不了可");

        bill.setStatus(TBarrJointSignBill.STATUS_FINISHED);
        bill.setArchNo("DC99");
        assertThrows(CaseFileRuleException.class, () -> service.advance(7L, form, "张三"), "单不是这一处的出不得");
    }

    @Test
    public void 结清闸口_只认核销那一步走没走过() {
        TBarrCaseFlow r = fileAt(2, "[1][2026-10-06 09:00:00][张三] 评估落定", "住户=H001|点位=DC00|档=2");
        CaseFileForm form = new CaseFileForm();
        form.setNote("验收通过");
        form.setBillNo("QRB-20261005-0001");
        TBarrJointSignBill bill = new TBarrJointSignBill();
        bill.setBillNo("QRB-20261005-0001");
        bill.setArchNo("DC00");
        bill.setStatus(TBarrJointSignBill.STATUS_FINISHED);
        when(jointSignBillMapper.selectOne(any())).thenReturn(bill);

        when(siteCardMapper.selectList(any())).thenReturn(new ArrayList<TBarrSiteCard>());
        assertThrows(CaseFileRuleException.class, () -> service.advance(7L, form, "张三"), "点位名下没卡，核销没走过");

        TBarrSiteCard card = new TBarrSiteCard();
        card.setCardNo("K2026Q4-000001");
        when(siteCardMapper.selectList(any())).thenReturn(Arrays.asList(card));
        when(payRowMapper.selectCount(any())).thenReturn(0);
        assertThrows(CaseFileRuleException.class, () -> service.advance(7L, form, "张三"), "没有一行已收下，核销没走过");

        when(payRowMapper.selectCount(any())).thenReturn(1);
        TBarrCaseFlow moved = service.advance(7L, form, "张三");
        assertEquals(3, moved.getStage(), "核销走过才进结清");
        assertEquals(2, moved.getStatus(), "走到结清即封住");
    }

    // ============================ 格次只有一处来路 ============================

    @Test
    public void 报送号推不动格次_只迈紧邻的一格() {
        TBarrCaseFlow r = fileAt(0, "[0][2026-10-06 09:00:00][张三] 起卷：住户=H001 点位=DC00 拟改=2处 档=提升",
                "住户=H001|点位=DC00|档=2");
        CaseFileForm form = new CaseFileForm();
        form.setNote("核卷齐");
        form.setReportedStage(3); // 报送说已到结清：只算一句转述
        TBarrCaseFlow moved = service.advance(7L, form, "张三");
        assertEquals(1, moved.getStage(), "报送号推不动，只迈紧邻的一格");
        assertEquals(1, moved.getStatus());
        assertTrue(moved.getContent().contains("[1]["), "留痕挂在系统数出来的那一格");
    }

    @Test
    public void 同一格重复递上来_只认头一遍() {
        TBarrCaseFlow r = fileAt(1,
                "[0][2026-10-06 09:00:00][张三] 起卷：住户=H001 点位=DC00 拟改=2处 档=提升\n"
                        + "[1][2026-10-06 09:05:00][张三] 核卷齐",
                "住户=H001|点位=DC00|档=2");
        CaseFileForm form = new CaseFileForm();
        form.setNote("核卷齐");
        TBarrCaseFlow again = service.advance(7L, form, "张三");
        assertEquals(1, again.getStage(), "重复递不多走一格");
        verify(caseFlowMapper, never()).updateById(any());
        assertEquals(2, again.getContent().split("\n").length, "不再另叠一层");
    }

    @Test
    public void 两条来路各递各的_两笔都落卷() {
        TBarrCaseFlow r = fileAt(1,
                "[0][2026-10-06 09:00:00][张三] 起卷：住户=H001 点位=DC00 拟改=2处 档=提升\n"
                        + "[1][2026-10-06 09:05:00][张三] 核卷齐",
                "住户=H001|点位=DC00|档=2");
        when(tierLineService.evaluateVerdict(any(), any())).thenReturn(TierVerdict.of(2, "RULE-1", new Date()));
        CaseFileForm form = new CaseFileForm();
        form.setNote("评估落定");
        form.setAssessNo("PG-1");
        form.setScore(new BigDecimal("80"));
        // 同一句话，另一位经办另一道来路：不许并成一条，也不许漏过一条
        TBarrCaseFlow moved = service.advance(7L, form, "李四");
        assertEquals(2, moved.getStage());
        assertTrue(moved.getContent().contains("[张三] 核卷齐"), "头一道来路的话还在");
        assertTrue(moved.getContent().contains("[李四] 评估落定"), "另一道来路的话也落了卷");
    }

    // ============================ 退格 ============================

    @Test
    public void 退格按语挪到末位_重走这一格重新攒起() {
        TBarrCaseFlow r = fileAt(2,
                "[0][2026-10-06 09:00:00][张三] 起卷：住户=H001 点位=DC00 拟改=2处 档=提升\n"
                        + "[1][2026-10-06 09:05:00][张三] 评估落定\n"
                        + "[2][2026-10-06 09:10:00][张三] 验收通过",
                "住户=H001|点位=DC00|档=2");
        CaseFileForm back = new CaseFileForm();
        back.setNote("退评估重办");
        TBarrCaseFlow moved = service.rollback(7L, back, "张三");
        assertEquals(1, moved.getStage(), "一回只收一格");

        String[] lines = moved.getContent().split("\n");
        assertEquals(4, lines.length, "旧笔留底不抹掉：" + moved.getContent());
        assertTrue(lines[1].contains("评估落定") && !lines[1].contains("退格留底"), "前头各格的话原地不动");
        assertTrue(lines[2].contains("退评估重办"), "退格这一笔落在卷面上");
        assertTrue(lines[3].contains("验收通过") && lines[3].endsWith("（退格留底）"),
                "被收那格的按语挪到末位留底：" + lines[3]);

        // 重走这一格：退格留底的旧笔不挡路，新笔重新攒起
        when(tierLineService.evaluateVerdict(any(), any())).thenReturn(TierVerdict.of(2, "RULE-1", new Date()));
        CaseFileForm again = new CaseFileForm();
        again.setNote("验收通过"); // 与留底旧笔同一句话：留底的不算重报
        again.setAssessNo("PG-2");
        again.setScore(new BigDecimal("81"));
        TBarrCaseFlow rewalk = service.advance(7L, again, "张三");
        assertEquals(2, rewalk.getStage(), "重走这一格从这一格重新攒起");
        String[] relines = rewalk.getContent().split("\n");
        assertEquals(5, relines.length);
        assertTrue(relines[4].contains("[2]") && relines[4].contains("验收通过")
                && !relines[4].contains("退格留底"), "新笔另起一层，不与留底旧笔并排");
    }

    @Test
    public void 头一格无路可退() {
        fileAt(0, "[0][2026-10-06 09:00:00][张三] 起卷：住户=H001 点位=DC00 拟改=2处 档=提升",
                "住户=H001|点位=DC00|档=2");
        CaseFileForm form = new CaseFileForm();
        form.setNote("再退");
        assertThrows(CaseFileRuleException.class, () -> service.rollback(7L, form, "张三"));
    }

    // ============================ 收尾 ============================

    @Test
    public void 中途撤了申请算收了尾_封死后只读() {
        TBarrCaseFlow r = fileAt(1,
                "[0][2026-10-06 09:00:00][张三] 起卷：住户=H001 点位=DC00 拟改=2处 档=提升\n"
                        + "[1][2026-10-06 09:05:00][张三] 核卷齐",
                "住户=H001|点位=DC00|档=2");
        TBarrCaseFlow sealed = service.sealWithdraw(7L, "户里不改了", "张三");
        assertEquals(3, sealed.getStage(), "收尾落到终态那一格");
        assertEquals(2, sealed.getStatus(), "一收尾便封死");
        assertTrue(sealed.getContent().contains("收尾：中途撤了申请：户里不改了"), "凭什么收的尾上卷面");
        assertTrue(sealed.getContent().contains("评估这一格收的尾"), "停在哪一格收的尾也上卷面");

        CaseFileForm form = new CaseFileForm();
        form.setNote("还想动");
        assertThrows(CaseFileRuleException.class, () -> service.advance(7L, form, "张三"), "封死后进不得");
        assertThrows(CaseFileRuleException.class, () -> service.rollback(7L, form, "张三"), "封死后退不得");
        assertThrows(CaseFileRuleException.class, () -> service.sealWithdraw(7L, "再收", "张三"), "封死后不再收");
    }

    @Test
    public void 底册并走_这一点位没封住的卷一并收尾() {
        TBarrArch merged = archInService();
        merged.setArchNo("DC01");
        merged.setStatus(1);
        when(archMapper.selectOne(any())).thenReturn(merged);

        TBarrCaseFlow a = new TBarrCaseFlow();
        a.setId(1L);
        a.setStage(0);
        a.setDelFlag(0);
        a.setRemark("住户=H1|点位=DC01|档=1");
        TBarrCaseFlow b = new TBarrCaseFlow();
        b.setId(2L);
        b.setStage(2);
        b.setDelFlag(0);
        b.setRemark("住户=H2|点位=DC01|档=2");
        when(caseFlowMapper.selectList(any())).thenReturn(Arrays.asList(a, b));

        int n = service.sealMergedArch("DC01", "张三");
        assertEquals(2, n, "没封住的一并封住");
        assertEquals(3, a.getStage());
        assertEquals(2, a.getStatus());
        assertTrue(a.getContent().contains("收尾：底册那一处并走了"));
        assertEquals(3, b.getStage());
        assertTrue(b.getContent().contains("收尾：底册那一处并走了"));
    }

    @Test
    public void 底册还在册_并走这条收尾路走不得() {
        when(archMapper.selectOne(any())).thenReturn(archInService());
        assertThrows(CaseFileRuleException.class, () -> service.sealMergedArch("DC00", "张三"));
    }

    // ============================ 板面 ============================

    @Test
    public void 在办张数与逐格点数同一回算() {
        List<TBarrCaseFlow> all = new ArrayList<>();
        int[] stages = {0, 1, 2, 3, 3};
        for (int i = 0; i < stages.length; i++) {
            TBarrCaseFlow r = new TBarrCaseFlow();
            r.setId((long) i);
            r.setStage(stages[i]);
            r.setDelFlag(0);
            all.add(r);
        }
        when(caseFlowMapper.selectList(any())).thenReturn(all);

        CaseFileBoard board = service.board();
        assertEquals(3, board.getRunningTotal(), "在办张数：随起卷加一、随封卷减一");
        assertEquals(2, board.getSealedTotal(), "已封住的屏上照旧列得出");
        assertEquals(1, board.getAtStage()[0]);
        assertEquals(1, board.getAtStage()[1]);
        assertEquals(1, board.getAtStage()[2]);
        assertEquals(2, board.getAtStage()[3]);
    }
}
