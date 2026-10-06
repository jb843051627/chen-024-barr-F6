package com.fc.v2.common.exception;

/**
 * 一户一档改造卷宗业务规则异常。
 * 打回去必须讲清毛病落在哪一处（如"住户代号：空着，这卷起不来"），不许只丢一句"办不成"。
 *
 * @author fuce
 * @date 2026-10-06
 */
public class CaseFileRuleException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public CaseFileRuleException(String message) {
        super(message);
    }
}
