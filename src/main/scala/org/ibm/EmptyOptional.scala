package org.ibm
import scala.deriving.Mirror
import scala.compiletime.{erasedValue, summonInline}
import org.pwharned.database.hkd._
// Type class to create empty Optional instances
trait EmptyOptional[T[_[_]]]:
  def empty: Optional[T]

object EmptyOptional:
  inline def derived[T[_[_]]](using
      m: Mirror.ProductOf[T[OptionalField]]
  ): EmptyOptional[T] =
    new EmptyOptional[T]:
      def empty: Optional[T] =
        m.fromProduct(createNoneTuple[m.MirroredElemTypes])

  // Recursively create a tuple of Nones matching the product structure
  inline def createNoneTuple[T <: Tuple]: Tuple =
    inline erasedValue[T] match
      case _: EmptyTuple => EmptyTuple
      case _: (t *: ts)  => None *: createNoneTuple[ts]

// Extension method for easy copying
extension [T[_[_]]](opt: Optional[T])
  inline def update[F[_]](f: Optional[T] => Optional[T]): Optional[T] = f(opt)
