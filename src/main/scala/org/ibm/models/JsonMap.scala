package org.ibm.models
import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonValueCodec,
  readFromString,
  writeToString
}
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import sttp.tapir.Schema
import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._
import scala.annotation.switch
import org.pwharned.database.hkd._
type JsonMap = Map[String, Any]

object JsonMap {

  given anyCodec: JsonValueCodec[Any] = new JsonValueCodec[Any] {
    def decodeValue(in: JsonReader, default: Any): Any = {
      in.nextToken() match {
        case 'n' =>
          if (
            in.nextByte() == 'u' && in.nextByte() == 'l' && in.nextByte() == 'l'
          ) null
          else in.decodeError("expected null")

        case '"' =>
          in.rollbackToken()
          in.readString(null)

        case 't' =>
          if (
            in.nextByte() == 'r' && in.nextByte() == 'u' && in.nextByte() == 'e'
          ) true
          else in.decodeError("expected true")

        case 'f' =>
          if (
            in.nextByte() == 'a' && in.nextByte() == 'l' && in
              .nextByte() == 's' && in.nextByte() == 'e'
          ) false
          else in.decodeError("expected false")

        case '[' =>
          if (in.isNextToken(']')) Nil
          else {
            in.rollbackToken()
            var list = List.empty[Any]
            while ({
              list = decodeValue(in, null) :: list
              in.isNextToken(',')
            }) ()
            if (!in.isCurrentToken(']')) in.arrayEndOrCommaError()
            list.reverse
          }

        case '{' =>
          if (in.isNextToken('}')) Map.empty[String, Any]
          else {
            in.rollbackToken()
            var map = Map.empty[String, Any]
            while ({
              val key = in.readKeyAsString()
              map = map.updated(key, decodeValue(in, null))
              in.isNextToken(',')
            }) ()
            if (!in.isCurrentToken('}')) in.objectEndOrCommaError()
            map
          }

        case c @ ('-' | '0' | '1' | '2' | '3' | '4' | '5' | '6' | '7' | '8' |
            '9') =>
          in.rollbackToken()
          in.setMark()
          var isDouble = false
          while ({
            val ch = in.nextToken()
            if (ch == '.' || ch == 'e' || ch == 'E') isDouble = true
            ch >= '0' && ch <= '9' || ch == '.' || ch == 'e' || ch == 'E' || ch == '+' || ch == '-'
          }) ()
          in.rollbackToken()
          in.rollbackToMark()
          if (isDouble) in.readDouble()
          else {
            val x = in.readLong()
            if (x.isValidInt) x.toInt else x
          }

        case _ =>
          in.decodeError("expected JSON value")
      }
    }

    def encodeValue(x: Any, out: JsonWriter): Unit = x match {
      case null         => out.writeNull()
      case n: None.type => out.writeNull()
      case o: Option[_] =>
        o match
          case Some(value) => encodeValue(value, out)
          case None        => out.writeNull()

      case v: String     => out.writeVal(v)
      case v: Int        => out.writeVal(v)
      case v: Long       => out.writeVal(v)
      case v: Double     => out.writeVal(v)
      case v: Float      => out.writeVal(v)
      case v: Boolean    => out.writeVal(v)
      case v: BigInt     => out.writeVal(v.bigInteger)
      case v: BigDecimal => out.writeVal(v.bigDecimal)
      case v: Map[_, _]  =>
        out.writeObjectStart()
        var first = true
        v.foreach { case (k, value) =>
          first = false
          out.writeKey(k.toString)
          encodeValue(value, out)
        }
        out.writeObjectEnd()
      case v: Iterable[_] =>
        out.writeArrayStart()
        var first = true
        v.foreach { item =>
          first = false
          encodeValue(item, out)
        }
        out.writeArrayEnd()
      case v: Array[_] =>
        out.writeArrayStart()
        var first = true
        v.foreach { item =>
          first = false
          encodeValue(item, out)
        }
        out.writeArrayEnd()
      case _ => out.writeVal(x.toString)
    }

    def nullValue: Any = null
  }

  // Now define codec for Map[String, Any] using the Any codec
  given jsonMapCodec: JsonValueCodec[JsonMap] = new JsonValueCodec[JsonMap] {
    def decodeValue(in: JsonReader, default: JsonMap): JsonMap = {
      if (in.isNextToken('{')) {
        var map = Map.empty[String, Any]
        if (!in.isNextToken('}')) {
          in.rollbackToken()
          while ({
            val key = in.readKeyAsString()
            map = map.updated(key, anyCodec.decodeValue(in, null))
            in.isNextToken(',')
          }) ()
          if (!in.isCurrentToken('}')) in.objectEndOrCommaError()
        }
        map
      } else in.readNullOrError(default, "expected '{' or null")
    }

    def encodeValue(x: JsonMap, out: JsonWriter): Unit = {
      if (x eq null) out.writeNull()
      else anyCodec.encodeValue(x, out)
    }

    def nullValue: JsonMap = null
  }
}
