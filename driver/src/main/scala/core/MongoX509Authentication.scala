package reactivemongo.core.actors

import java.nio.charset.Charset

import reactivemongo.core.commands.FailedAuthentication
import reactivemongo.core.nodeset.{ Authenticate, Connection }
import reactivemongo.core.protocol.Response

private[reactivemongo] trait MongoX509Authentication { system: MongoDBSystem =>
  import reactivemongo.core.commands.X509Authenticate
  import reactivemongo.core.nodeset.X509Authenticating
  import MongoDBSystem.logger

  private var previouslyFailedAuthentication = false

  protected final def sendAuthenticate(connection: Connection, nextAuth: Authenticate): Connection = {
    connection.send(X509Authenticate(nextAuth.user)("$external").maker(RequestId.authenticate.next))

    connection.copy(authenticating = Some(
      X509Authenticating(nextAuth.db, nextAuth.user)))
  }

  protected val authReceive: Receive = {
    case response: Response if RequestId.authenticate accepts response =>
      logger.debug(s"AUTH: got authenticated response! ${response.info.channelId}")
      val message = response.documents.toString(0, 87, Charset.defaultCharset())
      if (message.toLowerCase.contains("failed")) {
        whenAuthenticating(response.info.channelId) {
          case (con, authenticating) =>
            val failedMsg = "Failed to authenticate with X509 authentication. Either does not match certificate or one of the two does not exist"
            if (!previouslyFailedAuthentication) {
              authenticationResponse(response)(_ => Left(FailedAuthentication(failedMsg)))
              previouslyFailedAuthentication = true
            }
            con.copy(authenticating = None)
            val originalAuthenticate = Authenticate(authenticating.db, authenticating.user, "")
            AuthRequestsManager.handleAuthResult(originalAuthenticate, FailedAuthentication(failedMsg))
            con
        }
        ()
      }
      else {
        authenticationResponse(response)(X509Authenticate.parseResponse)
      }
  }

}