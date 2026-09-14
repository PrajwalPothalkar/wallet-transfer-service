package com.paytm.exercise.wallet.repository;

import com.paytm.exercise.wallet.domain.Transfer;
import com.paytm.exercise.wallet.domain.TransferStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Repository
public class TransferRepository {
    private static final String COLUMNS =
            "id, idempotency_key, from_wallet_id, to_wallet_id, amount_paise, status, decline_reason";
    private static final RowMapper<Transfer> ROW_MAPPER = (rs, rowNum) -> new Transfer(
            rs.getLong("id"), rs.getString("idempotency_key"), rs.getLong("from_wallet_id"),
            rs.getLong("to_wallet_id"), rs.getLong("amount_paise"),
            TransferStatus.valueOf(rs.getString("status").toUpperCase(Locale.ROOT)), rs.getString("decline_reason"));
    private final JdbcTemplate jdbc;

    public TransferRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims the unique idempotency key inside the caller's money transaction. An empty result
     * means a concurrent transaction already committed this key, so the caller must replay it.
     */
    public Optional<Transfer> claim(String idempotencyKey, long from, long to, long amountPaise) {
        List<Transfer> result = jdbc.query(
                "INSERT INTO transfers (idempotency_key, from_wallet_id, to_wallet_id, amount_paise, status) " +
                        "VALUES (?, ?, ?, ?, 'completed') ON CONFLICT (idempotency_key) DO NOTHING RETURNING " + COLUMNS,
                ROW_MAPPER, idempotencyKey, from, to, amountPaise);
        return result.stream().findFirst();
    }

    public Optional<Transfer> findByIdempotencyKey(String idempotencyKey) {
        return jdbc.query("SELECT " + COLUMNS + " FROM transfers WHERE idempotency_key = ?", ROW_MAPPER, idempotencyKey)
                .stream().findFirst();
    }

    public Transfer markDeclined(long id, String reason) {
        return jdbc.queryForObject(
                "UPDATE transfers SET status = 'declined', decline_reason = ?, updated_at = now() " +
                        "WHERE id = ? RETURNING " + COLUMNS, ROW_MAPPER, reason, id);
    }

    public Transfer markCompleted(long id) {
        return jdbc.queryForObject(
                "UPDATE transfers SET status = 'completed', updated_at = now() WHERE id = ? RETURNING " + COLUMNS,
                ROW_MAPPER, id);
    }

    public Optional<Transfer> findVisible(long transferId, String userId) {
        String fields = "t." + COLUMNS.replace(", ", ", t.");
        return jdbc.query(
                "SELECT " + fields + " FROM transfers t " +
                        "JOIN wallets source ON source.id = t.from_wallet_id " +
                        "JOIN wallets destination ON destination.id = t.to_wallet_id " +
                        "WHERE t.id = ? AND (source.user_id = ? OR destination.user_id = ?)",
                ROW_MAPPER, transferId, userId, userId).stream().findFirst();
    }
}
