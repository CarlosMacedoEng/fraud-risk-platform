package com.fraudplatform.decision.api;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AdmissionControlFilterTest {

    @Test
    void rejectsFastWhenAllPermitsAreInUseAndRecovers() throws Exception {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AdmissionControlFilter filter = new AdmissionControlFilter(1, 10, JsonMapper.builder().build(), meters);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Thread first = Thread.ofVirtual().start(() -> {
            try {
                filter.doFilter(post(), new MockHttpServletResponse(), (req, res) -> {
                    inside.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException ignored) {
                    }
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertThat(inside.await(2, TimeUnit.SECONDS)).isTrue();

        MockHttpServletResponse rejected = new MockHttpServletResponse();
        long start = System.nanoTime();
        filter.doFilter(post(), rejected, (req, res) -> {
            throw new AssertionError("must not be admitted");
        });
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(500);
        assertThat(rejected.getStatus()).isEqualTo(503);
        assertThat(rejected.getHeader("Retry-After")).isEqualTo("1");
        assertThat(rejected.getContentAsString()).contains("OVERLOADED");
        assertThat(meters.counter("risk.admission.rejected").count()).isEqualTo(1.0);

        release.countDown();
        first.join();
        MockHttpServletResponse admitted = new MockHttpServletResponse();
        filter.doFilter(post(), admitted, (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(200));
        assertThat(admitted.getStatus()).isEqualTo(200);
    }

    @Test
    void onlyScoringRequestsAreSubjectToAdmissionControl() throws Exception {
        AdmissionControlFilter filter = new AdmissionControlFilter(0, 1, JsonMapper.builder().build(), new SimpleMeterRegistry());
        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/v1/decisions/abc");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(get, res, (rq, rs) -> ((jakarta.servlet.http.HttpServletResponse) rs).setStatus(200));
        assertThat(res.getStatus()).isEqualTo(200);
    }

    private static MockHttpServletRequest post() {
        return new MockHttpServletRequest("POST", "/v1/decisions");
    }
}
