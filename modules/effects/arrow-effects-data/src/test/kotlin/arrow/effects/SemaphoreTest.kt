package arrow.effects

import arrow.core.extensions.eq
import arrow.core.toT
import arrow.data.extensions.list.traverse.traverse
import arrow.effects.extensions.io.applicative.applicative
import arrow.effects.extensions.io.applicativeError.handleError
import arrow.effects.extensions.io.async.async
import arrow.effects.extensions.io.concurrent.concurrent
import arrow.effects.extensions.io.concurrent.parMapN
import arrow.effects.extensions.io.functor.unit
import arrow.effects.extensions.io.monad.flatMap
import arrow.effects.extensions.io.monad.map
import arrow.test.UnitSpec
import arrow.test.laws.equalUnderTheLaw
import io.kotlintest.properties.Gen
import io.kotlintest.properties.forAll
import io.kotlintest.runner.junit4.KotlinTestRunner
import kotlinx.coroutines.Dispatchers
import org.junit.runner.RunWith

@RunWith(KotlinTestRunner::class)
class SemaphoreTest : UnitSpec() {


  init {

    fun tests(label: String, semaphore: (Long) -> IOOf<Semaphore<ForIO>>): Unit {
      "$label - acquire n synchronously" {
        val n = 20L
        semaphore(n).flatMap { s ->
          (0 until n).toList().traverse(IO.applicative()) { s.acquire() }.flatMap {
            s.available()
          }
        }.equalUnderTheLaw(IO.just(0L), EQ())
      }

      "$label - tryAcquire with available permits" {
        val n = 20
        semaphore(20).flatMap { s ->
          (0 until n).toList().traverse(IO.applicative()) { s.acquire() }.flatMap {
            s.tryAcquire()
          }
        }.equalUnderTheLaw(IO.just(true), EQ())
      }

      "$label - tryAcquire with no available permits" {
        val n = 20
        semaphore(n.toLong()).flatMap { s ->
          (0 until n).toList().traverse(IO.applicative()) { s.acquire() }.flatMap {
            s.tryAcquire()
          }
        }.equalUnderTheLaw(IO.just(false), EQ())
      }

      "$label - available with available permits" {
        semaphore(20).flatMap { s ->
          s.acquireN(19).flatMap {
            s.available()
          }
        }.equalUnderTheLaw(IO.just(1L), EQ())
      }

      "$label - available with no available permits" {
        semaphore(20).flatMap { s ->
          s.acquireN(20).flatMap {
            s.available()
          }
        }.equalUnderTheLaw(IO.just(0L), EQ())
      }

      "$label - tryAcquireN with no available permits" {
        semaphore(20).flatMap { s ->
          s.acquireN(20).flatMap {
            s.tryAcquireN(1)
          }
        }.equalUnderTheLaw(IO.just(false), EQ())
      }

      "$label - count with available permits" {
        val n = 18
        semaphore(20).flatMap { s ->
          (0 until n).toList().traverse(IO.applicative()) { s.acquire() }.flatMap {
            s.available().flatMap { available ->
              s.count().map { count -> available toT count }
            }
          }
        }
          .map { (available, count) -> available == count }
          .unsafeRunSync()
      }

      "$label - count with no available permits" {
        semaphore(20).flatMap { s ->
          s.acquireN(20).flatMap {
            s.count()
          }
        }.equalUnderTheLaw(IO.just(0L), EQ())
      }


      "$label - negative number of permits" {
        forAll(Gen.negativeIntegers().map(Int::toLong)) { i ->
          semaphore(i)
            .map { false }
            .handleError { true }
            .unsafeRunSync()
        }
      }

      "$label - withPermit" {
        forAll(Gen.positiveIntegers().map(Int::toLong)) { i ->
          semaphore(i).flatMap { s ->
            s.available().flatMap { current ->
              s.withPermit(IO.defer {
                s.available().map { it == current - 1L }
              }).flatMap { didAcquire ->
                IO.defer {
                  s.available().map { it == current && didAcquire }
                }
              }
            }
          }.unsafeRunSync()
        }
      }


      "$label - offsetting acquires/releases - acquires parallel with releases" {
        val permits: List<Long> = listOf(1, 0, 20, 4, 0, 5, 2, 1, 1, 3)
        semaphore(0).flatMap { s ->
          parMapN(Dispatchers.Default,
            permits.traverse(IO.applicative()) { s.acquireN(it) }.unit(),
            permits.reversed().traverse(IO.applicative()) { s.releaseN(it) }.unit()
          ) { _, _ -> Unit }
            .flatMap {
              s.count()
            }
        }.map { count -> count.equalUnderTheLaw(0L, Long.eq()) }
          .unsafeRunSync()
      }

    }

    tests("UncancelableSemaphore") { Semaphore.uncancelable(it, IO.async()) }
    tests("CancelableSemaphore") { Semaphore(it, IO.concurrent()) }

    "CancelableSemaphore - supports cancellation of acquire" {
      Semaphore(0, IO.concurrent()).flatMap { s ->
        s.acquire()
      }.unsafeRunAsyncCancellable { }
        .invoke()
    }

  }

}
