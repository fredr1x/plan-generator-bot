-- Several Telegram accounts of one teacher share the data of one account: user_id works as owner_id.
CREATE TABLE IF NOT EXISTS teacher_links (
    user_id  BIGINT PRIMARY KEY,
    owner_id BIGINT NOT NULL
);
