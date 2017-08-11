package reactivemongo.core.actors

import scala.concurrent.{ Future, Promise }

import reactivemongo.api.BSONSerializationPack
import reactivemongo.bson.{ BSONDocument, DefaultBSONHandlers }
import reactivemongo.core.commands.{ FailedAuthentication, SuccessfulAuthentication }
import reactivemongo.core.netty.{ BufferSequence, ChannelBufferWritableBuffer }
import reactivemongo.core.protocol.{ Query, QueryFlags, RequestMaker, Response }
import reactivemongo.core.nodeset.{
  Authenticate,
  CrAuthenticating,
  Connection,
  ScramSha1Authenticating,
  X509Authenticating
}

private[reactivemongo] trait MongoCrAuthentication { system: MongoDBSystem =>
  import reactivemongo.core.commands.{ CrAuthenticate, GetCrNonce }
  import MongoDBSystem.logger

  protected final def sendAuthenticate(connection: Connection, nextAuth: Authenticate): Connection = {
    connection.send(GetCrNonce(nextAuth.db).maker(RequestId.getNonce.next))
    connection.copy(authenticating = Some(
      CrAuthenticating(nextAuth.db, nextAuth.user, nextAuth.password, None)
    ))
  }

  protected val authReceive: Receive = {
    case response: Response if RequestId.getNonce accepts response => {
      GetCrNonce.ResultMaker(response).fold(
        e =>
          logger.warn(s"error while processing getNonce response #${response.header.responseTo}", e),
        nonce => {
          logger.debug(s"AUTH: got nonce for channel ${response.info.channelId}: $nonce")
          whenAuthenticating(response.info.channelId) {
            case (connection, a @ CrAuthenticating(db, user, pass, _)) =>
              connection.send(CrAuthenticate(user, pass, nonce)(db).
                maker(RequestId.authenticate.next))

              connection.copy(authenticating = Some(a.copy(
                nonce = Some(nonce)
              )))

            case (connection, auth) => {
              val msg = s"unexpected authentication: $auth"

              logger.warn(s"AUTH: $msg")
              authenticationResponse(response)(
                _ => Left(FailedAuthentication(msg))
              )

              connection
            }
          }
        }
      )

      ()
    }

    case response: Response if RequestId.authenticate accepts response => {
      logger.debug(s"AUTH: got authenticated response! ${response.info.channelId}")
      authenticationResponse(response)(CrAuthenticate.parseResponse(_))
      ()
    }
  }
}

private[reactivemongo] trait MongoScramSha1Authentication {
  system: MongoDBSystem =>

  import MongoDBSystem.logger
  import org.apache.commons.codec.binary.Base64
  import reactivemongo.core.commands.{ CommandError, ScramSha1FinalNegociation, ScramSha1Initiate, ScramSha1Negociation, ScramSha1StartNegociation, SuccessfulAuthentication }

  protected final def sendAuthenticate(connection: Connection, nextAuth: Authenticate): Connection = {
    val start = ScramSha1Initiate(nextAuth.user)

    connection.send(start(nextAuth.db).maker(RequestId.getNonce.next))

    connection.copy(authenticating = Some(
      ScramSha1Authenticating(nextAuth.db, nextAuth.user, nextAuth.password,
        start.randomPrefix, start.message)
    ))
  }

  protected val authReceive: Receive = {
    case response: Response if RequestId.getNonce accepts response => {
      ScramSha1Initiate.parseResponse(response).fold(
        { e =>
          val msg = s"error while processing getNonce response #${response.header.responseTo}"

          logger.warn(s"AUTH: $msg")
          logger.debug("SCRAM-SHA1 getNonce failure", e)

          authenticationResponse(response)(_ => Left(FailedAuthentication(msg)))
        }, { challenge =>
          logger.debug(s"AUTH: got challenge for channel ${response.info.channelId}: $challenge")

          whenAuthenticating(response.info.channelId) {
            case (con, a @ ScramSha1Authenticating(
              db, user, pwd, rand, msg, _, _, step)) => {
              val negociation = ScramSha1StartNegociation(user, pwd,
                challenge.conversationId, challenge.payload, rand, msg)

              negociation.serverSignature.fold[Connection](
                { e => authenticationResponse(response)(_ => Left(e)); con },
                { sig =>
                  con.send(negociation(db).maker(RequestId.authenticate.next))

                  con.copy(authenticating = Some(a.copy(
                    conversationId = Some(challenge.conversationId),
                    serverSignature = Some(sig),
                    step = step + 1
                  )))
                }
              )
            }

            case (con, auth) => {
              val msg = s"unexpected authentication: $auth"

              logger.warn(s"AUTH: $msg")
              authenticationResponse(response)(
                _ => Left(FailedAuthentication(msg))
              )

              con
            }
          }
        }
      )

      ()
    }

    case response: Response if RequestId.authenticate accepts response => {
      logger.debug(s"AUTH: got authenticated response! ${response.info.channelId}")

      @inline def resp: Either[Either[CommandError, SuccessfulAuthentication], Array[Byte]] = ScramSha1StartNegociation.parseResponse(response) match {
        case Left(err)             => Left(Left(err))
        case Right(Left(authed))   => Left(Right(authed))
        case Right(Right(payload)) => Right(payload)
      }

      resp.fold(
        { r => authenticationResponse(response)(_ => r) },
        { payload: Array[Byte] =>
          logger.debug("2-phase SCRAM-SHA1 negociation")

          whenAuthenticating(response.info.channelId) {
            case (con, a @ ScramSha1Authenticating(
              db, user, pwd, rand, msg, Some(cid), Some(sig),
              1 /* step; TODO: more retry? */ )) => {

              val serverSig: Option[String] =
                ScramSha1Negociation.parsePayload(payload).get("v")

              if (!serverSig.exists(_ == Base64.encodeBase64String(sig))) {
                val msg = "the SCRAM-SHA1 server signature is invalid"

                logger.warn(s"AUTH: $msg")
                authenticationResponse(response)(
                  _ => Left(FailedAuthentication(msg))
                )

                con
              } else {
                val negociation = ScramSha1FinalNegociation(cid, payload)

                con.send(negociation(db).maker(RequestId.authenticate.next))
                con.copy(authenticating = Some(a.copy(step = 2)))
              }
            }

            case (con, auth) => {
              val msg = s"unexpected authentication: $auth"

              logger.warn(s"AUTH: msg")
              authenticationResponse(response)(
                _ => Left(FailedAuthentication(msg))
              )

              con
            }
          }
        }
      )

      ()
    }
  }
}

//private[reactivemongo] trait MongoX509Authentication { system: MongoDBSystem =>
//  import MongoDBSystem.logger
//
//  protected final def sendAuthenticate(connection: Connection, nextAuth: Authenticate): Connection = {
//
//    logger.warn("********* Authorizing on Mongo")
//
//    val authDocument = BSONDocument(
//      "authenticate" -> 1,
//      "mechanism" -> "MONGODB-X509"
//    )
//
//    val buffer = ChannelBufferWritableBuffer()
//    BSONSerializationPack.serializeAndWrite(buffer, authDocument, DefaultBSONHandlers.BSONDocumentIdentity)
//
//    val bs = BufferSequence(buffer.buffer)
//
//    val request = RequestMaker(Query(QueryFlags.AwaitData, "$external", 0, 1), bs)
//
//    connection.send(request.apply(RequestId.authenticate.next))
//    connection.copy(authenticating = Some(
//      X509Authenticating(nextAuth.db, nextAuth.user)
//    ))
//  }
//
//  final def initialAuth(connection: Connection): Connection = {
//
//    logger.warn("********* Authorizing on Mongo")
//
//    val authDocument = BSONDocument(
//      "authenticate" -> 1,
//      "mechanism" -> "MONGODB-X509"
//    )
//
//    val buffer = ChannelBufferWritableBuffer()
//    BSONSerializationPack.serializeAndWrite(buffer, authDocument, DefaultBSONHandlers.BSONDocumentIdentity)
//
//    val bs = BufferSequence(buffer.buffer)
//
//    val request = RequestMaker(Query(QueryFlags.AwaitData, "$external", 0, 1), bs)
//
//    connection.send(request.apply(RequestId.authenticate.next))
//    connection
//  }
//
//  protected val authReceive: Receive = {
//    case _: Response => {
//      logger.warn("challenge received for a x509 auth, nothing to do here")
//      ()
//    }
//  }
//}

private[reactivemongo] trait MongoX509Authentication { system: MongoDBSystem =>
  import reactivemongo.core.commands.{ X509Authenticate }
  import reactivemongo.core.nodeset.X509Authenticating
  import MongoDBSystem.logger

  protected final def sendAuthenticate(connection: Connection, nextAuth: Authenticate): Connection = {
    connection.send(X509Authenticate(nextAuth.user)("$external").maker(RequestId.authenticate.next))

    connection.copy(authenticating = Some(
      X509Authenticating(nextAuth.db, nextAuth.user)
    ))
  }

  protected val authReceive: Receive = {
    case response: Response if RequestId.authenticate accepts response =>
      logger.debug(s"AUTH: got authenticated response! ${response.info.channelId}")
      authenticationResponse(response)(X509Authenticate.parseResponse(_))
  }
}

case class AuthRequest(authenticate: Authenticate, promise: Promise[SuccessfulAuthentication] = Promise()) {
  def future: Future[SuccessfulAuthentication] = promise.future
}
