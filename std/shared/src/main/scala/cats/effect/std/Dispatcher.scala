/*
 * Copyright 2020 Typelevel
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

package cats.effect.std

import cats.~>
import cats.effect.kernel.{Async, Resource, Sync}
import cats.effect.kernel.syntax.all._
import cats.syntax.all._

import scala.concurrent.{Future, Promise}

import java.util.concurrent.{Semaphore => JSemaphore}
import java.util.concurrent.atomic.AtomicReference

object Dispatcher {

  def apply[F[_]: Async, A](unsafe: (F ~> Future) => F[A]): Resource[F, A] =
    for {
      invokeRef <- Resource.liftF(Sync[F].delay(new AtomicReference[F[Unit] => Unit]))
      invokeLatch <- Resource.liftF(Sync[F].delay(new JSemaphore(1)))
      _ <- Resource.liftF(Sync[F].delay(invokeLatch.acquire()))

      runner = {
        val cont: F[F[Unit]] = Async[F] async_ { cb =>
          invokeRef.set(fu => cb(Right(fu)))
          invokeLatch.release()
        }

        cont.flatten
      }

      _ <- runner.foreverM[Unit].background

      back <- Resource liftF {
        def unsafeToFuture[E](fe: F[E]): Future[E] = {
          val promise = Promise[E]()

          invokeLatch.acquire()
          invokeRef.get() {
            fe.flatMap(e => Sync[F].delay(promise.success(e)))
              .onError { case t => Sync[F].delay(promise.failure(t)) }
              .void
          }

          promise.future
        }

        unsafe(new (F ~> Future) { def apply[E](fe: F[E]) = unsafeToFuture(fe) })
      }
    } yield back
}
