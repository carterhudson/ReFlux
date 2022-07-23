package com.slothware.reflux.architecture

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

interface ReFluxState
interface ReFluxEvent
interface ReFluxError

// Returns an event to aid in composing dispatch functions for purposes of enhancers, like middleware
typealias DispatchFunction = (ReFluxEvent) -> ReFluxEvent

typealias ViewModelDispatchFunction = (ReFluxEvent) -> Unit

fun interface Reducer<StateT : ReFluxState> {
    fun reduce(state: StateT, event: ReFluxEvent): StateT
}

inline fun <StateT : ReFluxState, reified EventT : ReFluxEvent> reducerForEvent(
    crossinline reduce: (state: StateT, event: EventT) -> StateT
): Reducer<StateT> =
    Reducer { state, event ->
        when (event) {
            is EventT -> reduce(state, event)
            else -> state
        }
    }

fun <StateT : ReFluxState> combineReducers(vararg reducers: Reducer<StateT>): Reducer<StateT> =
    Reducer { state, event ->
        reducers.fold(state) { accumulatedState, nextReducer ->
            nextReducer.reduce(
                accumulatedState,
                event
            )
        }
    }

fun interface SideEffect<StateT : ReFluxState> {
    fun handle(
        state: StateT,
        event: ReFluxEvent,
        dispatch: ViewModelDispatchFunction,
        scope: CoroutineScope
    )
}

inline fun <StateT : ReFluxState, reified EventT : ReFluxEvent> sideEffectForEvent(
    crossinline handle: (
        state: StateT,
        event: EventT,
        dispatch: ViewModelDispatchFunction,
        scope: CoroutineScope
    ) -> Unit
): SideEffect<StateT> =
    SideEffect { state, event, dispatch, scope ->
        when (event) {
            is EventT -> handle(state, event, dispatch, scope)
        }
    }

fun <StateT : ReFluxState> combineSideEffects(vararg sideEffects: SideEffect<StateT>): SideEffect<StateT> =
    SideEffect { state, event, dispatch, scope ->
        sideEffects.forEach { it.handle(state, event, dispatch, scope) }
    }

typealias StateGetter<StateT> = () -> StateT

typealias Middleware<StateT> = (StateGetter<StateT>) -> ((DispatchFunction) -> (DispatchFunction))

interface StateMachine<StateT : ReFluxState> {
    val state: StateFlow<StateT>
    var dispatch: DispatchFunction
    val getState: StateGetter<StateT>
}

typealias StateMachineCreator<StateT> = (
    state: StateT,
    reducer: Reducer<StateT>,
    enhancer: Any?
) -> StateMachine<StateT>

private fun <StateT : ReFluxState> createStateMachine(
    initialState: StateT,
    rootReducer: Reducer<StateT>
) = object : StateMachine<StateT> {

    private val stateInternal: MutableStateFlow<StateT> = MutableStateFlow(initialState)

    override val state: StateFlow<StateT> = stateInternal

    override val getState: StateGetter<StateT> = {
        state.value
    }

    override var dispatch: DispatchFunction = { event ->
        stateInternal.update { currentState ->
            rootReducer.reduce(currentState, event)
        }

        event
    }
}

typealias StateMachineEnhancer<StateT> = (StateMachineCreator<StateT>) -> StateMachineCreator<StateT>

fun <StateT : ReFluxState> createStateMachine(
    initialState: StateT,
    rootReducer: Reducer<StateT>,
    enhancer: StateMachineEnhancer<StateT>? = null,
): StateMachine<StateT> {
    if (enhancer == null) {
        return createStateMachine(
            initialState = initialState,
            rootReducer = rootReducer
        )
    }

    return enhancer { state, reducer, _ ->
        createStateMachine(
            initialState = state,
            rootReducer = reducer
        )
    }(initialState, rootReducer, null)
}

fun <StateT : ReFluxState> combineEnhancers(
    vararg enhancers: StateMachineEnhancer<StateT>
): StateMachineEnhancer<StateT> = { creator ->
    enhancers.fold(creator) { composedCreator, enhancer ->
        enhancer(composedCreator)
    }
}

/**
 * Creates a [StateMachine] with one or many [StateMachineEnhancer]s.
 *
 * @param StateT any type that extends [ReFluxState]
 * @param initialState the starting [ReFluxState]
 * @param reducers list of [Reducer]s that calculate [ReFluxState]s
 * @param enhancers list of enhancers that can alter behavior of a [StateMachine] via composition
 * @return a [StateMachine]
 */
fun <StateT : ReFluxState> createStateMachine(
    initialState: StateT,
    reducers: List<Reducer<StateT>>,
    enhancers: List<StateMachineEnhancer<StateT>>? = null,
): StateMachine<StateT> = createStateMachine(
    initialState = initialState,
    rootReducer = combineReducers(reducers = reducers.toTypedArray()),
    enhancer = enhancers?.toTypedArray()?.let(::combineEnhancers)
)

/**
 * Convenience method for creating middleware a flat, instead of curried, signature.
 *
 * @param StateT any type that extends [ReFluxState]
 * @param combinedSignature the un-curried middleware function
 * @return
 */
fun <StateT : ReFluxState> createMiddleware(
    combinedSignature: (StateGetter<StateT>, DispatchFunction, ReFluxEvent) -> ReFluxEvent
): Middleware<StateT> =
    { getState ->
        { nextDispatchWrapper -> // takes a dispatch function, returns a dispatch function
            { event -> // the returned dispatch function
                combinedSignature(getState, nextDispatchWrapper, event)
            }
        }
    }

/**
 * Function that creates a [StateMachineEnhancer] that can be used to apply [Middleware]
 * behavior to a [StateMachine]
 *
 * @param StateT any type that extends [ReFluxState]
 * @param middlewares list of [Middleware] instances
 * @return a [StateMachineEnhancer]
 */
fun <StateT : ReFluxState> applyMiddleware(vararg middlewares: Middleware<StateT>): StateMachineEnhancer<StateT> =
    { stateMachineCreator ->
        { state, reducer, enhancer ->
            val stateMachine = stateMachineCreator(state, reducer, enhancer)

            middlewares
                .map { middleware ->
                    // call the first function in the curried middleware to obtain the dispatch wrapper
                    middleware(stateMachine.getState)
                }
                .let { dispatchWrappers -> // explicitly shows that this is a list of dispatch wrappers
                    dispatchWrappers.foldRight(stateMachine.dispatch) { nextDispatchWrapper, composedDispatchFunction ->
                        nextDispatchWrapper(composedDispatchFunction)
                    }
                }
                .let { dispatchWithMiddleware ->
                    stateMachine.apply {
                        stateMachine.dispatch = dispatchWithMiddleware
                    }
                }
        }
    }

// Middleware that invokes the block before the event is dispatched to reducers
fun <StateT : ReFluxState> eventPreProcessor(block: (state: StateT, event: ReFluxEvent) -> Unit): Middleware<StateT> =
    createMiddleware { getState, dispatch, event ->
        block(getState(), event)
        dispatch(event)
    }

// Middleware that invokes the block after the event is dispatched to reducers
fun <StateT : ReFluxState> eventPostProcessor(block: (state: StateT, event: ReFluxEvent) -> Unit): Middleware<StateT> =
    createMiddleware { getState, dispatch, event ->
        dispatch(event).also {
            block(getState(), event)
        }
    }
