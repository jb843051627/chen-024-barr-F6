package com.fc.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TBarrCaseFlowMapper;
import com.fc.v2.mapper.auto.TBarrCaseLogMapper;
import com.fc.v2.mapper.custom.BarrCaseSeqDao;
import com.fc.v2.model.auto.TBarrCaseFlow;
import com.fc.v2.model.auto.TBarrCaseLog;
import com.fc.v2.model.custom.MovePlan;
import com.fc.v2.service.impl.TBarrCaseFlowServiceImpl;

/**
 * 卷宗流转内核规矩单测（不起 Spring、不连库；mapper 用内存库打桩）。
 * 盯：格次只由留痕数出、报送号只作转述、进只一格隔格无效、同格重报不叠层、
 * 退一格旧笔沉底重走另起一轮、一收尾四条改动路全拦且终态仍列得出、
 * 留痕层号并发各记各笔不并不漏、在办计数同一口径。
 */
public class CaseFlowServiceTest {

    private TBarrCaseFlowServiceImpl service;
    private TBarrCaseFlowMapper flowMapper;
    private TBarrCaseLogMapper logMapper;
    private BarrCaseSeqDao seqDao;

    private final List<TBarrCaseFlow> cases = Collections.synchronizedList(new ArrayList<>());
    private final List<TBarrCaseLog> logs = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong caseIds = new AtomicLong(1000);
    private final AtomicLong logIds = new AtomicLong(5000);
    private final java.util.Map<Long, AtomicLong> seqStore =
            Collections.synchronizedMap(new java.util.HashMap<>());
    private final ThreadLocal<Long> lastBump = new ThreadLocal<>();

    @BeforeEach
    public void setUp() throws Exception {
        service = new TBarrCaseFlowServiceImpl();
        flowMapper = mock(TBarrCaseFlowMapper.class);
        logMapper = mock(TBarrCaseLogMapper.class);
        seqDao = mock(BarrCaseSeqDao.class);
        setField(service, "barrCaseFlowMapper", flowMapper);
        setField(service, "barrCaseLogMapper", logMapper);
        setField(service, "barrCaseSeqDao", seqDao);

        when(flowMapper.insert(any())).thenAnswer(inv -> {
            TBarrCaseFlow r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId(caseIds.incrementAndGet());
            }
            cases.add(r);
            return 1;
        });
        when(flowMapper.updateById(any())).thenAnswer(inv -> {
            TBarrCaseFlow r = inv.getArgument(0);
            cases.removeIf(x -> x.getId() != null && x.getId().equals(r.getId()));
            cases.add(r);
            return 1;
        });
        when(flowMapper.selectById(any())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            synchronized (cases) {
                return cases.stream().filter(r -> id.equals(r.getId())).findFirst().orElse(null);
            }
        });
        when(flowMapper.selectOne(any())).thenAnswer(inv -> {
            WrapperConds c = WrapperConds.of(inv.getArgument(0));
            synchronized (cases) {
                return cases.stream().filter(r -> WrapperConds.match(r, c)).findFirst().orElse(null);
            }
        });
        when(flowMapper.selectList(any())).thenAnswer(inv -> {
            WrapperConds c = WrapperConds.of(inv.getArgument(0));
            synchronized (cases) {
                return cases.stream()
                        .filter(r -> WrapperConds.match(r, c))
                        .sorted(Comparator.comparing(TBarrCaseFlow::getId))
                        .collect(Collectors.toList());
            }
        });
        when(flowMapper.selectCount(any())).thenAnswer(inv -> {
            WrapperConds c = WrapperConds.of(inv.getArgument(0));
            synchronized (cases) {
                return (int) cases.stream().filter(r -> WrapperConds.match(r, c)).count();
            }
        });

        when(logMapper.insert(any())).thenAnswer(inv -> {
            TBarrCaseLog l = inv.getArgument(0);
            synchronized (logs) {
                boolean dupLayer = logs.stream().anyMatch(x -> x.getCaseId().equals(l.getCaseId())
                        && x.getLayerNo().equals(l.getLayerNo()));
                boolean dupSeq = logs.stream().anyMatch(x -> x.getCaseId().equals(l.getCaseId())
                        && x.getStage().equals(l.getStage()) && x.getRoundNo().equals(l.getRoundNo())
                        && x.getStageRoundSeq().equals(l.getStageRoundSeq()));
                if (dupLayer || dupSeq) {
                    throw new DuplicateKeyException(dupLayer ? "uk_case_layer" : "uk_case_stage_round_seq");
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
            synchronized (logs) {
                return logs.stream().filter(l -> WrapperConds.match(l, c))
                        .sorted(Comparator.comparing(TBarrCaseLog::getLayerNo).thenComparing(TBarrCaseLog::getId))
                        .collect(Collectors.toList());
            }
        });
        when(logMapper.selectCount(any())).thenAnswer(inv -> {
            WrapperConds c = WrapperConds.of(inv.getArgument(0));
            synchronized (logs) {
                return (int) logs.stream().filter(l -> WrapperConds.match(l, c)).count();
            }
        });

        when(seqDao.currentValue()).thenAnswer(inv -> lastBump.get());
        org.mockito.Mockito.doAnswer(inv -> {
            seqStore.computeIfAbsent(inv.getArgument(0), k -> new AtomicLong(0));
            return 1;
        }).when(seqDao).ensureCase(any());
        org.mockito.Mockito.doAnswer(inv -> {
            Long cid = inv.getArgument(0);
            long v = seqStore.computeIfAbsent(cid, k -> new AtomicLong(0)).incrementAndGet();
            lastBump.set(v);
            return 1;
        }).when(seqDao).bump(any());
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private TBarrCaseFlow seeded() {
        TBarrCaseFlow r = new TBarrCaseFlow();
        r.setOwnerCode("HZ-1");
        r.setSiteNo("SITE-1");
        r.setArchId(0L);
        r.setArchNo("DC00");
        r.setPlanCount(10);
        r.setTierCode(2);
        return r;
    }

    private TBarrCaseLog lastLog(Long caseId) {
        return logs.stream().filter(l -> l.getCaseId().equals(caseId))
                .max(Comparator.comparing(TBarrCaseLog::getLayerNo)).orElse(null);
    }

    private List<TBarrCaseLog> logsOf(Long caseId) {
        return logs.stream().filter(l -> l.getCaseId().equals(caseId))
                .sorted(Comparator.comparing(TBarrCaseLog::getLayerNo)).collect(Collectors.toList());
    }

    // ============================ 一、格次只有一处来路 ============================

    @Test
    public void 起卷落在头一格_头一层留痕排好() {
        TBarrCaseFlow r = service.openCase(seeded(), "ke");
        assertEquals(0, r.getStage());
        assertEquals(TBarrCaseFlow.STATUS_ACTIVE, r.getStatus(), "起卷即在办，随起卷加一");
        assertTrue(r.getBizNo().startsWith("JZ-"), r.getBizNo());
        TBarrCaseLog l = lastLog(r.getId());
        assertEquals(TBarrCaseLog.ACTION_OPEN, l.getAction());
        assertEquals(0, l.getStage());
        assertEquals(1, l.getLayerNo());
        assertEquals(1, l.getRoundNo());
        assertEquals(1, l.getStageRoundSeq());
        assertEquals("可", l.getVerdict());
    }

    @Test
    public void 报送号只作转述_紧邻认_隔格无效_旧报送不动_同格重报() {
        TBarrCaseFlow r = service.openCase(seeded(), "ke");
        // 头一格：捎 1 才是紧邻；捎 2/3 隔格无效；捎 0 同格重报
        assertEquals(MovePlan.Kind.ADVANCE, service.plan(r.getId(), 1).getKind());
        assertEquals(1, service.plan(r.getId(), null).getTargetStage(), "没捎号默认进下一格");
        assertEquals(MovePlan.Kind.DUP, service.plan(r.getId(), 0).getKind());
        assertEquals(MovePlan.Kind.JUMP, service.plan(r.getId(), 2).getKind());
        assertEquals(MovePlan.Kind.JUMP, service.plan(r.getId(), 3).getKind());

        service.commitAdvance(service.loadLiveCase(r.getId()), "评估落格", "ke");
        // 已到评估：报送里还捎头一格=旧报送，卷宗不回头
        assertEquals(MovePlan.Kind.STALE, service.plan(r.getId(), 0).getKind());
        assertEquals(MovePlan.Kind.DUP, service.plan(r.getId(), 1).getKind());
        assertEquals(MovePlan.Kind.ADVANCE, service.plan(r.getId(), 2).getKind());
    }

    @Test
    public void 格次照留痕逐笔数出_不信卷体手记() {
        TBarrCaseFlow r = service.openCase(seeded(), "ke");
        service.commitAdvance(service.loadLiveCase(r.getId()), "", "ke");
        service.commitAdvance(service.loadLiveCase(r.getId()), "", "ke");
        // 库读回的格次，是照留痕数的，不是报送推的
        assertEquals(2, service.currentStage(r.getId()));
        assertEquals(2, service.selectTBarrCaseFlowById(r.getId()).getStage());
    }

    // ============================ 二、进只一格 / 退一格沉底 ============================

    @Test
    public void 进一格走到结清即封住() {
        TBarrCaseFlow r = service.openCase(seeded(), "ke");
        service.commitAdvance(service.loadLiveCase(r.getId()), "评估", "ke");
        service.commitAdvance(service.loadLiveCase(r.getId()), "验收", "ke");
        TBarrCaseFlow settled = service.commitAdvance(service.loadLiveCase(r.getId()), "结清", "ke");
        assertEquals(3, settled.getStage());
        assertEquals(TBarrCaseFlow.END_SETTLE, settled.getEndReason());
        assertEquals(TBarrCaseFlow.STATUS_TERMINAL, settled.getStatus());
        assertEquals(TBarrCaseLog.ACTION_SETTLE, lastLog(r.getId()).getAction());
    }

    @Test
    public void 退一格旧笔沉底_重走另起一轮_轮次记在卷上() {
        TBarrCaseFlow r = service.openCase(seeded(), "ke");
        service.commitAdvance(service.loadLiveCase(r.getId()), "评估头一回", "ke"); // stage1 r1
        service.addNote(r.getId(), "评估格补一句", "ke");                            // stage1 r1 seq2
        service.commitAdvance(service.loadLiveCase(r.getId()), "验收头一回", "ke"); // stage2 r1
        service.commitRollback(service.loadLiveCase(r.getId()), "退评估补料", "ke"); // ->stage1 r2

        TBarrCaseFlow at1 = service.selectTBarrCaseFlowById(r.getId());
        assertEquals(1, at1.getStage());
        assertEquals("1|2|1|1", at1.getStageRounds(), "评估格轮次+1，余格不动");

        // 旧轮两笔（进格+按语）仍在第一轮，沉底不抹
        List<TBarrCaseLog> round1 = logsOf(r.getId()).stream()
                .filter(l -> l.getStage() == 1 && l.getRoundNo() == 1).collect(Collectors.toList());
        assertEquals(2, round1.size());
        // 退回笔落在评估格第二轮头一行，从1重新攒
        TBarrCaseLog back = logsOf(r.getId()).stream()
                .filter(l -> l.getStage() == 1 && l.getRoundNo() == 2).findFirst().orElse(null);
        assertNotNull(back);
        assertEquals(TBarrCaseLog.ACTION_ROLLBACK, back.getAction());
        assertEquals(1, back.getStageRoundSeq());
        assertEquals("不可", back.getVerdict());

        // 重走到验收：验收格早先那笔在第一轮，另起第二轮
        service.commitAdvance(service.loadLiveCase(r.getId()), "验收第二回", "ke");
        TBarrCaseFlow at2 = service.selectTBarrCaseFlowById(r.getId());
        assertEquals(2, at2.getStage());
        assertEquals("1|2|2|1", at2.getStageRounds());
        List<TBarrCaseLog> acceptR2 = logsOf(r.getId()).stream()
                .filter(l -> l.getStage() == 2 && l.getRoundNo() == 2).collect(Collectors.toList());
        assertEquals(1, acceptR2.size());
        assertEquals(1, acceptR2.get(0).getStageRoundSeq(), "重走该格从1重新攒");
    }

    @Test
    public void 头一格退不回() {
        TBarrCaseFlow r = service.openCase(seeded(), "ke");
        assertNull(service.commitRollback(service.loadLiveCase(r.getId()), "再退", "ke"));
        assertEquals(0, service.selectTBarrCaseFlowById(r.getId()).getStage());
    }

    // ============================ 三、一收尾四条路全拦、终态仍列得出 ============================

    @Test
    public void 结清封死后推进回退按语收口一律请不动_册子上仍翻得着() {
        TBarrCaseFlow r = service.openCase(seeded(), "ke");
        for (int i = 0; i < 3; i++) {
            service.commitAdvance(service.loadLiveCase(r.getId()), "走", "ke");
        }
        assertNull(service.loadLiveCase(r.getId()), "封死后装载活卷请不出");
        assertNull(service.commitAdvance(service.selectTBarrCaseFlowById(r.getId()), "x", "ke"));
        assertNull(service.commitRollback(service.selectTBarrCaseFlowById(r.getId()), "x", "ke"));
        assertNull(service.addNote(r.getId(), "再补一句", "ke"));
        assertNull(service.close(r.getId(), TBarrCaseFlow.END_WITHDRAW, "x", "ke"), "封死的不能再封");

        // 停在终态那一格：屏上照旧列得出来、单卷也翻得着，只是改不动
        List<TBarrCaseFlow> list = service.selectTBarrCaseFlowList(new QueryWrapper<>());
        assertEquals(1, list.size());
        assertEquals(3, list.get(0).getStage());
        assertEquals(TBarrCaseFlow.STATUS_TERMINAL, list.get(0).getStatus());
        assertNotNull(service.selectTBarrCaseFlowById(r.getId()));
    }

    @Test
    public void 中途撤申请与底册并走都算收尾封住_停在原格() {
        TBarrCaseFlow a = service.openCase(seeded(), "ke");
        service.commitAdvance(service.loadLiveCase(a.getId()), "评估", "ke");
        TBarrCaseFlow withdrawn = service.close(a.getId(), TBarrCaseFlow.END_WITHDRAW, "住户撤了", "ke");
        assertEquals(TBarrCaseFlow.END_WITHDRAW, withdrawn.getEndReason());
        assertEquals(TBarrCaseFlow.STATUS_TERMINAL, withdrawn.getStatus());
        assertEquals(1, withdrawn.getStage(), "中途止住就封在评估格，不往前挪");
        assertEquals(TBarrCaseLog.ACTION_WITHDRAW, lastLog(a.getId()).getAction());

        TBarrCaseFlow b = service.openCase(seeded(), "ke2");
        // 同一住户另一点位才能起第二张（内存库不过唯一键，这里直接造）
        b.setOwnerCode("HZ-2");
        TBarrCaseFlow merged = service.close(b.getId(), TBarrCaseFlow.END_MERGE, "底册并走", "ke");
        assertEquals(TBarrCaseFlow.END_MERGE, merged.getEndReason());
        assertEquals(TBarrCaseLog.ACTION_MERGE, lastLog(b.getId()).getAction());

        // 结清只由走到末格落下，不许拿收尾口硬封
        TBarrCaseFlow c = service.openCase(seeded(), "ke3");
        c.setOwnerCode("HZ-3");
        assertNull(service.close(c.getId(), TBarrCaseFlow.END_SETTLE, "x", "ke"));
    }

    // ============================ 四、留痕段序与并发 ============================

    @Test
    public void 逐层留痕层号连续_单拎一段也站得稳() {
        TBarrCaseFlow r = service.openCase(seeded(), "ke");
        service.commitAdvance(service.loadLiveCase(r.getId()), "评估", "ke");
        service.commitAdvance(service.loadLiveCase(r.getId()), "验收", "ke");
        List<TBarrCaseLog> trace = service.trace(r.getId());
        assertEquals(3, trace.size());
        for (int i = 0; i < trace.size(); i++) {
            assertEquals(i + 1, trace.get(i).getLayerNo(), "层号连续，从末往回数对得住受理簿");
        }
        // 单拎一段：格/轮/次第/原话/经办都齐，自己站得稳
        TBarrCaseLog one = trace.get(1);
        assertEquals(1, one.getStage());
        assertEquals(1, one.getRoundNo());
        assertEquals("评估", one.getNote());
        assertEquals("ke", one.getOperator());
    }

    @Test
    public void 并发两笔撞进同一秒_各记各的先后_不并不漏() throws Exception {
        TBarrCaseFlow r = service.openCase(seeded(), "ke");
        service.commitAdvance(service.loadLiveCase(r.getId()), "先进评估", "ke"); // 停在评估格
        int threads = 2;
        int per = 60;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        for (int t = 0; t < threads; t++) {
            final String who = "u" + t;
            Thread th = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < per; i++) {
                        TBarrCaseFlow live = service.loadLiveCase(r.getId());
                        service.addNote(live.getId(), who + "-按语" + i, who);
                    }
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            });
            th.start();
        }
        start.countDown();
        assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(errors.isEmpty(), "并发留痕不许有漏笔：" + errors);

        List<TBarrCaseLog> mine = logsOf(r.getId());
        // 起卷+进格两层，外加 120 笔按语
        assertEquals(2 + threads * per, mine.size());
        Set<Integer> layers = new HashSet<>();
        Set<String> stageRoundSeqs = new HashSet<>();
        for (TBarrCaseLog l : mine) {
            assertTrue(layers.add(l.getLayerNo()), "层号不许重：" + l.getLayerNo());
            stageRoundSeqs.add(l.getStage() + "#" + l.getRoundNo() + "#" + l.getStageRoundSeq());
        }
        assertEquals(mine.size(), layers.size());
        long evalNotes = mine.stream().filter(l -> l.getStage() == 1 && TBarrCaseLog.ACTION_NOTE.equals(l.getAction())).count();
        assertEquals(threads * per, evalNotes, "两条来路各记各的，一条不漏");
        // 本格本轮次第 1..120 各出现一次（撞键重发也不并号）
        Set<Integer> seqs = mine.stream().filter(l -> l.getStage() == 1 && l.getRoundNo() == 1
                && TBarrCaseLog.ACTION_NOTE.equals(l.getAction()))
                .map(TBarrCaseLog::getStageRoundSeq).collect(Collectors.toSet());
        assertEquals(threads * per, seqs.size());
    }

    // ============================ 五、在办计数 ============================

    @Test
    public void 在办张数随起卷加一封卷减一_与逐格点同一口径() {
        assertEquals(0, service.activeCount());
        TBarrCaseFlow a = service.openCase(seeded(), "ke");                       // 在办（头一格也算）
        TBarrCaseFlow b = service.openCase(seeded(), "ke");
        b.setOwnerCode("HZ-2");
        service.commitAdvance(service.loadLiveCase(b.getId()), "评估", "ke");      // 在办
        TBarrCaseFlow c = service.openCase(seeded(), "ke");
        c.setOwnerCode("HZ-3");
        for (int i = 0; i < 3; i++) {
            service.commitAdvance(service.loadLiveCase(c.getId()), "走", "ke");    // 结清封
        }
        assertEquals(2, service.activeCount(), "起两卷在办、一卷结清减一");
        service.close(a.getId(), TBarrCaseFlow.END_WITHDRAW, "撤", "ke");          // 撤申请再减一
        assertEquals(1, service.activeCount());
        assertFalse(service.selectTBarrCaseFlowList(new QueryWrapper<>()).isEmpty(),
                "封死的仍在台账上，只是改不动");
    }
}
