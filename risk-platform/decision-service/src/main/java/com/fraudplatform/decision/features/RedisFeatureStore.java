package com.fraudplatform.decision.features;

import com.fraudplatform.decision.domain.Transaction;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.DefaultStringRedisConnection;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Redis-backed feature store. Layout (all keys prefixed with {@code fs:<tenant>:}):
 * <pre>
 *   card:TOKEN        ZSET   member=txnId          score=ts   trimmed to 1h,  TTL 2h
 *   acct:CUSTOMER     ZSET   member=txnId|amount   score=ts   trimmed to 24h, TTL 25h
 *   dev:DEVICE        ZSET   member=txnId          score=ts   trimmed to 1h,  TTL 2h
 *   last:CUSTOMER     STRING ts of last transaction           TTL 31d
 *   lastcard:TOKEN    STRING ts|country                        TTL 2h
 *   seendev:CUSTOMER  SET    device ids                        TTL 400d
 *   seenben:CUSTOMER  SET    beneficiary ids                   TTL 400d
 * </pre>
 * Reads and writes are pipelined (one round trip each). Window reads use an exclusive lower bound,
 * matching the feature-spec rule {@code ts > t - window}.
 *
 * <p>Known limitation: load and record are separate round trips, so two concurrent transactions on the
 * same card can each miss the other in their velocity counts. Acceptable for this design (documented);
 * a Lua script doing read+write atomically is the production-hardening option.
 */
public class RedisFeatureStore implements FeatureStore {

    private static final Duration SHORT_TTL = Duration.ofHours(2);
    private static final Duration ACCOUNT_TTL = Duration.ofHours(25);
    private static final Duration LAST_TTL = Duration.ofDays(31);
    private static final Duration SEEN_TTL = Duration.ofDays(400);

    private final StringRedisTemplate redis;

    public RedisFeatureStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    static String key(Transaction tx, String type, String id) {
        return "fs:" + tx.tenantId() + ":" + type + ":" + id;
    }

    @Override
    @SuppressWarnings("unchecked")
    public EntityState load(Transaction tx) {
        long t = tx.eventTime().toEpochMilli();
        boolean card = tx.cardToken() != null;
        boolean device = tx.deviceId() != null;
        boolean ben = tx.isTransfer() && tx.beneficiaryId() != null;
        Range<Double> hourWindow = Range.of(Range.Bound.exclusive((double) (t - FeatureCalculator.HOUR)), Range.Bound.unbounded());
        Range<Double> dayWindow = Range.of(Range.Bound.exclusive((double) (t - FeatureCalculator.DAY)), Range.Bound.unbounded());

        List<Object> r = redis.executePipelined((RedisCallback<Object>) conn -> {
            StringRedisConnection c = new DefaultStringRedisConnection(conn);
            if (card) c.zRangeByScoreWithScores(bytes(key(tx, "card", tx.cardToken())), hourWindow, Limit.unlimited());
            c.zRangeByScoreWithScores(bytes(key(tx, "acct", tx.customerId())), dayWindow, Limit.unlimited());
            if (device) c.zRangeByScoreWithScores(bytes(key(tx, "dev", tx.deviceId())), hourWindow, Limit.unlimited());
            c.get(key(tx, "last", tx.customerId()));
            if (card) c.get(key(tx, "lastcard", tx.cardToken()));
            if (device) c.sIsMember(key(tx, "seendev", tx.customerId()), tx.deviceId());
            if (ben) c.sIsMember(key(tx, "seenben", tx.customerId()), tx.beneficiaryId());
            return null;
        });

        int i = 0;
        long[] cardTimes = card ? scores((Set<Object>) r.get(i++)) : new long[0];
        Set<Object> acct = (Set<Object>) r.get(i++);
        long[] accTimes = scores(acct);
        double[] accAmounts = new double[acct.size()];
        int j = 0;
        for (Object o : acct) {
            String member = String.valueOf(tupleValue(o));
            accAmounts[j++] = Double.parseDouble(member.substring(member.lastIndexOf('|') + 1));
        }
        long[] devTimes = device ? scores((Set<Object>) r.get(i++)) : new long[0];
        String last = (String) r.get(i++);
        Long lastCardTs = null;
        String lastCardCountry = null;
        if (card) {
            String lc = (String) r.get(i++);
            if (lc != null) {
                int sep = lc.indexOf('|');
                lastCardTs = Long.parseLong(lc.substring(0, sep));
                lastCardCountry = lc.substring(sep + 1);
            }
        }
        boolean devSeen = device && Boolean.TRUE.equals(r.get(i++));
        boolean benSeen = ben && Boolean.TRUE.equals(r.get(i));
        return new EntityState(cardTimes, accTimes, accAmounts, devTimes,
                last == null ? null : Long.parseLong(last), lastCardTs, lastCardCountry, devSeen, benSeen);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static long[] scores(Set<Object> tuples) {
        long[] out = new long[tuples == null ? 0 : tuples.size()];
        int i = 0;
        if (tuples != null) {
            for (Object o : tuples) {
                out[i++] = tupleScore(o).longValue();
            }
        }
        return out;
    }

    private static Double tupleScore(Object o) {
        if (o instanceof org.springframework.data.redis.core.ZSetOperations.TypedTuple<?> t) return t.getScore();
        if (o instanceof org.springframework.data.redis.connection.zset.Tuple t) return t.getScore();
        throw new IllegalStateException("unexpected zset tuple type " + o.getClass());
    }

    private static Object tupleValue(Object o) {
        if (o instanceof org.springframework.data.redis.core.ZSetOperations.TypedTuple<?> t) return t.getValue();
        if (o instanceof org.springframework.data.redis.connection.StringRedisConnection.StringTuple t) return t.getValueAsString();
        if (o instanceof org.springframework.data.redis.connection.zset.Tuple t) return new String(t.getValue());
        throw new IllegalStateException("unexpected zset tuple type " + o.getClass());
    }

    @Override
    public void record(Transaction tx) {
        long t = tx.eventTime().toEpochMilli();
        String ts = Long.toString(t);
        redis.executePipelined((RedisCallback<Object>) conn -> {
            StringRedisConnection c = new DefaultStringRedisConnection(conn);
            if (tx.cardToken() != null) {
                String k = key(tx, "card", tx.cardToken());
                c.zAdd(k, t, tx.transactionId());
                c.zRemRangeByScore(k, Double.NEGATIVE_INFINITY, t - FeatureCalculator.HOUR);
                c.pExpire(k, SHORT_TTL.toMillis());
                if (tx.merchantCountry() != null) {
                    c.pSetEx(key(tx, "lastcard", tx.cardToken()), SHORT_TTL.toMillis(), ts + "|" + tx.merchantCountry());
                }
            }
            String acct = key(tx, "acct", tx.customerId());
            c.zAdd(acct, t, tx.transactionId() + "|" + tx.amount().toPlainString());
            c.zRemRangeByScore(acct, Double.NEGATIVE_INFINITY, t - FeatureCalculator.DAY);
            c.pExpire(acct, ACCOUNT_TTL.toMillis());
            if (tx.deviceId() != null) {
                String dk = key(tx, "dev", tx.deviceId());
                c.zAdd(dk, t, tx.transactionId());
                c.zRemRangeByScore(dk, Double.NEGATIVE_INFINITY, t - FeatureCalculator.HOUR);
                c.pExpire(dk, SHORT_TTL.toMillis());
                String sd = key(tx, "seendev", tx.customerId());
                c.sAdd(sd, tx.deviceId());
                c.pExpire(sd, SEEN_TTL.toMillis());
            }
            if (tx.isTransfer() && tx.beneficiaryId() != null) {
                String sb = key(tx, "seenben", tx.customerId());
                c.sAdd(sb, tx.beneficiaryId());
                c.pExpire(sb, SEEN_TTL.toMillis());
            }
            c.pSetEx(key(tx, "last", tx.customerId()), LAST_TTL.toMillis(), ts);
            return null;
        });
    }
}
