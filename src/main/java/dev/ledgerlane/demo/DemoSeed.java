package dev.ledgerlane.demo;

import dev.ledgerlane.account.AccountRepository;
import dev.ledgerlane.account.AccountType;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Creates the Nordkai accounts for two brands. Safe to run on every start. */
@Component
@Profile("demo")
class DemoSeed implements ApplicationRunner {

    private final AccountRepository accounts;

    DemoSeed(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    public void run(ApplicationArguments args) {
        accounts.insert("cash:bank", AccountType.ASSET, "EUR", false);
        accounts.insert("customer:clearing", AccountType.LIABILITY, "EUR", true);
        accounts.insert("platform:revenue", AccountType.REVENUE, "EUR", false);
        accounts.insert("payouts:in-transit", AccountType.LIABILITY, "EUR", false);
        for (String brand : new String[] {"b1", "b2"}) {
            accounts.insert("brand:" + brand + ":payable", AccountType.LIABILITY, "EUR", false);
        }
    }
}
