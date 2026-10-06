package com.fc.v2.common.exception;

/**
 * 一户一档改造卷宗流转业务规则异常。
 * 打回要讲清卡在哪一格、缺的是哪一样（如"核卷：拟改的处数空着"），
 * 不许只丢一句"走不了"。
 *
 * @author fuce
 * @date 2026-10-06
 */
public class CaseFlowRuleException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public CaseFlowRuleException(String message) {
        super(message);
    }
}
