package com.ilabs.dsi.restapi

import java.util.Base64

import akka.actor.{ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshalling.{Marshaller, PredefinedToResponseMarshallers, ToEntityMarshaller, ToResponseMarshaller}
import akka.http.scaladsl.model.headers.{HttpCookie, HttpCookiePair, RawHeader}
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives.{complete, _}
import akka.http.scaladsl.server.{Directive, ExceptionHandler}
import akka.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, PredefinedFromEntityUnmarshallers}
import akka.stream.ActorMaterializer
import org.slf4j.LoggerFactory
import ch.megard.akka.http.cors.scaladsl.CorsDirectives._
import com.ilabs.dsi.utils.{Json, Utils, WebInput}
import slick.jdbc.SQLiteProfile.api._

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.io.{Source, StdIn}
import scala.util.Random
/**
  * Created by samik on 12/3/17.
  */
object RESTWebServer extends App
{
    // Set up logger
    val log = LoggerFactory.getLogger("WebServer")

    implicit val system = ActorSystem("ModelSubmitter")
    implicit val materializer = ActorMaterializer()
    // needed for the future flatMap/onComplete in the end
    implicit val executionContext = system.dispatcher
    private val db = Database.forConfig("ourdb")

    // Define an implicit unmarshaller to convert the JSON payload to an WebInput object.
    // Somewhat hairy topic :-) References:
    // [1] http://malaw.ski/2016/04/10/hakk-the-planet-implementing-akka-http-marshallers/
    // [2] http://doc.akka.io/docs/akka-http/current/scala/http/common/unmarshalling.html
    implicit val webInputFromStringPayloadUM: FromEntityUnmarshaller[WebInput] =
        PredefinedFromEntityUnmarshallers.stringUnmarshaller.map(new WebInput(_))

    // Define an exception handler for the possible exceptions that arise.
    val serverExceptionHandler = ExceptionHandler
    {
        case ex: Exception =>
            complete(HttpResponse(StatusCodes.InternalServerError, entity = ex.getMessage))
    }

    val route = (handleExceptions(serverExceptionHandler)
            & pathPrefix("v1")
            & post
            & decodeRequest
            & cors()
            & (entity(as[WebInput]) & optionalCookie("session") & optionalCookie("devKey") & optionalHeaderValueByName("X-devKey")))
    {
        (webInput, sessionCookie, devKeyCookie, devKeyHeader) =>
        {
            // Check and validate authorization values, and set up header/cookie(s) that would be sent
            val cookies = ArrayBuffer[HttpCookie]()
            val headers = ArrayBuffer[RawHeader]()
            // First try to get the devKey from cookie, then from header
            val devKey = ((devKeyCookie, devKeyHeader) match
            {
                case (Some(dkc), Some(dkh)) => if(dkc.value != dkh) None else Some(dkh)
                case (Some(dkc), None) => Some(dkc.value)
                case (None, Some(dkh)) => Some(dkh)
                case (None, None) => None
            })
            if(devKey.isEmpty || !devKeyIsValid(devKey.get))
                complete(StatusCodes.Unauthorized)
            else
            {
                if(devKeyCookie.isDefined) cookies += HttpCookie("devKey", value = devKey.get, path = Option("/"), httpOnly = true)
                if(devKeyHeader.isDefined) headers += RawHeader("X-devKey", devKey.get)
            }

            // At this point, we have a valid devKey
            // Check for sessionToken as well if available
            if(sessionCookie.isDefined)
            {
                if(sessionCookieIsValid(sessionCookie.get.value))
                    cookies += HttpCookie("session", value = sessionCookie.get.value, path = Option("/"), httpOnly = true)
                else
                    complete(StatusCodes.Unauthorized)
            }

            // Set up setCookie and respondWithHeaders directives
            val setMyCookies = if(cookies.length == 1) setCookie(cookies(0)) else if(cookies.length > 1) setCookie(cookies(0), cookies.tail: _*) else pass
            val setMyHeaders = if(headers.length == 1) respondWithHeaders(headers: _*) else pass

            (setMyCookies & setMyHeaders)
            {
                // Now proceed with the API functionality
                path("setModel")
                {
                    // Validate required fields
                    // Can we do an implicit List here?
                    val missingParams = webInput.required(List("modelName", "code"))
                    if (missingParams.nonEmpty)
                        complete((StatusCodes.BadRequest, s"Missing params: ${missingParams.mkString(", ")}"))
                    else
                    {
                        val modelKey = Random.alphanumeric.take(10).mkString
                        Await.result(db.run(sqlu"insert into models values($modelKey, ${webInput.stringVal("modelName")}, ${webInput.stringVal("code")})"), Duration.Inf)

                        complete(HttpEntity(ContentTypes.`application/json`, Json.Value(Map("modelKey" ->
                                    modelKey)).write))
                    }
                } ~
                path("getModel")
                {
                    val missingParams = webInput.required(List("modelKey"))
                    if (missingParams.nonEmpty)
                        complete((StatusCodes.BadRequest, s"Missing params: ${missingParams.mkString(", " +
                                "")}"))
                    else
                    {
                        val model = Await.result(db.run(sql"""select name, code from models where modelKey =
                                 ${webInput.stringVal("modelKey")}""".as[(String, String)]), Duration.Inf)(0)
                        complete(HttpEntity(ContentTypes.`application/json`, Json.Value(Map(
                            "modelKey" -> webInput.stringVal("modelKey"),
                            "modelName" -> model._1,
                            "code" -> model._2)).write))
                    }
                }
            }
        }
    }

    def sessionCookieIsValid(sessionToken: String): Boolean =
    {
        // Check if this is a valid session
        val tokenExists = Await.result(db.run(sql"""select case when exists(select * from userSessions where sessionToken = ${Utils.md5hash(sessionToken)}) then 1 else 0 end""".as[Int]), Duration.Inf)(0)
        (tokenExists == 1)
    }

    def devKeyIsValid(devKey: String): Boolean = true

    val bindingFuture = Http().bindAndHandle(route, "0.0.0.0", 3030)
    println(s"Server online at http://localhost:3030/")
}
