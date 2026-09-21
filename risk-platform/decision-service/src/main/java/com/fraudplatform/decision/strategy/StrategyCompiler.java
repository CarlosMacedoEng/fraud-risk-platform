package com.fraudplatform.decision.strategy;

import com.fraudplatform.decision.domain.Channel;
import com.fraudplatform.decision.domain.ReasonCode;
import com.fraudplatform.decision.strategy.CompiledStrategy.Action;
import com.fraudplatform.decision.strategy.CompiledStrategy.ChannelPolicy;
import com.fraudplatform.decision.strategy.CompiledStrategy.FailMode;
import com.fraudplatform.decision.strategy.CompiledStrategy.Rule;
import com.fraudplatform.decision.strategy.CompiledStrategy.Thresholds;
import com.fraudplatform.decision.strategy.CompiledStrategy.Weights;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Validates and compiles a strategy JSON document. Collects <em>all</em> problems in one pass so an
 * analyst gets a complete list rather than fixing errors one at a time.
 */
public class StrategyCompiler {

    private static final Pattern SEMVER = Pattern.compile("^\\d+\\.\\d+\\.\\d+$");
    private static final Set<String> OPS = Set.of("eq", "neq", "gt", "gte", "lt", "lte", "between", "in", "not_in",
            "in_list", "not_in_list");
    private static final Set<String> NUMERIC_OPS = Set.of("gt", "gte", "lt", "lte", "between");

    public CompiledStrategy compile(String tenantId, JsonNode doc) {
        List<String> errors = new ArrayList<>();
        CompiledStrategy result = compile(tenantId, doc, errors);
        if (!errors.isEmpty()) {
            throw new StrategyValidationException(errors);
        }
        return result;
    }

    /** Validation only; returns the list of problems (empty = valid). */
    public List<String> validate(String tenantId, JsonNode doc) {
        List<String> errors = new ArrayList<>();
        compile(tenantId, doc, errors);
        return errors;
    }

    private CompiledStrategy compile(String tenantId, JsonNode doc, List<String> errors) {
        if (doc == null || !doc.isObject()) {
            errors.add("strategy must be a JSON object");
            return null;
        }
        if (doc.path("schemaVersion").asInt(-1) != 1) errors.add("schemaVersion must be 1");
        String customer = doc.path("customerId").asString("");
        if (!tenantId.equals(customer)) errors.add("customerId '" + customer + "' does not match tenant '" + tenantId + "'");
        String version = doc.path("version").asString("");
        if (!SEMVER.matcher(version).matches()) errors.add("version must be semantic (x.y.z), got '" + version + "'");

        JsonNode model = doc.path("model");
        String modelVersion = model.path("version").asString(null);
        if (modelVersion == null || modelVersion.isBlank()) errors.add("model.version is required");
        String challenger = model.path("challengerVersion").isNull() ? null : model.path("challengerVersion").asString(null);
        String challengerMode = model.path("challengerMode").asString(challenger == null ? null : "SHADOW");
        if (challengerMode != null && !challengerMode.equals("SHADOW")) errors.add("model.challengerMode must be SHADOW");

        JsonNode w = doc.path("weights");
        Weights weights = new Weights(unit(w, "model", errors), unit(w, "rules", errors), unit(w, "graph", errors),
                unit(w, "anomaly", errors));
        double tail = doc.path("anomaly").path("tailStartPercentile").asDouble(0.99);
        if (tail < 0.5 || tail >= 1.0) errors.add("anomaly.tailStartPercentile must be in [0.5, 1)");

        JsonNode th = doc.path("thresholds");
        Thresholds def = thresholds(th.path("default"), "thresholds.default", true, errors);
        Map<String, Thresholds> bySegment = new HashMap<>();
        for (Map.Entry<String, JsonNode> e : th.path("bySegment").properties()) {
            Thresholds t = thresholds(e.getValue(), "thresholds.bySegment." + e.getKey(), false, errors);
            bySegment.put(e.getKey(), t);
            checkEffective(t, def, "thresholds.bySegment." + e.getKey(), errors);
        }
        Map<Channel, Thresholds> byChannel = new EnumMap<>(Channel.class);
        for (Map.Entry<String, JsonNode> e : th.path("byChannel").properties()) {
            Channel ch = channel(e.getKey(), "thresholds.byChannel", errors);
            Thresholds t = thresholds(e.getValue(), "thresholds.byChannel." + e.getKey(), false, errors);
            if (ch != null) byChannel.put(ch, t);
            checkEffective(t, def, "thresholds.byChannel." + e.getKey(), errors);
        }

        Map<Channel, ChannelPolicy> policies = new EnumMap<>(Channel.class);
        for (Map.Entry<String, JsonNode> e : doc.path("channelPolicies").properties()) {
            Channel ch = channel(e.getKey(), "channelPolicies", errors);
            String mode = e.getValue().path("failMode").asString("");
            FailMode fm = null;
            try {
                fm = FailMode.valueOf(mode);
            } catch (IllegalArgumentException ex) {
                errors.add("channelPolicies." + e.getKey() + ".failMode must be APPROVE, REVIEW or DECLINE");
            }
            JsonNode max = e.getValue().path("maxFailOpenAmount");
            Double maxAmount = max.isMissingNode() || max.isNull() ? null : max.asDouble();
            if (maxAmount != null && maxAmount < 0) errors.add("channelPolicies." + e.getKey() + ".maxFailOpenAmount must be >= 0");
            if (ch != null && fm != null) policies.put(ch, new ChannelPolicy(fm, maxAmount));
        }

        Map<String, Set<String>> lists = new HashMap<>();
        for (Map.Entry<String, JsonNode> e : doc.path("lists").properties()) {
            if (!e.getValue().isArray()) {
                errors.add("lists." + e.getKey() + " must be an array");
                continue;
            }
            Set<String> values = new LinkedHashSet<>();
            e.getValue().forEach(v -> values.add(v.asString()));
            lists.put(e.getKey(), Set.copyOf(values));
        }

        List<Rule> rules = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        compileRules(doc.path("emergencyRules"), true, lists, ids, rules, errors);
        compileRules(doc.path("rules"), false, lists, ids, rules, errors);

        if (!errors.isEmpty()) return null;
        return new CompiledStrategy(tenantId, version, modelVersion, challenger, challengerMode, weights, tail, def,
                Map.copyOf(bySegment), byChannel, policies, Map.copyOf(lists), List.copyOf(rules), checksum(doc), doc);
    }

    public static String checksum(JsonNode doc) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(doc.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static double unit(JsonNode node, String field, List<String> errors) {
        if (!node.has(field)) {
            errors.add("weights." + field + " is required");
            return 0;
        }
        double v = node.get(field).asDouble();
        if (v < 0 || v > 1) errors.add("weights." + field + " must be in [0, 1]");
        return v;
    }

    private static Thresholds thresholds(JsonNode node, String path, boolean required, List<String> errors) {
        Double review = node.has("review") ? node.get("review").asDouble() : null;
        Double decline = node.has("decline") ? node.get("decline").asDouble() : null;
        if (required && (review == null || decline == null)) errors.add(path + " requires review and decline");
        for (Double v : new Double[]{review, decline}) {
            if (v != null && (v <= 0 || v > 1)) errors.add(path + " thresholds must be in (0, 1]");
        }
        if (review != null && decline != null && review >= decline) errors.add(path + ": review must be lower than decline");
        return new Thresholds(review, decline);
    }

    private static void checkEffective(Thresholds override, Thresholds def, String path, List<String> errors) {
        if (def == null || def.review() == null || def.decline() == null) return;
        Thresholds eff = override.over(def);
        if (eff.review() >= eff.decline()) errors.add(path + ": effective review threshold must be lower than decline");
    }

    private static Channel channel(String name, String path, List<String> errors) {
        try {
            return Channel.valueOf(name);
        } catch (IllegalArgumentException e) {
            errors.add(path + ": unknown channel '" + name + "'");
            return null;
        }
    }

    private void compileRules(JsonNode arr, boolean emergency, Map<String, Set<String>> lists, Set<String> ids,
                              List<Rule> out, List<String> errors) {
        if (arr.isMissingNode() || arr.isNull()) return;
        if (!arr.isArray()) {
            errors.add((emergency ? "emergencyRules" : "rules") + " must be an array");
            return;
        }
        for (JsonNode r : arr) {
            String id = r.path("id").asString("");
            String p = "rule '" + id + "'";
            if (id.isBlank()) errors.add("every rule needs an id");
            else if (!ids.add(id)) errors.add(p + ": duplicate id");
            if (!r.path("enabled").asBoolean(true)) continue;

            Action action;
            try {
                action = Action.valueOf(r.path("action").asString("SCORE"));
            } catch (IllegalArgumentException e) {
                errors.add(p + ": action must be SCORE, REVIEW or DECLINE");
                continue;
            }
            int points = r.path("points").asInt(0);
            if (action == Action.SCORE && (points < 1 || points > 100)) errors.add(p + ": SCORE rules need points in [1, 100]");
            ReasonCode reason = null;
            try {
                reason = ReasonCode.valueOf(r.path("reasonCode").asString(""));
            } catch (IllegalArgumentException e) {
                errors.add(p + ": unknown reasonCode '" + r.path("reasonCode").asString("") + "'");
            }
            Set<Channel> channels = new HashSet<>();
            r.path("channels").forEach(c -> {
                Channel ch = channel(c.asString(), p + ".channels", errors);
                if (ch != null) channels.add(ch);
            });
            if (!r.has("when")) {
                errors.add(p + ": 'when' condition is required");
                continue;
            }
            Predicate<EvaluationContext> cond = condition(r.get("when"), p, lists, errors);
            if (cond != null && reason != null) {
                out.add(new Rule(id, r.path("description").asString(""), action, points, reason, Set.copyOf(channels),
                        emergency, cond));
            }
        }
    }

    private Predicate<EvaluationContext> condition(JsonNode c, String p, Map<String, Set<String>> lists, List<String> errors) {
        if (c.has("all") || c.has("any")) {
            boolean all = c.has("all");
            JsonNode arr = c.get(all ? "all" : "any");
            if (!arr.isArray() || arr.isEmpty()) {
                errors.add(p + ": '" + (all ? "all" : "any") + "' needs a non-empty array");
                return null;
            }
            List<Predicate<EvaluationContext>> parts = new ArrayList<>();
            for (JsonNode child : arr) {
                Predicate<EvaluationContext> pc = condition(child, p, lists, errors);
                if (pc != null) parts.add(pc);
            }
            if (parts.size() != arr.size()) return null;
            return all ? ctx -> parts.stream().allMatch(x -> x.test(ctx)) : ctx -> parts.stream().anyMatch(x -> x.test(ctx));
        }
        if (c.has("not")) {
            Predicate<EvaluationContext> inner = condition(c.get("not"), p, lists, errors);
            return inner == null ? null : inner.negate();
        }
        return leaf(c, p, lists, errors);
    }

    private Predicate<EvaluationContext> leaf(JsonNode c, String p, Map<String, Set<String>> lists, List<String> errors) {
        String field = c.path("field").asString("");
        String op = c.path("op").asString("");
        JsonNode value = c.path("value");
        FieldCatalog.Type type = FieldCatalog.typeOf(field);
        int before = errors.size();
        if (type == null) errors.add(p + ": unknown field '" + field + "'");
        if (!OPS.contains(op)) errors.add(p + ": unknown operator '" + op + "'");
        if (type == FieldCatalog.Type.STRING && NUMERIC_OPS.contains(op)) errors.add(p + ": operator '" + op + "' needs a numeric field, '" + field + "' is text");
        if ((op.equals("in_list") || op.equals("not_in_list")) && !lists.containsKey(value.asString(""))) {
            errors.add(p + ": list '" + value.asString("") + "' is not defined in 'lists'");
        }
        if ((op.equals("in") || op.equals("not_in")) && !value.isArray()) errors.add(p + ": '" + op + "' needs an array value");
        if (op.equals("between") && (!value.isArray() || value.size() != 2)) errors.add(p + ": 'between' needs [min, max]");
        if (type == FieldCatalog.Type.NUMBER && Set.of("eq", "neq", "gt", "gte", "lt", "lte").contains(op) && !value.isNumber()) {
            errors.add(p + ": field '" + field + "' is numeric but value is not a number");
        }
        if (errors.size() != before) return null;

        // Semantics shared with the Python evaluator: a missing (null) field makes every leaf false.
        return switch (op) {
            case "eq" -> type == FieldCatalog.Type.NUMBER
                    ? num(field, x -> x == value.asDouble())
                    : str(field, x -> x.equals(value.asString()));
            case "neq" -> type == FieldCatalog.Type.NUMBER
                    ? num(field, x -> x != value.asDouble())
                    : str(field, x -> !x.equals(value.asString()));
            case "gt" -> num(field, x -> x > value.asDouble());
            case "gte" -> num(field, x -> x >= value.asDouble());
            case "lt" -> num(field, x -> x < value.asDouble());
            case "lte" -> num(field, x -> x <= value.asDouble());
            case "between" -> {
                double lo = value.get(0).asDouble(), hi = value.get(1).asDouble();
                yield num(field, x -> x >= lo && x <= hi);
            }
            case "in", "not_in" -> {
                Set<String> set = new HashSet<>();
                value.forEach(v -> set.add(v.isNumber() ? trimNumber(v.asDouble()) : v.asString()));
                boolean positive = op.equals("in");
                yield ctx -> {
                    Object v = ctx.get(field);
                    if (v == null) return false;
                    String s = v instanceof Number n ? trimNumber(n.doubleValue()) : v.toString();
                    return set.contains(s) == positive;
                };
            }
            case "in_list", "not_in_list" -> {
                Set<String> set = lists.get(value.asString());
                boolean positive = op.equals("in_list");
                yield str(field, x -> set.contains(x) == positive);
            }
            default -> throw new IllegalStateException(op);
        };
    }

    private static String trimNumber(double d) {
        return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(d);
    }

    private static Predicate<EvaluationContext> num(String field, java.util.function.DoublePredicate p) {
        return ctx -> {
            Double v = ctx.number(field);
            return v != null && p.test(v);
        };
    }

    private static Predicate<EvaluationContext> str(String field, Predicate<String> p) {
        return ctx -> {
            String v = ctx.string(field);
            return v != null && p.test(v);
        };
    }
}
