package com.fraudplatform.decision.strategy;

import com.fraudplatform.commons.error.ErrorCode;
import com.fraudplatform.commons.error.PlatformException;

import java.util.List;
import java.util.Map;

public class StrategyValidationException extends PlatformException {

    private final List<String> errors;

    public StrategyValidationException(List<String> errors) {
        super(ErrorCode.STRATEGY_INVALID, "Strategy failed validation with " + errors.size() + " error(s)",
                Map.of("errors", List.copyOf(errors)));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }
}
