# 兄弟のサービスとの連携

smohua が兄弟のサービスとやり取りする向きと、それぞれの設定をまとめます。

| 相手 | 向き | 何をするか | 設定 |
|---|---|---|---|
| sruru（IdP） | smohua → sruru | OIDC ログイン。名前・メール・グループ・アイコン（`picture`）を受け取る | `OIDC_*`・`ADMIN_GROUPS` |
| skorimako | smohua → skorimako | ログ・トレース・メトリクス（OTLP）、ブラウザの観測（RUM） | `OTEL_EXPORTER_OTLP_*`・`SKORIMAKO_RUM_*` |
| skereru（メール） | smohua → skereru | 回答が届いた知らせをメールで送る（SMTP） | `SMTP_*`・`MAIL_FROM` |
| stui（チャット） | smohua → stui | 回答が届いた知らせを DM・チャンネルに送る（OAuth + `POST /api/apps/notify`） | `STUI_*` |
| skiwi（Wiki） | skiwi → smohua | ページの中のブロックに、フォームの回答を表示する（OAuth + 読み取りの API） | `OAUTH_CLIENTS` |

## 1. sruru（OIDC とアイコン）

- 認可コード + PKCE。state・nonce・code_verifier は DB（`login_state`）に置き、state はブラウザの Cookie とも照合する。
- ID トークンの `sub`・`name`・`email`・`groups`・`picture` を使う。ログインのたびに `app_user` を更新する。
- **アイコン**: `picture`（sruru で登録したアイコンの URL）を `avatar_url` にそのまま写す。sruru でアイコンを外すと `picture` がなくなるので、次のログインで smohua でも外れる
  （`UserStore.upsert`。stui・skea は「自分で登録したアイコンがない間だけ」IdP のものを使うが、smohua は自分で登録する機能を持たないので、常に IdP のものを使う）。
- `ADMIN_GROUPS` のグループに入っている人は管理者。

## 2. skorimako（観測）

stui と同じ `modules/observe`（otel4s）。`OTEL_EXPORTER_OTLP_ENDPOINT` がなければ何も送らない。

- **ログ**: logback から OTLP へ。トレースの ID と操作したユーザー（`baggage.user.id`・`baggage.user.name`）が付く。標準出力は `LOG_FORMAT=text|json`。
- **トレース**: HTTP の要求（`/api`・`/auth`・`/mcp`・`/oauth`）、SQL、外への呼び出し（`traceparent` を付ける）。業務のスパンは
  `form.create`・`form.submit`・`form.summary`・`notify.fanout`・`mail.send`・`stui.notify`・`push.send`。
- **メトリクス**: JVM・HTTP・SQL に加えて、
  - カウンター: `smohua.forms.created{via}`・`smohua.forms.status_changed{status}`・`smohua.responses.submitted{access,via}`・
    `smohua.notifications.sent{provider,result}`・`smohua.push.sent{result}`・`smohua.logins{via}`
  - 増減: `smohua.sse.connections`
  - DB から読むゲージ: `smohua.users.total`・`smohua.users.active_24h`・`smohua.sessions.active`・`smohua.forms.total`・`smohua.forms.open`・
    `smohua.responses.total`・`smohua.responses.last_24h`・`smohua.notify.outbox`（送信待ちが溜まっていないかのアラートに使える）
- **ブラウザ（RUM）**: `SKORIMAKO_RUM_URL`・`SKORIMAKO_RUM_TOKEN` があれば、画面が `{URL}/rum.js` を読み込む（`GET /api/observe/rum`）。

## 3. skereru（メール）

skereru はメールサーバー（SMTP・IMAP・Web メール）で、通知用の API は持たない。兄弟（skea・skiwi・sruru・stui）と同じく、SMTP でメールを送る。

- skereru に送信用のメールボックス（例: `smohua@example.com`）を作り、そのアプリパスワードを `SMTP_USER`・`SMTP_PASSWORD` に、同じアドレスを `MAIL_FROM` に設定する
  （`SMTP_HOST` は skereru、`SMTP_PORT=587`、`SMTP_TLS=starttls`）。
- フォームの「設定」→「回答が届いたときの知らせ」→「メール」を選ぶと、持ち主と編集できる人のメールアドレス（IdP から受け取ったもの）に送る。
- 送信は `notify_outbox` に積んで、裏のワーカーが送る（失敗は間を空けて5回まで）。

## 4. stui（チャット）

兄弟（skea・skorimako）と同じやり方（stui の `docs/integrations.md` の「兄弟 → stui」）。

1. stui の `OAUTH_CLIENTS` に smohua を登録する。
   ```json
   {"id":"smohua","secret":"(16文字以上のランダムな値)","redirectUri":"https://smohua.example.com/api/integrations/stui/callback","name":"smohua","scopes":["read","write"]}
   ```
   開発用（`scripts/dev-env.sh` の値。`PUBLIC_URL` が画面の開発サーバー `http://localhost:5198` のとき）:
   `{"id":"smohua","secret":"smohua-dev-secret-0123456789","redirectUri":"http://localhost:5198/api/integrations/stui/callback","name":"smohua","scopes":["read","write"]}`
   を stui の `scripts/dev-env.sh` の `OAUTH_CLIENTS` に足す。
2. smohua に `STUI_URL`（画面から開く URL）・`STUI_CLIENT_ID`・`STUI_CLIENT_SECRET`（・サーバーから呼ぶ URL が別なら `STUI_INTERNAL_URL`）を設定する。
3. 各ユーザーが、smohua の個人設定の「stui 連携」→「stui と連携する」で許可する（認可コード + PKCE）。トークンは `APP_SECRET` の鍵で暗号化して `integration_token` に保存する。
4. フォームの「設定」で「stui（チャット）」を選ぶ。
   - チャンネルを空にすると、連携している人それぞれに、ボット（smohua）からの DM で届く（`POST /api/apps/notify {title, text, url}`）。
   - チャンネルを書くと、持ち主の連携で、そのチャンネルに1回だけ投稿する（`{…, channel}`。持ち主がそのチャンネルに参加している必要がある）。
- stui が 401 を返したら（stui 側で失効）、その人の連携を外す。4xx は送り直さない。5xx・通信の失敗は間を空けて送り直す。

## 5. skiwi（ページの中のブロック）

skiwi 側の取り決めは skiwi の `docs/smohua-integration.md`。smohua が OAuth 2.0 の認可サーバー、skiwi がクライアントになる。

- `OAUTH_CLIENTS` に skiwi を登録する。
  ```json
  [{"id":"skiwi","secret":"(16文字以上のランダムな値)","redirectUri":"https://skiwi.example.com/api/integrations/smohua/callback","name":"skiwi","scopes":["read"]}]
  ```
- `GET /oauth/authorize`（許可画面。CSRF 対策つき）・`POST /oauth/token`（Basic 認証。PKCE S256）・`POST /oauth/revoke`。
  発行するトークンは、そのユーザーの読み取り専用の API キー（「skiwi 連携」。個人設定の API キーの一覧から失効できる。許可し直すと前のキーは失効する）。
- skiwi が呼ぶ API（本人の権限。見られないフォームは返さない・404）:
  - `POST /api/forms/lookup {"ids":[…]}` → `{"forms":[{"id","title","status","questions","responses","lastResponseAt","url"}]}`
  - `GET /api/forms/{ID}/summary` → 質問ごとの集計
  - `GET /api/forms/{ID}/responses?limit=50` → 回答の一覧
