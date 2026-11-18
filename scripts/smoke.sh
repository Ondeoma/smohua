#!/usr/bin/env bash
# 開発環境（DEV_LOGIN=true）で API を一通り呼ぶ確認用スクリプト。使い方: scripts/smoke.sh [BASE_URL]
set -euo pipefail
B=${1:-http://localhost:8118}
J=$(mktemp -d)
CA="$J/a" CB="$J/b"
req() { # cookie method path [json]
  local c=$1 m=$2 p=$3 d=${4:-}
  if [ -n "$d" ]; then curl -sS -b "$c" -c "$c" -X "$m" -H 'Content-Type: application/json' -d "$d" "$B$p"
  else curl -sS -b "$c" -c "$c" -X "$m" "$B$p"; fi
}
jqv() { python3 -c "import json,sys; d=json.load(sys.stdin); print(eval(sys.argv[1]))" "$1"; }
echo "== login";         req $CA POST /auth/dev-login '{"name":"山田 太郎","email":"yamada@example.com","admin":true}' >/dev/null
                         req $CB POST /auth/dev-login '{"name":"佐藤 花子","email":"sato@example.com"}' >/dev/null
N="スモーク$(date +%H%M%S)"
Q='[{"id":"name","kind":"short","label":"お名前","required":true},{"id":"day","kind":"multi","label":"参加できる日","options":["月","火","水"]},{"id":"score","kind":"scale","label":"満足度"}]'
echo "== create $N";     F=$(req $CA POST /api/forms "{\"title\":\"$N\",\"questions\":$Q}" | jqv "d['id']"); echo $F
echo "== draft は見えない"; req $CB GET /api/public/forms/$F | jqv "d.get('error')"
echo "== open";          req $CA PATCH /api/forms/$F '{"status":"open","oneResponse":true,"confirmation":"ありがとうございました"}' | jqv "(d['status'], d['oneResponse'])"
echo "== fill (佐藤)";    req $CB GET /api/public/forms/$F | jqv "(d['title'], len(d['questions']), d['answered'])"
echo "== submit";        req $CB POST /api/public/forms/$F/responses '{"answers":{"name":"佐藤","day":["火","月"],"score":4}}' | jqv "d['confirmation']"
echo "== 2回目は不可";     req $CB POST /api/public/forms/$F/responses '{"answers":{"name":"佐藤"}}' | jqv "d.get('error')"
echo "== 必須の確認";      req $CA POST /api/public/forms/$F/responses '{"answers":{"day":["日"]}}' | jqv "d.get('error')"
echo "== responses";     req $CA GET /api/forms/$F/responses | jqv "(d['total'], [(r['respondent']['name'], r['answers']) for r in d['responses']])"
echo "== summary";       req $CA GET /api/forms/$F/summary | jqv "[(q['label'], [(c['label'], c['count']) for c in q['counts']], q['average']) for q in d['questions']]"
echo "== csv";           req $CA GET /api/forms/$F/responses.csv | head -3
echo "== notifications"; sleep 1; req $CA GET "/api/notifications?unread=true" | jqv "[(n['title'], n['body']) for n in d['items']][:1]"
echo "== 他人には見えない";  req $CB GET /api/forms/$F/responses | jqv "d.get('error')"
echo "== mcp";           KEY=$(req $CA POST /api/me/api-keys '{"name":"smoke","scopes":["read","write"]}' | jqv "d['key']")
mcp() { curl -sS -X POST $B/mcp -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' -d "$1"; }
mcp '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | jqv "[t['name'] for t in d['result']['tools']]"
mcp "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"list_responses\",\"arguments\":{\"form\":\"$N\"}}}" | jqv "d['result']['content'][0]['text']"
mcp "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"get_summary\",\"arguments\":{\"form\":\"$F\"}}}" | jqv "d['result']['content'][0]['text']"
echo "== close・delete";  req $CA PATCH /api/forms/$F '{"status":"closed"}' | jqv "d['status']"; req $CA DELETE /api/forms/$F
echo "ok"
