import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chaos proof for Ledger Lane. Single file, JDK only: java scripts/chaos/Chaos.java N CONCURRENCY
 * Starts the app jar itself, sends marketplace traffic, kills it with SIGKILL at 40 %, restarts it,
 * then checks every invariant against SQL (psql) and the API.
 */
public class Chaos {
    static final String PG_BIN = "/Library/PostgreSQL/16/bin";
    static final int PG_PORT = 55433;
    static final int APP_PORT = freePort();
    static final String BASE = "http://127.0.0.1:" + APP_PORT;
    static final String[] ACCOUNTS = {"cash:bank", "customer:clearing", "platform:revenue", "payouts:in-transit",
            "brand:b1:payable", "brand:b2:payable"};
    static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"");

    record Entry(String id, Map<String, Long> deltas) {}
    record Reply(int status, String body, boolean replayed) {}

    static final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();
    static Process app;
    static final Path ROOT = Path.of("").toAbsolutePath();

    // statistics
    static final ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
    static final AtomicInteger duplicatesSent = new AtomicInteger(), replays = new AtomicInteger(),
            rejected422 = new AtomicInteger(), conflicts409 = new AtomicInteger(), retriesAfterCrash = new AtomicInteger(),
            accepted = new AtomicInteger(), unexpected = new AtomicInteger();
    static final AtomicInteger kinds[] = {new AtomicInteger(), new AtomicInteger(), new AtomicInteger()};
    // model: accepted idempotency key -> entry id, and expected signed balance per account
    static final ConcurrentHashMap<String, String> acceptedKeys = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<String, AtomicLong> expected = new ConcurrentHashMap<>();
    static final ConcurrentLinkedQueue<Entry> reversible = new ConcurrentLinkedQueue<>();
    static final List<String> problems = Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) throws Exception {
        int n = Integer.parseInt(args[0]);
        int concurrency = Integer.parseInt(args[1]);
        for (String a : ACCOUNTS) expected.put(a, new AtomicLong());
        String runId = Long.toString(System.currentTimeMillis(), 36);

        psql("postgres", "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='ledger' AND pid<>pg_backend_pid()");
        psql("ledger", "DROP SCHEMA public CASCADE; CREATE SCHEMA public");
        startApp();

        long t0 = System.nanoTime();
        int crashes = 0;
        int killAt = (int) (n * 0.4);
        Semaphore slots = new Semaphore(concurrency);
        long seed = 42;
        List<Future<?>> futures = new ArrayList<>();
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            Random rnd = new Random(seed);
            for (int i = 0; i < n; i++) {
                if (i == killAt) {
                    System.out.println("chaos: kill -9 the app at request " + i);
                    app.destroyForcibly().waitFor();
                    crashes++;
                    Thread.sleep(500);
                    startApp();
                    System.out.println("chaos: app restarted");
                }
                slots.acquire();
                final int idx = i;
                final long r = rnd.nextLong();
                futures.add(ex.submit(() -> {
                    try {
                        oneRequest(runId, idx, new Random(r), ex);
                    } catch (Throwable t) {
                        unexpected.incrementAndGet();
                        problems.add("request " + idx + " failed: " + t);
                    } finally {
                        slots.release();
                    }
                }));
                if (i % 10000 == 9999) System.out.println("chaos: " + (i + 1) + " / " + n);
            }
            for (Future<?> f : futures) f.get();
        }
        double seconds = (System.nanoTime() - t0) / 1e9;

        // verification
        Map<String, Boolean> checks = new LinkedHashMap<>();
        Map<String, String> notes = new LinkedHashMap<>();
        String sum = psql("ledger", "SELECT COALESCE(SUM(amount),0) FROM postings");
        checks.put("a_sum_of_postings_is_zero", sum.equals("0"));
        notes.put("a_sum_of_postings_is_zero", "sum = " + sum);

        String drift = psql("ledger", "SELECT COUNT(*) FROM accounts a WHERE a.balance <> COALESCE((SELECT SUM(amount) FROM postings p WHERE p.account_id=a.id),0)");
        checks.put("b_account_balance_equals_postings", drift.equals("0"));
        notes.put("b_account_balance_equals_postings", drift + " accounts differ");

        String unbalanced = psql("ledger", "SELECT COUNT(*) FROM (SELECT entry_id FROM postings GROUP BY entry_id HAVING SUM(amount)<>0 OR COUNT(*)<2) x "
                + "UNION ALL SELECT COUNT(*) FROM journal_entries e WHERE NOT EXISTS (SELECT 1 FROM postings p WHERE p.entry_id=e.id)");
        checks.put("c_every_entry_balances", unbalanced.replace("\n", "").equals("00"));
        notes.put("c_every_entry_balances", "unbalanced or empty entries: " + unbalanced.replace("\n", " + "));

        String entries = psql("ledger", "SELECT COUNT(*) FROM journal_entries");
        String keys = psql("ledger", "SELECT COUNT(*) FROM idempotency_keys");
        String distinct = psql("ledger", "SELECT COUNT(DISTINCT entry_id) FROM idempotency_keys");
        String orphans = psql("ledger", "SELECT COUNT(*) FROM journal_entries e WHERE NOT EXISTS (SELECT 1 FROM idempotency_keys k WHERE k.entry_id=e.id)");
        String noResponse = psql("ledger", "SELECT COUNT(*) FROM idempotency_keys WHERE response IS NULL");
        Map<String, String> dbKeys = new HashMap<>();
        for (String line : psql("ledger", "SELECT key || ' ' || entry_id FROM idempotency_keys").split("\n")) {
            if (!line.isBlank()) { String[] p = line.split(" "); dbKeys.put(p[0], p[1]); }
        }
        boolean keysMatch = dbKeys.equals(new HashMap<>(acceptedKeys));
        boolean d = entries.equals(keys) && keys.equals(distinct) && orphans.equals("0") && noResponse.equals("0")
                && keysMatch && dbKeys.size() == accepted.get();
        checks.put("d_idempotency_key_maps_to_exactly_one_entry", d);
        notes.put("d_idempotency_key_maps_to_exactly_one_entry", entries + " entries, " + keys + " keys, " + distinct
                + " distinct entry ids, " + orphans + " entries without key, driver accepted " + accepted.get()
                + ", db keys equal driver keys: " + keysMatch);

        StringBuilder diff = new StringBuilder();
        boolean e = true;
        for (String a : ACCOUNTS) {
            long exp = expected.get(a).get();
            String sqlBal = psql("ledger", "SELECT COALESCE(SUM(amount),0) FROM postings WHERE account_id='" + a + "'");
            Reply r = call("GET", "/v1/accounts/" + a, null, null);
            Matcher m = Pattern.compile("\"balance\"\\s*:\\s*(-?\\d+)").matcher(r.body());
            long apiBal = m.find() ? Long.parseLong(m.group(1)) : Long.MIN_VALUE;
            boolean ok = exp == Long.parseLong(sqlBal) && exp == apiBal;
            e &= ok;
            diff.append(a).append(" model=").append(exp).append(" sql=").append(sqlBal).append(" api=").append(apiBal)
                    .append(ok ? " ok; " : " MISMATCH; ");
        }
        checks.put("e_model_equals_ledger_to_the_cent", e);
        notes.put("e_model_equals_ledger_to_the_cent", diff.toString().trim());

        checks.put("f_no_unexpected_errors", unexpected.get() == 0 && problems.isEmpty());
        notes.put("f_no_unexpected_errors", unexpected.get() + " unexpected, " + problems.stream().limit(3).toList());

        List<Long> lat = new ArrayList<>(latencies);
        Collections.sort(lat);
        double p50 = pct(lat, 50), p95 = pct(lat, 95), p99 = pct(lat, 99);
        double tps = n / seconds;
        boolean pass = !checks.containsValue(false);
        report(n, concurrency, seconds, tps, p50, p95, p99, crashes, checks, notes, pass);
        System.out.printf(Locale.ROOT, "%nChaos %s: N=%d concurrency=%d duration=%.1fs throughput=%.0f/s p50=%.1fms p95=%.1fms p99=%.1fms%n",
                pass ? "PASS" : "FAIL", n, concurrency, seconds, tps, p50, p95, p99);
        System.out.printf("duplicates=%d replays=%d rejected422=%d conflicts409=%d crashes=%d retriesAfterCrash=%d accepted=%d%n",
                duplicatesSent.get(), replays.get(), rejected422.get(), conflicts409.get(), crashes, retriesAfterCrash.get(), accepted.get());
        checks.forEach((k, v) -> System.out.println((v ? "PASS " : "FAIL ") + k + ": " + notes.get(k)));
        app.destroy();
        app.waitFor();
        System.exit(pass ? 0 : 1);
    }

    // ---- traffic -----------------------------------------------------------------------------------------

    static void oneRequest(String runId, int idx, Random rnd, ExecutorService ex) throws Exception {
        String key = "chaos-" + runId + "-" + idx;
        int dice = rnd.nextInt(100);
        String path, body;
        Map<String, Long> deltas;
        Entry toReverse = null;
        int kind;
        if (dice < 70) { // customer payment
            long amount = 1000 + rnd.nextInt(49000);
            deltas = split(amount, rnd.nextBoolean() ? "b1" : "b2", 1);
            body = transferJson("order " + idx, deltas);
            path = "/v1/transfers";
            kind = 0;
        } else if (dice < 90) { // refund, 1 in 4 is deliberately far too large
            boolean oversized = rnd.nextInt(4) == 0;
            long amount = oversized ? 100_000_000L + rnd.nextInt(1000) : 1000 + rnd.nextInt(30000);
            deltas = split(amount, rnd.nextBoolean() ? "b1" : "b2", -1);
            body = transferJson("refund " + idx, deltas);
            path = "/v1/transfers";
            kind = 1;
        } else { // reversal of an earlier accepted entry
            toReverse = reversible.poll();
            if (toReverse == null) {
                long amount = 1000 + rnd.nextInt(49000);
                deltas = split(amount, "b1", 1);
                body = transferJson("order " + idx, deltas);
                path = "/v1/transfers";
                kind = 0;
            } else {
                deltas = new TreeMap<>();
                toReverse.deltas().forEach((a, v) -> deltas.put(a, -v));
                body = null;
                path = "/v1/transfers/" + toReverse.id() + "/reversal";
                kind = 2;
            }
        }
        kinds[kind].incrementAndGet();

        // ~10 % are sent twice: half as concurrent double click, half as a retry after the first answer
        List<Reply> replies = new ArrayList<>();
        if (rnd.nextInt(100) < 10) {
            duplicatesSent.incrementAndGet();
            if (rnd.nextBoolean()) {
                final String fp = path, fb = body;
                Future<Reply> other = ex.submit(() -> send(fp, key, fb));
                replies.add(send(path, key, body));
                replies.add(other.get());
            } else {
                replies.add(send(path, key, body));
                replies.add(send(path, key, body));
            }
        } else {
            replies.add(send(path, key, body));
        }

        String entryId = null;
        boolean has422 = false, has409 = false;
        for (Reply r : replies) {
            if (r.replayed()) replays.incrementAndGet();
            if (r.status() == 200 || r.status() == 201) {
                Matcher m = ID.matcher(r.body());
                if (!m.find()) { problems.add(key + ": no id in " + r.body()); continue; }
                if (entryId != null && !entryId.equals(m.group(1))) problems.add(key + ": two different entries for one key");
                entryId = m.group(1);
            } else if (r.status() == 422) has422 = true;
            else if (r.status() == 409) has409 = true;
            else problems.add(key + ": unexpected status " + r.status() + " " + r.body());
        }
        if (entryId != null) {
            accepted.incrementAndGet();
            acceptedKeys.put(key, entryId);
            deltas.forEach((a, v) -> expected.get(a).addAndGet(v));
            if (kind != 2) reversible.add(new Entry(entryId, deltas));
        } else if (has422) {
            rejected422.incrementAndGet();
            if (toReverse != null) reversible.add(toReverse);
        } else if (has409) {
            conflicts409.incrementAndGet();
        }
    }

    /** Payment (sign 1): cash +amount, brand and revenue credited. Refund (sign -1): the opposite. */
    static Map<String, Long> split(long amount, String brand, int sign) {
        long commission = Math.round(amount * 0.15);
        Map<String, Long> m = new TreeMap<>();
        m.put("cash:bank", sign * amount);
        m.put("brand:" + brand + ":payable", -sign * (amount - commission));
        m.put("platform:revenue", -sign * commission);
        return m;
    }

    static String transferJson(String description, Map<String, Long> deltas) {
        StringBuilder sb = new StringBuilder("{\"description\":\"" + description + "\",\"postings\":[");
        boolean first = true;
        for (var e : deltas.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"accountId\":\"").append(e.getKey()).append("\",\"amount\":").append(e.getValue())
                    .append(",\"currency\":\"EUR\"}");
        }
        return sb.append("]}").toString();
    }

    /** Sends until a definite answer arrives. Connection errors, timeouts and 5xx are unknown outcomes: retry with the same key. */
    static Reply send(String path, String key, String body) throws Exception {
        long giveUp = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        while (true) {
            long start = System.nanoTime();
            try {
                Reply r = call("POST", path, key, body);
                if (r.status() < 500) {
                    latencies.add(System.nanoTime() - start);
                    return r;
                }
            } catch (java.io.IOException ex) {
                // outcome unknown
            }
            retriesAfterCrash.incrementAndGet();
            if (System.nanoTime() > giveUp) throw new IllegalStateException("no definite answer for " + key);
            Thread.sleep(250);
        }
    }

    static Reply call(String method, String path, String key, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + path)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                // demo profile keys: reads need AUDITOR, transfers and reversals SHOP
                .header("X-Api-Key", method.equals("GET") ? "demo-auditor" : "demo-shop");
        if (key != null) b.header("Idempotency-Key", key);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(r.statusCode(), r.body(), "true".equals(r.headers().firstValue("Idempotent-Replayed").orElse("")));
    }

    // ---- process and database ----------------------------------------------------------------------------

    static void startApp() throws Exception {
        File log = ROOT.resolve(".local/chaos-app.log").toFile();
        log.getParentFile().mkdirs();
        ProcessBuilder pb = new ProcessBuilder("java", "-jar", ROOT.resolve("target/ledger-lane-0.1.0-SNAPSHOT.jar").toString(),
                "--spring.profiles.active=demo", "--server.port=" + APP_PORT)
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log));
        pb.environment().put("LEDGER_DB_URL", "jdbc:postgresql://127.0.0.1:" + PG_PORT + "/ledger");
        app = pb.start();
        for (int i = 0; i < 240; i++) {
            try {
                if (call("GET", "/actuator/health", null, null).status() == 200) return;
            } catch (Exception ignored) { }
            Thread.sleep(500);
        }
        throw new IllegalStateException("app did not start, see " + log);
    }

    /** First port from 8091 up that nothing listens on (8080 and others are used by other apps). */
    static int freePort() {
        for (int p = 8091; p < 8200; p++) {
            try (java.net.Socket s = new java.net.Socket()) {
                s.connect(new java.net.InetSocketAddress("127.0.0.1", p), 200);
            } catch (java.io.IOException free) {
                try (java.net.ServerSocket ss = new java.net.ServerSocket(p)) {
                    return p;
                } catch (java.io.IOException busy) { }
            }
        }
        throw new IllegalStateException("no free port");
    }

    static String psql(String db, String sql) throws Exception {
        Process p = new ProcessBuilder(PG_BIN + "/psql", "-h", "127.0.0.1", "-p", "" + PG_PORT, "-U", "ledger", "-d", db,
                "-tA", "-v", "ON_ERROR_STOP=1", "-c", sql).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        if (p.waitFor() != 0) throw new IllegalStateException("psql failed: " + out);
        return out;
    }

    static double pct(List<Long> sorted, int p) {
        if (sorted.isEmpty()) return 0;
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * p / 100.0) - 1)) / 1e6;
    }

    // ---- report ------------------------------------------------------------------------------------------

    static void report(int n, int c, double sec, double tps, double p50, double p95, double p99, int crashes,
                       Map<String, Boolean> checks, Map<String, String> notes, boolean pass) throws Exception {
        Path dir = ROOT.resolve("docs/chaos");
        Files.createDirectories(dir);
        StringBuilder j = new StringBuilder("{\n");
        j.append(String.format(Locale.ROOT, "  \"transfers\": %d,\n  \"concurrency\": %d,\n  \"durationSeconds\": %.1f,\n"
                        + "  \"throughputPerSecond\": %.0f,\n  \"latencyMs\": {\"p50\": %.1f, \"p95\": %.1f, \"p99\": %.1f},\n"
                        + "  \"acceptedTransfers\": %d,\n  \"duplicatesSent\": %d,\n  \"replaysServed\": %d,\n  \"rejected422\": %d,\n"
                        + "  \"conflicts409\": %d,\n  \"crashes\": %d,\n  \"retriesAfterCrash\": %d,\n  \"invariants\": {\n",
                n, c, sec, tps, p50, p95, p99, accepted.get(), duplicatesSent.get(), replays.get(), rejected422.get(),
                conflicts409.get(), crashes, retriesAfterCrash.get()));
        List<String> inv = new ArrayList<>();
        checks.forEach((k, v) -> inv.add("    \"" + k + "\": \"" + (v ? "PASS" : "FAIL") + "\""));
        j.append(String.join(",\n", inv)).append("\n  },\n  \"result\": \"").append(pass ? "PASS" : "FAIL").append("\"\n}\n");
        Files.writeString(dir.resolve("result.json"), j.toString());

        StringBuilder m = new StringBuilder("# Chaos report\n\nResult: **" + (pass ? "PASS" : "FAIL") + "**\n\n");
        m.append("Generated by `make chaos` on ").append(Instant.now().toString().substring(0, 10))
                .append(". The app (demo profile) ran as a separate process and was killed with `kill -9` at 40 % of the traffic, then restarted.\n\n");
        m.append("| Metric | Value |\n|---|---|\n");
        m.append("| Transfers sent (logical requests) | ").append(n).append(" |\n");
        m.append("| Concurrency | ").append(c).append(" |\n");
        m.append(String.format(Locale.ROOT, "| Duration | %.1f s |\n| Throughput | %.0f transfers/s |\n", sec, tps));
        m.append(String.format(Locale.ROOT, "| Latency p50 / p95 / p99 | %.1f / %.1f / %.1f ms |\n", p50, p95, p99));
        m.append("| Accepted entries | ").append(accepted.get()).append(" |\n");
        m.append("| Duplicates sent (same key twice) | ").append(duplicatesSent.get()).append(" |\n");
        m.append("| Replays served (`Idempotent-Replayed: true`) | ").append(replays.get()).append(" |\n");
        m.append("| Rejected with 422 (overdraft) | ").append(rejected422.get()).append(" |\n");
        m.append("| Rejected with 409 | ").append(conflicts409.get()).append(" |\n");
        m.append("| Crashes (SIGKILL) | ").append(crashes).append(" |\n");
        m.append("| Retries after crash (same key) | ").append(retriesAfterCrash.get()).append(" |\n\n");
        m.append("Mix: payments ").append(kinds[0]).append(", refunds ").append(kinds[1]).append(", reversals ").append(kinds[2])
                .append(". Latency is measured per HTTP call with a definite answer.\n\n## Invariants\n\n| Invariant | Result | Detail |\n|---|---|---|\n");
        String[] names = {"a_sum", "b_account", "c_every", "d_idem", "e_model", "f_no"};
        String[] labels = {"Sum of all postings is 0", "Each account balance equals the sum of its postings",
                "Every journal entry balances", "Every accepted key maps to exactly one entry, no entry without key",
                "Driver model equals ledger balance of every account, to the cent", "No unexpected responses"};
        int i = 0;
        for (var e : checks.entrySet()) {
            m.append("| ").append(labels[i++]).append(" | ").append(e.getValue() ? "PASS" : "FAIL").append(" | ")
                    .append(notes.get(e.getKey()).replace("|", "/")).append(" |\n");
        }
        Files.writeString(dir.resolve("report.md"), m.toString());
    }
}
