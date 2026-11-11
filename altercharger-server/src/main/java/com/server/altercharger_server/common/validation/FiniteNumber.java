package com.server.altercharger_server.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Documented
@Constraint(validatedBy = FiniteNumberValidator.class)
@Target({FIELD, PARAMETER, RECORD_COMPONENT, TYPE_USE, ANNOTATION_TYPE})
@Retention(RUNTIME)
public @interface FiniteNumber {
    String message() default "허용 범위 안의 유한한 수여야 합니다.";
    double min() default -Double.MAX_VALUE;
    double max() default Double.MAX_VALUE;
    boolean minInclusive() default true;
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
