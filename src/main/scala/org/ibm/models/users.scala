package org.ibm.models
import java.time.LocalDateTime
import scala.math.BigDecimal
import org.pwharned.database.hkd._
import org.pwharned.json.JsonDeserializer
import sttp.tapir._
import org.ibm.EmptyOptional
// Table: users
case class users[F[_]](
    user_id: F[PrimaryKey[Int]], // AUTOINCREMENT, so optional on insert
    cpf_cnpj: F[String],
    name: F[String],
    account_number: F[String],
    branch_number: F[String],
    account_type: F[String],
    balance: F[BigDecimal],
    created_at: F[Default[String]],
    updated_at: F[Default[String]]
)

object users:
  given Schema[Persisted[users]]     = Schema.derived
  given JsonDeserializer[New[users]] = JsonDeserializer.derived

  given JsonDeserializer[Persisted[users]] = JsonDeserializer.derived
  given EmptyOptional[users]               = EmptyOptional.derived
  given EndpointInput[Optional[users]] =
    query[Option[Int]]("user_id")
      .description("Auto incrementing primary key that uniquely identifies a user")
      .and(
        query[Option[String]]("cpf_cnpj")
          .description("Brazilian tax id. ")
      )
      .and(
        query[Option[String]]("name")
          .description("The user's name.")
      )
      .map {
        case (
              userId,
              taxId,
              name
            ) =>
          val empty: Optional[users] =
            summon[EmptyOptional[users]].empty
          empty.copy[OptionalField](
            userId,
            taxId,
            name
          )
      } { query =>
        (
          query.user_id,
          query.cpf_cnpj,
          query.name
        )
      }

case class pix_keys(
    pix_key_id: Option[Int] = None,
    user_id: Int,
    key_type: String,
    key_value: String,
    status: String = "active",
    is_primary: Boolean = false,
    created_at: LocalDateTime = LocalDateTime.now(),
    updated_at: LocalDateTime = LocalDateTime.now()
)

// Table: transactions
case class transactions(
    transaction_id: Option[Int] = None,
    transaction_uuid: String,
    sender_user_id: Int,
    receiver_user_id: Int,
    amount: BigDecimal,
    transaction_type: String,
    pix_key_used: Option[String] = None,
    description: Option[String] = None,
    status: String = "completed",
    created_at: LocalDateTime = LocalDateTime.now(),
    completed_at: Option[LocalDateTime] = None
)
