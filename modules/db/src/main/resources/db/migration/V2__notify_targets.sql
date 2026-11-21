-- フォームごとの通知先（受信 Webhook・署名付きの Webhook・決まったメールアドレス）と、その送信待ち。
-- 持ち主と編集できる人への知らせ（画面内・メール・stui）は、これまでどおり form.notify と notify_outbox で送る。

create table form_target (
  id         uuid primary key,
  form_id    uuid not null references form(id) on delete cascade,
  name       text not null,
  kind       text not null check (kind in ('chat_hook', 'webhook', 'mail')),
  url        text,                                  -- chat_hook・webhook: 送り先
  address    text,                                  -- mail: 宛先のメールアドレス
  secret_enc text,                                  -- webhook: 署名の鍵（APP_SECRET で暗号化）
  events     text[] not null default '{response.created}',  -- response.created / form.opened / form.closed
  enabled    boolean not null default true,
  last_error text,
  created_at timestamptz not null default now()
);
create index form_target_form_idx on form_target (form_id);

-- 通知先への送信待ち（SKIP LOCKED で分担し、失敗したら間を空けて送り直す）
create table form_delivery (
  id          uuid primary key,
  target_id   uuid not null references form_target(id) on delete cascade,
  payload     jsonb not null,
  not_before  timestamptz not null default now(),
  attempts    int not null default 0,
  status_code int,
  last_error  text,
  done_at     timestamptz,
  failed      boolean not null default false,
  created_at  timestamptz not null default now()
);
create index form_delivery_pending_idx on form_delivery (not_before) where done_at is null and not failed;
create index form_delivery_target_idx on form_delivery (target_id);

-- 回答の控え（回答した人へのメール）は、ユーザーでない宛先にも送るので、宛先のアドレスを持てるようにする
alter table notify_outbox alter column user_id drop not null;
alter table notify_outbox add column address text;
alter table notify_outbox add constraint notify_outbox_to check (user_id is not null or address is not null);
create index notify_outbox_address_idx on notify_outbox (address, created_at) where address is not null;

-- いま回答を受け付けているか（受付の開始・締め切りを知らせるための、前の状態）。受付期間の始まり・終わりは時刻で変わるので、裏で見比べる
alter table form add column accepting boolean not null default false;
update form set accepting = (status = 'open' and (open_from is null or open_from <= now()) and (open_until is null or now() < open_until))
  where deleted_at is null;
