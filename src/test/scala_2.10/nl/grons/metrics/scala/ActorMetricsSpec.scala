/*
 * Copyright (c) 2013-2013 Erik van Oosten
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nl.grons.metrics.scala

import org.mockito.Mockito._
import org.scalatest.OneInstancePerTest
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.mock.MockitoSugar
import org.scalatest.FunSpec
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import akka.actor.Actor
import akka.actor.ActorSystem
import com.codahale.metrics.Timer.Context
import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.{Counter => CHCounter}

object TestFixture {

  class Fixture  {
    import MockitoSugar._

    val mockCounter = mock[Counter]
    val mockTimer = mock[Timer]
    val mockTimerContext = mock[Context]
    val mockMeter = mock[Meter]

    when(mockTimer.timerContext()).thenReturn(mockTimerContext)
  }

  trait MetricRegistryFixture extends InstrumentedBuilder {
    import MockitoSugar._
    import org.mockito.Matchers._

    val fixture: Fixture

    val metricRegistry = null

    var counterName: String = null

    val mockBuilder = new MetricBuilder(null,null) {
	  override def counter(name: String, scope: String = null) = { counterName = name; fixture.mockCounter }
	  override def timer(name: String, scope: String = null) = fixture.mockTimer
	  override def meter(name: String, scope: String = null) = fixture.mockMeter
    }
    override def metrics = mockBuilder
  }

  class TestActor(val fixture: Fixture) extends Actor with MetricRegistryFixture {
    val messages = new scala.collection.mutable.ListBuffer[String]()

    def receive = { case message: String => messages += message }
  }

  class ExceptionThrowingTestActor(val fixture: Fixture) extends Actor with MetricRegistryFixture {
	  def receive = { case _ => throw new RuntimeException() }
  }


	class CounterTestActor(fixture: Fixture) extends TestActor(fixture) with ReceiveCounterActor {
	  override def receiveCounterName = "receiveCounter"
	}

	class TimerTestActor(fixture: Fixture) extends TestActor(fixture) with ReceiveTimerActor

	class ExceptionMeterTestActor(fixture: Fixture) extends ExceptionThrowingTestActor(fixture) with ReceiveExceptionMeterActor

	class ComposedActor(fixture: Fixture) extends TestActor(fixture)
		with ReceiveCounterActor with ReceiveTimerActor with ReceiveExceptionMeterActor
}

@RunWith(classOf[JUnitRunner])
class ActorMetricsSpec extends FunSpec with ShouldMatchers {
  import TestFixture._
  import akka.testkit.TestActorRef
  import scala.concurrent.duration._
  import scala.concurrent.Await
  import akka.pattern.ask
  import MockitoSugar._

  implicit val system = ActorSystem()

  describe("A counter actor") {
    it("increments counter on new messages") {
      val fixture = new Fixture
      val ref = TestActorRef(new CounterTestActor(fixture))
      ref ! "test"
      verify(fixture.mockCounter).+=(1)
      ref.underlyingActor.messages should contain ("test")
      ref.underlyingActor.counterName should equal ("receiveCounter")
    }
  }

  describe("A timer actor") {
    it("times a message processing") {
      val fixture = new Fixture
      val ref = TestActorRef(new TimerTestActor(fixture))
      ref ! "test"
      verify(fixture.mockTimer).timerContext()
      verify(fixture.mockTimerContext).stop()
      ref.underlyingActor.messages should contain ("test")
    }
  }

  describe("A exception meter actor") {
    it("meters thrown exceptions") {
      val fixture = new Fixture
      val ref = TestActorRef(new ExceptionMeterTestActor(fixture))
      intercept[RuntimeException] { ref.receive("test") }
      verify(fixture.mockMeter).mark()
    }
  }

  describe("A composed actor") {
    it("counts and times processing of messages") {
      val fixture = new Fixture
      val ref = TestActorRef(new ComposedActor(fixture))
      ref ! "test"
      verify(fixture.mockCounter).+=(1)
      verify(fixture.mockTimer).timerContext()
      verify(fixture.mockTimerContext).stop()
      verify(fixture.mockMeter,never()).mark()
      ref.underlyingActor.messages should contain ("test")
      ref.underlyingActor.counterName should equal ("nl.grons.metrics.scala.TestFixture$ComposedActor.receiveCounter")
    }
  }

}