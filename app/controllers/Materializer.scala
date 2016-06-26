package controllers

import play.api.libs.json._
import play.api.libs.ws.WSClient
import sangria.execution.{HandledException, Executor, QueryReducer}
import sangria.{schema, ast}
import sangria.ast.{ObjectTypeDefinition, TypeDefinition, FieldDefinition}
import sangria.schema._

import sangria.marshalling.MarshallingUtil._
import sangria.marshalling.playJson._
import sangria.marshalling.queryAst._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

class Materializer(client: WSClient)(implicit ec: ExecutionContext) {
  implicit class JsonOps(value: JsValue) {
    def get(key: String) = value match {
      case JsObject(fields) ⇒ fields.get(key)
      case _ ⇒ None
    }
    def apply(key: String) = get(key).get

    def arrayValue = value.asInstanceOf[JsArray].value

    def stringValue = value match {
      case JsString(v) ⇒ v
      case JsNumber(v) ⇒ v.toString
      case JsBoolean(v) ⇒ v.toString
      case v ⇒ throw new IllegalStateException(s"Invalid string: $v.")
    }

    def booleanValue = value match {
      case JsBoolean(v) ⇒ v
      case JsString(v) ⇒ v.toBoolean
      case v ⇒ throw new IllegalStateException(s"Invalid boolean: $v.")
    }

    def intValue = value match {
      case JsNumber(v) ⇒ v.intValue()
      case JsString(v) ⇒ v.toInt
      case v ⇒ throw new IllegalStateException(s"Invalid int: $v.")
    }

    def bigIntValue = value match {
      case JsNumber(v) ⇒ v.toBigInt
      case JsString(v) ⇒ BigInt(v)
      case v ⇒ throw new IllegalStateException(s"Invalid big int: $v.")
    }

    def bigDecimalValue = value match {
      case JsNumber(v) ⇒ v
      case JsString(v) ⇒ BigDecimal(v)
      case v ⇒ throw new IllegalStateException(s"Invalid big decimal: $v.")
    }

    def doubleValue = value match {
      case JsNumber(v) ⇒ v.doubleValue
      case JsString(v) ⇒ v.toDouble
      case v ⇒ throw new IllegalStateException(s"Invalid double: $v.")
    }
  }

  case class TooComplexQueryError(message: String) extends Exception(message)

  val complexityRejecor = QueryReducer.rejectComplexQueries(20000, (complexity: Double, _: Any) ⇒
    TooComplexQueryError(s"Query complexity is $complexity but max allowed complexity is 20000. Please reduce the number of the fields in the query."))

  val exceptionHandler: Executor.ExceptionHandler = {
    case (m, error: TooComplexQueryError) ⇒ HandledException(error.getMessage)
    case (m, NonFatal(error)) ⇒
      HandledException(error.getMessage)
  }

  private def directiveJsonArg(d: ast.Directive, name: String) =
    d.arguments.collectFirst {
      case ast.Argument(`name`, ast.StringValue(str, _, _), _, _) ⇒ Json.parse(str)
    }.getOrElse(throw new IllegalStateException(s"Can't find a directive argument $name"))

  private def directiveStringArg(d: ast.Directive, name: String) =
    d.arguments.collectFirst {
      case ast.Argument(`name`, ast.StringValue(str, _, _), _, _) ⇒ str
    }.getOrElse(throw new IllegalStateException(s"Can't find a directive argument $name"))

  private def directiveStringArgOpt(d: ast.Directive, name: String) =
    d.arguments.collectFirst {
      case ast.Argument(`name`, ast.StringValue(str, _, _), _, _) ⇒ str
    }

  private def directiveAstArg(d: ast.Directive, name: String) =
    d.arguments.collectFirst {
      case ast.Argument(`name`, ast, _, _) ⇒ ast
    }.getOrElse(throw new IllegalStateException(s"Can't find a directive argument $name"))

  def rootValue(schemaAst: ast.Document) = {
    val values = schemaAst.definitions
      .collect {case s: ast.SchemaDefinition ⇒ s}
      .flatMap(_.directives)
      .collect {
        case dir @ ast.Directive("jsonConst", _, _, _) ⇒
          directiveJsonArg(dir, "value")
        case dir @ ast.Directive("const", _, _, _) ⇒
          directiveAstArg(dir, "value").convertMarshaled[JsValue]
      }

    JsObject(values.foldLeft(Map.empty[String, JsValue]) {
      case (acc, JsObject(vv)) ⇒ acc ++ vv
      case (acc, _) ⇒ acc
    })
  }

  private def extractCorrectValue(tpe: OutputType[_], value: Option[JsValue]): Any = tpe match {
    case OptionType(ofType) ⇒ Option(extractCorrectValue(ofType, value))
    case _ if value.isEmpty || value.get == JsNull ⇒ null
    case ListType(ofType) ⇒ value.get.arrayValue map (v ⇒ extractCorrectValue(ofType, Option(v)))
    case t: ScalarType[_] if t eq BooleanType ⇒ value.get.booleanValue
    case t: ScalarType[_] if t eq StringType ⇒ value.get.stringValue
    case t: ScalarType[_] if t eq IntType ⇒ value.get.intValue
    case t: ScalarType[_] if t eq BigIntType ⇒ value.get.bigIntValue
    case t: ScalarType[_] if t eq BigDecimalType ⇒ value.get.bigDecimalValue
    case t: ScalarType[_] if t eq FloatType ⇒ value.get.doubleValue
    case t: CompositeType[_] ⇒ value.get
    case t ⇒ throw new IllegalStateException(s"Builder for type '$t' is not supported yet.")
  }

  private def convertArgs(args: Args, field: ast.Field): JsObject =
    JsObject(args.raw.keys.flatMap(name ⇒ field.arguments.find(_.name == name).map(a ⇒ a.name → a.value.convertMarshaled[JsValue])).toMap)

  val schemaBuilder: AstSchemaBuilder[Any] = new DefaultAstSchemaBuilder[Any] {

    val directiveMapping: Map[String, (ast.Directive, FieldDefinition) ⇒ Context[Any, _] ⇒ schema.Action[Any, _]] = Map(
      "httpGet" → { (dir, fd) ⇒
        val url = directiveStringArg(dir, "url")
        val request = client.url(url)

        val value = fd.directives.find(_.name == "value").map(d ⇒ directiveMapping(d.name)(d, fd))

        c ⇒ request.execute().map { resp ⇒
          value.fold(extractCorrectValue(c.field.fieldType, Some(resp.json))) {fn ⇒
            val updated = fn(c.copy(
              schema = c.schema.asInstanceOf[Schema[Any, Any]],
              field = c.field.asInstanceOf[Field[Any, Any]],
              value = resp.json))

            updated.asInstanceOf[Value[_, _]].value
          }
        }
      },

      "jsonConst" → { (dir, _) ⇒
        val value = directiveJsonArg(dir, "value")

        c ⇒ extractCorrectValue(c.field.fieldType, Some(value))
      },

      "const" → { (dir, _) ⇒
        val value = directiveAstArg(dir, "value").convertMarshaled[JsValue]

        c ⇒ extractCorrectValue(c.field.fieldType, Some(value))
      },

      "arg" → { (dir, _) ⇒
        val name = directiveStringArg(dir, "name")

        c ⇒ {
          val args = convertArgs(c.args, c.astFields.head)
          extractCorrectValue(c.field.fieldType, args.get(name))
        }
      },

      "value" → { (dir, _) ⇒
        directiveStringArgOpt(dir, "name") match {
          case Some(name) ⇒
            c ⇒ extractCorrectValue(c.field.fieldType, c.value.asInstanceOf[JsValue].get(name))
          case None ⇒
            directiveStringArgOpt(dir, "path") match {
              case Some(path) ⇒
                c ⇒ extractCorrectValue(c.field.fieldType, Some(JsonPath.query(path, c.value.asInstanceOf[JsValue])))
              case None ⇒
                throw new IllegalStateException(s"Can't find a directive argument 'path' or 'name'.")
            }
        }
      },

      "context" → { (dir, _) ⇒
        directiveStringArgOpt(dir, "name") match {
          case Some(name) ⇒
            c ⇒ extractCorrectValue(c.field.fieldType, c.ctx.asInstanceOf[JsValue].get(name))
          case None ⇒
            directiveStringArgOpt(dir, "path") match {
              case Some(path) ⇒
                c ⇒ extractCorrectValue(c.field.fieldType, Some(JsonPath.query(path, c.ctx.asInstanceOf[JsValue])))
              case None ⇒
                throw new IllegalStateException(s"Can't find a directive argument 'path' or 'name'.")
            }
        }
      })

    override def resolveField(typeDefinition: TypeDefinition, definition: FieldDefinition) =
      definition.directives.find(d ⇒ directiveMapping contains d.name) match {
        case Some(dir) ⇒
          directiveMapping(dir.name)(dir, definition)
        case None ⇒
          c ⇒ {
            extractCorrectValue(c.field.fieldType, c.value.asInstanceOf[JsValue].get(definition.name))
          }
      }

    override def objectTypeInstanceCheck(definition: ObjectTypeDefinition, extensions: List[ast.TypeExtensionDefinition]) =
      Some((value, _) ⇒ value.asInstanceOf[JsObject].fields.filter(_._1 == "type").exists(_._2.asInstanceOf[JsString].value == definition.name))

    override def fieldComplexity(typeDefinition: TypeDefinition, definition: FieldDefinition) =
      if (definition.directives.exists(_.name == "httpGet"))
        Some((_, _, _) ⇒ 1000.0)
      else
        super.fieldComplexity(typeDefinition, definition)
  }
}
