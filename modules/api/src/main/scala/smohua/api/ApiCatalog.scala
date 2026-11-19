package smohua.api

/** REST API の一覧（MCP の list_api_endpoints と GET /api/endpoints）。 */
object ApiCatalog {
  final case class Endpoint(method: String, path: String, description: String)
  val all: List[Endpoint] = List(
    Endpoint("GET", "/api/me", "自分の情報"),
    Endpoint("GET", "/api/users?q=", "ユーザーを探す"),
    Endpoint("GET", "/api/forms?q=&all=true", "自分が持ち主か編集できるフォーム（all=true は管理者だけ。すべてのフォーム）"),
    Endpoint(
      "POST",
      "/api/forms",
      "フォームを作る {title, description?, questions?: [{id, kind, label, help?, required?, options?, scaleMin?, scaleMax?}]}。kind は short・long・single・multi・dropdown・scale・date・number"
    ),
    Endpoint("POST", "/api/forms/lookup", "フォームの題名・状態・回答の数をまとめて読む {ids}（連携サービス向け。100件まで）"),
    Endpoint("GET", "/api/forms/{formId}", "フォームの中身（質問・設定）"),
    Endpoint(
      "PATCH",
      "/api/forms/{formId}",
      "フォームを変える {title?, description?, status?: draft|open|closed, access?: anyone|login, oneResponse?, confirmation?, questions?, notice?: {inApp, mail, stui, stuiChannel}, editorIds?, schedule?: {from?, until?}（ISO 8601. 受付期間）, appearance?: {background, card, text, accent（#rrggbb）, submitLabel, showTitle, showDescription, showBranding, showAnonymous, css}}"
    ),
    Endpoint("DELETE", "/api/forms/{formId}", "フォームを削除する（持ち主・管理者）"),
    Endpoint("POST", "/api/forms/{formId}/duplicate", "フォームを写して新しい下書きを作る"),
    Endpoint("GET", "/api/forms/{formId}/responses?before=&limit=", "回答の一覧（新しい順。before は seq）"),
    Endpoint("GET", "/api/forms/{formId}/responses.csv", "回答の CSV"),
    Endpoint("DELETE", "/api/forms/{formId}/responses/{responseId}", "回答を削除する"),
    Endpoint("GET", "/api/forms/{formId}/summary", "質問ごとの集計（選択肢の件数・平均・記述）"),
    Endpoint("GET", "/api/public/forms/{formId}", "回答する人に見せるフォーム"),
    Endpoint("POST", "/api/public/forms/{formId}/responses", "回答する {answers: {質問の id: 値}}（複数選ぶ質問は文字列の配列）"),
    Endpoint("GET", "/api/notifications?unread=true", "自分への通知"),
    Endpoint("POST", "/api/notifications/read", "通知を既読にする {ids?}（省略するとすべて）"),
    Endpoint("GET", "/api/integrations/links", "通知を送れる兄弟のサービス（stui）と、連携済みかどうか"),
    Endpoint("GET", "/api/admin/users", "ユーザーの一覧（管理者）"),
    Endpoint("PATCH", "/api/admin/users/{userId}", "ユーザーを変える {isAdmin?, disabled?}（管理者）"),
    Endpoint("GET", "/api/admin/audit", "監査ログ（管理者）")
  )
}
