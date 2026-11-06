import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Seeds the demo ledger with 200 marketplace transfers (180 customer payments, 20 brand payouts), all with a
 * reference, and writes docs/demo/bank-statement.csv. The statement matches the ledger except for three
 * deliberate discrepancies. Usage: java scripts/demo/DemoData.java http://localhost:8095
 * Idempotency keys are fixed, so running it again does not post anything twice.
 */
public class DemoData {
    static final String[] NAMES = {"Anna Weber", "Jonas Meyer", "Lea Schmidt", "Paul Fischer", "Mia Becker",
            "Luca Hoffmann", "Emma Koch", "Finn Richter", "Sofia Wolf", "Ben Neumann", "Clara Schwarz", "Noah Braun"};
    static final String[] BRANDS = {"b1", "b2"};

    public static void main(String[] args) throws Exception {
        String base = args.length > 0 ? args[0] : "http://localhost:8095";
        HttpClient http = HttpClient.newHttpClient();
        Random rnd = new Random(42);
        LocalDate start = LocalDate.now().minusDays(13);
        long[] payable = new long[BRANDS.length];
        List<String> lines = new ArrayList<>();
        int orders = 0, payouts = 0;
        for (int n = 0; n < 200; n++) {
            LocalDate date = start.plusDays(n * 14L / 200);
            if (n % 10 == 9) {
                int b = (n / 10) % BRANDS.length;
                long amount = payable[b] / 2;
                String ref = String.format("PO-2026-%03d", ++payouts);
                post(http, base + "/v1/payouts", "demo-" + ref,
                        "{\"brandId\":\"" + BRANDS[b] + "\",\"amount\":" + amount + ",\"reference\":\"" + ref + "\"}");
                payable[b] -= amount;
                lines.add(date + "," + (-amount) + "," + ref + ",Payout " + BRANDS[b]);
            } else {
                int b = rnd.nextInt(BRANDS.length);
                long gross = 1990 + rnd.nextInt(23000);
                long commission = Math.round(gross * 0.15);
                String ref = "ORD-" + (10001 + orders++);
                post(http, base + "/v1/transfers", "demo-" + ref,
                        "{\"description\":\"Order " + ref + "\",\"reference\":\"" + ref + "\",\"postings\":["
                                + "{\"accountId\":\"cash:bank\",\"amount\":" + gross + ",\"currency\":\"EUR\"},"
                                + "{\"accountId\":\"brand:" + BRANDS[b] + ":payable\",\"amount\":" + -(gross - commission) + ",\"currency\":\"EUR\"},"
                                + "{\"accountId\":\"platform:revenue\",\"amount\":" + -commission + ",\"currency\":\"EUR\"}]}");
                payable[b] += gross - commission;
                String line = date + "," + gross + "," + ref + "," + NAMES[rnd.nextInt(NAMES.length)];
                if (orders == 60) {
                    continue;                         // payment missing at the bank
                }
                if (orders == 120) {
                    line = date + "," + (gross + 1) + "," + ref + "," + NAMES[rnd.nextInt(NAMES.length)];   // 1 cent off
                }
                lines.add(line);
            }
            if (n == 150) {
                lines.add(date + ",-490,,Kontofuehrung Q4");   // bank fee, not in the ledger
            }
        }
        Path out = Path.of("docs/demo/bank-statement.csv");
        Files.createDirectories(out.getParent());
        Files.writeString(out, "booking_date,amount_cents,reference,counterparty\n" + String.join("\n", lines) + "\n");
        System.out.println("posted 200 entries, wrote " + out + " (" + lines.size() + " bank lines)");
        System.out.println("upload: curl -X POST " + base + "/v1/reconciliations -H 'Content-Type: text/csv' --data-binary @" + out);
    }

    static void post(HttpClient http, String url, String key, String body) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json").header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200 && r.statusCode() != 201) {
            throw new IllegalStateException(url + " -> " + r.statusCode() + " " + r.body());
        }
    }
}
