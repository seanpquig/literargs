package literargs

import scala.util.parsing.input.Positional
import cats.Id
import cats.data.Xor
import org.apache.commons.cli.{ Option => COption, CommandLine }
import scala.reflect.macros.whitebox

case class Opt(name: OptName, private[literargs] val hole: Hole) extends Positional {
  val option = hole.option(name)

  private[literargs] class Plus[C <: whitebox.Context](val c: C, idx: Int, val opt: Opt) {
    import c.universe._
    val ident = c.freshName(TermName(s"`${name.repr}`"))
    val ordinal = TermName(s"_${idx + 1}")
    val (argument, argType) = hole match {
      case BooleanHole =>
        q"object $ident extends BooleanArgument(opts.$ident)" -> tq"Argument[cats.Id, Boolean]"
      case ValueHole(arity, ascription) =>
        val tpe = ascription.map(t => tq"${TypeName(t)}").getOrElse(tq"String")
        val monad = arity match {
          case N_ary(required) => if (required) tq"cats.data.NonEmptyList" else tq"List"
          case Unary(required) => if (required) tq"cats.Id" else tq"Option"
        }
        q"object $ident extends ValueArgument[$monad, $tpe](opts.$ident)" -> tq"Argument[$monad, $tpe]"
    }
    val accessor = q"""def $ordinal: $argType = $ident"""
  }

  private[literargs] def apply[C <: whitebox.Context](c: C, idx: Int) = new Plus[C](c, idx, this)

  def render = (name.render, hole.render)
}

object Opt {
  def boolean(name: OptName) = Opt(name, BooleanHole)
  def value(name: OptName, multiple: Boolean = false, required: Boolean = false) =
    Opt(name, ValueHole(if (multiple) N_ary(required) else Unary(required)))
}

case class OptName(short: Char, long: Option[String] = None) {
  val repr = long.getOrElse(s"$short")
  def builder() = using(COption.builder(short.toString)) {
    builder =>
      long.foreach(builder.longOpt(_))
  }
  def render = s"-$short" + (long match {
    case Some(long) => s"|--$long"
    case _ => ""
  })
}

sealed trait Arity {
  def matches(cmd: CommandLine, opt: Opt): Boolean = ???
  def render(ascription: Option[String]): String
}
private[literargs] sealed trait Brackets {
  self: Arity =>
  protected def brackets(required: Boolean, ascription: Option[String], nary: Boolean = false) = {
    val dots = if (nary) ".." else ""
    val inner = ascription.map(":" + _).getOrElse("")
    if (required) "<" + dots + inner + ">"
    else "[" + dots + inner + "]"
  }
}
case class Unary(required: Boolean) extends Arity with Brackets {
  def render(ascription: Option[String]) = brackets(required, ascription)
}
case class N_ary(required: Boolean) extends Arity with Brackets {
  def render(ascription: Option[String]) = brackets(required, ascription, nary = true)
}

private[literargs] sealed trait Hole {
  def option(name: OptName): COption
  def render: String
}

case class ValueHole(arity: Arity, private[literargs] val ascription: Option[String] = None) extends Hole {
  def option(name: OptName): COption =
    using(name.builder())(_.hasArg(true).required(arity match {
      case Unary(true) => true
      case _ => false
    })).build()
  def render = arity.render(ascription)
}

case object BooleanHole extends Hole {
  private[literargs] def ascription = None
  def option(name: OptName): COption =
    using(name.builder())(_.hasArg(false).required(false)).build()
  def render = ""
}

sealed trait Argument[M[_], T] {
  def opt: Opt
  def value: Xor[Throwable, M[T]]
}

class ValueArgument[M[_], T](val opt: Opt)(
    implicit
    cmd: CommandLine,
    C: Collect[M],
    E: Extractor[M, T]
) extends Argument[M, T] {
  def value = C.collect(cmd, opt).map(E.extract)
}

class BooleanArgument(val opt: Opt)(implicit cmd: CommandLine) extends Argument[Id, Boolean] {
  def value = Xor.right(cmd.hasOption(opt.name.repr))
}
