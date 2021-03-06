package akka.contrib.pattern

import akka.testkit._
import akka.routing.{ ConsistentHashingRoutingLogic, RouterEnvelope }
import org.scalatest.WordSpecLike
import akka.actor.{ ActorInitializationException, ActorRef }

case class WrappedMessage(msg: String) extends RouterEnvelope {
  override def message = msg
}

case class UnwrappedMessage(msg: String)

object DistributedPubSubMediatorSpec {
  def config(routingLogic: String) = s"""
    akka.loglevel = INFO
    akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
    akka.remote.log-remote-lifecycle-events = off
    akka.contrib.cluster.pub-sub.routing-logic = $routingLogic
  """
}

trait DistributedPubSubMediatorSpec { this: WordSpecLike with TestKit with ImplicitSender ⇒
  def nonUnwrappingPubSub(mediator: ActorRef, testActor: ActorRef, msg: Any) {

    val path = testActor.path.toStringWithoutAddress

    "keep the RouterEnvelope when sending to a local logical path" in {

      mediator ! DistributedPubSubMediator.Put(testActor)

      mediator ! DistributedPubSubMediator.Send(path, msg, localAffinity = true)
      expectMsg(msg)

      mediator ! DistributedPubSubMediator.Remove(path)
    }

    "keep the RouterEnvelope when sending to a logical path" in {

      mediator ! DistributedPubSubMediator.Put(testActor)

      mediator ! DistributedPubSubMediator.Send(path, msg, localAffinity = false)
      expectMsg(msg)

      mediator ! DistributedPubSubMediator.Remove(path)
    }

    "keep the RouterEnvelope when sending to all actors on a logical path" in {

      mediator ! DistributedPubSubMediator.Put(testActor)

      mediator ! DistributedPubSubMediator.SendToAll(path, msg)
      expectMsg(msg) // SendToAll does not use provided RoutingLogic

      mediator ! DistributedPubSubMediator.Remove(path)
    }

    "keep the RouterEnvelope when sending to a topic" in {

      mediator ! DistributedPubSubMediator.Subscribe("topic", testActor)
      expectMsgClass(classOf[DistributedPubSubMediator.SubscribeAck])

      mediator ! DistributedPubSubMediator.Publish("topic", msg)
      expectMsg(msg) // Publish(... sendOneMessageToEachGroup = false) does not use provided RoutingLogic

      mediator ! DistributedPubSubMediator.Unsubscribe("topic", testActor)
      expectMsgClass(classOf[DistributedPubSubMediator.UnsubscribeAck])
    }

    "keep the RouterEnvelope when sending to a topic for a group" in {

      mediator ! DistributedPubSubMediator.Subscribe("topic", Some("group"), testActor)
      expectMsgClass(classOf[DistributedPubSubMediator.SubscribeAck])

      mediator ! DistributedPubSubMediator.Publish("topic", msg, sendOneMessageToEachGroup = true)
      expectMsg(msg)

      mediator ! DistributedPubSubMediator.Unsubscribe("topic", testActor)
      expectMsgClass(classOf[DistributedPubSubMediator.UnsubscribeAck])
    }
  }
}

class DistributedPubSubMediatorWithRandomRouterSpec
  extends AkkaSpec(DistributedPubSubMediatorSpec.config("random"))
  with DistributedPubSubMediatorSpec with DefaultTimeout with ImplicitSender {

  val mediator = DistributedPubSubExtension(system).mediator

  "DistributedPubSubMediator when sending wrapped message" must {
    val msg = WrappedMessage("hello")
    behave like nonUnwrappingPubSub(mediator, testActor, msg)
  }

  "DistributedPubSubMediator when sending unwrapped message" must {
    val msg = UnwrappedMessage("hello")
    behave like nonUnwrappingPubSub(mediator, testActor, msg)
  }
}

class DistributedPubSubMediatorWithHashRouterSpec
  extends AkkaSpec(DistributedPubSubMediatorSpec.config("consistent-hashing"))
  with DistributedPubSubMediatorSpec with DefaultTimeout with ImplicitSender {

  "DistributedPubSubMediator with Consistent Hash router" must {
    "not be allowed" when {
      "constructed by extension" in {
        intercept[IllegalArgumentException] {
          DistributedPubSubExtension(system).mediator
        }
      }
      "constructed by props" in {
        EventFilter[ActorInitializationException](occurrences = 1) intercept {
          system.actorOf(
            DistributedPubSubMediator.props(None, routingLogic = ConsistentHashingRoutingLogic(system)))
        }
      }
    }
  }
}
