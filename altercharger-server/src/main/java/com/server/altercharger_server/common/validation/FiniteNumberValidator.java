package com.server.altercharger_server.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class FiniteNumberValidator implements ConstraintValidator<FiniteNumber, Number> {
    private double min;
    private double max;
    private boolean minInclusive;

    @Override
    public void initialize(FiniteNumber constraint) {
        min = constraint.min();
        max = constraint.max();
        minInclusive = constraint.minInclusive();
    }

    @Override
    public boolean isValid(Number value, ConstraintValidatorContext context) {
        if (value == null) return true;
        double number = value.doubleValue();
        return Double.isFinite(number) && (minInclusive ? number >= min : number > min) && number <= max;
    }
}
