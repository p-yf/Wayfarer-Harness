package com.yf.wayfarer.toolcall;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 描述工具方法的参数。
 *
 * @author yyf
 * @description 为参数补充说明与是否必填；非必填参数不进入 JSON Schema 的 required 列表
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface ToolParam {

    String description() default "";

    /**
     * 是否必填，默认 true。
     */
    boolean required() default true;
}
