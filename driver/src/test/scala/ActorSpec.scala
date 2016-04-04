import akka.testkit.TestActorRef

import scala.concurrent.duration._
import scala.concurrent.Await
import akka.pattern.ask
import reactivemongo.api.MongoConnectionOptions
import reactivemongo.core.actors.MongoDBSystem
import reactivemongo.core.nodeset.{Authenticate, ChannelFactory, Connection}
import reactivemongo.core.protocol.Response

object ActorSpec extends org.specs2.mutable.Specification {
  "Actor model" title

  val system = new MongoDBSystem {override protected def authReceive: Receive = ???

    override protected def sendAuthenticate(connection: Connection, authentication: Authenticate): Connection = ???

    /**
      * List of authenticate messages - all the nodes will be authenticated as soon they are connected.
      */
    override def initialAuthenticates: Seq[Authenticate] = ???

    /**
      * MongoConnectionOption instance
      * (used for tweaking connection flags and pool size).
      */
    override def options: MongoConnectionOptions = ???

    /**
      * Nodes that will be probed to discover the whole replica set (or one standalone node).
      */
    override def seeds: Seq[String] = ???

    override def channelFactory: ChannelFactory = ???
  }

  val actorRef = TestActorRef(system)


  // This is the message that our new code is responding to
  val future = actorRef ? Response(header = ???, reply = ???, documents = ???, info = ???)


//  val Success(result: Int) = future.value.get
//  result should be(42)

  // Need to figure out what to assert on here

  // system.nodeSet something something

}