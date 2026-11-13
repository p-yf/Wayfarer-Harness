package com.yf.wayfarer.model;

/**
 * 模型调用失败。
 *
 * @author yyf
 * @description 包装底层 SDK 的连接、协议与流内异常
 */
public class ModelException extends RuntimeException {

    public ModelException(String message) {
        super(message);
    }

    public ModelException(String message, Throwable cause) {
        super(message, cause);
    }
}
