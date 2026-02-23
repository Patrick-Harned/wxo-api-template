package org.ibm

import org.pwharned.database.hkd._
import org.pwharned.json.JsonString
import org.pwharned.parse.{Parse, ParseError, Parser, Primitives}
import org.pwharned.parse.Parse.*

import scala.compiletime.*
import scala.deriving.*
import scala.util.Try

trait MapDeserializer[T]:
  def deserialize(m: Map[String, String]): Either[ParseError, T]

object MapDeserializer:

  trait QueryFieldDeserializer[A]:
    def parser: Parser[A]

  object QueryFieldDeserializer:
    given QueryFieldDeserializer[String] with
      def parser: Parser[String] = Primitives.stringNoAmpersand

    given QueryFieldDeserializer[Int] with
      def parser: Parser[Int] = Primitives.intParser
  given QueryFieldDeserializer[Long] with
    def parser: Parser[Long] = Primitives.longParser

  given QueryFieldDeserializer[Float] with
    def parser: Parser[Float] = Primitives.floatParser

  given QueryFieldDeserializer[java.util.UUID] with
    def parser: Parser[java.util.UUID] = input =>
      Primitives.stringNoAmpersand(input) match {
        case Left(err)          => Left(err)
        case Right((raw, rest)) =>
          Try(java.util.UUID.fromString(raw)).toEither.left
            .map { ex =>
              ParseError(
                0,
                raw,
                s"'$raw' is not a valid UUID: ${ex.getMessage}"
              )
            }
            .map(uuid => (uuid, rest))
      }
  given QueryFieldDeserializer[java.time.LocalDate] with
    def parser: Parser[java.time.LocalDate] = input =>
      Primitives.stringNoAmpersand(input) match {
        case Left(err)          => Left(err)
        case Right((raw, rest)) =>
          Try(java.time.LocalDate.parse(raw)).toEither.left
            .map { ex =>
              ParseError(
                0,
                raw,
                s"'$raw' is not a valid LocalDate: ${ex.getMessage}"
              )
            }
            .map(uuid => (uuid, rest))
      }

  given QueryFieldDeserializer[scala.math.BigDecimal] with
    def parser: Parser[scala.math.BigDecimal] = input =>
      Primitives.stringNoAmpersand(input) match {
        case Left(err)          => Left(err)
        case Right((raw, rest)) =>
          Try(scala.math.BigDecimal(raw)).toEither.left
            .map { ex =>
              ParseError(
                0,
                raw,
                s"'$raw' is not a valid Decimal: ${ex.getMessage}"
              )
            }
            .map(uuid => (uuid, rest))
      }

  given QueryFieldDeserializer[java.time.Instant] with
    def parser: Parser[java.time.Instant] = input =>
      Primitives.stringNoAmpersand(input) match {
        case Left(err)          => Left(err)
        case Right((raw, rest)) =>
          Try(java.time.Instant.parse(raw)).toEither.left
            .map { ex =>
              ParseError(
                0,
                raw,
                s"'$raw' is not a valid Instant: ${ex.getMessage}"
              )
            }
            .map(inst => (inst, rest))
      }

  given QueryFieldDeserializer[Boolean] with
    def parser: Parser[Boolean] = Primitives.boolParser

  // Wrap a parsed T into PrimaryKey[T]
  given [T](using
      underlying: QueryFieldDeserializer[T]
  ): QueryFieldDeserializer[PrimaryKey[T]] with
    def parser: Parser[PrimaryKey[T]] =
      underlying.parser.map(PrimaryKey(_))

  given [T](using
      underlying: QueryFieldDeserializer[T]
  ): QueryFieldDeserializer[GeneratedPrimaryKey[T]] with
    def parser: Parser[GeneratedPrimaryKey[T]] =
      underlying.parser.map(GeneratedPrimaryKey(_))

  given [T](using
      underlying: QueryFieldDeserializer[T]
  ): QueryFieldDeserializer[Nullable[T]] with
    def parser: Parser[Nullable[T]] =
      underlying.parser.map(Nullable(_))

  // Option[T]
  given [T](using
      underlying: QueryFieldDeserializer[T]
  ): QueryFieldDeserializer[Option[T]] with
    def parser: Parser[Option[T]] = input =>
      underlying.parser(input) match {
        case Right((value, rest)) => Right((Some(value), rest))
        case Left(err)            => Left(err)
      }

  // Vector[T]
  given vecParser[T](using
      underlying: QueryFieldDeserializer[T]
  ): QueryFieldDeserializer[Vector[T]] with
    def parser: Parser[Vector[T]] = input =>
      val inner = input.trim.stripPrefix("[").stripSuffix("]").trim
      if inner.isEmpty then Right((Vector.empty, ""))
      else {
        val tokens = inner.split(",").toList.map(_.trim)
        tokens.foldLeft[Either[ParseError, (Vector[T], String)]](
          Right((Vector.empty, ""))
        ) { case (accE, tok) =>
          for {
            (xs, _)  <- accE
            (x, rem) <- underlying.parser(tok)
          } yield (xs :+ x, "")
        }
      }

  // List[T]
  given listParser[T](using
      underlying: QueryFieldDeserializer[T]
  ): QueryFieldDeserializer[List[T]] with
    def parser: Parser[List[T]] = input =>
      val inner = input.trim.stripPrefix("[").stripSuffix("]").trim
      if inner.isEmpty then Right((Nil, ""))
      else {
        val tokens = inner.split(",").toList.map(_.trim)
        tokens.foldLeft[Either[ParseError, (List[T], String)]](
          Right((Nil, ""))
        ) { case (accE, tok) =>
          for {
            (xs, _)  <- accE
            (x, rem) <- underlying.parser(tok)
          } yield (xs :+ x, "")
        }
      }

  // Selects the correct parser based on field type.
  inline def fieldParser[h](
      key: String,
      valueOpt: Option[String]
  ): Either[ParseError, h] =
    inline erasedValue[h] match {
      case _: Option[t] =>
        valueOpt match {
          case Some(v) =>
            summonInline[QueryFieldDeserializer[t]]
              .parser(v)
              .map(_._1)
              .map(Some(_).asInstanceOf[h])
          case None => Right(None.asInstanceOf[h])
        }
      case _ =>
        valueOpt match {
          case Some(v) =>
            summonInline[QueryFieldDeserializer[h]].parser(v).map(_._1)
          case None => Left(ParseError(0, "", s"Missing required field: $key"))
        }
    }
  inline def buildParsers[Elems <: Tuple](
      names: List[String]
  ): List[(String, Option[String] => Either[ParseError, Any])] =
    inline erasedValue[Elems] match {
      case _: EmptyTuple => Nil
      case _: (h *: t)   =>
        val name                                                = names.head
        val parserFn: Option[String] => Either[ParseError, Any] =
          valueOpt => fieldParser[h](name, valueOpt)
        (name, parserFn) :: buildParsers[t](names.tail)
    }

  inline given derived[T <: Product](using
      m: Mirror.ProductOf[T]
  ): MapDeserializer[T] =
    val fieldNames: List[String] =
      constValueTuple[m.MirroredElemLabels].toIArray.toList.map(_.toString)
    val parsers = buildParsers[m.MirroredElemTypes](fieldNames)
    new MapDeserializer[T]:
      def deserialize(map: Map[String, String]): Either[ParseError, T] =
        val parsedElems: List[Either[ParseError, Any]] = parsers.map {
          case (name, parser) =>
            parser(map.get(name))
        }

        val errors = parsedElems.collect { case Left(err) => err }
        if errors.nonEmpty then Left(errors.head) // or merge errors
        else
          val values = parsedElems.collect { case Right(v) => v }
          Right(
            m.fromTuple(
              Tuple.fromArray(values.toArray).asInstanceOf[m.MirroredElemTypes]
            )
          )
