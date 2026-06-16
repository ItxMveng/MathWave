package com.example.enib.domain;

import net.objecthunter.exp4j.Expression;
import net.objecthunter.exp4j.ExpressionBuilder;
import net.objecthunter.exp4j.function.Function;

import java.util.ArrayList;
import java.util.List;

public class MathFunctionParser {

    // ln(x) alias — exp4j's built-in "log" is already the natural log,
    // but users expect to type "ln" from the scientific keyboard.
    private static final Function FN_LN = new Function("ln", 1) {
        @Override
        public double apply(double... args) {
            return Math.log(args[0]);
        }
    };

    public static class ParseResult {
        public final List<float[]> points;
        public final String errorMessage;
        public final boolean isSuccess;

        private ParseResult(List<float[]> points) {
            this.points = points;
            this.errorMessage = null;
            this.isSuccess = true;
        }

        private ParseResult(String errorMessage) {
            this.points = null;
            this.errorMessage = errorMessage;
            this.isSuccess = false;
        }

        public static ParseResult success(List<float[]> points) { return new ParseResult(points); }
        public static ParseResult failure(String msg)           { return new ParseResult(msg); }
    }

    public static ParseResult evaluate(String expression, double xMin, double xMax, int numPoints) {
        try {
            Expression exp = new ExpressionBuilder(expression)
                    .variable("x")
                    .functions(FN_LN)
                    .build();

            var validation = exp.validate(false);
            if (!validation.isValid()) {
                return ParseResult.failure("Expression invalide : " + validation.getErrors().get(0));
            }

            List<float[]> points = new ArrayList<>();
            double step = (xMax - xMin) / numPoints;

            for (int i = 0; i <= numPoints; i++) {
                double x = xMin + i * step;
                exp.setVariable("x", x);
                double y = exp.evaluate();
                if (Double.isFinite(y)) {
                    points.add(new float[]{(float) x, (float) y});
                }
            }

            if (points.isEmpty()) {
                return ParseResult.failure("La fonction ne produit aucune valeur finie sur cet intervalle");
            }

            return ParseResult.success(points);

        } catch (Exception e) {
            return ParseResult.failure("Erreur de syntaxe : " + e.getMessage());
        }
    }
}
