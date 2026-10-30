import app.binnacle.CarState;

/** Plain-JVM check of the tyre decoding (run by tests/run.sh; android.jar only supplies class stubs). */
public class TyreScaleTest {
    static int fails;

    static void check(int raw, String unit, String display, String expect) {
        CarState c = new CarState();
        c.tpmsRaw[0] = raw;
        c.tpmsUnit = unit;
        String got = c.tyre(0, display);
        boolean ok = expect == null ? got == null : expect.equals(got);
        System.out.println((ok ? "ok   " : "FAIL ") + raw + " " + unit + " -> " + display + " = " + got + (ok ? "" : " (expected " + expect + ")"));
        if (!ok) fails++;
    }

    public static void main(String[] a) {
        check(29000, "bar", "psi", "42.1");   // car: stock dash showed 42.0 psi
        check(29000, "bar", "bar", "2.9");
        check(9000, "bar", "bar", "0.9");     // low tyre must stay low (Codex P1)
        check(5000, "bar", "psi", "7.3");
        check(42000, "psi", "psi", "4.2");    // fixed scale like the stock app (psi feed unverified: this car reports bar)
        check(64000, "bar", "psi", null);     // placeholder
        check(0, "bar", "psi", null);
        if (fails > 0) { System.out.println(fails + " failed"); System.exit(1); }
        System.out.println("all tyre checks passed");
    }
}
