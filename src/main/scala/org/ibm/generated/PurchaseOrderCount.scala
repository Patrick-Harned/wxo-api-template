package org.ibm
import org.pwharned.json.JsonDeserializer
import org.pwharned.database.hkd._
import org.pwharned.database.derive.SelectStatement
import sttp.tapir._
import org.ibm.EmptyOptional
import org.pwharned.database.derive.SqlSelect
import scala.compiletime._
import scala.deriving.Mirror
case class PurchaseOrderCount[F[_]](
    SK_SUPPLIER_NUMBER: F[Int],
    SUPPLIER_UNIQUE_NUMBER: F[String],
    SUPPLIER_DUNNS_ENTITY_NUMBER: F[String],
    SUPPLIER_DUNNS_GLOBAL_NUMBER: F[String],
    SUPPLIER_NAME1: F[String],
    NUMBER_OF_PURCHASE_ORDERS: F[Int],
    YEAR: F[Int],
    MONTH: F[Int],
    START_DATE: F[String],
    END_DATE: F[String]
)

object PurchaseOrderCount:
  given EmptyOptional[PurchaseOrderCount] =
    EmptyOptional.derived

  given EndpointInput[Optional[PurchaseOrderCount]] = {
    query[Option[Int]]("SK_SUPPLIER_NUMBER")
      .description("Surrogate key for the supplier")
      .and(
        query[Option[String]]("SUPPLIER_UNIQUE_NUMBER")
          .description("Unique supplier identifier (VLC/CAAPS/SM ID)")
      )
      .and(
        query[Option[String]]("SUPPLIER_DUNNS_ENTITY_NUMBER")
          .description("DUNS entity number for the supplier")
      )
      .and(
        query[Option[String]]("SUPPLIER_DUNNS_GLOBAL_NUMBER")
          .description("DUNS global ultimate number for the supplier")
      )
      .and(
        query[Option[String]]("SUPPLIER_NAME1")
          .description(
            "Supplier name. You must use an exact match. If the user provides a supplier name, you should first search the DIM_S2P_SUPPLIER table which supports fuzzy match to determine the correct name before attempting a search here. "
          )
      )
      .and(query[Option[Int]]("NUMBER_OF_PURCHASE_ORDERS"))
      .and(query[Option[Int]]("YEAR"))
      .and(query[Option[Int]]("MONTH"))
      .and(
        query[Option[String]]("START_DATE").description(
          "ISO 8601 extended date format to indicate the start date"
        )
      )
      .and(
        query[Option[String]]("END_DATE")
          .description("ISO 8601 extended date format to indicate the end date")
      )
      .map {
        case (
              skSupplier,
              supplierUniqueNumber,
              dunsEntity,
              dunsGlobal,
              supplierName,
              numberOfPurchaseOrders,
              year,
              month,
              startDate,
              endDate
            ) =>
          val empty: Optional[PurchaseOrderCount] =
            summon[EmptyOptional[PurchaseOrderCount]].empty
          empty.copy[OptionalField](
            SK_SUPPLIER_NUMBER = skSupplier,
            SUPPLIER_UNIQUE_NUMBER = supplierUniqueNumber,
            SUPPLIER_DUNNS_ENTITY_NUMBER = dunsEntity,
            SUPPLIER_DUNNS_GLOBAL_NUMBER = dunsGlobal,
            SUPPLIER_NAME1 = supplierName,
            NUMBER_OF_PURCHASE_ORDERS = numberOfPurchaseOrders,
            YEAR = year,
            MONTH = month,
            START_DATE = startDate,
            END_DATE = endDate
          )
      } { query =>
        (
          query.SK_SUPPLIER_NUMBER,
          query.SUPPLIER_UNIQUE_NUMBER,
          query.SUPPLIER_DUNNS_ENTITY_NUMBER,
          query.SUPPLIER_DUNNS_GLOBAL_NUMBER,
          query.SUPPLIER_NAME1,
          query.NUMBER_OF_PURCHASE_ORDERS,
          query.YEAR,
          query.MONTH,
          query.START_DATE,
          query.END_DATE
        )
      }
  }
  given SqlSelect[Optional[PurchaseOrderCount]] with
    override def select: String =
      summon[SelectStatement[Optional[PurchaseOrderCount]]].select()
    override def selectWhere(
        obj: Optional[PurchaseOrderCount]
    ): String = {
      val m = summon[Mirror.ProductOf[Optional[PurchaseOrderCount]]]
      val names: List[String] =
        constValueTuple[m.MirroredElemLabels].productIterator.toList
          .map(_.toString)

      val values = obj.productIterator.toList
      val where  =
        names
          .zip(values)
          .collect {
            case (name, value) if name == "START_DATE" & value != None =>
              s"$name > ?"
            case (name, value) if name == "END_DATE" & value != None =>
              s"$name < ?"
            case (name, value) if value != None => s" $name = ? "
          }
          .mkString(" and ")

      val sql = s"$select where $where  "
      sql

    }
    override def selectWhere: String = select

  given SelectStatement[Optional[PurchaseOrderCount]] with
    def select(): String =
      summon[SelectStatement[Persisted[PurchaseOrderCount]]].select()

  given JsonDeserializer[Persisted[PurchaseOrderCount]] =
    JsonDeserializer.derived

  given SelectStatement[Persisted[PurchaseOrderCount]] with
    def select(): String = """
    SELECT * FROM (
WITH agg AS (
SELECT
    ds.SK_SUPPLIER_NUMBER,
    ds.SUPPLIER_UNIQUE_NUMBER,
    ds.SUPPLIER_NAME1,
    MAX(tp.DATE) as END_DATE,
    MIN(tp.DATE) as START_DATE,
    YEAR(MAX(tp.DATE)) as YEAR,
    MONTH(MAX(tp.DATE)) as MONTH,
    COUNT(DISTINCT fpo.SK_S2P_SUPPLIER_PURCHASE_ORDER) AS NUMBER_OF_PURCHASE_ORDERS
FROM
    EPM_PROCUREMENT.DIM_S2P_SUPPLIER AS ds
JOIN
    EPM_PROCUREMENT.DIM_S2P_SUPPLIER_PURCHASE_ORDER AS dpo
    ON ds.SK_SUPPLIER_NUMBER = dpo.SK_S2P_PROCUREMENT_SUPPLIER
JOIN 
    EPM_VIEW_PROCUREMENT.FACT_S2P_SUPPLIER_PURCHASE_ORDER fpo 
    ON fpo.SK_S2P_SUPPLIER_PURCHASE_ORDER = dpo.SK_S2P_SUPPLIER_PURCHASE_ORDER
JOIN 
    EPM.DIM_TIME_PERIOD_GREGORIAN tp 
    ON tp.SK_DAY = dpo.SK_PO_CREATE_DATE
GROUP BY
    ds.SK_SUPPLIER_NUMBER,
    ds.SUPPLIER_UNIQUE_NUMBER,
    ds.SUPPLIER_NAME1,
    tp.SK_MONTH,
    tp.YEAR_NUMBER
)
SELECT 
    agg.SK_SUPPLIER_NUMBER,
    agg.SUPPLIER_UNIQUE_NUMBER,
    ds.SUPPLIER_DUNNS_ENTITY_NUMBER,
    ds.SUPPLIER_DUNNS_GLOBAL_NUMBER,
    agg.SUPPLIER_NAME1,
    agg.NUMBER_OF_PURCHASE_ORDERS,
    agg.START_DATE,
    agg.END_DATE,
    agg.YEAR,
    agg.MONTH
FROM agg
JOIN EPM_PROCUREMENT.DIM_S2P_SUPPLIER ds 
    ON ds.SK_SUPPLIER_NUMBER = agg.SK_SUPPLIER_NUMBER
)
"""
