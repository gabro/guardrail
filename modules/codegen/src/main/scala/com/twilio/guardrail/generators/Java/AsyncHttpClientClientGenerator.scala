package com.twilio.guardrail.generators.Java

import cats.data.NonEmptyList
import cats.instances.list._
import cats.syntax.traverse._
import cats.~>
import com.github.javaparser.JavaParser
import com.github.javaparser.ast.NodeList
import com.github.javaparser.ast.Modifier._
import com.github.javaparser.ast.`type`.{ClassOrInterfaceType, Type, VoidType}
import com.github.javaparser.ast.body._
import com.github.javaparser.ast.expr.{MethodCallExpr, NameExpr, _}
import com.github.javaparser.ast.stmt._
import com.twilio.guardrail.SwaggerUtil.jpaths
import com.twilio.guardrail.generators.{Response, ScalaParameter}
import com.twilio.guardrail.generators.syntax.Java._
import com.twilio.guardrail.languages.JavaLanguage
import com.twilio.guardrail.protocol.terms.client._
import com.twilio.guardrail.terms.RouteMeta
import com.twilio.guardrail.{RenderedClientOperation, StaticDefns, Target, languages}
import java.net.URI
import java.util
import javax.lang.model.`type`.PrimitiveType

object AsyncHttpClientClientGenerator {
  private val URI_TYPE = JavaParser.parseClassOrInterfaceType("URI")
  private val DEFAULT_ASYNC_HTTP_CLIENT_CONFIG_BUILDER_TYPE = JavaParser.parseClassOrInterfaceType("DefaultAsyncHttpClientConfig.Builder")
  private val DEFAULT_ASYNC_HTTP_CLIENT_TYPE = JavaParser.parseClassOrInterfaceType("DefaultAsyncHttpClient")
  private val ASYNC_HTTP_CLIENT_TYPE = JavaParser.parseClassOrInterfaceType("AsyncHttpClient")
  private val ASYNC_HTTP_CLIENT_CONFIG_TYPE = JavaParser.parseClassOrInterfaceType("AsyncHttpClientConfig")
  private val REQUEST_BUILDER_TYPE = JavaParser.parseClassOrInterfaceType("RequestBuilder")
  private val REQUEST_TYPE = JavaParser.parseClassOrInterfaceType("Request")
  private val RESPONSE_TYPE = JavaParser.parseClassOrInterfaceType("Response")
  private val FILE_PART_TYPE = JavaParser.parseClassOrInterfaceType("FilePart")
  private val STRING_PART_TYPE = JavaParser.parseClassOrInterfaceType("StringPart")
  private val OBJECT_MAPPER_TYPE = JavaParser.parseClassOrInterfaceType("ObjectMapper")
  private val BUILDER_TYPE = JavaParser.parseClassOrInterfaceType("Builder")
  private val MARSHALLING_EXCEPTION_TYPE = JavaParser.parseClassOrInterfaceType("MarshallingException")
  private val HTTP_ERROR_TYPE = JavaParser.parseClassOrInterfaceType("HttpError")
  private val EXCEPTION_TYPE = JavaParser.parseClassOrInterfaceType("Exception")
  private val ILLEGAL_ARGUMENT_EXCEPTION_TYPE = JavaParser.parseClassOrInterfaceType("IllegalArgumentException")
  private val JSON_PROCESSING_EXCEPTION_TYPE = JavaParser.parseClassOrInterfaceType("JsonProcessingException")

  private val HTTP_CLIENT_FUNCTION_TYPE = functionType.setTypeArguments(new NodeList[Type](
      REQUEST_TYPE,
      completionStageType.setTypeArguments(RESPONSE_TYPE)
    ))

  private def typeReferenceType(typeArg: Type): ClassOrInterfaceType =
    JavaParser.parseClassOrInterfaceType("TypeReference").setTypeArguments(typeArg)

  private def showParam(param: ScalaParameter[JavaLanguage], overrideParamName: Option[String] = None): Expression = {
    val paramName = overrideParamName.getOrElse(param.paramName.asString)

    def doShow(tpe: Type): Expression = tpe match {
      case _: PrimitiveType =>
        new MethodCallExpr(new NameExpr("String"), "valueOf", new NodeList[Expression](new NameExpr(paramName)))
      case cls: ClassOrInterfaceType if cls.isOptional =>
        doShow(cls.containedType)
      case cls: ClassOrInterfaceType if cls.isBoxedType =>
        new MethodCallExpr(new NameExpr(paramName), "toString")
      case cls: ClassOrInterfaceType if cls.isNamed("List") =>
        doShow(cls.containedType)
      case cls: ClassOrInterfaceType if cls.getName.asString() == "String" =>
        new NameExpr(paramName)
      case _: ClassOrInterfaceType =>
        // FIXME: this will cover our autogenerated enum types, but it would be really nice if we could at identify
        // our enum times via a list of some sort.  this will fail to compile in most non-enum cases, but there's
        // the possibility that this could generate incorrect but compilable code.
        new MethodCallExpr(new NameExpr(paramName), "getValue")
      case _: VoidType =>
        new NullLiteralExpr
      case other =>
        println(s"WARN: Unhandled arg type ${other.getClass.getName} for arg typed ${other.name} ${param.paramName}")
        new NameExpr("UNSUPPORTED_PARAMETER_TYPE_PLEASE_FILE_AN_ISSUE")
    }

    doShow(param.argType)
  }

  private def optionIfPresent(optionVarType: Type, optionVarName: String, innerStatement: Statement): Statement = {
    new ExpressionStmt(new MethodCallExpr(new NameExpr(optionVarName), "ifPresent", new NodeList[Expression](
      new LambdaExpr(new NodeList(new Parameter(util.EnumSet.of(FINAL), optionVarType, new SimpleName("arg"))),
        innerStatement,
        true
      ))
    ))
  }

  private def generateBuilderMethodCalls(params: List[ScalaParameter[JavaLanguage]], builderMethodName: String): List[Statement] = {
    val needsMultipart = params.exists(_.isFile)
    params.map({ param =>
      val finalMethodName = if (needsMultipart) "addBodyPart" else builderMethodName
      val argName = if (param.required) param.paramName.asString else "arg"
      val containedType = param.argType.containedType
      val isList = if (param.required) param.argType.isNamed("List") else containedType.isNamed("List")
      val listType = if (param.required) containedType else containedType.containedType

      val makeArgList: String => NodeList[Expression] = name =>
        if (param.isFile) {
          new NodeList[Expression](new ObjectCreationExpr(null, FILE_PART_TYPE, new NodeList(
            new StringLiteralExpr(param.argName.value),
            new NameExpr(name)
          )))
        } else if (needsMultipart) {
          new NodeList[Expression](new ObjectCreationExpr(null, STRING_PART_TYPE, new NodeList(
            new StringLiteralExpr(param.argName.value),
            showParam(param, Some(name))
          )))
        } else {
          new NodeList[Expression](new StringLiteralExpr(param.argName.value), showParam(param, Some(name)))
        }

      val builderStatement: Statement = if (isList) {
        new ForEachStmt(
          new VariableDeclarationExpr(listType, "member", FINAL),
          new NameExpr(argName),
          new BlockStmt(new NodeList(
            new ExpressionStmt(new MethodCallExpr(new NameExpr("builder"), finalMethodName, makeArgList("member")))
          ))
        )
      } else {
        new ExpressionStmt(new MethodCallExpr(new NameExpr("builder"), finalMethodName, makeArgList(argName)))
      }

      if (param.required) {
        builderStatement
      } else {
        optionIfPresent(containedType, param.paramName.asString, builderStatement)
      }
    })
  }

  private def generateBodyMethodCall(param: Option[ScalaParameter[JavaLanguage]]): Option[Statement] = {
    def wrapSetBody(expr: Expression): MethodCallExpr = {
      new MethodCallExpr(new NameExpr("builder"), "setBody", new NodeList[Expression](expr))
    }

    param.map({ param =>
      if (param.isFile) {
        new ExpressionStmt(wrapSetBody(new ObjectCreationExpr(null, FILE_PART_TYPE, new NodeList(
          new StringLiteralExpr(param.argName.value),
          new NameExpr(param.paramName.asString)
        ))))
      } else {
        new TryStmt(
          new BlockStmt(new NodeList(
            new ExpressionStmt(wrapSetBody(new MethodCallExpr(
              new FieldAccessExpr(new ThisExpr, "objectMapper"),
              "writeValueAsString",
              new NodeList[Expression](new NameExpr(param.paramName.asString))
            )))
          )),
          new NodeList(
            new CatchClause(
              new Parameter(util.EnumSet.of(FINAL), JSON_PROCESSING_EXCEPTION_TYPE, new SimpleName("e")),
              new BlockStmt(new NodeList(
                new ThrowStmt(new ObjectCreationExpr(
                  null,
                  ILLEGAL_ARGUMENT_EXCEPTION_TYPE,
                  new NodeList(new MethodCallExpr(new NameExpr("e"), "getMessage"), new NameExpr("e"))
                ))
              ))
            )
          ),
          null
        )
      }
    })
  }

  private def jacksonTypeReferenceFor(tpe: Type): Expression = {
    tpe match {
      case cls: ClassOrInterfaceType if cls.getTypeArguments.isPresent =>
        new ObjectCreationExpr(null, typeReferenceType(cls), new NodeList).setAnonymousClassBody(new NodeList)
      case other =>
        new ClassExpr(other)
    }
  }

  object ClientTermInterp extends (ClientTerm[JavaLanguage, ?] ~> Target) {
    def apply[T](term: ClientTerm[JavaLanguage, T]): Target[T] = term match {
      case GenerateClientOperation(_, RouteMeta(pathStr, httpMethod, operation), methodName, tracing, parameters, responses) =>
        val responseParentName = s"${operation.getOperationId.capitalize}Response"
        for {
          responseParentType <- safeParseClassOrInterfaceType(responseParentName)
          pathExpr <- jpaths.generateUrlPathParams(pathStr, parameters.pathParams)
        } yield {
          val method = new MethodDeclaration(util.EnumSet.of(PUBLIC), new VoidType, methodName)
          method.setType(completionStageType.setTypeArguments(responseParentType))

          val pathParams = parameters.pathParams.map(_.param)
          val qsParams = parameters.queryStringParams.map(_.param)
          val formParams = parameters.formParams.map(_.param)
          val headerParams = parameters.headerParams.map(_.param)
          val bodyParams = parameters.bodyParams.map(_.param).toList
          (pathParams ++ qsParams ++ formParams ++ headerParams ++ bodyParams).foreach(method.addParameter)

          val requestBuilder = new MethodCallExpr(
            new AssignExpr(new VariableDeclarationExpr(
              new VariableDeclarator(REQUEST_BUILDER_TYPE, "builder"), FINAL),
              new ObjectCreationExpr(null, REQUEST_BUILDER_TYPE, new NodeList[Expression](
                new StringLiteralExpr(httpMethod.toString)
              )),
              AssignExpr.Operator.ASSIGN
            ),
            "setUrl", new NodeList[Expression](pathExpr)
          )

          val builderMethodCalls: List[Statement] = List(
            generateBuilderMethodCalls(parameters.queryStringParams, "addQueryParam"),
            generateBuilderMethodCalls(parameters.formParams, "addFormParam"),
            generateBuilderMethodCalls(parameters.headerParams, "addHeader"),
            generateBodyMethodCall(parameters.bodyParams).toList
          ).flatten

          val httpMethodCallExpr = new MethodCallExpr(
            new FieldAccessExpr(new ThisExpr, "httpClient"),
            "apply",
            new NodeList[Expression](new MethodCallExpr(new NameExpr("builder"), "build"))
          )
          val requestCall = new MethodCallExpr(httpMethodCallExpr, "thenApply", new NodeList[Expression](
            new LambdaExpr(new NodeList(new Parameter(util.EnumSet.of(FINAL), RESPONSE_TYPE, new SimpleName("response"))), new BlockStmt(new NodeList(
              new SwitchStmt(new MethodCallExpr(new NameExpr("response"), "getStatusCode"), new NodeList(
                responses.value.map(response => new SwitchEntryStmt(new IntegerLiteralExpr(response.statusCode), new NodeList(response.value match {
                  case None => new ReturnStmt(new ObjectCreationExpr(null, JavaParser.parseClassOrInterfaceType(s"${responseParentName}.${response.statusCodeName.asString}"), new NodeList()))
                  case Some((valueType, _)) => new TryStmt(
                    new BlockStmt(new NodeList(
                      new ExpressionStmt(new AssignExpr(
                        new VariableDeclarationExpr(new VariableDeclarator(valueType, "result"), FINAL),
                        new MethodCallExpr(
                          new FieldAccessExpr(new ThisExpr, "objectMapper"),
                          "readValue",
                          new NodeList[Expression](
                            new MethodCallExpr(new NameExpr("response"), "getResponseBodyAsStream"),
                            jacksonTypeReferenceFor(valueType)
                          )
                        ), AssignExpr.Operator.ASSIGN)),
                      new IfStmt(
                        new BinaryExpr(new NameExpr("result"), new NullLiteralExpr, BinaryExpr.Operator.EQUALS),
                        new BlockStmt(new NodeList(new ThrowStmt(new ObjectCreationExpr(null, MARSHALLING_EXCEPTION_TYPE, new NodeList(new StringLiteralExpr("Failed to unmarshal response")))))),
                        new BlockStmt(new NodeList(new ReturnStmt(new ObjectCreationExpr(null, JavaParser.parseClassOrInterfaceType(s"${responseParentName}.${response.statusCodeName.asString}"), new NodeList[Expression](new NameExpr("result"))))))
                      )
                    )),
                    new NodeList(
                      new CatchClause(
                        new Parameter(util.EnumSet.of(FINAL), MARSHALLING_EXCEPTION_TYPE, new SimpleName("e")),
                        new BlockStmt(new NodeList(
                          new ThrowStmt(new NameExpr("e"))
                        ))
                      ),
                      new CatchClause(
                        new Parameter(util.EnumSet.of(FINAL), EXCEPTION_TYPE, new SimpleName("e")),
                        new BlockStmt(new NodeList(
                          new ThrowStmt(new ObjectCreationExpr(null, MARSHALLING_EXCEPTION_TYPE, new NodeList(new MethodCallExpr(new NameExpr("e"), "getMessage"), new NameExpr("e"))))
                        ))
                      )
                    ),
                    null
                  )
                }))) :+ new SwitchEntryStmt(null, new NodeList(new ThrowStmt(new ObjectCreationExpr(null, HTTP_ERROR_TYPE, new NodeList(new NameExpr("response")))))): _*
              ))
            )), true)
          ))

          method.setBody(new BlockStmt(new NodeList(
            new ExpressionStmt(requestBuilder) +:
            builderMethodCalls :+
            new ReturnStmt(requestCall): _*
          )))

          RenderedClientOperation[JavaLanguage](method, List.empty)
        }

      case GetImports(tracing) =>
        if (tracing) {
          Target.raiseError("Tracing is not yet supported by this framework")
        } else {
          (List(
            "java.net.URI",
            "java.util.Optional",
            "java.util.concurrent.CompletionStage",
            "java.util.function.Function",
            "com.fasterxml.jackson.core.JsonProcessingException",
            "com.fasterxml.jackson.core.type.TypeReference",
            "com.fasterxml.jackson.databind.ObjectMapper",
            "com.fasterxml.jackson.datatype.jdk8.Jdk8Module",
            "com.fasterxml.jackson.datatype.jsr310.JavaTimeModule",
            "org.asynchttpclient.AsyncHttpClient",
            "org.asynchttpclient.AsyncHttpClientConfig",
            "org.asynchttpclient.DefaultAsyncHttpClient",
            "org.asynchttpclient.DefaultAsyncHttpClientConfig",
            "org.asynchttpclient.Request",
            "org.asynchttpclient.RequestBuilder",
            "org.asynchttpclient.Response",
            "org.asynchttpclient.request.body.multipart.FilePart",
            "org.asynchttpclient.request.body.multipart.StringPart"
          ).map(safeParseRawImport) ++ List(
            "java.util.Objects.requireNonNull"
          ).map(safeParseRawStaticImport)).sequence
        }

      case GetExtraImports(tracing) =>
        Target.pure(List.empty)

      case ClientClsArgs(tracingName, serverUrls, tracing) =>
        Target.pure(List.empty)

      case GenerateResponseDefinitions(operationId, responses, protocolElems) =>
        val abstractClassName = s"${operationId.capitalize}Response"
        val genericTypeParam = JavaParser.parseClassOrInterfaceType("T")

        val responseData = responses.value.map({ case Response(statusCodeName, valueType) =>
          val responseName: String = statusCodeName.asString
          val responseType = JavaParser.parseClassOrInterfaceType(responseName)
          val responseLambdaName = s"handle${responseName}"

          val responseInnerClass = new ClassOrInterfaceDeclaration(util.EnumSet.of(PUBLIC, STATIC), false, responseName);
          responseInnerClass.addExtendedType(abstractClassName)
          valueType.foreach({ vt =>
            val finalValueType: Type = vt match {
              case p: ClassOrInterfaceType if p.isBoxedType => p.toUnboxedType
              case other => other
            }

            responseInnerClass.addField(finalValueType, "value", PRIVATE, FINAL)

            val constructor = responseInnerClass.addConstructor(PUBLIC)
            constructor.addParameter(new Parameter(util.EnumSet.of(FINAL), finalValueType, new SimpleName("value")))
            constructor.setBody(new BlockStmt(new NodeList(
              new ExpressionStmt(new AssignExpr(
                new FieldAccessExpr(new ThisExpr, "value"),
                new NameExpr("value"),
                AssignExpr.Operator.ASSIGN
              ))
            )))

            val getValueMethod = responseInnerClass.addMethod("getValue", PUBLIC)
            getValueMethod.setType(finalValueType)
            getValueMethod.setBody(new BlockStmt(new NodeList(
              new ReturnStmt(new FieldAccessExpr(new ThisExpr, "value"))
            )))
          })

          val foldMethodParamType = functionType.setTypeArguments(responseType, genericTypeParam)
          val foldMethodParameter = new Parameter(util.EnumSet.of(FINAL), foldMethodParamType, new SimpleName(responseLambdaName))

          val foldMethodBranch = new IfStmt(
            new InstanceOfExpr(new ThisExpr, responseType),
            new BlockStmt(new NodeList(
              new ReturnStmt(new MethodCallExpr(
                new NameExpr(responseLambdaName),
                "apply",
                new NodeList[Expression](new CastExpr(responseType, new ThisExpr))
              ))
            )),
            null
          )

          (responseInnerClass, foldMethodParameter, foldMethodBranch)
        })

        val abstractResponseClass = new ClassOrInterfaceDeclaration(util.EnumSet.of(PUBLIC, STATIC, ABSTRACT), false, abstractClassName)

        val (innerClasses, foldMethodParameters, foldMethodIfBranches) = responseData.unzip3

        innerClasses.foreach(abstractResponseClass.addMember)

        val foldMethod = abstractResponseClass.addMethod("fold", PUBLIC)
        foldMethod.addTypeParameter("T")
        foldMethod.setType("T")
        foldMethodParameters.foreach(foldMethod.addParameter)

        NonEmptyList.fromList(foldMethodIfBranches).foreach({ nel =>
          nel.reduceLeft({ (prev, next) =>
            prev.setElseStmt(next)
            next
          })

          nel.last.setElseStmt(new BlockStmt(new NodeList(
            new ThrowStmt(new ObjectCreationExpr(null, ASSERTION_ERROR_TYPE,
              new NodeList(new StringLiteralExpr("This is a bug in guardrail!")))
            )
          )))

          foldMethod.setBody(new BlockStmt(new NodeList(nel.head)))
        })

        Target.pure(List(abstractResponseClass))

      case BuildStaticDefns(clientName, tracingName, serverUrls, ctorArgs, tracing) =>
        Target.pure(
          StaticDefns[JavaLanguage](
            className = clientName,
            extraImports = List.empty,
            definitions = List.empty
          )
        )

      case BuildClient(clientName, tracingName, serverUrls, basePath, ctorArgs, clientCalls, supportDefinitions, tracing) =>
        val clientType = JavaParser.parseClassOrInterfaceType(clientName)

        val builderClass = new ClassOrInterfaceDeclaration(util.EnumSet.of(PUBLIC, STATIC), false, "Builder")
        val serverUrl = serverUrls.map(_.head).map(uri => new URI(uri.toString + basePath.getOrElse("")))

        val baseUrlField = builderClass.addField(URI_TYPE, "baseUrl", PRIVATE, FINAL)
        serverUrl.foreach({ serverUrl =>
          builderClass.addFieldWithInitializer(
            URI_TYPE, "DEFAULT_BASE_URL",
            new MethodCallExpr(
              new NameExpr("URI"),
              "create",
              new NodeList[Expression](new StringLiteralExpr(serverUrl.toString))
            ),
            PRIVATE, STATIC, FINAL
          )
          baseUrlField.setFinal(false)
          baseUrlField.getVariables.iterator().next().setInitializer(new NameExpr("DEFAULT_BASE_URL"))
        })

        if (tracing) {
          val clientNameField = builderClass.addField(STRING_TYPE, "clientName", PRIVATE, FINAL)
          tracingName.foreach({ tracingName =>
            builderClass.addFieldWithInitializer(
              STRING_TYPE, "DEFAULT_CLIENT_NAME",
              new StringLiteralExpr(tracingName),
              PRIVATE, STATIC, FINAL
            )
            clientNameField.setFinal(false)
            clientNameField.getVariables.iterator().next().setInitializer(new NameExpr("DEFAULT_CLIENT_NAME"))
          })
        }

        builderClass.addFieldWithInitializer(
          optionalType.setTypeArguments(HTTP_CLIENT_FUNCTION_TYPE), "httpClient",
          new MethodCallExpr(new NameExpr("Optional"), "empty"),
          PRIVATE
        )
        builderClass.addFieldWithInitializer(
          optionalType.setTypeArguments(OBJECT_MAPPER_TYPE), "objectMapper",
          new MethodCallExpr(new NameExpr("Optional"), "empty"),
          PRIVATE
        )

        val builderConstructor = builderClass.addConstructor(PUBLIC)
        def createConstructorParameter(tpe: Type, name: String): Parameter = {
          new Parameter(util.EnumSet.of(FINAL), tpe, new SimpleName(name))
        }
        def createBuilderConstructorAssignment(name: String): Statement = {
          new ExpressionStmt(new AssignExpr(
            new FieldAccessExpr(new ThisExpr, name),
            new MethodCallExpr("requireNonNull", new NameExpr(name)), AssignExpr.Operator.ASSIGN)
          )
        }
        (serverUrl, tracingName) match {
          case (None, None) if tracing =>
            builderConstructor.setParameters(new NodeList(
              createConstructorParameter(URI_TYPE, "baseUrl"),
              createConstructorParameter(STRING_TYPE, "clientName")
            ))
            builderConstructor.setBody(new BlockStmt(new NodeList(
              createBuilderConstructorAssignment("baseUrl"),
              createBuilderConstructorAssignment("clientName")
            )))

          case (Some(_), None) if tracing =>
            builderConstructor.setParameters(new NodeList(
              createConstructorParameter(STRING_TYPE, "clientName")
            ))
            builderConstructor.setBody(new BlockStmt(new NodeList(
              createBuilderConstructorAssignment("clientName")
            )))

          case (None, _) =>
            builderConstructor.setParameters(new NodeList(
              createConstructorParameter(URI_TYPE, "baseUrl")
            ))
            builderConstructor.setBody(new BlockStmt(new NodeList(
              createBuilderConstructorAssignment("baseUrl")
            )))

          case (Some(_), Some(_)) =>  // no params

          case (Some(_), _) if !tracing =>  // no params
        }

        def addSetter(tpe: Type, name: String, initializer: String => Expression): Unit = {
          val setter = builderClass.addMethod(s"with${name.capitalize}", PUBLIC)
          setter.setType(BUILDER_TYPE)
          setter.addParameter(new Parameter(util.EnumSet.of(FINAL), tpe, new SimpleName(name)))
          setter.setBody(new BlockStmt(new NodeList(
            new ExpressionStmt(new AssignExpr(new FieldAccessExpr(new ThisExpr, name), initializer(name), AssignExpr.Operator.ASSIGN)),
            new ReturnStmt(new ThisExpr)
          )))
        }
        val nonNullInitializer: String => Expression = name => new MethodCallExpr(null, "requireNonNull", new NodeList[Expression](new NameExpr(name)))
        def optionalInitializer(valueArg: String => Expression): String => Expression = name => new MethodCallExpr(new NameExpr("Optional"), "of", new NodeList[Expression](valueArg(name)))
        if (serverUrl.isDefined) {
          addSetter(URI_TYPE, "baseUrl", nonNullInitializer)
        }
        if (tracing) {
          addSetter(STRING_TYPE, "clientName", nonNullInitializer)
        }
        addSetter(HTTP_CLIENT_FUNCTION_TYPE, "httpClient", optionalInitializer(new NameExpr(_)))
        addSetter(OBJECT_MAPPER_TYPE, "objectMapper",
          optionalInitializer(name => new MethodCallExpr("configureObjectMapper", new NameExpr(name))))

        val buildMethod = builderClass.addMethod("build", PUBLIC)
        buildMethod.setType(clientType)
        buildMethod.setBody(new BlockStmt(new NodeList(
          new ReturnStmt(new ObjectCreationExpr(null, clientType, new NodeList(new ThisExpr)))
        )))

        def addInternalGetter(tpe: Type, name: String, getterCall: Expression): Unit = {
          val getter = builderClass.addMethod(s"get${name.capitalize}", PRIVATE)
          getter.setType(tpe)
          getter.setBody(new BlockStmt(new NodeList(
            new ReturnStmt(new MethodCallExpr(
              new FieldAccessExpr(new ThisExpr, name),
              "orElseGet",
              new NodeList[Expression](
                new LambdaExpr(new NodeList(), new ExpressionStmt(getterCall), true)
              )
            ))
          )))
        }
        addInternalGetter(HTTP_CLIENT_FUNCTION_TYPE, "httpClient",
          new MethodCallExpr("createDefaultHttpClient"))
        addInternalGetter(OBJECT_MAPPER_TYPE, "objectMapper",
          new MethodCallExpr("configureObjectMapper", new ObjectCreationExpr(null, OBJECT_MAPPER_TYPE, new NodeList())))

        val ahcConfigBuilder = new ObjectCreationExpr(null, DEFAULT_ASYNC_HTTP_CLIENT_CONFIG_BUILDER_TYPE, new NodeList())
        val ahcConfig = new MethodCallExpr(
          List(
            ("setMaxRequestRetry", 2),
            ("setConnectTimeout", 3000),
            ("setRequestTimeout", 10000),
            ("setReadTimeout", 3000),
            ("setMaxConnections", 1024),
            ("setMaxConnectionsPerHost", 1024)
          ).foldLeft[Expression](ahcConfigBuilder)({ case (lastExpr, (name, arg)) =>
            new MethodCallExpr(lastExpr, name, new NodeList[Expression](new IntegerLiteralExpr(arg)))
          }), "build", new NodeList[Expression]())

        val createDefaultHttpClientMethod = builderClass.addMethod("createDefaultHttpClient", PRIVATE, STATIC)
        createDefaultHttpClientMethod.setType(HTTP_CLIENT_FUNCTION_TYPE)
        createDefaultHttpClientMethod.setBody(new BlockStmt(new NodeList(
          new ExpressionStmt(new VariableDeclarationExpr(new VariableDeclarator(ASYNC_HTTP_CLIENT_CONFIG_TYPE, "config", ahcConfig), FINAL)),
          new ExpressionStmt(new VariableDeclarationExpr(new VariableDeclarator(
            ASYNC_HTTP_CLIENT_TYPE,
            "client",
            new ObjectCreationExpr(null, DEFAULT_ASYNC_HTTP_CLIENT_TYPE, new NodeList(new NameExpr("config")))
          ), FINAL)),
          new ReturnStmt(new LambdaExpr(
            new NodeList(new Parameter(util.EnumSet.of(FINAL), REQUEST_TYPE, new SimpleName("request"))),
            new ExpressionStmt(new MethodCallExpr(new MethodCallExpr(new NameExpr("client"), "executeRequest", new NodeList[Expression](new NameExpr("request"))), "toCompletableFuture")),
            true
          ))
        )))

        val configureObjectMapperMethod = builderClass.addMethod("configureObjectMapper", PRIVATE, STATIC)
        configureObjectMapperMethod.setType(OBJECT_MAPPER_TYPE)
        configureObjectMapperMethod.addParameter(new Parameter(util.EnumSet.of(FINAL), OBJECT_MAPPER_TYPE, new SimpleName("objectMapper")))
        configureObjectMapperMethod.setBody(new BlockStmt(new NodeList(
          List("JavaTimeModule", "Jdk8Module").map(name =>
            new ExpressionStmt(new MethodCallExpr(
              new NameExpr("objectMapper"),
              "registerModule",
              new NodeList[Expression](new ObjectCreationExpr(null, JavaParser.parseClassOrInterfaceType(name), new NodeList()))
            ))
          ) :+
          new ReturnStmt(new NameExpr("objectMapper")): _*
        )))

        val clientClass = new ClassOrInterfaceDeclaration(util.EnumSet.of(PUBLIC), false, clientName)

        List(
          Some((URI_TYPE, "baseUrl")),
          if (tracing) Some((STRING_TYPE, "clientName")) else None,
          Some((HTTP_CLIENT_FUNCTION_TYPE, "httpClient")),
          Some((OBJECT_MAPPER_TYPE, "objectMapper"))
        ).flatten.foreach({ case (tpe, name) => clientClass.addField(tpe, name, PRIVATE, FINAL) })

        val constructor = clientClass.addConstructor(PRIVATE)
        constructor.addParameter(new Parameter(util.EnumSet.of(FINAL), BUILDER_TYPE, new SimpleName("builder")))
        def newFieldAccessExpr(scope: Expression, name: String): Expression = new FieldAccessExpr(scope, name)
        def newMethodCallExpr(scope: Expression, name: String): Expression = new MethodCallExpr(scope, s"get${name.capitalize}")
        constructor.setBody(new BlockStmt(new NodeList(
          List[Option[(String, (Expression, String) => Expression)]](
            Some(("baseUrl", newFieldAccessExpr)),
            if (tracing) Some(("clientName", newFieldAccessExpr)) else None,
            Some(("httpClient", newMethodCallExpr)),
            Some(("objectMapper", newMethodCallExpr))
          ).flatten.map({ case (name, value) =>
            new ExpressionStmt(new AssignExpr(
              new FieldAccessExpr(new ThisExpr, name),
              value(new NameExpr("builder"), name),
              AssignExpr.Operator.ASSIGN
            ))
          }): _*
        )))

        clientClass.addMember(builderClass)
        clientCalls.foreach(clientClass.addMember)

        Target.pure(NonEmptyList(Right(clientClass), Nil))
    }
  }
}
