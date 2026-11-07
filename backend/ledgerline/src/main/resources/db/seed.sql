INSERT INTO roles (name)
VALUES
    ('ROLE_ADMIN'),
    ('ROLE_USER'),
    ('ROLE_AUDITOR')
ON CONFLICT (name) DO NOTHING;

-- System user that owns the settlement account. It is disabled, has no roles
-- and its password hash is not a BCrypt hash, so it can never log in.
INSERT INTO app_users (full_name, email, password_hash, enabled)
VALUES ('LedgerLine Settlement', 'settlement@ledgerline.system', '!login-disabled!', FALSE)
ON CONFLICT (email) DO NOTHING;

-- The bank's settlement (cash) account. Deposits debit it and withdrawals credit
-- it, so its balance always equals the sum of all customer balances.
INSERT INTO accounts (user_id, account_number, balance, status, account_type)
SELECT id, 'LL-SETTLEMENT', 0.00, 'ACTIVE', 'SETTLEMENT'
FROM app_users
WHERE email = 'settlement@ledgerline.system'
ON CONFLICT DO NOTHING;
