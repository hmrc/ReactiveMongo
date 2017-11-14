package reactivemongo.core.actors

import java.nio.charset.Charset

import reactivemongo.core.commands.FailedAuthentication
import reactivemongo.core.nodeset.{ Authenticate, Connection }
import reactivemongo.core.protocol.Response

private[reactivemongo] trait MongoX509Authentication { system: MongoDBSystem =>
  import reactivemongo.core.commands.X509Authenticate
  import reactivemongo.core.nodeset.X509Authenticating
  import MongoDBSystem.logger

  var x509Steps = 0

  protected final def sendAuthenticate(connection: Connection, nextAuth: Authenticate): Connection = {
    connection.send(X509Authenticate(nextAuth.user)("$external").maker(RequestId.authenticate.next))

    connection.copy(authenticating = Some(
      X509Authenticating(nextAuth.db, nextAuth.user, nextAuth.password)))
  }

  protected val authReceive: Receive = {
    case response: Response if RequestId.authenticate accepts response =>
      logger.debug(s"AUTH: got authenticated response! ${response.info.channelId}")
      val message = response.documents.toString(0, 87, Charset.defaultCharset())
      if (x509Steps < 2) {
        x509Steps += 1
        if (message.toLowerCase.contains("failed")) {
          val failedMsg = "Failed to authenticate with X509 authentication. Either does not match certificate or one of the two does not exist"
          authenticationResponse(response)(_ => Left(FailedAuthentication(failedMsg)))
        }
        else {
          authenticationResponse(response)(X509Authenticate.parseResponse)
        }
      }
      ()
  }
}