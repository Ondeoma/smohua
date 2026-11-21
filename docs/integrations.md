# 兄弟のサービスとの連携

smohua が兄弟のサービスとやり取りする向きと、それぞれの設定をまとめます。

| 相手 | 向き | 何をするか | 設定 |
|---|---|---|---|
| sruru（IdP） | smohua → sruru | OIDC ログイン。名前・メール・グループ・アイコン（`picture`）を受け取る | `OIDC_*`・`ADMIN_GROUPS` |
| skorimako | smohua → skorimako | ログ・トレース・メトリクス（OTLP）、ブラウザの観測（RUM） | `OTEL_EXPORTER_OTLP_*`・`SKORIMAKO_RUM_*` |
| skereru（メール） | smohua → skereru | 回答・受付の開始と締め切りの知らせ、回答の控えをメールで送る（SMTP） | `SMTP_*`・`MAIL_FROM` |
| stui（チャット） | smohua → stui | 知らせを DM・チャンネルに送る（OAuth + `POST /api/apps/notify`、またはチャンネルの受信 Webhook） | `STUI_*` |
| ほかのシステム | smohua → 任意の URL | 知らせを署名付きの JSON で送る（フォームの通知先の Webhook） | `NOTIFY_ALLOW_INTERNAL` |
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
- 「受付を始めたとき・締め切ったときも知らせる」を選ぶと、受付の開始・締め切り（受付期間の始まり・終わりを含む）も同じ宛先に送る。
- **回答の控え**: 「回答した人に、回答の控えをメールで送る」を選ぶと、ログインして回答した人は IdP のアドレスへ、匿名のフォームは「メールアドレス」の質問の答えへ、
  すべての質問と答えを送る。知らない人へ送り付けるのに使われないよう、同じアドレスへは1時間に5通まで。
- **決まったアドレス**: フォームの通知先（6.）に「メールアドレス」を足すと、smohua を使っていない人（問い合わせの窓口など）にも送れる。
- 送信は `notify_outbox`（通知先は `form_delivery`）に積んで、裏のワーカーが送る（失敗は間を空けて5回〈通知先は8回〉まで）。

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
- 連携の手続き（認可コード + PKCE・トークンの交換と失効）と通知の送信は、兄弟と共通の `sbirds.links.LinkClient`・`StuiClient`（sbirds-lib）を使う。
- **連携しない使い方**: stui のチャンネルの設定の「連携」で受信 Webhook を作り、その URL をフォームの通知先（6.）の「チャットの受信 Webhook」に登録する。
  smohua の名前で、そのチャンネルに投稿する（Slack・Mattermost の受信 Webhook も同じ形）。

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

## 6. フォームの通知先（受信 Webhook・Webhook・メールアドレス）

フォームの「設定」→「通知先」で、持ち主と管理者が登録する（回答の中身がフォームの外へ出るため、編集できる人は見られない）。1つのフォームに20件まで。
通知先ごとに、知らせるきっかけ（`response.created` 回答が届いた・`form.opened` 受付を始めた・`form.closed` 締め切った）を選ぶ。

| 種類 | 送るもの |
|---|---|
| チャットの受信 Webhook（`chat_hook`） | `{"text": "**[見出し](URL)**\n\n要約", "username": "smohua"}`。回答の文は Markdown の記号を無害にしてから載せる |
| Webhook（`webhook`） | イベントの JSON（下）。HMAC-SHA256 の署名付き |
| メールアドレス（`mail`） | 見出し・要約・リンクのメール |

- 送り先の URL は、登録のときと送るたびに確かめ、内部のアドレス（ループバック・プライベートなど）には送らない（`sbirds.net.NetGuard`。リダイレクトはたどらない）。
  内部の相手に送るときは `NOTIFY_ALLOW_INTERNAL` で許す。stui のホストは自動で許す。
- 送れなかった理由は通知先の一覧に出す。「試す」で、送信待ちを通さずにその場で送れる（イベント `test`）。
- 受付の開始・締め切りは、状態を変えたときと、受付期間の始まり・終わり（全ノードが30秒ごとに見比べる）に送る。

### Webhook の中身

ヘッダー:

- `X-Smohua-Event`: イベントの名前
- `X-Smohua-Delivery`: 送信の ID（送り直しても同じ。受け手は重複を捨てるのに使う）
- `X-Smohua-Signature`: `sha256=` + hex(HMAC-SHA256(署名の鍵, 本文))。署名の鍵は通知先の編集の画面に出る

本文（`response.created`）:

```json
{
  "event": "response.created",
  "deliveryId": "…",
  "form": {"id": "…", "title": "申し込み", "url": "https://smohua.example.com/f/…", "responsesUrl": "https://smohua.example.com/forms/…/responses"},
  "response": {
    "id": "…", "seq": 12, "createdAt": "2026-11-21T01:23:45Z",
    "respondent": {"name": "山田", "email": "yamada@example.com"},
    "url": "https://smohua.example.com/forms/…/responses",
    "answers": [{"questionId": "name", "label": "お名前", "kind": "short", "value": "山田", "text": "山田"}]
  }
}
```

`respondent` は、ログインした人だけが答えるフォームのときだけ（匿名のフォームは `null`）。`value` は質問の種類のままの値（複数選ぶ質問は配列、数は数）、`text` は文にしたもの。

本文（`form.opened`・`form.closed`）: `{"event", "deliveryId", "form", "at", "responseCount"}`

署名の確かめ方（例: Python）:

```python
import hmac, hashlib
ok = hmac.compare_digest(request.headers["X-Smohua-Signature"],
                         "sha256=" + hmac.new(secret.encode(), request.body, hashlib.sha256).hexdigest())
```
