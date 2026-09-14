CREATE TABLE wallets (
  id BIGSERIAL PRIMARY KEY,
  user_id TEXT NOT NULL UNIQUE,
  balance_paise BIGINT NOT NULL CHECK (balance_paise >= 0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE transfers (
  id BIGSERIAL PRIMARY KEY,
  idempotency_key TEXT NOT NULL UNIQUE,
  from_wallet_id BIGINT NOT NULL REFERENCES wallets(id),
  to_wallet_id BIGINT NOT NULL REFERENCES wallets(id),
  amount_paise BIGINT NOT NULL CHECK (amount_paise > 0),
  status TEXT NOT NULL CHECK (status IN ('completed', 'declined')),
  decline_reason TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (from_wallet_id <> to_wallet_id),
  CHECK ((status = 'declined' AND decline_reason IS NOT NULL) OR (status = 'completed' AND decline_reason IS NULL))
);

CREATE INDEX transfers_from_wallet_id_idx ON transfers(from_wallet_id);
CREATE INDEX transfers_to_wallet_id_idx ON transfers(to_wallet_id);
