# 開発用の環境変数（source scripts/dev-env.sh）。docker-compose.dev.yml の DB を使う
export DATABASE_URL=jdbc:postgresql://localhost:54398/smohua DATABASE_USER=smohua DATABASE_PASSWORD=smohua
export PUBLIC_URL=${PUBLIC_URL:-http://localhost:5198} HTTP_PORT=8118
export DEV_LOGIN=true
# 回数の制限（IP ごとの1分あたり）。Vite の開発サーバー経由ではすべての要求が 127.0.0.1 から来るので、開発中は緩める
export RATE_LIMIT_AUTH=${RATE_LIMIT_AUTH:-300} RATE_LIMIT_PUBLIC=${RATE_LIMIT_PUBLIC:-1000} RATE_LIMIT_SUBMIT=${RATE_LIMIT_SUBMIT:-300}
# テスト（sbt test）で DB を使うもの
export TEST_DATABASE_URL=jdbc:postgresql://localhost:54398/smohua_test
# メールの通知（skereru の開発環境の Mailpit。http://localhost:8032 で中身を見られる。skereru 本体に送るなら submission の 2587 番とアプリパスワード）
export SMTP_HOST=localhost SMTP_PORT=1032 SMTP_TLS=none MAIL_FROM=smohua@example.com
# smohua を使う兄弟のサービス（skiwi のページに回答を表示する。開発用の値。本番では secret をランダムな値にする）
export OAUTH_CLIENTS='[{"id":"skiwi","secret":"skiwi-dev-secret-0123456789","redirectUri":"http://localhost:5173/api/integrations/smohua/callback","name":"skiwi","scopes":["read"]}]'
# smohua から stui へ通知する向き（stui の OAUTH_CLIENTS に client_id=smohua として登録する。URL は stui の画面の開発サーバー）
export STUI_URL=http://localhost:5178 STUI_INTERNAL_URL=http://localhost:8098 STUI_CLIENT_ID=smohua STUI_CLIENT_SECRET=smohua-dev-secret-0123456789
