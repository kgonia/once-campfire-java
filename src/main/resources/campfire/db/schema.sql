CREATE TABLE "accounts" ("id" integer PRIMARY KEY AUTOINCREMENT NOT NULL, "created_at" datetime(6) NOT NULL, "custom_styles" text, "join_code" varchar NOT NULL, "name" varchar NOT NULL, "settings" json, "singleton_guard" integer DEFAULT 0 NOT NULL, "updated_at" datetime(6) NOT NULL);
CREATE UNIQUE INDEX "index_accounts_on_singleton_guard" ON "accounts" ("singleton_guard");
CREATE TABLE "action_text_rich_texts" ("id" integer PRIMARY KEY AUTOINCREMENT NOT NULL, "body" text, "created_at" datetime(6) NOT NULL, "name" varchar NOT NULL, "record_id" bigint NOT NULL, "record_type" varchar NOT NULL, "updated_at" datetime(6) NOT NULL);
CREATE UNIQUE INDEX "index_action_text_rich_texts_uniqueness" ON "action_text_rich_texts" ("record_type", "record_id", "name");
CREATE TABLE "active_storage_blobs" ("id" integer PRIMARY KEY AUTOINCREMENT NOT NULL, "byte_size" bigint NOT NULL, "checksum" varchar, "content_type" varchar, "created_at" datetime(6) NOT NULL, "filename" varchar NOT NULL, "key" varchar NOT NULL, "metadata" text, "service_name" varchar NOT NULL);
CREATE UNIQUE INDEX "index_active_storage_blobs_on_key" ON "active_storage_blobs" ("key");
CREATE TABLE "memberships" ("id" integer PRIMARY KEY AUTOINCREMENT NOT NULL, "connected_at" datetime(6), "connections" integer DEFAULT 0 NOT NULL, "created_at" datetime(6) NOT NULL, "involvement" varchar DEFAULT 'mentions', "room_id" integer NOT NULL, "unread_at" datetime(6), "updated_at" datetime(6) NOT NULL, "user_id" integer NOT NULL);
CREATE INDEX "index_memberships_on_room_id_and_created_at" ON "memberships" ("room_id", "created_at");
CREATE UNIQUE INDEX "index_memberships_on_room_id_and_user_id" ON "memberships" ("room_id", "user_id");
CREATE INDEX "index_memberships_on_room_id" ON "memberships" ("room_id");
CREATE INDEX "index_memberships_on_user_id" ON "memberships" ("user_id");
CREATE TABLE "rooms" ("id" integer PRIMARY KEY AUTOINCREMENT NOT NULL, "created_at" datetime(6) NOT NULL, "creator_id" bigint NOT NULL, "name" varchar, "type" varchar NOT NULL, "updated_at" datetime(6) NOT NULL);
CREATE TABLE "users" ("id" integer PRIMARY KEY AUTOINCREMENT NOT NULL, "bio" text, "bot_token" varchar, "created_at" datetime(6) NOT NULL, "email_address" varchar, "name" varchar NOT NULL, "password_digest" varchar, "role" integer DEFAULT 0 NOT NULL, "status" integer DEFAULT 0 NOT NULL, "updated_at" datetime(6) NOT NULL);
CREATE UNIQUE INDEX "index_users_on_bot_token" ON "users" ("bot_token");
CREATE UNIQUE INDEX "index_users_on_email_address" ON "users" ("email_address");
CREATE TABLE "active_storage_attachments" ("id" integer PRIMARY KEY AUTOINCREMENT NOT NULL, "blob_id" bigint NOT NULL, "created_at" datetime(6) NOT NULL, "name" varchar NOT NULL, "record_id" bigint NOT NULL, "record_type" varchar NOT NULL, CONSTRAINT "fk_rails_c3b3935057"
FOREIGN KEY ("blob_id")
  REFERENCES "active_storage_blobs" ("id")
);
CREATE INDEX "index_active_storage_attachments_on_blob_id" ON "active_storage_attachments" ("blob_id");
CREATE UNIQUE INDEX "index_active_storage_attachments_uniqueness" ON "active_storage_attachments" ("record_type", "record_id", "name", "blob_id");
CREATE TABLE "active_storage_variant_records" ("id" integer PRIMARY KEY AUTOINCREMENT NOT NULL, "blob_id" bigint NOT NULL, "variation_digest" varchar NOT NULL, CONSTRAINT "fk_rails_993965df05"
FOREIGN KEY ("blob_id")
  REFERENCES "active_storage_blobs" ("id")
);
CREATE UNIQUE INDEX "index_active_storage_variant_records_uniqueness" ON "active_storage_variant_records" ("blob_id", "variation_digest");
CREATE TABLE "bans" ("id" integer PRIMARY KEY AUTOINCREMENT NOT NULL, "created_at" datetime(6) NOT NULL, "ip_address" varchar NOT NULL, "updated_at" datetime(6) NOT NULL, "user_id" integer NOT NULL, CONSTRAINT "fk_rails_070022cd76"
FOREIGN KEY ("user_id")
  REFERENCES "users" ("id")
);
CREATE INDEX "index_bans_on_ip_address" ON "bans" ("ip_address");
CREATE INDEX "index_bans_on_user_id" ON "bans" ("user_id");
CREATE TABLE "boosts" ("id" integer PRIMARY KEY AUTOINCREMENT NOT NULL, "booster_id" integer NOT NULL, "content" varchar(16) NOT NULL, "created_at" datetime(6) NOT NULL, "message_id" integer NOT NULL, "updated_at" datetime(6) NOT NULL, CONSTRAINT "fk_rails_3539c52d73"
FOREIGN KEY ("message_id")
  REFERENCES "messages" ("id")
);
CREATE INDEX "index_boosts_on_booster_id" ON "boosts" ("booster_id");
CREATE INDEX "index_boosts_on_message_id" ON "boosts" ("message_id");
CREATE TABLE "messages" ("id" integer PRIMARY KEY AUTOINCREMENT NOT NULL, "client_message_id" varchar NOT NULL, "created_at" datetime(6) NOT NULL, "creator_id" integer NOT NULL, "room_id" integer NOT NULL, "updated_at" datetime(6) NOT NULL, CONSTRAINT "fk_rails_a8db0fb63a"
FOREIGN KEY ("room_id")
  REFERENCES "rooms" ("id")
, CONSTRAINT "fk_rails_761a2f12b3"
FOREIGN KEY ("creator_id")
  REFERENCES "users" ("id")
);
CREATE INDEX "index_messages_on_creator_id" ON "messages" ("creator_id");
CREATE INDEX "index_messages_on_room_id" ON "messages" ("room_id");
CREATE TABLE "push_subscriptions" ("id" integer PRIMARY KEY AUTOINCREMENT NOT NULL, "auth_key" varchar, "created_at" datetime(6) NOT NULL, "endpoint" varchar, "p256dh_key" varchar, "updated_at" datetime(6) NOT NULL, "user_agent" varchar, "user_id" integer NOT NULL, CONSTRAINT "fk_rails_43d43720fc"
FOREIGN KEY ("user_id")
  REFERENCES "users" ("id")
);
CREATE INDEX "idx_on_endpoint_p256dh_key_auth_key_7553014576" ON "push_subscriptions" ("endpoint", "p256dh_key", "auth_key");
CREATE INDEX "index_push_subscriptions_on_user_id" ON "push_subscriptions" ("user_id");
CREATE TABLE "searches" ("id" integer PRIMARY KEY AUTOINCREMENT NOT NULL, "created_at" datetime(6) NOT NULL, "query" varchar NOT NULL, "updated_at" datetime(6) NOT NULL, "user_id" integer NOT NULL, CONSTRAINT "fk_rails_e192b86393"
FOREIGN KEY ("user_id")
  REFERENCES "users" ("id")
);
CREATE INDEX "index_searches_on_user_id" ON "searches" ("user_id");
CREATE TABLE "sessions" ("id" integer PRIMARY KEY AUTOINCREMENT NOT NULL, "created_at" datetime(6) NOT NULL, "ip_address" varchar, "last_active_at" datetime(6) NOT NULL, "token" varchar NOT NULL, "updated_at" datetime(6) NOT NULL, "user_agent" varchar, "user_id" integer NOT NULL, CONSTRAINT "fk_rails_758836b4f0"
FOREIGN KEY ("user_id")
  REFERENCES "users" ("id")
);
CREATE UNIQUE INDEX "index_sessions_on_token" ON "sessions" ("token");
CREATE INDEX "index_sessions_on_user_id" ON "sessions" ("user_id");
CREATE TABLE "webhooks" ("id" integer PRIMARY KEY AUTOINCREMENT NOT NULL, "created_at" datetime(6) NOT NULL, "updated_at" datetime(6) NOT NULL, "url" varchar, "user_id" integer NOT NULL, CONSTRAINT "fk_rails_51bf96d3bc"
FOREIGN KEY ("user_id")
  REFERENCES "users" ("id")
);
CREATE INDEX "index_webhooks_on_user_id" ON "webhooks" ("user_id");
CREATE VIRTUAL TABLE message_search_index USING fts5 (body, tokenize=porter);
CREATE TABLE "schema_migrations" ("version" varchar NOT NULL PRIMARY KEY);
CREATE TABLE "ar_internal_metadata" ("key" varchar NOT NULL PRIMARY KEY, "value" varchar, "created_at" datetime(6) NOT NULL, "updated_at" datetime(6) NOT NULL);
