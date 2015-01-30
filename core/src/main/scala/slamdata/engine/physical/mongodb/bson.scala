package slamdata.engine.physical.mongodb

import slamdata.engine.{Data, Error}
import slamdata.engine.analysis.fixplate.{Term}
import slamdata.engine.fp._
import slamdata.engine.javascript._

import org.threeten.bp.{Instant, ZoneOffset}

import com.mongodb._
import org.bson.types

import collection.immutable.ListMap
import scalaz._
import scalaz.syntax.traverse._
import scalaz.std.list._
import scalaz.std.map._

/**
 * A type-safe ADT for Mongo's native data format. Note that this representation
 * is not suitable for efficiently storing large quantities of data.
 */
sealed trait Bson {
  def repr: AnyRef
}

object Bson {
  trait ConversionError extends Error
  case class InvalidObjectIdError(data: Data.Id) extends Error {
    def message = "Not a valid MongoDB ObjectId: " + data.value
  }
  case class BsonConversionError(bson: Bson) extends Error {
    def message = "BSON has no corresponding Data representation: " + bson
  }

  def fromData(data: Data): InvalidObjectIdError \/ Bson = {
    data match {
      case Data.Null => \/ right (Bson.Null)
      
      case Data.Str(value) => \/ right (Bson.Text(value))

      case Data.True => \/ right (Bson.Bool(true))
      case Data.False => \/ right (Bson.Bool(false))

      case Data.Dec(value) => \/ right (Bson.Dec(value.toDouble))
      case Data.Int(value) => \/ right (Bson.Int64(value.toLong))

      case Data.Obj(value) => 
        type MapF[X] = Map[String, X]
        type Right[X] = InvalidObjectIdError \/ X

        val map: MapF[InvalidObjectIdError \/ Bson] = value.mapValues(fromData _)

        Traverse[MapF].sequence[Right, Bson](map).map((x: MapF[Bson]) => Bson.Doc(x.toList.toListMap))

      case Data.Arr(value) => value.map(fromData _).sequenceU.map(Bson.Arr.apply _)

      case Data.Set(value) => value.map(fromData _).sequenceU.map(Bson.Arr.apply _)

      case Data.Timestamp(value) => \/ right (Bson.Date(value))

      case d @ Data.Date(_) => fromData(slamdata.engine.std.DateLib.startOfDay(d))

      case Data.Time(value) => {
        def pad2(x: Int) = if (x < 10) "0" + x else x.toString
        def pad3(x: Int) = if (x < 10) "00" + x else if (x < 100) "0" + x else x.toString
        \/ right (Bson.Text(pad2(value.getHour()) + ":" + pad2(value.getMinute()) + ":" + pad2(value.getSecond()) + "." + pad3(value.getNano()/1000000)))
      }
      case Data.Interval(value) => \/ right (Bson.Dec(value.getSeconds*1000 + value.getNano*1e-6))

      case Data.Binary(value) => \/ right (Bson.Binary(value.toArray))

      case Data.Id(value) => ObjectId.parse(value)
    }
  }
  
  def toData(bson: Bson): BsonConversionError \/ Data = bson match {
    case Bson.Null              => \/-(Data.Null)
    case Bson.Text(str)         => \/-(Data.Str(str))
    case Bson.Bool(true)        => \/-(Data.True)
    case Bson.Bool(false)       => \/-(Data.False)
    case Bson.Dec(value)        => \/-(Data.Dec(value))
    case Bson.Int32(value)      => \/-(Data.Int(value))
    case Bson.Int64(value)      => \/-(Data.Int(value))
    case Bson.Doc(value)        => value.toList.map { case (k, v) => toData(v).map(k -> _) }.sequenceU.map(_.toListMap).map(Data.Obj.apply)
    case Bson.Arr(value)        => value.map(toData).sequenceU.map(Data.Arr.apply)
    case Bson.Date(value)       => \/-(Data.Timestamp(value))
    case Bson.Binary(value)     => \/-(Data.Binary(value.toList))
    case oid @ Bson.ObjectId(_) => \/-(Data.Id(oid.str))

    // NB: several types have no corresponding Data representation, including
    // MinKey, MaxKey, Regex, Timestamp, JavaScript, and JavaScriptScope
    case _ => -\/(BsonConversionError(bson))
  }

  def fromRepr(obj: DBObject): Bson = {
    import collection.JavaConversions._

    def loop(v: AnyRef): Bson = v match {
      case null                       => Null
      case x: String                  => Text(x)
      case x: java.lang.Boolean       => Bool(x)
      case x: java.lang.Integer       => Int32(x)
      case x: java.lang.Long          => Int64(x)
      case x: java.lang.Double        => Dec(x)
      case list: BasicDBList          => Arr(list.map(loop).toList)
      case obj: DBObject              => Doc(obj.keySet.toList.map(k => k -> loop(obj.get(k))).toListMap)
      case x: java.util.Date          => Date(Instant.ofEpochMilli(x.getTime))
      case x: types.ObjectId          => ObjectId(x.toByteArray.toList)
      case x: types.Binary            => Binary(x.getData)
      case _: types.MinKey            => MinKey
      case _: types.MaxKey            => MaxKey
      case x: types.Symbol            => Symbol(x.getSymbol)
      case x: types.BSONTimestamp     => Timestamp(Instant.ofEpochSecond(x.getTime), x.getInc)
      case x: java.util.regex.Pattern => Regex(x.pattern)
      case x: Array[Byte]             => Binary(x)

      // NB: the remaining types are not easily translated back to Bson, 
      // and we don't expect them to appear anyway.
      // JavaScript/JavaScriptScope: would require parsing a string to our Js type
    }
    
    loop(obj)
  }

  case class Dec(value: Double) extends Bson {
    def repr = value: java.lang.Double
  }
  case class Text(value: String) extends Bson {
    def repr = value
  }
  case class Binary(value: Array[Byte]) extends Bson {
    def repr = value
  }
  case class Doc(value: ListMap[String, Bson]) extends Bson {
    def repr: DBObject = value.foldLeft(new BasicDBObject) {
      case (obj, (name, value)) =>
        obj.put(name, value.repr)
        obj
    }
  }
  case class Arr(value: List[Bson]) extends Bson {
    def repr = value.foldLeft(new BasicDBList) {
      case (array, value) =>
        array.add(value.repr)
        array
    }
  }
  case class ObjectId(value: List[Byte]) extends Bson {
    def repr = new types.ObjectId(value.toArray)

    def str = value.map { b =>
      val bs = Integer.toHexString(b.toInt & 0xff)
      if (bs.length == 1) ("0" + bs) else bs
    }.mkString
  }
  object ObjectId {
    def parse(str: String): InvalidObjectIdError \/ ObjectId = {
      val Pattern = "(?:[0-9a-fA-F][0-9a-fA-F]){12}".r
      def parse(suffix: String): List[Byte] = suffix match {
        case "" => Nil
        case _  => Integer.parseInt(suffix.substring(0, 2), 16).toByte :: parse(suffix.substring(2))
      }
      str match {
        case Pattern() =>  \/-(Bson.ObjectId(parse(str)))
        case _         => -\/ (InvalidObjectIdError(Data.Id(str)))
      }
    }
  }
  case class Bool(value: Boolean) extends Bson {
    def repr = value: java.lang.Boolean
  }
  case class Date(value: Instant) extends Bson {
    def repr = new java.util.Date(value.toEpochMilli)
  }
  case object Null extends Bson {
    def repr = null
  }
  case class Regex(value: String) extends Bson {
    def repr = java.util.regex.Pattern.compile(value)
  }
  case class JavaScript(value: Js) extends Bson {
    def repr = value.render(2)
  }
  case class JavaScriptScope(code: Js, doc: Doc) extends Bson {
    def repr = new types.CodeWScope(code.render(2), doc.repr)
  }
  case class Symbol(value: String) extends Bson {
    def repr = new types.Symbol(value)
  }
  case class Int32(value: Int) extends Bson {
    def repr = value: java.lang.Integer
  }
  case class Int64(value: Long) extends Bson {
    def repr = value: java.lang.Long
  }
  case class Timestamp(instant: Instant, ordinal: Int) extends Bson {
    def repr = new types.BSONTimestamp((instant.toEpochMilli / 1000).toInt, ordinal)
  }
  case object MinKey extends Bson {
    def repr = new types.MinKey
  }
  case object MaxKey extends Bson {
    def repr = new types.MaxKey
  }
}

sealed trait BsonType {
  def ordinal: Int
}

object BsonType {
  private[BsonType] abstract class AbstractType(val ordinal: Int) extends BsonType
  case object Dec extends AbstractType(1)
  case object Text extends AbstractType(2)
  case object Doc extends AbstractType(3)
  case object Arr extends AbstractType(4)
  case object Binary extends AbstractType(5)
  case object ObjectId extends AbstractType(7)
  case object Bool extends AbstractType(8)
  case object Date extends AbstractType(9)
  case object Null extends AbstractType(10)
  case object Regex extends AbstractType(11)
  case object JavaScript extends AbstractType(13)
  case object JavaScriptScope extends AbstractType(15)
  case object Symbol extends AbstractType(14)
  case object Int32 extends AbstractType(16)
  case object Int64 extends AbstractType(18)
  case object Timestamp extends AbstractType(17)
  case object MinKey extends AbstractType(255)
  case object MaxKey extends AbstractType(127)
}

sealed trait BsonField {
  def asText  : String
  def asField : String = "$" + asText
  def asVar   : String = "$$" + asText

  def bson      = Bson.Text(asText)
  def bsonField = Bson.Text(asField)
  def bsonVar   = Bson.Text(asVar)

  import BsonField._

  def \ (that: BsonField): BsonField = (this, that) match {
    case (Path(x), Path(y)) => Path(NonEmptyList.nel(x.head, x.tail ++ y.list))
    case (Path(x), y: Leaf) => Path(NonEmptyList.nel(x.head, x.tail :+ y))
    case (y: Leaf, Path(x)) => Path(NonEmptyList.nel(y, x.list))
    case (x: Leaf, y: Leaf) => Path(NonEmptyList.nels(x, y))
  }

  def \\ (tail: List[BsonField]): BsonField = if (tail.isEmpty) this else this match {
    case Path(p) => Path(NonEmptyList.nel(p.head, p.tail ::: tail.flatMap(_.flatten)))
    case l: Leaf => Path(NonEmptyList.nel(l, tail.flatMap(_.flatten)))
  }

  def flatten: List[Leaf]

  def parent: Option[BsonField] = BsonField(flatten.reverse.drop(1).reverse)

  def startsWith(that: BsonField) = this.flatten.startsWith(that.flatten)

  def toJs: JsMacro =
    this.flatten.foldLeft(JsMacro(identity))((acc, leaf) =>
      leaf match {
        case Name(v)  => JsMacro(arg => JsCore.Access(acc(arg), JsCore.Literal(Js.Str(v)).fix).fix)
        case Index(v) => JsMacro(arg => JsCore.Access(acc(arg), JsCore.Literal(Js.Num(v, false)).fix).fix)
      })

  override def hashCode = this match {
    case Name(v) => v.hashCode
    case Index(v) => v.hashCode
    case Path(v) if (v.tail.length == 0) => v.head.hashCode
    case p @ Path(_) => p.flatten.hashCode
  }

  override def equals(that: Any): Boolean = (this, that) match {
    case (Name(v1),      Name(v2))      => v1 == v2
    case (Name(_),       Index(_))      => false
    case (Index(v1),     Index(v2))     => v1 == v2
    case (Index(_),      Name(_))       => false
    case (v1: BsonField, v2: BsonField) => v1.flatten.equals(v2.flatten)
    case _                              => false
  }
}

object BsonField {
  sealed trait Root
  final case object Root extends Root {
    override def toString = "BsonField.Root"
  }

  def apply(v: List[BsonField.Leaf]): Option[BsonField] = v match {
    case Nil => None
    case head :: Nil => Some(head)
    case head :: tail => Some(Path(NonEmptyList.nel(head, tail)))
  }

  sealed trait Leaf extends BsonField {
    def asText = Path(NonEmptyList(this)).asText

    def flatten: List[Leaf] = this :: Nil

    // Distinction between these is artificial as far as BSON concerned so you 
    // can always translate a leaf to a Name (but not an Index since the key might
    // not be numeric).
    def toName: Name = this match {
      case n @ Name(_) => n
      case Index(idx) => Name(idx.toString)
    }
  }

  case class Name(value: String) extends Leaf {
    override def toString = s"""BsonField.Name("$value")"""
  }
  case class Index(value: Int) extends Leaf {
    override def toString = s"BsonField.Index($value)"
  }

  private case class Path(values: NonEmptyList[Leaf]) extends BsonField {
    def flatten: List[Leaf] = values.list

    def asText = (values.list.zipWithIndex.map { 
      case (Name(value), 0) => value
      case (Name(value), _) => "." + value
      case (Index(value), 0) => value.toString
      case (Index(value), _) => "." + value.toString
    }).mkString("")
    
    override def toString = values.list.mkString(" \\ ")
  }

  private lazy val TempNames:   EphemeralStream[BsonField.Name]  = EphemeralStream.iterate(0)(_ + 1).map(i => BsonField.Name("__sd_tmp_" + i.toString))
  private lazy val TempIndices: EphemeralStream[BsonField.Index] = EphemeralStream.iterate(0)(_ + 1).map(i => BsonField.Index(i))

  def genUniqName(v: Iterable[BsonField.Name]): BsonField.Name =
    genUniqNames(1, v).head

  def genUniqNames(n: Int, v: Iterable[BsonField.Name]): List[BsonField.Name] =
    TempNames.filter(n => !v.toSet.contains(n)).take(n).toList

  def genUniqIndex(v: Iterable[BsonField.Index]): BsonField.Index =
    genUniqIndices(1, v).head

  def genUniqIndices(n: Int, v: Iterable[BsonField.Index]):
      List[BsonField.Index] =
    TempIndices.filter(n => !v.toSet.contains(n)).take(n).toList
}
