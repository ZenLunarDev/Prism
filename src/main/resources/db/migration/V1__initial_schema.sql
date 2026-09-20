CREATE TABLE IF NOT EXISTS prism_config (
    key VARCHAR(255) PRIMARY KEY,
    value TEXT NOT NULL,
    description TEXT,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_prism_config_updated ON prism_config(updated_at);

-- Default configuration entries
INSERT INTO prism_config (key, value, description) VALUES
('pools', '[]', 'Server pools configuration (JSON)'),
('fallback.chain', '["lobby", "fallback"]', 'Fallback chain (JSON array)'),
('fallback.default', 'lobby', 'Default fallback pool'),
('health_check.enabled', 'true', 'Enable health checks'),
('health_check.interval', '30', 'Health check interval in seconds'),
('health_check.timeout', '5000', 'Health check timeout in milliseconds')
ON CONFLICT (key) DO NOTHING;

CREATE TABLE IF NOT EXISTS prism_players (
    uuid UUID PRIMARY KEY,
    username VARCHAR(16) NOT NULL,
    first_join TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    last_seen TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    total_playtime BIGINT DEFAULT 0,
    current_server VARCHAR(255),
    current_pool VARCHAR(255),
    metadata JSONB DEFAULT '{}'
);

CREATE INDEX IF NOT EXISTS idx_prism_players_username ON prism_players(username);
CREATE INDEX IF NOT EXISTS idx_prism_players_last_seen ON prism_players(last_seen);

CREATE TABLE IF NOT EXISTS prism_servers (
    name VARCHAR(255) PRIMARY KEY,
    host VARCHAR(255) NOT NULL,
    port INTEGER NOT NULL DEFAULT 25565,
    pool_name VARCHAR(255),
    weight INTEGER DEFAULT 1,
    fallback BOOLEAN DEFAULT FALSE,
    motd TEXT,
    max_players INTEGER DEFAULT 0,
    online BOOLEAN DEFAULT TRUE,
    healthy BOOLEAN DEFAULT TRUE,
    last_ping TIMESTAMP WITH TIME ZONE,
    metadata JSONB DEFAULT '{}'
);

CREATE INDEX IF NOT EXISTS idx_prism_servers_pool ON prism_servers(pool_name);
CREATE INDEX IF NOT EXISTS idx_prism_servers_healthy ON prism_servers(healthy);

CREATE TABLE IF NOT EXISTS prism_events (
    id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    proxy_id VARCHAR(50) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_prism_events_type ON prism_events(event_type);
CREATE INDEX IF NOT EXISTS idx_prism_events_proxy ON prism_events(proxy_id);
CREATE INDEX IF NOT EXISTS idx_prism_events_created ON prism_events(created_at);

CREATE TABLE IF NOT EXISTS prism_bans (
    uuid UUID PRIMARY KEY,
    username VARCHAR(16),
    reason TEXT,
    banned_by UUID,
    banned_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    expires_at TIMESTAMP WITH TIME ZONE,
    active BOOLEAN DEFAULT TRUE
);

CREATE INDEX IF NOT EXISTS idx_prism_bans_active ON prism_bans(active);
CREATE INDEX IF NOT EXISTS idx_prism_bans_expires ON prism_bans(expires_at);

CREATE TABLE IF NOT EXISTS prism_permissions (
    uuid UUID NOT NULL,
    permission VARCHAR(255) NOT NULL,
    value BOOLEAN DEFAULT TRUE,
    context JSONB DEFAULT '{}',
    PRIMARY KEY (uuid, permission)
);

CREATE INDEX IF NOT EXISTS idx_prism_permissions_uuid ON prism_permissions(uuid);