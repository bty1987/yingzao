package com.yingzao.core.web;

/**
 * 业务异常:必须携带模块码段内的错误码。
 * 码段约定:内核 100000-199999;各 addon 按 addon.yml 声明的独占码段。
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
