package com.template

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.*
import net.corda.core.contracts.Requirements.using
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.*
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.node.services.queryBy
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.ProgressTracker

class PartnershipContract: Contract {
    override val legalContractReference: SecureHash = SecureHash.zeroHash
    override fun verify(tx: TransactionForContract) {
        val command = tx.commands.single()
        when (command.value) {
            is Issue -> requireThat {
                "Must be no inputs" using (tx.inputs.isEmpty())
                "Must be one output" using (tx.outputs.size == 1)
                val output = tx.outputs.single() as PartnershipAgreement
                "Must be at least two partners" using (output.partners.size > 1)
                "Must be no projects for a new agreement" using (output.projects.isEmpty())
                "All partners must sign agreement" using
                        (command.signers.toSet() == output.partners.map { it.owningKey}.toSet())
            }
            is Update -> {
                "Must be one input" using (tx.inputs.size == 1)
                "Must be one output" using (tx.outputs.size == 1)
                val input = tx.inputs.single() as PartnershipAgreement
                val output = tx.outputs.single() as PartnershipAgreement
                "Can add one project at a time" using (output.projects.size == input.projects.size + 1)
                "Project names must start with 'Project ...'" using (output.projects.last().startsWith("Project "))
                "All partners must approve projects" using
                        (command.signers.toSet() == output.partners.map { it.owningKey}.toSet())
            }
            else -> throw Exception("Unrecognised command!")
        }
    }
}

data class PartnershipAgreement(val partners: Set<Party>, val projects: List<String>): ContractState {
    override val contract: Contract get() = PartnershipContract()
    override val participants: List<AbstractParty> get() = partners.toList()
    fun doProject(projectName: String) = copy(projects = projects + projectName)
}

@InitiatingFlow
@StartableByRPC
class ProposePartnership(val partner: Party): FlowLogic<SignedTransaction>() {
    companion object {
        object PROPOSING : ProgressTracker.Step("Proposing partnership") {
            override fun childProgressTracker() = CreateBilateralAgreementFlow.tracker()
        }

        fun tracker() = ProgressTracker(PROPOSING)
    }

    override val progressTracker: ProgressTracker = tracker()

    @Suspendable
    override fun call(): SignedTransaction {
        val me = serviceHub.myInfo.legalIdentity
        val state = PartnershipAgreement(setOf(me, partner), emptyList())
        progressTracker.currentStep = PROPOSING
        return subFlow(CreateBilateralAgreementFlow(state, PROPOSING.childProgressTracker()))
    }
}

@InitiatedBy(ProposePartnership::class)
class ApprovePartnership(val otherParty: Party): FlowLogic<SignedTransaction>() {
    override val progressTracker: ProgressTracker = ProgressTracker()

    @Suspendable
    override fun call(): SignedTransaction {
        val flow = object : SignBilateralAgremeent(otherParty) {
            override fun performChecks(stx: SignedTransaction) = requireThat {
                "Received a transaction that doesn't concern a new partnership agreement." using
                        (stx.tx.outputs.single().data is PartnershipAgreement)
            }
        }
        return subFlow(flow)
    }
}

@InitiatingFlow
@StartableByRPC
class ProposeProject(val name: String, val partner: Party): FlowLogic<SignedTransaction>() {
    companion object {
        object PROPOSING : ProgressTracker.Step("Proposing project") {
            override fun childProgressTracker() = UpdateBilateralAgreement.tracker()
        }

        fun tracker() = ProgressTracker(PROPOSING)
    }

    override val progressTracker: ProgressTracker = tracker()

    @Suspendable
    override fun call(): SignedTransaction {
        val me = serviceHub.myInfo.legalIdentity
        val oldState = serviceHub.vaultQueryService.queryBy<PartnershipAgreement>().states.find { result ->
            result.state.data.partners == setOf(me, partner)
        } ?: throw FlowException("${setOf(me, partner)} are not yet partners!")
        val newState = oldState.state.data.doProject(name)
        progressTracker.currentStep = PROPOSING
        return subFlow(UpdateBilateralAgreement(oldState, newState, PROPOSING.childProgressTracker()))
    }
}

@InitiatedBy(ProposeProject::class)
class CollaborateOnProject(val otherParty: Party): FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val flow = object : SignBilateralAgremeent(otherParty) {
            override fun performChecks(stx: SignedTransaction) = requireThat {
                "Received a transaction that doesn't concern a partnership agreement." using
                        (stx.tx.outputs.single().data is PartnershipAgreement)
            }
        }
        return subFlow(flow)
    }
}