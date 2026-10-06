package com.fc.v2.controller.admin;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.common.base.BaseController;
import com.fc.v2.common.domain.AjaxResult;
import com.fc.v2.common.domain.ResultTable;
import com.fc.v2.common.exception.CaseFlowRuleException;
import com.fc.v2.common.log.Log;
import com.fc.v2.mapper.auto.TBarrArchMapper;
import com.fc.v2.model.auto.TBarrArch;
import com.fc.v2.model.auto.TBarrCaseFlow;
import com.fc.v2.model.custom.CaseOpenForm;
import com.fc.v2.model.custom.CaseStageCred;
import com.fc.v2.service.ICaseFlowAppService;
import com.fc.v2.shiro.util.ShiroUtils;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 一户一档改造卷宗 Controller（state-machine 形状：卷宗流转入口）。
 * 控制层不替卷宗定格次：报送格次号只随凭据捎给编排层，隔格／重报／旧报送怎么处理由内核一处裁。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Api(value = "一户一档改造卷宗")
@Controller
@RequestMapping("/barrCaseFlow")
public class BarrCaseFlowController extends BaseController {

    private final String prefix = "admin/barrCaseFlow";

    @Autowired
    private ICaseFlowAppService caseFlowAppService;

    @Autowired
    private TBarrArchMapper barrArchMapper;

    @ApiOperation(value = "流转台账跳转", notes = "流转台账跳转")
    @GetMapping("/view")
    @RequiresPermissions("barrCaseFlow:view")
    public String view(ModelMap model) {
        model.put("activeCount", caseFlowAppService.activeCount());
        return prefix + "/list";
    }

    @Log(title = "一户一档改造卷宗流转台账", action = "list")
    @ApiOperation(value = "流转台账", notes = "封死的终态卷也列得出，只是改不动删不掉")
    @GetMapping("/list")
    @RequiresPermissions("barrCaseFlow:list")
    @ResponseBody
    public ResultTable list(TBarrCaseFlow query) {
        QueryWrapper<TBarrCaseFlow> w = new QueryWrapper<TBarrCaseFlow>()
                .orderByDesc("create_time");
        if (query.getBizNo() != null && !query.getBizNo().trim().isEmpty()) {
            w.likeRight("biz_no", query.getBizNo().trim());
        }
        if (query.getOwnerCode() != null && !query.getOwnerCode().trim().isEmpty()) {
            w.likeRight("owner_code", query.getOwnerCode().trim());
        }
        if (query.getSiteNo() != null && !query.getSiteNo().trim().isEmpty()) {
            w.likeRight("site_no", query.getSiteNo().trim());
        }
        startPage();
        com.github.pagehelper.PageInfo<TBarrCaseFlow> page =
                new com.github.pagehelper.PageInfo<TBarrCaseFlow>(caseFlowAppService.list(w));
        return pageTable(page.getList(), page.getTotal());
    }

    @ApiOperation(value = "在办张数", notes = "屏上显的与回库逐格点的同一回算")
    @GetMapping("/activeCount")
    @RequiresPermissions("barrCaseFlow:list")
    @ResponseBody
    public AjaxResult activeCount() {
        return AjaxResult.successData(200, caseFlowAppService.activeCount());
    }

    @ApiOperation(value = "起卷跳转", notes = "凑齐四样立一卷")
    @GetMapping("/add")
    @RequiresPermissions("barrCaseFlow:open")
    public String add(ModelMap model) {
        List<TBarrArch> archs = barrArchMapper.selectList(new QueryWrapper<TBarrArch>()
                .eq("del_flag", 0).eq("status", 0).orderByAsc("arch_no"));
        model.put("archList", archs);
        return prefix + "/add";
    }

    @Log(title = "一户一档卷宗起卷", action = "open")
    @ApiOperation(value = "起卷", notes = "四样凑齐、同点位只许一张没封住的卷")
    @PostMapping("/open")
    @RequiresPermissions("barrCaseFlow:open")
    @ResponseBody
    public AjaxResult open(CaseOpenForm form) {
        try {
            TBarrCaseFlow r = caseFlowAppService.open(form, ShiroUtils.getLoginName());
            return AjaxResult.success("起卷成功，卷宗号：" + r.getBizNo());
        } catch (CaseFlowRuleException e) {
            return error(e.getMessage());
        }
    }

    @Log(title = "一户一档卷宗进一格", action = "advance")
    @ApiOperation(value = "进一格", notes = "只许紧邻一格；报送格次号只作转述，凭据按要进的格捎")
    @PostMapping("/advance")
    @RequiresPermissions("barrCaseFlow:advance")
    @ResponseBody
    public AjaxResult advance(Long id, Integer reportedStage, CaseStageCred cred) {
        try {
            caseFlowAppService.advance(id, reportedStage, cred, ShiroUtils.getLoginName());
            return AjaxResult.success("已进一格");
        } catch (CaseFlowRuleException e) {
            return error(e.getMessage());
        }
    }

    @Log(title = "一户一档卷宗退一格", action = "rollback")
    @ApiOperation(value = "退一格", notes = "一回只退一格，旧笔沉底重走另起一轮")
    @PostMapping("/rollback")
    @RequiresPermissions("barrCaseFlow:rollback")
    @ResponseBody
    public AjaxResult rollback(Long id, String note) {
        try {
            caseFlowAppService.rollback(id, note, ShiroUtils.getLoginName());
            return AjaxResult.success("已退回一格，上回那格旧笔留底，重走另起一轮");
        } catch (CaseFlowRuleException e) {
            return error(e.getMessage());
        }
    }

    @Log(title = "住户撤申请收口", action = "withdraw")
    @ApiOperation(value = "住户撤申请收口", notes = "中途撤申请也算收尾，封住只读")
    @PostMapping("/withdraw")
    @RequiresPermissions("barrCaseFlow:withdraw")
    @ResponseBody
    public AjaxResult withdraw(Long id, String note) {
        try {
            caseFlowAppService.withdraw(id, note, ShiroUtils.getLoginName());
            return AjaxResult.success("住户撤申请，已收尾封卷");
        } catch (CaseFlowRuleException e) {
            return error(e.getMessage());
        }
    }

    @Log(title = "底册并走收口", action = "merge")
    @ApiOperation(value = "底册并走收口", notes = "底册那一处并走，挂它名下在办卷一并封")
    @PostMapping("/merge")
    @RequiresPermissions("barrCaseFlow:merge")
    @ResponseBody
    public AjaxResult merge(String archId, String note) {
        try {
            int n = caseFlowAppService.mergeByArch(archId, note, ShiroUtils.getLoginName()).size();
            return AjaxResult.success("底册并走，已封住在办卷 " + n + " 张");
        } catch (CaseFlowRuleException e) {
            return error(e.getMessage());
        }
    }

    @ApiOperation(value = "卷宗逐层回看", notes = "从末格数回头格，单层也站得稳")
    @GetMapping("/trace/{id}")
    @RequiresPermissions("barrCaseFlow:trace")
    @ResponseBody
    public AjaxResult trace(@PathVariable("id") Long id) {
        return AjaxResult.successData(200, caseFlowAppService.trace(id));
    }
}
