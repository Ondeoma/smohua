-- smohua のスキーマ
-- 適用済みのマイグレーションは書き換えないこと。変更は新しい番号の V<n>__*.sql を追加する。

-- ------------------------------------------------------------------ ユーザー・認証

create table app_user (
  id            uuid primary key,
  sub           text not null unique,            -- OIDC の sub
  name          text not null,
  email         text,
  avatar_url    text,                            -- IdP（sruru）で登録したアイコン。ログインのたびに写す（外されたら外す）
  is_admin      boolean not null default false,  -- 画面で付けた管理者権限（ADMIN_GROUPS の所属者は別途管理者）
  disabled      boolean not null default false,
  timezone      text not null default 'Asia/Tokyo',
  created_at    timestamptz not null default now(),
  last_login_at timestamptz
);

create table user_group (
  id   uuid primary key,
  name text not null unique
);

create table group_member (
  group_id uuid not null references user_group(id) on delete cascade,
  user_id  uuid not null references app_user(id) on delete cascade,
  primary key (group_id, user_id)
);

-- セッションは DB に置く（複数ノードのどこに来てもよい）
create table session (
  token_hash bytea primary key,
  user_id    uuid not null references app_user(id) on delete cascade,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null,
  id_token   text                                -- IdP からもログアウトするときに渡す id_token_hint（OIDC でログインしたセッションだけ）
);
create index session_expires_idx on session (expires_at);

-- OIDC のログイン途中の状態（複数ノードのどこに戻ってきてもよいよう DB に置く）
create table login_state (
  state      text primary key,
  verifier   text not null,
  nonce      text not null,
  return_to  text not null default '/',
  created_at timestamptz not null default now()
);

create table api_key (
  id           uuid primary key,
  user_id      uuid not null references app_user(id) on delete cascade,
  name         text not null,
  prefix       text not null,                    -- 画面に出す先頭部分（smohua_xxxx）
  key_hash     bytea not null unique,
  scopes       text[] not null,                  -- read / write / admin
  created_at   timestamptz not null default now(),
  expires_at   timestamptz,
  last_used_at timestamptz,
  revoked_at   timestamptz,
  oauth_client text                              -- 外部サービスへの認可で発行したキーのクライアント（許可し直したら前のキーを失効させる）
);
create index api_key_user_idx on api_key (user_id);
create index api_key_oauth_idx on api_key (user_id, oauth_client) where oauth_client is not null;

-- ------------------------------------------------------------------ フォーム

create table form (
  id           uuid primary key,
  owner_id     uuid not null references app_user(id),
  title        text not null,
  description  text not null default '',
  status       text not null default 'draft' check (status in ('draft', 'open', 'closed')),
  access       text not null default 'login' check (access in ('anyone', 'login')),
  one_response boolean not null default false,   -- 1人1回だけ（access = login のときだけ）
  confirmation text not null default '',         -- 回答した後に見せる文
  questions    jsonb not null default '[]',      -- [{id, kind, label, help, required, options, scaleMin, scaleMax}]
  notify       jsonb not null default '{}',      -- 回答が届いたときの知らせ方 {inApp, mail, stui, stuiChannel}
  open_from    timestamptz,                      -- 受付期間（status が open のときだけ効く。どちらも null なら期限なし）
  open_until   timestamptz,
  appearance   jsonb not null default '{}',      -- 回答の画面の見た目（埋め込み用の色・文言・表示の有無・CSS）
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now(),
  deleted_at   timestamptz,                      -- 削除（30日後に回答ごと消す）
  constraint form_open_range check (open_from is null or open_until is null or open_from < open_until)
);
create index form_owner_idx on form (owner_id, updated_at desc) where deleted_at is null;

-- 持ち主のほかに、編集して回答を見られる人
create table form_editor (
  form_id uuid not null references form(id) on delete cascade,
  user_id uuid not null references app_user(id) on delete cascade,
  primary key (form_id, user_id)
);
create index form_editor_user_idx on form_editor (user_id);

create table form_response (
  id         uuid primary key,
  seq        bigserial not null unique,           -- 全体の通し番号（並び順・続きの取得）
  form_id    uuid not null references form(id) on delete cascade,
  user_id    uuid references app_user(id) on delete set null,  -- ログインして回答した人（access = anyone のフォームでは記録しない）
  answers    jsonb not null,                      -- {質問の id: 値}
  created_at timestamptz not null default now()
);
create index form_response_form_idx on form_response (form_id, seq desc);
create index form_response_user_idx on form_response (form_id, user_id) where user_id is not null;

-- フォームの変更・回答の履歴（画面への知らせ。各ノードがここを見て、つないでいる画面に配る）
create table form_event (
  id      bigserial primary key,
  form_id uuid not null,
  kind    text not null,                          -- form.created / form.updated / form.deleted / response.created / response.deleted
  at      timestamptz not null default now()
);
create index form_event_at_idx on form_event (at);

-- ------------------------------------------------------------------ 通知

-- 画面内の通知
create table notification (
  id       uuid primary key,
  user_id  uuid not null references app_user(id) on delete cascade,
  kind     text not null,                         -- response
  form_id  uuid references form(id) on delete cascade,
  title    text not null,
  body     text not null default '',
  link     text not null default '/',
  at       timestamptz not null default now(),
  read_at  timestamptz
);
create index notification_user_idx on notification (user_id, at desc);
create index notification_unread_idx on notification (user_id) where read_at is null;
-- 画面への知らせ（各ノードが0.5秒ごとに「この時刻より後の通知」を探す）と、古い通知の掃除
create index notification_at_idx on notification (at);
-- フォームを消すと、そのフォームの通知も消える（on delete cascade）。そのときに通知の表を全部読まないため
create index notification_form_idx on notification (form_id) where form_id is not null;

-- メール（SMTP。skereru など）と stui へ送る通知の送信待ち（SKIP LOCKED でノードが取り合う。失敗したら間を空けて送り直す）
create table notify_outbox (
  id         uuid primary key,
  user_id    uuid not null references app_user(id) on delete cascade,  -- 宛先の人（stui は本人の連携のトークンで送る）
  provider   text not null,                       -- mail / stui
  payload    jsonb not null,
  not_before timestamptz not null default now(),
  attempts   int not null default 0,
  last_error text,
  sent_at    timestamptz,
  skipped    boolean not null default false,
  created_at timestamptz not null default now()
);
create index notify_outbox_pending_idx on notify_outbox (not_before) where sent_at is null and not skipped;

-- プッシュ通知（PWA）. VAPID の鍵（全ノードで同じものを使う）と、ブラウザ・端末ごとの購読
create table push_config (
  id            int primary key check (id = 1),
  vapid_public  text not null,
  vapid_private text not null,
  created_at    timestamptz not null default now()
);

create table push_subscription (
  id           uuid primary key,
  user_id      uuid not null references app_user(id) on delete cascade,
  endpoint     text not null unique,
  p256dh       text not null,
  auth         text not null,
  user_agent   text,
  created_at   timestamptz not null default now(),
  last_used_at timestamptz
);
create index push_subscription_user_idx on push_subscription (user_id);

-- ------------------------------------------------------------------ 外部サービスとの連携（OAuth）

-- smohua が認可を出す側（skiwi などが smohua のフォーム・回答を読む）。認可コード: 60秒で失効、一度だけ使える
create table oauth_code (
  code_hash      bytea primary key,
  client_id      text not null,
  redirect_uri   text not null,
  user_id        uuid not null references app_user(id) on delete cascade,
  code_challenge text not null,
  scope          text not null,
  expires_at     timestamptz not null,
  used_at        timestamptz
);
create index oauth_code_expires_idx on oauth_code (expires_at);

-- smohua が認可を受ける側（smohua が stui に通知を送る）。ユーザーごとのトークン
create table integration_token (
  user_id    uuid not null references app_user(id) on delete cascade,
  provider   text not null,
  token_enc  text not null,                      -- APP_SECRET の鍵で暗号化したトークン
  created_at timestamptz not null default now(),
  primary key (user_id, provider)
);

-- 認可を始めたときの state と PKCE の code_verifier（10分で失効）
create table integration_state (
  state         text primary key,
  user_id       uuid not null references app_user(id) on delete cascade,
  provider      text not null,
  code_verifier text not null,
  return_to     text not null,
  expires_at    timestamptz not null
);

-- ------------------------------------------------------------------ 運用

-- 複数ノードで1つだけ動かす定期処理のリース
create table job_lock (
  name       text primary key,
  holder     text not null,
  expires_at timestamptz not null
);

create table audit_log (
  id       bigserial primary key,
  at       timestamptz not null default now(),
  actor_id uuid,
  via      text not null,
  action   text not null,
  target   text,
  detail   jsonb not null default '{}'
);
create index audit_log_at_idx on audit_log (at desc);
