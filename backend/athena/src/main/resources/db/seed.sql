INSERT INTO roles (name)
VALUES
    ('ROLE_ADMIN'),
    ('ROLE_USER'),
    ('ROLE_AUDITOR')
ON CONFLICT (name) DO NOTHING;

-- The project was renamed from LedgerLine to Athena. A database created before
-- the rename keeps its system user and its one settlement account, under the
-- new names, so a second settlement account is never created. No-op otherwise.
UPDATE app_users
SET email = 'settlement@athena.system', full_name = 'Athena Settlement'
WHERE email = 'settlement@ledgerline.system'
  AND NOT EXISTS (SELECT 1 FROM app_users WHERE email = 'settlement@athena.system');

UPDATE accounts
SET account_number = 'AT-SETTLEMENT'
WHERE account_number = 'LL-SETTLEMENT'
  AND account_type = 'SETTLEMENT'
  AND NOT EXISTS (SELECT 1 FROM accounts WHERE account_number = 'AT-SETTLEMENT');

-- System user that owns the settlement account. It is disabled, has no roles
-- and its password hash is not a BCrypt hash, so it can never log in.
INSERT INTO app_users (full_name, email, password_hash, enabled)
VALUES ('Athena Settlement', 'settlement@athena.system', '!login-disabled!', FALSE)
ON CONFLICT (email) DO NOTHING;

-- The bank's settlement (cash) account. Deposits debit it and withdrawals credit
-- it, so its balance always equals the sum of all customer balances.
INSERT INTO accounts (user_id, account_number, balance, status, account_type)
SELECT id, 'AT-SETTLEMENT', 0.00, 'ACTIVE', 'SETTLEMENT'
FROM app_users
WHERE email = 'settlement@athena.system'
ON CONFLICT DO NOTHING;
