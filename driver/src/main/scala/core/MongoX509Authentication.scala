package reactivemongo.core.actors

import reactivemongo.core.nodeset.{ Authenticate, Connection }
import reactivemongo.core.protocol.Response

private[reactivemongo] trait MongoX509Authentication { system: MongoDBSystem =>
  import reactivemongo.core.commands.X509Authenticate
  import reactivemongo.core.nodeset.X509Authenticating
  import MongoDBSystem.logger

  protected final def sendAuthenticate(connection: Connection, nextAuth: Authenticate): Connection = {
    connection.send(X509Authenticate(nextAuth.user)("$external").maker(RequestId.authenticate.next))

    connection.copy(authenticating = Some(
      X509Authenticating(nextAuth.db, nextAuth.user)))
  }

  protected val authReceive: Receive = {
    case response: Response if RequestId.authenticate accepts response =>
      logger.debug(s"AUTH: got authenticated response! ${response.info.channelId}")
      authenticationResponse(response)(X509Authenticate.parseResponse)
  }

}