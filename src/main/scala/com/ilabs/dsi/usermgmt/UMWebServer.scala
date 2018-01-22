package com.ilabs.dsi.usermgmt

import java.time.Instant
import java.util.{Base64, Date}

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers.{HttpCookie, RawHeader}
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives.{path, _}
import akka.http.scaladsl.server.{Directive1, ExceptionHandler}
import akka.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, FromRequestUnmarshaller, PredefinedFromEntityUnmarshallers}
import akka.stream.ActorMaterializer
import ch.megard.akka.http.cors.scaladsl.CorsDirectives._
import com.ilabs.dsi.utils.{Json, Utils, WebInput}
import org.mindrot.jbcrypt.BCrypt
import org.slf4j.LoggerFactory
import slick.jdbc.SQLiteProfile.api._

import scala.concurrent.Await
import scala.concurrent.duration.Duration


/**
  * Created by samik on 12/3/17.
  */
object UMWebServer extends App
{
    // Set up logger
    val log = LoggerFactory.getLogger("WebServer")

    implicit val system = ActorSystem("ModelSubmitter")
    implicit val materializer = ActorMaterializer()
    // needed for the future flatMap/onComplete in the end
    implicit val executionContext = system.dispatcher
    private val db = Database.forConfig("ourdb")

    // Define an exception handler for the possible exceptions that arise.
    val serverExceptionHandler = ExceptionHandler
    {
        case ex: Exception =>
            complete(HttpResponse(StatusCodes.InternalServerError, entity = ex.getMessage))
    }

    // Define helper directive for converting the map of form fields to an WebInput object
    val webInputFromFormFields: Directive1[WebInput] = formFieldMap.map(fields => new WebInput(fields))


    val route = (handleExceptions(serverExceptionHandler) & post & decodeRequest & cors() & webInputFromFormFields)
    {
        webInput =>
        {
            //val webInput = new WebInput(fields)
            // Define paths
            path("register")
            {
                // Validate required fields
                val missingParams = webInput.required(List("email", "name", "password"))
                if (missingParams.nonEmpty)
                    complete(StatusCodes.BadRequest, s"Missing params: ${missingParams.mkString(", ")}")
                else
                {
                    val devKey = Utils.md5hash(webInput.stringVal("email")).substring(0, 20)
                    val response =
                        try
                        {
                            val action = sqlu"insert into users values(${webInput.stringVal("email")}, ${webInput.stringVal("name")}, ${BCrypt.hashpw(webInput.stringVal("password"), BCrypt.gensalt)}, $devKey, ${Date.from(Instant.now).toString})"
                            Await.result(db.run(action), Duration.Inf)
                            Map("registered" -> true, "devKey" -> devKey)
                        }
                        catch
                        {
                            case ex: Exception => Map("registered" -> false, "message" -> ex.getMessage)
                        }
                    complete(HttpEntity(ContentTypes.`application/json`, Json.Value(response).write))
                }
            } ~
            path("doLogin")
            {
                // Validate required fields
                val missingParams = webInput.required(List("email", "userName", "password"))
                if (missingParams.nonEmpty)
                    complete(StatusCodes.BadRequest)
                else if (webInput.stringVal("email") != "")
                {
                    // TODO: Log this
                    complete(StatusCodes.BadRequest)
                }
                else
                {
                    // Update database and get back the devKey
                    val devKey = doLogin(
                        webInput.stringVal("userName"),
                        webInput.stringVal("password")
                    )
                    if (devKey == "")
                        complete(StatusCodes.BadRequest, "Password incorrect.")
                    else
                    {
                        // Create a sessionToken
                        val sessionToken = Utils.constructRandomKey(25)
                        saveSession(webInput.stringVal("userName"), sessionToken)

                        setCookie(HttpCookie("session", value = sessionToken, path = Option("/"), httpOnly = true),

                            HttpCookie("devKey", value = devKey, path = Option("/"), httpOnly = true))
                        {
                            val headers = List(RawHeader("X-devKey", devKey))
                            respondWithHeaders(headers)
                            {
                                redirect("http://localhost/editor.html", StatusCodes.TemporaryRedirect)
                                //complete(HttpEntity(ContentTypes.`application/json`, Json.Value(Map("loginSuccess" -> true, "redirectTo" -> "http://localhost/editor.html")).write))
                            }
                        }
                    }
                }
            } ~
            path("getLogin")
            {
                optionalCookie("session")
                {
                    case Some(sessionCookie) =>
                    {
                        // Check if this is a valid session
                        val loginName = Await.result(db.run(sql"""select case when exists(select * from userSessions where sessionToken = ${Utils.md5hash(sessionCookie.value)}) then 'junk' else '' end""".as[String]), Duration.Inf)(0)
                        complete(HttpEntity(ContentTypes.`application/json`, Json.Value(Map("loginName" ->
                                loginName)).write))
                    }
                    case None => complete(HttpEntity(ContentTypes.`application/json`, Json.Value(Map("loginName" -> "")).write))
                }
            } ~
            path("doLogout")
            {
                // Delete cookie without checking
                deleteCookie(HttpCookie("session", ""), HttpCookie("devKey", ""))
                {
                    redirect("http://localhost/login.html", StatusCodes.TemporaryRedirect)
                }
            }
        }
    }

    /**
      * Method to log in an user.
      * @param username
      * @param password
      */
    def doLogin(username: String, password: String) =
    {
        // Get the hashed password for this user
        // Note: email field is used as username.
        val (devKey, hashedpw) = Await.result(db.run(sql"""select devKey, password from users where email = $username""".as[(String, String)]), Duration.Inf)(0)
        if(BCrypt.checkpw(password, hashedpw)) devKey else ""
    }

    def saveSession(email: String, sessionToken: String) =
    {
        val action = sqlu"insert into userSessions values($email, ${Utils.md5hash(sessionToken)}, ${System.currentTimeMillis.toString})"
        Await.result(db.run(action), Duration.Inf)
    }

    val bindingFuture = Http().bindAndHandle(route, "0.0.0.0", 3118)
    println(s"Server online at http://localhost:3118/")
}
