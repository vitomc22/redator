package com.redator.corretor.service;

import com.redator.corretor.model.EvaluationProfile;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class BudgetGuard {

    private static final double DEFAULT_LIMIT_USD = 25.0;

    private final double limitUsd;
    private double spentUsd;

    public BudgetGuard() {
        this(DEFAULT_LIMIT_USD);
    }

    public BudgetGuard(double limitUsd) {
        if (limitUsd <= 0) {
            throw new IllegalArgumentException("limite de orcamento deve ser positivo");
        }
        this.limitUsd = limitUsd;
    }

    public synchronized double reserve(EvaluationProfile profile) {
        double cost = switch (profile) {
            case CHEAP -> 0.002;
            case NORMAL -> 0.008;
            case PREMIUM -> 0.015;
        };

        if (spentUsd + cost > limitUsd) {
            throw new IllegalStateException(
                    "orcamento excedido: custo estimado " + (spentUsd + cost) + " USD para limite de " + limitUsd + " USD"
            );
        }

        spentUsd += cost;
        return cost;
    }

    public double spentUsd() {
        return spentUsd;
    }
}
