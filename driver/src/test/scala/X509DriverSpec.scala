import akka.actor.{ Actor, ActorRef, ActorSystem, Props }
import org.specs2.concurrent.{ ExecutionEnv => EE }
import reactivemongo.api.commands.DBUserRole
import reactivemongo.api.{ DefaultDB, FailoverStrategy, MongoDriver, X509Authentication }
import reactivemongo.bson.BSONDocument
import reactivemongo.core.actors.Exceptions.PrimaryUnavailableException
import reactivemongo.core.actors.{ Exceptions, PrimaryAvailable, RegisterMonitor, SetAvailable }
import reactivemongo.core.commands.{ FailedAuthentication, SuccessfulAuthentication }
import reactivemongo.core.nodeset.{ Authenticate, ProtocolMetadata }

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future, Promise }

class X509DriverSpec extends org.specs2.mutable.Specification {
  "Driver with X509" title

  sequential

  import Common._

  val hosts = Seq(primaryHost)

  "Authentication MONGODB-X509" should {
    import Common.{ DefaultOptions, timeout }

    lazy val drv = MongoDriver()
    val conOpts = DefaultOptions.copy(nbChannelsPerNode = 1, authMode = X509Authentication)
    lazy val connection = drv.connection(
      List(primaryHost), options = conOpts)
    val slowOpts = SlowOptions.copy(nbChannelsPerNode = 1, authMode = X509Authentication)
    lazy val slowConnection = drv.connection(List(slowPrimary), slowOpts)

    val dbName = "specs2-test-scramsha1-auth"
    def db_(implicit ee: ExecutionContext) =
      connection.database(dbName, failoverStrategy)

    val id = System.identityHashCode(drv)

    "work only if configured" in { implicit ee: EE =>
      db_.flatMap(_.drop()).map(_ => {}) must beEqualTo({}).
        await(0, timeout * 2)
    } tag "not_mongo26"

    "create a user" in { implicit ee: EE =>
      db_.flatMap(_.createUser(s"test-$id", Some(s"password-$id"),
        roles = List(DBUserRole("readWrite", dbName)))).
        aka("creation") must beEqualTo({}).await(0, timeout)
    }

    "not be successful with wrong credentials" >> {
      "with the default connection" in { implicit ee: EE =>
        connection.authenticate(dbName, "foo", "bar").
          aka("authentication") must throwA[FailedAuthentication].
          await(1, timeout)

      } tag "not_mongo26"

      "with the slow connection" in { implicit ee: EE =>
        slowConnection.authenticate(dbName, "foo", "bar").
          aka("authentication") must throwA[FailedAuthentication].
          await(1, slowTimeout)

      } tag "not_mongo26"
    }

    "be successful on existing connection with right credentials" >> {
      "with the default connection" in { implicit ee: EE =>
        connection.authenticate(dbName, s"test-$id", s"password-$id").
          aka("authentication") must beLike[SuccessfulAuthentication](
            { case _ => ok }).await(1, timeout) and {
              db_.flatMap {
                _("testcol").insert(BSONDocument("foo" -> "bar"))
              }.map(_ => {}) must beEqualTo({}).await(1, timeout * 2)
            }

      } tag "not_mongo26"

      "with the slow connection" in { implicit ee: EE =>
        slowConnection.authenticate(dbName, s"test-$id", s"password-$id").
          aka("authentication") must beLike[SuccessfulAuthentication](
            { case _ => ok }).await(1, slowTimeout)

      } tag "not_mongo26"
    }

    "be successful with right credentials" >> {
      val auth = Authenticate(dbName, s"test-$id", s"password-$id")

      "with the default connection" in { implicit ee: EE =>
        val con = drv.connection(
          List(primaryHost), options = conOpts, authentications = Seq(auth))

        con.database(dbName, Common.failoverStrategy).
          aka("authed DB") must beLike[DefaultDB] {
            case rdb => rdb.collection("testcol").insert(
              BSONDocument("foo" -> "bar")).map(_ => {}).
              aka("insertion") must beEqualTo({}).await(1, timeout)

          }.await(1, timeout) and {
            con.askClose()(timeout) must not(throwA[Exception]).
              await(1, timeout)
          }
      } tag "not_mongo26"

      "with the slow connection" in { implicit ee: EE =>
        val con = drv.connection(
          List(slowPrimary), options = slowOpts, authentications = Seq(auth))

        con.database(dbName, slowFailover).
          aka("authed DB") must beLike[DefaultDB] { case _ => ok }.
          await(1, slowTimeout) and {
            con.askClose()(slowTimeout) must not(throwA[Exception]).
              await(1, slowTimeout)
          }
      } tag "not_mongo26"
    }

    "driver shutdown" in { // mainly to ensure the test driver is closed
      drv.close(timeout) must not(throwA[Exception])
    } tag "not_mongo26"

    "fail on DB without authentication" >> {
      val auth = Authenticate(Common.commonDb, "test", "password")

      "with the default connection" in { implicit ee: EE =>
        def con = Common.driver.connection(
          List(primaryHost), options = conOpts, authentications = Seq(auth))

        con.database(Common.commonDb, failoverStrategy).
          aka("DB resolution") must throwA[PrimaryUnavailableException].like {
            case reason => reason.getStackTrace.headOption.
              aka("most recent") must beSome[StackTraceElement].like {
                case mostRecent =>
                  mostRecent.getClassName aka "class" must beEqualTo(
                    "reactivemongo.api.MongoConnection") and (
                      mostRecent.getMethodName aka "method" must_== "database")
              } and {
                Option(reason.getCause).
                  aka("cause") must beSome[Throwable].like {
                    case _: Exceptions.InternalState => ok
                  }
              }
          }.await(1, timeout)

      } tag "not_mongo26"

      "with the slow connection" in { implicit ee: EE =>
        def con = Common.driver.connection(
          List(slowPrimary), options = slowOpts, authentications = Seq(auth))

        con.database(Common.commonDb, slowFailover).
          aka("database resolution") must throwA[PrimaryUnavailableException].
          await(1, slowTimeout)

      } tag "not_mongo26"
    }
  }

  def testMonitor[T](result: Promise[T], actorSys: ActorSystem = driver.system)(test: Any => Option[T]): ActorRef = actorSys.actorOf(Props(new Actor {
    private object Msg {
      def unapply(that: Any): Option[T] = test(that)
    }

    val receive: Receive = {
      case sys: akka.actor.ActorRef => {
        // Manually register this as connection/system monitor
        sys ! RegisterMonitor
      }

      case Msg(v) => {
        result.success(v)
        context.stop(self)
      }
    }
  }))
}
