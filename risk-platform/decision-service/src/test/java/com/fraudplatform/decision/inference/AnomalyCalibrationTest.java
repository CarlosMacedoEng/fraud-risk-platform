package com.fraudplatform.decision.inference;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Expected values computed with numpy.interp(x, xp, fp, left=0, right=1). */
class AnomalyCalibrationTest {

    static final double[] XP = {-0.5, -0.2, -0.2, 0.0, 0.1, 0.1, 0.3};
    static final double[] FP = {0.0, 0.2, 0.4, 0.6, 0.8, 0.9, 1.0};

    @Test
    void matchesNumpyInterp() {
        assertThat(AnomalyCalibration.percentile(-0.6, XP, FP)).isEqualTo(0.0);   // left
        assertThat(AnomalyCalibration.percentile(0.31, XP, FP)).isEqualTo(1.0);   // right
        assertThat(AnomalyCalibration.percentile(-0.35, XP, FP)).isCloseTo(0.1, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(AnomalyCalibration.percentile(-0.2, XP, FP)).isEqualTo(0.4);   // duplicate x: last index wins
        assertThat(AnomalyCalibration.percentile(-0.1, XP, FP)).isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(AnomalyCalibration.percentile(0.1, XP, FP)).isEqualTo(0.9);
        assertThat(AnomalyCalibration.percentile(0.3, XP, FP)).isEqualTo(1.0);
    }
}
