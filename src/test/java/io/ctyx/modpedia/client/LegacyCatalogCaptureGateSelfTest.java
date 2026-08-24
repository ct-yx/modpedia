package io.ctyx.modpedia.client;

/** 验证物品 Tooltip 捕获只发生在进入世界之前。 */
public final class LegacyCatalogCaptureGateSelfTest {
    private LegacyCatalogCaptureGateSelfTest() {
    }

    public static void main(String[] args) {
        LegacyCatalogCaptureGate gate = new LegacyCatalogCaptureGate();
        check(gate.observe(false, true) == LegacyCatalogCaptureGate.Decision.WAIT,
                "capture must be requested first");
        gate.request();
        check(gate.observe(false, false) == LegacyCatalogCaptureGate.Decision.CAPTURE,
                "pre-world capture does not require a visible menu");
        check(gate.observe(false, true) == LegacyCatalogCaptureGate.Decision.CAPTURE,
                "menu capture");
        check(gate.observe(true, true) == LegacyCatalogCaptureGate.Decision.PAUSE,
                "world pauses capture");
        check(gate.observe(false, true) == LegacyCatalogCaptureGate.Decision.CAPTURE,
                "capture resumes in menu");

        gate.reset();
        gate.request();
        gate.complete();
        check(gate.observe(false, true) == LegacyCatalogCaptureGate.Decision.CLOSED,
                "completed capture stays closed");
        System.out.println("LegacyCatalogCaptureGateSelfTest: OK");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
