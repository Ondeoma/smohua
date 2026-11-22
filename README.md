# smohua

<p align="center"><img src="frontend/public/logo.svg" width="128" height="128" alt="smohua のロゴ"></p>

smohua は、フォームを作って回答を集めるためのツールです（アンケート・申し込み・出欠の確認など）。ログインは OpenID Connect（OIDC）。ライセンス: MIT

- **フォームを作る**: 質問を並べるだけ。種類は、記述（1行・複数行）・1つ選ぶ・複数選ぶ・プルダウン・段階で選ぶ・日付・数。必須、補足の文、並べ替え、複製
- **公開する**: 下書き → 受付中 → 締め切り。回答用の URL（`/f/{ID}`）を渡す。回答できる人は「URL を知っている人（ログイン不要・匿名）」か
  「ログインした人だけ」。ログインした人だけのフォームは「1人1回」にできる。回答した後に見せる文も決められる。受付期間（始まりと終わりの日時）も決められる
- **埋め込む**: 回答の画面を iframe でほかのサイトに埋め込める（`/f/{ID}?embed=1`。設定の画面で埋め込み用の HTML をコピーできる）。
  背景・カード・文字・強調の色、送信ボタンの文言、題名・説明・「smohua のフォーム」・「この回答は匿名で送られます」の表示の有無、追加の CSS を決められる
- **回答を集める**: 回答は質問の id に結び付くので、あとから質問文や選択肢を変えても、前の回答は残る
- **回答を見る**: 質問ごとの**集計**（選択肢ごとの件数と割合・段階や数の平均・記述の一覧）と、回答の**一覧**。CSV（Excel で開ける UTF-8）のダウンロード。
  新しい回答は、開いている画面にすぐ出る（Server-Sent Events）
- **通知**: 回答が届いた・受付を始めた・締め切った（受付期間の始まり・終わりを含む）ときに知らせる。フォームごとに選ぶ
  - 持ち主と編集できる人へ: 画面内の通知とプッシュ通知（PWA）、メール（SMTP。**skereru** など）、**stui**（チャット。本人への DM か、決めたチャンネル）
  - 回答した人へ: 回答の控えをメールで送る（ログインした人はそのアドレス、匿名のフォームは「メールアドレス」の質問の答え）
  - 通知先へ（持ち主と管理者が設定）: **受信 Webhook**（stui・Slack・Mattermost のチャンネルに投稿）、**Webhook**（署名付きの JSON。ほかのシステムとの連携）、
    決まった**メールアドレス**（問い合わせの窓口など）。試しに送れる
- **いっしょに使う**: フォームに「編集できる人」を足せる（質問と設定を変えられ、回答を見られる）
- **兄弟のサービスとの連携**: ログインとアイコンは **sruru**（IdP）、観測は **skorimako**、通知は **skereru**（メール）と **stui**、
  **skiwi** のページには回答を表示するブロックを置ける（[docs/integrations.md](docs/integrations.md)）
- **API・AI**: ユーザーごとのスコープ付き API キー、REST API、MCP（AI からフォームの作成・回答の取得・集計ができる）
- **PWA**: ホーム画面に追加でき、ブラウザを閉じていてもプッシュ通知が届く
- **冗長化**: アプリはステートレスで、複数ノードで動く（下の「冗長構成」）
- レスポンシブ対応、ダークモード

兄弟のシステム: [skea](https://github.com/Ondeoma/skea)（タスク管理）、[skiwi](https://github.com/Ondeoma/skiwi)（Wiki）、
[skawau](https://github.com/Ondeoma/skawau)（ファイル共有）、[skuaka](https://github.com/Ondeoma/skuaka)（カレンダー）、[skaka](https://github.com/Ondeoma/skaka)（Web 会議）、
[stui](https://github.com/Ondeoma/stui)（チャット）、[skereru](https://github.com/Ondeoma/skereru)（メール）、[skorimako](https://github.com/Ondeoma/skorimako)（オブザーバビリティ）、[sruru](https://github.com/Ondeoma/sruru)（IdP）。

## 構成

| モジュール | 内容 |
|---|---|
| `modules/shared` | バックエンドと画面（Scala.js）で共有する API の型（circe） |
| `modules/core` | ドメインの純粋なロジック（質問と回答の確認・集計・CSV） |
| `modules/db` | doobie のストア、Flyway のマイグレーション、ジョブのリース、送信待ちの表の処理 |
| `modules/auth` | OIDC（Authorization Code + PKCE）、APIキー、暗号化 |
| `modules/notify` | メール（SMTP） |
| `modules/observe` | 観測（OpenTelemetry。ログ・トレース・メトリクスを OTLP で skorimako へ） |
| `modules/push` | プッシュ通知（Web Push。暗号化と VAPID は JDK の機能だけで実装） |
| `modules/api` | http4s のルート（REST・SSE・MCP・OAuth）と、フォーム・通知のサービス |
| `modules/app` | 起動・設定（環境変数）・画面の配信 |
| `modules/web` | 画面（[Scalactive](https://github.com/Ondeoma/scalactive) の `.st` 単一ファイルコンポーネント） |
| `frontend/` | Vite。Scala.js の出力をビルドする。Service Worker（`public/sw.js`）と PWA の manifest・アイコン |

技術: Scala 3.3 LTS、Cats Effect 3、http4s 1.0（マイルストーン版）、doobie、Flyway、PostgreSQL 17、Scala.js + Scalactive。
画面は Scala.js で書き、JS のままなのは Service Worker と Vite の設定だけです。

## 開発

必要なもの: JDK 25、sbt、Node.js、Docker。

```bash
docker compose -f docker-compose.dev.yml up -d --wait   # PostgreSQL (:54398。テスト用の smohua_test も作る)
scripts/dev-restart.sh                                   # バックエンドを :8118 で起動（scripts/dev-env.sh の設定。DEV_LOGIN=true）
cd frontend && npm install && npm run dev                # 画面を :5198 で起動（/api などは :8118 へ中継）
```

http://localhost:5198 を開き、名前を入れてログインします（開発用のログイン。本番は OIDC）。

```bash
. scripts/dev-env.sh && sbt test          # テスト。TEST_DATABASE_URL がなければ DB を使うテストは飛ばす
sbt web/fastLinkJS                         # 画面（Scala.js）だけをコンパイル
(cd frontend && npm run build)             # 画面をビルド（frontend/dist）
scripts/smoke.sh                           # 起動したバックエンドで、フォームの作成 → 公開 → 回答 → 集計 → MCP を一通り呼ぶ
```

テストは `modules/core`（質問・回答・集計・CSV の規則）、`modules/db`（ストア。アイコンの反映、1人1回の排他、送信待ち、リース）、
`modules/api`（REST・MCP・通知をルートを通して。stui とメールは偽物）、`modules/push`（Web Push の暗号化）にあります。

ほかの兄弟と同時に動かせるよう、ポートは :5198（画面）・:8118（API）・:54398（DB）にしています。

## 配布・起動

```bash
scripts/dist.sh                                     # dist/lib/*.jar と dist/static（画面）を作る
STATIC_DIR=dist/static java -cp 'dist/lib/*' smohua.app.Main
```

### 環境変数

| 変数 | 内容 |
|---|---|
| `DATABASE_URL`・`DATABASE_USER`・`DATABASE_PASSWORD` | PostgreSQL（必須）。Patroni なら `jdbc:postgresql://db1,db2,db3/smohua?targetServerType=primary`。`DATABASE_POOL_SIZE`（既定 10） |
| `PUBLIC_URL` | 利用者から見た URL（必須。例: `https://smohua.example.com`） |
| `HTTP_PORT` | 待ち受けるポート（既定 8080） |
| `STATIC_DIR` | ビルドした画面（`dist/static`）。指定すると画面も配信する |
| `APP_SECRET` | 16文字以上の秘密の値（必須。連携のトークンの暗号化に使う。**全ノードで同じ値**） |
| `OIDC_ISSUER`・`OIDC_CLIENT_ID`・`OIDC_CLIENT_SECRET` | OIDC の IdP（sruru）。リダイレクト URI は `${PUBLIC_URL}/auth/callback`。ログアウトでは IdP からもログアウトし、`${PUBLIC_URL}/` に戻る（sruru 以外の IdP では、この戻り先も登録する）。`OIDC_GROUPS_CLAIM`（既定 `groups`） |
| `ADMIN_GROUPS` | 管理者にする IdP のグループ（カンマ区切り） |
| `TIMEZONE` | 新しいユーザーの既定のタイムゾーン（既定 `Asia/Tokyo`。CSV の日時に使う） |
| `SMTP_HOST`・`SMTP_PORT`・`SMTP_TLS`（`starttls`・`ssl`・`none`）・`SMTP_USER`・`SMTP_PASSWORD`・`MAIL_FROM` | メールの通知。skereru に送るなら、skereru に送信用のメールボックスを作り、submission（587番）とそのアプリパスワードを指定する |
| `STUI_URL`・`STUI_CLIENT_ID`・`STUI_CLIENT_SECRET`・`STUI_INTERNAL_URL` | stui への通知。stui の `OAUTH_CLIENTS` に smohua を登録する（下の「兄弟のサービスとの連携」） |
| `NOTIFY_ALLOW_INTERNAL` | フォームの通知先（受信 Webhook・Webhook）に、内部のアドレスを許す一覧（カンマ区切りの CIDR・IP・ホスト名）。既定は外のアドレスだけ。stui のホストは自動で足す |
| `OAUTH_CLIENTS` | smohua の回答を読む兄弟のサービス（skiwi）。JSON の配列 |
| `OTEL_EXPORTER_OTLP_ENDPOINT`・`OTEL_EXPORTER_OTLP_HEADERS` | skorimako へログ・トレース・メトリクスを送る（例: `https://skorimako.example.com`・`Authorization=Bearer <取り込み用のキー>`） |
| `SKORIMAKO_RUM_URL`・`SKORIMAKO_RUM_TOKEN` | ブラウザの観測（表示の速さ・JavaScript のエラー） |
| `VAPID_SUBJECT` | プッシュ通知の連絡先（`mailto:` か `https:`。省略すると `PUBLIC_URL`） |
| `RATE_LIMIT_AUTH`・`RATE_LIMIT_PUBLIC`・`RATE_LIMIT_SUBMIT` | IP ごとの1分あたりの回数の上限（ログインと OAuth の認可の画面〈`/auth/…`・`/oauth/authorize`〉20・OAuth のトークンの発行・失効〈`/oauth/token`・`/oauth/revoke`〉と回答する側の API〈`/api/public/…`〉120・回答の送信 20。0 なら制限しない。ノードごとに数える） |
| `TRUSTED_PROXIES` | リバースプロキシの IP か CIDR（カンマ区切り）。ここから来た要求だけ `X-Forwarded-For` を信じて、回数を数える IP とトレースの `client.address` を決める |
| `LOG_FORMAT`（`text`・`json`）・`LOG_LEVEL` | 標準出力のログ |
| `NODE_ID` | ノードの名前（ログとリースに出る。省略するとホスト名から作る） |
| `DEV_LOGIN` | `true` で開発用のログイン（名前だけ）。`PUBLIC_URL` が localhost（`localhost`・`127.0.0.1`・`[::1]`・`*.localhost`）のときだけ使える（それ以外では起動しない）。https の `PUBLIC_URL`・OIDC とは同時に使えない |

## 兄弟のサービスとの連携

くわしくは [docs/integrations.md](docs/integrations.md)。

- **OIDC（sruru）**: 兄弟と同じ OIDC ログイン（認可コード + PKCE。state は DB に置く）。
- **アイコン（sruru）**: sruru で登録したアイコン（ID トークンの `picture`）を、ログインのたびに写す。sruru でアイコンを外したら、次のログインで smohua でも外れる。
  smohua の画面からはアイコンを変えない（兄弟で「自分で登録したアイコン」を持つものは、それがない間だけ IdP のものを使う。smohua は常に IdP のものを使う）。
- **skorimako（ログ・トレース・メトリクス）**: stui と同じ `modules/observe`。HTTP・SQL・外への呼び出しのスパンに加えて、業務のスパン
  （`form.create`・`form.submit`・`form.summary`・`notify.fanout`・`mail.send`・`stui.notify`・`push.send`）と、メトリクス
  （`smohua.forms.created`・`smohua.forms.status_changed`・`smohua.responses.submitted`・`smohua.notifications.sent`・`smohua.push.sent`・`smohua.logins`・`smohua.sse.connections`、
  DB から読むゲージ `smohua.users.total`・`smohua.users.active_24h`・`smohua.sessions.active`・`smohua.forms.total`・`smohua.forms.open`・`smohua.responses.total`・
  `smohua.responses.last_24h`・`smohua.notify.outbox`・`smohua.notify.deliveries`）を送る。ログにはトレースの ID と操作したユーザーが付く。
- **skereru（メール）**: 兄弟と同じく SMTP でメールを送る（skereru は通知用の API を持たないメールサーバーなので、送り先を skereru にする）。
- **stui（チャット）**: 兄弟（skea・skorimako・skereru）と同じく、各ユーザーが個人設定の「stui 連携」で許可し（OAuth）、smohua が本人のトークンで `POST /api/apps/notify` を呼ぶ。
  連携しなくても、フォームの通知先に stui のチャンネルの受信 Webhook の URL を登録すれば、そのチャンネルに投稿できる。
- **skiwi（ブロック）**: skiwi のエディタの `/` →「フォームの回答（smohua）」。skiwi が smohua の OAuth のクライアントになり、見ている本人の権限で回答を読む。

## 冗長構成

アプリはステートレスで、同じ設定（`APP_SECRET` も同じ値）の複数ノードをロードバランサーの後ろに並べられます。状態はすべて PostgreSQL に置きます。

| もの | やり方 |
|---|---|
| セッション・OIDC のログイン途中の state・OAuth の認可コード・連携の state | DB（どのノードに戻ってきてもよい。スティッキーセッションは要らない） |
| 回答の「1人1回」 | 保存と同じトランザクションで、フォームと人の組のロック（`pg_advisory_xact_lock`）を取って確かめる |
| 通知の送信（メール・stui） | 送信待ちの表 `notify_outbox` を全ノードのワーカーが `FOR UPDATE SKIP LOCKED` で取り合う（二重に送らない）。失敗は間を空けて5回まで |
| 通知先への送信（受信 Webhook・Webhook・メールアドレス） | 送信待ちの表 `form_delivery` を同じく取り合う。失敗は間を空けて8回まで |
| 受付の開始・締め切りの検知 | 全ノードが30秒ごとに見比べ、`form.accepting` を書き換えられた1ノードだけが知らせる |
| 掃除（期限切れのセッション・古い通知・削除したフォーム） | `job_lock` のリースを取れた1ノードだけが動かす |
| 画面への知らせ（SSE） | 各ノードが0.5秒ごとに DB の変更履歴（`form_event`）と通知を確かめ、自分につないでいる画面へ配る。ノードどうしの連絡（Redis・LISTEN/NOTIFY）は使わない |
| プッシュ通知の鍵（VAPID） | DB に1組（最初に起動したノードが作り、全ノードで同じものを使う） |
| マイグレーション | 起動時に Flyway（同時に起動しても、Flyway のロックで1回だけ適用される） |

- ヘルスチェックは `GET /healthz`（DB のプライマリに書けなければ 503）。
- DB は Patroni などで冗長化し、`DATABASE_URL` に全ノードと `targetServerType=primary` を指定します（フェイルオーバーの後は自動でつなぎ直す）。
- SSE は長くつながるので、ロードバランサーのタイムアウトは60秒以上にします（20秒ごとに空の行を送る）。ノードが止まると、ブラウザが自動で別のノードにつなぎ直し、画面は読み直して追いつきます。
- 試験用の3ノード構成（stui の `docker-compose.ha.yml` に当たるもの）は、まだ用意していません。

## API・MCP

- **API キー**: 個人設定で発行する（`smohua_…`。スコープは read・write・admin。DB にはハッシュだけを保存）。`Authorization: Bearer <キー>` で送る。
- **REST API**: 一覧は `GET /api/endpoints`。おもなもの:

| API | 内容 |
|---|---|
| `GET /api/forms`・`POST /api/forms` | フォームの一覧・作成 |
| `GET`・`PATCH`・`DELETE /api/forms/{ID}` | フォームの中身・変更（公開・締め切りは `status`）・削除 |
| `GET /api/forms/{ID}/responses`・`/responses.csv`・`/summary` | 回答の一覧・CSV・集計 |
| `GET /api/public/forms/{ID}`・`POST /api/public/forms/{ID}/responses` | 回答する側（フォームの設定によってはログイン不要） |
| `POST /api/forms/lookup` | 連携サービス向け（題名・状態・回答の数をまとめて読む） |
| `GET /api/stream` | 画面への知らせ（SSE。`change`・`notify`） |

- **MCP**: `POST /mcp`（Streamable HTTP。認証は API キー）。ツールは `list_forms`・`get_form`・`list_responses`・`get_summary`・`create_form`・`update_form`・
  `set_form_status`・`submit_response`・`list_notifications`・`get_me`・`list_api_endpoints`・`call_api`。各ツールは REST API をそのキーで内部から呼ぶので、
  権限・スコープ・監査は REST API と同じです。

```bash
claude mcp add --transport http smohua https://smohua.example.com/mcp --header "Authorization: Bearer <APIキー>"
```

## 安全のための決まり

- 回答を見られるのは、フォームの持ち主・編集できる人・システム管理者だけです（ほかの人には、フォームがあることも見せません）。
- 「URL を知っている人」のフォームは匿名です（ログインしている人が答えても、だれの回答かは記録しません）。
- 状態を変える要求は、別のサイト（Origin が違う）からなら断ります。本文は 2MB まで。CSV は、表計算ソフトで式として動く値（`=` などで始まる）を無害にします。
- 画面には CSP を付けます。ほかのサイトへの埋め込み（`frame-ancestors`）を許すのは、回答の画面（`/f/…`）だけです。
- フォームの追加の CSS では、外のサイトを読み込む書き方（`@import`・`http(s):` や `//` の参照）を受け付けません（回答する人の IP アドレスが外に漏れないように）。
- ログイン・回答する側の API・回答の送信には、IP ごとの回数の制限があります（`RATE_LIMIT_*`。ノードごとに数えるので、複数ノードでは前段のロードバランサーでも流量を制限してください）。
