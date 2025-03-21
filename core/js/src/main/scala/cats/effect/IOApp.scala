/*
 * Copyright 2020-2021 Typelevel
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

package cats.effect

import scala.concurrent.CancellationException
import scala.concurrent.duration._
import scala.scalajs.js

/**
 * The primary entry point to a Cats Effect application. Extend this
 * trait rather than defining your own `main` method. This avoids the
 * need to run [[IO.unsafeRunAsync]] (or similar) on your own.
 *
 * `IOApp` takes care of the messy details of properly setting up
 * (and tearing down) the [[unsafe.IORuntime]] needed to run the [[IO]]
 * which represents your application. All of the associated thread
 * pools (if relevant) will be configured with the assumption that
 * your application is fully contained within the `IO` produced by
 * the [[run]] method. Note that the exact details of how the runtime
 * will be configured are very platform-specific. Part of the point
 * of `IOApp` is to insulate users from the details of the underlying
 * runtime (whether JVM or JavaScript).
 *
 * {{{
 *   object MyApplication extends IOApp {
 *     def run(args: List[String]) =
 *       for {
 *         _ <- IO.print("Enter your name: ")
 *         name <- IO.readln
 *         _ <- IO.println("Hello, " + name)
 *       } yield ExitCode.Success
 *   }
 * }}}
 *
 * In the above example, `MyApplication` will be a runnable class with
 * a `main` method, visible to Sbt, IntelliJ, or plain-old `java`. When
 * run externally, it will print, read, and print in the obvious way,
 * producing a final process exit code of 0. Any exceptions thrown within
 * the `IO` will be printed to standard error and the exit code will be
 * set to 1. In the event that the main [[Fiber]] (represented by the `IO`
 * returned by `run`) is canceled, the runtime will produce an exit code of 1.
 *
 * Note that exit codes are an implementation-specific feature of the
 * underlying runtime, as are process arguments. Naturally, all JVMs
 * support these functions, as does NodeJS, but some JavaScript execution
 * environments will be unable to replicate these features (or they simply
 * may not make sense). In such cases, exit codes may be ignored and/or
 * argument lists may be empty.
 *
 * Note that in the case of the above example, we would actually be
 * better off using [[IOApp.Simple]] rather than `IOApp` directly, since
 * we are neither using `args` nor are we explicitly producing a custom
 * [[ExitCode]]:
 *
 * {{{
 *   object MyApplication extends IOApp.Simple {
 *     val run =
 *       for {
 *         _ <- IO.print("Enter your name: ")
 *         name <- IO.readln
 *         _ <- IO.println(s"Hello, " + name)
 *       } yield ()
 *   }
 * }}}
 *
 * It is valid to define `val run` rather than `def run` because `IO`'s
 * evaluation is lazy: it will only run when the `main` method is
 * invoked by the runtime.
 *
 * In the event that the process receives an interrupt signal (`SIGINT`) due
 * to Ctrl-C (or any other mechanism), it will immediately `cancel` the main
 * fiber. Assuming this fiber is not within an `uncancelable` region, this
 * will result in interrupting any current activities and immediately invoking
 * any finalizers (see: [[IO.onCancel]] and [[IO.bracket]]). The process will
 * not shut down until the finalizers have completed. For example:
 *
 * {{{
 *   object InterruptExample extends IOApp.Simple {
 *     val run =
 *       IO.bracket(startServer)(
 *         _ => IO.never)(
 *         server => IO.println("shutting down") *> server.close)
 *   }
 * }}}
 *
 * If we assume the `startServer` function has type `IO[Server]` (or similar),
 * this kind of pattern is very common. When this process receives a `SIGINT`,
 * it will immediately print "shutting down" and run the `server.close` effect.
 *
 * One consequence of this design is it is possible to build applications which
 * will ignore process interrupts. For example, if `server.close` runs forever,
 * the process will ignore interrupts and will need to be cleaned up using
 * `SIGKILL` (i.e. `kill -9`). This same phenomenon can be demonstrated by using
 * [[IO.uncancelable]] to suppress all interruption within the application
 * itself:
 *
 * {{{
 *   object Zombie extends IOApp.Simple {
 *     val run = IO.never.uncancelable
 *   }
 * }}}
 *
 * The above process will run forever and ignore all interrupts. The only way
 * it will shut down is if it receives `SIGKILL`.
 *
 * It is possible (though not necessary) to override various platform-specific
 * runtime configuration options, such as `computeWorkerThreadCount` (which only
 * exists on the JVM). Please note that the default configurations have been
 * extensively benchmarked and are optimal (or close to it) in most conventional
 * scenarios.
 *
 * However, with that said, there really is no substitute to benchmarking your
 * own application. Every application and scenario is unique, and you will
 * always get the absolute best results by performing your own tuning rather
 * than trusting someone else's defaults. `IOApp`'s defaults are very ''good'',
 * but they are not perfect in all cases. One common example of this is
 * applications which maintain network or file I/O worker threads which are
 * under heavy load in steady-state operations. In such a performance profile,
 * it is usually better to reduce the number of compute worker threads to
 * "make room" for the I/O workers, such that they all sum to the number of
 * physical threads exposed by the kernel.
 *
 * @see [[IO]]
 * @see [[run]]
 * @see [[ResourceApp]]
 * @see [[IOApp.Simple]]
 */
trait IOApp {

  private[this] var _runtime: unsafe.IORuntime = null

  /**
   * The runtime which will be used by `IOApp` to evaluate the
   * [[IO]] produced by the `run` method. This may be overridden
   * by `IOApp` implementations which have extremely specialized
   * needs, but this is highly unlikely to ever be truly needed.
   * As an example, if an application wishes to make use of an
   * alternative compute thread pool (such as `Executors.fixedThreadPool`),
   * it is almost always better to leverage [[IO.evalOn]] on the value
   * produced by the `run` method, rather than directly overriding
   * `runtime`.
   *
   * In other words, this method is made available to users, but its
   * use is strongly discouraged in favor of other, more precise
   * solutions to specific use-cases.
   *
   * This value is guaranteed to be equal to [[unsafe.IORuntime.global]].
   */
  protected def runtime: unsafe.IORuntime = _runtime

  /**
   * The configuration used to initialize the [[runtime]] which will
   * evaluate the [[IO]] produced by `run`. It is very unlikely that
   * users will need to override this method.
   */
  protected def runtimeConfig: unsafe.IORuntimeConfig = unsafe.IORuntimeConfig()

  /**
   * The entry point for your application. Will be called by the runtime
   * when the process is started. If the underlying runtime supports it,
   * any arguments passed to the process will be made available in the
   * `args` parameter. The numeric value within the resulting [[ExitCode]]
   * will be used as the exit code when the process terminates unless
   * terminated exceptionally or by interrupt.
   *
   * @param args The arguments passed to the process, if supported by the
   *        underlying runtime. For example, `java com.company.MyApp --foo --bar baz`
   *        or `node com-mycompany-fastopt.js --foo --bar baz` would each
   *        result in `List("--foo", "--bar", "baz")`.
   * @see [[IOApp.Simple!.run:cats\.effect\.IO[Unit]*]]
   */
  def run(args: List[String]): IO[ExitCode]

  final def main(args: Array[String]): Unit = {
    if (runtime == null) {
      import unsafe.IORuntime

      val installed = IORuntime installGlobal {
        IORuntime(
          IORuntime.defaultComputeExecutionContext,
          IORuntime.defaultComputeExecutionContext,
          IORuntime.defaultScheduler,
          () => (),
          runtimeConfig)
      }

      if (!installed) {
        System
          .err
          .println(
            "WARNING: Cats Effect global runtime already initialized; custom configurations will be ignored")
      }

      _runtime = IORuntime.global
    }

    // An infinite heartbeat to keep main alive.  This is similar to
    // `IO.never`, except `IO.never` doesn't schedule any tasks and is
    // insufficient to keep main alive.  The tick is fast enough that
    // it isn't silently discarded, as longer ticks are, but slow
    // enough that we don't interrupt often.  1 hour was chosen
    // empirically.
    lazy val keepAlive: IO[Nothing] =
      IO.sleep(1.hour) >> keepAlive

    val argList =
      if (js.typeOf(js.Dynamic.global.process) != "undefined" && js.typeOf(
          js.Dynamic.global.process.argv) != "undefined")
        js.Dynamic.global.process.argv.asInstanceOf[js.Array[String]].toList.drop(2)
      else
        args.toList

    Spawn[IO]
      .raceOutcome[ExitCode, Nothing](run(argList), keepAlive)
      .flatMap {
        case Left(Outcome.Canceled()) =>
          IO.raiseError(new CancellationException("IOApp main fiber was canceled"))
        case Left(Outcome.Errored(t)) => IO.raiseError(t)
        case Left(Outcome.Succeeded(code)) => code
        case Right(Outcome.Errored(t)) => IO.raiseError(t)
        case Right(_) => sys.error("impossible")
      }
      .unsafeRunAsync({
        case Left(t) =>
          t match {
            case _: CancellationException =>
              // Do not report cancelation exceptions but still exit with an error code.
              reportExitCode(ExitCode(1))
            case t: Throwable =>
              throw t
          }
        case Right(code) => reportExitCode(code)
      })(runtime)
  }

  private[this] def reportExitCode(code: ExitCode): Unit =
    if (js.typeOf(js.Dynamic.global.process) != "undefined") {
      js.Dynamic.global.process.exitCode = code.code
    }
}

object IOApp {

  /**
   * A simplified version of [[IOApp]] for applications which ignore their
   * process arguments and always produces [[ExitCode.Success]] (unless
   * terminated exceptionally or interrupted).
   *
   * @see [[IOApp]]
   */
  trait Simple extends IOApp {
    def run: IO[Unit]
    final def run(args: List[String]): IO[ExitCode] = run.as(ExitCode.Success)
  }
}
