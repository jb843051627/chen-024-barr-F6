package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.common.exception.CaseFileRuleException;
import com.fc.v2.mapper.auto.TBarrArchMapper;
import com.fc.v2.mapper.auto.TBarrCaseFlowMapper;
import com.fc.v2.mapper.auto.TBarrJointSignBillMapper;
import com.fc.v2.mapper.auto.TBarrPayRowMapper;
import com.fc.v2.mapper.auto.TBarrSiteCardMapper;
import com.fc.v2.model.auto.TBarrArch;
import com.fc.v2.model.auto.TBarrCaseFlow;
import com.fc.v2.model.auto.TBarrJointSignBill;
import com.fc.v2.model.auto.TBarrPayRow;
import com.fc.v2.model.auto.TBarrSiteCard;
import com.fc.v2.model.custom.CaseFileBoard;
import com.fc.v2.model.custom.CaseFileForm;
import com.fc.v2.model.custom.TierVerdict;
import com.fc.v2.service.ITBarrCaseFileService;
import com.fc.v2.service.ITBarrCaseFlowService;
import com.fc.v2.service.ITBarrTierLineService;
import com.fc.v2.util.SnowflakeIdWorker;
import com.fc.v2.util.StringUtils;

/**
 * 一户一档改造卷宗·办事口 Service业务层处理——另起的一层，只调本体不碰本体。
 *
 * 格次：只认本体那一处数出来的（stage），报送里捎来的号只算一句转述，收进来不作数；
 *      写库按数出来的写，读回也按数出来的读（读回一律走本体回查）。
 * 留痕：每一格写过的话、落在哪一刻、谁经的手，一层一层叠在卷面上；
 *      层数与先后只在这一处排定，别处不许替卷面补写一句。
 * 进退：进只迈紧邻的一格、退一回只收一格；被收那格的按语挪到末位留底（退格留底），
 *      重走这一格重新攒起；同一格重复递上来只认头一遍，不再另叠一层。
 * 收尾：走到结清、中途撤了申请、底册那一处并走了，三条路都算收了尾；
 *      一收尾便封死，往后只能读，整卷抽不走，回头挪半格也不行。
 *
 * @author fuce
 * @date 2026-10-06
 */
@Service
public class TBarrCaseFileServiceImpl implements ITBarrCaseFileService {

    /** 头一格：核卷 */
    private static final int STAGE_FIRST = 0;
    /** 末一格：结清。走到此格即封住（与本体同一把尺） */
    private static final int STAGE_TERMINAL = 3;
    /** 格名：0核卷 1评估 2验收 3结清 */
    private static final String[] STAGE_NAMES = {"核卷", "评估", "验收", "结清"};
    /** 卷宗情形：已封住（与本体 deriveStatus 同一把尺：到末格即封住） */
    private static final int STATUS_SEALED = 2;
    /** 核销行情形：已收下（核销那一步走过了） */
    private static final int PAY_ROW_LANDED = 1;
    /** 退格留底的按语尾巴：带这个记号的不与后补之笔并排，重走那格重新攒起 */
    private static final String MARK_REVERTED = "（退格留底）";
    /** 卷面一层的摆法：[格次][落在哪一刻][谁经的手] 写下的话 */
    private static final Pattern LINE = Pattern.compile("^\\[(\\d+)\\]\\[[^\\]]*\\]\\[([^\\]]*)\\] (.*)$");
    /** 时刻的摆法：与卷面现行那张一致 */
    private static final String STAMP_PATTERN = "yyyy-MM-dd HH:mm:ss";
    /** 卷宗号系统取号 */
    private static final SnowflakeIdWorker ID_WORKER = new SnowflakeIdWorker(0, 0);

    /** 本体：格次与留痕的唯一出处，原先那一套形状锁住，本层只调它 */
    @Autowired
    private ITBarrCaseFlowService caseFlowService;

    /** 与本体同一张表：起卷落库、收尾封卷、退格挪按语走这里 */
    @Autowired
    private TBarrCaseFlowMapper caseFlowMapper;

    @Autowired
    private TBarrArchMapper archMapper;

    @Autowired
    private TBarrJointSignBillMapper jointSignBillMapper;

    @Autowired
    private TBarrSiteCardMapper siteCardMapper;

    @Autowired
    private TBarrPayRowMapper payRowMapper;

    @Autowired
    private ITBarrTierLineService tierLineService;

    // ============================ 起卷 ============================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TBarrCaseFlow openCase(CaseFileForm form, String operator) {
        if (form == null) {
            throw new CaseFileRuleException("卷面空着，这卷起不来");
        }
        // 头一格要凑齐四样，缺一样这卷起不来
        String householdNo = cleanCode(form.getHouseholdNo(), "住户代号");
        String siteNo = cleanCode(form.getSiteNo(), "点位代号");
        int planNum = parseCount(form.getPlanNum());
        int tier = parseTier(form.getTier());
        // 报送里捎来的格次号只算一句转述：form.getReportedStage() 收下不作数，格次从头一格数起
        String op = cleanOperator(operator);

        // 卷从底册里挑出来：点位代号合不上在册底册、或底册已并走的，起不来
        TBarrArch arch = archMapper.selectOne(new QueryWrapper<TBarrArch>()
                .eq("arch_no", siteNo).eq("del_flag", 0).last("limit 1"));
        if (arch == null) {
            throw new CaseFileRuleException("点位代号「" + siteNo + "」合不上在册底册，从底册里挑不出来，这卷起不来");
        }
        if (arch.getStatus() != null && arch.getStatus() == 1) {
            throw new CaseFileRuleException("底册「" + siteNo + "」已并走停用，这卷起不来");
        }

        // 一户同一处点位同时只许一张没封住的卷：在办的、中途止住的都占着位子
        String remark = "住户=" + householdNo + "|点位=" + siteNo + "|档=" + tier;
        Integer active = caseFlowMapper.selectCount(new QueryWrapper<TBarrCaseFlow>()
                .eq("del_flag", 0)
                .lt("stage", STAGE_TERMINAL)
                .likeRight("remark", "住户=" + householdNo + "|点位=" + siteNo + "|"));
        if (active != null && active > 0) {
            throw new CaseFileRuleException("这户这处点位前一张卷还挂在半道（没封住），第二张起不来");
        }

        TBarrCaseFlow r = new TBarrCaseFlow();
        r.setBizNo(StringUtils.isNotEmpty(form.getBizNo()) ? form.getBizNo().trim()
                : "JZ-" + ID_WORKER.nextId());
        r.setStage(STAGE_FIRST);
        r.setStatus(0); // 未起——与本体同一把尺：头一格即未起
        r.setLastAction("起卷");
        r.setContent(line(STAGE_FIRST, op, "起卷：住户=" + householdNo + " 点位=" + siteNo
                + " 拟改=" + planNum + "处 档=" + TierVerdict.tierName(tier)));
        r.setRemark(remark);
        r.setDelFlag(0);
        caseFlowMapper.insert(r);
        return r;
    }

    // ============================ 进一格 ============================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TBarrCaseFlow advance(Long id, CaseFileForm form, String operator) {
        TBarrCaseFlow r = loadUnsealed(id);
        int stage = r.getStage();
        String note = requireNote(form);
        String op = cleanOperator(operator);
        // 同一格重复递上来：卷面仍只认头一遍写下那句，不再另叠一层、也不多走一格
        if (alreadyRecorded(r.getContent(), stage, op, note)) {
            return r;
        }
        // 出格前先过本格闸口：哪一格的条件没满，哪一格就出不得
        if (stage == 1) {
            checkAssessGate(form, r);
        } else if (stage == 2) {
            checkAcceptGate(form, r);
            checkSettleGate(r);
        }
        // 报送里捎来的号只算转述：挪格只走本体这一处，本体数出来是哪格就是哪格
        TBarrCaseFlow moved = caseFlowService.advance(id, recordOf(op, note));
        if (moved == null) {
            throw new CaseFileRuleException("这一格请不动：本体没认这一笔");
        }
        return moved;
    }

    // ============================ 退一格 ============================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TBarrCaseFlow rollback(Long id, CaseFileForm form, String operator) {
        TBarrCaseFlow r = loadUnsealed(id);
        int stage = r.getStage();
        if (stage <= STAGE_FIRST) {
            throw new CaseFileRuleException("已经落在头一格，前面没有格，退不动");
        }
        String note = requireNote(form);
        String op = cleanOperator(operator);
        // 同一格重复递上来：只认头一遍
        if (alreadyRecorded(r.getContent(), stage, op, note)) {
            return r;
        }
        TBarrCaseFlow moved = caseFlowService.rollback(id, recordOf(op, note));
        if (moved == null) {
            throw new CaseFileRuleException("这一格退不动：本体没认这一笔");
        }
        // 被收那格里早先落下的按语挪到末位留底，不与后补之笔并排；重走这一格从这一格重新攒起
        String relocated = relocateReverted(moved.getContent(), stage);
        if (!relocated.equals(moved.getContent())) {
            moved.setContent(relocated);
            caseFlowMapper.updateById(moved);
        }
        return moved;
    }

    // ============================ 收尾两条路 ============================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TBarrCaseFlow sealWithdraw(Long id, String note, String operator) {
        TBarrCaseFlow r = loadUnsealed(id);
        String extra = note == null || note.trim().isEmpty() ? "" : "：" + note.trim();
        seal(r, "中途撤了申请" + extra, cleanOperator(operator));
        return caseFlowService.selectTBarrCaseFlowById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int sealMergedArch(String siteNo, String operator) {
        String site = cleanCode(siteNo, "点位代号");
        TBarrArch arch = archMapper.selectOne(new QueryWrapper<TBarrArch>()
                .eq("arch_no", site).eq("del_flag", 0).last("limit 1"));
        boolean merged = arch == null || (arch.getStatus() != null && arch.getStatus() == 1);
        if (!merged) {
            throw new CaseFileRuleException("底册「" + site + "」还在册，谈不上并走，这条收尾路走不得");
        }
        List<TBarrCaseFlow> open = caseFlowMapper.selectList(new QueryWrapper<TBarrCaseFlow>()
                .eq("del_flag", 0)
                .lt("stage", STAGE_TERMINAL)
                .like("remark", "|点位=" + site + "|"));
        String op = cleanOperator(operator);
        for (TBarrCaseFlow r : open) {
            seal(r, "底册那一处并走了", op);
        }
        return open.size();
    }

    // ============================ 回查 / 板面 ============================

    @Override
    public TBarrCaseFlow view(Long id) {
        // 读回也按那一处点出来的读：情形只由格次当场派生
        return caseFlowService.selectTBarrCaseFlowById(id);
    }

    @Override
    public CaseFileBoard board() {
        // 在办张数与逐格点数同一回算：回库一趟扫过在册卷宗，不许一头 count 一头手填
        List<TBarrCaseFlow> all = caseFlowService.selectTBarrCaseFlowList(new QueryWrapper<TBarrCaseFlow>());
        int[] atStage = new int[STAGE_TERMINAL + 1];
        int sealed = 0;
        for (TBarrCaseFlow r : all) {
            int s = r.getStage() == null ? STAGE_FIRST : r.getStage();
            s = Math.max(STAGE_FIRST, Math.min(STAGE_TERMINAL, s));
            atStage[s]++;
            if (s >= STAGE_TERMINAL) {
                sealed++;
            }
        }
        int sum = 0;
        for (int n : atStage) {
            sum += n;
        }
        if (sum != all.size()) {
            throw new CaseFileRuleException("逐格点数（" + sum + "）与在册张数（" + all.size() + "）对不上");
        }
        CaseFileBoard board = new CaseFileBoard();
        board.setRunningTotal(all.size() - sealed);
        board.setSealedTotal(sealed);
        board.setAtStage(atStage);
        return board;
    }

    // ============================ 闸口：只在这一处裁量 ============================

    /** 评估格闸口：入户评估的记录落定没有、分数与定档那条答复对得上合不上 */
    private void checkAssessGate(CaseFileForm form, TBarrCaseFlow r) {
        String assessNo = form.getAssessNo() == null ? null : form.getAssessNo().trim();
        if (StringUtils.isEmpty(assessNo)) {
            throw new CaseFileRuleException("入户评估的记录还没落定（记录号空着），评估这一格出不得");
        }
        BigDecimal score = form.getScore();
        if (score == null) {
            throw new CaseFileRuleException("评估分空着，与定档那条答复对不上，评估这一格出不得");
        }
        TierVerdict verdict = tierLineService.evaluateVerdict(score, new Date());
        if (verdict == null || verdict.getTier() == TierVerdict.TIER_NONE) {
            throw new CaseFileRuleException("这一刻请不出在位定档线，评估分对不上定档答复，评估这一格出不得");
        }
        int fileTier = tierOf(r);
        if (verdict.getTier() != fileTier) {
            throw new CaseFileRuleException("评估分落在「" + verdict.getConclusion() + "」档，与卷上所走的「"
                    + TierVerdict.tierName(fileTier) + "」档合不上，评估这一格出不得");
        }
    }

    /** 验收格闸口：签认单编没编上、结论写的是不是「可」 */
    private void checkAcceptGate(CaseFileForm form, TBarrCaseFlow r) {
        String billNo = form.getBillNo() == null ? null : form.getBillNo().trim();
        if (StringUtils.isEmpty(billNo)) {
            throw new CaseFileRuleException("签认单还没编上（单号空着），验收这一格出不得");
        }
        TBarrJointSignBill bill = jointSignBillMapper.selectOne(new QueryWrapper<TBarrJointSignBill>()
                .eq("bill_no", billNo).last("limit 1"));
        if (bill == null) {
            throw new CaseFileRuleException("签认单「" + billNo + "」翻不着，算不得编上，验收这一格出不得");
        }
        // 签讫的单子第三口必是两可，结论即「可」；没签讫结论落不了「可」
        if (bill.getStatus() == null || bill.getStatus() != TBarrJointSignBill.STATUS_FINISHED) {
            throw new CaseFileRuleException("签认单「" + billNo + "」还没签讫，结论落不了「可」，验收这一格出不得");
        }
        String site = siteOf(r);
        if (StringUtils.isNotEmpty(bill.getArchNo()) && StringUtils.isNotEmpty(site)
                && !bill.getArchNo().equals(site)) {
            throw new CaseFileRuleException("签认单「" + billNo + "」是「" + bill.getArchNo()
                    + "」那一处的，与这卷的点位合不上，验收这一格出不得");
        }
    }

    /** 结清格闸口：只认核销那一步走没走过（这一点位的卡有一行已收下） */
    private void checkSettleGate(TBarrCaseFlow r) {
        String site = siteOf(r);
        if (StringUtils.isEmpty(site)) {
            throw new CaseFileRuleException("卷上没记着点位代号，核销那一步对不上，结清这一格进不得");
        }
        List<TBarrSiteCard> cards = siteCardMapper.selectList(new QueryWrapper<TBarrSiteCard>()
                .eq("arch_no", site).eq("del_flag", 0));
        List<String> cardNos = new ArrayList<>();
        for (TBarrSiteCard c : cards) {
            if (StringUtils.isNotEmpty(c.getCardNo())) {
                cardNos.add(c.getCardNo());
            }
        }
        if (cardNos.isEmpty()) {
            throw new CaseFileRuleException("这一点位名下翻不着进度卡，核销那一步还没走过，结清这一格进不得");
        }
        Integer landed = payRowMapper.selectCount(new QueryWrapper<TBarrPayRow>()
                .in("item_code", cardNos).eq("status", PAY_ROW_LANDED).eq("del_flag", 0));
        if (landed == null || landed == 0) {
            throw new CaseFileRuleException("核销那一步还没走过（这一点位的卡没有一行已收下），结清这一格进不得");
        }
    }

    // ============================ 内部：装载与封卷 ============================

    /** 每条改动路共用的装载：翻不着的、已撤下的、已收过尾的，一律请不动 */
    private TBarrCaseFlow loadUnsealed(Long id) {
        if (id == null) {
            throw new CaseFileRuleException("不知道动的是哪一张卷");
        }
        TBarrCaseFlow r = caseFlowService.selectTBarrCaseFlowById(id);
        if (r == null || (r.getDelFlag() != null && r.getDelFlag() == 1)) {
            throw new CaseFileRuleException("这张卷翻不着，或已撤下");
        }
        if (r.getStage() != null && r.getStage() >= STAGE_TERMINAL) {
            throw new CaseFileRuleException("这张卷已收过尾：往后只能读，改不得、挪不动、抽不走");
        }
        return r;
    }

    /** 一收尾便封死：格次落到末一格（情形随格次封住），凭什么收的尾写进卷面 */
    private void seal(TBarrCaseFlow r, String reason, String op) {
        int from = r.getStage() == null ? STAGE_FIRST : r.getStage();
        r.setStage(STAGE_TERMINAL);
        r.setStatus(STATUS_SEALED); // 与本体同一把尺：到末格即封住
        r.setLastAction("收尾：" + reason);
        appendLine(r, line(STAGE_TERMINAL, op,
                "收尾：" + reason + "（卷停在" + STAGE_NAMES[Math.max(0, Math.min(STAGE_TERMINAL, from))] + "这一格收的尾）"));
        caseFlowMapper.updateById(r);
    }

    // ============================ 内部：卷面留痕 ============================

    /** 一层的摆法：[格次][落在哪一刻][谁经的手] 写下的话 */
    private String line(int stage, String op, String text) {
        return "[" + stage + "]" + recordOf(op, text);
    }

    /** 交给本体落笔的那一句：时刻与经办编进话里，本体只负责叠上卷面 */
    private String recordOf(String op, String text) {
        return "[" + new SimpleDateFormat(STAMP_PATTERN).format(new Date()) + "][" + op + "] " + text;
    }

    private void appendLine(TBarrCaseFlow r, String line) {
        String old = r.getContent();
        r.setContent(old == null || old.trim().isEmpty() ? line : old + "\n" + line);
    }

    /**
     * 同一格重复递上来：卷面已有这一格这位经办写下的同一句话（退格留底的不算），
     * 只认头一遍，不再另叠一层。两条来路各递各的（经办不同），两笔各记各的先后。
     */
    private boolean alreadyRecorded(String content, int stage, String op, String text) {
        if (content == null || content.isEmpty()) {
            return false;
        }
        for (String line : content.split("\n")) {
            if (line.endsWith(MARK_REVERTED)) {
                continue;
            }
            Matcher m = LINE.matcher(line);
            if (m.matches() && Integer.parseInt(m.group(1)) == stage
                    && m.group(2).equals(op) && m.group(3).equals(text)) {
                return true;
            }
        }
        return false;
    }

    /** 被收那格的按语挪到末位留底：原句不动、尾巴带上退格留底，先后照原序 */
    private String relocateReverted(String content, int reclaimedStage) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        List<String> stay = new ArrayList<>();
        List<String> moved = new ArrayList<>();
        for (String line : content.split("\n")) {
            Matcher m = LINE.matcher(line);
            if (m.matches() && !line.endsWith(MARK_REVERTED)
                    && Integer.parseInt(m.group(1)) == reclaimedStage) {
                moved.add(line + MARK_REVERTED);
            } else {
                stay.add(line);
            }
        }
        if (moved.isEmpty()) {
            return content;
        }
        StringBuilder sb = new StringBuilder(String.join("\n", stay));
        for (String l : moved) {
            sb.append("\n").append(l);
        }
        return sb.toString();
    }

    // ============================ 内部：起卷四样 ============================

    private String cleanCode(String raw, String field) {
        String v = raw == null ? null : raw.trim();
        if (StringUtils.isEmpty(v)) {
            throw new CaseFileRuleException(field + "：空着，这卷起不来");
        }
        if (v.contains("|") || v.contains("%") || v.contains("_") || v.contains("\n")) {
            throw new CaseFileRuleException(field + "：代号里带不得 | % _ 这类字符（" + v + "），这卷起不来");
        }
        return v;
    }

    /** 拟改的处数：只收正整数，空着、带零头、负数、不是数一律起不来 */
    private int parseCount(String raw) {
        String v = raw == null ? null : raw.trim();
        if (StringUtils.isEmpty(v)) {
            throw new CaseFileRuleException("拟改的处数：空着，这卷起不来");
        }
        if (!v.matches("^\\d+$")) {
            throw new CaseFileRuleException("拟改的处数：只收正整数（" + raw.trim() + "），这卷起不来");
        }
        try {
            int n = Integer.parseInt(v);
            if (n <= 0) {
                throw new CaseFileRuleException("拟改的处数：得是正数（" + raw.trim() + "），这卷起不来");
            }
            return n;
        } catch (NumberFormatException e) {
            throw new CaseFileRuleException("拟改的处数：数太大，这卷起不来");
        }
    }

    /** 所走的补助那一档：只认基础/提升/重点三档（1/2/3） */
    private int parseTier(Integer tier) {
        if (tier == null) {
            throw new CaseFileRuleException("所走的补助那一档：空着，这卷起不来");
        }
        if (tier < TierVerdict.TIER_BASIC || tier > TierVerdict.TIER_KEY) {
            throw new CaseFileRuleException("所走的补助那一档：只认基础/提升/重点三档（1/2/3），写的是「" + tier + "」，这卷起不来");
        }
        return tier;
    }

    private String requireNote(CaseFileForm form) {
        String note = form == null || form.getNote() == null ? null : form.getNote().trim();
        if (StringUtils.isEmpty(note)) {
            throw new CaseFileRuleException("这一格落下的话空着：每挪一格都得落一句，递不上去");
        }
        return note.replace("\n", " ").replace("\r", " ");
    }

    private String cleanOperator(String operator) {
        String op = operator == null ? null : operator.trim();
        if (StringUtils.isEmpty(op)) {
            return "系统";
        }
        return op.replace("[", "").replace("]", "").replace("\n", " ");
    }

    // ============================ 内部：卷上记着的那几样 ============================

    private String segmentOf(String remark, String prefix) {
        if (remark == null) {
            return null;
        }
        for (String seg : remark.split("\\|")) {
            if (seg.startsWith(prefix)) {
                return seg.substring(prefix.length());
            }
        }
        return null;
    }

    private String siteOf(TBarrCaseFlow r) {
        return segmentOf(r.getRemark(), "点位=");
    }

    private int tierOf(TBarrCaseFlow r) {
        String seg = segmentOf(r.getRemark(), "档=");
        if (seg == null) {
            throw new CaseFileRuleException("卷上没记着所走的补助那一档，评估这一格出不得");
        }
        try {
            return Integer.parseInt(seg.trim());
        } catch (NumberFormatException e) {
            throw new CaseFileRuleException("卷上记的补助档「" + seg + "」站不住，评估这一格出不得");
        }
    }
}
