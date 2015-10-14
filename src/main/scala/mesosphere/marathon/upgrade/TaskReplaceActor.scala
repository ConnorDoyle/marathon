package mesosphere.marathon.upgrade

import akka.actor.{ Actor, ActorLogging, Cancellable }
import akka.event.EventStream
import mesosphere.marathon.TaskUpgradeCanceledException
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.event.{ HealthStatusChanged, MesosStatusUpdateEvent }
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.upgrade.TaskReplaceActor._
import org.apache.mesos.Protos.TaskID
import org.apache.mesos.SchedulerDriver

import scala.collection.mutable
import scala.concurrent.Promise
import scala.concurrent.duration._

class TaskReplaceActor(
    driver: SchedulerDriver,
    taskQueue: LaunchQueue,
    taskTracker: TaskTracker,
    eventBus: EventStream,
    app: AppDefinition,
    promise: Promise[Unit]) extends Actor with ActorLogging {
  import context.dispatcher

  val tasksToKill = taskTracker.get(app.id)
  val appId = app.id
  val version = app.version.toString
  var healthy = Set.empty[String]
  var newTasksStarted: Int = 0
  var oldTaskIds = tasksToKill.map(_.getId)
  val toKill = oldTaskIds.to[mutable.Queue]
  var maxCapacity = (app.instances * (1 + app.upgradeStrategy.maximumOverCapacity)).toInt
  var outstandingKills = Set.empty[String]
  var periodicalRetryKills: Cancellable = _

  override def preStart(): Unit = {
    eventBus.subscribe(self, classOf[MesosStatusUpdateEvent])
    eventBus.subscribe(self, classOf[HealthStatusChanged])

    val minHealthy = (app.instances * app.upgradeStrategy.minimumHealthCapacity).ceil.toInt
    val nrToKillImmediately = math.max(0, toKill.size - minHealthy)

    // make sure at least one task can be started to get the ball rolling
    if (nrToKillImmediately == 0 && maxCapacity == app.instances)
      maxCapacity += 1

    log.info(s"For minimumHealthCapacity ${app.upgradeStrategy.minimumHealthCapacity} of ${app.id.toString} leave " +
      s"$minHealthy tasks running, maximum capacity $maxCapacity, killing $nrToKillImmediately tasks immediately")

    for (_ <- 0 until nrToKillImmediately) {
      killNextOldTask()
    }
    scheduleRetryKills()

    reconcileNewTasks()

    log.info("Resetting the backoff delay before restarting the app")
    taskQueue.resetDelay(app)
  }

  override def postStop(): Unit = {
    eventBus.unsubscribe(self)
    if (!promise.isCompleted)
      promise.tryFailure(
        new TaskUpgradeCanceledException(
          "The task upgrade has been cancelled"))
  }

  override def receive: Receive = {
    val behavior =
      if (app.healthChecks.nonEmpty)
        healthCheckingBehavior
      else
        taskStateBehavior

    behavior orElse commonBehavior
  }

  def taskStateBehavior: PartialFunction[Any, Unit] = {
    case MesosStatusUpdateEvent(slaveId, taskId, "TASK_RUNNING", _, `appId`, _, _, `version`, _, _) =>
      handleNewHealthyTask(taskId)
  }

  def healthCheckingBehavior: PartialFunction[Any, Unit] = {
    case HealthStatusChanged(`appId`, taskId, `version`, true, _, _) if !healthy(taskId) =>
      handleNewHealthyTask(taskId)
  }

  def commonBehavior: PartialFunction[Any, Unit] = {
    case MesosStatusUpdateEvent(slaveId, taskId, TaskFailed(_), _, `appId`, _, _, `version`, _, _) if !oldTaskIds(taskId) => // scalastyle:ignore line.size.limit
      log.error(s"New task $taskId failed on slave $slaveId during app $appId restart")
      healthy -= taskId
      taskQueue.add(app)

    case MesosStatusUpdateEvent(slaveId, taskId, TaskFinished(_), _, `appId`, _, _, _, _, _) if oldTaskIds(taskId) => // scalastyle:ignore line.size.limit
      oldTaskIds -= taskId
      outstandingKills -= taskId
      reconcileNewTasks()
      checkFinished()

    case RetryKills =>
      retryKills()
      scheduleRetryKills()

    case x: Any => log.debug(s"Received $x")
  }

  def reconcileNewTasks(): Unit = {
    val leftCapacity = math.max(0, maxCapacity - oldTaskIds.size - newTasksStarted)
    val tasksNotStartedYet = math.max(0, app.instances - newTasksStarted)
    val tasksToStartNow = math.min(tasksNotStartedYet, leftCapacity)
    if (tasksToStartNow > 0) {
      log.info(s"Reconciling tasks during app $appId restart: queuing $tasksToStartNow new tasks")
      taskQueue.add(app, tasksToStartNow)
      newTasksStarted += tasksToStartNow
    }
  }

  def handleNewHealthyTask(taskId: String): Unit = {
    healthy += taskId
    killNextOldTask(Some(taskId))
    checkFinished()
  }

  def killNextOldTask(maybeNewTaskId: Option[String] = None): Unit = {
    if (toKill.nonEmpty) {
      val nextOldTask = toKill.dequeue()

      maybeNewTaskId match {
        case Some(newTaskId: String) =>
          log.info(s"Killing old task $nextOldTask because $newTaskId became reachable")
        case _ =>
          log.info(s"Killing old task $nextOldTask")
      }

      outstandingKills += nextOldTask
      driver.killTask(buildTaskId(nextOldTask))
    }
  }

  def checkFinished(): Unit = {
    if (healthy.size == app.instances && oldTaskIds.isEmpty) {
      log.info(s"App All new tasks for $appId are healthy and all old tasks have been killed")
      periodicalRetryKills.cancel()
      promise.success(())
      context.stop(self)
    }
    else if (log.isDebugEnabled) {
      log.debug(s"For app: [${app.id}] there are [${healthy.size}] healthy new instances and " +
        s"[${oldTaskIds.size}] old instances.")
    }
  }

  def retryKills(): Unit = {
    outstandingKills.foreach { id =>
      log.warning(s"Retrying kill of old task $id")
      driver.killTask(buildTaskId(id))
    }
  }

  def buildTaskId(id: String): TaskID =
    TaskID.newBuilder()
      .setValue(id)
      .build()

  def scheduleRetryKills(): Unit =
    periodicalRetryKills = context.system.scheduler.scheduleOnce(15.seconds, self, RetryKills)
}

object TaskReplaceActor {
  val TaskFinished = "^TASK_(ERROR|FAILED|FINISHED|LOST|KILLED)$".r
  val TaskFailed = "^TASK_(ERROR|FAILED|LOST|KILLED)$".r

  case object RetryKills
}

