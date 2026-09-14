package com.paytm.exercise.wallet.repository;

import com.paytm.exercise.wallet.domain.Wallet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class WalletRepository {
    private static final RowMapper<Wallet> ROW_MAPPER = (rs, rowNum) -> new Wallet(
            rs.getLong("id"), rs.getString("user_id"), rs.getLong("balance_paise"));
    private final JdbcTemplate jdbc;

    public WalletRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public GetOrCreateResult getOrCreate(String userId, long openingBalancePaise) {
        List<Wallet> inserted = jdbc.query(
                "INSERT INTO wallets (user_id, balance_paise) VALUES (?, ?) " +
                        "ON CONFLICT (user_id) DO NOTHING RETURNING id, user_id, balance_paise",
                ROW_MAPPER, userId, openingBalancePaise);
        if (!inserted.isEmpty()) return new GetOrCreateResult(inserted.getFirst(), true);
        Wallet existing = jdbc.queryForObject(
                "SELECT id, user_id, balance_paise FROM wallets WHERE user_id = ?", ROW_MAPPER, userId);
        return new GetOrCreateResult(existing, false);
    }

    public Optional<Wallet> findOwned(long id, String userId) {
        return jdbc.query("SELECT id, user_id, balance_paise FROM wallets WHERE id = ? AND user_id = ?", ROW_MAPPER, id, userId)
                .stream().findFirst();
    }

    /** Acquires both wallet locks in one globally deterministic order. Must run inside a transaction. */
    public List<Wallet> lockPair(long firstId, long secondId) {
        long lower = Math.min(firstId, secondId);
        long higher = Math.max(firstId, secondId);
        return jdbc.query(
                "SELECT id, user_id, balance_paise FROM wallets WHERE id IN (?, ?) ORDER BY id FOR UPDATE",
                ROW_MAPPER, lower, higher);
    }

    /** Atomic no-overdraft guard. Must run inside the same transaction as the matching credit. */
    public boolean conditionalDebit(long walletId, long amountPaise) {
        return jdbc.update("UPDATE wallets SET balance_paise = balance_paise - ? WHERE id = ? AND balance_paise >= ?",
                amountPaise, walletId, amountPaise) == 1;
    }

    public void credit(long walletId, long amountPaise) {
        jdbc.update("UPDATE wallets SET balance_paise = balance_paise + ? WHERE id = ?", amountPaise, walletId);
    }

    public record GetOrCreateResult(Wallet wallet, boolean created) { }
}
