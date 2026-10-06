package com.fc.v2.service.impl;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TBarrCaseFlowMapper;
import com.fc.v2.mapper.auto.TBarrCaseLogMapper;
import com.fc.v2.mapper.custom.BarrCaseSeqDao;
import com.fc.v2.model.auto.TBarrCaseFlow;
import com.fc.v2.model.auto.TBarrCaseLog;
import com.fc.v2.model.custom.MovePlan;
import com.fc.v2.service.ITBarrCaseFlowService;

/**
 * 一户一档改造卷宗 Service业务层（state-machine 形状：卷宗流转内核）。
 *
 * 形状锁住、一个号进一个数出：
 * <ul>
 *   <li>格次只有一处来路——系统照 {@link TBarrCaseLog} 把走过的格一笔一笔数出来；
 *       报送里捎来的格次号顶多一句转述，隔格那一笔无效，同格重报只认头一句不另叠一层。</li>
 *   <li>层数与先后只由按卷取号（{@link BarrCaseSeqDao}）在这一处排定，别处不替卷面补一句。</li>
 *   <li>进只许紧邻一格，退一回只收一格；退到的那格旧笔沉底、重走另起一轮。</li>
 *   <li>一收尾（结清/撤申请/底册并走）便封死：此后只读，推进、回退、补按语、删抽一律拦下。</li>
 * </ul>
 * 评估、签认、核销那些联动判定不掺在本体里，另起一层（编排服务）调本内核。
 *
 * @author fuce
 * @date 2026-10-06
 */
@Service
public class TBarrCaseFlowServiceImpl implements ITBarrCaseFlowService {

    private static final int STAGE_FIRST = TBarrCaseFlow.STAGE_FIRST;
    private static final int STAGE_TERMINAL = TBarrCaseFlow.STAGE_TERMINAL;
    private static final int STAGE_COUNT = 4;

    @javax.annotation.Resource
    private TBarrCaseFlowMapper barrCaseFlowMapper;
    @javax.annotation.Resource
    private TBarrCaseLogMapper barrCaseLogMapper;
    @javax.annotation.Resource
    private BarrCaseSeqDao barrCaseSeqDao;

    // ============================ 查 ============================

    @Override
    public TBarrCaseFlow selectTBarrCaseFlowById(Long id) {
        if (id == null) {
            return null;
        }
        TBarrCaseFlow r = barrCaseFlowMapper.selectById(id);
        return r == null || nz(r.getDelFlag()) == 1 ? null : normalize(r);
    }

    @Override
    public List<TBarrCaseFlow> selectTBarrCaseFlowList(QueryWrapper<TBarrCaseFlow> queryWrapper) {
        // 已撤下留底的不上台账；封死（结清/撤/并走）的终态卷仍列得出，只是改不动
        QueryWrapper<TBarrCaseFlow> w = queryWrapper == null ? new QueryWrapper<TBarrCaseFlow>() : queryWrapper;
        w.eq("del_flag", 0);
        List<TBarrCaseFlow> list = barrCaseFlowMapper.selectList(w);
        for (TBarrCaseFlow r : list) {
            normalize(r);
        }
        return list;
    }

    @Override
    public TBarrCaseFlow loadLiveCase(Long id) {
        if (id == null) {
            return null;
        }
        TBarrCaseFlow r = barrCaseFlowMapper.selectOne(new QueryWrapper<TBarrCaseFlow>()
                .eq("id", id).eq("del_flag", 0));
        if (r == null) {
            return null;
        }
        normalize(r);
        return isSealed(r) ? null : r;
    }

    @Override
    public int currentStage(Long id) {
        if (id == null) {
            return STAGE_FIRST;
        }
        List<TBarrCaseLog> logs = barrCaseLogMapper.selectList(new QueryWrapper<TBarrCaseLog>()
                .eq("case_id", id).orderByAsc("layer_no"));
        int stage = STAGE_FIRST;
        boolean any = false;
        for (TBarrCaseLog log : logs) {
            if (isMovement(log.getAction()) && log.getToStage() != null) {
                stage = log.getToStage();
                any = true;
            }
        }
        // 旧档（还没有留痕行）退一档：照卷体记的格接住，绝不凭空挪
        if (!any) {
            TBarrCaseFlow r = barrCaseFlowMapper.selectById(id);
            if (r != null && r.getStage() != null) {
                stage = r.getStage();
            }
        }
        return stage;
    }

    @Override
    public MovePlan plan(Long id, Integer reportedStage) {
        TBarrCaseFlow r = barrCaseFlowMapper.selectOne(new QueryWrapper<TBarrCaseFlow>()
                .eq("id", id).eq("del_flag", 0));
        if (r == null) {
            return MovePlan.of(MovePlan.Kind.NO_CASE, STAGE_FIRST, STAGE_FIRST);
        }
        normalize(r);
        int stage = nz(r.getStage());
        if (isSealed(r)) {
            return MovePlan.of(MovePlan.Kind.SEALED, stage, stage);
        }
        if (reportedStage == null) {
            // 没捎号：默认报的就是紧邻的下一格
            return MovePlan.of(MovePlan.Kind.ADVANCE, stage, stage + 1);
        }
        if (reportedStage < 0 || reportedStage > STAGE_TERMINAL) {
            return MovePlan.of(MovePlan.Kind.JUMP, stage, reportedStage);
        }
        if (reportedStage == stage) {
            return MovePlan.of(MovePlan.Kind.DUP, stage, stage);
        }
        if (reportedStage < stage) {
            return MovePlan.of(MovePlan.Kind.STALE, stage, reportedStage);
        }
        if (reportedStage == stage + 1) {
            return MovePlan.of(MovePlan.Kind.ADVANCE, stage, reportedStage);
        }
        return MovePlan.of(MovePlan.Kind.JUMP, stage, reportedStage);
    }

    @Override
    public List<TBarrCaseLog> trace(Long id) {
        return barrCaseLogMapper.selectList(new QueryWrapper<TBarrCaseLog>()
                .eq("case_id", id).orderByAsc("layer_no").orderByAsc("id"));
    }

    @Override
    public int activeCount() {
        // 在办=起过卷（在册）且还没封住；屏上显的数与回库逐格点的数同一回算，都走这一道
        Integer n = barrCaseFlowMapper.selectCount(new QueryWrapper<TBarrCaseFlow>()
                .eq("del_flag", 0).isNull("end_reason").lt("stage", STAGE_TERMINAL));
        return n == null ? 0 : n;
    }

    // ============================ 起卷 ============================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TBarrCaseFlow openCase(TBarrCaseFlow seeded, String operator) {
        // 四样由外层凑齐后交进来；内核只管排号、落格、留痕
        seeded.setDelFlag(0);
        seeded.setEndReason(null);
        seeded.setStage(STAGE_FIRST);
        seeded.setStatus(TBarrCaseFlow.STATUS_ACTIVE);
        seeded.setStageRounds(initialRounds());
        barrCaseFlowMapper.insert(seeded);
        // 卷宗号系统排：以卷主键为底，本子自带号不认
        seeded.setBizNo("JZ-" + new SimpleDateFormat("yyyyMMdd").format(new Date())
                + "-" + String.format("%06d", seeded.getId() % 1000000L));
        barrCaseFlowMapper.updateById(seeded);

        barrCaseSeqDao.ensureCase(seeded.getId());
        String note = "起卷：住户" + nv(seeded.getOwnerCode()) + "、点位" + nv(seeded.getSiteNo())
                + "、拟改" + seeded.getPlanCount() + "处、走第" + seeded.getTierCode() + "档";
        writeLog(seeded, TBarrCaseLog.ACTION_OPEN, null, STAGE_FIRST, STAGE_FIRST,
                roundsOf(seeded)[STAGE_FIRST], TBarrCaseLog.VERDICT_YES, note, operator);
        seeded.setContent(note);
        seeded.setLastAction(TBarrCaseLog.ACTION_OPEN);
        barrCaseFlowMapper.updateById(seeded);
        return normalize(seeded);
    }

    // ============================ 进一格 ============================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TBarrCaseFlow commitAdvance(TBarrCaseFlow live, String note, String operator) {
        normalize(live);
        if (isSealed(live)) {
            return null;
        }
        int from = nz(live.getStage());
        if (from >= STAGE_TERMINAL) {
            return null;
        }
        int target = from + 1;
        int[] rounds = roundsOf(live);
        // 回退已把退回那格的轮次+1（退回笔落新一轮头一行），这里不重复加；
        // 越过、重入的格当前轮还留着早先的进格层，另起一轮，新一轮从1重新攒
        if (hasEntryLayer(live.getId(), target, rounds[target])) {
            rounds[target] = rounds[target] + 1;
        }
        int round = rounds[target];
        boolean settle = target == STAGE_TERMINAL;
        String action = settle ? TBarrCaseLog.ACTION_SETTLE : TBarrCaseLog.ACTION_ADVANCE;
        String line = trim(note);
        if (line == null) {
            line = stageName(target) + "落格";
        }
        writeLog(live, action, from, target, target, round, TBarrCaseLog.VERDICT_YES, line, operator);

        live.setStage(target);
        live.setStageRounds(formatRounds(rounds));
        live.setLastAction(action);
        live.setContent("[" + target + "]" + line);
        if (settle) {
            // 走到结清即封住：审批那一本与卷宗册同指此卷，此后只读
            live.setEndReason(TBarrCaseFlow.END_SETTLE);
        }
        live.setStatus(isSealed(live) ? TBarrCaseFlow.STATUS_TERMINAL : TBarrCaseFlow.STATUS_ACTIVE);
        barrCaseFlowMapper.updateById(live);
        return normalize(live);
    }

    // ============================ 退一格 ============================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TBarrCaseFlow commitRollback(TBarrCaseFlow live, String note, String operator) {
        normalize(live);
        if (isSealed(live)) {
            return null;
        }
        int from = nz(live.getStage());
        if (from <= STAGE_FIRST) {
            // 头一格前面没有格，回头挪半格也不行
            return null;
        }
        int target = from - 1;
        int[] rounds = roundsOf(live);
        // 被收那格早先落下的按语沉到旧轮末位，重走这一格另起一轮、从1重新攒
        rounds[target] = rounds[target] + 1;
        String line = trim(note);
        if (line == null) {
            line = "退回" + stageName(target) + "重来";
        }
        writeLog(live, TBarrCaseLog.ACTION_ROLLBACK, from, target, target, rounds[target],
                TBarrCaseLog.VERDICT_NO, line, operator);

        live.setStage(target);
        live.setStageRounds(formatRounds(rounds));
        live.setLastAction(TBarrCaseLog.ACTION_ROLLBACK);
        live.setContent("[" + target + "]" + line);
        live.setStatus(TBarrCaseFlow.STATUS_ACTIVE);
        barrCaseFlowMapper.updateById(live);
        return normalize(live);
    }

    // ============================ 同格按语 ============================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TBarrCaseFlow addNote(Long id, String note, String operator) {
        TBarrCaseFlow live = loadLiveCase(id);
        if (live == null) {
            return null;
        }
        String line = trim(note);
        if (line == null) {
            return null;
        }
        int stage = nz(live.getStage());
        int round = roundsOf(live)[stage];
        writeLog(live, TBarrCaseLog.ACTION_NOTE, null, null, stage, round, null, line, operator);
        live.setLastAction(TBarrCaseLog.ACTION_NOTE);
        live.setContent("[" + stage + "]" + line);
        barrCaseFlowMapper.updateById(live);
        return normalize(live);
    }

    // ============================ 收尾封卷 ============================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TBarrCaseFlow close(Long id, String reason, String note, String operator) {
        TBarrCaseFlow live = loadLiveCase(id);
        if (live == null) {
            return null;
        }
        if (!TBarrCaseFlow.END_WITHDRAW.equals(reason) && !TBarrCaseFlow.END_MERGE.equals(reason)) {
            // 结清只由走到末格落下，不许拿这道口封
            return null;
        }
        int stage = nz(live.getStage());
        int round = roundsOf(live)[stage];
        String line = trim(note);
        if (line == null) {
            line = TBarrCaseFlow.END_WITHDRAW.equals(reason) ? "住户中途撤申请，收尾封卷" : "底册那一处并走，收尾封卷";
        }
        String action = TBarrCaseFlow.END_WITHDRAW.equals(reason)
                ? TBarrCaseLog.ACTION_WITHDRAW : TBarrCaseLog.ACTION_MERGE;
        writeLog(live, action, stage, stage, stage, round, TBarrCaseLog.VERDICT_NO, line, operator);

        live.setEndReason(reason);
        live.setStatus(TBarrCaseFlow.STATUS_TERMINAL);
        live.setLastAction(action);
        live.setContent("[" + stage + "]" + line);
        barrCaseFlowMapper.updateById(live);
        return normalize(live);
    }

    // ============================ 内部：单一出处 ============================

    /** 卷体接住：格次照留痕当场重数，情形照格次/封卷当场派生，旧档空值有兜底 */
    private TBarrCaseFlow normalize(TBarrCaseFlow r) {
        if (r.getDelFlag() == null) {
            r.setDelFlag(0);
        }
        if (r.getStageRounds() == null || r.getStageRounds().trim().isEmpty()) {
            r.setStageRounds(initialRounds());
        }
        if (r.getId() != null && hasAnyLog(r.getId())) {
            r.setStage(currentStage(r.getId()));
        } else if (r.getStage() == null) {
            r.setStage(STAGE_FIRST);
        }
        // 起过卷即在办；一封住（结清到末格／撤申请／底册并走）即终态
        r.setStatus(isSealed(r) ? TBarrCaseFlow.STATUS_TERMINAL : TBarrCaseFlow.STATUS_ACTIVE);
        return r;
    }

    private boolean isSealed(TBarrCaseFlow r) {
        if (r.getEndReason() != null && !r.getEndReason().trim().isEmpty()) {
            return true;
        }
        // 旧档没有封卷缘故、但格次已到末格：按已结清封住接住
        return nz(r.getStage()) >= STAGE_TERMINAL;
    }

    private boolean isMovement(String action) {
        return TBarrCaseLog.ACTION_OPEN.equals(action)
                || TBarrCaseLog.ACTION_ADVANCE.equals(action)
                || TBarrCaseLog.ACTION_ROLLBACK.equals(action)
                || TBarrCaseLog.ACTION_SETTLE.equals(action);
    }

    private boolean hasAnyLog(Long caseId) {
        Integer n = barrCaseLogMapper.selectCount(new QueryWrapper<TBarrCaseLog>().eq("case_id", caseId));
        return n != null && n > 0;
    }

    private boolean hasEntryLayer(Long caseId, int stage, int round) {
        Integer n = barrCaseLogMapper.selectCount(new QueryWrapper<TBarrCaseLog>()
                .eq("case_id", caseId).eq("stage", stage).eq("round_no", round)
                .in("action", Arrays.asList(TBarrCaseLog.ACTION_ADVANCE, TBarrCaseLog.ACTION_SETTLE)));
        return n != null && n > 0;
    }

    private int countStageRound(Long caseId, int stage, int round) {
        Integer n = barrCaseLogMapper.selectCount(new QueryWrapper<TBarrCaseLog>()
                .eq("case_id", caseId).eq("stage", stage).eq("round_no", round));
        return n == null ? 0 : n;
    }

    private void writeLog(TBarrCaseFlow r, String action, Integer from, Integer to,
                          int stage, int round, String verdict, String note, String operator) {
        // layer_no 按卷发号：并发两笔各拿各的号，先后由号钉死；撞本格本轮次第就重发一次，不并不漏
        RuntimeException last = null;
        for (int attempt = 0; attempt < 8; attempt++) {
            barrCaseSeqDao.ensureCase(r.getId());
            barrCaseSeqDao.bump(r.getId());
            int layer = (int) barrCaseSeqDao.currentValue();
            int seq = countStageRound(r.getId(), stage, round) + 1;
            TBarrCaseLog log = new TBarrCaseLog();
            log.setCaseId(r.getId());
            log.setCaseNo(r.getBizNo());
            log.setAction(action);
            log.setFromStage(from);
            log.setToStage(to);
            log.setStage(stage);
            log.setRoundNo(round);
            log.setLayerNo(layer);
            log.setStageRoundSeq(seq);
            log.setVerdict(verdict);
            log.setNote(note);
            log.setOperator(operator);
            log.setCreateTime(new Date());
            try {
                barrCaseLogMapper.insert(log);
                return;
            } catch (org.springframework.dao.DuplicateKeyException e) {
                last = e;
            }
        }
        throw last;
    }

    private String initialRounds() {
        return formatRounds(new int[]{1, 1, 1, 1});
    }

    private int[] roundsOf(TBarrCaseFlow r) {
        int[] rounds = new int[STAGE_COUNT];
        Arrays.fill(rounds, 1);
        String s = r.getStageRounds();
        if (s != null && !s.trim().isEmpty()) {
            String[] parts = s.split("\\|");
            for (int i = 0; i < parts.length && i < STAGE_COUNT; i++) {
                try {
                    rounds[i] = Integer.parseInt(parts[i].trim());
                } catch (NumberFormatException ignore) {
                    rounds[i] = 1;
                }
            }
        }
        return rounds;
    }

    private String formatRounds(int[] rounds) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < STAGE_COUNT; i++) {
            if (i > 0) {
                sb.append('|');
            }
            sb.append(rounds[i]);
        }
        return sb.toString();
    }

    private String stageName(int stage) {
        switch (stage) {
            case 0:
                return "核卷";
            case 1:
                return "评估";
            case 2:
                return "验收";
            case 3:
                return "结清";
            default:
                return "第" + stage + "格";
        }
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private String trim(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private String nv(String s) {
        return s == null ? "" : s;
    }
}
