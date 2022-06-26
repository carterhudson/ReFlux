package com.slothware.reflux.architecture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow

abstract class StateMachineViewModel<StateT : State>(
    initialState: StateT,
    reducers: List<Reducer<StateT>>,
    middlewares: List<Middleware<StateT>> = emptyList(),
    val sideEffect: SideEffect<StateT>
) : ViewModel() {

    val stateMachine: StateMachine<StateT> = createStateMachine(
        initialState = initialState,
        rootReducer = combineReducers(reducers = reducers.toTypedArray()),
        enhancer = combineEnhancers(applyMiddleware(middlewares = middlewares.toTypedArray()))
    )

    val stateFlow: StateFlow<StateT> = stateMachine.state

    val currentState: StateT
        get() = stateFlow.value

    fun dispatch(event: Event) {
        stateMachine.dispatch(event)
        sideEffect.handle(currentState, event, ::dispatch, viewModelScope)
    }
}