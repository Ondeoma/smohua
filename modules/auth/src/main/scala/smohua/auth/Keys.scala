package smohua.auth

/** smohua の APIキー（smohua_ で始まる）. */
object ApiKeys extends sbirds.auth.ApiKeys("smohua_")

/** smohua の秘密の値の暗号化（鍵の salt は smohua 固有. 変えると保存済みの値を復号できなくなる）. */
object SecretBox {
  def apply(secret: String): sbirds.auth.SecretBox = new sbirds.auth.SecretBox(secret, "smohua-secret-box:")
}

type SecretBox = sbirds.auth.SecretBox
