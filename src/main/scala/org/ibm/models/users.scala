package org.ibm.models
import java.time.LocalDateTime
import scala.math.BigDecimal
import org.pwharned.database.hkd._
import org.pwharned.json.JsonDeserializer
import sttp.tapir._
import org.ibm.EmptyOptional
import cats.mtl.Local
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
given PrimaryKeySchema: Schema[PrimaryKey[Int]] =
  Schema.schemaForBigInt
    .as[PrimaryKey[Int]]
    .description("Autoincrementing integer primary key")

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

// Table: pix_keys
case class pix_keys[F[_]](
    pix_key_id: F[PrimaryKey[Int]],
    user_id: F[Int],
    key_type: F[String],
    key_value: F[String],
    status: F[Default[String]],
    is_primary: F[Default[Boolean]],
    created_at: F[Default[String]],
    updated_at: F[Default[String]]
)

// Table: transactions
case class transactions[F[_]](
    transaction_id: F[PrimaryKey[Int]],
    transaction_uuid: F[String],
    sender_user_id: F[Int],
    receiver_user_id: F[Int],
    amount: F[BigDecimal],
    transaction_type: F[String],
    pix_key_used: F[Nullable[String]],
    description: F[String],
    status: F[Default[String]],
    created_at: F[Default[String]],
    completed_at: F[Nullable[String]]
)
given Conversion[Option[String], Option[LocalDateTime]] with
  def apply(x: Option[String]): Option[LocalDateTime] = x.map(y => LocalDateTime.parse(y))
object pix_keys:
  given Schema[Persisted[pix_keys]]     = Schema.derived
  given JsonDeserializer[New[pix_keys]] = JsonDeserializer.derived

  given JsonDeserializer[Persisted[pix_keys]] = JsonDeserializer.derived
  given EmptyOptional[pix_keys]               = EmptyOptional.derived
  given EndpointInput[Optional[pix_keys]] =
    query[Option[Int]]("pix_key_id")
      .description("Auto incrementing primary key that uniquely identifies a PIX key")
      .and(
        query[Option[Int]]("user_id")
          .description("Foreign key to the user who owns this PIX key")
      )
      .and(
        query[Option[String]]("key_type")
          .description("Type of PIX key: email, phone, cpf, cnpj, or random")
      )
      .and(
        query[Option[String]]("key_value")
          .description("The actual PIX key value")
      )
      .and(
        query[Option[String]]("status")
          .description("Status of the PIX key: active, inactive, or pending")
      )
      .and(
        query[Option[Boolean]]("is_primary")
          .description("Whether this is the primary PIX key for the user")
      )
      .map {
        case (
              pixKeyId,
              userId,
              keyType,
              keyValue,
              status,
              isPrimary
            ) =>
          val empty: Optional[pix_keys] =
            summon[EmptyOptional[pix_keys]].empty
          empty.copy[OptionalField](
            pixKeyId,
            userId,
            keyType,
            keyValue,
            status,
            isPrimary
          )
      } { query =>
        (
          query.pix_key_id,
          query.user_id,
          query.key_type,
          query.key_value,
          query.status,
          query.is_primary
        )
      }

object transactions:
  given Schema[Persisted[transactions]]     = Schema.derived
  given JsonDeserializer[New[transactions]] = JsonDeserializer.derived

  given JsonDeserializer[Persisted[transactions]] = JsonDeserializer.derived
  given EmptyOptional[transactions]               = EmptyOptional.derived
  given EndpointInput[Optional[transactions]] =
    query[Option[Int]]("transaction_id")
      .description("Auto incrementing primary key that uniquely identifies a transaction")
      .and(
        query[Option[String]]("transaction_uuid")
          .description("UUID for external reference to this transaction")
      )
      .and(
        query[Option[Int]]("sender_user_id")
          .description("Foreign key to the user sending money")
      )
      .and(
        query[Option[Int]]("receiver_user_id")
          .description("Foreign key to the user receiving money")
      )
      .and(
        query[Option[BigDecimal]]("amount")
          .description("Amount of money transferred")
      )
      .and(
        query[Option[String]]("transaction_type")
          .description("Type of transaction: pix_key, qr_code, or copy_paste")
      )
      .and(
        query[Option[String]]("pix_key_used")
          .description("The PIX key used for this transaction")
      )
      .and(
        query[Option[String]]("description")
          .description("Optional description/note for the transaction")
      )
      .and(
        query[Option[String]]("status")
          .description("Status: completed, cancelled, pending, or failed")
      )
      .map {
        case (
              transactionId,
              transactionUuid,
              senderUserId,
              receiverUserId,
              amount,
              transactionType,
              pixKeyUsed,
              description,
              status
            ) =>
          val empty: Optional[transactions] =
            summon[EmptyOptional[transactions]].empty
          empty.copy[OptionalField](
            transactionId,
            transactionUuid,
            senderUserId,
            receiverUserId,
            amount,
            transactionType,
            pixKeyUsed,
            description,
            status
          )
      } { query =>
        (
          query.transaction_id,
          query.transaction_uuid,
          query.sender_user_id,
          query.receiver_user_id,
          query.amount,
          query.transaction_type,
          query.pix_key_used,
          query.description,
          query.status
        )
      }
