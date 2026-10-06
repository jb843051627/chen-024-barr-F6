package com.fc.v2.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.common.base.BaseController;
import com.fc.v2.common.domain.AjaxResult;
import com.fc.v2.common.domain.ResultTable;
import com.fc.v2.common.exception.CaseFileRuleException;
import com.fc.v2.common.log.Log;
import com.fc.v2.model.auto.TBarrCaseFlow;
import com.fc.v2.model.custom.CaseFileForm;
import com.fc.v2.service.ITBarrCaseFileService;
import com.fc.v2.service.ITBarrCaseFlowService;
import com.fc.v2.shiro.util.ShiroUtils;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.*;

/**
 * 一户一档改造卷宗 Controller（流转入口：起卷、进格、退格、收尾、板面都过办事口那一层）
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
    private ITBarrCaseFlowService barrCaseFlowService;

    @Autowired
    private ITBarrCaseFileService barrCaseFileService;

    @ApiOperation(value = "流转台账跳转", notes = "流转台账跳转")
    @GetMapping("/view")
    @RequiresPermissions("barrCaseFlow:view")
    public String view(ModelMap model) {
        return prefix + "/list";
    }

    @Log(title = "一户一档改造卷宗流转台账", action = "list")
    @ApiOperation(value = "流转台账", notes = "流转台账")
    @GetMapping("/list")
    @RequiresPermissions("barrCaseFlow:list")
    @ResponseBody
    public ResultTable list(TBarrCaseFlow record) {
        QueryWrapper<TBarrCaseFlow> queryWrapper = new QueryWrapper<TBarrCaseFlow>();
        startPage();
        com.github.pagehelper.PageInfo<TBarrCaseFlow> page =
                new com.github.pagehelper.PageInfo<TBarrCaseFlow>(barrCaseFlowService.selectTBarrCaseFlowList(queryWrapper));
        return pageTable(page.getList(), page.getTotal());
    }

    @Log(title = "一户一档改造卷宗起卷", action = "open")
    @ApiOperation(value = "起卷", notes = "四样凑齐才立得起来；同户同点位只许一张没封住的卷")
    @PostMapping("/open")
    @RequiresPermissions("barrCaseFlow:open")
    @ResponseBody
    public AjaxResult open(CaseFileForm form) {
        try {
            TBarrCaseFlow r = barrCaseFileService.openCase(form, ShiroUtils.getLoginName());
            return AjaxResult.success("起卷成功，卷宗号：" + r.getBizNo());
        } catch (CaseFileRuleException e) {
            return error(e.getMessage());
        }
    }

    @Log(title = "一户一档改造卷宗推进", action = "advance")
    @ApiOperation(value = "推进一档", notes = "只迈紧邻的一格；出格前先过本格闸口")
    @PostMapping("/advance")
    @RequiresPermissions("barrCaseFlow:advance")
    @ResponseBody
    public AjaxResult advance(Long id, CaseFileForm form) {
        try {
            barrCaseFileService.advance(id, form, ShiroUtils.getLoginName());
            return success();
        } catch (CaseFileRuleException e) {
            return error(e.getMessage());
        }
    }

    @Log(title = "一户一档改造卷宗回退", action = "rollback")
    @ApiOperation(value = "回退一档", notes = "一回只收一格；被收那格的按语挪到末位留底")
    @PostMapping("/rollback")
    @RequiresPermissions("barrCaseFlow:rollback")
    @ResponseBody
    public AjaxResult rollback(Long id, CaseFileForm form) {
        try {
            barrCaseFileService.rollback(id, form, ShiroUtils.getLoginName());
            return success();
        } catch (CaseFileRuleException e) {
            return error(e.getMessage());
        }
    }

    @Log(title = "一户一档改造卷宗收尾", action = "sealWithdraw")
    @ApiOperation(value = "收尾·中途撤了申请", notes = "一收尾便封死，往后只能读")
    @PostMapping("/sealWithdraw")
    @RequiresPermissions("barrCaseFlow:seal")
    @ResponseBody
    public AjaxResult sealWithdraw(Long id, String note) {
        try {
            barrCaseFileService.sealWithdraw(id, note, ShiroUtils.getLoginName());
            return success();
        } catch (CaseFileRuleException e) {
            return error(e.getMessage());
        }
    }

    @Log(title = "一户一档改造卷宗收尾", action = "sealMergedArch")
    @ApiOperation(value = "收尾·底册并走", notes = "这一点位名下没封住的卷一并封住")
    @PostMapping("/sealMergedArch")
    @RequiresPermissions("barrCaseFlow:seal")
    @ResponseBody
    public AjaxResult sealMergedArch(String siteNo) {
        try {
            int n = barrCaseFileService.sealMergedArch(siteNo, ShiroUtils.getLoginName());
            return AjaxResult.success("底册并走收尾完成，封住 " + n + " 张");
        } catch (CaseFileRuleException e) {
            return error(e.getMessage());
        }
    }

    @Log(title = "一户一档改造卷宗板面", action = "board")
    @ApiOperation(value = "板面", notes = "在办张数与逐格点数同一回算")
    @GetMapping("/board")
    @RequiresPermissions("barrCaseFlow:board")
    @ResponseBody
    public AjaxResult board() {
        return AjaxResult.successData(0, barrCaseFileService.board());
    }
}
