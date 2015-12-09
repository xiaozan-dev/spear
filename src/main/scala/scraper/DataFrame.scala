package scraper

import scraper.expressions.dsl._
import scraper.expressions.functions._
import scraper.expressions.{Ascending, SortOrder, Expression, NamedExpression}
import scraper.plans.logical.{Sort, Inner, Join, LogicalPlan}
import scraper.plans.{QueryExecution, logical}
import scraper.types.StructType

class DataFrame(val queryExecution: QueryExecution) {
  def this(logicalPlan: LogicalPlan, context: Context) = this(context execute logicalPlan)

  def context: Context = queryExecution.context

  private def build(f: LogicalPlan => LogicalPlan): DataFrame =
    new DataFrame(f(queryExecution.logicalPlan), context)

  lazy val schema: StructType = StructType fromAttributes queryExecution.analyzedPlan.output

  def rename(newNames: String*): DataFrame = {
    assert(newNames.length == schema.fields.length)
    val oldNames = schema.fields map (_.name)
    val aliases = (oldNames, newNames).zipped map { Symbol(_) as _ }
    this select aliases
  }

  def select(first: Expression, rest: Expression*): DataFrame =
    this select (first +: rest)

  def select(expressions: Seq[Expression]): DataFrame = build(
    logical.Project(_, expressions map {
      case e: NamedExpression => e
      case e                  => e as e.sql
    })
  )

  def filter(condition: Expression): DataFrame = build(logical.Filter(_, condition))

  def where(condition: Expression): DataFrame = this filter condition

  def limit(n: Expression): DataFrame = build(logical.Limit(_, n))

  def limit(n: Int): DataFrame = this limit lit(n)

  def join(right: DataFrame, condition: Option[Expression] = None): DataFrame = build { left =>
    Join(left, right.queryExecution.logicalPlan, Inner, condition)
  }

  def groupBy(expr: Expression*): GroupedData = new GroupedData(this, expr)

  def agg(expr: Expression, exprs: Expression*): DataFrame = this groupBy () agg (expr, exprs: _*)

  def orderBy(expr: Expression, exprs: Expression*): DataFrame = build { plan =>
    Sort(plan, (expr +: exprs).map(SortOrder(_, Ascending)))
  }

  def iterator: Iterator[Row] = queryExecution.physicalPlan.iterator

  def registerAsTable(tableName: String): Unit =
    context.catalog.registerRelation(tableName, queryExecution.analyzedPlan)

  def toSeq: Seq[Row] = iterator.toSeq

  def toArray: Array[Row] = iterator.toArray

  def explain(extended: Boolean): String = if (extended) {
    s"""# Logical plan
       |${queryExecution.logicalPlan.prettyTree}
       |
       |# Analyzed plan
       |${queryExecution.analyzedPlan.prettyTree}
       |
       |# Optimized plan
       |${queryExecution.optimizedPlan.prettyTree}
       |
       |# Physical plan
       |${queryExecution.physicalPlan.prettyTree}
       |""".stripMargin
  } else {
    s"""# Physical plan
       |${queryExecution.physicalPlan.prettyTree}
       |""".stripMargin
  }

  def printExplain(extended: Boolean): Unit = println(explain(extended))

  def printExplain(): Unit = println(explain(extended = true))
}
