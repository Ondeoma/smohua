package smohua.db

import doobie.*
import doobie.implicits.*

/** SQL の部品（doobie の Fragments を List で使うため）。 */
object Sql {

  /** set a = 1, b = 2（空のリストは渡さないこと）。 */
  def set(
    fs: List[Fragment]
  ): Fragment = fr"set" ++ fs.reduce(_ ++ fr"," ++ _)

  /** where a and b（空なら何も付けない）。 */
  def whereAnd(
    fs: List[Fragment]
  ): Fragment =
    if fs.isEmpty then Fragment.empty else fr"where" ++ fs.map(f => fr"(" ++ f ++ fr")").reduce(_ ++ fr"and" ++ _)

  /** like のワイルドカード（% _）とエスケープの文字（\）を、ただの文字として扱うようにする。 */
  def likeEscape(
    s: String
  ): String = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

  /** ilike で「s を含む」を探す値。 */
  def containing(
    s: String
  ): String = "%" + likeEscape(s) + "%"
}
