package com.yf.wayfarer.toolcall;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个方法为可供模型调用的工具。
 *
 * @author yyf
 * @description 声明工具名称与用途，不写名称时使用方法名
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Tool {

    String name() default "";

    String description() default "";
}
