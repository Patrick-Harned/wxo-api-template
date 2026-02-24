CREATE TABLE IF NOT EXISTS users (
    user_id INTEGER PRIMARY KEY AUTOINCREMENT,
    cpf_cnpj VARCHAR(18) UNIQUE NOT NULL, 
    name VARCHAR(100) NOT NULL,
    account_number VARCHAR(20) NOT NULL,
    branch_number VARCHAR(10) NOT NULL,
    account_type VARCHAR(20) NOT NULL, 
    balance DECIMAL(15, 2) DEFAULT 0.00,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS pix_keys (
    pix_key_id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    key_type VARCHAR(20) NOT NULL, 
    key_value VARCHAR(255) UNIQUE NOT NULL,
    status VARCHAR(20) DEFAULT 'active', 
    is_primary BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(user_id)
);

CREATE INDEX IF NOT EXISTS idx_pix_keys_key_value ON pix_keys(key_value);
CREATE INDEX IF NOT EXISTS idx_pix_keys_user_id ON pix_keys(user_id);
CREATE TABLE IF NOT EXISTS transactions (
    transaction_id INTEGER PRIMARY KEY AUTOINCREMENT,
    transaction_uuid VARCHAR(36) UNIQUE NOT NULL,
    sender_user_id INTEGER NOT NULL,
    receiver_user_id INTEGER NOT NULL,
    amount DECIMAL(15, 2) NOT NULL,
    transaction_type VARCHAR(20) NOT NULL,
    pix_key_used VARCHAR(255),
    description TEXT,
    status VARCHAR(20) DEFAULT 'completed',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP,
    FOREIGN KEY (sender_user_id) REFERENCES users(user_id),
    FOREIGN KEY (receiver_user_id) REFERENCES users(user_id)
);

CREATE INDEX IF NOT EXISTS idx_transactions_sender ON transactions(sender_user_id);
CREATE INDEX IF NOT EXISTS idx_transactions_receiver ON transactions(receiver_user_id);
CREATE INDEX IF NOT EXISTS idx_transactions_created_at ON transactions(created_at);
CREATE TABLE IF NOT EXISTS qr_codes (
    qr_code_id INTEGER PRIMARY KEY AUTOINCREMENT,
    qr_code_string VARCHAR(500) UNIQUE NOT NULL,
    user_id INTEGER NOT NULL,
    amount DECIMAL(15, 2),
    description TEXT,
    expiration_date TIMESTAMP,
    status VARCHAR(20) DEFAULT 'active', 
    usage_limit INTEGER DEFAULT 1, 
    times_used INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(user_id)
);

CREATE INDEX IF NOT EXISTS idx_qr_codes_string ON qr_codes(qr_code_string);
CREATE TABLE IF NOT EXISTS pix_copy_paste_codes (
    code_id INTEGER PRIMARY KEY AUTOINCREMENT,
    code_string TEXT UNIQUE NOT NULL, 
    user_id INTEGER NOT NULL,
    amount DECIMAL(15, 2) NOT NULL,
    description TEXT,
    expiration_date TIMESTAMP,
    status VARCHAR(20) DEFAULT 'active',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(user_id)
);
CREATE TABLE IF NOT EXISTS pending_transactions (
    pending_id INTEGER PRIMARY KEY AUTOINCREMENT,
    sender_user_id INTEGER NOT NULL,
    receiver_pix_key VARCHAR(255) NOT NULL,
    amount DECIMAL(15, 2) NOT NULL,
    description TEXT,
    confirmation_code VARCHAR(10),
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (sender_user_id) REFERENCES users(user_id)
);

