package com.paytm.exercise.wallet;

import com.paytm.exercise.wallet.api.CreateTransferRequest;
import com.paytm.exercise.wallet.api.TransferResponse;
import com.paytm.exercise.wallet.api.WalletResponse;
import com.paytm.exercise.wallet.domain.TransferStatus;
import com.paytm.exercise.wallet.service.TransferService;
import com.paytm.exercise.wallet.service.WalletService;
import com.paytm.exercise.wallet.shared.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class TransferConcurrencyIT {
    private static final long OPENING_BALANCE = 100_000L;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("wallet.initial-balance-paise", () -> OPENING_BALANCE);
    }

    @Autowired private WalletService wallets;
    @Autowired private TransferService transfers;

    @Test
    void concurrentGetOrCreateYieldsExactlyOneWallet() throws Exception {
        String user = user("race");
        List<WalletResponse> raced = inParallel(50, () -> wallets.getOrCreate(user).wallet());
        assertEquals(1, raced.stream().map(WalletResponse::id).distinct().count());
    }

    @Test
    void idempotencyStormAppliesExactlyOneDebitAndCredit() throws Exception {
        String alice = user("alice");
        String bob = user("bob");
        long a = wallets.getOrCreate(alice).wallet().id();
        long b = wallets.getOrCreate(bob).wallet().id();
        CreateTransferRequest request = new CreateTransferRequest(a, b, 1_000, UUID.randomUUID().toString());

        List<TransferResponse> results = inParallel(30, () -> transfers.create(request, alice).transfer());

        assertEquals(1, results.stream().map(TransferResponse::id).distinct().count());
        assertTrue(results.stream().allMatch(r -> r.status() == TransferStatus.COMPLETED));
        assertEquals(OPENING_BALANCE - 1_000, wallets.balance(a, alice).balancePaise());
        assertEquals(OPENING_BALANCE + 1_000, wallets.balance(b, bob).balancePaise());
    }

    @Test
    void reusedKeyWithDifferentBodyIsAConflict() {
        String alice = user("conflict-a");
        String bob = user("conflict-b");
        long a = wallets.getOrCreate(alice).wallet().id();
        long b = wallets.getOrCreate(bob).wallet().id();
        String key = UUID.randomUUID().toString();

        transfers.create(new CreateTransferRequest(a, b, 500, key), alice);
        ApiException conflict = assertThrows(ApiException.class,
                () -> transfers.create(new CreateTransferRequest(a, b, 900, key), alice));
        assertEquals(HttpStatus.CONFLICT, conflict.status());
    }

    @Test
    void bidirectionalContentionConservesMoneyAndNeverOverdraws() throws Exception {
        String alice = user("contend-a");
        String bob = user("contend-b");
        long a = wallets.getOrCreate(alice).wallet().id();
        long b = wallets.getOrCreate(bob).wallet().id();
        String tag = UUID.randomUUID().toString();

        // A→B and B→A at once, salted with amounts that must overdraw.
        List<TransferResponse> results = inParallelIndexed(200, index -> {
            long amount = index % 7 == 0 ? 99_999_999L : 250L;
            return index % 2 == 0
                    ? transfers.create(new CreateTransferRequest(a, b, amount, tag + "-" + index), alice).transfer()
                    : transfers.create(new CreateTransferRequest(b, a, amount, tag + "-" + index), bob).transfer();
        });

        assertTrue(results.stream().anyMatch(r -> r.status() == TransferStatus.DECLINED), "expected some declines");
        long balanceA = wallets.balance(a, alice).balancePaise();
        long balanceB = wallets.balance(b, bob).balancePaise();
        assertEquals(2 * OPENING_BALANCE, balanceA + balanceB, "conservation broken");
        assertTrue(balanceA >= 0 && balanceB >= 0, "negative balance");
    }

    private static String user(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private <T> List<T> inParallel(int count, Callable<T> task) throws Exception {
        return inParallelIndexed(count, index -> {
            try {
                return task.call();
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
    }

    private <T> List<T> inParallelIndexed(int count, java.util.function.IntFunction<T> task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(count, 32));
        try {
            List<Callable<T>> jobs = IntStream.range(0, count)
                    .<Callable<T>>mapToObj(index -> () -> task.apply(index))
                    .toList();
            return executor.invokeAll(jobs).stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            }).toList();
        } finally {
            executor.shutdownNow();
        }
    }
}
