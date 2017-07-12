package com.template

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.*
import net.corda.core.crypto.newSecureRandom
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.flows.CollectSignaturesFlow
import net.corda.flows.FinalityFlow
import net.corda.flows.SignTransactionFlow

/**
 * Create a generic bilateral agreement.
 */
class Issue(override val nonce: Long = newSecureRandom().nextLong()): IssueCommand
class Update: CommandData

class CreateBilateralAgreementFlow(val state: ContractState,
                                   override val progressTracker: ProgressTracker = tracker()) : FlowLogic<SignedTransaction>() {
    companion object {
        object CREATING : ProgressTracker.Step("Creating transaction")
        object SIGNING : ProgressTracker.Step("Signing transaction")
        object COLLECTING : ProgressTracker.Step("Collecting other signatures") {
            override fun childProgressTracker() = CollectSignaturesFlow.tracker()
        }

        object FINALISING : ProgressTracker.Step("Finalising transaction") {
            override fun childProgressTracker() = FinalityFlow.tracker()
        }

        fun tracker() = ProgressTracker(CREATING, SIGNING, COLLECTING, FINALISING)
    }

    @Suspendable
    override fun call(): SignedTransaction {
        progressTracker.currentStep = CREATING
        val notary = serviceHub.networkMapCache.getAnyNotary()
        val command = Command(Issue(), state.participants.map { it.owningKey })
        val utx = TransactionBuilder(TransactionType.General, notary).withItems(command, state)
        progressTracker.currentStep = SIGNING
        val ptx = serviceHub.signInitialTransaction(utx)
        progressTracker.currentStep = COLLECTING
        val stx = subFlow(CollectSignaturesFlow(ptx, COLLECTING.childProgressTracker()))
        progressTracker.currentStep = FINALISING
        return subFlow(FinalityFlow(stx, FINALISING.childProgressTracker())).single()
    }
}

/**
 * Update a generic bilateral agreement.
 */
class UpdateBilateralAgreement<out T : ContractState>(val old: StateAndRef<T>,
                                                      val new: ContractState,
                                                      override val progressTracker: ProgressTracker = tracker()): FlowLogic<SignedTransaction>() {
    companion object {
        object CREATING : ProgressTracker.Step("Creating transaction")
        object SIGNING : ProgressTracker.Step("Signing transaction")
        object COLLECTING : ProgressTracker.Step("Collecting other signatures") {
            override fun childProgressTracker() = CollectSignaturesFlow.tracker()
        }

        object FINALISING : ProgressTracker.Step("Finalising transaction") {
            override fun childProgressTracker() = FinalityFlow.tracker()
        }

        fun tracker() = ProgressTracker(CREATING, SIGNING, COLLECTING, FINALISING)
    }

    @Suspendable
    override fun call(): SignedTransaction {
        progressTracker.currentStep = CREATING
        val notary = serviceHub.networkMapCache.getAnyNotary()
        val command = Command(Update(), old.state.data.participants.map { it.owningKey })
        val utx = TransactionBuilder(notary = notary).withItems(command, new, old)
        progressTracker.currentStep = SIGNING
        val ptx = serviceHub.signInitialTransaction(utx)
        progressTracker.currentStep = COLLECTING
        val stx = subFlow(CollectSignaturesFlow(ptx, COLLECTING.childProgressTracker()))
        progressTracker.currentStep = FINALISING
        return subFlow(FinalityFlow(stx, FINALISING.childProgressTracker())).single()
    }
}

/**
 * Flow for the other side of the above two flows.
 */
abstract class SignBilateralAgremeent(val otherParty: Party): FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val flow = object : SignTransactionFlow(otherParty) {
            override fun checkTransaction(stx: SignedTransaction) = performChecks(stx)
        }
        val stx = subFlow(flow)
        return waitForLedgerCommit(stx.id)
    }

    abstract fun performChecks(stx: SignedTransaction)
}