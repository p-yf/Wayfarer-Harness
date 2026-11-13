package com.yf.wayfarer.toolcall;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 描述工具方法的参数。
 *
 * @author yyf
 * @description 为参数补充说明
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface ToolParam {

    String description() default "";
}
