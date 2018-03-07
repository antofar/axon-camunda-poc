package com.plexiti.horizon.model

import com.plexiti.horizon.model.api.*
import com.plexiti.integration.*
import org.axonframework.commandhandling.CommandBus
import org.axonframework.commandhandling.CommandCallback
import org.axonframework.commandhandling.CommandMessage
import org.axonframework.commandhandling.GenericCommandMessage
import org.axonframework.eventhandling.EventBus
import org.axonframework.eventhandling.GenericEventMessage
import org.axonframework.eventhandling.saga.EndSaga
import org.axonframework.eventhandling.saga.SagaEventHandler
import org.axonframework.eventhandling.saga.SagaLifecycle
import org.axonframework.eventhandling.saga.StartSaga
import org.axonframework.queryhandling.GenericQueryMessage
import org.axonframework.queryhandling.QueryBus
import org.axonframework.spring.stereotype.Saga
import org.camunda.bpm.engine.ProcessEngine
import org.camunda.bpm.engine.ProcessEngineException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.jvm.jvmErasure

@Saga
class PaymentSaga {

    private val logger = LoggerFactory.getLogger(PaymentSaga::class.java)

    @Autowired
    @Transient
    private lateinit var commandBus: CommandBus

    @Autowired
    @Transient
    private lateinit var eventBus: EventBus

    @Autowired
    @Transient
    private lateinit var queryBus: QueryBus

    @Autowired
    @Transient
    private lateinit var processEngine: ProcessEngine

    @SagaEventHandler(associationProperty = "processInstanceId")
    fun on(event: CommandIssued) {

        val message = createMessage(event.messageName)

        commandBus.dispatch(GenericCommandMessage(message), object: CommandCallback<Any, Any> {

            override fun onSuccess(commandMessage: CommandMessage<out Any>?, result: Any?) {
                var done = false
                do {
                    try {
                        Thread.sleep(25)
                        processEngine.runtimeService.signal(event.executionId, null, null, variables())
                        done = true
                    } catch (e: ProcessEngineException) {
                    }
                } while (!done)
            }

            override fun onFailure(commandMessage: CommandMessage<out Any>?, cause: Throwable) {
                var done = false
                do {
                    try {
                        Thread.sleep(25)
                        processEngine.runtimeService.signal(event.executionId, cause::class.java.canonicalName, cause.message, null)
                        done = true
                    } catch (e: ProcessEngineException) {
                    }
                } while (!done)
            }

        })

    }

    @SagaEventHandler(associationProperty = "processInstanceId")
    fun on(event: EventRaised) {

        val message = createMessage(event.messageName)
        eventBus.publish(GenericEventMessage(message))

    }

    @SagaEventHandler(associationProperty = "processInstanceId")
    fun on(event: QueryRequested) {

        val message = createMessage(event.messageName)
        val q = queryBus.query(GenericQueryMessage(message, returnType(message::class).java))
        val result = q.get()
        handleResponse(result)
        var done = false
        do {
            try {
                Thread.sleep(25)
                processEngine.runtimeService.signal(event.executionId, null, null, variables())
                done = true
            } catch (e: ProcessEngineException) {
            }
        } while (!done)
        logger.debug(result.toString())

    }

    private fun createMessage(type: String): Any {
        val re = Class.forName(type).kotlin
        val factoryMethod = this::class.memberFunctions.find {
            val returnTypeOfMethod = it.returnType.jvmErasure
            val isSagaCommandFactory = it.findAnnotation<SagaCommandFactory>() != null
            val isSagaEventFactory = it.findAnnotation<SagaEventFactory>() != null
            val isSagaQueryFactory = it.findAnnotation<SagaQueryFactory>() != null
            returnTypeOfMethod.equals(re) && (isSagaCommandFactory || isSagaEventFactory || isSagaQueryFactory)
        }!!
        return factoryMethod.call(this)!!
    }

    private fun handleResponse(response: Any) {
        val responseHandlingMethod = this::class.memberFunctions.find {
            val parameterType = if (it.parameters.size == 2) it.parameters[1].type else null
            val isSagaResponseHandler = it.findAnnotation<SagaResponseHandler>() != null
            parameterType != null && parameterType.jvmErasure.equals(response::class) && isSagaResponseHandler
        }
        if (responseHandlingMethod == null)
            throw IllegalArgumentException("No handler found for response $response!")
        responseHandlingMethod.call(this, response)
    }

    private fun returnType(type: KClass<*>): KClass<*> {
        this::class.memberFunctions.forEach {
            val returnTypeOfMethod = it.returnType.jvmErasure
            val sagaQueryFactory = it.findAnnotation<SagaQueryFactory>()
            if (returnTypeOfMethod.equals(type) && sagaQueryFactory != null) {
                return sagaQueryFactory.responseType
            }
        }
        throw IllegalArgumentException()
    }

    private fun attachProcessInstance(type: String) {
        val instance = processEngine.runtimeService
            .startProcessInstanceByKey(type, variables())
        SagaLifecycle.associateWith("processInstanceId", instance.processInstanceId)
    }

    fun variables(): Map<String, Any> { // TODO generify
        return mapOf (
            "creditAvailable" to (creditAvailable > 0F),
            "creditFullyCovering" to (creditAvailable >= paymentAmount)
        )
    }

    // This is the only saga code necessary once a proper integration Axon / Camunda exists

    private lateinit var paymentId: PaymentId
    private lateinit var accountId: AccountId
    private var paymentAmount: Float = 0F
    private var creditAvailable = 0F
    private var amountWithdrawn: Float = 0F
    private var creditCardExpired = false

    @StartSaga
    @SagaEventHandler(associationProperty = "accountId")
    fun on(event: PaymentCreated) {
        logger.debug(event.toString())
        accountId = event.accountId
        paymentId = event.paymentId
        paymentAmount = event.amount
        creditCardExpired = accountId.id == "kermit"
        attachProcessInstance("PaymentSaga")
    }

    @SagaQueryFactory(responseType = AccountSummary::class)
    fun checkBalance(): DocumentAccountSummary {
        val query = DocumentAccountSummary(accountId)
        logger.debug(query.toString())
        return query
    }

    @SagaCommandFactory
    fun chargeCreditCard(): ChargeCreditCard {
        val command = ChargeCreditCard(accountId, paymentAmount - amountWithdrawn, creditCardExpired)
        logger.debug(command.toString())
        return command
    }

    @SagaCommandFactory
    fun withdrawAmount(): WithdrawAmount {
        val command = WithdrawAmount(accountId, amountWithdrawn)
        logger.debug(command.toString())
        return command
    }

    @SagaCommandFactory
    fun creditAmount(): CreditAmount {
        val command = CreditAmount(accountId, amountWithdrawn)
        logger.debug(command.toString())
        return command
    }

    @SagaEventFactory
    fun paymentReceived(): PaymentReceived {
        val event = PaymentReceived(paymentId, accountId, paymentAmount)
        logger.debug(event.toString())
        return event
    }

    @SagaEventFactory
    fun paymentNotReceived(): PaymentNotReceived {
        val event = PaymentNotReceived(paymentId, accountId, paymentAmount - amountWithdrawn)
        logger.debug(event.toString())
        return event
    }

    @SagaEventFactory
    fun paymentFullyCoveredByAccount(): PaymentFullyCoveredByAccount {
        val event = PaymentFullyCoveredByAccount(paymentId, accountId, amountWithdrawn)
        logger.debug(event.toString())
        return event
    }

    @SagaEventFactory
    fun paymentPartlyCoveredByAccount(): PaymentPartlyCoveredByAccount {
        val event = PaymentPartlyCoveredByAccount(paymentId, accountId, amountWithdrawn)
        logger.debug(event.toString())
        return event
    }

    @SagaEventFactory
    fun updateCreditCardReminded(): UpdateCreditCardReminded {
        val event = UpdateCreditCardReminded(accountId)
        logger.debug(event.toString())
        return event
    }

    @SagaResponseHandler
    fun handle(accountSummary: AccountSummary) {
        logger.debug(accountSummary.toString())
        creditAvailable = accountSummary.balance
        amountWithdrawn = if (creditAvailable > paymentAmount) paymentAmount else creditAvailable
    }

    @SagaEventHandler(associationProperty = "accountId")
    fun on(event: CreditCardDetailsUpdated) {
        logger.debug(event.toString())
        creditCardExpired = false
    }

    @EndSaga
    @SagaEventHandler(associationProperty = "accountId")
    fun on(event: PaymentReceived) {
        logger.debug(event.toString())
    }

}