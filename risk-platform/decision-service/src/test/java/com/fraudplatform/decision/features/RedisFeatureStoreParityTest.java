package com.fraudplatform.decision.features;

import com.fraudplatform.decision.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** The Redis feature store must produce the same features as the Python reference. */
class RedisFeatureStoreParityTest extends IntegrationTestBase {

    @Autowired
    StringRedisTemplate redis;

    @Test
    void redisStoreMatchesPythonFeatures() throws Exception {
        // Parity streams use real tenant ids; clear any state left by other tests.
        var keys = redis.keys("fs:*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        assertThat(FeatureParityTest.replay("aldermoor-bank", new RedisFeatureStore(redis))).isEmpty();
    }
}
