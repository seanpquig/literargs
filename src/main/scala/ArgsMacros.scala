package literargs

import scala.language.experimental.macros
import scala.reflect.macros.whitebox

class ArgsMacros(val c: whitebox.Context) extends Parsing with Parts {
  object liftables extends Liftables[c.type](c)
  import c.universe._
  import liftables._

  implicit class MovablePosition(pos: Position) {
    def move(offset: Int) = pos.focus.withPoint(pos.focus.point + offset)
  }

  def unapplySeqImpl(unapplied: c.Expr[Array[String]]) = {
    ???
  }

  def unapplyImpl(unapplied: c.Expr[Array[String]]) = {
    val Select(Apply(_, List(Apply(_, parts))), Debug(debug)) = c.prefix.tree
    val opts = parseParts(extractParts(parts)).zipWithIndex.map { case (opt, idx) => opt[c.type](c, idx) }

    val get = opts match {
      case single :: Nil => q"def get: ${single.argType} = ${single.ident}"
      case _ => q"def get = this"
    }

    val unapply = q"""
    new {
      import org.apache.commons.cli.{Options, LenientParser}
      class Match(argv: Array[String]) {
        object opts {
          ..${opts.map(opt => q"val ${opt.ident} = ${opt.opt}")}
        }
        implicit val cmd =
          new LenientParser().parse({
            val os = new Options
            ..${opts.map(opt => q"os.addOption(opts.${opt.ident}.option)")}
            os
          }, argv)
        ..${opts.map(_.argument)}
        def isEmpty = ${opts.isEmpty}
        $get
        ..${opts.map(_.accessor)}
      }
      def unapply(argv: Array[String]) = new Match(argv)
    }.unapply($unapplied)
    """
    if (debug) println(showCode(unapply))
    unapply
  }

  private object Debug {
    def unapply(interpolator: Name): Option[Boolean] =
      Some(interpolator match {
        case TermName("argsd") => true
        case _ => false
      })
  }
}

trait Parts {
  self: Parsing { val c: whitebox.Context } =>

  import c.universe._

  def extractParts(trees: List[Tree]): String =
    trees.collect { case Literal(Constant(x: String)) => x }.mkString("")

  def parseParts(text: String) = {
    val Right(parsed) = (new Parser).parse_!(text)
    parsed
  }
}
