package arrow.effects.reactor

import arrow.Kind
import arrow.core.*
import arrow.effects.ConnectionCancellationException
import arrow.effects.reactor.CoroutineContextReactorScheduler.asScheduler
import arrow.effects.typeclasses.Disposable
import arrow.effects.typeclasses.ExitCase
import arrow.higherkind
import arrow.typeclasses.Applicative
import reactor.core.publisher.Flux
import reactor.core.publisher.FluxSink
import kotlin.coroutines.CoroutineContext

fun <A> Flux<A>.k(): FluxK<A> = FluxK(this)

fun <A> FluxKOf<A>.value(): Flux<A> =
  this.fix().flux

@Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
@higherkind
data class FluxK<A>(val flux: Flux<A>) : FluxKOf<A>, FluxKKindedJ<A> {
  fun <B> map(f: (A) -> B): FluxK<B> =
    flux.map(f).k()

  fun <B> ap(fa: FluxKOf<(A) -> B>): FluxK<B> =
    flatMap { a -> fa.fix().map { ff -> ff(a) } }

  fun <B> flatMap(f: (A) -> FluxKOf<B>): FluxK<B> =
    flux.flatMap { f(it).fix().flux }.k()

  /**
   * A way to safely acquire a resource and release in the face of errors and cancellation.
   * It uses [ExitCase] to distinguish between different exit cases when releasing the acquired resource.
   *
   * @param use is the action to consume the resource and produce an [FluxK] with the result.
   * Once the resulting [FluxK] terminates, either successfully, error or disposed,
   * the [release] function will run to clean up the resources.
   *
   * @param release the allocated resource after the resulting [FluxK] of [use] is terminates.
   *
   * {: data-executable='true'}
   * ```kotlin:ank
   * import reactor.core.publisher.Flux
   * import arrow.effects.reactor.*
   * import arrow.effects.typeclasses.ExitCase
   *
   * class File(url: String) {
   *   fun open(): File = this
   *   fun close(): Unit {}
   *   fun content(): FluxK<String> =
   *     Flux.just("This", "file", "contains", "some", "interesting", "content!").k()
   * }
   *
   * fun openFile(uri: String): FluxK<File> = FluxK { File(uri).open() }
   * fun closeFile(file: File): FluxK<Unit> = FluxK { file.close() }
   *
   * fun main(args: Array<String>) {
   *   //sampleStart
   *   val safeComputation = openFile("data.json").bracketCase(
   *     release = { file, exitCase ->
   *       when (exitCase) {
   *         is ExitCase.Completed -> { /* do something */ }
   *         is ExitCase.Cancelled -> { /* do something */ }
   *         is ExitCase.Error -> { /* do something */ }
   *       }
   *       closeFile(file)
   *     },
   *     use = { file -> file.content() }
   *   )
   *   //sampleEnd
   *   println(safeComputation)
   * }
   *  ```
   */
  fun <B> bracketCase(use: (A) -> FluxKOf<B>, release: (A, ExitCase<Throwable>) -> FluxKOf<Unit>): FluxK<B> =
    flatMap { a ->
      Flux.create<B> { sink ->
        val d = use(a).fix()
          .flatMap { b ->
            release(a, ExitCase.Completed)
              .fix().map { b }
          }.handleErrorWith { e ->
            release(a, ExitCase.Error(e))
              .fix().flatMap { raiseError<B>(e) }
          }.flux.subscribe({ b -> sink.next(b) }, sink::error, sink::complete) { subscription ->
          sink.onRequest(subscription::request)
        }
        sink.onCancel(d)
        sink.onDispose { release(a, ExitCase.Cancelled).fix().flux.subscribe({}, sink::error, {}) }
      }.k()
    }

  fun <B> concatMap(f: (A) -> FluxKOf<B>): FluxK<B> =
    flux.concatMap { f(it).fix().flux }.k()

  fun <B> switchMap(f: (A) -> FluxKOf<B>): FluxK<B> =
    flux.switchMap { f(it).fix().flux }.k()

  fun <B> foldLeft(b: B, f: (B, A) -> B): B = flux.reduce(b, f).block()

  fun <B> foldRight(lb: Eval<B>, f: (A, Eval<B>) -> Eval<B>): Eval<B> {
    fun loop(fa_p: FluxK<A>): Eval<B> = when {
      fa_p.flux.hasElements().map { !it }.block() -> lb
      else -> f(fa_p.flux.blockFirst(), Eval.defer { loop(fa_p.flux.skip(1).k()) })
    }

    return Eval.defer { loop(this) }
  }

  fun <G, B> traverse(GA: Applicative<G>, f: (A) -> Kind<G, B>): Kind<G, FluxK<B>> =
    foldRight(Eval.always { GA.just(Flux.empty<B>().k()) }) { a, eval ->
      GA.run { f(a).map2Eval(eval) { Flux.concat(Flux.just<B>(it.a), it.b.flux).k() } }
    }.value()

  fun handleErrorWith(function: (Throwable) -> FluxK<A>): FluxK<A> =
    this.fix().flux.onErrorResume { t: Throwable -> function(t).flux }.k()

  fun continueOn(ctx: CoroutineContext): FluxK<A> =
    flux.publishOn(ctx.asScheduler()).k()

  fun runAsync(cb: (Either<Throwable, A>) -> FluxKOf<Unit>): FluxK<Unit> =
    flux.flatMap { cb(Right(it)).value() }.onErrorResume { cb(Left(it)).value() }.k()

  fun runAsyncCancellable(cb: (Either<Throwable, A>) -> FluxKOf<Unit>): FluxK<Disposable> =
    Flux.defer {
      val disposable: reactor.core.Disposable = runAsync(cb).value().subscribe()
      val dispose: Disposable = { disposable.dispose() }
      Flux.just(dispose)
    }.k()

  override fun equals(other: Any?): Boolean =
    when (other) {
      is FluxK<*> -> this.flux == other.flux
      is Flux<*> -> this.flux == other
      else -> false
    }

  override fun hashCode(): Int = flux.hashCode()

  companion object {
    fun <A> just(a: A): FluxK<A> =
      Flux.just(a).k()

    fun <A> raiseError(t: Throwable): FluxK<A> =
      Flux.error<A>(t).k()

    operator fun <A> invoke(fa: () -> A): FluxK<A> =
      defer { just(fa()) }

    fun <A> defer(fa: () -> FluxKOf<A>): FluxK<A> =
      Flux.defer { fa().value() }.k()

    /**
     * Creates a [FluxK] that'll run [FluxKProc].
     *
     * {: data-executable='true'}
     *
     * ```kotlin:ank
     * import arrow.core.Either
     * import arrow.core.right
     * import arrow.effects.reactor.FluxK
     * import arrow.effects.reactor.FluxKConnection
     * import arrow.effects.reactor.value
     *
     * class Resource {
     *   fun asyncRead(f: (String) -> Unit): Unit = f("Some value of a resource")
     *   fun close(): Unit = Unit
     * }
     *
     * fun main(args: Array<String>) {
     *   //sampleStart
     *   val result = FluxK.async { conn: FluxKConnection, cb: (Either<Throwable, String>) -> Unit ->
     *     val resource = Resource()
     *     conn.push(FluxK { resource.close() })
     *     resource.asyncRead { value -> cb(value.right()) }
     *   }
     *   //sampleEnd
     *   result.value().subscribe(::println)
     * }
     * ```
     */
    fun <A> async(fa: FluxKProc<A>): FluxK<A> =
      Flux.create<A> { sink ->
        val conn = FluxKConnection()
        //On disposing of the upstream stream this will be called by `setCancellable` so check if upstream is already disposed or not because
        //on disposing the stream will already be in a terminated state at this point so calling onError, in a terminated state, will blow everything up.
        conn.push(FluxK { if (!sink.isCancelled) sink.error(ConnectionCancellationException) })
        sink.onCancel { conn.cancel().value().subscribe() }

        fa(conn) { callback: Either<Throwable, A> ->
          callback.fold({
            sink.error(it)
          }, {
            sink.next(it)
            sink.complete()
          })
        }
      }.k()

    fun <A> asyncF(fa: FluxKProcF<A>): FluxK<A> =
      Flux.create { sink: FluxSink<A> ->
        val conn = FluxKConnection()
        //On disposing of the upstream stream this will be called by `setCancellable` so check if upstream is already disposed or not because
        //on disposing the stream will already be in a terminated state at this point so calling onError, in a terminated state, will blow everything up.
        conn.push(FluxK { if (!sink.isCancelled) sink.error(ConnectionCancellationException) })
        sink.onCancel { conn.cancel().value().subscribe() }

        fa(conn) { callback: Either<Throwable, A> ->
          callback.fold({
            sink.error(it)
          }, {
            sink.next(it)
            sink.complete()
          })
        }.fix().flux.subscribe({}, sink::error)
      }.k()

    tailrec fun <A, B> tailRecM(a: A, f: (A) -> FluxKOf<Either<A, B>>): FluxK<B> {
      val either = f(a).value().blockFirst()
      return when (either) {
        is Either.Left -> tailRecM(either.a, f)
        is Either.Right -> Flux.just(either.b).k()
      }
    }

  }
}

fun <A, G> FluxKOf<Kind<G, A>>.sequence(GA: Applicative<G>): Kind<G, FluxK<A>> =
  fix().traverse(GA, ::identity)
